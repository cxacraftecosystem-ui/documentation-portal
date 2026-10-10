"""Where the documentation portal e-mails from: admitting an address with "send an invitation", a task's
status move, and the notification preference routes.

The roster routes run over HTTP against ``access_roster_fakes.FakeDb`` (the harness the roster's own
tests use) with ``notify_access_granted`` replaced by a recorder. The task route is called directly with
its database seams replaced, so what is asserted is that the move is handed to
``notify_task_change`` with the right before/after and who moved it. Which move earns which e-mail is
``test_email_outbox.py``.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeDb, install
from app.api.router import api_router
from app.api.routes import access_roster as roster_routes
from app.api.routes import preferences as preference_routes
from app.api.routes import tasks as task_routes
from app.core import deps
from app.core.config import get_settings
from app.schemas.tasks import TaskUpdate
from app.services import mailer

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
        async with httpx.AsyncClient(transport=transport, base_url="http://mail.test") as client:
            return await client.request(method, path, json=body)

    return asyncio.run(run())


def _admin() -> SimpleNamespace:
    caller = SimpleNamespace(
        id="admin-1", email="admin-1@example.org", name="An Admin", role="ADMIN",
        canManageQuestionnaire=False, canManageCrafts=False, canManageWorkshops=False,
        canReview=False, canViewProvenance=False, canDownloadDataset=False,
    )
    _CURRENT["user"] = caller
    return caller


@pytest.fixture
def invites(monkeypatch: pytest.MonkeyPatch) -> list[dict[str, Any]]:
    settings = get_settings()
    monkeypatch.setattr(settings, "master_admin_email", "master@example.org")
    monkeypatch.setattr(settings, "access_roster_enforced", True)
    seen: list[dict[str, Any]] = []

    async def record(email: str, *, name: str | None) -> bool:
        seen.append({"email": email, "name": name})
        return True

    monkeypatch.setattr(roster_routes, "notify_access_granted", record)
    yield seen
    _CURRENT["user"] = None


def test_admitting_with_an_invitation_e_mails_the_address(monkeypatch: pytest.MonkeyPatch, invites: list[Any]) -> None:
    install(monkeypatch, FakeDb())
    _admin()
    made = _call("POST", "/api/access-roster", {"email": "new@example.org", "fullName": "New Person", "sendInvite": True})
    assert made.status_code == 201, made.text
    assert invites == [{"email": "new@example.org", "name": "New Person"}]


def test_admitting_without_asking_e_mails_nobody(monkeypatch: pytest.MonkeyPatch, invites: list[Any]) -> None:
    install(monkeypatch, FakeDb())
    _admin()
    assert _call("POST", "/api/access-roster", {"email": "quiet@example.org"}).status_code == 201
    assert _call(
        "POST", "/api/access-roster", {"email": "held@example.org", "isActive": False, "sendInvite": True}
    ).status_code == 201
    assert invites == [], "a row that is not admitted is never told it may sign in"


def test_approving_a_request_with_an_invitation_e_mails_it(monkeypatch: pytest.MonkeyPatch, invites: list[Any]) -> None:
    install(
        monkeypatch,
        FakeDb(roster=[{"id": "roster-w", "email": "waiting@example.org", "status": "PENDING"}]),
    )
    _admin()
    rejected = _call("PATCH", "/api/access-roster/roster-w", {"status": "REJECTED", "sendInvite": True})
    assert rejected.status_code == 200 and invites == []
    approved = _call("PATCH", "/api/access-roster/roster-w", {"status": "ACTIVE", "sendInvite": True})
    assert approved.status_code == 200, approved.text
    assert [i["email"] for i in invites] == ["waiting@example.org"]


class _Preferences:
    def __init__(self) -> None:
        self.row: Any = None

    async def find_unique(self, where: dict[str, Any]) -> Any:
        return self.row

    async def upsert(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        values = data["update"] if self.row is not None else data["create"]
        self.row = SimpleNamespace(**{**vars(self.row or SimpleNamespace()), **values})
        return self.row


def test_the_preference_routes_report_availability_and_keep_the_opt_out(monkeypatch: pytest.MonkeyPatch) -> None:
    table = _Preferences()
    monkeypatch.setattr(preference_routes, "db", SimpleNamespace(userpreference=table))
    on = get_settings().model_copy(update={"mail_from_address": "no-reply@repo.example.org"})
    monkeypatch.setattr(mailer, "get_settings", lambda: on)
    _admin()
    try:
        assert _call("GET", "/api/preferences/notifications").json() == {"available": True, "emailTaskUpdates": True}
        saved = _call("PUT", "/api/preferences/notifications", {"emailTaskUpdates": False})
        assert saved.json() == {"available": True, "emailTaskUpdates": False}
        assert _call("GET", "/api/preferences/notifications").json()["emailTaskUpdates"] is False
        off = get_settings().model_copy(update={"mail_from_address": None})
        monkeypatch.setattr(mailer, "get_settings", lambda: off)
        assert _call("GET", "/api/preferences/notifications").json()["available"] is False
    finally:
        _CURRENT["user"] = None


@pytest.fixture
def task_seams(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    task = SimpleNamespace(
        id="t1", title="Photograph the looms", status="IN_PROGRESS", createdById="creator", assigneeId="assignee",
        workshopId=None, recordTypes=[], artisanIds=[], sectionIds=[], targetCount=None, progressCount=0,
    )
    calls: list[dict[str, Any]] = []

    async def require(task_id: str) -> Any:
        return task

    async def update(where: dict[str, Any], data: dict[str, Any], include: Any = None) -> Any:
        return SimpleNamespace(**{**vars(task), **{k: v for k, v in data.items() if not isinstance(v, dict)}})

    async def serialize(rows: list[Any], with_derived: bool = True) -> list[dict[str, Any]]:
        return [{"id": r.id, "status": r.status} for r in rows]

    async def notify(row: Any, **kwargs: Any) -> int:
        calls.append(kwargs)
        return 1

    monkeypatch.setattr(task_routes, "require_task", require)
    monkeypatch.setattr(task_routes, "db", SimpleNamespace(assignedtask=SimpleNamespace(update=update)))
    monkeypatch.setattr(task_routes, "serialize_tasks", serialize)
    monkeypatch.setattr(task_routes, "notify_task_change", notify)
    return SimpleNamespace(task=task, calls=calls)


def test_handing_a_task_in_is_passed_on_as_a_move_by_the_assignee(task_seams: Any) -> None:
    assignee = SimpleNamespace(id="assignee", name="Asha", role="RESEARCHER")
    asyncio.run(task_routes.update_task("t1", TaskUpdate(status="DONE"), assignee))
    (call,) = task_seams.calls
    assert call["before"] == "IN_PROGRESS" and call["after"] == "SUBMITTED"
    assert call["by_manager"] is False and call["actor"] is assignee


def test_sending_a_task_back_is_passed_on_as_a_managers_move(task_seams: Any) -> None:
    task_seams.task.status = "SUBMITTED"
    admin = SimpleNamespace(id="admin-1", name="An Admin", role="ADMIN")
    asyncio.run(task_routes.update_task("t1", TaskUpdate(status="IN_PROGRESS"), admin))
    (call,) = task_seams.calls
    assert (call["before"], call["after"], call["by_manager"]) == ("SUBMITTED", "IN_PROGRESS", True)
