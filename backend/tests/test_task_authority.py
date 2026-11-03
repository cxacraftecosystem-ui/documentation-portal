import asyncio
import sys
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps

ALL_ROLES = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "PROFESSOR",
    "ADMIN",
    "MASTER_ADMIN",
)

ADMIN_ROLES = ("ADMIN", "MASTER_ADMIN")
NON_ADMIN_ROLES = tuple(role for role in ALL_ROLES if role not in ADMIN_ROLES)

MASTER_ID = "u_master"
ADMIN_ID = "u_admin"
WORKER_ID = "u_worker"


def _user(role: str, user_id: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.test",
        name=role.title().replace("_", " "),
        role=role,
        canReview=False,
        canDownloadDataset=False,
        canViewProvenance=False,
    )


PEOPLE: dict[str, SimpleNamespace] = {
    MASTER_ID: _user("MASTER_ADMIN", MASTER_ID),
    ADMIN_ID: _user("ADMIN", ADMIN_ID),
    WORKER_ID: _user("RESEARCHER", WORKER_ID),
}


def _task(**overrides: Any) -> SimpleNamespace:
    row = {
        "id": "t1",
        "title": "Record 10 tools for the Bagru trip",
        "description": None,
        "status": "OPEN",
        "dueAt": None,
        "completedAt": None,
        "recordType": None,
        "recordId": None,
        "workshopId": None,
        "recordTypes": [],
        "artisanIds": [],
        "sectionIds": [],
        "targetCount": None,
        "progressCount": 0,
        "batchId": None,
        "assigneeId": WORKER_ID,
        "createdById": ADMIN_ID,
        "createdAt": datetime(2026, 9, 1, tzinfo=UTC),
        "updatedAt": datetime(2026, 9, 1, tzinfo=UTC),
        "assignee": PEOPLE[WORKER_ID],
        "createdBy": PEOPLE[ADMIN_ID],
        "workshop": None,
    }
    row.update(overrides)
    return SimpleNamespace(**row)


class _Delegate:
    def __init__(self, row: Any = None) -> None:
        self.row = row
        self.creates: list[dict] = []
        self.updates: list[dict] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> Any:
        return self.row

    async def find_first(self, where: dict, include: dict | None = None) -> Any:
        return self.row

    async def find_many(self, where: dict | None = None, **_: Any) -> list:
        batch = (where or {}).get("batchId")
        if batch:
            return [_task(**data) for data in self.creates if data.get("batchId") == batch]
        return []

    async def count(self, where: dict | None = None, **_: Any) -> int:
        return 0

    async def create(self, data: dict, include: dict | None = None) -> Any:
        self.creates.append(data)
        return _task(**{key: value for key, value in data.items() if key != "id"})

    async def create_many(self, data: list[dict]) -> int:
        self.creates.extend(data)
        return len(data)

    async def update(self, where: dict, data: dict, include: dict | None = None) -> Any:
        self.updates.append(data)
        merged = {key: value for key, value in data.items() if not isinstance(value, dict)}
        return _task(**{**vars(self.row), **merged}) if self.row else _task(**merged)


class _UserDelegate(_Delegate):
    async def find_unique(self, where: dict, include: dict | None = None) -> Any:
        return PEOPLE.get(where.get("id"))

    async def find_many(self, where: dict | None = None, **_: Any) -> list:
        wanted = ((where or {}).get("id") or {}).get("in") or []
        return [PEOPLE[uid] for uid in wanted if uid in PEOPLE]


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _Board:
    def __init__(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self.assignedtask = _Delegate(_task())
        self.user = _UserDelegate()
        fake_db = SimpleNamespace(
            assignedtask=self.assignedtask,
            user=self.user,
            workshop=_Delegate(),
            artisan=_Delegate(),
            questionnairesection=_Delegate(),
            questionnaireresponse=_Delegate(),
            questionnaireinterview=_Delegate(),
            productdocumentation=_Delegate(),
            tooldocumentation=_Delegate(),
            process=_Delegate(),
            mediafile=_Delegate(),
        )
        real_db = core_db.db
        monkeypatch.setattr(core_db, "db", fake_db)
        for module in list(sys.modules.values()):
            is_app = getattr(module, "__name__", "").startswith("app.")
            if is_app and getattr(module, "db", None) is real_db:
                monkeypatch.setattr(module, "db", fake_db)

    def holding(self, **overrides: Any) -> "_Board":
        self.assignedtask.row = _task(**overrides)
        self.assignedtask.updates.clear()
        self.assignedtask.creates.clear()
        return self

    def as_(self, role: str, user_id: str | None = None) -> "_Board":
        _CURRENT["user"] = _user(role, user_id) if user_id else PEOPLE[_ID_FOR_ROLE[role]]
        return self

    def _call(self, method: str, path: str, body: dict | None = None) -> httpx.Response:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            base = "http://tasks.test"
            async with httpx.AsyncClient(transport=transport, base_url=base) as client:
                return await client.request(method, f"/api/tasks{path}", json=body)

        return asyncio.run(run())

    def post(self, path: str, body: dict) -> httpx.Response:
        return self._call("POST", path, body)

    def patch(self, path: str, body: dict) -> httpx.Response:
        return self._call("PATCH", path, body)


_ID_FOR_ROLE = {
    "MASTER_ADMIN": MASTER_ID,
    "ADMIN": ADMIN_ID,
    "RESEARCHER": WORKER_ID,
}
for _role in ALL_ROLES:
    if _role not in _ID_FOR_ROLE:
        _ID_FOR_ROLE[_role] = f"u_{_role.lower()}"
        PEOPLE[_ID_FOR_ROLE[_role]] = _user(_role, _ID_FOR_ROLE[_role])

_PEER_ID_FOR_ROLE = {role: f"u_peer_{role.lower()}" for role in ALL_ROLES}
for _role, _peer_id in _PEER_ID_FOR_ROLE.items():
    PEOPLE[_peer_id] = _user(_role, _peer_id)


@pytest.fixture
def board(monkeypatch: pytest.MonkeyPatch):
    b = _Board(monkeypatch)
    yield b
    _CURRENT["user"] = None



@pytest.mark.parametrize("role", ADMIN_ROLES)
def test_an_admin_may_assign_a_task_to_themselves(board: _Board, role: str) -> None:
    me = _ID_FOR_ROLE[role]

    response = board.as_(role).post("", {"title": "Draft the Bagru report", "assigneeId": me})

    assert response.status_code == 201, response.text
    written = board.assignedtask.creates[0]
    assert written["assigneeId"] == me
    assert written["createdById"] == me


@pytest.mark.parametrize("role", ADMIN_ROLES)
def test_an_admin_may_put_themselves_into_a_batch_alongside_the_people_they_assigned(
    board: _Board, role: str
) -> None:
    me = _ID_FOR_ROLE[role]

    response = board.as_(role).post(
        "/batch",
        {
            "title": "Tool survey",
            "assigneeIds": [WORKER_ID, me],
            "recordTypes": ["tool"],
            "targetCount": 10,
        },
    )

    assert response.status_code == 201, response.text
    assert [row["assigneeId"] for row in board.assignedtask.creates] == [WORKER_ID, me]


@pytest.mark.parametrize("role", NON_ADMIN_ROLES)
def test_a_non_admin_cannot_create_a_task_at_all_so_the_lifted_ban_reaches_nobody_below_admin(
    board: _Board, role: str
) -> None:
    response = board.as_(role).post("", {"title": "Mine", "assigneeId": _ID_FOR_ROLE[role]})

    assert response.status_code == 403, response.text
    assert response.json()["detail"] == "Admin access required"
    assert board.assignedtask.creates == []


def test_a_demoted_creator_still_may_not_move_their_old_task_onto_themselves(board: _Board) -> None:
    demoted = PEOPLE[WORKER_ID]

    response = (
        board.holding(createdById=demoted.id, assigneeId="u_someone_else")
        .as_("RESEARCHER")
        .patch("/t1", {"assigneeId": demoted.id})
    )

    assert response.status_code == 422, response.text
    assert response.json()["detail"] == "You cannot assign a task to yourself"
    assert board.assignedtask.updates == []


@pytest.mark.parametrize("assignee_role", ALL_ROLES)
def test_the_tier_rule_for_assigning_to_OTHER_people_is_untouched(
    board: _Board, assignee_role: str
) -> None:
    target = _PEER_ID_FOR_ROLE[assignee_role]

    as_admin = board.as_("ADMIN").post("", {"title": "Survey", "assigneeId": target})
    as_master = board.as_("MASTER_ADMIN").post("", {"title": "Survey", "assigneeId": target})

    admin_allowed = deps.ROLE_RANK[assignee_role] < deps.ROLE_RANK["ADMIN"]
    assert (as_admin.status_code == 201) is admin_allowed, (assignee_role, as_admin.text)
    if not admin_allowed:
        assert as_admin.json()["detail"] == "You can only assign tasks to users below your own tier"
    assert as_master.status_code == 201, (assignee_role, as_master.text)


def test_an_unknown_assignee_is_still_a_404_and_not_a_tier_refusal(board: _Board) -> None:
    response = board.as_("ADMIN").post("", {"title": "Survey", "assigneeId": "u_ghost"})

    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Assignee not found"



@pytest.mark.parametrize("role", ADMIN_ROLES)
@pytest.mark.parametrize("from_status", ["OPEN", "IN_PROGRESS"])
def test_an_admin_may_mark_another_persons_task_done(
    board: _Board, role: str, from_status: str
) -> None:
    response = (
        board.holding(status=from_status, createdById="u_another_admin")
        .as_(role)
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    written = board.assignedtask.updates[0]
    assert written["status"] == "DONE"
    assert written["completedAt"] is not None


@pytest.mark.parametrize("role", ADMIN_ROLES)
@pytest.mark.parametrize("back_to", ["OPEN", "IN_PROGRESS"])
def test_an_admin_may_reopen_a_task_they_marked_done_and_the_completion_stamp_is_cleared(
    board: _Board, role: str, back_to: str
) -> None:
    response = (
        board.holding(status="DONE", completedAt=datetime(2026, 9, 10, tzinfo=UTC))
        .as_(role)
        .patch("/t1", {"status": back_to})
    )

    assert response.status_code == 200, response.text
    written = board.assignedtask.updates[0]
    assert written["status"] == back_to
    assert written["completedAt"] is None


def test_marking_a_quota_task_done_fills_the_reported_figure_to_the_target(board: _Board) -> None:
    response = (
        board.holding(targetCount=10, progressCount=0).as_("ADMIN").patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["progressCount"] == 10


def test_reopening_does_not_put_the_reported_figure_back(board: _Board) -> None:
    response = (
        board.holding(status="DONE", targetCount=10, progressCount=10)
        .as_("ADMIN")
        .patch("/t1", {"status": "OPEN"})
    )

    assert response.status_code == 200, response.text
    assert "progressCount" not in board.assignedtask.updates[0]


def test_an_assignee_may_finish_their_own_task_but_still_may_not_cancel_it(board: _Board) -> None:
    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    finished = mine.patch("/t1", {"status": "DONE"})
    assert finished.status_code == 200, finished.text
    assert board.assignedtask.updates[0]["status"] == "SUBMITTED"
    assert finished.json()["status"] == "SUBMITTED"

    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    cancelled = mine.patch("/t1", {"status": "CANCELLED"})
    assert cancelled.status_code == 403, cancelled.text
    assert cancelled.json()["detail"] == "Only the task creator or an admin can cancel a task"
    assert board.assignedtask.updates == []


def test_a_bystander_cannot_mark_somebody_elses_task_done(board: _Board) -> None:
    response = (
        board.holding(assigneeId="u_someone_else", createdById=ADMIN_ID)
        .as_("RESEARCHER")
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 403, response.text
    refusal = "Only the assignee, the creator, or an admin can update this task"
    assert response.json()["detail"] == refusal
    assert board.assignedtask.updates == []


def test_an_override_leaves_no_trace_of_who_performed_it(board: _Board) -> None:
    board.holding(assigneeId=WORKER_ID, createdById="u_another_admin").as_("ADMIN").patch(
        "/t1", {"status": "DONE"}
    )
    by_admin = dict(board.assignedtask.updates[0])

    board.holding(assigneeId=WORKER_ID, createdById="u_another_admin").as_("RESEARCHER").patch(
        "/t1", {"status": "DONE"}
    )
    by_assignee = dict(board.assignedtask.updates[0])

    assert set(by_admin) == set(by_assignee) == {"status", "completedAt"}
    assert by_admin["status"] == "DONE"
    assert by_assignee["status"] == "SUBMITTED"
    assert "approvedById" not in by_admin and "approvedById" not in by_assignee
