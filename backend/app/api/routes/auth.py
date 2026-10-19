"""Sign-in: the password branch, the Google branch, and THE GATE they both pass through.

THE GATE IS NEW AND IT IS THE POINT OF THIS MODULE. Until it existed, this application refused
nobody: a wrong password was the only failure it could produce, and any Google address whose owner
Google would vouch for silently self-provisioned an account and walked out with a bearer token. Both
branches now converge on :func:`assert_access_admitted`, and an address that is not ACTIVE on the
``AccessRoster`` gets no token — including on the Google branch, where it also gets NO ACCOUNT.

THREE RULES THAT MUST SURVIVE ANY REWRITE OF THIS FILE:

1. **ONE GATE, AT THE CONFLUENCE.** Both branches are checked, and they are checked in one place
   each for one reason: two copies of an authorisation check are two chances to forget it, and the
   one that gets forgotten is always the one an attacker uses. The Google branch is checked inside
   :func:`login_with_google` rather than in :func:`login` because it has to be checked BEFORE the
   account is created — see that function.
2. **THE GATE RUNS AFTER THE CREDENTIAL IS PROVED, NEVER BEFORE.** Refusing an unproved caller with
   "your access request is awaiting approval" would hand anybody a free oracle for which addresses
   this institution knows, without them even holding the address. The ordering is also what bounds
   the unauthenticated write: see ``access_roster.record_access_request``.
3. **MASTER_ADMIN IS NEVER GATED.** Exempted both by the configured ``MASTER_ADMIN_EMAIL`` (which
   works before the account exists) and by a live ``MASTER_ADMIN`` role on the row (which covers a
   second master admin promoted through the users screen). This is the break-glass: the roster is a
   table only an administrator can edit, so an administrator locked out by it is an outage with no
   remedy inside the product.
"""

import hashlib
import hmac
import logging
import secrets
from datetime import UTC, datetime
from typing import Any

import jwt
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request, Response, status
from fastapi.encoders import jsonable_encoder
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    get_current_user,
    invalidate_cached_user,
    is_master_admin,
    password_credential,
    require_admin,
    role_rank,
)
from app.core.security import (
    create_access_token,
    hash_password,
    verify_password,
    verify_password_or_decoy,
)
from app.core.user_fields import public_user
from app.scale.rate_limit import (
    account_credential_attempt,
    account_credential_refund,
    address_credential_attempt,
    client_address,
    forgot_password_attempt,
)
from app.schemas.auth import (
    ChangePasswordRequest,
    ForgotPasswordRequest,
    IssuePasswordLinkRequest,
    LoginRequest,
    PasswordLinkCheckRequest,
    SetPasswordRequest,
    TokenResponse,
    TwoFactorChallengeResponse,
)
from app.services import (
    credential_links,
    email_outbox,
    mailer,
    oidc_sign_in,
    password_reset,
    signin_role,
    two_factor,
)
from app.services.access_roster import (
    ADMITTING_STATUS,
    INVALID_CREDENTIALS_DETAIL,
    gate_enforced,
    is_master_admin_email,
    mark_first_seen,
    normalise_email,
    record_access_request,
    refusal_detail,
    role_value,
    roster_row,
    status_value,
)

router = APIRouter(prefix="/auth", tags=["auth"])
logger = logging.getLogger(__name__)


#: The per-account guessing budget is spent (``rate_limit.account_credential_attempt``). Not a 401:
#: the clients end the session on a 401, and this is about guesses, not about the session.
ACCOUNT_THROTTLED_DETAIL = (
    "Too many failed sign-in attempts for this account. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)


def serialize_user(user: Any) -> dict[str, Any]:
    """The account as every client reads it: no hash and no second-factor secret, plus
    ``hasPassword`` — the one fact about the hash the account screen and the users screen need."""
    return public_user(jsonable_encoder(user), user)


def enum_value(value: Any) -> str:
    return str(getattr(value, "value", value))


def role_for_email(email: str) -> str:
    settings = get_settings()
    if is_master_admin_email(email):
        return "MASTER_ADMIN"
    # New self-registered Google accounts start at the configured signup tier (lowest tier by
    # default) and are elevated by an admin — an unknown Google account no longer becomes a
    # full researcher automatically.
    #
    # Since the gate exists, reaching this line at all means an administrator has ALREADY admitted
    # the address; ``AccessRoster.grantedRole`` is what actually decides the tier, and this is only
    # the floor it cannot fall below. "All the users by default join as the lowest rung unless
    # promoted there itself" — the promotion happens on the roster screen.
    return settings.default_signup_role


def is_gate_exempt(email: Any, user: Any = None) -> bool:
    """Is this sign-in the break-glass? See rule 3 of the module docstring.

    TWO TESTS AND NOT ONE. The configured address covers the case where the account does not exist
    yet or has been deleted — the situation a break-glass has to survive, and the situation in which
    a role column cannot help because there is no row to read it from. The live ``MASTER_ADMIN``
    role covers a SECOND master admin, minted through ``POST /users`` by the first, whose address is
    not the one in the environment. Dropping either test leaves a way for this institution to lock
    itself out of its own repository with no in-product remedy.
    """
    if is_master_admin_email(email):
        return True
    return role_value(getattr(user, "role", None)) == "MASTER_ADMIN"


async def assert_access_admitted(email: Any, user: Any = None) -> None:
    """THE GATE. Raise the appropriate refusal unless this address is ACTIVE on the roster.

    CALLED ONLY AFTER THE CREDENTIAL HAS BEEN PROVED — a verified Google ID token, or a password
    that matched an existing hash. Rule 2 of the module docstring says why, and
    ``access_roster.record_access_request`` says what depends on it.

    THE TWO ANSWERS ARE DELIBERATELY DIFFERENT, and this is the ruling the whole feature turns on.
    A person waiting on an administrator is TOLD they are waiting; they do not get "invalid email or
    password". That widens account enumeration — a caller who holds an address can learn whether
    this institution knows it — and the trade was made knowingly, because the alternative is
    somebody spending a week resetting a password that was never wrong and telephoning the wrong
    desk about it. What must NOT widen past that: the refusal names no person, no role, no other
    account, and never says whether a password was ever set. Read
    ``access_roster.ACCESS_PENDING_DETAIL`` and its neighbours before touching the wording.

    THE BODY IS ``{"detail": {"code": ..., "message": ...}}`` RATHER THAN A BARE STRING because both
    clients already understand that shape — ``frontend/lib/api.ts::describeApiDetail`` and
    ``android/…/FieldRepository.kt::detailMessage`` each pull ``detail.message`` out of an object —
    so the human sentence still renders everywhere, while ``code`` gives a client something stable
    to branch on that is not a string comparison against English prose.
    """
    if not gate_enforced():
        return
    if is_gate_exempt(email, user):
        return

    row = await roster_row(email)
    if row is not None and status_value(row.status) == ADMITTING_STATUS:
        return

    # Read the status BEFORE recording, because recording deliberately does not change it: a
    # REJECTED person's re-attempt stays REJECTED and must be answered as such, not as pending.
    # A brand-new address is answered as PENDING because that is what the write below makes it.
    refused_status = status_value(row.status) if row is not None else "PENDING"
    await record_access_request(email)
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail={
            "code": f"ACCESS_{refused_status}",
            "message": refusal_detail(refused_status),
        },
    )


def verify_google_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    if not settings.google_client_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Google sign-in isn't available. Use your email and password.",
        )
    last_error: ValueError | None = None
    for client_id in settings.google_client_ids:
        try:
            return google_id_token.verify_oauth2_token(
                token,
                google_requests.Request(),
                client_id,
            )
        except ValueError as exc:
            last_error = exc
            logger.info("Google token rejected for configured audience %s: %s", client_id, exc)
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid Google ID token") from last_error


async def login_with_google(token: str) -> Any:
    """Google sign-in — GATED, which is the single biggest behavioural change in this feature.

    WHAT THIS USED TO DO. It took any Google ID token this server could verify, and if the address
    was unknown it CREATED a ``User`` row and returned a token. That is how every account in this
    application other than the seeded admins came to exist. It also meant that the set of people who
    could sign in was "everyone with a Google account", which is roughly three billion people, and
    the institution had no say in it whatsoever.

    WHAT IT DOES NOW. A verified Google identity that is not ACTIVE on the roster gets **no account
    and no token**. It becomes a PENDING roster entry and reads the same sentence a refused password
    sign-in reads. The product owner's ruling is explicit: "google signin should have the caveat of
    being in the allowlist as well, that should also go into pending if not already in the list."

    THE GATE IS CHECKED HERE, BEFORE ANY WRITE, AND NOT IN :func:`login` WITH THE OTHER BRANCH.
    That is the one place this file's "one gate at the confluence" rule bends, and it has to:
    ``login`` only sees the user AFTER this function has run, and by then the damage — an account
    provisioned for a stranger, or an existing account's name and avatar overwritten from an
    unadmitted token — is already committed. The refusal has to land before the first write.
    :func:`login` still calls :func:`assert_access_admitted` afterwards for BOTH branches; on this
    path that second call is a cheap, deliberate belt-and-braces re-check rather than the real gate.

    THE ORDER OF THE THREE CHECKS BELOW IS LOAD-BEARING: verify the token, THEN require a verified
    email, THEN consult the roster. Consulting the roster first would answer "is this address known
    to the institution" to anybody who typed an address, with no token at all.
    """
    id_info = verify_google_token(token)

    if not id_info.get("email") or not id_info.get("email_verified"):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Google account email is not verified",
        )

    email = normalise_email(id_info["email"])
    if not email:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Google account email is not verified",
        )
    return await _sign_in_verified_address(
        email, display_name=id_info.get("name"), avatar_url=id_info.get("picture"), provider="GOOGLE"
    )


def _oidc_refused_detail(label: str) -> str:
    """What a Microsoft or Yahoo sign-in that did not prove itself is told. One sentence for every
    failure of the code, the token or its signature: the log line carries the class, and the person
    has the same useful move whichever it was."""
    return f"Signing in with {label} did not complete. Try again, or use another way to sign in."


def _oidc_unverified_detail(label: str) -> str:
    return (
        f"{label} has not confirmed the email address on this account, so it cannot be used to sign "
        f"in here. Confirm the address with {label}, or use another way to sign in."
    )


async def login_with_oidc(payload: LoginRequest) -> Any:
    """Microsoft and Yahoo sign-in: redeem the code, prove the ID token, then the Google path's gate.

    THE SAME ORDER AS :func:`login_with_google`, FOR RULE 2 OF THE MODULE DOCSTRING: the identity is
    proved — the code redeemed with the client secret, the ID token verified against the provider's
    keys, the nonce matched, the address verified by the provider — before the roster is consulted or
    anything is written. See ``app/services/oidc_sign_in.py`` for what is checked and why the code is
    redeemed here rather than on a phone or in a browser.
    """
    name = payload.oidcProvider or ""
    label = oidc_sign_in.label_of(name)
    if oidc_sign_in.provider(name) is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Signing in with {label} is not available here. Use another way to sign in.",
        )
    if not oidc_sign_in.acceptable_redirect_uri(payload.oidcRedirectUri or ""):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_refused_detail(label))
    try:
        proved = await oidc_sign_in.prove(
            name,
            code=payload.oidcCode or "",
            code_verifier=payload.oidcCodeVerifier or "",
            redirect_uri=payload.oidcRedirectUri or "",
            raw_nonce=payload.oidcNonce or "",
        )
    except oidc_sign_in.EmailNotVerified as exc:
        logger.info("auth: %s sign-in refused (%s)", name, exc.reason)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_unverified_detail(label)
        ) from exc
    except oidc_sign_in.SignInRefused as exc:
        # THE REASON TAG, NEVER THE TOKEN OR THE CODE. See oidc_sign_in's module docstring.
        logger.info("auth: %s sign-in refused (%s)", name, exc.reason)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_refused_detail(label)) from exc
    email = normalise_email(proved.email)
    if not email:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_unverified_detail(label))
    return await _sign_in_verified_address(
        email, display_name=proved.name, avatar_url=None, provider=proved.provider
    )


async def _sign_in_verified_address(
    email: str, *, display_name: str | None, avatar_url: str | None, provider: str
) -> Any:
    """The gate, then the account, for an address a provider has verified.

    Reached from :func:`login_with_google` and :func:`login_with_oidc` only. ``provider`` is
    ``GOOGLE``, ``MICROSOFT`` or ``YAHOO``: the ``authProvider`` a new account is created with. The
    account is found at the normalised address and nowhere else, for every provider.
    """
    settings = get_settings()
    role = role_for_email(email)
    name = settings.master_admin_name if role == "MASTER_ADMIN" else display_name or email.split("@")[0]

    existing = await db.user.find_unique(where={"email": email})

    # THE GATE, before the account is created and before the existing one is touched. ``existing``
    # is passed so that a second master admin — MASTER_ADMIN by role rather than by the configured
    # address — is exempt here exactly as they are on the password branch.
    await assert_access_admitted(email, existing)

    # Only reachable for an admitted address (or the break-glass). ``grantedRole`` is the role an
    # administrator chose when they approved this person, and it is applied ONLY WHEN THE ACCOUNT
    # IS CREATED (the sign-in list rule, 2026-10-18, ``services/signin_role``). It never raises an
    # existing account (a sign-in that changed somebody's powers would do it invisibly, with no
    # administrator's name on it and no audit entry) and never lowers one (a row left at the
    # default must not knock an admin, promoted since on Manage users, back to the bottom rung).
    admitted = await roster_row(email)
    granted = role_value(getattr(admitted, "grantedRole", None))

    if existing:
        # Google's sign-in has always refreshed the name, the avatar and the provider. Microsoft and
        # Yahoo send no picture, so they leave the avatar alone, and they record their provider only
        # on an account that had none of its own (LOCAL) — a second provider is a second way in, not
        # a change of what the account is.
        data: dict[str, Any] = {"name": name}
        if provider == "GOOGLE":
            data.update({"avatarUrl": avatar_url, "authProvider": "GOOGLE"})
        elif str(getattr(existing.authProvider, "value", existing.authProvider) or "").upper() == "LOCAL":
            data["authProvider"] = provider
        # The configured master admin address is the break-glass, not the list: it is restored to
        # MASTER_ADMIN as it always was. Nobody else's role is written here.
        if role == "MASTER_ADMIN":
            data["role"] = "MASTER_ADMIN"
            data["canManageQuestionnaire"] = True
        updated = await db.user.update(
            where={"email": email},
            data=data,
        )
        # Sign-in is a WRITE to the identity the rest of the app authorises against: it can rename
        # the account and, for the master-admin email, hand it MASTER_ADMIN and
        # canManageQuestionnaire. Drop the cached row so the token minted below is never validated
        # against the pre-login one. (Keyed by id, which is why the update's return value is used.)
        invalidate_cached_user(updated.id)
        return updated

    if role != "MASTER_ADMIN":
        # A brand-new account for an address an administrator admitted, at the role they chose and
        # never below the signup floor. Without this the person an admin deliberately approved as,
        # say, a RESEARCHER would arrive at a home screen with a volunteer's powers.
        role = signin_role.role_for_new_account(role, granted)

    created = await db.user.create(
        data={
            "email": email,
            "name": name,
            "avatarUrl": avatar_url,
            "authProvider": provider,
            "role": role,
            "canManageQuestionnaire": role == "MASTER_ADMIN",
        }
    )
    invalidate_cached_user(created.id)
    return created


@router.post("/login", response_model=TokenResponse | TwoFactorChallengeResponse)
async def login(payload: LoginRequest) -> dict[str, Any]:
    """The one sign-in endpoint. Two branches in, one gate, one token mint.

    ``POST /auth/google`` is a two-line wrapper around this, so there is genuinely nowhere else a
    token is minted in this application. That is why the gate can live here and be complete — and
    why the two-step challenge can: an account with it on gets a challenge token here, from every
    branch, and only ``POST /auth/two-factor/verify`` turns that into a session.
    """
    if payload.googleIdToken:
        user = await login_with_google(payload.googleIdToken)
    elif payload.has_oidc_login:
        # Microsoft and Yahoo, gated inside the call before any write, for the Google branch's reason.
        user = await login_with_oidc(payload)
    else:
        email = normalise_email(payload.email)
        user = await db.user.find_unique(where={"email": email}) if email else None
        # THE PER-ACCOUNT GUESSING BUDGET (2026-10-16), shared with the change-password screen's
        # "current password": taken before bcrypt, given back when the password is right. Keyed on
        # the account, so guesses spread across many networks still meet it. An address that matches
        # no account spends an identical bucket keyed on the address as typed (security review N1),
        # and the decoy bcrypt below costs it the same time: otherwise the eleventh guess answered
        # 429 for a real address and 401 for an unknown one, which told a script who has an account.
        if user is not None:
            allowed, _retry = account_credential_attempt(user.id)
        else:
            allowed, _retry = address_credential_attempt(email)
        if not allowed:
            raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=ACCOUNT_THROTTLED_DETAIL)
        if not verify_password_or_decoy(payload.password or "", user.passwordHash if user else None) or not user:
            # UNCHANGED, AND DELIBERATELY SO. This is the wrong-credential answer, and it is the one
            # place the gate does NOT speak: an unknown address with a guessed password has proved
            # nothing, so it is told nothing and — crucially — it writes NOTHING to the roster. If
            # this branch recorded an access request, the administrators' pending queue would be
            # writable at line rate by any anonymous script, and the notification this whole feature
            # exists to deliver would drown in it. See
            # ``access_roster.record_access_request``: proof of ownership comes first.
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=INVALID_CREDENTIALS_DETAIL,
            )
        # The password was right, so nothing was spent.
        account_credential_refund(user.id)

    # THE GATE, ON THE LAST LINE BEFORE THE TOKEN. Both branches reach it and neither can avoid it,
    # which is the property that matters: this is the only place in the application a token is
    # minted, so a check here is a check everywhere.
    #
    # For the PASSWORD branch this is the only gate, and it is deliberately below the credential
    # check — the password matched an existing hash, so ownership of the address is proved and the
    # gate may now speak in full. After the grandfathering migration it can only refuse somebody an
    # administrator deliberately suspended or rejected; every account that existed when the gate
    # shipped went onto the roster as ACTIVE.
    #
    # For the GOOGLE branch it is the SECOND check, on purpose. ``login_with_google`` has to gate
    # before it writes, so it does its own; this one guarantees that no future edit to that function
    # can produce a user object which reaches the mint ungated. It costs one indexed lookup on a
    # path a person takes once a week.
    await assert_access_admitted(user.email, user)

    # THE SECOND STEP, after the gate (a refused person learns that before being asked for a code)
    # and before anything that means "got in". No session yet: the client shows the code screen.
    if getattr(user, "twoFactorEnabled", False):
        # LOCKED: no challenge is minted, so none can be stockpiled to spend the moment the lock
        # lapses. Said plainly, with /verify's own words: only somebody holding the password reaches
        # this line, and hiding the lock would hide from the owner that the password is known.
        # "Locked" means EVERY way in — a six-digit lock alone still leaves the owner's recovery
        # codes, and they need a challenge to use one.
        if two_factor.fully_locked(user):
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL
            )
        return {
            "two_factor_required": True,
            "challenge_token": await two_factor.issue_challenge(user),
        }

    return await session_response(user)


async def session_response(user: Any) -> dict[str, Any]:
    """The successful sign-in body. Shared by :func:`login` and the two-step verify endpoint, so a
    session that passed a second factor is byte-for-byte the session one without it gets."""
    # Only after a successful admission: "first seen" means the first time somebody actually got IN,
    # so a suspended person's refused attempt must not consume the stamp an administrator reads as
    # "this approval was taken up".
    await mark_first_seen(user.email)
    # One write, by id, and the body below is built from the row it returns, so the sign-in answer
    # already shows this sign-in as the last one.
    # ``lastLoginAt`` moves on every sign-in (the users screen's "Last signed in"). ``firstLoginAt``
    # is "the first time this account got in", stamped once and never moved. It is what tells a
    # password link for an account nobody has used yet (a 72-hour invitation) from one for a live
    # account (a 2-hour reset) — ``credential_links.purpose_for``.
    now = datetime.now(UTC)
    stamps: dict[str, Any] = {"lastLoginAt": now}
    if getattr(user, "firstLoginAt", None) is None:
        stamps["firstLoginAt"] = now
    user = await db.user.update(where={"id": user.id}, data=stamps) or user
    invalidate_cached_user(user.id)
    return {"accessToken": _session_token(user), "tokenType": "bearer", "user": serialize_user(user)}


def _session_token(user: Any) -> str:
    """The session token both doors hand out — the sign-in, and the change-password answer.

    BOUND TO THE PASSWORD IT WAS OPENED WITH: ``password_credential`` is the fingerprint of the hash
    on the row in hand, so a later password change, by any door, retires it (``deps._user_from_bearer``).
    """
    return create_access_token(
        subject=user.id,
        extra_claims={"email": user.email, "role": enum_value(user.role)},
        credential=password_credential(user),
    )


@router.post("/google", response_model=TokenResponse | TwoFactorChallengeResponse)
async def google_login(payload: LoginRequest) -> dict[str, Any]:
    if not payload.googleIdToken:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Missing Google ID token")
    return await login(payload)


@router.post("/logout")
async def logout() -> dict[str, bool]:
    return {"ok": True}


@router.get("/me")
async def me(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return serialize_user(current_user)


# ==================================================================================================
# PASSWORDS: links, their redemption, the change, the first password, and "Forgot your password?"
# ==================================================================================================
#
# Ported on 2026-10-16 from the Design Prototype Workshop's routes/auth.py: the same routes, bodies,
# answers and headers, so the two portals' clients stay copyable. The mechanism is
# app/services/credential_links.py: a single-use, expiring token bound to the account's password
# fingerprint, recorded (as a digest) so it can be withdrawn and throttled, and delivered either by
# the administrator copying it or by e-mail to the account's own address. NOTHING HERE MINTS A
# SESSION FROM A LINK: a redemption answers {"ok": true} and the person then signs in, meeting the
# two-step challenge if their account has it on.


def _auth_provider(account: Any) -> str:
    """``LOCAL``, ``GOOGLE``, ``MICROSOFT`` or ``YAHOO``, from a live row or a hand-built one."""
    provider = getattr(account, "authProvider", None)
    return str(getattr(provider, "value", provider) or "").upper()


def _is_google_only(account: Any) -> bool:
    """No password, and Google is how it signs in, so a password link has nothing to do."""
    return getattr(account, "passwordHash", None) is None and _auth_provider(account) == "GOOGLE"


GOOGLE_ONLY_LINK_DETAIL = (
    "This account signs in with Google and has no password, so there is no password link to issue."
)

#: The refusal for your own account: a link to yourself is a password set without the current one.
OWN_CREDENTIALS_DETAIL = (
    "You cannot set or reset your own password here. Change it from your own account (Change "
    "password), which asks for the password you use now."
)


def assert_may_reset_credentials(actor: Any, target: Any) -> None:
    """The rule for a password, the password flag, a password link or "Sign out of every device" on
    somebody's account: an administrator (the route's ``require_admin``), never on themselves, and
    only on an account they may manage (``users.assert_can_manage_target``)."""
    from app.api.routes.users import assert_can_manage_target, assert_not_another_master

    if getattr(target, "id", None) == getattr(actor, "id", None):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_CREDENTIALS_DETAIL)
    assert_can_manage_target(actor, target)
    # A master admin over ANOTHER master admin: refused, as the sibling portal does (review B4).
    assert_not_another_master(actor, target)


def issuer_still_manages(issuer: Any, target: Any) -> bool:
    """May the account that ISSUED a password link still manage the account it is for? A link is
    authorised once, at issue, against tiers that can move before it is used. A master admin always
    may; a link nobody issued ("Forgot your password?"), or whose issuer was deleted, always may."""
    if issuer is None or is_master_admin(issuer):
        return True
    return role_rank(target) < role_rank(issuer)


def _link_payload(issued: credential_links.DeliveredLink) -> dict[str, Any]:
    """The answer to an issuing request. The token is not a separate field: the link contains it,
    and a credential appearing twice in one answer is a credential in two places to keep out of logs."""
    return {
        "id": issued.id,
        "link": issued.link,
        "expiresAt": issued.expiresAt,
        "purpose": issued.purpose,
        "deliveredBy": issued.deliveredBy,
    }


@router.post("/password-links", status_code=status.HTTP_201_CREATED)
async def issue_password_link(
    payload: IssuePasswordLinkRequest, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Mint a set-password link for another account: an administrator, for an account it may manage,
    never for itself. ``delivery`` is ``COPY_LINK`` (the administrator copies it and hands it over by
    phone, WhatsApp, anything) or ``EMAIL`` (queued to the account's own address through SES; the
    administrator is handed no copy). A Google account with no password is a 422. The PURPOSE is
    ``credential_links.purpose_for``: INVITE (72 h) for an account nobody has used yet, RESET (2 h)
    for one somebody has. Throttled per SUBJECT (four an hour)."""
    target = await db.user.find_unique(where={"id": payload.userId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    assert_may_reset_credentials(current_user, target)
    if _is_google_only(target):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=GOOGLE_ONLY_LINK_DETAIL)
    try:
        issued = await credential_links.issue_link(
            user=target,
            purpose=credential_links.purpose_for(target),
            issued_by_id=current_user.id,
            deliver_by=payload.delivery,
        )
    except credential_links.DeliveryUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="This link cannot be sent by e-mail. Copy it instead and pass it on yourself.",
        ) from exc
    except credential_links.IssueThrottled as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=(
                "Several password links have already been issued for this account. Wait an hour, "
                "or ask the person to use one of them."
            ),
            headers={"retry-after": str(exc.retry_after_minutes * 60)},
        ) from exc
    # Ids, never the link: the link is a credential and the log is not a place for one.
    logger.warning(
        "AUDIT password link issued: purpose=%s link=%s user=%s by=%s delivery=%s",
        issued.purpose,
        issued.id,
        target.id,
        current_user.id,
        issued.deliveredBy,
    )
    return _link_payload(issued)


@router.post("/password-links/{link_id}/revoke")
async def revoke_password_link(link_id: str, current_user: Any = Depends(require_admin)) -> dict[str, bool]:
    """Withdraw a link that has not been used yet ("I pasted that into the wrong window"). Authorised
    on the account the link is for, exactly as issuing is."""
    row = await credential_links.link_row(link_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    target = await db.user.find_unique(where={"id": row.userId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    assert_may_reset_credentials(current_user, target)
    if not await credential_links.revoke_link(link_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    logger.warning("AUDIT password link withdrawn: link=%s user=%s by=%s", link_id, target.id, current_user.id)
    return {"ok": True}


@router.get("/set-password")
async def check_set_password_token(token: str = "") -> dict[str, Any]:
    """Is this link still good? Unauthenticated. Answers about the LINK and never about the account
    (no email, no name). Kept for parity with the sibling portal; both clients here ask
    :func:`check_set_password_token_in_body`, which keeps the token off the request line."""
    return await _link_check(token)


@router.post("/set-password/check")
async def check_set_password_token_in_body(payload: PasswordLinkCheckRequest) -> dict[str, Any]:
    """:func:`check_set_password_token`, with the token in a JSON body: ``{"token": "..."}``."""
    return await _link_check(payload.token)


async def _link_check(raw: str | None) -> dict[str, Any]:
    verdict = await _link_verdict(raw)
    return {"valid": verdict.ok, "reason": verdict.reason, "purpose": verdict.purpose}


async def _link_verdict(raw: str | None) -> credential_links.TokenVerdict:
    """``credential_links.describe_token`` plus: may the account that ISSUED this link still manage
    the account it is for? If not, the link reads as withdrawn."""
    verdict = await credential_links.describe_token(raw)
    if not verdict.ok or verdict.issued_by_id is None:
        return verdict
    issuer = await db.user.find_unique(where={"id": verdict.issued_by_id})
    target = await db.user.find_unique(where={"id": verdict.user_id})
    if target is not None and not issuer_still_manages(issuer, target):
        logger.info(
            "auth: refused password link for account %s: its issuer %s can no longer manage it",
            verdict.user_id,
            verdict.issued_by_id,
        )
        return credential_links.TokenVerdict(False, credential_links.REVOKED)
    return verdict


#: A new password that IS the one the account holds now, refused while the account still carries
#: ``mustChangePassword``: the forced change exists to retire a password somebody else chose.
SAME_PASSWORD_DETAIL = (
    "Choose a password different from the one you have now. A password somebody else gave you has "
    "to be replaced, not typed again."
)

#: A current password that does not match. 400, NOT 401: the clients end the session on a 401.
CURRENT_PASSWORD_WRONG_DETAIL = "Current password is incorrect"

_SET_PASSWORD_REFUSALS = {
    credential_links.MISSING: "This link is incomplete. Open the whole link you were sent.",
    credential_links.MALFORMED: "This is not a link this site issued. Ask the administrator for another.",
    credential_links.EXPIRED: "This link has expired. Ask for a new one.",
    credential_links.REVOKED: "This link was withdrawn. Ask the administrator for a new one.",
    credential_links.SPENT: "This link has already been used. Sign in with the password you set.",
    credential_links.UNKNOWN_ACCOUNT: "This link no longer points at an account.",
}


@router.post("/set-password")
async def set_password(payload: SetPasswordRequest) -> dict[str, bool]:
    """Redeem a link. Writes the hash, ``passwordSetAt``, ``mustChangePassword: false`` and
    ``sessionsValidFrom`` (EVERY SESSION OF THE ACCOUNT ENDS, because the usual reason somebody is
    resetting is that a session they no longer control is live somewhere), then stamps the row used.

    NO SESSION IS MINTED. The person signs in with the new password afterwards, and an account with
    two-step verification on is asked for its code there as always: a link (or a mailbox) is never a
    way past the second factor.
    """
    verdict = await _link_verdict(payload.token)
    if not verdict.ok:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_SET_PASSWORD_REFUSALS.get(verdict.reason or "", "This password link is not valid."),
        )
    account = await db.user.find_unique(where={"id": verdict.user_id})
    if (
        account is not None
        and account.mustChangePassword
        and verify_password(payload.password, account.passwordHash)
    ):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=SAME_PASSWORD_DETAIL)
    password_hash = hash_password(payload.password)
    now = datetime.now(UTC)  # after bcrypt, so a sign-in racing the redemption cannot post-date it
    await db.user.update(
        where={"id": verdict.user_id},
        data={
            "passwordHash": password_hash,
            "passwordSetAt": now,
            "mustChangePassword": False,
            "sessionsValidFrom": now,
        },
    )
    invalidate_cached_user(verdict.user_id)
    await credential_links.mark_used(payload.token)
    logger.warning("AUDIT password set through a %s link: user=%s", verdict.purpose, verdict.user_id)
    if account is not None:
        # The owner hears of it at their own address, whoever held the link: "added" for an account
        # that had no password (an invitation), "changed" for one that did.
        kind = mailer.PASSWORD_ADDED if account.passwordHash is None else mailer.PASSWORD_CHANGED
        await email_outbox.notify_password_event(account, kind, when=now)
    return {"ok": True}


@router.post("/forgot-password")
async def forgot_password(
    payload: ForgotPasswordRequest,
    request: Request,
    response: Response,
    background: BackgroundTasks,
) -> dict[str, str]:
    """"Forgot your password?": ask for a single-use set-password link by e-mail.

    Unauthenticated. ONE ANSWER FOR EVERY ADDRESS: a well-formed request always gets
    ``password_reset.FORGOT_PASSWORD_MESSAGE``, and everything that depends on WHICH address it is
    (the lookup, the allow-list, the per-address cap, the link, the outbox row, the audit row) runs
    after the response has gone (FastAPI ``BackgroundTasks``), so a real account does not answer more
    slowly than a stranger's guess. See ``app/services/password_reset.py`` for the rules and what this
    deliberately does not do (end sessions, clear throttles, mention two-step).

    The two refusals are about the REQUEST: 422 for a malformed address, and 429 for too many
    requests from one connection (``rate_limit.forgot_password_attempt``), with ``Retry-After``. The
    link is built from ``NEXT_PUBLIC_APP_URL`` inside ``credential_links``; nothing from this
    request's headers reaches it.
    """
    allowed, retry_after = forgot_password_attempt(request.scope)
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=password_reset.TOO_MANY_REQUESTS_DETAIL,
            headers={"Retry-After": str(max(1, int(retry_after + 0.999)))},
        )
    address = client_address(request.scope)
    background.add_task(
        password_reset.request_password_reset,
        str(payload.email),
        ip_address=address.removeprefix("ip:"),
        user_agent=request.headers.get("user-agent"),
    )
    response.headers["Cache-Control"] = "no-store"
    return {"message": password_reset.FORGOT_PASSWORD_MESSAGE}


#: The header ``change_password``'s answer carries the caller's NEW session token in. A header and
#: not a body field, so the body stays ``{"ok": true}`` exactly as the sibling portal's handsets read
#: it. Must be in ``expose_headers`` in app/main.py, or the browser hides it from JavaScript.
SESSION_TOKEN_HEADER = "X-Session-Token"

#: THE PROVIDER SIGN-IN, RUN AGAIN (2026-10-17). An account with no password may SET its first one
#: only with a provider sign-in this fresh, sent with the request: the ID token's ``iat`` no older than
#: this, for the account's own address. A session token's age was the proof before; a stolen session
#: is exactly what must not be able to add a permanent way in, so the provider is asked again.
PROVIDER_PROOF_MAX_AGE_SECONDS = 300
#: A token "issued" this far in the future is still accepted: phones' clocks drift.
PROVIDER_PROOF_CLOCK_SKEW_SECONDS = 60

#: The header on a first-password refusal that wants the provider sign-in (missing, stale or not this
#: account's). Its value is the provider to run: ``GOOGLE``, ``MICROSOFT`` or ``YAHOO``. A client
#: re-runs that sign-in and sends again, without matching prose. In ``expose_headers`` in app/main.py.
CONFIRM_SIGN_IN_HEADER = "X-Confirm-Sign-In"

#: The header on a first-password refusal that wants the two-step code (missing or wrong), so a
#: client can show the code box without matching prose. In ``expose_headers`` in app/main.py.
TWO_FACTOR_REQUIRED_HEADER = "X-Two-Factor-Required"

#: A wrong two-step code AFTER a provider sign-in was accepted: the 400 carries this, a short-lived
#: token that stands in for that sign-in on the next try (body field ``providerProof``). A Microsoft or
#: Yahoo sign-in is a single-use code the server has already redeemed, so without it a mistyped
#: authenticator code would cost a trip back to the provider. Signed with a key of its own (never a
#: session), bound to the account, valid only until the sign-in it stands for turns five minutes old,
#: and single use: every refusal that consumes one hands out the next. In ``expose_headers``.
SIGN_IN_PROOF_HEADER = "X-Sign-In-Proof"
_SIGN_IN_PROOF_TYPE = "first-password-proof"
#: Spent proof ids until they expire. Per process: the API runs one worker (deploy/fieldrepo.service).
_spent_sign_in_proofs: dict[str, float] = {}


def _sign_in_proof_key() -> bytes:
    return hmac.new(get_settings().jwt_secret.encode("utf-8"), _SIGN_IN_PROOF_TYPE.encode(), hashlib.sha256).digest()


def _mint_sign_in_proof(account_id: str, provider: str, expires_at: float) -> str:
    claims = {
        "sub": account_id,
        "typ": _SIGN_IN_PROOF_TYPE,
        "prv": provider,
        "jti": secrets.token_urlsafe(16),
        "exp": int(expires_at),
    }
    return jwt.encode(claims, _sign_in_proof_key(), algorithm="HS256")


def _redeem_sign_in_proof(token: str, account_id: str) -> tuple[str, float] | None:
    """``(provider, expires_at)`` for an unspent proof minted for ``account_id``, now spent; else None."""
    try:
        claims = jwt.decode(token, _sign_in_proof_key(), algorithms=["HS256"], options={"require": ["exp", "sub", "jti"]})
    except jwt.PyJWTError:
        return None
    if claims.get("typ") != _SIGN_IN_PROOF_TYPE or claims.get("sub") != account_id:
        return None
    now = datetime.now(UTC).timestamp()
    for spent, until in list(_spent_sign_in_proofs.items()):
        if until < now:
            _spent_sign_in_proofs.pop(spent, None)
    jti = str(claims["jti"])
    if jti in _spent_sign_in_proofs:
        return None
    _spent_sign_in_proofs[jti] = float(claims["exp"])
    provider = claims.get("prv")
    return (provider if provider in _PROVIDER_LABELS else "GOOGLE"), float(claims["exp"])

TWO_FACTOR_CODE_NEEDED_DETAIL = "Enter the code from your authenticator app to set a password."

_PROVIDER_LABELS = {"GOOGLE": "Google", "MICROSOFT": "Microsoft", "YAHOO": "Yahoo"}


def _proof_provider(account: Any) -> str:
    """The provider a password-less account is asked to sign in with again: its own, or Google."""
    provider = _auth_provider(account)
    return provider if provider in _PROVIDER_LABELS else "GOOGLE"


def confirm_sign_in_detail(provider: str) -> str:
    label = _PROVIDER_LABELS.get(provider, "Google")
    return f"To set a password, first confirm it's you: sign in with {label} again, then save within 5 minutes."


def proof_refused_detail(provider: str, email: str) -> str:
    label = _PROVIDER_LABELS.get(provider, "Google")
    return f"That {label} sign-in didn't match this account or has expired. Sign in with {label} again as {email}, then save."


@router.post("/change-password")
async def change_password(
    payload: ChangePasswordRequest,
    response: Response,
    current_user: Any = Depends(get_current_user),
) -> dict[str, bool]:
    """The signed-in account replacing its own password (the route ``mustChangePassword`` sends
    somebody to) or, for an account with none, setting its first one.

    THE CURRENT PASSWORD IS REQUIRED for an account that has one, even when ``mustChangePassword`` is
    set (400 if wrong, never 401: the clients end the session on a 401). A new password equal to the
    current one is refused. Wrong current passwords spend the per-account guessing budget the sign-in
    spends (429 when it is gone). EVERY OTHER SESSION ENDS — through the credential fingerprint and
    the ``sessionsValidFrom`` watermark, so a token too old to carry a fingerprint ends too — and this
    one continues on a new token in ``X-Session-Token``; the body stays ``{"ok": true}``. The owner is
    sent "Your password was changed" (``mailer.PASSWORD_CHANGED``).

    AN ACCOUNT WITH NO PASSWORD (Google, Microsoft or Yahoo only) sets its first one here, without a
    current password, but only with that provider's sign-in RUN AGAIN and sent along — no older than
    :data:`PROVIDER_PROOF_MAX_AGE_SECONDS`, for this account's address — and, when two-step is on, a
    code (its own budget: ``two_factor.claim_first_password_check``). Otherwise a 400 carrying
    ``X-Confirm-Sign-In`` or ``X-Two-Factor-Required`` (with ``X-Sign-In-Proof`` for the next try
    when the sign-in was accepted and only the code was wrong). Its provider stays: the person can now sign
    in either way.
    """
    if current_user.passwordHash is None:
        return await _set_first_password(payload, response, current_user)
    # THE PER-ACCOUNT GUESSING BUDGET (security review D1, 2026-10-16). Without it a stolen session
    # could guess the current password here without limit, and the prize is exactly the permanent
    # takeover this route exists to prevent. The SAME bucket as the sign-in, keyed on the account:
    # taken before bcrypt, given back when the password is right.
    allowed, _retry = account_credential_attempt(current_user.id)
    if not allowed:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=ACCOUNT_THROTTLED_DETAIL)
    if not verify_password(payload.currentPassword, current_user.passwordHash):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=CURRENT_PASSWORD_WRONG_DETAIL)
    account_credential_refund(current_user.id)
    if verify_password(payload.newPassword, current_user.passwordHash):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=SAME_PASSWORD_DETAIL)
    updated = await _write_own_password(current_user, payload.newPassword)
    logger.warning("AUDIT password changed by the account itself: user=%s", current_user.id)
    response.headers[SESSION_TOKEN_HEADER] = _session_token(updated)
    await email_outbox.notify_password_event(updated, mailer.PASSWORD_CHANGED)
    return {"ok": True}


async def _write_own_password(current_user: Any, new_password: str) -> Any:
    """Write the account's own new password and END EVERY OTHER SESSION (security review D2).

    The ``cred`` fingerprint already retires every token minted with the old password — but a token
    minted before that claim existed carries none and would survive. So ``sessionsValidFrom`` is
    stamped too, TRUNCATED TO THE WHOLE SECOND: a token's ``iat`` is whole seconds, the refusal is
    ``iat < stamp``, and the new session token is minted after this write, so its ``iat`` is never
    earlier than the stamp and it is the one session that carries on. The cost of the truncation is
    that a token minted in the same wall second as the change, before it, also survives — it would
    still carry the old fingerprint and be refused by that check. Stamped after bcrypt.
    """
    password_hash = hash_password(new_password)
    now = datetime.now(UTC).replace(microsecond=0)
    updated = await db.user.update(
        where={"id": current_user.id},
        data={
            "passwordHash": password_hash,
            "passwordSetAt": now,
            "mustChangePassword": False,
            "sessionsValidFrom": now,
        },
    )
    invalidate_cached_user(current_user.id)
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="This account no longer exists. Sign in again."
        )
    return updated


async def _provider_proof(payload: ChangePasswordRequest, account: Any) -> tuple[str, float]:
    """THE PROVIDER SIGN-IN, RUN AGAIN: proved, fresh, and this account's — or a 400 carrying
    ``X-Confirm-Sign-In``. Never a 401: the clients end the session on one. Answers the provider
    and the moment the sign-in stops counting (``iat`` + five minutes). A ``providerProof`` handed
    out by an earlier refusal (:data:`SIGN_IN_PROOF_HEADER`) stands in for the sign-in it was minted
    from, until that same moment.

    Any of the three providers is accepted when it vouches for this account's address: each of them
    could sign this account in anyway. The account keeps no provider subject, so the match is on the
    address the provider verified (normalised exactly as sign-in finds the account), and the token
    must name a subject at all. Fresh means an ``iat`` within :data:`PROVIDER_PROOF_MAX_AGE_SECONDS`.
    """
    wanted = _proof_provider(account)
    email = normalise_email(getattr(account, "email", "")) or ""

    def refuse(provider: str, detail: str | None = None) -> HTTPException:
        return HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=detail or proof_refused_detail(provider, email),
            headers={CONFIRM_SIGN_IN_HEADER: wanted},
        )

    proved_email: str | None = None
    subject: str | None = None
    issued_at: float | None = None
    if payload.providerProof:
        carried = _redeem_sign_in_proof(payload.providerProof, getattr(account, "id", ""))
        if carried is None:
            raise refuse(wanted)
        return carried
    if payload.googleIdToken:
        used = "GOOGLE"
        try:
            id_info = verify_google_token(payload.googleIdToken)
        except HTTPException as exc:
            logger.info("auth: first-password Google proof refused (%s)", exc.status_code)
            raise refuse(used) from exc
        if id_info.get("email_verified"):
            proved_email = normalise_email(id_info.get("email"))
        raw_sub, raw_iat = id_info.get("sub"), id_info.get("iat")
        subject = raw_sub if isinstance(raw_sub, str) and raw_sub else None
        if isinstance(raw_iat, int | float) and not isinstance(raw_iat, bool):
            issued_at = float(raw_iat)
    elif payload.has_oidc_proof:
        used = payload.oidcProvider or wanted
        if oidc_sign_in.provider(used) is None or not oidc_sign_in.acceptable_redirect_uri(
            payload.oidcRedirectUri or ""
        ):
            raise refuse(used)
        try:
            proved = await oidc_sign_in.prove(
                used,
                code=payload.oidcCode or "",
                code_verifier=payload.oidcCodeVerifier or "",
                redirect_uri=payload.oidcRedirectUri or "",
                raw_nonce=payload.oidcNonce or "",
            )
        except (oidc_sign_in.SignInRefused, oidc_sign_in.EmailNotVerified) as exc:
            logger.info("auth: first-password %s proof refused (%s)", used, getattr(exc, "reason", "refused"))
            raise refuse(used) from exc
        proved_email = normalise_email(proved.email)
        subject, issued_at = proved.subject, proved.issued_at
    else:
        raise refuse(wanted, confirm_sign_in_detail(wanted))

    now = datetime.now(UTC).timestamp()
    fresh = (
        issued_at is not None
        and now - PROVIDER_PROOF_MAX_AGE_SECONDS <= issued_at <= now + PROVIDER_PROOF_CLOCK_SKEW_SECONDS
    )
    if not (proved_email and email and proved_email == email and subject and fresh):
        logger.info(
            "auth: first-password proof refused for user %s (address %s, subject %s, fresh %s)",
            getattr(account, "id", None),
            "matched" if proved_email and proved_email == email else "different",
            "present" if subject else "missing",
            fresh,
        )
        raise refuse(used)
    return used, (issued_at or 0.0) + PROVIDER_PROOF_MAX_AGE_SECONDS


def _refuse_code(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=detail, headers={TWO_FACTOR_REQUIRED_HEADER: "1"})


async def _first_password_code_allowance(account: Any, code: str) -> Any:
    """BEFORE the provider sign-in is redeemed: the code is there, the second factor is not locked,
    and this account's own budget (five a quarter-hour: ``two_factor.claim_first_password_check``,
    never the sign-in's) has a check left — which is taken now and given back by the caller when the
    sign-in is refused or the code is right. Checked first because a Microsoft or Yahoo sign-in is a
    single-use code: redeeming it only to refuse for a reason known beforehand would spend it for
    nothing. Answers the account's row as it is now (the cached identity may be seconds behind a code
    just used)."""
    if not code:
        raise _refuse_code(TWO_FACTOR_CODE_NEEDED_DETAIL)
    fresh = await db.user.find_unique(where={"id": account.id}) or account
    too_many = HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL)
    if two_factor.is_locked(fresh, two_factor.budget_for(code)):
        raise too_many
    if not await two_factor.claim_first_password_check(account.id):
        raise too_many
    return fresh


async def _set_first_password(
    payload: ChangePasswordRequest,
    response: Response,
    current_user: Any,
) -> dict[str, bool]:
    """An account with no password choosing its first one. A provider sign-in run again in the last
    five minutes stands in for the current password (:func:`_provider_proof`) and, when two-step is
    on, the second factor is asked for AGAIN (security review B2), on its own budget
    (:func:`_first_password_code_allowance`). Nothing is written on a refusal. Every other session
    ends (``_write_own_password``) and the owner is told by e-mail.

    The ORDER keeps a provider sign-in from being spent for nothing: everything the code check can
    refuse without looking at the code is refused before the sign-in is redeemed, and a wrong code
    after it answers :data:`SIGN_IN_PROOF_HEADER`, so the next try needs only the right code."""
    two_step = bool(getattr(current_user, "twoFactorEnabled", False))
    code = (payload.twoFactorCode or "").strip()
    fresh = await _first_password_code_allowance(current_user, code) if two_step else current_user
    try:
        provider, proof_expires_at = await _provider_proof(payload, current_user)
    except HTTPException:
        if two_step:
            await two_factor.refund_first_password_check(current_user.id)
        raise
    if two_step:
        # An accepted code is still spent (replay-proof); a right one costs no check.
        if not await two_factor.consume_any_code(fresh, code):
            logger.info("auth: first-password two-step code refused for user %s", current_user.id)
            refused = _refuse_code(two_factor.WRONG_CODE_DETAIL)
            refused.headers[SIGN_IN_PROOF_HEADER] = _mint_sign_in_proof(current_user.id, provider, proof_expires_at)
            raise refused
        await two_factor.refund_first_password_check(current_user.id)
    updated = await _write_own_password(current_user, payload.newPassword)
    logger.warning("AUDIT first password set by the account itself: user=%s", current_user.id)
    response.headers[SESSION_TOKEN_HEADER] = _session_token(updated)
    await email_outbox.notify_password_event(updated, mailer.PASSWORD_ADDED)
    return {"ok": True}
