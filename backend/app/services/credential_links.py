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

INVITE = "INVITE"
RESET = "RESET"

INVITE_TTL_HOURS = 72
RESET_TTL_HOURS = 2

ISSUE_BUDGET = 4
ISSUE_WINDOW_HOURS = 1

MAX_TOKEN_LENGTH = 1024

SET_PASSWORD_PATH = "/set-password"

MIN_PASSWORD_LENGTH = 8


def ttl_hours(purpose: str) -> int:
    return INVITE_TTL_HOURS if purpose == INVITE else RESET_TTL_HOURS


def purpose_for(user: Any) -> str:
    if getattr(user, "passwordHash", None) is None:
        return INVITE
    if getattr(user, "firstLoginAt", None) is None:
        return INVITE
    return RESET



def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _unb64(raw: str) -> bytes:
    return base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4))


def credential_fingerprint(password_hash: str | None) -> str:
    return hashlib.sha256((password_hash or "no-password").encode("utf-8")).hexdigest()[:16]


def fingerprint_matches(password_hash: str | None, presented: str) -> bool:
    return hmac.compare_digest(credential_fingerprint(password_hash), presented)


def _sign(body: str) -> str:
    secret = get_settings().jwt_secret.encode("utf-8")
    return _b64(hmac.new(secret, body.encode("ascii"), hashlib.sha256).digest())


def mint_token(*, user_id: str, purpose: str, expires_at: datetime, password_hash: str | None) -> str:
    payload = {
        "v": 1,
        "sub": user_id,
        "purpose": purpose,
        "exp": int(expires_at.timestamp()),
        "cred": credential_fingerprint(password_hash),
        "n": secrets.token_urlsafe(12),
    }
    body = _b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    return f"{body}.{_sign(body)}"


def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


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
    issued_by_id: str | None = None


def verify_token(raw: str | None, *, now: datetime | None = None) -> TokenVerdict:
    moment = now or datetime.now(UTC)
    if not isinstance(raw, str):
        return TokenVerdict(False, MISSING)
    token = raw.strip()
    if not token:
        return TokenVerdict(False, MISSING)
    if len(token) > MAX_TOKEN_LENGTH:
        return TokenVerdict(False, MALFORMED)

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
    nonce = decoded.get("n")
    if not isinstance(nonce, str) or not 0 < len(nonce) <= 64:
        return TokenVerdict(False, MALFORMED)
    if expiry <= int(moment.timestamp()):
        return TokenVerdict(False, EXPIRED)

    return TokenVerdict(True, None, subject, purpose)



@dataclass(frozen=True)
class DeliveredLink:
    id: str
    link: str | None
    expiresAt: str
    purpose: str
    deliveredBy: str


class CredentialDelivery(Protocol):
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
        pass


class CopyLinkDelivery:
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
        logger.info(
            "auth: %s link issued for account %s, expires %s (delivery: copy-link)",
            purpose,
            getattr(user, "id", "?"),
            expires_at.isoformat(),
        )
        return self.name


class EmailDelivery:
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
    pass


COPY_LINK = CopyLinkDelivery.name
EMAIL = EmailDelivery.name
DELIVERIES = (COPY_LINK, EMAIL)


def delivery(name: str = COPY_LINK) -> CredentialDelivery:
    return EmailDelivery() if name == EMAIL else CopyLinkDelivery()



def link_for(token: str) -> str:
    base = str(get_settings().next_public_app_url).rstrip("/")
    from urllib.parse import quote

    return f"{base}{SET_PASSWORD_PATH}?token={quote(token, safe='')}"


class IssueThrottled(Exception):
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
    if deliver_by == EMAIL:
        from app.services import mailer

        if not mailer.mail_configured() or not getattr(user, "email", None):
            raise DeliveryUnavailable()
    now = datetime.now(UTC)
    since = now - timedelta(hours=ISSUE_WINDOW_HOURS)
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
    return await db.passwordresettoken.find_unique(where={"id": row_id})


async def revoke_link(row_id: str) -> bool:
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
    verdict = verify_token(raw)
    if not verdict.ok:
        return verdict
    row = await db.passwordresettoken.find_unique(where={"tokenHash": token_digest(str(raw).strip())})
    if row is None:
        return TokenVerdict(False, UNKNOWN_ACCOUNT)
    if row.revokedAt is not None:
        return TokenVerdict(False, REVOKED)
    if row.usedAt is not None:
        return TokenVerdict(False, SPENT)
    user = await db.user.find_unique(where={"id": verdict.user_id})
    if user is None:
        return TokenVerdict(False, UNKNOWN_ACCOUNT)
    token_payload = json.loads(_unb64(str(raw).strip().split(".", 1)[0]).decode("utf-8"))
    if not fingerprint_matches(user.passwordHash, str(token_payload.get("cred"))):
        return TokenVerdict(False, SPENT)
    return TokenVerdict(True, None, user.id, verdict.purpose, row.issuedById)


async def revoke_outstanding(user_id: str) -> int:
    return await db.passwordresettoken.update_many(
        where={"userId": user_id, "usedAt": None, "revokedAt": None},
        data={"revokedAt": datetime.now(UTC)},
    )


async def mark_used(raw: str) -> None:
    digest = token_digest(raw.strip())
    row = await db.passwordresettoken.find_unique(where={"tokenHash": digest})
    if row is not None and row.usedAt is None:
        await db.passwordresettoken.update(
            where={"id": row.id}, data={"usedAt": datetime.now(UTC)}
        )


async def self_service_links_since(user_id: str, since: datetime) -> int:
    return await db.passwordresettoken.count(
        where={"userId": user_id, "issuedById": None, "createdAt": {"gte": since}}
    )
