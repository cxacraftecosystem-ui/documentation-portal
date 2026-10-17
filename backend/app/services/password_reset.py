""""FORGOT YOUR PASSWORD?" — the self-service door to the password link.

Ported on 2026-10-16 from the Design Prototype Workshop's ``backend/app/services/password_reset.py``
(same names), itself ported in substance from ``C:/dev/IIT/cxa-cms/lib/auth/password-reset.ts``
(``requestPasswordReset``) and ``app/api/auth/forgot-password/route.ts``. Read those before changing anything here; the rules
below are theirs, and every one of them is about what an answer reveals.

ONE TOKEN MECHANISM, NOT TWO. The link is ``credential_links.issue_link(purpose=RESET)`` — the same
signed, fingerprint-bound, single-use link an administrator makes, landing on the same
``/set-password`` screen and redeemed by the same ``POST /auth/set-password``. So everything that
route guarantees holds here too and is NOT re-implemented: single use, the two-hour expiry, every
session ended when the password is set (``sessionsValidFrom``), and — ⚠ — **TWO-STEP VERIFICATION
STILL ASKED FOR**: redeeming a link mints no session; the person signs in afterwards and meets the
second step as usual. Nothing here touches ``twoFactorEnabled`` and nothing may.

THE RULES:

1. **ONE ANSWER.** A well-formed request always gets :data:`FORGOT_PASSWORD_MESSAGE`, for a real
   account, an unknown address, an account with no password, one the allow-list does not admit, a
   throttled address, a failed send, a site with no mail. Only the REQUEST may be refused (422 for a
   malformed address, 429 for too many requests from one connection).
2. **THE SAME SPEED.** Everything that depends on the address runs after the response has gone
   (FastAPI ``BackgroundTasks``), so a known address does not take longer to answer than an unknown
   one.
3. **TWO LIMITS.** Per client network in the route (``rate_limit.forgot_password_attempt``, a 429,
   because it is about the connection). Per ADDRESS here, SILENT (:data:`ADDRESS_BUDGET` an hour,
   counting only links this door minted, and only for an account that would actually be mailed) —
   a 429 there would say the address had been asked about.
4. **THE LINK'S ORIGIN IS THE CONFIGURED ONE.** ``credential_links.link_for`` builds it from
   ``NEXT_PUBLIC_APP_URL``; nothing in the request (``Host``, ``X-Forwarded-Host``, ``Origin``) reaches
   it, so a forged header cannot send somebody's credential to the forger's domain.
5. **AUDITED.** One ``PasswordResetRequest`` row per request, unknown addresses included, with the
   typed address, the matched account id, the outcome and the client address.

WHAT A REQUEST DELIBERATELY DOES NOT DO: end any session or clear any throttle. Anybody can type
anybody's address here; if asking were enough to sign somebody out, this form would be a way to do
that to the whole staff list on a loop. Sessions end when the link is USED, which is when the person
has proved they hold the mailbox.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from app.core.db import db
from app.services import access_roster, credential_links, mailer

logger = logging.getLogger(__name__)

#: THE ONLY ANSWER the endpoint gives a well-formed request. It does not mention two-step verification:
#: whether an account has a second factor is not something to tell a stranger who typed its address.
#: One short sentence: the screen already says how long the link lasts and that an administrator can
#: make one.
FORGOT_PASSWORD_MESSAGE = "If that address has an account, a reset link is on its way."

#: The per-client refusal (429). About the connection, never about an account.
TOO_MANY_REQUESTS_DETAIL = (
    "Too many password-link requests from this connection. Try again in a few minutes. If you are "
    "locked out now, an administrator can make you a link."
)

#: Self-service links one account's address may be sent in an hour. Silent when spent.
ADDRESS_BUDGET = 3
ADDRESS_WINDOW_HOURS = 1

#: What happened, for the audit row and the tests. Never shown to the person who asked.
EMAILED = "emailed"
UNKNOWN_ADDRESS = "unknown-address"
NO_PASSWORD = "no-password"
NOT_ADMITTED = "not-admitted"
ADDRESS_RATE_LIMITED = "address-rate-limited"
EMAIL_NOT_CONFIGURED = "email-not-configured"
EMAIL_FAILED = "email-failed"

#: Bound on what is stored from a request header.
MAX_USER_AGENT = 300


def normalise_address(raw: str) -> str:
    return (raw or "").strip().lower()


async def _admitted(user: Any) -> bool:
    """Would a sign-in by this account get past the allow-list? The same three answers as
    ``routes/auth.assert_access_admitted``: the gate switched off, the break-glass (the configured
    master admin address, or a live MASTER_ADMIN role), or an ACTIVE row."""
    if not access_roster.gate_enforced():
        return True
    email = getattr(user, "email", None)
    if access_roster.is_master_admin_email(email):
        return True
    if access_roster.role_value(getattr(user, "role", None)) == "MASTER_ADMIN":
        return True
    return await access_roster.roster_admits(email)


async def _decide_and_send(email: str) -> tuple[str, str | None]:
    """``(outcome, account id)``. Raises only on a database fault, which the caller records."""
    user = await db.user.find_unique(where={"email": email})
    if user is None:
        return UNKNOWN_ADDRESS, None
    if getattr(user, "passwordHash", None) is None:
        # Signs in with a provider, or has not claimed an invitation. An administrator can still make
        # it a link; a signed-in person with no password sets one from their own account screen.
        return NO_PASSWORD, user.id
    if not await _admitted(user):
        return NOT_ADMITTED, user.id
    since = datetime.now(UTC) - timedelta(hours=ADDRESS_WINDOW_HOURS)
    if await credential_links.self_service_links_since(user.id, since) >= ADDRESS_BUDGET:
        return ADDRESS_RATE_LIMITED, user.id
    if not mailer.mail_configured():
        return EMAIL_NOT_CONFIGURED, user.id
    try:
        await credential_links.issue_link(
            user=user,
            purpose=credential_links.RESET,
            issued_by_id=None,
            deliver_by=credential_links.EMAIL,
            requested_by="self",
        )
    except credential_links.IssueThrottled:
        return ADDRESS_RATE_LIMITED, user.id
    except credential_links.DeliveryUnavailable:
        return EMAIL_NOT_CONFIGURED, user.id
    return EMAILED, user.id


async def request_password_reset(
    raw_email: str, *, ip_address: str | None = None, user_agent: str | None = None
) -> str:
    """Handle one request, after the answer has gone. NEVER RAISES. Returns the outcome."""
    email = normalise_address(raw_email)
    outcome = EMAIL_FAILED
    account_id: str | None = None
    try:
        outcome, account_id = await _decide_and_send(email)
    except Exception as exc:  # noqa: BLE001 - the person already has their answer; record the fault
        logger.error("auth: a password-reset request could not be processed (%s)", type(exc).__name__)
    try:
        await db.passwordresetrequest.create(
            data={
                "email": email,
                "accountId": account_id,
                "outcome": outcome,
                "ipAddress": ip_address,
                "userAgent": (user_agent or "")[:MAX_USER_AGENT] or None,
            }
        )
    except Exception as exc:  # noqa: BLE001 - an audit write must not take the worker down
        logger.error("auth: a password-reset request was not recorded (%s)", type(exc).__name__)
    # Ids and the outcome only — never the address, never the link.
    logger.info(
        "auth: forgot-password request for account %s: %s", account_id or "-", outcome
    )
    return outcome
