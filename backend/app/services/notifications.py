from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

from app.core.config import Settings, get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

MAX_TITLE_LENGTH = 200
MAX_BODY_LENGTH = 1000
MAX_LINK_LENGTH = 500


@dataclass(frozen=True)
class Delivery:
    notification: Any | None = None
    email: Any | None = None

    @property
    def in_app(self) -> bool:
        return self.notification is not None

    @property
    def emailed(self) -> bool:
        return self.email is not None


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


def _clip(text: Any, limit: int) -> str | None:
    value = str(text or "").strip()
    if not value:
        return None
    return value if len(value) <= limit else value[: limit - 1].rstrip() + "…"


def _app_path(link: Any) -> str | None:
    value = _clip(link, MAX_LINK_LENGTH)
    if value is None or not value.startswith("/") or value.startswith("//"):
        return None
    return value


async def wants_email(user_id: str, preference: str | None) -> bool:
    if not preference:
        return True
    row = await db.userpreference.find_unique(where={"userId": user_id})
    if row is None:
        return True
    value = getattr(row, preference, True)
    return True if value is None else bool(value)


async def _recipient(user: Any) -> Any | None:
    if isinstance(user, str):
        return await db.user.find_unique(where={"id": user})
    return user


async def notify(
    user: Any,
    kind: str,
    title: str,
    body: str | None = None,
    link: str | None = None,
    *,
    email_kind: str | None = None,
    email_params: dict[str, Any] | None = None,
    preference: str | None = None,
    to_address: str | None = None,
    in_app: bool = True,
    settings: Settings | None = None,
) -> Delivery:
    from app.services import email_outbox

    settings = settings or get_settings()
    try:
        person = await _recipient(user)
    except Exception:
        logger.exception("notify: could not read the recipient of a %s notice", kind)
        return Delivery()
    user_id = _value(person, "id")
    if not user_id:
        return Delivery()

    email_row: Any | None = None
    if email_kind:
        try:
            address = (to_address or _value(person, "email") or "").strip()
            if address and await wants_email(str(user_id), preference):
                params = {"recipientName": _value(person, "name"), **(email_params or {})}
                email_row = await email_outbox.enqueue(
                    email_kind,
                    to_address=address,
                    recipient_id=str(user_id),
                    params=params,
                    settings=settings,
                )
        except Exception:
            logger.exception("notify: could not queue the %s e-mail for account %s", email_kind, user_id)

    row: Any | None = None
    if in_app:
        try:
            row = await db.notification.create(
                data={
                    "userId": str(user_id),
                    "kind": str(kind),
                    "title": _clip(title, MAX_TITLE_LENGTH) or str(kind),
                    "body": _clip(body, MAX_BODY_LENGTH),
                    "link": _app_path(link),
                    "emailMessageId": _value(email_row, "id") if email_row is not None else None,
                }
            )
        except Exception:
            logger.exception("notify: could not write the %s notice for account %s", kind, user_id)
    return Delivery(notification=row, email=email_row)


def serialize(row: Any) -> dict[str, Any]:
    read_at = _value(row, "readAt")
    created_at = _value(row, "createdAt")
    return {
        "id": _value(row, "id"),
        "kind": _value(row, "kind"),
        "title": _value(row, "title"),
        "body": _value(row, "body"),
        "link": _value(row, "link"),
        "read": read_at is not None,
        "readAt": read_at.isoformat() if hasattr(read_at, "isoformat") else read_at,
        "createdAt": created_at.isoformat() if hasattr(created_at, "isoformat") else created_at,
    }
