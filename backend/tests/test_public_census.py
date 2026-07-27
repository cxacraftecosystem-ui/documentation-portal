"""The public census: what it may publish, and what it must never say.

This is the only unauthenticated read in the API, so most of what follows is not about the numbers
being right — it is about the payload having nowhere for a person to appear, about a figure never
being labelled something it is not, and about the landing page surviving a database that has
stopped answering. The counting itself is checked for the two properties that cost real money on a
cross-region link: it happens once per burst, and the eight reads go out together.

Nothing here touches a database. ``public.db`` is replaced by tables that record every ``count()``
they are asked for, which is what turns "one round of counts, not twenty" into a measurement.
"""

import asyncio
import re
from functools import lru_cache
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI

from app.api.router import api_router
from app.api.routes import public
from app.services.concurrency import pool_width

# Every record type, with a distinct total so a mis-wired delegate shows up as a wrong number rather
# than as a coincidence.
_TOTALS = {
    "artisan": 16,
    "craft": 9,
    "productdocumentation": 18,
    "process": 4,
    "tooldocumentation": 74,
    "questionnaireinterview": 25,
    "mediafile": 925,
    "workshop": 1,
}

# The same figures under the names the wire uses. These are the live production counts as probed on
# 2026-07-27, so the frontend ledger and this test are checking each other.
_EXPECTED_HELD = {
    "artisans": 16,
    "crafts": 9,
    "products": 18,
    "processes": 4,
    "tools": 74,
    "interviews": 25,
    "media": 925,
    "workshops": 1,
}


class _Corpus:
    """Stands in for the Prisma client: counts on demand, and remembers how it was asked."""

    def __init__(self, totals: dict[str, int], *, delay: float = 0.0, failing: set | None = None):
        self.totals = dict(totals)
        self.delay = delay
        self.failing = failing or set()
        self.calls: list[tuple[str, dict]] = []
        self.in_flight = 0
        self.peak_in_flight = 0

    @property
    def query_count(self) -> int:
        return len(self.calls)

    def delegate(self, name: str):
        corpus = self

        class _Table:
            async def count(self, **kwargs):
                corpus.calls.append((name, kwargs))
                corpus.in_flight += 1
                corpus.peak_in_flight = max(corpus.peak_in_flight, corpus.in_flight)
                try:
                    if corpus.delay:
                        # A window wide enough for the other seven reads to arrive inside it;
                        # without it "concurrent" would be measuring how fast a dict lookup returns.
                        await asyncio.sleep(corpus.delay)
                    if name in corpus.failing:
                        raise RuntimeError(f"{name} is unreachable")
                    return corpus.totals[name]
                finally:
                    corpus.in_flight -= 1

        return _Table()

    def as_client(self) -> SimpleNamespace:
        return SimpleNamespace(**{attr: self.delegate(attr) for attr in self.totals})


async def _get(path: str = "/api/public/census", *, headers: dict | None = None):
    app = httpx.ASGITransport(app=_app())
    async with httpx.AsyncClient(transport=app, base_url="http://census.test") as client:
        return await client.get(path, headers=headers)


async def _get_many(count: int, path: str = "/api/public/census"):
    """*count* requests issued together on one event loop, i.e. a link that just got shared."""
    app = httpx.ASGITransport(app=_app())
    async with httpx.AsyncClient(transport=app, base_url="http://census.test") as client:
        return await asyncio.gather(*(client.get(path) for _ in range(count)))


@lru_cache(maxsize=1)
def _app():
    # The whole api_router, not just the census one, so the path under test is the real public URL —
    # the one a CloudFront cache behaviour would have to be scoped to. Built once: mounting two
    # hundred routes per request would make this file's runtime about app construction, and the app
    # holds no census state anyway — the cache and the stubbed tables both live in the module.
    app = FastAPI()
    app.include_router(api_router)
    return app


@pytest.fixture
def corpus(monkeypatch: pytest.MonkeyPatch):
    """Build an isolated census stack; the factory takes the totals and how the tables misbehave."""

    def build(totals: dict[str, int] | None = None, *, delay: float = 0.0,
              failing: set | None = None) -> _Corpus:
        built = _Corpus(totals if totals is not None else _TOTALS, delay=delay, failing=failing)
        monkeypatch.setattr(public, "db", built.as_client())
        return built

    public.clear_census_cache()
    yield build
    public.clear_census_cache()


# --- What it counts, and what it calls it --------------------------------------------------------


def test_every_record_type_is_counted_exactly_once(corpus) -> None:
    tables = corpus()

    response = asyncio.run(_get())

    assert response.status_code == 200
    assert response.json()["recordsHeld"] == _EXPECTED_HELD
    assert sorted(name for name, _ in tables.calls) == sorted(_TOTALS)


def test_the_census_counts_every_row_held_not_only_the_approved_ones(corpus) -> None:
    """The anti-drift test, and the reason it is worth having.

    ``recordsHeld`` is a promise about WHICH rows are in the number. Filtering to approvals without
    renaming the field would be silent and badly wrong: in production every one of the 925 media
    files is PENDING, so an "approved" census would publish zero media for a repository that holds
    all of them. This asserts both halves at once — no filter reaches the database, and the field
    the payload exposes is the one that says so.
    """
    tables = corpus()

    body = asyncio.run(_get()).json()

    assert "recordsHeld" in body
    assert all(kwargs == {} for _, kwargs in tables.calls), "a where clause reached a census count"


def test_the_census_is_mounted_at_the_public_path_the_cdn_rule_would_be_scoped_to() -> None:
    paths = {route.path for route in api_router.routes}
    assert "/api/public/census" in paths


def test_the_census_asks_for_no_token(corpus) -> None:
    """A census is the same number for everyone; requiring a token to learn it would be theatre.

    Checked at the route rather than only over HTTP, because a dependency that silently returned a
    user would still answer 200 here — what matters is that there is no auth dependency at all.
    """
    corpus()
    route = next(r for r in api_router.routes if getattr(r, "path", None) == "/api/public/census")

    assert route.dependant.dependencies == []
    assert asyncio.run(_get(headers={})).status_code == 200


# --- What the payload may contain ----------------------------------------------------------------

# Words that would mean the value beside them is about a PERSON, a PLACE or a ROW, rather than about
# the collection. Matched against the words in a key, so "artisans" (a record type, holding an
# integer) passes while "artisanName" or "artisanId" could not.
_IDENTIFYING_WORDS = frozenset({
    "id", "ids", "uuid", "cuid", "key", "slug", "name", "names", "email", "phone", "mobile",
    "aadhaar", "pehchan", "card", "address", "place", "village", "district", "state", "pincode",
    "gender", "dob", "age", "lat", "latitude", "lon", "lng", "longitude", "geo", "location",
    "caption", "filename", "file", "url", "transcript", "notes", "note", "title", "description",
    "by", "owner", "author", "user", "users", "contributor", "researcher", "reviewer", "uploaded",
    "created", "reviewed", "status", "pending", "approved", "rejected",
})

# The only strings the census may contain: the two as-of stamps, a date and an offset-bearing
# instant. A name is a string, so pinning the permitted string shapes is what leaves nowhere for one
# to hide.
_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2}))?$")


def _words(key: str) -> list[str]:
    return [part.lower() for part in re.findall(r"[a-z]+|[A-Z][a-z]*|[A-Z]+(?![a-z])", key)]


def _walk(node, path="$"):
    """Every (path, key, value) in the payload, so the assertions below cover nesting too."""
    if isinstance(node, dict):
        for key, value in node.items():
            yield f"{path}.{key}", key, value
            yield from _walk(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield f"{path}[{index}]", None, value
            yield from _walk(value, f"{path}[{index}]")


def test_no_field_in_the_census_could_carry_a_name_or_an_id(corpus) -> None:
    corpus()

    body = asyncio.run(_get()).json()

    offenders = [
        (where, sorted(set(_words(key)) & _IDENTIFYING_WORDS))
        for where, key, _ in _walk(body)
        if key and set(_words(key)) & _IDENTIFYING_WORDS
    ]
    assert offenders == [], f"identity-bearing keys in a world-readable payload: {offenders}"


def test_the_only_strings_in_the_census_are_the_two_as_of_stamps(corpus) -> None:
    corpus()

    body = asyncio.run(_get()).json()

    strings = [(where, value) for where, _, value in _walk(body) if isinstance(value, str)]
    assert [where for where, _ in strings] == ["$.asOf", "$.asOfDate"]
    assert all(_TIMESTAMP.match(value) for _, value in strings), strings


def test_the_printed_date_is_the_day_the_stamped_instant_actually_falls_on(corpus) -> None:
    """The ledger prints ``asOfDate``; a client that also shows ``asOf`` must not see two days. Both
    are India Standard Time and the instant carries its offset, so neither can be read as UTC."""
    corpus()

    body = asyncio.run(_get()).json()

    assert body["asOf"].endswith("+05:30")
    assert body["asOf"].startswith(body["asOfDate"])


def test_the_shape_is_frozen_so_a_new_field_cannot_be_added_without_a_decision(corpus) -> None:
    """Every field on this URL is a publication decision. Adding one should break a test, not slip
    out with the next deploy — least of all a per-craft or per-place breakdown, where a pilot corpus
    of sixteen artisans produces buckets of one."""
    corpus()

    body = asyncio.run(_get()).json()

    assert set(body) == {"counted", "stale", "asOf", "asOfDate", "recordsHeld"}
    assert set(body["recordsHeld"]) == set(_EXPECTED_HELD)


def test_every_published_count_is_a_plain_integer(corpus) -> None:
    corpus()

    held = asyncio.run(_get()).json()["recordsHeld"]

    assert all(isinstance(value, int) and not isinstance(value, bool) for value in held.values())


# --- Cost: one round of counts, issued together --------------------------------------------------


def test_the_eight_counts_go_out_together_not_one_cross_region_trip_at_a_time(corpus) -> None:
    """Sequentially this is eight round trips to another AWS region — seconds of pure waiting for
    queries that cost fractions of a millisecond to execute."""
    tables = corpus(delay=0.05)

    asyncio.run(_get())

    assert tables.peak_in_flight == min(len(_TOTALS), pool_width())


def test_a_burst_of_twenty_requests_costs_one_round_of_counts_not_twenty(corpus) -> None:
    """The stampede this URL is most exposed to: a cold cache and a link that has just been shared."""
    tables = corpus(delay=0.02)

    responses = asyncio.run(_get_many(20))

    assert {r.status_code for r in responses} == {200}
    assert tables.query_count == len(_TOTALS)


def test_bursts_on_two_different_event_loops_both_work(corpus) -> None:
    """The stampede lock must not stay bound to the loop it first blocked on. Nothing in production
    would ever notice — one process, one loop for its lifetime — which is exactly why it is worth
    pinning here, where each case gets a fresh ``asyncio.run``."""
    tables = corpus(delay=0.02)
    asyncio.run(_get_many(5))

    public.clear_census_cache()
    asyncio.run(_get_many(5))

    assert tables.query_count == 2 * len(_TOTALS)


def test_a_second_request_inside_the_ttl_reads_nothing(corpus) -> None:
    tables = corpus()

    asyncio.run(_get())
    asyncio.run(_get())

    assert tables.query_count == len(_TOTALS)


def test_the_census_is_recounted_once_it_expires(corpus, monkeypatch: pytest.MonkeyPatch) -> None:
    tables = corpus()
    asyncio.run(_get())
    monkeypatch.setattr(public, "_CACHE_TTL_SECONDS", 0.0)

    asyncio.run(_get())

    assert tables.query_count == 2 * len(_TOTALS)


# --- Failing soft --------------------------------------------------------------------------------


def test_an_empty_corpus_is_distinguishable_from_a_database_that_would_not_answer(corpus) -> None:
    """The distinction the payload exists to make. Both render as "no numbers"; only one of them is
    a fact about the collection."""
    corpus({name: 0 for name in _TOTALS})
    empty = asyncio.run(_get()).json()

    public.clear_census_cache()
    corpus(failing=set(_TOTALS))
    broken = asyncio.run(_get()).json()

    assert empty["counted"] is True
    assert empty["recordsHeld"] == dict.fromkeys(_EXPECTED_HELD, 0)
    assert broken["counted"] is False
    assert broken["recordsHeld"] is None


def test_an_unreachable_database_does_not_500_the_landing_page(corpus) -> None:
    corpus(failing=set(_TOTALS))

    response = asyncio.run(_get())

    assert response.status_code == 200


def test_an_uncountable_census_carries_no_as_of_date(corpus) -> None:
    """Stamping the present moment on a payload with no counts would manufacture an as-of date for
    figures that were never taken — the precise thing an as-of date exists to prevent."""
    corpus(failing=set(_TOTALS))

    body = asyncio.run(_get()).json()

    assert body["asOf"] is None
    assert body["asOfDate"] is None


def test_one_failing_count_withholds_the_census_rather_than_publishing_a_zero(corpus) -> None:
    """A silent zero in a published ledger reads as a fact about the collection, not as an outage."""
    corpus(failing={"mediafile"})

    body = asyncio.run(_get()).json()

    assert body["counted"] is False
    assert body["recordsHeld"] is None


def test_a_failure_after_a_good_count_serves_the_last_figures_and_admits_they_are_stale(
    corpus, monkeypatch: pytest.MonkeyPatch
) -> None:
    corpus()
    fresh = asyncio.run(_get()).json()
    monkeypatch.setattr(public, "_CACHE_TTL_SECONDS", 0.0)
    monkeypatch.setattr(public, "_RETRY_BACKOFF_SECONDS", 0.0)
    corpus(failing=set(_TOTALS))

    degraded = asyncio.run(_get()).json()

    assert fresh["stale"] is False
    assert degraded["stale"] is True
    assert degraded["counted"] is True
    assert degraded["recordsHeld"] == _EXPECTED_HELD
    # The as-of date must stay pinned to when the numbers were actually taken, not roll forward.
    assert degraded["asOf"] == fresh["asOf"]


def test_figures_older_than_the_stale_grace_are_withheld_rather_than_published(
    corpus, monkeypatch: pytest.MonkeyPatch
) -> None:
    corpus()
    asyncio.run(_get())
    monkeypatch.setattr(public, "_CACHE_TTL_SECONDS", 0.0)
    monkeypatch.setattr(public, "_RETRY_BACKOFF_SECONDS", 0.0)
    monkeypatch.setattr(public, "_STALE_GRACE_SECONDS", -1.0)
    corpus(failing=set(_TOTALS))

    body = asyncio.run(_get()).json()

    assert body["counted"] is False


def test_a_failed_count_is_not_retried_by_every_arriving_request(
    corpus, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A public URL under load turns "retry immediately" into a request-rate hammering of a database
    that is already struggling — the amplification shape that has taken this API down before."""
    monkeypatch.setattr(public, "_CACHE_TTL_SECONDS", 0.0)
    tables = corpus(failing=set(_TOTALS))

    for _ in range(5):
        asyncio.run(_get())

    assert tables.query_count == len(_TOTALS)


# --- What the caches are told --------------------------------------------------------------------


def test_no_cache_is_told_to_hold_a_copy_longer_than_this_process_considers_one_current(
    corpus,
) -> None:
    corpus()

    cache_control = asyncio.run(_get()).headers["cache-control"]

    shared = int(re.search(r"s-maxage=(\d+)", cache_control).group(1))
    browser = int(re.search(r"(?<!s-)max-age=(\d+)", cache_control).group(1))
    assert "public" in cache_control
    assert shared == int(public._CACHE_TTL_SECONDS)
    assert browser <= shared


def test_a_degraded_answer_earns_a_shorter_cache_life_never_a_longer_one(
    corpus, monkeypatch: pytest.MonkeyPatch
) -> None:
    corpus()
    fresh = asyncio.run(_get()).headers["cache-control"]
    monkeypatch.setattr(public, "_CACHE_TTL_SECONDS", 0.0)
    monkeypatch.setattr(public, "_RETRY_BACKOFF_SECONDS", 0.0)
    corpus(failing=set(_TOTALS))

    stale = asyncio.run(_get()).headers["cache-control"]

    fresh_shared = int(re.search(r"s-maxage=(\d+)", fresh).group(1))
    stale_shared = int(re.search(r"s-maxage=(\d+)", stale).group(1))
    assert stale_shared < fresh_shared


def test_an_uncountable_census_is_never_remembered_anywhere(corpus) -> None:
    """A cached failure would keep the ledger blank for the whole TTL after the database came back."""
    corpus(failing=set(_TOTALS))

    assert asyncio.run(_get()).headers["cache-control"] == "no-store"
