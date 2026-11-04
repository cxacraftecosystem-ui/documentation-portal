import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from app.core.db import db
from app.services import access_roster, credential_links, mailer

logger = logging.getLogger(__name__)

FORGOT_PASSWORD_MESSAGE = "If that address has an account, a reset link is on its way."

TOO_MANY_REQUESTS_DETAIL = (
    "Too many password-link requests from this connection. Try again in a few minutes. If you are "
    "locked out now, an administrator can make you a link."
)

ADDRESS_BUDGET = 3
ADDRESS_WINDOW_HOURS = 1

EMAILED = "emailed"
UNKNOWN_ADDRESS = "unknown-address"
NO_PASSWORD = "no-password"
NOT_ADMITTED = "not-admitted"
ADDRESS_RATE_LIMITED = "address-rate-limited"
EMAIL_NOT_CONFIGURED = "email-not-configured"
EMAIL_FAILED = "email-failed"

MAX_USER_AGENT = 300


def normalise_address(raw: str) -> str:
    return (raw or "").strip().lower()


async def _admitted(user: Any) -> bool:
    if not access_roster.gate_enforced():
        return True
    email = getattr(user, "email", None)
    if access_roster.is_master_admin_email(email):
        return True
    if access_roster.role_value(getattr(user, "role", None)) == "MASTER_ADMIN":
        return True
    return await access_roster.roster_admits(email)


async def _decide_and_send(email: str) -> tuple[str, str | None]:
    user = await db.user.find_unique(where={"email": email})
    if user is None:
        return UNKNOWN_ADDRESS, None
    if getattr(user, "passwordHash", None) is None:
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
    email = normalise_address(raw_email)
    outcome = EMAIL_FAILED
    account_id: str | None = None
    try:
        outcome, account_id = await _decide_and_send(email)
    except Exception as exc:  # noqa: BLE001
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
    except Exception as exc:  # noqa: BLE001
        logger.error("auth: a password-reset request was not recorded (%s)", type(exc).__name__)
    logger.info(
        "auth: forgot-password request for account %s: %s", account_id or "-", outcome
    )
    return outcome
