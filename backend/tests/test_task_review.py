"""A RESEARCHER'S "DONE" IS A CLAIM; AN ADMIN'S "DONE" IS A DECISION — and measurable work counts
itself.

THE OWNER'S REQUEST, in their words: "the admin/master admin need to approve the work as done when
the researcher has marked it as done for it to not pop up next time, tell them that it is currently
under review, for the measurable things such as the sections covered for all artisans, or
questionnaire sections should progress automatically as they record for more and more artisans".

Two halves, and this file is organised as two halves.

PART 1 — THE REVIEW STATE. ``SUBMITTED`` sits between IN_PROGRESS and DONE. An assignee's "Mark
done" lands there; only a manager (``update_task``'s ``createdById == me OR is_admin(me)`` test)
moves it to DONE. The traps pinned here are the ones a reading of the route does not make obvious:

  - ``SUBMITTED`` IS NOT IN ``LIVE_STATUSES`` and that is deliberate. ``LIVE_STATUSES`` is what
    ``is_overdue`` consults. Work handed in on the 1st and approved on the 12th must not flip to
    overdue on the 8th — that would publish the REVIEWER's backlog as the RESEARCHER's lateness, on
    the one board whose entire purpose is saying who is behind.
  - ``SUBMITTED`` IS in ``OUTSTANDING_STATUSES``, which is what keeps it on the assignee's screen.
    The owner's "for it to not pop up next time" is a statement about what happens AFTER approval;
    before approval it must go on popping up, wearing a different pill.
  - AN ASSIGNEE MAY NOT REOPEN AN APPROVED TASK. Harmless before this change (DONE was their own
    declaration, so taking it back was correcting themselves); now it would erase somebody else's
    decision, and the approval step would be decorative.
  - ``completedAt`` IS STAMPED ON SUBMISSION AND NOT RE-STAMPED ON APPROVAL, so it keeps meaning
    "when the work was finished" and stays comparable with ``dueAt``. No column was added; the
    price, stated rather than discovered later, is that the moment of approval is not recorded.

PART 2 — AUTOMATIC PROGRESS. ``derivedCount``/``derivedTarget`` already existed and already counted
records and questionnaire answers. Two things were wrong with them for the owner's use:

  - A "sections for ALL artisans" task counted DISTINCT SECTIONS against a target of
    ``len(sectionIds)``, so finishing both sections for ONE artisan out of forty read 100% and every
    further artisan moved the bar by zero — the precise complaint. It now counts (artisan, section)
    PAIRS against ``sections x the workshop's roster``.
  - A task with NO measurable scope reported ``derivedCount: 0`` or ``null`` depending on whether
    some OTHER task on the same page happened to have a scope. Same row, two answers, decided by
    pagination — and the 0 reads on screen as "this person produced nothing".

NOTHING HERE TOUCHES A DATABASE. ``db`` is replaced by delegates answering with canned rows and
recording every write, so "allowed" means the row was actually written rather than merely "not a
403" — the same shape, and the same argument, as ``tests/test_task_authority.py`` beside it.
"""

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
    """One AssignedTask row. Scopeless by default, so a status test exercises the authority rules
    and nothing else: an empty ``recordTypes``/``sectionIds`` means ``derive_progress`` finds no job
    and ``load_scope_lookups`` issues no query."""
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
    """One Prisma model. Answers with ``row`` (and ``rows`` for list reads) and remembers writes."""

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


# =================================================================================================
# PART 1 — the review state
# =================================================================================================


def test_the_status_sets_say_what_each_one_is_for() -> None:
    """THE LOAD-BEARING SET MEMBERSHIPS, asserted rather than left to a reader of three constants.

    ``LIVE_STATUSES`` answers "does the assignee still owe this" and is what ``is_overdue`` reads;
    ``OUTSTANDING_STATUSES`` answers "is this still on their screen". SUBMITTED belongs to the
    second and not the first, and the whole overdue argument rests on exactly that.
    """
    assert SUBMITTED in TASK_STATUSES
    assert SUBMITTED not in LIVE_STATUSES
    assert SUBMITTED in REVIEW_STATUSES
    assert SUBMITTED in OUTSTANDING_STATUSES
    assert LIVE_STATUSES == {"OPEN", "IN_PROGRESS"}
    assert OUTSTANDING_STATUSES == {"OPEN", "IN_PROGRESS", SUBMITTED}
    # DONE and CANCELLED are terminal: neither is outstanding, neither is live.
    assert not {"DONE", "CANCELLED"} & OUTSTANDING_STATUSES


def test_every_status_has_a_label_and_the_count_keys_cover_every_status() -> None:
    """Three hand-written clients read this payload and none is generated from it. A status with no
    entry in STATUS_LABELS ships a raw enum word into a pill; a status missing from
    STATUS_COUNT_KEYS ships a statusCounts map without that key, and a client reading
    ``counts.SUBMITTED`` faults on the happy path."""
    assert set(STATUS_LABELS) == TASK_STATUSES
    assert set(STATUS_COUNT_KEYS) == TASK_STATUSES
    assert STATUS_LABELS[SUBMITTED] == "Under review"


def test_an_assignees_mark_done_lands_in_review_and_the_response_says_so(board: _Board) -> None:
    """THE REQUEST ITSELF. The write must be SUBMITTED — a 200 with DONE in the row would be the
    feature reported as working while the approval step does nothing — and the response has to carry
    the corrected status, because the client that sent "DONE" would otherwise render "Done"."""
    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    response = mine.patch("/t1", {"status": "DONE"})

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == SUBMITTED
    body = response.json()
    assert body["status"] == SUBMITTED
    assert body["statusLabel"] == "Under review"
    assert body["isAwaitingReview"] is True


def test_a_submitted_task_is_still_on_the_assignees_list(board: _Board) -> None:
    """"For it to not pop up next time" is a statement about AFTER approval. Until then the row has
    to stay outstanding, or the researcher's screen empties the instant they press the button and
    the admin's agreement becomes invisible to the only person waiting on it."""
    response = board.holding(status=SUBMITTED).as_("RESEARCHER").get("/t1")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["isOutstanding"] is True
    # ...but visibly NOT "to do". Same list, different pill.
    assert body["statusLabel"] == "Under review"
    assert body["isAwaitingReview"] is True


def test_work_submitted_on_time_and_reviewed_late_is_not_overdue(board: _Board) -> None:
    """THE TRAP THIS WHOLE DESIGN TURNS ON. Put SUBMITTED into LIVE_STATUSES and a researcher who
    handed in early starts reading as LATE the moment an admin is slow — the board would be
    publishing the reviewer's backlog as the researcher's record. An OPEN task with the same past
    due date IS overdue, which is what makes this an assertion about the review state and not about
    the clock being broken."""
    overdue_date = datetime.now(UTC) - timedelta(days=3)

    submitted = board.holding(status=SUBMITTED, dueAt=overdue_date).as_("RESEARCHER").get("/t1")
    assert submitted.status_code == 200, submitted.text
    assert submitted.json()["isOverdue"] is False

    still_open = board.holding(status="OPEN", dueAt=overdue_date).as_("RESEARCHER").get("/t1")
    assert still_open.json()["isOverdue"] is True

    # And the pure predicate agrees, so the rule cannot be quietly re-implemented per screen.
    assert is_overdue(_task(status=SUBMITTED, dueAt=overdue_date)) is False


@pytest.mark.parametrize("role", MANAGER_ROLES)
def test_an_admin_approves_a_submitted_task(board: _Board, role: str) -> None:
    """The other end of the handshake. Approval is an ordinary PATCH down the manager branch — the
    override that landed on 2026-09-14 is the same code path and keeps working untouched."""
    response = board.holding(status=SUBMITTED).as_(role).patch("/t1", {"status": "DONE"})

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "DONE"
    assert response.json()["statusLabel"] == "Approved"


def test_the_task_creator_may_approve_even_without_being_an_admin(board: _Board) -> None:
    """``is_manager`` is ``createdById == me OR is_admin(me)``. A demoted admin still manages the
    tasks they handed out, and approving them is part of managing them — otherwise their assignees'
    submissions would sit in a queue with no reviewer."""
    demoted = _user("PROFESSOR", "u_demoted")
    PEOPLE[demoted.id] = demoted

    response = (
        board.holding(status=SUBMITTED, createdById=demoted.id)
        .as_("PROFESSOR", demoted.id)
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "DONE"


def test_approving_does_not_restamp_the_moment_the_work_was_finished(board: _Board) -> None:
    """WHY NO ``submittedAt`` COLUMN WAS ADDED. ``completedAt`` is written once, at submission, and
    approval leaves it alone — so ``completedAt`` still means "when the work was finished" and
    ``completedAt > dueAt`` stays a fact about the person who did the work. Re-stamping on approval
    would silently convert every slow review into a late researcher."""
    submitted_at = datetime(2026, 9, 10, tzinfo=UTC)

    response = (
        board.holding(status=SUBMITTED, completedAt=submitted_at)
        .as_("ADMIN")
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    written = board.assignedtask.updates[0]
    assert written["status"] == "DONE"
    # Not merely "the old value" — the key must be ABSENT, so the column is not touched at all.
    assert "completedAt" not in written


@pytest.mark.parametrize("back_to", ["OPEN", "IN_PROGRESS"])
def test_a_manager_may_send_submitted_work_back(board: _Board, back_to: str) -> None:
    """Reviewing means being able to say no. Both live statuses are offered because "start again"
    and "nearly there" are different answers, and the stamp has to go: a task sent back is not
    finished, and a ``completedAt`` left standing would say it was finished on the 10th."""
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
    """Submitting too early is an ordinary mistake and the assignee can take it back — SUBMITTED is
    their claim, and a claim is theirs to retract. Contrast the test below: DONE is NOT their claim,
    and that is the whole difference between the two."""
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
    """THE CLAUSE WITHOUT WHICH APPROVAL IS DECORATIVE. Before the review state, letting an assignee
    move their own DONE task was letting them correct their own declaration. Now DONE means somebody
    else agreed, and the same request would erase that decision — and, through the ``completedAt``
    branch, the record of when the work was finished with it. ``SUBMITTED`` is in the list because
    the coercion above would otherwise turn a re-press of "Mark done" on an approved task into a
    silent un-approval."""
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
    """The mirror of the refusal above: the power did not vanish, it moved. An admin who approved by
    accident must be able to undo it, or the accident is permanent."""
    response = (
        board.holding(status="DONE", completedAt=NOW).as_("ADMIN").patch("/t1", {"status": "OPEN"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "OPEN"
    assert board.assignedtask.updates[0]["completedAt"] is None


def test_an_admin_finishing_their_own_self_assigned_task_needs_no_second_signature(
    board: _Board,
) -> None:
    """Self-assignment landed on 2026-09-14 and this is where it meets the review state. The manager
    test runs BEFORE the assignee test, so an admin who assigned work to themselves goes down the
    manager branch and lands on DONE in one press. Requiring them to approve their own submission
    would be a second click by the same person with the same authority — theatre, not review."""
    response = (
        board.holding(assigneeId=ADMIN_ID, createdById=ADMIN_ID)
        .as_("ADMIN")
        .patch("/t1", {"status": "DONE"})
    )

    assert response.status_code == 200, response.text
    assert board.assignedtask.updates[0]["status"] == "DONE"


def test_submitting_a_quota_task_fills_the_reported_figure(board: _Board) -> None:
    """The quota fill moves from DONE to the SUBMISSION, because the submission is the claim and
    ``progressCount`` is the claim's number. An approver comparing "says 10" against "repository
    found 2" needs both figures present at the moment they are asked to decide, not after."""
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
    """A client may name the review state explicitly rather than relying on the DONE rewrite, and
    the 422 for a genuinely unknown status has to list it so the message stays a usable one."""
    mine = board.holding(assigneeId=WORKER_ID).as_("RESEARCHER")
    accepted = mine.patch("/t1", {"status": SUBMITTED})
    assert accepted.status_code == 200, accepted.text
    assert board.assignedtask.updates[0]["status"] == SUBMITTED

    refused = board.holding().as_("ADMIN").patch("/t1", {"status": "FINISHED"})
    assert refused.status_code == 422, refused.text
    assert SUBMITTED in refused.json()["detail"]


# =================================================================================================
# PART 1b — the assignee's summary card
# =================================================================================================


def test_the_assignees_list_can_ask_for_all_three_outstanding_statuses_at_once(
    board: _Board,
) -> None:
    """"Still on my screen" is THREE statuses now and ``status`` takes one. Without this filter a
    client has to fetch unfiltered and drop the finished rows itself — across a PAGED endpoint,
    which means a researcher with twenty-one tasks gets a list that silently omits outstanding work.
    Under-reporting outstanding work is the one failure this feature exists to prevent."""
    response = board.as_("RESEARCHER")._call("GET", "?view=assigned&outstanding=true")

    assert response.status_code == 200, response.text
    asked = board.assignedtask.queries[-1]
    assert asked["status"] == {"in": ["IN_PROGRESS", "OPEN", "SUBMITTED"]}
    # And still hard-pinned to the caller: a filter must never widen who the list is about.
    assert asked["assigneeId"] == WORKER_ID


def test_asking_for_one_status_and_for_outstanding_at_once_is_refused(board: _Board) -> None:
    """Either order of precedence would be a guess. ``status=DONE&outstanding=true`` is a caller
    with a bug in it, and answering quietly hides the bug behind a plausible list."""
    response = board.as_("RESEARCHER")._call("GET", "?view=assigned&status=DONE&outstanding=true")

    assert response.status_code == 422, response.text
    assert response.json()["detail"] == "Pass either status or outstanding=true, not both"


def test_the_summary_route_is_not_swallowed_by_the_task_id_route(board: _Board) -> None:
    """``/tasks/summary`` is declared BEFORE ``/tasks/{task_id}``. Declared after, FastAPI would
    match it as a task whose id is the word "summary" and the card would render whatever
    ``get_task`` said about a row that does not exist — a 404 if you were lucky, and the assignee's
    own task ``t1`` if the fixture happened to answer, which is worse."""
    response = board.as_("RESEARCHER").get("/summary")

    assert response.status_code == 200, response.text
    assert "remainingCount" in response.json()


def test_the_summary_counts_remaining_review_and_approved_separately(board: _Board) -> None:
    """THE CARD'S NUMBERS. "Remaining" is what is still on YOU and must exclude submitted work — a
    researcher who has handed everything in has nothing left to do, and telling them they still have
    three tasks is telling them to do them again. "Outstanding" is what they will still SEE."""
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
    # Every status key present even at zero, so a client may index the map without guarding.
    assert set(body["statusCounts"]) == TASK_STATUSES


def test_the_summary_bar_ignores_withdrawn_work_and_credits_submitted_work(board: _Board) -> None:
    """A CANCELLED row is not work: leaving it in the denominator would let an admin tidying up
    their own mis-assignments quietly drag somebody's bar down. A SUBMITTED row with nothing
    measurable counts as 100 — the assignee has done all they can do, and showing their handed-in
    task at 0% would be the system calling them a liar on no evidence."""
    board.listing(
        _task(id="a", status=SUBMITTED),
        _task(id="b", status="OPEN"),
        _task(id="c", status="CANCELLED"),
    )

    body = board.as_("RESEARCHER").get("/summary").json()

    # Two tasks in the denominator (100 + 0), not three.
    assert body["percentComplete"] == 50
    # Neither figure was measured from the repository, and the card is told so rather than
    # implying the bar was derived.
    assert body["measuredCount"] == 0


def test_the_summary_separates_overdue_from_next_due(board: _Board) -> None:
    """An already-overdue task is counted once, by ``overdueCount``, and kept out of ``nextDueAt``:
    a card whose "next due" is a date in the past reports the same emergency twice under two
    headings while hiding the genuine next deadline behind it."""
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
    """There is no ``assigneeId`` on this route. A researcher's own summary is not an accountability
    board, and adding a way to point it at somebody else would hand every user the rollup that
    ``GET /tasks/progress`` deliberately keeps behind ``require_admin``."""
    body = board.as_("RESEARCHER").get("/summary").json()
    assert body["assignee"]["id"] == WORKER_ID

    response = board.as_("RESEARCHER")._call("GET", f"/summary?assigneeId={ADMIN_ID}")
    assert response.json()["assignee"]["id"] == WORKER_ID


# =================================================================================================
# PART 2 — progress that counts itself
# =================================================================================================


def _run(coro):
    return asyncio.run(coro)


def test_a_sections_task_for_named_artisans_targets_the_pairs() -> None:
    """Unchanged behaviour, asserted first so the change below is visibly a REFINEMENT and not a
    rewrite: naming artisans has always produced an honest denominator."""
    task = _task(sectionIds=["s1", "s2"], artisanIds=["a1", "a2", "a3"])
    assert _section_target(task) == 6
    assert _derived_target(task) == 6


def test_a_sections_task_for_every_artisan_targets_the_workshop_roster() -> None:
    """THE OWNER'S COMPLAINT, AS A UNIT TEST. "Sections covered for all artisans should progress
    automatically as they record for more and more artisans." With ``artisanIds`` empty the target
    was ``len(sectionIds)`` — 2 — so a researcher who finished both sections for the FIRST artisan
    read 2 of 2, done, and artisans 2 through 12 moved the bar by nothing at all."""
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId="w1")

    assert _section_target(task, roster=12) == 24
    assert _derived_target(task, roster=12) == 24
    # And with no roster to divide by, the old reading stands rather than a percentage of nothing.
    assert _section_target(task, roster=None) == 2
    assert _section_target(task, roster=0) == 2


def test_a_mixed_task_with_an_open_ended_record_half_still_has_no_overall_target() -> None:
    """"Record products (as many as apply) + sections C and D" has an honest denominator for one
    half and none for the other. Adding them would present the section fraction as if it described
    the whole task, so the PERCENTAGE stays silent — the numbers behind it stay on
    ``derivedBreakdown``, which is where a UI should read them."""
    task = _task(recordTypes=["product"], sectionIds=["s1"], targetCount=None, workshopId="w1")
    assert _derived_target(task, roster=5) is None

    # Give the record half a quota and both halves are countable, so the sum is meaningful again.
    quota = _task(recordTypes=["product"], sectionIds=["s1"], targetCount=4, workshopId="w1")
    assert _derived_target(quota, roster=5) == 9


def test_an_all_artisan_section_task_counts_pairs_and_climbs_per_artisan(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """END TO END THROUGH ``derive_progress``: the numerator and the denominator must switch units
    TOGETHER. Counting pairs against a section target (or sections against a pair target) is not a
    smaller bug than the one being fixed — it is the same bug with the fraction inverted."""
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId="w1")

    # Two sections answered, for three of the workshop's twelve artisans = 6 pairs.
    monkeypatch.setattr(tasks_module, "_count_sections", lambda *a: _done((6, 2, 0)))
    monkeypatch.setattr(tasks_module, "_count_workshop_artisans", lambda *a: _done(12))

    derived = _run(derive_progress([task]))["t1"]

    assert derived["derivedCount"] == 6
    assert derived["derivedTarget"] == 24
    assert derived["derivedBreakdown"]["sections"] == 6
    # Echoed so a UI can say "6 of 24 (2 sections x 12 artisans)" rather than a bare number.
    assert derived["derivedArtisanCount"] == 12


def test_without_a_workshop_an_all_artisan_section_task_keeps_the_old_unit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A standing task with no workshop has no roster to divide by, and inventing one would be the
    "percentage of nothing" the original code was right to refuse. The old reading — distinct
    sections against the section count — is kept for exactly that shape."""
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId=None)
    monkeypatch.setattr(tasks_module, "_count_sections", lambda *a: _done((6, 2, 0)))

    derived = _run(derive_progress([task]))["t1"]

    assert derived["derivedCount"] == 2
    assert derived["derivedTarget"] == 2
    assert derived["derivedArtisanCount"] is None


def test_answers_that_name_no_artisan_are_reported_rather_than_silently_lost(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """An interview with no artisan attached cannot say any artisan's section is covered, so it
    cannot raise the pair count. Without this number the difference between "nobody has started" and
    "the interviews were never linked to anybody" is a stuck 0% with no explanation — the same
    shortfall the completion matrix reports as ``unassignedInterviews``."""
    task = _task(sectionIds=["s1", "s2"], artisanIds=[], workshopId="w1")
    monkeypatch.setattr(tasks_module, "_count_sections", lambda *a: _done((0, 2, 2)))
    monkeypatch.setattr(tasks_module, "_count_workshop_artisans", lambda *a: _done(12))

    derived = _run(derive_progress([task]))["t1"]

    assert derived["derivedCount"] == 0
    assert derived["derivedBreakdown"]["unlinkedSections"] == 2


def test_a_task_with_no_measurable_scope_never_derives_a_zero(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A REGRESSION THAT DEPENDED ON PAGINATION. "Food + collect TA details" has no recordTypes and
    no sectionIds, so it contributes no counting job. Alone on a page, ``derive_progress`` returned
    early and the row got ``derivedCount: null``; with ANY scoped task beside it the final loop ran
    and the same row got ``derivedCount: 0``. Same task, two answers, decided by what else happened
    to be on the page — and the 0 renders as "this person has produced nothing", which is a specific
    accusation about work the database cannot measure at all."""
    scopeless = _task(id="t1", recordTypes=[], sectionIds=[])
    measurable = _task(id="t2", recordTypes=["tool"], targetCount=10, workshopId="w1")
    monkeypatch.setattr(tasks_module, "_count_records", lambda *a: _done(4))

    alone = _run(derive_progress([scopeless]))
    together = _run(derive_progress([scopeless, measurable]))

    assert alone.get("t1", {}).get("derivedCount") is None
    assert together["t1"]["derivedCount"] is None
    assert together["t1"]["derivedTarget"] is None
    # The measurable neighbour is unaffected — this is about the unmeasurable row, not about
    # switching derivation off.
    assert together["t2"]["derivedCount"] == 4
    assert together["t2"]["derivedTarget"] == 10


def test_a_record_type_task_advances_without_anybody_typing_a_number(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """"Record 10 tools" already derived correctly — what was missing was any wire field telling a
    client to DRAW that number instead of the self-reported one. ``effectivePercent`` is it, and
    ``progressSource`` says where it came from so the card can caption the bar honestly."""
    task = _task(recordTypes=["tool"], targetCount=10, workshopId="w1", progressCount=0)
    monkeypatch.setattr(tasks_module, "_count_records", lambda *a: _done(4))

    numbers = _run(derive_progress([task]))["t1"]
    effective = effective_progress(task, numbers)

    assert numbers["derivedCount"] == 4
    assert effective["percent"] == 40
    assert effective["source"] == "derived"
    assert effective["label"] == "4 of 10 tools recorded"


def test_the_progress_precedence_is_pinned_end_to_end() -> None:
    """THE PRECEDENCE TABLE, as one table, because every client has to agree with it and none of
    them is generated from it. The last row is the one the owner's request turns on: an unmeasurable
    task must produce NO percentage, so the UI renders a state instead of a bar at 0% that reads as
    "nothing done"."""
    measured = {"derivedCount": 3, "derivedTarget": 12, "derivedBreakdown": {"tool": 3}}
    nothing: dict[str, Any] = {}

    # 1. An approval outranks every measurement.
    approved = effective_progress(_task(status="DONE"), {"derivedCount": 0, "derivedTarget": 9})
    assert (approved["percent"], approved["source"]) == (100, "status")

    # 2. Withdrawn work is 0, not 100.
    cancelled = effective_progress(_task(status="CANCELLED"), measured)
    assert (cancelled["percent"], cancelled["source"]) == (0, "status")

    # 3. Derived beats reported — the whole point of counting it.
    both = effective_progress(_task(targetCount=10, progressCount=10), measured)
    assert (both["percent"], both["source"]) == (25, "derived")

    # 4. Reported, when there is a quota and nothing was measured.
    reported = effective_progress(_task(targetCount=10, progressCount=4), nothing)
    assert (reported["percent"], reported["source"]) == (40, "reported")
    assert reported["label"] == "4 of 10 reported"

    # 5. A submission with nothing measurable is the assignee's 100.
    claimed = effective_progress(_task(status=SUBMITTED), nothing)
    assert (claimed["percent"], claimed["source"]) == (100, "status")

    # 6. NO NUMBER AT ALL. Not zero.
    unmeasurable = effective_progress(_task(status="IN_PROGRESS"), nothing)
    assert unmeasurable["percent"] is None
    assert unmeasurable["source"] is None


def test_an_open_ended_derived_count_reports_the_number_without_a_percentage() -> None:
    """"Record artisans, as many as apply" has a real numerator and no denominator. Suppressing the
    percentage is right; suppressing the COUNT would throw away the only measurement there is."""
    task = _task(recordTypes=["artisan"], targetCount=None, workshopId="w1")
    effective = effective_progress(
        task, {"derivedCount": 7, "derivedTarget": None, "derivedBreakdown": {"artisan": 7}}
    )

    assert effective["percent"] is None
    assert effective["source"] == "derived"
    assert effective["label"] == "7 artisans recorded"


def _done(value: Any):
    """An already-resolved awaitable, so a monkeypatched counter can stand in for an async one."""

    async def coro() -> Any:
        return value

    return coro()
