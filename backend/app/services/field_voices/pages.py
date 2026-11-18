from __future__ import annotations

import json
import math
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from app.services.field_voices import contract, ledger
from app.services.field_voices.ledger import UnknownCursor
from app.services.field_voices.sources import plain_json

__all__ = [
    "BadView",
    "RateWindow",
    "UnknownCursor",
    "assemble_page",
    "change_of",
    "generated_at",
    "page",
    "presigned",
    "state",
    "views_for",
]

PAGE_SQL = (
    'SELECT "itemId", "state", "revision", "reason", "successorId", "payload", "copyKey", '
    f'to_char("updatedAt", \'{ledger.STAMP_FORMAT}\') AS "stamp" FROM "FieldVoiceItem" '
    'WHERE "stream" = $1 AND "state" = ANY($2::text[]) AND ($3::text IS NULL OR "updatedAt" > $3::timestamp '
    'OR ("updatedAt" = $3::timestamp AND "itemId" COLLATE "C" > $4::text COLLATE "C")) '
    'ORDER BY "updatedAt", "itemId" COLLATE "C" LIMIT $5'
)
COUNT_SQL = (
    'SELECT "state", count(*)::int AS "n" FROM "FieldVoiceItem" WHERE "stream" = $1 GROUP BY "state"'
)
LIVE_REVISIONS_SQL = (
    'SELECT "itemId", "revision" FROM "FieldVoiceItem" WHERE "stream" = $1 AND "state" = \'LIVE\''
)
LATEST_SQL = (
    f'SELECT "itemId", to_char("updatedAt", \'{ledger.STAMP_FORMAT}\') AS "stamp" FROM "FieldVoiceItem" '
    'WHERE "stream" = $1 ORDER BY "updatedAt" DESC, "itemId" COLLATE "C" DESC LIMIT 1'
)
REFRESH_SQL = (
    'SELECT to_char("finishedAt", \'YYYY-MM-DD"T"HH24:MI:SS"Z"\') AS "finishedAt" FROM "FieldVoiceRefresh" '
    'WHERE "id" = 1'
)


class BadView(ValueError):
    pass


def generated_at(now: datetime | None = None) -> str:
    moment = now or datetime.now(UTC)
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class RateWindow:
    calls: int = contract.RATE_LIMIT_CALLS
    seconds: float = contract.RATE_LIMIT_WINDOW_SECONDS
    clock: Callable[[], float] = time.monotonic
    seen: dict[str, deque[float]] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def admit(self, caller: str | None) -> int | None:
        key = caller or ""
        now = self.clock()
        with self.lock:
            window = self.seen.setdefault(key, deque())
            while window and now - window[0] >= self.seconds:
                window.popleft()
            if len(window) >= self.calls:
                return max(1, math.ceil(self.seconds - (now - window[0])))
            window.append(now)
            return None


def views_for(view: str | None) -> tuple[str, list[str]]:
    chosen = view or contract.VIEW_ALL
    if chosen not in contract.VIEWS:
        raise BadView(chosen)
    states = [contract.LIVE, contract.GONE] if chosen == contract.VIEW_ALL else [contract.GONE]
    return chosen, states


def change_of(row: dict[str, Any], *, image_url: Callable[[str, dict[str, Any]], tuple[str, str]] | None) -> dict[str, Any]:
    if row["state"] == contract.GONE:
        return {
            "state": contract.GONE,
            "id": row["itemId"],
            "updatedAt": row["stamp"],
            "reason": row.get("reason"),
            "successorId": row.get("successorId"),
        }
    payload = plain_json(row.get("payload")) or {}
    change = {"state": contract.LIVE, "id": row["itemId"], "revision": row["revision"], "updatedAt": row["stamp"]}
    change.update({k: v for k, v in payload.items() if k != "id"})
    if image_url is not None and isinstance(payload.get("image"), dict):
        url, expires = image_url(str(row.get("copyKey") or ""), payload["image"])
        change["image"] = {**payload["image"], "url": url, "expiresAt": expires}
    return change


def _size(change: dict[str, Any]) -> int:
    return len(json.dumps(change, separators=(",", ":"), default=str).encode("utf-8")) + 1


def assemble_page(
    rows: list[dict[str, Any]], *, stream: str, image_url: Callable[[str, dict[str, Any]], tuple[str, str]] | None
) -> tuple[list[dict[str, Any]], bool]:
    limit = contract.PAGE_CHANGES[stream]
    budget = contract.PAGE_BYTES.get(stream)
    changes: list[dict[str, Any]] = []
    used = 0
    for row in rows[:limit]:
        change = change_of(row, image_url=image_url)
        size = _size(change)
        if budget is not None and changes and used + size > budget:
            return changes, True
        changes.append(change)
        used += size
    return changes, len(rows) > len(changes)


def presigned(copy_key: str, image: dict[str, Any], *, now: datetime | None = None) -> tuple[str, str]:
    from app.services import s3

    leaf = copy_key.rsplit("/", 1)[-1]
    url, window = s3.presign_get_url_aged(
        copy_key,
        filename=leaf,
        mime_type=str(image.get("mimeType") or "image/jpeg"),
        expires_in=contract.IMAGE_URL_SECONDS,
        disposition="inline",
    )
    moment = (now or datetime.now(UTC)) + timedelta(seconds=int(window) + contract.IMAGE_URL_HALF_LIFE_SECONDS)
    return url, generated_at(moment)


async def page(
    client: Any, *, portal: str, token: str, stream: str, view: str | None, cursor: str | None,
    image_url: Callable[[str, dict[str, Any]], tuple[str, str]] | None = None,
) -> dict[str, Any]:
    chosen, states = views_for(view)
    after: tuple[str | None, str | None] = (None, None)
    if cursor is not None:
        after = ledger.decode_cursor(token, cursor, stream=stream, view=chosen)
    limit = contract.PAGE_CHANGES[stream]
    rows = [
        dict(r)
        for r in await client.query_raw(
            PAGE_SQL, stream, states, after[0], after[1] or "", limit + 1
        )
    ]
    changes, more = assemble_page(rows, stream=stream, image_url=image_url)
    next_cursor = cursor
    if changes:
        last = changes[-1]
        next_cursor = ledger.encode_cursor(token, stream=stream, view=chosen, stamp=last["updatedAt"], item_id=last["id"])
    body = {
        "schemaVersion": contract.SCHEMA_VERSION,
        "portal": portal,
        "generatedAt": generated_at(),
        "stream": stream,
        "view": chosen,
        "cursor": cursor,
        "nextCursor": next_cursor,
        "hasMore": more,
        "changes": changes,
    }
    contract.assert_sendable(body)
    return body


async def _stream_state(client: Any, *, token: str, stream: str, enabled: bool) -> dict[str, Any]:
    counts = {str(r["state"]): int(r["n"]) for r in await client.query_raw(COUNT_SQL, stream)}
    live = [(str(r["itemId"]), str(r["revision"])) for r in await client.query_raw(LIVE_REVISIONS_SQL, stream)]
    latest = await client.query_raw(LATEST_SQL, stream)
    cursor = None
    if latest:
        cursor = ledger.encode_cursor(
            token, stream=stream, view=contract.VIEW_ALL, stamp=str(latest[0]["stamp"]), item_id=str(latest[0]["itemId"])
        )
    return {
        "enabled": enabled,
        "live": counts.get(contract.LIVE, 0),
        "gone": counts.get(contract.GONE, 0),
        "digest": ledger.digest(live),
        "latestCursor": cursor,
    }


async def state(client: Any, *, portal: str, token: str, images_enabled: bool) -> dict[str, Any]:
    refreshed = await client.query_raw(REFRESH_SQL)
    body = {
        "schemaVersion": contract.SCHEMA_VERSION,
        "portal": portal,
        "generatedAt": generated_at(),
        "refreshedAt": (refreshed[0].get("finishedAt") if refreshed else None) or None,
        "streams": {
            contract.TRANSCRIPTS: await _stream_state(client, token=token, stream=contract.TRANSCRIPTS, enabled=True),
            contract.IMAGES: await _stream_state(client, token=token, stream=contract.IMAGES, enabled=images_enabled),
        },
    }
    contract.assert_sendable(body)
    return body
