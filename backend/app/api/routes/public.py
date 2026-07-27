"""The public corpus census — the one route in this API that answers the same thing to everybody.

WHY IT EXISTS. The landing page's ledger read a hand-typed snapshot with a date in it
(``frontend/components/hero/corpusCensus.ts``). A number maintained by hand is a number that is
wrong between commits, and the date beside it makes the wrongness citable. This serves the counts
from the database instead, with the instant they were taken attached.

WHY IT IS THE ONLY UNAUTHENTICATED READ HERE, AND WHY THAT IS SAFE. ``docs/CDN.md`` states the
rule that every response under ``/api/*`` is a function of who is asking — six role tiers, workshop
scoping, per-record grants, Aadhaar masking. This route is the deliberate exception and the only
one: it takes no user, builds no ``visibility_where``, and reads no column. It issues eight bare
``count()`` calls and returns eight integers. There is no per-caller variation for a shared cache
to leak, because there is no caller in the query.

WHAT IS DELIBERATELY NOT HERE, because a stranger reading this URL is the threat model:

* No breakdown by craft, place, workshop or contributor. The corpus is a pilot — sixteen artisans
  across nine crafts and four districts — so a per-craft or per-place row would routinely have a
  count of ONE, and "one indigo dyer in Jammu" is a person, named or not. A breakdown key is an
  ATTRIBUTE OF THE PEOPLE COUNTED; a record-type total is not, which is why "1 workshop" below is
  publishable while "1 craft in Akola" would not be.
* No status breakdown. Publishing pending/approved/rejected would make this URL a live readout of
  the review queue: poll it and you learn when a moderator is working, and how a small number of
  identifiable researchers' submissions are being judged.
* No timestamps belonging to individual records — no newest upload, no last-modified. Those
  describe fieldwork cadence ("someone recorded in Bagru yesterday"), which is a movement pattern
  for the artisans as much as for the researchers. ``asOf`` is the moment THIS COUNT ran and is
  attached to nothing in the database.
* No ids, no names, no free text of any kind. Every value below is an integer or a timestamp, so
  there is nowhere in the payload for a string from a record to hide.

WHY IT COUNTS HOLDINGS AND SAYS SO IN THE FIELD NAME. The wire field is ``recordsHeld`` because
that is exactly what it is: every row, whatever its review status. Two reasons it cannot be
"approved". Craft has no ``status`` column at all, so for one of the eight rows an approved count
is not even expressible. And measured against production on 2026-07-27, all 925 media files are
PENDING and none are APPROVED — an approved census would publish "0 media files" for a repository
that holds 925. The name carries the meaning so a UI cannot relabel the number without changing
the key it reads: there is one word, in one place, and rendering the figure requires typing it.
"""

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

# The census is dated in India Standard Time, because that is the date its readers mean. The corpus
# is Indian fieldwork read mostly from India, and every record in the database already carries
# ``recordedTimezone = "Asia/Kolkata"``, so a UTC calendar date would put the ledger a day behind
# for five and a half hours out of every twenty-four — visibly wrong to the people it is for.
#
# A fixed offset rather than ``ZoneInfo("Asia/Kolkata")`` on purpose: India has observed one offset
# with no daylight saving since 1945, and ``zoneinfo`` needs a system tz database that a Windows dev
# box does not have. The offset is written into ``asOf`` itself, so the instant is never ambiguous.
_IST = timezone(timedelta(hours=5, minutes=30))

# The ledger, in reading order: the public name, and the Prisma delegate it counts. Held as data
# rather than eight hand-written awaits so the concurrency below cannot drift from the list.
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

# How long a counted census stays authoritative in this process. Five minutes against a corpus that
# gains a handful of records on a fieldwork day means the published figure is never meaningfully
# behind, while the database sees at most eight counts every five minutes NO MATTER how hard this
# URL is hit — which is the actual protection, because the CDN in front of it caches nothing today
# (see the header note below). Kept local and unconditional rather than reaching for
# ``app/scale/cache.py``: that cache is flag-gated off by default, and the most public URL in the
# system must not have its only load defence sitting behind a dormant feature flag.
_CACHE_TTL_SECONDS = 300.0

# How long the last good numbers may still be served after the database stops answering. Long
# enough to ride out the pooler exhaustion this box has a documented history of, short enough that
# nobody can cite yesterday's figure as today's. Past it the response says it could not count.
_STALE_GRACE_SECONDS = 6 * 60 * 60.0

# After a failed count, do not try again on the very next request. A public URL under load turns
# "retry immediately" into a request-rate hammering of a database that is already struggling — the
# amplification shape that has taken this API down before.
_RETRY_BACKOFF_SECONDS = 15.0

# The census's own deadline. Eight gathered cross-region counts complete in about a second; ten
# seconds is an order of magnitude of headroom and still comfortably under CloudFront's 30s
# origin-response timeout, so this route can never be the request that trips it.
_COUNT_TIMEOUT_SECONDS = 10.0

# Browser TTL. Short, because a reader who reloads should see the corpus move; the shared-cache TTL
# below is what actually keeps load off the origin.
_BROWSER_TTL_SECONDS = 60

# ``s-maxage`` is derived from the server-side TTL rather than typed twice, so the edge can never be
# told to hold a copy for longer than this process considers one current. Worst case an edge fetches
# a value that is already 299s old and keeps it 300s, so the oldest number a stranger can be served
# is about ten minutes — and ``asOf`` in the body states its true age, so staleness is visible
# rather than assumed.
#
# NOTE FOR WHOEVER TURNS THIS ON: the API distribution runs ``Managed-CachingDisabled`` on purpose
# (docs/CDN.md), so ``s-maxage`` is correct but INERT until a cache behaviour is added for this path
# alone. It must be path-scoped to ``/api/public/census`` and must not include ``Authorization`` in
# the cache key. Caching ``/api/*` broadly would serve one researcher's scoped response to another.
_FRESH_CACHE_CONTROL = f"public, max-age={_BROWSER_TTL_SECONDS}, s-maxage={int(_CACHE_TTL_SECONDS)}"

# Serving numbers we could not refresh: tell every cache to come back quickly, so recovery reaches
# readers in half a minute instead of five. A degraded answer earns a SHORTER life, never a longer
# one.
_STALE_CACHE_CONTROL = "public, max-age=0, s-maxage=30"

# "We could not count" must never be remembered anywhere. A cached failure would keep the ledger
# blank for the whole TTL after the database came back.
_UNAVAILABLE_CACHE_CONTROL = "no-store"


class _CensusState:
    """The last census taken, and when — the whole cache.

    Two clocks on purpose. Expiry is measured on ``time.monotonic``, which cannot jump when the box
    syncs its clock or crosses a DST boundary, so a cache entry can never be accidentally immortal
    or instantly stale. ``asOf`` is wall time, because it is a date a scholar will quote.
    """

    def __init__(self) -> None:
        self.counts: dict[str, int] | None = None
        self.counted_at: float | None = None  # monotonic, for expiry
        self.counted_at_wall: datetime | None = None  # IST, for asOf
        self.last_failure_at: float | None = None  # monotonic, for the retry backoff

    def age(self, now: float) -> float:
        return float("inf") if self.counted_at is None else now - self.counted_at


_state = _CensusState()

# One refresh at a time. Without it, the first request after expiry is joined by every other request
# that arrives during the second the counts take, and each fires its own eight reads — the classic
# stampede, aimed at a pool of ten connections.
_refresh_lock: asyncio.Lock | None = None
_refresh_lock_loop: asyncio.AbstractEventLoop | None = None


def _refresh_gate() -> asyncio.Lock:
    """The stampede lock, belonging to whichever loop is asking.

    An ``asyncio.Lock`` binds itself to the loop it first BLOCKS on and refuses to be awaited from
    another, so a module-level one is a latent error. The server has a single loop for its whole
    life and would never notice; the test suite runs each case in its own ``asyncio.run``, where a
    lock left bound to a dead loop fails in whichever test happens to contend second. ``deps.py``
    stores a loop beside its in-flight futures for the same reason.
    """
    global _refresh_lock, _refresh_lock_loop
    loop = asyncio.get_running_loop()
    if _refresh_lock is None or _refresh_lock_loop is not loop:
        _refresh_lock, _refresh_lock_loop = asyncio.Lock(), loop
    return _refresh_lock


def clear_census_cache() -> None:
    """Forget the cached census. Exists for tests, which must not inherit each other's numbers."""
    global _state
    _state = _CensusState()


async def _count_everything() -> dict[str, int]:
    """Every record type's total, counted concurrently.

    Sequentially this is eight cross-region round trips — roughly six seconds of pure waiting for
    eight queries whose server-side cost is a fraction of a millisecond each. Gathered it is one
    round trip's worth. ``gather_reads`` bounds the fan-out by the connection pool, so the busiest
    public URL in the system still cannot claim more connections than the pool has.

    Deliberately all-or-nothing: one failed count aborts the whole census rather than being folded
    in as a zero. A silent zero in a published ledger is worse than an admitted outage — it reads as
    a fact about the collection.
    """
    delegates = [getattr(db, attribute) for _, attribute in _CENSUS_TABLES]
    async with asyncio.timeout(_COUNT_TIMEOUT_SECONDS):
        # No `where`. The absence of a filter here is what makes `recordsHeld` an honest name; adding
        # a status filter without renaming the field is the exact drift this route is built against.
        totals = await gather_reads(*(delegate.count() for delegate in delegates))
    return {name: int(total) for (name, _), total in zip(_CENSUS_TABLES, totals, strict=True)}


def _body(counts: dict[str, int] | None, taken_at: datetime | None, *, stale: bool) -> dict:
    """The wire shape, in one place so the three response paths cannot disagree about it.

    ``counted`` is the discriminator a client needs and could not otherwise reconstruct: an empty
    corpus and an unreachable database both produce "no numbers to show", and only one of them is a
    fact about the collection. ``recordsHeld`` is null exactly when ``counted`` is false, and
    ``asOf`` with it — stamping the present moment on a payload that contains no counts would
    manufacture an as-of date for figures that were never taken.
    """
    return {
        "counted": counts is not None,
        "stale": stale,
        "asOf": taken_at.isoformat(timespec="seconds") if taken_at else None,
        # The ledger prints a date, and deriving one client-side from an instant reintroduces the
        # split the frontend snapshot documents: server and browser resolve different zones and
        # disagree on the rendered day. Both fields are IST and `asOf` carries the offset, so the
        # date here is the one the instant above falls on — they cannot drift by a day.
        "asOfDate": taken_at.strftime("%Y-%m-%d") if taken_at else None,
        "recordsHeld": counts,
    }


def _stale_or_unavailable(now: float) -> tuple[dict, str]:
    """What to serve when a fresh count is not available: the last good numbers, or an honest gap."""
    if _state.counts is not None and _state.age(now) <= _STALE_GRACE_SECONDS:
        return _body(_state.counts, _state.counted_at_wall, stale=True), _STALE_CACHE_CONTROL
    return _body(None, None, stale=False), _UNAVAILABLE_CACHE_CONTROL


async def _census() -> tuple[dict, str]:
    """The cached census and the ``Cache-Control`` its freshness has earned."""
    now = time.monotonic()
    if _state.counts is not None and _state.age(now) < _CACHE_TTL_SECONDS:
        return _body(_state.counts, _state.counted_at_wall, stale=False), _FRESH_CACHE_CONTROL

    # A database that just refused us gets left alone for a moment, and a refresh already in flight
    # is not worth queueing behind when there are numbers on hand — a reader would rather have a
    # five-minute-old census now than a current one after a cross-region round trip.
    gate = _refresh_gate()
    backing_off = (
        _state.last_failure_at is not None and now - _state.last_failure_at < _RETRY_BACKOFF_SECONDS
    )
    if backing_off or (gate.locked() and _state.counts is not None):
        return _stale_or_unavailable(now)

    async with gate:
        # Re-read the clock and the state: whoever held the lock has probably just refreshed both.
        now = time.monotonic()
        if _state.counts is not None and _state.age(now) < _CACHE_TTL_SECONDS:
            return _body(_state.counts, _state.counted_at_wall, stale=False), _FRESH_CACHE_CONTROL
        if _state.last_failure_at is not None and now - _state.last_failure_at < _RETRY_BACKOFF_SECONDS:
            return _stale_or_unavailable(now)
        try:
            counts = await _count_everything()
        except Exception as exc:  # noqa: BLE001 - the landing page must not 500 because a count did
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
    """How many records of each type the repository holds, and when they were counted.

    Unauthenticated and world-readable by design: a census is the same number for everyone, and
    requiring a token to learn the size of a public collection would be theatre. See the module
    docstring for what is excluded and why — the short version is that nothing here is attached to a
    person, and every value is an integer or a timestamp.

    Always HTTP 200, including when the database is unreachable. This is the landing page's first
    request; a 5xx here would take a page that is mostly typography down with a count it did not
    need, and would page whoever watches for 5xx about a decorative ledger. ``/health/ready`` is the
    endpoint that exists to say the database is down, and it is the one alerting should watch.
    """
    body, cache_control = await _census()
    return JSONResponse(content=body, headers={"cache-control": cache_control})
