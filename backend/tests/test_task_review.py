import asyncio
import sys
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.api.routes.tasks as tasks_module
import app.core.db as core_db
from app.api.router import api_router
from app.api.routes.tasks import (
    LIVE_STATUSES,
    OUTSTANDING_STATUSES,
    REVIEW_STATUSES,
    STATUS_COUNT_KEYS,
    STATUS_LABELS,
    SUBMITTED,
    TASK_STATUSES,
    _derived_target,
    _section_target,
    derive_progress,
    effective_progress,
    is_overdue,
)
from app.core import deps

MASTER_ID = "u_master"
ADMIN_ID = "u_admin"
OTHER_ADMIN_ID = "u_other_admin"
WORKER_ID = "u_worker"

NOW = datetime(2026, 9, 14, 6, 0, tzinfo=UTC)


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
    OTHER_ADMIN_ID: _user("ADMIN", OTHER_ADMIN_ID),
    WORKER_ID: _user("RESEARCHER", WORKER_ID),
}

_ID_FOR_ROLE = {"MASTER_ADMIN": MASTER_ID, "ADMIN": ADMIN_ID, "RESEARCHER": WORKER_ID}

MANAGER_ROLES = ("ADMIN", "MASTER_ADMIN")


def _task(**overrides: Any) -> SimpleNamespace:
    row = {
        "id": "t1",
        "title": "Sections C and D at Bagru",
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
    def __init__(self, row: Any = None, rows: list | None = None, count: int = 0) -> None:
        self.row = row
        self.rows = rows or []
        self.count_value = count
        self.updates: list[dict] = []
        self.queries: list[dict] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> Any:
        return self.row

    async def find_first(self, where: dict, include: dict | None = None) -> Any:
        return self.row

    async def find_many(self, where: dict | None = None, **_: Any) -> list:
        self.queries.append(where or {})
        return list(self.rows)

    async def count(self, where: dict | None = None, **_: Any) -> int:
        return self.count_value

    async def create(self, data: dict, include: dict | None = None) -> Any:
        return _task(**{key: value for key, value in data.items() if key != "id"})

    async def update(self, where: dict, data: dict, include: dict | None = None) -> Any:
        self.updates.append(data)
        merged = {key: value for key, value in data.items() if not isinstance(value, dict)}
        return _task(**{**vars(self.row), **merged}) if self.row else _task(**merged)


class _UserDelegate(_Delegate):
    async def find_unique(self, where: dict, include: dict | None = None) -> Any:
        return PEOPLE.get(where.get("id"))


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
        return self

    def listing(self, *rows: Any) -> "_Board":
        self.assignedtask.rows = list(rows)
        return self

    def as_(self, role: str, user_id: str | None = None) -> "_Board":
        _CURRENT["user"] = PEOPLE[user_id] if user_id else PEOPLE[_ID_FOR_ROLE[role]]
        return self

    def _call(self, method: str, path: str, body: dict | None = None) -> httpx.Response:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://tasks.test") as c:
                return await c.request(method, f"/api/tasks{path}", json=body)

        return asyncio.run(run())

    def get(self, path: str) -> httpx.Response:
        return self._call("GET", path)

    def patch(self, path: str, body: dict) -> httpx.Response:
        return self._call("PATCH", path, body)


@pytest.fixture
def board(monkeypatch: pytest.MonkeyPatch):
    b = _Board(monkeypatch)
    yield b
    _CURRENT["user"] = None



def test_the_status_sets_say_what_each_one_is_for() -> None:
    assert SUBMITTED in TASK_STATUSES
    assert SUBMITTED not in LIVE_STATUSES
    assert SUBMITTED in REVIEW_STATUSES
    assert SUBMITTED in OUTSTANDING_STATUSES
    assert LIVE_STATUSES == {"OPEN", "IN_PROGRESS"}
    assert OUTSTANDING_STATUSES == {"OPEN", "IN_PROGRESS", SUBMITTED}
    assert not {"DONE", "CANCELLED"} & OUTSTANDING_STATUSES


def test_every_status_has_a_label_and_the_count_keys_cover_every_status() -> None:
    assert set(STATUS_LABELS) == TASK_STATUSES
    assert set(STATUS_COUNT_KEYS) == TASK_STATUSES
    assert STATUS_LABELS[SUBMITTED] == "Under review"


def test_an_assignees_mark_done_lands_in_review_and_the_response_says_so(board: _Board) -> None:
    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    response = mine.patch("/t1", {"status": "DONE"})

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == SUBMITTED
    body = response.json()
    assert body["status"] == SUBMITTED
    assert body["statusLabel"] == "Under review"
    assert body["isAwaitingReview"] is True


def test_a_submitted_task_is_still_on_the_assignees_list(board: _Board) -> None:
    response = board.holding(status=SUBMITTED).as_("RESEARCHER").get("/t1")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["isOutstanding"] is True
    assert body["statusLabel"] == "Under review"
    assert body["isAwaitingReview"] is True


def test_work_submitted_on_time_and_reviewed_late_is_not_overdue(board: _Board) -> None:
    overdue_date = datetime.now(UTC) - timedelta(days=3)

    submitted = board.holding(status=SUBMITTED, dueAt=overdue_date).as_("RESEARCHER").get("/t1")
    assert submitted.status_code == 200, submitted.text
    assert submitted.json()["isOverdue"] is False

    still_open = board.holding(status="OPEN", dueAt=overdue_date).as_("RESEARCHER").get("/t1")
    assert still_open.json()["isOverdue"] is True

    assert is_overdue(_task(status=SUBMITTED, dueAt=overdue_date)) is False


@pytest.mark.parametrize("role", MANAGER_ROLES)
def test_an_admin_approves_a_submitted_task(board: _Board, role: str) -> None:
    response = board.holding(status=SUBMITTED).as_(role).patch("/t1", {"status": "DONE"})

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "DONE"
    assert response.json()["statusLabel"] == "Approved"


def test_a_demoted_task_creator_may_not_approve_submitted_work(board: _Board) -> None:
    demoted = _user("PROFESSOR", "u_demoted")
    PEOPLE[demoted.id] = demoted

    response = (
        board.holding(status=SUBMITTED, createdById=demoted.id)
        .as_("PROFESSOR", demoted.id)
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 403, response.text
    assert board.assignedtask.updates == []


def test_approving_does_not_restamp_the_moment_the_work_was_finished(board: _Board) -> None:
    submitted_at = datetime(2026, 9, 10, tzinfo=UTC)

    response = (
        board.holding(status=SUBMITTED, completedAt=submitted_at)
        .as_("ADMIN")
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    written = board.assignedtask.updates[0]
    assert written["status"] == "DONE"
    assert "completedAt" not in written


@pytest.mark.parametrize("back_to", ["OPEN", "IN_PROGRESS"])
def test_a_manager_may_send_submitted_work_back(board: _Board, back_to: str) -> None:
    response = (
        board.holding(status=SUBMITTED, completedAt=datetime(2026, 9, 10, tzinfo=UTC))
        .as_("ADMIN")
        .patch("/t1", {"status": back_to})
    )

    assert response.status_code == 200, response.text
    written = board.assignedtask.updates[0]
    assert written["status"] == back_to
    assert written["completedAt"] is None


def test_an_assignee_may_withdraw_their_own_submission(board: _Board) -> None:
    response = (
        board.holding(status=SUBMITTED, assigneeId=WORKER_ID)
        .as_("RESEARCHER")
        .patch("/t1", {"status": "IN_PROGRESS"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "IN_PROGRESS"
    assert board.assignedtask.updates[0]["completedAt"] is None


@pytest.mark.parametrize("attempt", ["OPEN", "IN_PROGRESS", "DONE", SUBMITTED])
def test_an_assignee_cannot_touch_the_status_of_an_approved_task(
    board: _Board, attempt: str
) -> None:
    response = (
        board.holding(status="DONE", assigneeId=WORKER_ID, completedAt=NOW)
        .as_("RESEARCHER")
        .patch("/t1", {"status": attempt})
    )

    assert response.status_code == 403, response.text
    refusal = "Only the task creator or an admin can reopen an approved task"
    assert response.json()["detail"] == refusal
    assert board.assignedtask.updates == []


def test_a_manager_may_still_reopen_an_approved_task(board: _Board) -> None:
    response = (
        board.holding(status="DONE", completedAt=NOW).as_("ADMIN").patch("/t1", {"status": "OPEN"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "OPEN"
    assert board.assignedtask.updates[0]["completedAt"] is None


def test_an_admin_finishing_their_own_self_assigned_task_needs_no_second_signature(
    board: _Board,
) -> None:
    response = (
        board.holding(assigneeId=ADMIN_ID, createdById=ADMIN_ID)
        .as_("ADMIN")
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "DONE"


def test_submitting_a_quota_task_fills_the_reported_figure(board: _Board) -> None:
    response = (
        board.holding(assigneeId=WORKER_ID, targetCount=10, progressCount=0)
        .as_("RESEARCHER")
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    written = board.assignedtask.updates[0]
    assert written["status"] == SUBMITTED
    assert written["progressCount"] == 10
    assert written["completedAt"] is not None


def test_submitted_is_an_accepted_status_value_on_the_wire(board: _Board) -> None:
    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    accepted = mine.patch("/t1", {"status": SUBMITTED})
    assert accepted.status_code == 200, accepted.text
    assert board.assignedtask.updates[0]["status"] == SUBMITTED

    refused = board.holding().as_("ADMIN").patch("/t1", {"status": "FINISHED"})
    assert refused.status_code == 422, refused.text
    assert SUBMITTED in refused.json()["detail"]



def test_the_assignees_list_can_ask_for_all_three_outstanding_statuses_at_once(
    board: _Board,
) -> None:
    response = board.as_("RESEARCHER")._call("GET", "?view=assigned&outstanding=true")

    assert response.status_code == 200, response.text
    asked = board.assignedtask.queries[-1]
    assert asked["status"] == {"in": ["IN_PROGRESS", "OPEN", "SUBMITTED"]}
    assert asked["assigneeId"] == WORKER_ID


def test_asking_for_one_status_and_for_outstanding_at_once_is_refused(board: _Board) -> None:
    response = board.as_("RESEARCHER")._call("GET", "?view=assigned&status=DONE&outstanding=true")

    assert response.status_code == 422, response.text
    assert response.json()["detail"] == "Pass either status or outstanding=true, not both"


def test_the_summary_route_is_not_swallowed_by_the_task_id_route(board: _Board) -> None:
    response = board.as_("RESEARCHER").get("/summary")

    assert response.status_code == 200, response.text
    assert "remainingCount" in response.json()


def test_the_summary_counts_remaining_review_and_approved_separately(board: _Board) -> None:
    board.listing(
        _task(id="a", status="OPEN"),
        _task(id="b", status="IN_PROGRESS"),
        _task(id="c", status=SUBMITTED),
        _task(id="d", status="DONE"),
        _task(id="e", status="CANCELLED"),
    )

    body = board.as_("RESEARCHER").get("/summary").json()

    assert body["taskCount"] == 5
    assert body["remainingCount"] == 2
    assert body["awaitingReviewCount"] == 1
    assert body["outstandingCount"] == 3
    assert body["approvedCount"] == 1
    assert body["cancelledCount"] == 1
    assert set(body["statusCounts"]) == TASK_STATUSES


def test_the_summary_bar_ignores_withdrawn_work_and_credits_submitted_work(board: _Board) -> None:
    board.listing(
        _task(id="a", status=SUBMITTED),
        _task(id="b", status="OPEN"),
        _task(id="c", status="CANCELLED"),
    )

    body = board.as_("RESEARCHER").get("/summary").json()

    assert body["percentComplete"] == 50
    assert body["measuredCount"] == 0


def test_the_summary_separates_overdue_from_next_due(board: _Board) -> None:
    past = datetime.now(UTC) - timedelta(days=2)
    soon = datetime.now(UTC) + timedelta(hours=5)
    later = datetime.now(UTC) + timedelta(days=30)
    board.listing(
        _task(id="a", status="OPEN", dueAt=past),
        _task(id="b", status="OPEN", dueAt=soon),
        _task(id="c", status="OPEN", dueAt=later),
    )

    body = board.as_("RESEARCHER").get("/summary").json()

    assert body["overdueCount"] == 1
    assert body["dueSoonCount"] == 1
    assert body["nextDueAt"].startswith(soon.strftime("%Y-%m-%d"))


def test_the_summary_is_always_about_the_caller(board: _Board) -> None:
    body = board.as_("RESEARCHER").get("/summary").json()
    assert body["assignee"]["id"] == WORKER_ID

    response = board.as_("RESEARCHER")._call("GET", f"/summary?assigneeId={ADMIN_ID}")
    assert response.json()["assignee"]["id"] == WORKER_ID



def _run(coro):
    return asyncio.run(coro)


def test_a_sections_task_for_named_artisans_targets_the_pairs() -> None:
    task = _task(sectionIds=["s1", "s2"], artisanIds=["a1", "a2", "a3"])
    assert _section_target(task) == 6
    assert _derived_target(task) == 6


def test_a_sections_task_for_every_artisan_targets_the_workshop_roster() -> None:
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId="w1")

    assert _section_target(task, roster=12) == 24
    assert _derived_target(task, roster=12) == 24
    assert _section_target(task, roster=None) == 2
    assert _section_target(task, roster=0) == 2


def test_a_mixed_task_with_an_open_ended_record_half_still_has_no_overall_target() -> None:
    task = _task(recordTypes=["product"], sectionIds=["s1"], targetCount=None, workshopId="w1")
    assert _derived_target(task, roster=5) is None

    quota = _task(recordTypes=["product"], sectionIds=["s1"], targetCount=4, workshopId="w1")
    assert _derived_target(quota, roster=5) == 9


def test_an_all_artisan_section_task_counts_pairs_and_climbs_per_artisan(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId="w1")

    monkeypatch.setattr(tasks_module, "_count_sections", lambda *a: _done((6, 2, 0)))
    monkeypatch.setattr(tasks_module, "_count_workshop_artisans", lambda *a: _done(12))

    derived = _run(derive_progress([task]))["t1"]

    assert derived["derivedCount"] == 6
    assert derived["derivedTarget"] == 24
    assert derived["derivedBreakdown"]["sections"] == 6
    assert derived["derivedArtisanCount"] == 12


def test_without_a_workshop_an_all_artisan_section_task_keeps_the_old_unit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId=None)
    monkeypatch.setattr(tasks_module, "_count_sections", lambda *a: _done((6, 2, 0)))

    derived = _run(derive_progress([task]))["t1"]

    assert derived["derivedCount"] == 2
    assert derived["derivedTarget"] == 2
    assert derived["derivedArtisanCount"] is None


def test_answers_that_name_no_artisan_are_reported_rather_than_silently_lost(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId="w1")
    monkeypatch.setattr(tasks_module, "_count_sections", lambda *a: _done((0, 2, 2)))
    monkeypatch.setattr(tasks_module, "_count_workshop_artisans", lambda *a: _done(12))

    derived = _run(derive_progress([task]))["t1"]

    assert derived["derivedCount"] == 0
    assert derived["derivedBreakdown"]["unlinkedSections"] == 2


def test_a_task_with_no_measurable_scope_never_derives_a_zero(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    scopeless = _task(id="t1", recordTypes=[], sectionIds=[])
    measurable = _task(id="t2", recordTypes=["tool"], targetCount=10, workshopId="w1")
    monkeypatch.setattr(tasks_module, "_count_records", lambda *a: _done(4))

    alone = _run(derive_progress([scopeless]))
    together = _run(derive_progress([scopeless, measurable]))

    assert alone.get("t1", {}).get("derivedCount") is None
    assert together["t1"]["derivedCount"] is None
    assert together["t1"]["derivedTarget"] is None
    assert together["t2"]["derivedCount"] == 4
    assert together["t2"]["derivedTarget"] == 10


def test_a_record_type_task_advances_without_anybody_typing_a_number(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    task = _task(recordTypes=["tool"], targetCount=10, workshopId="w1", progressCount=0)
    monkeypatch.setattr(tasks_module, "_count_records", lambda *a: _done(4))

    numbers = _run(derive_progress([task]))["t1"]
    effective = effective_progress(task, numbers)

    assert numbers["derivedCount"] == 4
    assert effective["percent"] == 40
    assert effective["source"] == "derived"
    assert effective["label"] == "4 of 10 tools recorded"


def test_the_progress_precedence_is_pinned_end_to_end() -> None:
    measured = {"derivedCount": 3, "derivedTarget": 12, "derivedBreakdown": {"tool": 3}}
    nothing: dict[str, Any] = {}

    approved = effective_progress(_task(status="DONE"), {"derivedCount": 0, "derivedTarget": 9})
    assert (approved["percent"], approved["source"]) == (100, "status")

    cancelled = effective_progress(_task(status="CANCELLED"), measured)
    assert (cancelled["percent"], cancelled["source"]) == (0, "status")

    both = effective_progress(_task(targetCount=10, progressCount=10), measured)
    assert (both["percent"], both["source"]) == (25, "derived")

    reported = effective_progress(_task(targetCount=10, progressCount=4), nothing)
    assert (reported["percent"], reported["source"]) == (40, "reported")
    assert reported["label"] == "4 of 10 reported"

    claimed = effective_progress(_task(status=SUBMITTED), nothing)
    assert (claimed["percent"], claimed["source"]) == (100, "status")

    unmeasurable = effective_progress(_task(status="IN_PROGRESS"), nothing)
    assert unmeasurable["percent"] is None
    assert unmeasurable["source"] is None


def test_an_open_ended_derived_count_reports_the_number_without_a_percentage() -> None:
    task = _task(recordTypes=["artisan"], targetCount=None, workshopId="w1")
    effective = effective_progress(
        task, {"derivedCount": 7, "derivedTarget": None, "derivedBreakdown": {"artisan": 7}}
    )

    assert effective["percent"] is None
    assert effective["source"] == "derived"
    assert effective["label"] == "7 artisans recorded"


def _done(value: Any):
    async def coro() -> Any:
        return value

    return coro()
