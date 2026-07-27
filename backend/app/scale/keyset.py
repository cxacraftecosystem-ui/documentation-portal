"""Keyset (cursor) pagination, and the cursor format itself. Flag-gated; off by default.

THE PROBLEM WITH OFFSETS. ``skip=N`` makes Postgres produce and discard N rows before it returns
anything, so page 50 costs fifty pages of work and page 1 costs one. On 925 media rows that is
invisible; the reason to build the helper now is that the cost is linear in the offset and the fix
is not something you retrofit under load. Keyset paging asks "the next 20 rows after THIS one",
which is an index seek — the same cost on page 1 and page 500.

THE OTHER PROBLEM IT FIXES, which matters at today's volumes already: offset paging over a
non-unique sort key is not stable. Every list route here orders by ``createdAt desc`` alone, and two
records created in the same millisecond — an import, a batch upload, a phone syncing offline work —
have no defined order between them. Postgres may order them differently on the query for page 2 than
it did for page 1, and the researcher sees one row twice and never sees another. Keyset paging
cannot express "after this row" without a unique tie-break, so adopting it forces the ``(sortField,
id)`` ordering that makes offset paging correct too.

WHY THE CURSOR IS SIGNED. It is decoded straight back into a Prisma ``where`` clause. An unsigned
cursor is a query fragment supplied by the client, and while the visibility filter is applied
separately (so no rows leak), a hand-crafted value is a free hand at the query planner and at the
type coercion in between. The HMAC costs microseconds and means the only cursors this API will act
on are ones it minted.
"""

import base64
import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Iterable, Mapping

from app.scale.flags import keyset_enabled, log_once, settings

# Long enough that forging one is not worth anyone's afternoon, short enough that the cursor stays a
# reasonable query-string value. The cursor is not a credential — it names a position in a list the
# caller is already authorised to read — so this is tamper-evidence, not authentication.
_TAG_LENGTH = 16


@dataclass(frozen=True)
class Cursor:
    """A decoded position: the sort value and id of the last row the client received."""

    sort_field: str
    value: Any
    record_id: str
    descending: bool


def _sign(body: str) -> str:
    secret = settings().jwt_secret.encode("utf-8")
    return hmac.new(secret, body.encode("ascii"), hashlib.sha256).hexdigest()[:_TAG_LENGTH]


def _encode_value(value: Any) -> dict[str, Any]:
    """Tag the sort value with its type so decoding can hand Prisma the type it was given.

    A datetime that came back as the string "2026-07-26T10:00:00+00:00" is not the same query
    argument as the datetime itself, and the difference surfaces as a comparison the engine performs
    against text. So the type travels with the value rather than being guessed on the way back.
    """
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
    """Mint the opaque token that means "resume after this row"."""
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
    """Decode a cursor, or None if there is nothing usable here.

    None covers four cases deliberately treated the same: no cursor was sent, the flag is off, the
    token was tampered with, and the token belongs to a different sort order than this route uses.
    In all four the caller's correct response is identical — fall back to offset paging and serve
    the requested page — so distinguishing them would only give a route four branches where one is
    right. The two that are not simply "no cursor" are logged once.

    ``sort_field`` and ``descending`` are checked, not trusted from the token: a cursor minted for
    ``createdAt desc`` fed to a route ordering by ``name asc`` describes a position that does not
    exist in that list, and honouring it would silently drop rows.
    """
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
    except (KeyError, ValueError, TypeError) as exc:  # binascii.Error subclasses ValueError
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
    """The Prisma filter for "strictly after this row" in the cursor's own ordering.

    Expressed as the row-value comparison ``(sort, id) < (v, i)`` written out longhand, because
    Prisma has no tuple comparison. Postgres will use a composite index on ``(sortField, id)`` for
    it; without such an index this is still correct, just not yet fast — which is the right order to
    do things in, since the index is a migration and this is not.

    Merge it into the route's existing ``where`` under ``AND``, never at the top level: these routes
    already build ``OR`` clauses for free-text search, and a second top-level ``OR`` would replace
    the first one.
    """
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
    """The ordering keyset paging requires: the sort column, then the id as a unique tie-break.

    Safe to adopt on the offset path at the same time, and worth doing independently — see the
    module docstring on rows that appear twice when a non-unique sort has no tie-break.
    """
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
    """A cursor for the row after the last one in ``items``, or None when this was the last page.

    Returns None on a short page — fewer rows than asked for means there is nothing after them, and
    a cursor that leads to an empty page is a "next" button that lies.
    """
    rows = list(items)
    if not rows or len(rows) < page_size:
        return None
    last = rows[-1]
    value = getattr(last, sort_field, None)
    record_id = getattr(last, id_field, None)
    if value is None or record_id is None:
        # A NULL sort value has no defined position in a keyset ordering, so there is no honest
        # cursor to hand back. Offset paging still works; the client just does not get the fast path.
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
    """Add ``nextCursor`` to an existing page payload without disturbing anything already in it.

    Purely additive, which is what keeps page numbers in the UI contract: ``items``, ``total``,
    ``page``, ``pageSize`` and ``pages`` still mean exactly what they mean today, and a client that
    has never heard of cursors keeps working unchanged. ``nextCursor`` is absent — not null — when
    there is no next page, so its presence is the whole test a client needs.
    """
    if cursor is None:
        return payload
    return {**payload, "nextCursor": cursor}
