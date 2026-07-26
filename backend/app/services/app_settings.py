"""Global, master-admin-configurable application settings (a single DB row).

For now this controls how audio transcripts are produced, which speech-to-text provider is tried
first, and an optional off-peak window during which the heavy transcription + refinement work runs
(so it doesn't compete with daytime field uploads).
"""

from datetime import datetime, time, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

from app.core.db import db

SINGLETON_ID = "singleton"
DEFAULT_TRANSCRIPTION_MODE = "REFINED_TRANSLATED"
VALID_TRANSCRIPTION_MODES = {"RAW", "REFINED", "REFINED_TRANSLATED"}
DEFAULT_TIMEZONE = "Asia/Kolkata"

# The speech-to-text providers a master admin may rank, listed in the order that was compiled into the
# transcription chain before ranking existed. Doubles as the tie-breaker for anything a stored ranking
# does not mention, so introducing a fourth engine here never leaves it stranded behind an old row.
DEFAULT_STT_PROVIDER_ORDER = ("elevenlabs", "deepgram", "whisper")


async def load_app_settings() -> Any | None:
    """The settings row, or None if it has never been created (callers treat None as the defaults)."""
    try:
        return await db.appsetting.find_unique(where={"id": SINGLETON_ID})
    except Exception:  # noqa: BLE001 - settings must never break the request/worker path
        return None


async def get_or_create_app_settings(updated_by_id: str | None = None) -> Any:
    existing = await load_app_settings()
    if existing is not None:
        return existing
    return await db.appsetting.create(data={"id": SINGLETON_ID, "updatedById": updated_by_id})


def transcription_mode(row: Any | None) -> str:
    if row is None:
        return DEFAULT_TRANSCRIPTION_MODE
    mode = getattr(row, "transcriptionMode", None)
    return mode if mode in VALID_TRANSCRIPTION_MODES else DEFAULT_TRANSCRIPTION_MODE


def stt_provider_order(row: Any | None) -> list[str]:
    """The ranked speech-to-text providers, sanitised against the known set.

    Unknown and repeated ids are dropped, then any provider the row omits is appended in default
    order: a ranking saved before a provider existed must demote it, never disable it.
    """
    stored = list(getattr(row, "sttProviderOrder", None) or []) if row is not None else []
    ordered: list[str] = []
    for provider in stored:
        if provider in DEFAULT_STT_PROVIDER_ORDER and provider not in ordered:
            ordered.append(provider)
    ordered.extend(p for p in DEFAULT_STT_PROVIDER_ORDER if p not in ordered)
    return ordered


async def load_stt_provider_order() -> list[str]:
    """Read the ranking from the database now, not at import: a reorder saved in the Settings hub has
    to reach the next transcription job in both the API and the queue process without a restart."""
    return stt_provider_order(await load_app_settings())


def invalid_stt_provider_order(value: list[str]) -> str | None:
    """None when *value* is a usable ranking, otherwise why it is not, phrased for the admin."""
    known = list(DEFAULT_STT_PROVIDER_ORDER)
    unknown = [p for p in value if p not in DEFAULT_STT_PROVIDER_ORDER]
    if unknown:
        return f"lists unknown providers {unknown}; expected any of {known}"
    repeated = sorted({p for p in value if value.count(p) > 1})
    if repeated:
        return f"repeats {repeated}; each provider may appear once"
    missing = [p for p in known if p not in value]
    if missing:
        return f"is missing {missing}; it must rank every provider, not just the preferred ones"
    return None


def parse_hhmm(value: str | None, fallback: time) -> time:
    if not value:
        return fallback
    try:
        hours, minutes = str(value).split(":")
        return time(int(hours) % 24, int(minutes) % 60)
    except (ValueError, AttributeError):
        return fallback


def is_valid_hhmm(value: str | None) -> bool:
    if not value:
        return False
    try:
        hours, minutes = str(value).split(":")
        return 0 <= int(hours) <= 23 and 0 <= int(minutes) <= 59
    except (ValueError, AttributeError):
        return False


def within_processing_window(row: Any | None, now: datetime | None = None) -> bool:
    """True when heavy transcription/refinement jobs may run right now.

    Always true when the off-peak window is disabled (or no settings exist). When enabled, true only
    inside [start, end) in the configured timezone, correctly handling a window that wraps past
    midnight (e.g. 22:00–05:00).
    """
    if row is None or not getattr(row, "batchWindowEnabled", False):
        return True
    tz_name = getattr(row, "batchTimezone", None) or DEFAULT_TIMEZONE
    try:
        tz = ZoneInfo(tz_name)
    except Exception:  # noqa: BLE001 - bad tz string must not stall the queue
        try:
            tz = ZoneInfo(DEFAULT_TIMEZONE)
        except Exception:  # noqa: BLE001 - no tz database at all (Windows without tzdata)
            tz = timezone(timedelta(hours=5, minutes=30), name="IST")
    current = (now or datetime.now(tz)).astimezone(tz).time()
    start = parse_hhmm(getattr(row, "batchWindowStart", None), time(2, 0))
    end = parse_hhmm(getattr(row, "batchWindowEnd", None), time(5, 0))
    if start == end:
        return True
    if start < end:
        return start <= current < end
    return current >= start or current < end
