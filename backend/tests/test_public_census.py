import asyncio
import re
from functools import lru_cache
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI
from fastapi.routing import iter_route_contexts

from app.api.router import api_router
from app.api.routes import public
from app.services.concurrency import pool_width

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


_READS = 2 * len(_TOTALS) - 1


class _Corpus:
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
    app = httpx.ASGITransport(app=_app())
    async with httpx.AsyncClient(transport=app, base_url="http://census.test") as client:
        return await asyncio.gather(*(client.get(path) for _ in range(count)))


@lru_cache(maxsize=1)
def _app():
    app = FastAPI()
    app.include_router(api_router)
    return app


@pytest.fixture
def corpus(monkeypatch: pytest.MonkeyPatch):
    def build(totals: dict[str, int] | None = None, *, delay: float = 0.0,
              failing: set | None = None) -> _Corpus:
        built = _Corpus(totals if totals is not None else _TOTALS, delay=delay, failing=failing)
        monkeypatch.setattr(public, "db", built.as_client())
        return built

    public.clear_census_cache()
    yield build
    public.clear_census_cache()



_ACTIVE_CRAFTS = {"where": {"status": "ACTIVE"}}


def test_every_record_type_is_counted_exactly_once(corpus) -> None:
    tables = corpus()

    response = asyncio.run(_get())

    assert response.status_code == 200
    assert response.json()["recordsHeld"] == _EXPECTED_HELD
    held = [name for name, kwargs in tables.calls if kwargs in ({}, _ACTIVE_CRAFTS)]
    assert sorted(held) == sorted(_TOTALS)


def test_the_census_counts_every_row_held_not_only_the_approved_ones(corpus) -> None:
    tables = corpus()

    body = asyncio.run(_get()).json()

    assert "recordsHeld" in body
    held = [name for name, kwargs in tables.calls if kwargs == {} or (name == "craft" and kwargs == _ACTIVE_CRAFTS)]
    assert sorted(held) == sorted(_TOTALS), "a where clause reached a held count"
    approved = [(name, kwargs) for name, kwargs in tables.calls if kwargs not in ({}, _ACTIVE_CRAFTS)]
    assert all(kwargs == {"where": {"status": "APPROVED"}} for _, kwargs in approved)
    assert sorted(name for name, _ in approved) == sorted(n for n in _TOTALS if n != "craft")


def test_the_public_figure_is_the_approved_one_and_crafts_count_whole(corpus) -> None:
    corpus()

    body = asyncio.run(_get()).json()

    assert body["recordsPublished"] == _EXPECTED_HELD
    assert set(body["recordsPublished"]) == set(body["recordsHeld"])


def _mounted_routes() -> list:
    return list(iter_route_contexts(api_router.routes))


def test_the_census_is_mounted_at_the_public_path_the_cdn_rule_would_be_scoped_to() -> None:
    paths = {route.path for route in _mounted_routes()}
    assert "/api/public/census" in paths


def test_the_census_asks_for_no_token(corpus) -> None:
    corpus()
    route = next(r for r in _mounted_routes() if r.path == "/api/public/census")

    assert route.dependant.dependencies == []
    assert asyncio.run(_get(headers={})).status_code == 200


_IDENTIFYING_WORDS = frozenset({
    "id", "ids", "uuid", "cuid", "key", "slug", "name", "names", "email", "phone", "mobile",
    "aadhaar", "pehchan", "card", "address", "place", "village", "district", "state", "pincode",
    "gender", "dob", "age", "lat", "latitude", "lon", "lng", "longitude", "geo", "location",
    "caption", "filename", "file", "url", "transcript", "notes", "note", "title", "description",
    "by", "owner", "author", "user", "users", "contributor", "researcher", "reviewer", "uploaded",
    "created", "reviewed", "status", "pending", "approved", "rejected",
})

_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2}))?$")


def _words(key: str) -> list[str]:
    return [part.lower() for part in re.findall(r"[a-z]+|[A-Z][a-z]*|[A-Z]+(?![a-z])", key)]


def _walk(node, path="$"):
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
    corpus()

    body = asyncio.run(_get()).json()

    assert body["asOf"].endswith("+05:30")
    assert body["asOf"].startswith(body["asOfDate"])


def test_the_shape_is_frozen_so_a_new_field_cannot_be_added_without_a_decision(corpus) -> None:
    corpus()

    body = asyncio.run(_get()).json()

    assert set(body) == {"counted", "stale", "asOf", "asOfDate", "recordsHeld", "recordsPublished"}
    assert set(body["recordsHeld"]) == set(_EXPECTED_HELD)


def test_every_published_count_is_a_plain_integer(corpus) -> None:
    corpus()

    held = asyncio.run(_get()).json()["recordsHeld"]

    assert all(isinstance(value, int) and not isinstance(value, bool) for value in held.values())



def test_the_eight_counts_go_out_together_not_one_cross_region_trip_at_a_time(corpus) -> None:
    tables = corpus(delay=0.05)

    asyncio.run(_get())

    assert tables.peak_in_flight == min(_READS, pool_width())


def test_a_burst_of_twenty_requests_costs_one_round_of_counts_not_twenty(corpus) -> None:
    tables = corpus(delay=0.02)

    responses = asyncio.run(_get_many(20))

    assert {r.status_code for r in responses} == {200}
    assert tables.query_count == _READS


def test_bursts_on_two_different_event_loops_both_work(corpus) -> None:
    tables = corpus(delay=0.02)
    asyncio.run(_get_many(5))

    public.clear_census_cache()
    asyncio.run(_get_many(5))

    assert tables.query_count == 2 * _READS


def test_a_second_request_inside_the_ttl_reads_nothing(corpus) -> None:
    tables = corpus()

    asyncio.run(_get())
    asyncio.run(_get())

    assert tables.query_count == _READS


def test_the_census_is_recounted_once_it_expires(corpus, monkeypatch: pytest.MonkeyPatch) -> None:
    tables = corpus()
    asyncio.run(_get())
    monkeypatch.setattr(public, "_CACHE_TTL_SECONDS", 0.0)

    asyncio.run(_get())

    assert tables.query_count == 2 * _READS



def test_an_empty_corpus_is_distinguishable_from_a_database_that_would_not_answer(corpus) -> None:
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
    corpus(failing=set(_TOTALS))

    body = asyncio.run(_get()).json()

    assert body["asOf"] is None
    assert body["asOfDate"] is None


def test_one_failing_count_withholds_the_census_rather_than_publishing_a_zero(corpus) -> None:
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
    monkeypatch.setattr(public, "_CACHE_TTL_SECONDS", 0.0)
    tables = corpus(failing=set(_TOTALS))

    for _ in range(5):
        asyncio.run(_get())

    assert tables.query_count == _READS



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
    corpus(failing=set(_TOTALS))

    assert asyncio.run(_get()).headers["cache-control"] == "no-store"
