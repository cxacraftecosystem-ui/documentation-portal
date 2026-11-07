import logging
from datetime import UTC, datetime
from typing import Any

from app.core.config import get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

ADMITTING_STATUS = "ACTIVE"

DECIDABLE_STATUSES = frozenset({"ACTIVE", "REJECTED", "SUSPENDED"})

MAX_EMAIL_LENGTH = 254

MAX_FULL_NAME_LENGTH = 200
MAX_NOTES_LENGTH = 2000


INVALID_CREDENTIALS_DETAIL = "Invalid email or password"

ACCESS_PENDING_DETAIL = (
    "Your access request is awaiting approval by an administrator. "
    "Your sign-in details are correct; there is nothing more to do until an administrator approves "
    "this address."
)

ACCESS_REJECTED_DETAIL = (
    "This address isn't approved to sign in. "
    "Contact your administrator if you believe this is a mistake."
)

ACCESS_SUSPENDED_DETAIL = (
    "Your access has been suspended. Contact your administrator."
)

_REFUSAL_DETAILS: dict[str, str] = {
    "PENDING": ACCESS_PENDING_DETAIL,
    "REJECTED": ACCESS_REJECTED_DETAIL,
    "SUSPENDED": ACCESS_SUSPENDED_DETAIL,
}


def refusal_detail(status: Any) -> str:
    return _REFUSAL_DETAILS.get(status_value(status), ACCESS_REJECTED_DETAIL)



def normalise_email(email: Any) -> str:
    address = str(email or "").strip().lower()
    if not address or len(address) > MAX_EMAIL_LENGTH:
        return ""
    return address


def status_value(status: Any) -> str:
    return str(getattr(status, "value", status) or "")


def role_value(role: Any) -> str:
    return str(getattr(role, "value", role) or "")


def is_master_admin_email(email: Any) -> bool:
    address = normalise_email(email)
    if not address:
        return False
    return address == normalise_email(get_settings().master_admin_email)


def gate_enforced() -> bool:
    return bool(get_settings().access_roster_enforced)



async def roster_row(email: Any) -> Any | None:
    address = normalise_email(email)
    if not address:
        return None
    return await db.accessroster.find_unique(where={"email": address})


BARRED_STATUSES = frozenset({"REJECTED", "SUSPENDED"})


async def account_barred(account: Any) -> bool:
    email = getattr(account, "email", None)
    if is_master_admin_email(email) or role_value(getattr(account, "role", None)) == "MASTER_ADMIN":
        return False
    row = await roster_row(email)
    return row is not None and status_value(row.status) in BARRED_STATUSES


async def roster_admits(email: Any) -> bool:
    address = normalise_email(email)
    if not address:
        return False
    row = await db.accessroster.find_first(
        where={"email": address, "status": ADMITTING_STATUS}
    )
    return row is not None


async def mark_first_seen(email: Any) -> None:
    address = normalise_email(email)
    if not address:
        return
    await db.accessroster.update_many(
        where={"email": address, "firstSeenAt": None},
        data={"firstSeenAt": datetime.now(UTC)},
    )


async def record_access_request(email: Any) -> None:
    address = normalise_email(email)
    if not address:
        return

    now = datetime.now(UTC)
    existing = await db.accessroster.find_unique(where={"email": address})
    if existing is not None:
        await db.accessroster.update(
            where={"email": address},
            data={
                "requestCount": (existing.requestCount or 0) + 1,
                "lastRequestedAt": now,
            },
        )
        return

    cap = max(0, int(get_settings().access_roster_max_pending))
    pending = await pending_count()
    if pending >= cap:
        logger.warning(
            "Access request from a new address dropped: the pending roster is at its ceiling "
            "(%s of %s). An administrator must clear the queue before new requests are recorded.",
            pending,
            cap,
        )
        return

    await db.accessroster.create(
        data={
            "email": address,
            "status": "PENDING",
            "requestCount": 1,
            "firstRequestedAt": now,
            "lastRequestedAt": now,
        }
    )


async def pending_count() -> int:
    return await db.accessroster.count(where={"status": "PENDING"})



async def ensure_admitted(
    email: Any,
    *,
    granted_role: str | None = None,
    full_name: str | None = None,
    notes: str | None = None,
    decided_by_id: str | None = None,
    joined_at: datetime | None = None,
    client: Any = None,
) -> Any:
    address = normalise_email(email)
    if not address:
        raise ValueError("An access roster entry needs an email address")

    now = datetime.now(UTC)
    role = role_value(granted_role) or None
    store = client if client is not None else db
    existing = await store.accessroster.find_unique(where={"email": address})
    if existing is not None:
        data: dict[str, Any] = {
            "status": ADMITTING_STATUS,
            "decidedAt": now,
            "decidedById": decided_by_id,
            "joinedAt": existing.joinedAt or joined_at or now,
        }
        if role:
            data["grantedRole"] = role
        if full_name is not None:
            data["fullName"] = clean_text(full_name, MAX_FULL_NAME_LENGTH)
        if notes is not None:
            data["notes"] = clean_text(notes, MAX_NOTES_LENGTH)
        return await store.accessroster.update(where={"email": address}, data=data)

    return await store.accessroster.create(
        data={
            "email": address,
            "status": ADMITTING_STATUS,
            "grantedRole": role or get_settings().default_signup_role,
            "fullName": clean_text(full_name, MAX_FULL_NAME_LENGTH),
            "notes": clean_text(notes, MAX_NOTES_LENGTH),
            "joinedAt": joined_at or now,
            "decidedAt": now,
            "decidedById": decided_by_id,
            "requestCount": 0,
            "firstRequestedAt": now,
            "lastRequestedAt": now,
        }
    )


def clean_text(value: Any, limit: int) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return text[:limit]



def roster_payload(row: Any, *, account: Any = None) -> dict[str, Any]:
    return {
        "id": row.id,
        "email": row.email,
        "status": status_value(row.status),
        "grantedRole": role_value(row.grantedRole),
        "fullName": row.fullName,
        "notes": row.notes,
        "joinedAt": _iso(row.joinedAt),
        "firstSeenAt": _iso(row.firstSeenAt),
        "requestCount": row.requestCount,
        "firstRequestedAt": _iso(row.firstRequestedAt),
        "lastRequestedAt": _iso(row.lastRequestedAt),
        "decidedAt": _iso(row.decidedAt),
        "decidedById": row.decidedById,
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
        "userId": getattr(account, "id", None),
        "accountRole": role_value(getattr(account, "role", None)) or None,
        "accountName": getattr(account, "name", None),
    }


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None
