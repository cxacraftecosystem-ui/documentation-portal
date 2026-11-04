from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, time, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

DEFAULT_TIMEZONE = "Asia/Kolkata"
DEFAULT_WINDOW = ("02:00", "05:00")
PAUSED_MESSAGE = "Transcription is paused."


def _get(row: Any, name: str, default: Any) -> Any:
    if row is None:
        return default
    if isinstance(row, dict):
        return row.get(name, default)
    return getattr(row, name, default)


def is_paused(row: Any) -> bool:
    if row is None:
        return True
    value = _get(row, "transcriptionPaused", True)
    return True if value is None else bool(value)


def parse_hhmm(value: str | None, fallback: time) -> time:
    if not value:
        return fallback
    try:
        hours, minutes = str(value).split(":")
        return time(int(hours) % 24, int(minutes) % 60)
    except ValueError, AttributeError:
        return fallback


def _zone(name: str | None) -> Any:
    try:
        return ZoneInfo(name or DEFAULT_TIMEZONE)
    except Exception:  # noqa: BLE001 - bad tz string or no tz database (Windows without tzdata)
        try:
            return ZoneInfo(DEFAULT_TIMEZONE)
        except Exception:  # noqa: BLE001
            return timezone(timedelta(hours=5, minutes=30), name="IST")


def within_window(row: Any, now: datetime | None = None) -> bool:
    if row is None or not _get(row, "batchWindowEnabled", False):
        return True
    tz = _zone(_get(row, "batchTimezone", None))
    current = (now or datetime.now(tz)).astimezone(tz).time()
    start = parse_hhmm(_get(row, "batchWindowStart", None), time(2, 0))
    end = parse_hhmm(_get(row, "batchWindowEnd", None), time(5, 0))
    if start == end:
        return True
    if start < end:
        return start <= current < end
    return current >= start or current < end


def next_window_start(row: Any, now: datetime) -> datetime:
    if within_window(row, now):
        return now
    tz = _zone(_get(row, "batchTimezone", None))
    local = now.astimezone(tz)
    start = parse_hhmm(_get(row, "batchWindowStart", None), time(2, 0))
    candidate = local.replace(hour=start.hour, minute=start.minute, second=0, microsecond=0)
    if candidate <= local:
        candidate += timedelta(days=1)
    return candidate.astimezone(now.tzinfo or UTC)


@dataclass(frozen=True)
class QueueGate:
    may_run: bool
    reason: str | None


def queue_gate(row: Any, now: datetime | None = None) -> QueueGate:
    if is_paused(row):
        return QueueGate(False, "paused")
    if not within_window(row, now):
        return QueueGate(False, "window")
    return QueueGate(True, None)
