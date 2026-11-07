from __future__ import annotations

import logging
import math
import os
import threading
import time
from collections import deque
from dataclasses import dataclass
from typing import Any
from uuid import uuid4

logger = logging.getLogger(__name__)

TRANSCRIPT = "transcript"
MEASUREMENT = "measurement"

WINDOW_SECONDS = 3600.0
KEEP_SECONDS = 2 * 3600
DATABASE = "database"
MEMORY = "memory"
STORE = DATABASE

_DEFAULTS: dict[str, tuple[int, int]] = {
    TRANSCRIPT: (60, 600),
    MEASUREMENT: (60, 600),
}

_WHAT = {
    TRANSCRIPT: "transcript questions, drafts and retries",
    MEASUREMENT: "grid measurements",
}


@dataclass(frozen=True, slots=True)
class Limits:
    per_user: int
    total: int


class AllowanceSpent(Exception):
    def __init__(self, message: str, retry_after: int) -> None:
        super().__init__(message)
        self.message = message
        self.retry_after = retry_after


_lock = threading.Lock()
_by_user: dict[tuple[str, str], deque[float]] = {}
_total: dict[str, deque[float]] = {}


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        logger.warning("paid_ai_allowance: %s=%r is not a whole number; using %d", name, raw, default)
        return default
    if value < 0:
        logger.warning("paid_ai_allowance: %s=%d is negative; using %d", name, value, default)
        return default
    return value


def limits(bucket: str) -> Limits:
    per_user, total = _DEFAULTS[bucket]
    prefix = f"PAID_AI_{bucket.upper()}"
    return Limits(
        per_user=_env_int(f"{prefix}_PER_USER_PER_HOUR", per_user),
        total=_env_int(f"{prefix}_TOTAL_PER_HOUR", total),
    )


def _trim(stamps: deque[float], now: float) -> None:
    edge = now - WINDOW_SECONDS
    while stamps and stamps[0] <= edge:
        stamps.popleft()


def _wait(stamps: deque[float], now: float) -> int:
    if not stamps:
        return int(WINDOW_SECONDS)
    return max(1, math.ceil(stamps[0] + WINDOW_SECONDS - now))


def _refusal(bucket: str, retry_after: int, *, everyone: bool) -> AllowanceSpent:
    minutes = max(1, math.ceil(retry_after / 60))
    unit = "minute" if minutes == 1 else "minutes"
    what = _WHAT.get(bucket, "AI requests")
    if everyone:
        message = (
            f"The portal has reached its hourly limit for {what}. Try again in about "
            f"{minutes} {unit}."
        )
    else:
        message = (
            f"You have reached your hourly limit for {what}. Try again in about "
            f"{minutes} {unit}."
        )
    return AllowanceSpent(message, retry_after)


def _take_in_memory(bucket: str, who: str, caps: Limits, moment: float) -> None:
    with _lock:
        overall = _total.setdefault(bucket, deque())
        _trim(overall, moment)
        mine = _by_user.setdefault((bucket, who), deque()) if who else None
        if mine is not None:
            _trim(mine, moment)
            if len(mine) >= caps.per_user:
                raise _refusal(bucket, _wait(mine, moment), everyone=False)
        if len(overall) >= caps.total:
            raise _refusal(bucket, _wait(overall, moment), everyone=True)
        overall.append(moment)
        if mine is not None:
            mine.append(moment)


def _refund_in_memory(bucket: str, who: str) -> None:
    with _lock:
        overall = _total.get(bucket)
        if overall:
            overall.pop()
        mine = _by_user.get((bucket, who)) if who else None
        if mine:
            mine.pop()


_COUNT_SQL = (
    'SELECT count(*)::int AS "total", '
    'count(*) FILTER (WHERE "userId" = $2)::int AS "mine", '
    'COALESCE(CEIL(EXTRACT(EPOCH FROM (min("spentAt") + make_interval(secs => $3::int) - now()::timestamp(3))))::int, 0) AS "totalWait", '
    'COALESCE(CEIL(EXTRACT(EPOCH FROM (min("spentAt") FILTER (WHERE "userId" = $2) + make_interval(secs => $3::int) - now()::timestamp(3))))::int, 0) AS "mineWait" '
    'FROM "PaidAiSpend" WHERE "bucket" = $1 AND "spentAt" > now()::timestamp(3) - make_interval(secs => $3::int)'
)
_SPEND_SQL = 'INSERT INTO "PaidAiSpend" ("id", "bucket", "userId") VALUES ($1, $2, $3)'
_PRUNE_SQL = 'DELETE FROM "PaidAiSpend" WHERE "bucket" = $1 AND "spentAt" < now()::timestamp(3) - make_interval(secs => $2::int)'
_REFUND_SQL = (
    'DELETE FROM "PaidAiSpend" WHERE "id" = (SELECT "id" FROM "PaidAiSpend" WHERE "bucket" = $1 '
    'AND "userId" = $2 ORDER BY "spentAt" DESC LIMIT 1)'
)

_fallback_logged = False


def _client() -> Any:
    from app.core.db import db

    return db


def _note_fallback(exc: Exception) -> None:
    global _fallback_logged
    if _fallback_logged:
        return
    _fallback_logged = True
    logger.warning(
        "paid_ai_allowance: the shared counters could not be read (%s); counting in this process "
        "until the database answers again",
        type(exc).__name__,
    )


async def _take_in_database(bucket: str, who: str, caps: Limits) -> None:
    client = _client()
    window = int(WINDOW_SECONDS)
    rows = await client.query_raw(_COUNT_SQL, bucket, who, window)
    row = rows[0] if rows else {}
    mine = int(row.get("mine") or 0)
    total = int(row.get("total") or 0)
    if who and mine >= caps.per_user:
        raise _refusal(bucket, max(1, int(row.get("mineWait") or window)), everyone=False)
    if total >= caps.total:
        raise _refusal(bucket, max(1, int(row.get("totalWait") or window)), everyone=True)
    await client.execute_raw(_SPEND_SQL, uuid4().hex, bucket, who)
    await client.execute_raw(_PRUNE_SQL, bucket, KEEP_SECONDS)


async def take(bucket: str, user_id: str | None, *, now: float | None = None) -> None:
    caps = limits(bucket)
    who = str(user_id or "")
    if STORE == DATABASE:
        try:
            await _take_in_database(bucket, who, caps)
            return
        except AllowanceSpent:
            raise
        except Exception as exc:  # noqa: BLE001
            _note_fallback(exc)
    _take_in_memory(bucket, who, caps, time.monotonic() if now is None else now)


async def refund(bucket: str, user_id: str | None) -> None:
    who = str(user_id or "")
    if STORE == DATABASE:
        try:
            await _client().execute_raw(_REFUND_SQL, bucket, who)
            return
        except Exception as exc:  # noqa: BLE001
            _note_fallback(exc)
    _refund_in_memory(bucket, who)


def reset() -> None:
    with _lock:
        _by_user.clear()
        _total.clear()
