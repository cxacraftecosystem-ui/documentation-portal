import base64
import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Iterable, Mapping

from app.scale.flags import keyset_enabled, log_once, settings

_TAG_LENGTH = 16


@dataclass(frozen=True)
class Cursor:
    sort_field: str
    value: Any
    record_id: str
    descending: bool


def _sign(body: str) -> str:
    secret = settings().jwt_secret.encode("utf-8")
    return hmac.new(secret, body.encode("ascii"), hashlib.sha256).hexdigest()[:_TAG_LENGTH]


def _encode_value(value: Any) -> dict[str, Any]:
    if isinstance(value, datetime):
        return {"t": "dt", "v": value.isoformat()}
    if isinstance(value, date):
        return {"t": "d", "v": value.isoformat()}
    if isinstance(value, Decimal):
        return {"t": "dec", "v": str(value)}
    if isinstance(value, bool) or value is None or isinstance(value, (str, int, float)):
        return {"t": "j", "v": value}
    raise TypeError(f"{type(value).__name__} cannot be a keyset cursor value")


def _decode_value(payload: Mapping[str, Any]) -> Any:
    kind = payload.get("t")
    raw = payload.get("v")
    if kind == "dt":
        return datetime.fromisoformat(str(raw))
    if kind == "d":
        return date.fromisoformat(str(raw))
    if kind == "dec":
        return Decimal(str(raw))
    if kind == "j":
        return raw
    raise ValueError(f"unknown cursor value type {kind!r}")


def encode_cursor(*, sort_field: str, value: Any, record_id: str, descending: bool = True) -> str:
    body = base64.urlsafe_b64encode(
        json.dumps(
            {
                "f": sort_field,
                "k": _encode_value(value),
                "i": record_id,
                "d": bool(descending),
            },
            separators=(",", ":"),
        ).encode("utf-8")
    ).decode("ascii").rstrip("=")
    return f"{body}.{_sign(body)}"


def decode_cursor(token: str | None, *, sort_field: str, descending: bool = True) -> Cursor | None:
    if not token or not keyset_enabled():
        return None
    body, _, tag = token.partition(".")
    if not tag or not hmac.compare_digest(tag, _sign(body)):
        log_once(
            "keyset.cursor.signature",
            "Received a pagination cursor this API did not sign; ignoring it and serving the "
            "requested page by offset instead.",
        )
        return None
    try:
        padded = body + "=" * (-len(body) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")))
        cursor = Cursor(
            sort_field=str(payload["f"]),
            value=_decode_value(payload["k"]),
            record_id=str(payload["i"]),
            descending=bool(payload["d"]),
        )
    except (KeyError, ValueError, TypeError) as exc:
        log_once(
            f"keyset.cursor.parse.{type(exc).__name__}",
            "A signed pagination cursor did not parse (%s); serving the requested page by offset.",
            exc,
        )
        return None
    if cursor.sort_field != sort_field or cursor.descending != descending:
        log_once(
            "keyset.cursor.mismatch",
            "A pagination cursor for %s (%s) arrived at a route ordering by %s (%s); ignoring it.",
            cursor.sort_field,
            "desc" if cursor.descending else "asc",
            sort_field,
            "desc" if descending else "asc",
        )
        return None
    return cursor


def after_where(cursor: Cursor, *, id_field: str = "id") -> dict[str, Any]:
    operator = "lt" if cursor.descending else "gt"
    return {
        "OR": [
            {cursor.sort_field: {operator: cursor.value}},
            {
                "AND": [
                    {cursor.sort_field: cursor.value},
                    {id_field: {operator: cursor.record_id}},
                ]
            },
        ]
    }


def order_by(sort_field: str, *, descending: bool = True, id_field: str = "id") -> list[dict[str, str]]:
    direction = "desc" if descending else "asc"
    return [{sort_field: direction}, {id_field: direction}]


def next_cursor(
    items: Iterable[Any],
    *,
    sort_field: str,
    page_size: int,
    descending: bool = True,
    id_field: str = "id",
) -> str | None:
    rows = list(items)
    if not rows or len(rows) < page_size:
        return None
    last = rows[-1]
    value = getattr(last, sort_field, None)
    record_id = getattr(last, id_field, None)
    if value is None or record_id is None:
        return None
    try:
        return encode_cursor(
            sort_field=sort_field, value=value, record_id=str(record_id), descending=descending
        )
    except TypeError as exc:
        log_once(
            f"keyset.encode.{sort_field}",
            "Cannot build a cursor from %s (%s); this endpoint will page by offset only.",
            sort_field,
            exc,
        )
        return None


def with_cursor(payload: dict[str, Any], cursor: str | None) -> dict[str, Any]:
    if cursor is None:
        return payload
    return {**payload, "nextCursor": cursor}
