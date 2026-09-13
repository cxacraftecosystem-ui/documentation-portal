"""WHO MAY HAND OUT WORK, AND WHO MAY DECLARE IT FINISHED — driven over HTTP against the routes.

TWO RULINGS, ONE FILE, because they are two halves of one owner request: "allot my tasks to the
master admin account itself, and the admins and master admins should have the option to manually
override it and mark an assigned task as done as well."

PART 1 — SELF-ASSIGNMENT. ``assert_assignable`` (app/api/routes/tasks.py:125) refused a
self-assignment for everyone, master admin included. Because both creation routes are
``Depends(require_admin)``, the only accounts that could ever reach that clause on a create were
ADMIN and MASTER_ADMIN — so the rule protected nobody and only stopped an administrator recording
their own workload on the board they hold everybody else to. It is now lifted for those two roles
and kept for everybody else, and "everybody else" is reachable: a demoted creator still manages the
tasks they created and can still PATCH an ``assigneeId``.

PART 2 — THE ADMIN OVERRIDE. This half asserts something that was ALREADY TRUE and was never
exercised, which is the more dangerous kind of untested: ``update_task`` treats an admin as a
manager (tasks.py:1188) and a manager may write any field, ``status`` included. The whole of the
override feature is therefore front-end work, and these tests are what stops somebody "tidying" the
manager test later and silently breaking a UI control with no server test behind it. They also pin
the two consequences the confirmation copy on that control has to state: marking a quota task DONE
FILLS ``progressCount`` to ``targetCount``, and reopening clears ``completedAt``.

NOTHING HERE TOUCHES A DATABASE. ``db`` is replaced by delegates that answer with canned rows and
record every write, so "allowed" means the row was actually written rather than merely "not a 403".
Same shape, and the same argument, as ``tests/test_review_edit_authority.py`` beside it.
"""

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


# The cast of this file, by id, so a route that looks a user up gets the same row the caller is.
PEOPLE: dict[str, SimpleNamespace] = {
    MASTER_ID: _user("MASTER_ADMIN", MASTER_ID),
    ADMIN_ID: _user("ADMIN", ADMIN_ID),
    WORKER_ID: _user("RESEARCHER", WORKER_ID),
}


def _task(**overrides: Any) -> SimpleNamespace:
    """One AssignedTask row with NO scope, deliberately.

    An empty ``recordTypes``/``sectionIds``/``artisanIds`` means ``derive_progress`` finds no jobs
    and ``load_scope_lookups`` issues no queries, so a status test exercises the authority rules
    and nothing else. ``targetCount`` is still honoured by the quota-fill branch, which is the one
    derivation-free number these tests do care about.
    """
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
    """One Prisma model. Answers with ``row`` and remembers every write."""

    def __init__(self, row: Any = None) -> None:
        self.row = row
        self.creates: list[dict] = []
        self.updates: list[dict] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> Any:
        return self.row

    async def find_first(self, where: dict, include: dict | None = None) -> Any:
        return self.row

    async def find_many(self, where: dict | None = None, **_: Any) -> list:
        return []

    async def count(self, where: dict | None = None, **_: Any) -> int:
        return 0

    async def create(self, data: dict, include: dict | None = None) -> Any:
        self.creates.append(data)
        return _task(**{key: value for key, value in data.items() if key != "id"})

    async def update(self, where: dict, data: dict, include: dict | None = None) -> Any:
        self.updates.append(data)
        merged = {key: value for key, value in data.items() if not isinstance(value, dict)}
        return _task(**{**vars(self.row), **merged}) if self.row else _task(**merged)


class _UserDelegate(_Delegate):
    """The user table, which these routes look up BY ID — ``assert_assignable`` resolves the
    assignee, and answering with one canned row would make every self-assignment test pass."""

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


# A role with nobody in PEOPLE gets an id of its own, so `as_("PROFESSOR")` still works.
_ID_FOR_ROLE = {
    "MASTER_ADMIN": MASTER_ID,
    "ADMIN": ADMIN_ID,
    "RESEARCHER": WORKER_ID,
}
for _role in ALL_ROLES:
    if _role not in _ID_FOR_ROLE:
        _ID_FOR_ROLE[_role] = f"u_{_role.lower()}"
        PEOPLE[_ID_FOR_ROLE[_role]] = _user(_role, _ID_FOR_ROLE[_role])

# A SECOND person at every rank, never the caller. The tier rule and the self rule are now two
# different rules, so a test of one must not accidentally be a test of the other: assigning to
# `_ID_FOR_ROLE["ADMIN"]` while signed in as the admin IS the self case, and reads as the tier rule
# having been loosened when it has not been touched.
_PEER_ID_FOR_ROLE = {role: f"u_peer_{role.lower()}" for role in ALL_ROLES}
for _role, _peer_id in _PEER_ID_FOR_ROLE.items():
    PEOPLE[_peer_id] = _user(_role, _peer_id)


@pytest.fixture
def board(monkeypatch: pytest.MonkeyPatch):
    b = _Board(monkeypatch)
    yield b
    _CURRENT["user"] = None


# =================================================================================================
# PART 1 — self-assignment
# =================================================================================================


@pytest.mark.parametrize("role", ADMIN_ROLES)
def test_an_admin_may_assign_a_task_to_themselves(board: _Board, role: str) -> None:
    """THE REQUEST. The master admin allotting work to the master admin account is now an ordinary
    create, and "allowed" has to mean the row was written with them as the assignee — a 200 over an
    empty `creates` list would be the feature reported as working while doing nothing."""
    me = _ID_FOR_ROLE[role]

    response = board.as_(role).post("", {"title": "Draft the Bagru report", "assigneeId": me})

    assert response.status_code == 201, response.text
    written = board.assignedtask.creates[0]
    assert written["assigneeId"] == me
    # Assigned BY and assigned TO are now the same person, on purpose — the board renders it.
    assert written["createdById"] == me


@pytest.mark.parametrize("role", ADMIN_ROLES)
def test_an_admin_may_put_themselves_into_a_batch_alongside_the_people_they_assigned(
    board: _Board, role: str
) -> None:
    """/tasks/batch validates every assignee up front so the batch is all-or-nothing. If the
    self-assignment rule had been lifted only on the single-assignee route, "give this to the three
    of us" would still have failed — and failed AFTER the admin filled the whole builder in."""
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
    """The load-bearing half of the argument for lifting the ban. Every tier below ADMIN is stopped
    by `require_admin` BEFORE `assert_assignable` is ever called, so the self-assignment clause was
    never protecting them from anything and widening it cannot have widened anything for them."""
    response = board.as_(role).post("", {"title": "Mine", "assigneeId": _ID_FOR_ROLE[role]})

    assert response.status_code == 403, response.text
    assert response.json()["detail"] == "Admin access required"
    assert board.assignedtask.creates == []


def test_a_demoted_creator_still_may_not_move_their_old_task_onto_themselves(board: _Board) -> None:
    """THE 422 IS KEPT AND IT IS REACHABLE. `update_task`'s manager test is `createdById == me OR
    is_admin(me)`, so somebody who was an admin when they created a task still manages it after
    being demoted — and PATCH {"assigneeId": me} runs through the same helper. A researcher must
    not be able to hand themselves work now that they are no longer entitled to hand out any."""
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
    """Everything except the self case must behave exactly as it did. An ADMIN may assign only
    strictly below ADMIN; the master admin may assign to anyone. Lifting the self-ban must not have
    let an admin hand work sideways to another admin."""
    target = _PEER_ID_FOR_ROLE[assignee_role]

    as_admin = board.as_("ADMIN").post("", {"title": "Survey", "assigneeId": target})
    as_master = board.as_("MASTER_ADMIN").post("", {"title": "Survey", "assigneeId": target})

    admin_allowed = deps.ROLE_RANK[assignee_role] < deps.ROLE_RANK["ADMIN"]
    assert (as_admin.status_code == 201) is admin_allowed, (assignee_role, as_admin.text)
    if not admin_allowed:
        assert as_admin.json()["detail"] == "You can only assign tasks to users below your own tier"
    # The master admin could always assign to anyone but themselves, and it is exactly that one
    # exclusion this change removed — so every rank, another master admin included, stays
    # assignable.
    assert as_master.status_code == 201, (assignee_role, as_master.text)


def test_an_unknown_assignee_is_still_a_404_and_not_a_tier_refusal(board: _Board) -> None:
    """The 404 clause runs BEFORE the self test and must keep doing so: telling an admin they may
    not assign to a user who does not exist would send them looking at the role ladder."""
    response = board.as_("ADMIN").post("", {"title": "Survey", "assigneeId": "u_ghost"})

    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Assignee not found"


# =================================================================================================
# PART 2 — the admin override, which the server already permitted
# =================================================================================================


@pytest.mark.parametrize("role", ADMIN_ROLES)
@pytest.mark.parametrize("from_status", ["OPEN", "IN_PROGRESS"])
def test_an_admin_may_mark_another_persons_task_done(
    board: _Board, role: str, from_status: str
) -> None:
    """THE PREMISE OF THE WHOLE FRONT-END CHANGE, asserted so it cannot quietly stop being true.
    No backend change was needed for the override: an admin is a manager on any task and a manager
    may write `status`. The task below was created by SOMEBODY ELSE and assigned to a third person,
    so neither "I made it" nor "it is mine" is doing the work here — only the admin rank is."""
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
    """The override has to be REVERSIBLE, or an admin who presses it by accident has permanently
    told the repository a piece of fieldwork happened. Both live statuses are offered because
    "never started" and "half done" are different answers and the board must not force one."""
    response = (
        board.holding(status="DONE", completedAt=datetime(2026, 9, 10, tzinfo=UTC))
        .as_(role)
        .patch("/t1", {"status": back_to})
    )

    assert response.status_code == 200, response.text
    written = board.assignedtask.updates[0]
    assert written["status"] == back_to
    # Left standing, the stamp would say a reopened task was finished on the 10th.
    assert written["completedAt"] is None


def test_marking_a_quota_task_done_fills_the_reported_figure_to_the_target(board: _Board) -> None:
    """WHY THE OVERRIDE CONTROL MUST SAY THIS BEFORE IT FIRES. `update_task` fills `progressCount`
    to `targetCount` on DONE so the rollups stop reading "DONE — 0 of 10". Correct for an assignee
    who genuinely finished; for an admin overriding somebody else it silently converts "0 of 10
    reported" into "10 of 10 reported" on the one board whose entire purpose is comparing what was
    reported against what the repository can find. The confirmation copy on the board states the
    two numbers, and this test is where that sentence's claim is verified."""
    response = (
        board.holding(targetCount=10, progressCount=0).as_("ADMIN").patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["progressCount"] == 10


def test_reopening_does_not_put_the_reported_figure_back(board: _Board) -> None:
    """The fill is NOT undone by the reverse move — there is no column recording what the figure
    was before the override, so the server has nothing to restore. Pinned here because it is the
    asymmetry a reader of the override control will assume away: "reversible" means the status and
    the completion stamp, and it does not mean the quota."""
    response = (
        board.holding(status="DONE", targetCount=10, progressCount=10)
        .as_("ADMIN")
        .patch("/t1", {"status": "OPEN"})
    )

    assert response.status_code == 200, response.text
    assert "progressCount" not in board.assignedtask.updates[0]


def test_an_assignee_may_finish_their_own_task_but_still_may_not_cancel_it(board: _Board) -> None:
    """The assignee's own half, AS REDRAWN BY THE REVIEW STATE (2026-09-14).

    This test used to assert that an assignee's "Mark done" wrote DONE, and that was the whole of
    the old rule: finishing was theirs to declare outright. It is not any more — "the admin/master
    admin need to approve the work as done" — so the same request now writes SUBMITTED, and the
    assertion is flipped here rather than deleted because the ROUTE that must keep working is
    identical. The half that has not moved is the one below it: CANCELLED is a withdrawal of the
    assignment and still belongs to whoever handed it out.

    See ``tests/test_task_review.py`` for the rest of the review rules; this file keeps the pairing
    with CANCELLED because the two refusals share one branch.
    """
    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    finished = mine.patch("/t1", {"status": "DONE"})
    assert finished.status_code == 200, finished.text
    assert board.assignedtask.updates[0]["status"] == "SUBMITTED"
    # And the response says so, so a client that sent "DONE" is never left believing it landed.
    assert finished.json()["status"] == "SUBMITTED"

    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    cancelled = mine.patch("/t1", {"status": "CANCELLED"})
    assert cancelled.status_code == 403, cancelled.text
    assert cancelled.json()["detail"] == "Only the task creator or an admin can cancel a task"
    assert board.assignedtask.updates == []


def test_a_bystander_cannot_mark_somebody_elses_task_done(board: _Board) -> None:
    """The override is an ADMIN power, not a "logged in" power. A researcher who is neither the
    assignee nor the creator is refused, which is what stops the front-end gate being the only
    thing between a colleague and somebody else's completion record."""
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
    """THE HONEST LIMIT OF THE FEATURE, pinned so the UI cannot claim otherwise — and NARROWED by
    the review state, which is worth stating precisely because it is easy to overstate.

    The row still records `status` and `completedAt` and nothing else. There is no `approvedById`,
    so "who approved this" remains unanswerable and any "overridden by" readout on the board is a
    SESSION-LOCAL note that must say so.

    WHAT THE REVIEW STATE DID ADD is one bit, and only one: the two requests no longer write the
    same VALUE. An admin's press lands on DONE; the assignee's lands on SUBMITTED, because DONE is
    not theirs to write. So a DONE row on a task the assignee never submitted did come from a
    manager — which is an inference from the value, not a recorded author, and it says nothing about
    WHICH manager. The UI may say "approved"; it still may not say by whom.
    """
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
    # Neither write names an author. The columns are identical; only the verdict differs.
    assert "approvedById" not in by_admin and "approvedById" not in by_assignee
