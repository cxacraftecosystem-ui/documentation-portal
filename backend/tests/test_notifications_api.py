"""The in-app activity list's routes (``app/api/routes/notifications.py``): the caller's own notices,
newest first and paged, the unread count, and marking read — one, several, or all — idempotently.

Over HTTP against the real router, with ``get_current_user`` overridden and the route module's ``db``
replaced by a small in-memory table that answers exactly the calls the routes make. The same file,
byte for byte, runs in the documentation portal: the two servers' routes are one contract.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.router import api_router
from app.api.routes import notifications as routes
from app.core import deps

_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://notices.test") as client:
            return await client.request(method, path, json=body)

    return asyncio.run(run())


def _matches(row: Any, where: dict[str, Any]) -> bool:
    for key, expected in where.items():
        actual = getattr(row, key)
        if isinstance(expected, dict):
            if "in" in expected and actual not in expected["in"]:
                return False
        elif actual != expected:
            return False
    return True


class _Table:
    def __init__(self) -> None:
        self.rows: list[SimpleNamespace] = []

    def add(self, user_id: str, title: str, minutes_ago: int, *, read: bool = False) -> SimpleNamespace:
        created = datetime(2026, 10, 17, 12, tzinfo=UTC) - timedelta(minutes=minutes_ago)
        row = SimpleNamespace(
            id=f"n{len(self.rows) + 1:02d}", userId=user_id, kind="TASK_SENT_BACK", title=title, body=None,
            link="/tasks", emailMessageId=None, readAt=created if read else None, createdAt=created,
        )
        self.rows.append(row)
        return row

    async def count(self, where: dict[str, Any]) -> int:
        return sum(1 for r in self.rows if _matches(r, where))

    async def find_first(self, where: dict[str, Any]) -> Any:
        return next((r for r in self.rows if _matches(r, where)), None)

    async def find_unique(self, where: dict[str, Any]) -> Any:
        return next((r for r in self.rows if r.id == where["id"]), None)

    async def find_many(self, where: dict[str, Any], order: Any, take: int, cursor: Any = None, skip: int = 0) -> list[Any]:
        found = sorted((r for r in self.rows if _matches(r, where)), key=lambda r: (r.createdAt, r.id), reverse=True)
        if cursor is not None:
            ids = [r.id for r in found]
            found = found[ids.index(cursor["id"]):] if cursor["id"] in ids else []
        return found[skip: skip + take]

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        hits = [r for r in self.rows if _matches(r, where)]
        for row in hits:
            vars(row).update(data)
        return len(hits)


@pytest.fixture
def table(monkeypatch: pytest.MonkeyPatch) -> Any:
    rows = _Table()
    monkeypatch.setattr(routes, "db", SimpleNamespace(notification=rows))
    _CURRENT["user"] = SimpleNamespace(id="asha", email="asha@example.org", name="Asha", role="RESEARCHER")
    yield rows
    _CURRENT["user"] = None


def test_the_list_is_the_callers_own_newest_first_with_the_unread_count(table: Any) -> None:
    table.add("asha", "older", 30)
    table.add("asha", "newer", 5)
    table.add("asha", "read one", 60, read=True)
    table.add("bina", "not hers", 1)
    answer = _call("GET", "/api/notifications")
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert [item["title"] for item in body["items"]] == ["newer", "older", "read one"]
    assert body["unreadCount"] == 2 and body["hasMore"] is False
    first = body["items"][0]
    assert set(first) == {"id", "kind", "title", "body", "link", "read", "readAt", "createdAt"}
    assert first["read"] is False and first["link"] == "/tasks"
    assert _call("GET", "/api/notifications/unread-count").json() == {"unreadCount": 2}
    unread = _call("GET", "/api/notifications?unreadOnly=true").json()
    assert [item["title"] for item in unread["items"]] == ["newer", "older"]


def test_pages_follow_the_last_id_shown(table: Any) -> None:
    for minutes in range(5):
        table.add("asha", f"notice {minutes}", minutes)
    first = _call("GET", "/api/notifications?limit=2").json()
    assert [item["title"] for item in first["items"]] == ["notice 0", "notice 1"] and first["hasMore"] is True
    second = _call("GET", f"/api/notifications?limit=2&before={first['items'][-1]['id']}").json()
    assert [item["title"] for item in second["items"]] == ["notice 2", "notice 3"] and second["hasMore"] is True
    last = _call("GET", f"/api/notifications?limit=2&before={second['items'][-1]['id']}").json()
    assert [item["title"] for item in last["items"]] == ["notice 4"] and last["hasMore"] is False
    # Somebody else's id is no anchor: the list starts from the top.
    other = table.add("bina", "not hers", 100)
    assert _call("GET", f"/api/notifications?limit=2&before={other.id}").json()["items"][0]["title"] == "notice 0"


def test_marking_one_read_is_idempotent_and_keeps_the_first_moment(table: Any) -> None:
    row = table.add("asha", "a", 1)
    table.add("asha", "b", 2)
    first = _call("POST", f"/api/notifications/{row.id}/read")
    assert first.status_code == 200 and first.json()["item"]["read"] is True
    assert first.json()["unreadCount"] == 1
    moment = row.readAt
    again = _call("POST", f"/api/notifications/{row.id}/read")
    assert again.status_code == 200 and row.readAt == moment


def test_another_persons_notice_is_not_found_and_never_marked(table: Any) -> None:
    theirs = table.add("bina", "not hers", 1)
    assert _call("POST", f"/api/notifications/{theirs.id}/read").status_code == 404
    answer = _call("POST", "/api/notifications/read", {"ids": [theirs.id]}).json()
    assert answer == {"updated": 0, "unreadCount": 0}
    assert theirs.readAt is None


def test_a_batch_marks_the_named_ones_and_all_marks_the_rest(table: Any) -> None:
    a, b, c = table.add("asha", "a", 1), table.add("asha", "b", 2), table.add("asha", "c", 3)
    theirs = table.add("bina", "not hers", 1)
    assert _call("POST", "/api/notifications/read", {"ids": [a.id, a.id, b.id]}).json() == {"updated": 2, "unreadCount": 1}
    # A replay of the same mark (the handset's offline queue) changes nothing.
    assert _call("POST", "/api/notifications/read", {"ids": [a.id, b.id]}).json() == {"updated": 0, "unreadCount": 1}
    assert _call("POST", "/api/notifications/read", {}).json() == {"updated": 0, "unreadCount": 1}
    assert _call("POST", "/api/notifications/read", {"all": True}).json() == {"updated": 1, "unreadCount": 0}
    assert c.readAt is not None and theirs.readAt is None


def test_the_page_size_is_bounded(table: Any) -> None:
    assert _call("GET", "/api/notifications?limit=0").status_code == 422
    assert _call("GET", f"/api/notifications?limit={routes.MAX_PAGE + 1}").status_code == 422
