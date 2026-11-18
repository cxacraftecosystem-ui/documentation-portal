from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import hmac
import json
import logging
from collections.abc import Callable, Iterable
from datetime import UTC, datetime, timedelta
from typing import Any

from app.services.field_voices import contract
from app.services.field_voices.items import Outcome
from app.services.field_voices.sources import plain_json

__all__ = [
    "Busy",
    "UnknownCursor",
    "clock",
    "decode_cursor",
    "digest",
    "encode_cursor",
    "live_rows",
    "release_copies",
    "revision",
    "stamp_text",
    "sync_point",
    "write_outcomes",
]

logger = logging.getLogger(__name__)

CURSOR_CONTEXT = "field-voices-cursor\n"
LOCK_KEY = "field-voices"
STAMP_FORMAT = 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
REVISION_DROPPED = ("state", "revision", "updatedAt")
IMAGE_DROPPED = ("url", "expiresAt")
TX_MAX_WAIT = timedelta(seconds=contract.LOCK_TIMEOUT_SECONDS + 5)
TX_TIMEOUT = timedelta(seconds=120)
SUBJECTS = "SUBJECTS"

ROWS_SQL = (
    'SELECT "itemId", "state", "revision", "copyKey", "copyOf", "payload", "subjects", '
    f'to_char("updatedAt", \'{STAMP_FORMAT}\') AS "stamp" FROM "FieldVoiceItem" '
    'WHERE "stream" = $1 AND "itemId" = ANY($2::text[])'
)
LIVE_SQL = (
    'SELECT "itemId", "revision", "copyKey", "copyOf", "payload", "subjects" FROM "FieldVoiceItem" '
    'WHERE "stream" = $1 AND "state" = \'LIVE\' ORDER BY "itemId"'
)
UPSERT_SQL = (
    'INSERT INTO "FieldVoiceItem" ("stream", "itemId", "state", "revision", "reason", "successorId", "payload", '
    '"copyKey", "copyOf", "subjects", "updatedAt") VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9, $10::jsonb, '
    "greatest((clock_timestamp() AT TIME ZONE 'UTC')::timestamp(6), "
    '(SELECT max(f."updatedAt") FROM "FieldVoiceItem" f WHERE f."stream" = $1) + interval \'1 microsecond\')) '
    'ON CONFLICT ("stream", "itemId") DO UPDATE SET "state" = EXCLUDED."state", "revision" = EXCLUDED."revision", '
    '"reason" = EXCLUDED."reason", "successorId" = EXCLUDED."successorId", "payload" = EXCLUDED."payload", '
    '"copyKey" = EXCLUDED."copyKey", "copyOf" = EXCLUDED."copyOf", "subjects" = EXCLUDED."subjects", '
    '"updatedAt" = EXCLUDED."updatedAt"'
)
SUBJECTS_SQL = (
    'UPDATE "FieldVoiceItem" SET "subjects" = $3::jsonb WHERE "stream" = $1 AND "itemId" = $2 AND "state" = \'LIVE\''
)
CLOCK_SQL = f"SELECT to_char((clock_timestamp() AT TIME ZONE 'UTC')::timestamp(6), '{STAMP_FORMAT}') AS \"now\""
COPY_IN_USE_SQL = (
    'SELECT DISTINCT "copyKey" FROM "FieldVoiceItem" WHERE "state" = \'LIVE\' AND "copyKey" = ANY($1::text[])'
)
QUEUE_RELEASE_SQL = (
    'INSERT INTO "FieldVoiceCopyRelease" ("copyKey") SELECT unnest($1::text[]) ON CONFLICT ("copyKey") DO NOTHING'
)
UNQUEUE_RELEASE_SQL = 'DELETE FROM "FieldVoiceCopyRelease" WHERE "copyKey" = ANY($1::text[])'
PENDING_RELEASE_SQL = 'SELECT "copyKey" FROM "FieldVoiceCopyRelease" ORDER BY "queuedAt", "copyKey" LIMIT $1'
FAILED_RELEASE_SQL = (
    'UPDATE "FieldVoiceCopyRelease" SET "attempts" = "attempts" + 1 WHERE "copyKey" = ANY($1::text[])'
)
LOCK_SQL = (
    'SELECT count(*) AS "locked" FROM (SELECT pg_advisory_xact_lock(hashtext($1))) AS l'
)


class Busy(Exception):
    pass


class UnknownCursor(ValueError):
    pass


def stamp_text(value: Any) -> str:
    if isinstance(value, datetime):
        moment = value if value.tzinfo else value.replace(tzinfo=UTC)
        return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    return str(value)


def canonical(item: dict[str, Any]) -> str:
    return json.dumps(item, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def revision_body(item: dict[str, Any]) -> dict[str, Any]:
    body = {k: v for k, v in item.items() if k not in REVISION_DROPPED}
    image = body.get("image")
    if isinstance(image, dict):
        body["image"] = {k: v for k, v in image.items() if k not in IMAGE_DROPPED}
    return body


def revision(item: dict[str, Any]) -> str:
    return hashlib.sha256(canonical(revision_body(item)).encode("utf-8")).hexdigest()[:32]


def digest(live: Iterable[tuple[str, str]]) -> str:
    lines = sorted(f"{item_id} {rev}\n" for item_id, rev in live)
    return hashlib.sha256("".join(lines).encode("utf-8")).hexdigest()


def _mac(token: str, body: str) -> str:
    material = (CURSOR_CONTEXT + body).encode("utf-8")
    return hmac.new(token.strip().encode("utf-8"), material, hashlib.sha256).hexdigest()[:32]


def encode_cursor(token: str, *, stream: str, view: str, stamp: str, item_id: str) -> str:
    raw = json.dumps({"s": stream, "v": view, "u": stamp, "i": item_id}, separators=(",", ":"))
    body = base64.urlsafe_b64encode(raw.encode("utf-8")).decode("ascii").rstrip("=")
    return f"{body}.{_mac(token, body)}"


def decode_cursor(token: str, cursor: str, *, stream: str, view: str) -> tuple[str, str]:
    if not cursor or len(cursor) > contract.MAX_CURSOR_LENGTH or "." not in cursor:
        raise UnknownCursor(cursor)
    body, mac = cursor.rsplit(".", 1)
    if not hmac.compare_digest(_mac(token, body), mac):
        raise UnknownCursor(cursor)
    try:
        decoded = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)).decode("utf-8"))
    except (binascii.Error, UnicodeDecodeError, ValueError) as exc:
        raise UnknownCursor(cursor) from exc
    if not isinstance(decoded, dict) or decoded.get("s") != stream or decoded.get("v") != view:
        raise UnknownCursor(cursor)
    stamp, item_id = decoded.get("u"), decoded.get("i")
    if not isinstance(stamp, str) or not isinstance(item_id, str) or not item_id:
        raise UnknownCursor(cursor)
    try:
        datetime.fromisoformat(stamp)
    except ValueError as exc:
        raise UnknownCursor(cursor) from exc
    return stamp, item_id


def _lock_timeout(exc: BaseException) -> bool:
    text = str(exc).lower()
    return "lock timeout" in text or "55p03" in text or "lock_timeout" in text


async def take_lock(tx: Any) -> None:
    await tx.execute_raw(f"SET LOCAL lock_timeout = '{contract.LOCK_TIMEOUT_SECONDS}s'")
    try:
        await tx.query_raw(LOCK_SQL, LOCK_KEY)
    except Exception as exc:
        if _lock_timeout(exc):
            raise Busy(str(exc)) from exc
        raise


async def sync_point(client: Any) -> None:
    async with client.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        await take_lock(tx)


def _row(row: dict[str, Any]) -> dict[str, Any]:
    out = dict(row)
    out["payload"] = plain_json(row.get("payload"))
    out["subjects"] = plain_json(row.get("subjects"))
    return out


async def clock(client: Any) -> str:
    rows = await client.query_raw(CLOCK_SQL)
    return str(rows[0]["now"])


async def rows_for(client: Any, stream: str, ids: Iterable[str]) -> dict[str, dict[str, Any]]:
    wanted = sorted({str(i) for i in ids if i})
    if not wanted:
        return {}
    return {str(r["itemId"]): _row(r) for r in await client.query_raw(ROWS_SQL, stream, wanted)}


async def live_rows(client: Any, stream: str) -> dict[str, dict[str, Any]]:
    return {str(r["itemId"]): _row(r) for r in await client.query_raw(LIVE_SQL, stream)}


def _gone_since(existing: dict[str, Any] | None, since: str | None) -> bool:
    if since is None or not existing or existing.get("state") != contract.GONE:
        return False
    return str(existing.get("stamp") or "") > since


def planned(outcome: Outcome, existing: dict[str, Any] | None, *, since: str | None = None) -> tuple[str, ...] | None:
    if outcome.payload is not None:
        if _gone_since(existing, since):
            return None
        rev = revision(outcome.payload)
        if (
            existing
            and existing.get("state") == contract.LIVE
            and existing.get("revision") == rev
            and existing.get("copyKey") == outcome.copy_key
            and existing.get("copyOf") == outcome.copy_of
        ):
            return None if existing.get("subjects") == outcome.subjects else (SUBJECTS,)
        return (contract.LIVE, rev)
    if outcome.reason and existing and existing.get("state") == contract.LIVE:
        return (contract.GONE, outcome.reason)
    return None


def _json(value: Any) -> str | None:
    return None if value is None else json.dumps(value, ensure_ascii=False, sort_keys=True)


async def _apply(tx: Any, stream: str, outcome: Outcome, plan: tuple[str, ...]) -> None:
    if plan[0] == SUBJECTS:
        await tx.execute_raw(SUBJECTS_SQL, stream, outcome.item_id, _json(outcome.subjects))
        return
    if plan[0] == contract.LIVE:
        await tx.execute_raw(
            UPSERT_SQL, stream, outcome.item_id, contract.LIVE, plan[1], None, None,
            json.dumps(outcome.payload, ensure_ascii=False), outcome.copy_key, outcome.copy_of,
            _json(outcome.subjects),
        )
        return
    await tx.execute_raw(
        UPSERT_SQL, stream, outcome.item_id, contract.GONE, None, plan[1], outcome.successor, None, None, None, None
    )


async def _write_chunk(tx: Any, stream: str, chunk: list[Outcome], since: str | None) -> int:
    await take_lock(tx)
    existing = await rows_for(tx, stream, [o.item_id for o in chunk])
    changed = 0
    released: set[str] = set()
    named: set[str] = set()
    for outcome in chunk:
        before = existing.get(outcome.item_id)
        plan = planned(outcome, before, since=since)
        if plan is None:
            continue
        await _apply(tx, stream, outcome, plan)
        changed += 1
        if plan[0] == contract.LIVE and outcome.copy_key:
            named.add(outcome.copy_key)
        old_key = (before or {}).get("copyKey")
        if old_key and old_key != outcome.copy_key:
            released.add(str(old_key))
    if named:
        await tx.execute_raw(UNQUEUE_RELEASE_SQL, sorted(named))
    if released - named:
        await tx.execute_raw(QUEUE_RELEASE_SQL, sorted(released - named))
    return changed


async def write_outcomes(
    client: Any, stream: str, outcomes: list[Outcome], *, dry_run: bool = False, since: str | None = None
) -> int:
    if not outcomes:
        return 0
    changed = 0
    for start in range(0, len(outcomes), contract.WRITE_BATCH):
        chunk = outcomes[start : start + contract.WRITE_BATCH]
        if dry_run:
            existing = await rows_for(client, stream, [o.item_id for o in chunk])
            changed += sum(1 for o in chunk if planned(o, existing.get(o.item_id), since=since))
            continue
        async with client.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
            changed += await _write_chunk(tx, stream, chunk, since)
    return changed


async def _releasable(client: Any, limit: int) -> list[str]:
    async with client.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        await take_lock(tx)
        pending = [str(r["copyKey"]) for r in await tx.query_raw(PENDING_RELEASE_SQL, limit)]
        if not pending:
            return []
        in_use = {str(r["copyKey"]) for r in await tx.query_raw(COPY_IN_USE_SQL, pending)}
        if in_use:
            await tx.execute_raw(UNQUEUE_RELEASE_SQL, sorted(in_use))
        return [key for key in pending if key not in in_use]


def _delete_copy(key: str) -> None:
    from app.services import s3

    s3.delete_object(key)


async def release_copies(
    client: Any, *, limit: int = contract.COPIES_PER_REFRESH, delete: Callable[[str], None] = _delete_copy
) -> dict[str, int]:
    keys = await _releasable(client, limit)
    deleted: list[str] = []
    failed: list[str] = []
    for key in keys:
        try:
            await asyncio.to_thread(delete, key)
            deleted.append(key)
        except Exception:
            failed.append(key)
            logger.exception("field voices: could not delete the copy %s; the next refresh tries again", key)
    if deleted:
        await client.execute_raw(UNQUEUE_RELEASE_SQL, deleted)
    if failed:
        await client.execute_raw(FAILED_RELEASE_SQL, failed)
    return {"deleted": len(deleted), "failed": len(failed)}
