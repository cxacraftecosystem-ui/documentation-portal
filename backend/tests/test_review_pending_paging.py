"""``GET /review/pending`` — the additive paged shape, and the truncation flag on the old one.

UX audit 2026-10-16: the queue read at most ``PENDING_TAKE`` (200) rows per record type and said
nothing when it stopped, so a reviewer behind a busy workshop saw a queue that ended quietly. A
caller that passes ``page`` now gets one page of the WHOLE queue with the true ``total``; a caller
that does not keeps the old answer, plus ``truncated``.

NOTHING HERE TOUCHES A DATABASE: ``_PENDING_SOURCES`` is replaced by in-memory delegates.
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
    """One record type's pending rows, newest first, honouring ``take`` and ``count``."""

    def __init__(self, prefix: str, minutes: list[int]) -> None:
        self.rows = [
            SimpleNamespace(
                id=f"{prefix}{m}",
                name=f"{prefix} {m}",
                title=f"{prefix} {m}",
                place=None,
                status="PENDING",
                createdAt=_BASE + timedelta(minutes=m),
                createdBy=_user("RESEARCHER", "author"),
                workshopId=None,
                extraMetadata={},
            )
            for m in minutes
        ]
        self.takes: list[int] = []

    async def find_many(self, where: dict, include: dict | None = None, order: Any = None, take: int = 0) -> list:
        self.takes.append(take)
        ordered = sorted(self.rows, key=lambda row: row.createdAt, reverse=True)
        return ordered[:take]

    async def count(self, where: dict) -> int:
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
    monkeypatch.setattr(
        review,
        "_PENDING_SOURCES",
        [("artisan", artisans, ("name",)), ("workshop", workshops, ("title",))],
    )
    _CURRENT["user"] = _user("MASTER_ADMIN", "reviewer")
    yield artisans, workshops
    _CURRENT["user"] = None


def test_a_page_is_the_newest_rows_across_types_with_the_true_total(sources) -> None:
    response = _get("/pending?page=2&pageSize=10")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["total"] == 50
    assert body["page"] == 2 and body["pageSize"] == 10
    # Minutes 49..0 newest first; page 2 of 10 is minutes 39..30, interleaved across both types.
    assert [item["id"] for item in body["items"]] == [
        f"{'w' if m % 2 else 'a'}{m}" for m in range(39, 29, -1)
    ]
    assert "truncated" not in body


def test_paging_reads_only_enough_rows_to_answer_the_page(sources) -> None:
    artisans, workshops = sources
    _get("/pending?page=3&pageSize=5")
    assert artisans.takes == [15] and workshops.takes == [15]


def test_the_last_page_is_short_and_past_it_is_empty(sources) -> None:
    assert len(_get("/pending?page=5&pageSize=10").json()["items"]) == 10
    past = _get("/pending?page=6&pageSize=10").json()
    assert past["items"] == [] and past["total"] == 50


@pytest.mark.parametrize("query", ["page=0", "page=1&pageSize=0", "page=1&pageSize=101"])
def test_out_of_range_paging_is_refused(sources, query: str) -> None:
    assert _get(f"/pending?{query}").status_code == 422


def test_without_page_the_old_shape_is_unchanged_and_says_it_is_whole(sources) -> None:
    body = _get("/pending").json()
    assert body["total"] == 50 and len(body["items"]) == 50
    assert body["truncated"] is False
    assert "page" not in body


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
