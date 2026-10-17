"""``GET /review/pending`` — longest-waiting first, one page read as one page, and the filters.

UX audit 2026-10-16: the queue read at most ``PENDING_TAKE`` (200) rows per record type and said
nothing when it stopped. A caller that passes ``page`` gets one page of the WHOLE queue with the true
``total``; a caller that does not keeps the old answer, plus ``truncated``.

2026-10-20 (A3, B13, A13): the queue is served by ``submittedAt`` ascending — the longest-waiting
record first, and a record that was sent back and fixed comes back at the front — a page is read as
ONE key query across the tables (``pending_page_keys``) and then by id, and it can be narrowed to a
workshop, a creator and record types.

NOTHING HERE TOUCHES A DATABASE: ``_PENDING_SOURCES`` is replaced by in-memory delegates and the key
query by an in-memory equivalent. ``pending_keys_sql`` is checked as text here; ``test_review_pending_sql_db.py`` runs it against a
real Postgres.
"""

import asyncio
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.router import api_router
from app.api.routes import review
from app.core import deps

_BASE = datetime(2026, 10, 1, tzinfo=timezone.utc)


def _user(role: str, user_id: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.test",
        name=role.title(),
        role=role,
        canReview=False,
        canDownloadDataset=False,
        canViewProvenance=False,
    )


class _Delegate:
    """One record type's pending rows, honouring ``take``, ``order`` and an id list."""

    def __init__(self, prefix: str, minutes: list[int]) -> None:
        self.rows = [
            SimpleNamespace(
                id=f"{prefix}{m}",
                name=f"{prefix} {m}",
                title=f"{prefix} {m}",
                place=None,
                status="PENDING",
                createdAt=_BASE + timedelta(minutes=m),
                submittedAt=_BASE + timedelta(minutes=m),
                createdBy=_user("RESEARCHER", "author"),
                workshopId=None,
                reviewedAt=None,
                reviewedById=None,
                reviewNotes=None,
                extraMetadata={},
            )
            for m in minutes
        ]
        self.takes: list[int] = []
        self.wheres: list[dict] = []

    async def find_many(self, where: dict, include: dict | None = None, order: Any = None, take: int | None = None) -> list:
        self.wheres.append(where)
        rows = self.rows
        if "id" in where and isinstance(where["id"], dict) and "in" in where["id"]:
            wanted = set(where["id"]["in"])
            return [row for row in rows if row.id in wanted]
        self.takes.append(take or 0)
        newest = bool(order) and order.get("submittedAt") == "desc"
        ordered = sorted(rows, key=lambda row: row.submittedAt, reverse=newest)
        return ordered[: take or len(ordered)]

    async def count(self, where: dict) -> int:
        self.wheres.append(where)
        return len(self.rows)


_CURRENT: dict[str, Any] = {"user": None}
_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


def _get(path: str) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://review.test") as client:
            return await client.get(f"/api/review{path}")

    return asyncio.run(run())


@pytest.fixture
def sources(monkeypatch: pytest.MonkeyPatch):
    # Artisans at even minutes, workshops at odd ones: the merged order interleaves the two types.
    artisans = _Delegate("a", list(range(0, 50, 2)))
    workshops = _Delegate("w", list(range(1, 50, 2)))
    delegates = {"artisan": artisans, "workshop": workshops}
    monkeypatch.setattr(
        review,
        "_PENDING_SOURCES",
        [("artisan", artisans, ("name",)), ("workshop", workshops, ("title",))],
    )
    calls: list[dict[str, Any]] = []

    async def keys(filters: review.PendingFilters, *, limit: int, offset: int) -> list[tuple[str, str]]:
        calls.append({"filters": filters, "limit": limit, "offset": offset})
        merged = [
            (row.submittedAt, record_type, row.id)
            for record_type, _d, _l in filters.sources()
            for row in delegates[record_type].rows
        ]
        merged.sort(reverse=filters.newest_first)
        return [(record_type, row_id) for _, record_type, row_id in merged[offset : offset + limit]]

    monkeypatch.setattr(review, "pending_page_keys", keys)

    async def no_files(keys: list[tuple[str, str]], reviewer: Any) -> dict[tuple[str, str], int]:
        return {}

    monkeypatch.setattr(review, "pending_media_counts", no_files)
    _CURRENT["user"] = _user("MASTER_ADMIN", "reviewer")
    yield SimpleNamespace(artisans=artisans, workshops=workshops, calls=calls)
    _CURRENT["user"] = None


def test_a_page_is_the_longest_waiting_rows_across_types_with_the_true_total(sources) -> None:
    response = _get("/pending?page=2&pageSize=10")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] == 50
    assert body["page"] == 2 and body["pageSize"] == 10
    # Minutes 0..49 oldest first; page 2 of 10 is minutes 10..19, interleaved across both types.
    assert [item["id"] for item in body["items"]] == [f"{'w' if m % 2 else 'a'}{m}" for m in range(10, 20)]
    assert "truncated" not in body
    assert body["items"][0]["submittedAt"].startswith("2026-10-01T00:10")


def test_newest_first_is_still_available(sources) -> None:
    body = _get("/pending?page=1&pageSize=3&order=newest").json()
    assert [item["id"] for item in body["items"]] == ["w49", "a48", "w47"]


def test_a_page_reads_one_page_of_keys_and_then_only_those_rows(sources) -> None:
    _get("/pending?page=3&pageSize=5")
    (call,) = sources.calls
    assert (call["limit"], call["offset"]) == (5, 10)
    # No "newest N per type" reads: the rows are fetched by id.
    assert sources.artisans.takes == [] and sources.workshops.takes == []


def test_the_last_page_is_short_and_past_it_is_empty(sources) -> None:
    assert len(_get("/pending?page=5&pageSize=10").json()["items"]) == 10
    past = _get("/pending?page=6&pageSize=10").json()
    assert past["items"] == [] and past["total"] == 50


@pytest.mark.parametrize(
    "query", ["page=0", "page=1&pageSize=0", "page=1&pageSize=101", "page=1&order=random", "page=1&recordType=media"]
)
def test_out_of_range_paging_is_refused(sources, query: str) -> None:
    assert _get(f"/pending?{query}").status_code == 422


def test_without_page_the_old_shape_is_unchanged_and_says_it_is_whole(sources) -> None:
    body = _get("/pending").json()
    assert body["total"] == 50 and len(body["items"]) == 50
    assert body["truncated"] is False
    assert "page" not in body
    assert body["items"][0]["id"] == "a0"  # oldest first in the old shape too


def test_without_page_a_type_that_hits_the_take_is_reported_as_truncated(
    sources, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(review, "PENDING_TAKE", 20)
    body = _get("/pending").json()
    assert body["truncated"] is True
    assert len(body["items"]) == 40


def test_a_reviewer_with_nobody_below_them_gets_an_empty_page(sources) -> None:
    # A granted ``canReview`` on the bottom tier: review access, and nobody beneath them.
    _CURRENT["user"] = _user("CROWDSOURCE_VOLUNTEER", "reviewer")
    _CURRENT["user"].canReview = True
    response = _get("/pending?page=1")
    assert response.status_code == 200, response.text
    assert response.json() == {"items": [], "total": 0, "page": 1, "pageSize": 20}


def test_the_filters_reach_the_query(sources) -> None:
    body = _get("/pending?page=1&workshopId=W1&createdById=U1&recordType=workshop").json()
    (call,) = sources.calls
    filters = call["filters"]
    assert (filters.workshop_id, filters.created_by, filters.record_types) == ("W1", "U1", ("workshop",))
    assert all(item["recordType"] == "workshop" for item in body["items"])
    # The count used the same narrowing: a workshop row is its own workshop.
    assert sources.workshops.wheres[0] == {"status": "PENDING", "createdById": "U1", "id": "W1"}
    assert sources.artisans.wheres == []


def test_a_resubmitted_record_carries_what_it_was_sent_back_with(sources, monkeypatch: pytest.MonkeyPatch) -> None:
    row = sources.artisans.rows[0]
    row.reviewedAt = _BASE
    row.reviewedById = "rev-1"
    row.reviewNotes = "Add the village."

    async def logs(**kwargs: Any) -> list:
        return [SimpleNamespace(recordId="a0", status="NEEDS_REVISION", reason="MISSING_DETAILS")]

    async def users(**kwargs: Any) -> list:
        return [SimpleNamespace(id="rev-1", name="Dr Rao")]

    monkeypatch.setattr(
        review,
        "db",
        SimpleNamespace(reviewlog=SimpleNamespace(find_many=logs), user=SimpleNamespace(find_many=users)),
    )
    first, second = _get("/pending?page=1&pageSize=2").json()["items"]
    assert first["resubmitted"] is True and second["resubmitted"] is False
    assert first["previousReview"]["notes"] == "Add the village."
    assert first["previousReview"]["reviewerName"] == "Dr Rao"
    assert first["previousReview"]["reasonLabel"] == "Missing details"
    assert second["previousReview"] is None


def test_the_key_query_is_one_ordered_union_with_its_ids_as_parameters() -> None:
    filters = review.PendingFilters(roles=["RESEARCHER", "NOT_A_ROLE"], workshop_id="W1", created_by="U1")
    sql, params = review.pending_keys_sql(filters, limit=20, offset=40)
    assert sql.count("UNION ALL") == len(review._PENDING_SOURCES) - 1
    assert 'ORDER BY q."submittedAt" ASC' in sql and sql.endswith("LIMIT 20 OFFSET 40")
    assert "'RESEARCHER'" in sql and "NOT_A_ROLE" not in sql
    assert params == ["U1", "W1"]
    assert "W1" not in sql and "U1" not in sql
    assert '"WorkshopArtisan"' in sql  # an artisan reaches a workshop through the roster too
    newest, _ = review.pending_keys_sql(review.PendingFilters(newest_first=True), limit=5, offset=0)
    assert 'q."submittedAt" DESC' in newest and "JOIN" not in newest


def test_a_tasks_artisans_narrow_every_type_and_travel_as_parameters() -> None:
    filters = review.PendingFilters(artisan_ids=("A1", "A2"), created_by="U1")
    sql, params = review.pending_keys_sql(filters, limit=20, offset=0)
    assert params == ["U1", "A1", "A2"] and "A1" not in sql
    assert 't."id" IN ($2, $3)' in sql and 't."artisanId" IN ($2, $3)' in sql
    assert '"QuestionnaireInterviewArtisan"' in sql and '"ProductDocumentation" p' in sql
    assert " AND FALSE" in sql  # a workshop record has no artisan
    assert review.pending_where("process", filters)["AND"][1] == {"product": {"is": {"artisanId": {"in": ["A1", "A2"]}}}}
    assert review.pending_where("workshop", filters)["AND"][1] == {"id": {"in": []}}


def test_the_artisan_filter_reaches_the_queue(sources) -> None:
    _get("/pending?page=1&artisanId=a4,a2")
    (call,) = sources.calls
    assert call["filters"].artisan_ids == ("a2", "a4")
