from __future__ import annotations

import base64
import hashlib
import hmac
import re
import uuid
from datetime import datetime, timedelta
from typing import Any

from app.services.datareport import i18n

DAYS_MIN = 1
DAYS_MAX = 30
DAYS_DEFAULT = 7
SHARES_PER_OWNER = 50
VIEWS_LIST_MAX = 500
VIEWS_KEPT_MAX = 5000
REPEAT_VIEW_SECONDS = 60
USER_AGENT_MAX = 300
TOKEN_LENGTH = 43
OPTION_KEYS = ("sheets", "answers", "rows", "links", "thumbnails", "locale", "extras", "q", "name")
_TOKEN = re.compile(r"^[A-Za-z0-9_-]{43}$")
_SALT = b"data-report-share:"


LABELS = (
    ("eyebrow", "{product} · Live report"),
    ("sharedBy", "Shared by"),
    ("teamMember", "a team member"),
    ("figuresFrom", "Figures from"),
    ("linkExpires", "Link expires"),
    ("truncated", "Large folder: figures cover the first 5,000 of each kind."),
    ("keyFigures", "Key figures"),
    ("showAsTable", "Show as table"),
    ("date", "Date"),
    ("category", "Category"),
    ("legend", "Legend"),
    ("nothingYet", "Nothing to show yet."),
    ("earlierOmitted", "Showing the latest 52 weeks; {n} earlier not shown."),
    ("sectionsOmitted", "{n} more sections not shown."),
    ("weekOf", "week of {date}"),
    ("periods", "{title}, {n} periods"),
    ("withheldOne", "{n} recording is left out for consent."),
    ("withheldMany", "{n} recordings are left out for consent."),
    ("readOnly", "Read-only. Figures were fixed when the link was made."),
    ("filter.status", "Review status"),
    ("opening", "Opening the report…"),
    ("expired", "This link has expired or was turned off."),
    ("failed", "Couldn't open this report. Check your connection and try again."),
    ("incomplete", "This link is incomplete. Open the whole link."),
    ("tryAgain", "Try again"),
)


def locale_of(value: Any) -> str:
    return "hi" if str(value or "").strip().lower() == "hi" else "en"


def labels(locale: str) -> dict[str, str]:
    t = i18n.translator(locale_of(locale))
    return {name: t(f"share.{name}", english) for name, english in LABELS}


class Invalid(ValueError):
    pass


def new_id() -> str:
    return uuid.uuid4().hex


def token_for(share_id: str, secret: str) -> str:
    mac = hmac.new(secret.encode("utf-8"), _SALT + share_id.encode("utf-8"), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(mac).decode("ascii").rstrip("=")[:TOKEN_LENGTH]


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def well_formed(token: str) -> bool:
    return bool(_TOKEN.match(token or ""))


def address_hash(address: str, secret: str) -> str:
    return hmac.new(secret.encode("utf-8"), b"report-share-viewer:" + address.encode("utf-8"), hashlib.sha256).hexdigest()[:32]


def share_url(base: Any, token: str, locale: Any = "en") -> str:
    url = f"{str(base or '').rstrip('/')}/report-share/{token}"
    return f"{url}?lang=hi" if locale_of(locale) == "hi" else url


def days_of(value: Any) -> int:
    if value is None:
        return DAYS_DEFAULT
    if isinstance(value, bool) or not isinstance(value, int | float) or int(value) != value:
        raise Invalid("expiresInDays must be a whole number of days")
    days = int(value)
    if days < DAYS_MIN or days > DAYS_MAX:
        raise Invalid(f"A link lasts {DAYS_MIN} to {DAYS_MAX} days.")
    return days


def options_of(value: Any) -> dict[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise Invalid("options must be an object")
    return {key: value[key] for key in OPTION_KEYS if key in value}


FILTERS_MAX = 10
FILTER_TEXT_MAX = 64


def filters_of(value: Any) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict) or len(value) > FILTERS_MAX:
        raise Invalid(f"filters is an object of at most {FILTERS_MAX} entries")
    out: dict[str, str] = {}
    for key, item in value.items():
        if item is None or item == "":
            continue
        if not isinstance(key, str) or not isinstance(item, str | int | float) or isinstance(item, bool):
            raise Invalid("A filter is a name and a short text value")
        text = str(item).strip()
        if not key.strip() or len(key) > FILTER_TEXT_MAX or len(text) > FILTER_TEXT_MAX:
            raise Invalid("A filter is a name and a short text value")
        out[key.strip()] = text
    return out


def expiry(now: datetime, days: int) -> datetime:
    return now + timedelta(days=days)


def live(row: Any, now: datetime) -> bool:
    if row is None or getattr(row, "revokedAt", None) is not None:
        return False
    expires = getattr(row, "expiresAt", None)
    return expires is not None and _aware(expires, now) > now


def _aware(value: datetime, like: datetime) -> datetime:
    if value.tzinfo is None and like.tzinfo is not None:
        return value.replace(tzinfo=like.tzinfo)
    return value


def state_of(row: Any, now: datetime) -> str:
    if getattr(row, "revokedAt", None) is not None:
        return "revoked"
    return "active" if live(row, now) else "expired"


def user_agent_of(value: str | None) -> str | None:
    text = " ".join(str(value or "").split())
    return text[:USER_AGENT_MAX] or None


def _iso(value: Any) -> str | None:
    return value.isoformat() if value is not None else None


def share_out(row: Any, *, url: str, now: datetime, views: int = 0, last_opened: Any = None) -> dict[str, Any]:
    return {
        "id": row.id,
        "url": url,
        "path": row.path,
        "title": row.title,
        "options": dict(row.options) if isinstance(row.options, dict) else {},
        "createdAt": _iso(row.createdAt),
        "expiresAt": _iso(row.expiresAt),
        "revokedAt": _iso(row.revokedAt),
        "state": state_of(row, now),
        "views": views,
        "lastOpenedAt": _iso(last_opened),
    }


def view_out(row: Any) -> dict[str, Any]:
    viewer = getattr(row, "viewer", None)
    return {
        "id": row.id,
        "openedAt": _iso(row.openedAt),
        "viewer": {"id": viewer.id, "name": getattr(viewer, "name", None)} if viewer is not None else None,
        "visitor": str(row.ipHash or "")[:8] or None,
        "userAgent": row.userAgent,
    }


def public_out(row: Any) -> dict[str, Any]:
    snapshot = dict(row.snapshot) if isinstance(row.snapshot, dict) else {}
    locale = locale_of(snapshot.get("locale"))
    return {
        **snapshot,
        "locale": locale,
        "labels": labels(locale),
        "expiresAt": _iso(row.expiresAt),
        "sharedAt": _iso(row.createdAt),
    }
