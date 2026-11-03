import asyncio
import logging
import time
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.core.db import db
from app.services.concurrency import gather_reads

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/public", tags=["public"])

_IST = timezone(timedelta(hours=5, minutes=30))

_CENSUS_TABLES: tuple[tuple[str, str], ...] = (
    ("artisans", "artisan"),
    ("crafts", "craft"),
    ("products", "productdocumentation"),
    ("processes", "process"),
    ("tools", "tooldocumentation"),
    ("interviews", "questionnaireinterview"),
    ("media", "mediafile"),
    ("workshops", "workshop"),
)

_HELD_WHERE: dict[str, dict] = {"craft": {"where": {"status": "ACTIVE"}}}

_CACHE_TTL_SECONDS = 300.0

_STALE_GRACE_SECONDS = 6 * 60 * 60.0

_RETRY_BACKOFF_SECONDS = 15.0

_COUNT_TIMEOUT_SECONDS = 10.0

_BROWSER_TTL_SECONDS = 60

_FRESH_CACHE_CONTROL = f"public, max-age={_BROWSER_TTL_SECONDS}, s-maxage={int(_CACHE_TTL_SECONDS)}"

_STALE_CACHE_CONTROL = "public, max-age=0, s-maxage=30"

_UNAVAILABLE_CACHE_CONTROL = "no-store"


class _CensusState:
    def __init__(self) -> None:
        self.counts: dict[str, int] | None = None
        self.counted_at: float | None = None
        self.counted_at_wall: datetime | None = None
        self.last_failure_at: float | None = None

    def age(self, now: float) -> float:
        return float("inf") if self.counted_at is None else now - self.counted_at


_state = _CensusState()

_refresh_lock: asyncio.Lock | None = None
_refresh_lock_loop: asyncio.AbstractEventLoop | None = None


def _refresh_gate() -> asyncio.Lock:
    global _refresh_lock, _refresh_lock_loop
    loop = asyncio.get_running_loop()
    if _refresh_lock is None or _refresh_lock_loop is not loop:
        _refresh_lock, _refresh_lock_loop = asyncio.Lock(), loop
    return _refresh_lock


def clear_census_cache() -> None:
    global _state
    _state = _CensusState()


async def _count_everything() -> dict[str, int]:
    delegates = [getattr(db, attribute) for _, attribute in _CENSUS_TABLES]
    reviewed = [(name, attribute) for name, attribute in _CENSUS_TABLES if attribute not in _UNREVIEWED]
    async with asyncio.timeout(_COUNT_TIMEOUT_SECONDS):
        totals = await gather_reads(
            *(
                delegate.count(**_HELD_WHERE.get(attribute, {}))
                for delegate, (_, attribute) in zip(delegates, _CENSUS_TABLES, strict=True)
            ),
            *(getattr(db, attribute).count(where={"status": "APPROVED"}) for _, attribute in reviewed),
        )
    held_totals, approved_totals = totals[: len(delegates)], totals[len(delegates) :]
    held = {name: int(total) for (name, _), total in zip(_CENSUS_TABLES, held_totals, strict=True)}
    approved_by_name = {name: int(total) for (name, _), total in zip(reviewed, approved_totals, strict=True)}
    approved = {
        name: (held[name] if attribute in _UNREVIEWED else approved_by_name[name])
        for name, attribute in _CENSUS_TABLES
    }
    return {"held": held, "approved": approved}


_UNREVIEWED = frozenset({"craft"})


def _body(counts: dict[str, dict[str, int]] | None, taken_at: datetime | None, *, stale: bool) -> dict:
    return {
        "counted": counts is not None,
        "stale": stale,
        "asOf": taken_at.isoformat(timespec="seconds") if taken_at else None,
        "asOfDate": taken_at.strftime("%Y-%m-%d") if taken_at else None,
        "recordsHeld": counts["held"] if counts is not None else None,
        "recordsPublished": counts["approved"] if counts is not None else None,
    }


def _stale_or_unavailable(now: float) -> tuple[dict, str]:
    if _state.counts is not None and _state.age(now) <= _STALE_GRACE_SECONDS:
        return _body(_state.counts, _state.counted_at_wall, stale=True), _STALE_CACHE_CONTROL
    return _body(None, None, stale=False), _UNAVAILABLE_CACHE_CONTROL


async def _census() -> tuple[dict, str]:
    now = time.monotonic()
    if _state.counts is not None and _state.age(now) < _CACHE_TTL_SECONDS:
        return _body(_state.counts, _state.counted_at_wall, stale=False), _FRESH_CACHE_CONTROL

    gate = _refresh_gate()
    backing_off = (
        _state.last_failure_at is not None and now - _state.last_failure_at < _RETRY_BACKOFF_SECONDS
    )
    if backing_off or (gate.locked() and _state.counts is not None):
        return _stale_or_unavailable(now)

    async with gate:
        now = time.monotonic()
        if _state.counts is not None and _state.age(now) < _CACHE_TTL_SECONDS:
            return _body(_state.counts, _state.counted_at_wall, stale=False), _FRESH_CACHE_CONTROL
        if _state.last_failure_at is not None and now - _state.last_failure_at < _RETRY_BACKOFF_SECONDS:
            return _stale_or_unavailable(now)
        try:
            counts = await _count_everything()
        except Exception as exc:  # noqa: BLE001
            _state.last_failure_at = time.monotonic()
            logger.warning("Public census count failed: %s — serving the last known figures", exc)
            return _stale_or_unavailable(time.monotonic())
        _state.counts = counts
        _state.counted_at = time.monotonic()
        _state.counted_at_wall = datetime.now(_IST)
        _state.last_failure_at = None
        return _body(counts, _state.counted_at_wall, stale=False), _FRESH_CACHE_CONTROL


@router.get("/census")
async def public_census() -> JSONResponse:
    body, cache_control = await _census()
    return JSONResponse(content=body, headers={"cache-control": cache_control})
