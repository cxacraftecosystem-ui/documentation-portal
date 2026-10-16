"""THE PASSWORD LINK: a one-off, single-use address that lets somebody set their own password.

Ported on 2026-10-16 from the Design Prototype Workshop's ``backend/app/services/credential_links.py``
(same names, same token construction, same table), which ported it from
``C:/dev/cxa-cms/lib/auth/credential-token.ts``, which is the canonical minting of this
construction. Read that file before changing anything here; the four properties it argues for are
kept intact and three of them are the reason this is not a JWT.

── WHAT WAS PORTED, VERBATIM IN SUBSTANCE ────────────────────────────────────────────────────────

1. **NOT A SESSION TOKEN.** ``core.security.create_access_token`` mints the credential the whole API
   authorises against. Anything shaped like one risks being accepted as one by a call site that
   checks only a signature, so this is a different construction — ``base64url(payload) "."
   base64url(HMAC-SHA256(payload))`` — that no bearer-token path can parse.
2. **THE FINGERPRINT, WHICH IS WHAT MAKES IT SINGLE-USE.** The payload carries 16 hex characters of
   a SHA-256 of the account's password hash at minting time. Setting a password changes the hash,
   so every link minted before it stops verifying — immediately, with nothing having to remember.
   An account with NO password digests a fixed sentinel, so an invitation is bound to "still has no
   password". The digest is never enough to attack the hash and is enough to notice a change.
3. **EXPIRY IS INSIDE THE SIGNED PAYLOAD**, so a stale link is refused without a database read, and
   the reason can be reported honestly to somebody holding a link that has simply gone cold.
4. **SESSION REVOCATION ON RESET.** Redeeming a link writes ``User.sessionsValidFrom``, which
   ``deps._user_from_bearer`` compares every token's ``iat`` against. The reason somebody is
   resetting is usually that a session they no longer control exists somewhere. Since 2026-10-09
   :func:`credential_fingerprint` binds the bearer tokens too (``security.CREDENTIAL_CLAIM``), so a
   new password retires the sessions opened with the old one whichever door wrote it — one
   definition of "the password changed", for links and for sessions.

── WHAT WAS DELIBERATELY NOT PORTED ──────────────────────────────────────────────────────────────

**cxa-cms HAS NO THROTTLE ON ISSUING, AND THAT IS A HOLE THIS DOES NOT COPY.** Because minting is
free and redeeming revokes sessions, an administrator there can sign a colleague out of their own
laptop as often as they can click. :data:`ISSUE_BUDGET` and :data:`ISSUE_WINDOW_HOURS` close it:
per-SUBJECT, not per-admin, because the person being harmed is the subject and two admins taking
turns is the same harm.

**THE TOKEN IS NEVER RETURNED AS A SEPARATE FIELD BESIDE THE LINK.** cxa-cms's canonical route
refuses to, on the stated grounds that a credential appearing twice in one answer is a credential in
two places to keep out of logs. Honoured: :func:`issue_link` hands back the LINK, and the token
exists inside it.

── THE ONE THING THAT IS NEW HERE: A TABLE ───────────────────────────────────────────────────────

cxa-cms is deliberately tableless. ``PasswordResetToken`` exists here because the owner asked for
revocation, a throttle and an audit trail, and a stateless token can express none of the three — see
that model's docstring. **It does not replace the fingerprint**; both checks run, and the
fingerprint is still what makes a redeemed link dead even if the row is never read again.

── DELIVERY ──────────────────────────────────────────────────────────────────────────────────────

Two transports implement :class:`CredentialDelivery`. :class:`CopyLinkDelivery` hands the link
back to the administrator to copy — the owner's choice of 2026-08-30, and still the default.
:class:`EmailDelivery` (2026-10-10) queues the link to the account's own address through
``email_outbox`` and hands the administrator NO link: one copy of a credential, in the inbox of the
person it is for. It is offered only when mail is configured (``mailer.mail_configured``). The link
travels to the worker Fernet-sealed and the seal is cleared once the message is sent or has failed;
no log line carries it.
"""

import base64
import hashlib
import hmac
import json
import logging
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from app.core.config import get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

#: What a link is for. The two differ in how long they last and in what the screen says.
INVITE = "INVITE"
RESET = "RESET"

#: An invitation is generous: it is passed on by hand, read on a Monday and acted on after a
#: conference. A reset link is short, because it answers "I am locked out NOW" and a link that
#: outlives that conversation is a spare key left under the mat. Both numbers are cxa-cms's.
INVITE_TTL_HOURS = 72
RESET_TTL_HOURS = 2

#: THE THROTTLE cxa-cms LACKS. Four links for one account in an hour is generous for the real case
#: (an admin issues one, the designer loses it, the admin issues another) and cheap enough that
#: nobody can use this route to keep somebody signed out.
ISSUE_BUDGET = 4
ISSUE_WINDOW_HOURS = 1

#: A cap on how much text is treated as a token at all, so a megabyte pasted into the address bar is
#: refused before any HMAC is computed over it. cxa-cms's number.
MAX_TOKEN_LENGTH = 1024

#: The screen a link points at. Changing this changes every link already in somebody's hands.
SET_PASSWORD_PATH = "/set-password"

#: The shortest password this product will store. Matches ``LoginRequest.password``'s own floor and
#: ``UserCreate.password``'s, so a password that can be set can always be used to sign in.
MIN_PASSWORD_LENGTH = 8


def ttl_hours(purpose: str) -> int:
    return INVITE_TTL_HOURS if purpose == INVITE else RESET_TTL_HOURS


def purpose_for(user: Any) -> str:
    """INVITE for an account nobody has started using; RESET for one somebody has.

    "NOBODY HAS STARTED USING IT" IS TWO STATES, NOT ONE (2026-10-09). An account with no password is
    the obvious one. The other is an account a provisioner has just created WITH a password: it holds
    a hash, so the old rule ("INVITE while the hash is NULL") handed its holder a two-hour RESET link —
    read on a Monday, dead by lunch. ``firstLoginAt`` is NULL until the first sign-in that actually
    got in (``auth.session_response``), so an account carrying a hash and no first login is still an
    invitation in every sense that matters.

    A NULL IS TRUSTED FOR EVERY ACCOUNT, WHENEVER IT WAS CREATED. The column arrived empty, and for a
    while the rule trusted a NULL only on accounts created after it did, which gave a never-used
    account created earlier a two-hour link. ``20261018090100_first_login_backfill`` filled the
    column for every older account from the sign-in roster's ``firstSeenAt`` (stamped on every
    sign-in since 2026-08-16), so a NULL now means "never got in" for old and new accounts alike.

    THE REASON INVITE IS NOT USED FOR EVERYBODY STILL HOLDS: once somebody has signed in, the account
    is live, and a seventy-two-hour credential that ends their sessions when redeemed is a spare key
    for three days rather than for two hours.
    """
    if getattr(user, "passwordHash", None) is None:
        return INVITE
    if getattr(user, "firstLoginAt", None) is None:
        return INVITE
    return RESET


# --------------------------------------------------------------------------------------
# The token
# --------------------------------------------------------------------------------------


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _unb64(raw: str) -> bytes:
    return base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4))


def credential_fingerprint(password_hash: str | None) -> str:
    """A short digest of "what the password is now".

    ⚠ THE HASH IS NEVER PUT IN THE TOKEN — only 16 hex characters of a SHA-256 of it, which is not
    enough to attack and is enough to notice a change. A row with no password digests a fixed
    sentinel, so an invitation is bound to "still has no password".
    """
    return hashlib.sha256((password_hash or "no-password").encode("utf-8")).hexdigest()[:16]


def fingerprint_matches(password_hash: str | None, presented: str) -> bool:
    """Constant-time comparison.

    Not because a timing attack is reachable here — a caller only gets this far by presenting a
    VALID signature, which needs the signing key — but because a credential comparison written the
    short way is the one that gets copied somewhere it does matter.
    """
    return hmac.compare_digest(credential_fingerprint(password_hash), presented)


def _sign(body: str) -> str:
    secret = get_settings().jwt_secret.encode("utf-8")
    return _b64(hmac.new(secret, body.encode("ascii"), hashlib.sha256).digest())


def mint_token(*, user_id: str, purpose: str, expires_at: datetime, password_hash: str | None) -> str:
    payload = {
        # Bumped if the shape ever changes, so an old token is refused rather than misread.
        "v": 1,
        "sub": user_id,
        "purpose": purpose,
        "exp": int(expires_at.timestamp()),
        "cred": credential_fingerprint(password_hash),
        # ── THE NONCE, AND THE BUG IT CLOSES ──────────────────────────────────────────────────
        #
        # Every other claim is a FUNCTION OF THE ACCOUNT: the id, the purpose, the fingerprint of
        # the current password, and an expiry stamped in whole seconds. So two links issued for one
        # account inside the same second were byte-for-byte identical — measured 2026-08-30, and it
        # surfaced as `UniqueViolationError` on `PasswordResetToken.tokenHash` and a 500 at
        # `POST /auth/password-links`, which is the visible half.
        #
        # THE INVISIBLE HALF IS WORSE AND IS THE REASON THIS IS A NONCE RATHER THAN A RETRY. Two
        # issues that produce one token are one credential: revoking the row an admin thinks they
        # just issued would leave the other row live, and the link in the wrong chat window would go
        # on working. cxa-cms mints the same deterministic shape and never noticed, because it keeps
        # no rows and therefore has nothing that has to be one-to-one with a link.
        #
        # 96 bits of `secrets` output. It is not a secret in its own right — the HMAC is what makes
        # the token unforgeable — it exists so that two mints are two tokens.
        "n": secrets.token_urlsafe(12),
    }
    body = _b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    return f"{body}.{_sign(body)}"


def token_digest(token: str) -> str:
    """What the table stores. A table of live credentials is worth stealing; a table of digests is
    not, and a redemption can still find its row in one indexed read."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


#: Why a token was refused. ``malformed`` deliberately covers a FORGED signature as well as a
#: mangled one: the two are indistinguishable to a reader — both mean "this is not a link this site
#: issued" — and separating them would tell somebody probing the endpoint which half of their guess
#: was wrong.
MISSING = "missing"
MALFORMED = "malformed"
EXPIRED = "expired"
REVOKED = "revoked"
SPENT = "spent"
UNKNOWN_ACCOUNT = "unknown-account"


@dataclass(frozen=True)
class TokenVerdict:
    ok: bool
    reason: str | None = None
    user_id: str | None = None
    purpose: str | None = None
    #: Who issued the link, off its row — set only by :func:`describe_token` on a good verdict, for
    #: the redemption's question of whether that account may still manage this one
    #: (``account_provisioning.issuer_still_manages``). None when nobody did, or the issuer is gone.
    issued_by_id: str | None = None


def verify_token(raw: str | None, *, now: datetime | None = None) -> TokenVerdict:
    """Signature, shape and expiry. **It does not check the fingerprint** — that needs the account's
    current hash and this function reads no database.

    :func:`redeem` is the only caller and it follows a good verdict with the fingerprint check and
    the row check. A caller that skips the fingerprint has built a link that works for ever.

    THE ORDER IS DELIBERATE: signature first, then shape, then expiry. Everything after the
    signature is only reachable by somebody who already holds a link this installation issued, so
    the more specific answers below cannot be used to learn anything.
    """
    moment = now or datetime.now(UTC)
    if not isinstance(raw, str):
        return TokenVerdict(False, MISSING)
    token = raw.strip()
    if not token:
        return TokenVerdict(False, MISSING)
    if len(token) > MAX_TOKEN_LENGTH:
        return TokenVerdict(False, MALFORMED)

    # EXACTLY ONE separator. A token with two would let the body be chosen after the signature was
    # computed over a prefix of it.
    if token.count(".") != 1:
        return TokenVerdict(False, MALFORMED)
    body, presented = token.split(".", 1)
    if not body or not presented:
        return TokenVerdict(False, MALFORMED)
    if not hmac.compare_digest(presented, _sign(body)):
        return TokenVerdict(False, MALFORMED)

    try:
        decoded = json.loads(_unb64(body).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return TokenVerdict(False, MALFORMED)

    # The signature proves the bytes came from this installation; it does not prove they parse into
    # what this version of the code expects.
    if not isinstance(decoded, dict) or decoded.get("v") != 1:
        return TokenVerdict(False, MALFORMED)
    subject = decoded.get("sub")
    purpose = decoded.get("purpose")
    expiry = decoded.get("exp")
    cred = decoded.get("cred")
    if not isinstance(subject, str) or not 0 < len(subject) <= 64:
        return TokenVerdict(False, MALFORMED)
    if purpose not in (INVITE, RESET):
        return TokenVerdict(False, MALFORMED)
    if not isinstance(expiry, int):
        return TokenVerdict(False, MALFORMED)
    if not isinstance(cred, str) or len(cred) != 16:
        return TokenVerdict(False, MALFORMED)
    # Bounded rather than merely present: the signature proves the bytes came from this
    # installation, not that they parse into what this version expects, and an unbounded string here
    # would be a field a future bug could grow without anything noticing.
    nonce = decoded.get("n")
    if not isinstance(nonce, str) or not 0 < len(nonce) <= 64:
        return TokenVerdict(False, MALFORMED)
    if expiry <= int(moment.timestamp()):
        return TokenVerdict(False, EXPIRED)

    return TokenVerdict(True, None, subject, purpose)


# --------------------------------------------------------------------------------------
# Delivery — the interface the owner asked for, and its one implementation
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class DeliveredLink:
    """What an administrator gets back. ``link`` is the whole credential; there is no separate
    ``token`` field, on cxa-cms's stated grounds."""

    id: str
    #: ``None`` when the link was e-mailed: the administrator who chose that is not handed a copy.
    link: str | None
    expiresAt: str
    purpose: str
    #: How it reached the person. ``COPY_LINK`` means "it did not — you copy it and hand it over",
    #: which is what the clients print. ``EMAIL`` means it was queued to the account's own address.
    deliveredBy: str


class CredentialDelivery(Protocol):
    """How a minted link reaches the person it is for.

    ONE METHOD, AND IT RETURNS THE ANSWER RATHER THAN RAISING ON THE COMMON CASE, because the
    caller has already written the row: a transport failure must not leave a live credential with
    nothing recording it.
    """

    name: str

    async def deliver(
        self,
        *,
        user: Any,
        link: str,
        purpose: str,
        expires_at: datetime,
        requested_by: str = "administrator",
    ) -> str:
        """Return the ``deliveredBy`` value the clients render. ``requested_by`` is ``administrator``
        or ``self`` ("Forgot your password?"), and only changes the words of an e-mail."""


class CopyLinkDelivery:
    """THE ONE SHIPPED TODAY. It sends nothing and says so.

    The administrator copies the link out of the screen and hands it over by whatever channel they
    already use. That is the owner's decision, taken 2026-08-30, and it is why no mail dependency
    was added to ``pyproject.toml``.
    """

    name = "COPY_LINK"

    async def deliver(
        self,
        *,
        user: Any,
        link: str,
        purpose: str,
        expires_at: datetime,
        requested_by: str = "administrator",
    ) -> str:
        # At INFO and WITHOUT THE LINK. The link is a credential; the fact that one was issued is
        # an operational event worth having in a log, and the credential itself is not. The account
        # is named by its id, like every other line about a password in this product.
        logger.info(
            "auth: %s link issued for account %s, expires %s (delivery: copy-link)",
            purpose,
            getattr(user, "id", "?"),
            expires_at.isoformat(),
        )
        return self.name


class EmailDelivery:
    """Queue the link to the account's own address. The administrator is handed no link.

    The outbox row carries the link sealed (``managed_secrets.encrypt``) and the worker clears the
    seal once the message is SENT or FAILED. Raises :class:`DeliveryUnavailable` when mail is not
    configured, BEFORE anything is minted — see :func:`issue_link`.
    """

    name = "EMAIL"

    async def deliver(
        self,
        *,
        user: Any,
        link: str,
        purpose: str,
        expires_at: datetime,
        requested_by: str = "administrator",
    ) -> str:
        from app.services import email_outbox, mailer

        row = await email_outbox.enqueue(
            mailer.PASSWORD_LINK,
            to_address=getattr(user, "email", "") or "",
            recipient_id=getattr(user, "id", None),
            params={
                "purpose": purpose,
                "recipientName": getattr(user, "name", None),
                "expiresAt": expires_at.isoformat(),
                "requestedBy": requested_by,
            },
            secret=link,
        )
        if row is None:
            raise DeliveryUnavailable()
        logger.info(
            "auth: %s link issued for account %s, expires %s (delivery: e-mail, message %s)",
            purpose,
            getattr(user, "id", "?"),
            expires_at.isoformat(),
            row.id,
        )
        return self.name


class DeliveryUnavailable(Exception):
    """E-mail delivery was asked for and mail is not configured."""


COPY_LINK = CopyLinkDelivery.name
EMAIL = EmailDelivery.name
DELIVERIES = (COPY_LINK, EMAIL)


def delivery(name: str = COPY_LINK) -> CredentialDelivery:
    """The transport for *name*: ``COPY_LINK`` (the default) or ``EMAIL``."""
    return EmailDelivery() if name == EMAIL else CopyLinkDelivery()


# --------------------------------------------------------------------------------------
# Issuing, revoking and redeeming
# --------------------------------------------------------------------------------------


def link_for(token: str) -> str:
    base = str(get_settings().next_public_app_url).rstrip("/")
    from urllib.parse import quote

    return f"{base}{SET_PASSWORD_PATH}?token={quote(token, safe='')}"


class IssueThrottled(Exception):
    """Raised when :data:`ISSUE_BUDGET` links have already been minted for one account."""

    def __init__(self, retry_after_minutes: int) -> None:
        super().__init__("Too many password links issued for this account")
        self.retry_after_minutes = retry_after_minutes


async def issue_link(
    *,
    user: Any,
    purpose: str | None = None,
    issued_by_id: str | None = None,
    deliver_by: str = COPY_LINK,
    requested_by: str = "administrator",
) -> DeliveredLink:
    """Mint, record, deliver.

    ``purpose`` defaults to :func:`purpose_for` — INVITE for an account nobody has started using,
    RESET for one somebody has — which is the only difference a reader can see, and it decides the
    lifetime.

    The hash is read off the row the caller just loaded: binding the token to a stale hash would
    mint a link that was already spent, and binding it to none would mint one that stays valid after
    a password is set.
    """
    if deliver_by == EMAIL:
        from app.services import mailer

        # Refused BEFORE the throttle and the mint, so asking for e-mail on a deployment without
        # mail neither spends one of the account's four links nor leaves a row nobody holds.
        if not mailer.mail_configured() or not getattr(user, "email", None):
            raise DeliveryUnavailable()
    now = datetime.now(UTC)
    since = now - timedelta(hours=ISSUE_WINDOW_HOURS)
    # TWO BUDGETS, NEITHER SPENDING THE OTHER (security review, 2026-10-16). Links an administrator
    # issued count against the administrators' four; links "Forgot your password?" minted (no
    # issuer) count against their own, with the door's tighter silent cap on top
    # (``services/password_reset``). Counted together, a stranger typing somebody's address four
    # times an hour left no administrator able to help them back in.
    # Administrators' links are every link minus the self-service ones: two counts both using a
    # filter (equality to NULL) every Prisma client reads the same way.
    self_service = await db.passwordresettoken.count(
        where={"userId": user.id, "issuedById": None, "createdAt": {"gte": since}}
    )
    if issued_by_id is None:
        recent = self_service
    else:
        every = await db.passwordresettoken.count(where={"userId": user.id, "createdAt": {"gte": since}})
        recent = every - self_service
    if recent >= ISSUE_BUDGET:
        raise IssueThrottled(retry_after_minutes=ISSUE_WINDOW_HOURS * 60)

    kind = purpose or purpose_for(user)
    expires_at = now + timedelta(hours=ttl_hours(kind))
    token = mint_token(
        user_id=user.id,
        purpose=kind,
        expires_at=expires_at,
        password_hash=getattr(user, "passwordHash", None),
    )
    row = await db.passwordresettoken.create(
        data={
            "userId": user.id,
            "tokenHash": token_digest(token),
            "purpose": kind,
            "expiresAt": expires_at,
            "issuedById": issued_by_id,
        }
    )
    try:
        delivered = await delivery(deliver_by).deliver(
            user=user,
            link=link_for(token),
            purpose=kind,
            expires_at=expires_at,
            requested_by=requested_by,
        )
    except Exception:
        # Nobody holds this link — it was never handed back and never queued — so the row is
        # withdrawn rather than left looking like an outstanding credential.
        await db.passwordresettoken.update(where={"id": row.id}, data={"revokedAt": datetime.now(UTC)})
        raise
    return DeliveredLink(
        id=row.id,
        link=None if delivered == EMAIL else link_for(token),
        expiresAt=expires_at.isoformat(),
        purpose=kind,
        deliveredBy=delivered,
    )


async def link_row(row_id: str) -> Any | None:
    """The ``PasswordResetToken`` row behind an issued link, or None.

    For the revoke route, which has to know WHOSE link it is before it may withdraw it: revoking is
    authorised on the account the link is for, exactly as issuing is, so a provisioner cannot reach
    into the links of an account they could not have issued one for.
    """
    return await db.passwordresettoken.find_unique(where={"id": row_id})


async def revoke_link(row_id: str) -> bool:
    """Withdraw an outstanding link. Returns False when there was nothing outstanding to withdraw.

    Idempotent on a row already revoked or already used: an admin pressing the button twice has not
    made a mistake, and answering the second press with an error would suggest they had.
    """
    row = await db.passwordresettoken.find_unique(where={"id": row_id})
    if row is None:
        return False
    if row.revokedAt is not None or row.usedAt is not None:
        return True
    await db.passwordresettoken.update(
        where={"id": row_id}, data={"revokedAt": datetime.now(UTC)}
    )
    return True


async def describe_token(raw: str | None) -> TokenVerdict:
    """Everything :func:`verify_token` checks, PLUS the row and the fingerprint.

    The set-password screen calls this before drawing a password box, so somebody holding a dead
    link is told why instead of typing a new password into a form that will refuse it.
    """
    verdict = verify_token(raw)
    if not verdict.ok:
        return verdict
    row = await db.passwordresettoken.find_unique(where={"tokenHash": token_digest(str(raw).strip())})
    if row is None:
        # A signature that verifies with no row behind it means the row was deleted with the
        # account. Answering "malformed" would be a lie; the account is what is gone.
        return TokenVerdict(False, UNKNOWN_ACCOUNT)
    if row.revokedAt is not None:
        return TokenVerdict(False, REVOKED)
    if row.usedAt is not None:
        return TokenVerdict(False, SPENT)
    user = await db.user.find_unique(where={"id": verdict.user_id})
    if user is None:
        return TokenVerdict(False, UNKNOWN_ACCOUNT)
    # THE FINGERPRINT, LAST AND NEVER SKIPPED. Everything above can be satisfied by a link that has
    # already been used through some other row; this is what cannot.
    token_payload = json.loads(_unb64(str(raw).strip().split(".", 1)[0]).decode("utf-8"))
    if not fingerprint_matches(user.passwordHash, str(token_payload.get("cred"))):
        return TokenVerdict(False, SPENT)
    return TokenVerdict(True, None, user.id, verdict.purpose, row.issuedById)


async def revoke_outstanding(user_id: str) -> int:
    """Withdraw every link for *user_id* that has been neither used nor revoked; returns how many.

    For ``PATCH /api/users/{id}`` raising an account's role: a link is its issuer's credential, and a
    promotion must not carry it above the issuer's reach (``account_provisioning``, rule 5). Guarded
    in the WHERE, as :func:`revoke_link` is in Python, so a link redeemed a moment earlier keeps its
    ``usedAt`` and is not rewritten as withdrawn.
    """
    return await db.passwordresettoken.update_many(
        where={"userId": user_id, "usedAt": None, "revokedAt": None},
        data={"revokedAt": datetime.now(UTC)},
    )


async def mark_used(raw: str) -> None:
    """Stamp the row for the link just redeemed. Belt to the fingerprint's braces — see the model."""
    digest = token_digest(raw.strip())
    row = await db.passwordresettoken.find_unique(where={"tokenHash": digest})
    if row is not None and row.usedAt is None:
        await db.passwordresettoken.update(
            where={"id": row.id}, data={"usedAt": datetime.now(UTC)}
        )


async def self_service_links_since(user_id: str, since: datetime) -> int:
    """How many links "Forgot your password?" has minted for *user_id* since *since*.

    The self-service door's own, SILENT, per-address cap (``services/password_reset``) reads this: a
    link nobody issued (``issuedById`` NULL) is a link the account's own address asked for. Links an
    administrator made are not counted, so a flood of strangers' requests can never use up an
    administrator's ability to help. :data:`ISSUE_BUDGET` applies to each kind separately (see
    :func:`issue_link`), so neither door's links spend the other's.
    """
    return await db.passwordresettoken.count(
        where={"userId": user_id, "issuedById": None, "createdAt": {"gte": since}}
    )
