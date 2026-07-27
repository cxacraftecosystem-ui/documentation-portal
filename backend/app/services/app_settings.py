"""Global, master-admin-configurable application settings (a single DB row).

For now this controls how audio transcripts are produced, which speech-to-text provider is tried
first, and an optional off-peak window during which the heavy transcription + refinement work runs
(so it doesn't compete with daytime field uploads).
"""

from collections.abc import Collection
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


async def save_stt_provider_order(order: list[str], updated_by_id: str | None = None) -> list[str]:
    """Persist a ranking and return it as the next transcription job will read it.

    Creates the singleton first, because a deployment whose settings row has never been written
    would otherwise fail its very first reorder. The returned value is round-tripped through
    :func:`stt_provider_order` rather than echoing the request, so the caller sees the stored truth.
    """
    await get_or_create_app_settings(updated_by_id)
    # Scalar-list columns take an explicit {"set": [...]} on update, unlike create.
    row = await db.appsetting.update(
        where={"id": SINGLETON_ID},
        data={"sttProviderOrder": {"set": order}, "updatedById": updated_by_id},
    )
    return stt_provider_order(row)


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


# --- Freezing an engine that has not been proven to work ---------------------------------------
#
# A ranking is a promise about what will happen to the next recording, and a promise it cannot keep
# is worse than no ranking at all: put ElevenLabs first with a dead key and every job pays for a
# rejected call before falling through to Deepgram, while this screen goes on saying ElevenLabs is
# handling the work. So an engine only earns a rankable position by PASSING a live test against the
# provider — a key that is merely present has not been proven to be a key that works, and the whole
# point of the exercise is to stop the panel from claiming otherwise.
#
# Four states, and only the last one thaws:
STT_KEY_ABSENT = "NO_KEY"  # nothing to call with; the chain skips this engine entirely
STT_KEY_UNTESTED = "UNTESTED"  # a key is present but nobody has asked the provider about it
STT_KEY_FAILING = "FAILING"  # we asked, and the provider refused it
STT_KEY_PASSING = "PASSING"  # we asked, and the provider accepted it


def freeze_unrankable(order: list[str], rankable: Collection[str]) -> list[str]:
    """*order* with every unproven provider sunk below every proven one.

    A STABLE PARTITION rather than a sort, deliberately. The admin's relative preference inside each
    half survives untouched, so an engine that later passes its test comes back rankable from the
    position it was already holding among its peers instead of from wherever an arbitrary sort left
    it. When nothing is rankable (a fresh deployment where no key has been tested yet) both halves
    collapse into one and this is the identity function — the freeze must not turn "we know nothing"
    into a reshuffle nobody asked for.
    """
    proven = [provider for provider in order if provider in rankable]
    frozen = [provider for provider in order if provider not in rankable]
    return proven + frozen


def order_violations(order: list[str], rankable: Collection[str]) -> int:
    """How many (frozen above proven) pairs *order* contains — zero means the freeze holds.

    Counted rather than asserted because the stored order can already be in violation without anyone
    having done anything wrong: a key expires overnight and yesterday's perfectly legal ranking is
    illegal by morning. A move is then judged by whether it makes this number worse, which lets an
    admin repair such an order by hand instead of being locked out of every control on the row.
    """
    seen_frozen = 0
    total = 0
    for provider in order:
        if provider in rankable:
            total += seen_frozen
        else:
            seen_frozen += 1
    return total


# Same-process fallback for verdicts on keys that live in the deployed environment.
#
# THIS USED TO BE THE WHOLE STORY, and it was the bug. ``ManagedSecret`` is where a test result
# belongs, but a row only exists once someone has SAVED a key through the Settings hub; a key
# supplied by the environment has no row, and inventing one would copy the deployed value into the
# database as an override, which is emphatically not what pressing "Test" should do. So the verdict
# was held here, in memory, and lost on every restart — on production, where every key comes from
# the environment, that meant each deploy reset the ranking to "nothing verified" and re-froze every
# engine.
#
# The durable answer is ``SecretTestResult`` (see services/managed_secrets.py): the verdict, and
# nothing from which the key could be reconstructed. This dictionary stays as the narrow fallback
# for the case where that write could not be made — a transient database error during the one
# request that ran the probe — so a test whose verdict was just observed does not read as untested
# on the panel returned by that same request. It is consulted only when no durable verdict exists.
_recalled_verdicts: dict[str, dict[str, Any]] = {}


def remember_key_verdict(key_name: str, *, passing: bool, checked_at: datetime, error: str | None) -> None:
    _recalled_verdicts[key_name] = {
        "status": STT_KEY_PASSING if passing else STT_KEY_FAILING,
        "checkedAt": checked_at,
        "error": None if passing else error,
    }


def recalled_key_verdict(key_name: str) -> dict[str, Any] | None:
    return _recalled_verdicts.get(key_name)


def forget_key_verdicts() -> None:
    """Drop every remembered verdict. Exists for tests, and for a caller that knows the keys moved."""
    _recalled_verdicts.clear()


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
