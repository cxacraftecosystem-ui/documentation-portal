"""Task assignment — who has to record what, where, and how far along they actually are.

THE MODEL
---------
One ``AssignedTask`` row is always exactly ONE assignee. Handing the same scope to N people writes
N rows sharing a ``batchId``, so an admin manages "record tools for these 5 researchers" as a single
unit (``GET /tasks/batches``) while each person still owns, sees and reports progress on their own
row. That is why there is no many-to-many join here: the row IS one person's to-do item.

A task's SCOPE is six orthogonal dimensions (see ``app.schemas.tasks``):
``workshopId`` x ``Workshop.questionnaireId`` x ``recordTypes[]`` x ``artisanIds[]`` x
``sectionIds[]`` x ``targetCount``.
Everything an admin needs to express falls out of combining them — ``recordTypes=[product]`` +
``artisanIds=[A,B,C]`` is "record products for artisans A, B and C"; ``sectionIds=[F]`` +
``artisanIds=[A,B]`` is "answer section F for artisans A and B". An empty scope (no record types
AND no sections) is rejected on create: a task with no work in it is a bug, not an empty state.

THE SIXTH DIMENSION IS NOT SET HERE, AND THAT IS THE POINT. ``Workshop.questionnaireId`` chooses the
**instrument**. ``AssignedTask.sectionIds`` chooses **which parts of that instrument one person
owns**. The first is a property of the event, the second a property of a workload. They are a set
and a subset — the only defect possible is a subset that is not one, and ``resolve_scope`` now
refuses it.

PROGRESS IS REPORTED *AND* DERIVED
----------------------------------
``progressCount`` is what the assignee claims. ``derivedCount`` is what the database can see them
having actually produced — products they created in that workshop for those artisans, questionnaire
sections they answered, and so on. The two travel side by side and the derived value NEVER
overwrites the reported one, because they answer different questions ("how far do you think you
are" vs "what landed in the repository") and the gap between them is precisely the signal the
accountability view exists to surface.

Derivation costs one COUNT per (task, record type). To stop a list request becoming a hundred
serial round trips those counts are de-duplicated by scope signature and run concurrently under a
small semaphore, and a page larger than :data:`DERIVED_TASK_LIMIT` skips derivation entirely
(``derivedCount: null``) rather than hammering the connection pool — which this deployment has
already had to be rescued from once, see ``app.core.db``.

FINISHING IS A CLAIM; APPROVAL IS A DECISION
--------------------------------------------
An assignee moving a task to "done" lands on :data:`SUBMITTED`, never on ``DONE``. ``DONE`` is what
an ADMIN, a MASTER_ADMIN or the task's creator writes when they agree the work happened, and until
one of them does the task stays on the assignee's screen wearing a different pill ("Under review",
:data:`STATUS_LABELS`) instead of quietly vanishing. That split exists because the whole point of
this module is the gap between what somebody says they did and what the repository can see, and a
status the doer sets alone cannot close that gap — it only records that they think it is closed.

WHICH SET A REVIEW STATE BELONGS IN, AND WHY IT IS NOT :data:`LIVE_STATUSES`. ``LIVE_STATUSES``
drives ``is_overdue``. Put ``SUBMITTED`` in it and a researcher who submitted a week before the due
date starts reading as LATE the moment an admin is slow to look — the board would blame the assignee
for the reviewer's backlog, which is precisely the accusation this board must never make by
accident. So ``LIVE_STATUSES`` keeps its old meaning ("work the ASSIGNEE still owes") and the new
:data:`OUTSTANDING_STATUSES` carries the other one ("still on the assignee's screen, by anybody's
doing"). Every counter now names which of the two it meant.

NO NEW TIMESTAMP COLUMN, AND THE ARGUMENT FOR THAT. ``completedAt`` is stamped when the work is
FIRST declared finished — the assignee's submission, or an admin's direct mark where there was no
submission — and approval does NOT re-stamp it. So ``completedAt`` still means what its name always
meant, "when the work was finished", and ``completedAt > dueAt`` stays a statement about the person
who did the work rather than a blend of their lateness and the reviewer's. The price is that the
moment of APPROVAL is not recorded anywhere (``updatedAt`` is bumped by every write and cannot
answer it) — the same honest limit as "an override leaves no trace of who performed it", stated here
so no screen claims otherwise.
"""
import asyncio
import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    ROLE_LABELS,
    get_current_user,
    get_value,
    is_admin,
    is_empty_value,
    is_master_admin,
    require_admin,
    role_rank,
    role_value,
)
from app.schemas.tasks import TaskBatchCreate, TaskCreate, TaskUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.questionnaire_instruments import (
    default_questionnaire_id,
    require_questionnaire,
)
from app.services.record_filters import artisan_workshop_clause
from app.services.records import clean_data, public_encode

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/tasks", tags=["tasks"])

# The assignee's "I have finished" — a CLAIM awaiting a decision, not the decision. Named for the
# act that provably happened (somebody submitted it) rather than for a reviewer who may not exist
# yet: "IN_REVIEW" would assert that a person is looking at it, which the row cannot support, and
# "DONE_PENDING_REVIEW" would put the substring DONE into a value that is emphatically not done —
# one careless `"DONE" in task.status` and an unapproved task counts as finished repository-wide.
# The word the OWNER uses ("under review") is the assignee-facing LABEL, see STATUS_LABELS.
SUBMITTED = "SUBMITTED"

TASK_STATUSES = {"OPEN", "IN_PROGRESS", SUBMITTED, "DONE", "CANCELLED"}
# Fixed key order for every statusCounts map on the wire. A dict built with `.get(status, 0) + 1`
# grows keys on demand, so a batch nobody has submitted in would ship a map with no SUBMITTED key at
# all and a client reading `counts.SUBMITTED` would fault on the happy path.
STATUS_COUNT_KEYS = ("OPEN", "IN_PROGRESS", SUBMITTED, "DONE", "CANCELLED")

# WORK THE ASSIGNEE STILL OWES. Unchanged on purpose: this is the set `is_overdue` consults, and
# SUBMITTED must stay out of it. A task handed in on the 1st and approved on the 12th would
# otherwise flip to overdue on the 8th and put the reviewer's delay on the researcher's record.
LIVE_STATUSES = {"OPEN", "IN_PROGRESS"}
# WORK THE REVIEWER OWES — the admin's approval queue.
REVIEW_STATUSES = {SUBMITTED}
# STILL ON THE ASSIGNEE'S SCREEN, by anybody's doing. The owner's "for it to not pop up next time"
# read backwards is the requirement: it MUST go on popping up until somebody approves it. This is
# the set the assignee's list and summary card count, and the set `includeFinished=false` keeps —
# "unfinished" and "not yet approved" are the same thing to everyone except the overdue clock.
OUTSTANDING_STATUSES = LIVE_STATUSES | REVIEW_STATUSES

# ONE VOCABULARY FOR THREE CLIENTS. There is no API codegen here: web `lib/types.ts` and Android
# `ApiModels.kt` are hand-written, and Kotlin's `ignoreUnknownKeys` makes a missed field silent. If
# each client invented its own pill wording, an assignee on Android and the same assignee on the web
# would be told different things about the same row. The server says it once.
STATUS_LABELS = {
    "OPEN": "To do",
    "IN_PROGRESS": "In progress",
    SUBMITTED: "Under review",
    # "Approved", not "Done": after this change DONE is specifically the state a second person
    # agreed to, and that agreement is the only new information the pill has to carry.
    "DONE": "Approved",
    "CANCELLED": "Cancelled",
}

INCLUDE = {"assignee": True, "createdBy": True, "workshop": True}

VIEWS = {"assigned", "created", "all"}

# The record kinds a task can ask for, in the order they are shown and titled. A list, not a set,
# so a generated title reads in a stable, sensible order regardless of the payload's order.
RECORD_TYPE_ORDER = ["artisan", "product", "process", "tool", "questionnaire", "media"]
RECORD_TYPES = set(RECORD_TYPE_ORDER)

# kind -> (singular, plural) label, used for generated titles and for the assignment picker.
RECORD_TYPE_LABELS: dict[str, tuple[str, str]] = {
    "artisan": ("artisan", "artisans"),
    "product": ("product", "products"),
    "process": ("process", "processes"),
    "tool": ("tool", "tools"),
    "questionnaire": ("questionnaire interview", "questionnaire interviews"),
    "media": ("media file", "media files"),
}

# Derivation guards. A page of 150 tasks with two record types each is ~300 COUNTs; past that we
# decline rather than degrade the whole API for one oversized request.
DERIVED_TASK_LIMIT = 150
DERIVED_CONCURRENCY = 8

# How many task rows the batch/progress rollups scan. Both group in Python (Prisma has no "group by
# batch, with per-member detail" in one call), so the window has to be explicitly bounded.
ROLLUP_SCAN_LIMIT = 2000

# "Due soon" on the assignee's summary card. Two days is one fieldwork day plus the evening to
# notice — short enough to mean something, long enough that a warning is still actionable.
DUE_SOON_HOURS = 48


# ---------------------------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------------------------


async def require_task(task_id: str) -> Any:
    task = await db.assignedtask.find_unique(where={"id": task_id}, include=INCLUDE)
    if not task:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found")
    return task


def assert_status_value(value: str) -> None:
    if value not in TASK_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"status must be one of {sorted(TASK_STATUSES)}",
        )


async def assert_assignable(assigner: Any, assignee_id: str) -> Any:
    """The assignee must exist and rank strictly below the assigner — except the master admin, who
    may assign work to anyone, and an ADMIN or MASTER_ADMIN assigning to THEMSELVES, which is
    allowed outright (see the argument at the self-assignment clause below)."""
    assignee = await db.user.find_unique(where={"id": assignee_id})
    if not assignee:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Assignee not found")
    if assignee.id == get_value(assigner, "id"):
        # SELF-ASSIGNMENT — REFUSED FOR EVERYONE UNTIL 2026-09-14, NOW ALLOWED FOR ADMINS.
        #
        # WHY THE BAN EXISTED AT ALL. The tier rule below cannot express "yourself": role_rank(me)
        # >= role_rank(me) is true by definition, so without this clause a self-assignment fell
        # through and was refused with "You can only assign tasks to users below your own tier" —
        # a sentence that is nonsense said to somebody about themselves, and one that would have
        # read to a master admin as a tier problem they had no way to fix. Catching the case here
        # bought one honest error message. That is the whole of what it ever bought.
        #
        # WHY LIFTING IT FOR ADMIN AND MASTER_ADMIN IS SAFE, AND WHY IT IS NOT A WIDENING. Both
        # creation routes are Depends(require_admin) — create_task (:766) and create_task_batch
        # (:790) — so the ONLY accounts that can reach this clause while creating a task are
        # already ADMIN or MASTER_ADMIN. The clause therefore never protected a lower tier from
        # anything; it only stopped an administrator recording their own workload on the same board
        # they hold everybody else to, which is exactly the thing the owner asked for.
        #
        # THE 422 IS KEPT FOR EVERYONE ELSE, AND IT IS REACHABLE. update_task's manager test is
        # `task.createdById == current_user.id or is_admin(current_user)` (:1188), so a demoted
        # admin still manages the tasks they created and can still send
        # PATCH /tasks/{id} {"assigneeId": "<their own id>"} through this helper (:1191). For them
        # nothing changes: they may hand their old task to somebody below them, and they may not
        # quietly move it onto themselves now that they are no longer entitled to assign work.
        #
        # THE COST, STATED RATHER THAN DISCOVERED LATER. The accountability board now contains rows
        # whose "assigned by" and "assigned to" are the same person: GET /tasks/progress groups by
        # assignee and excludes nobody, so a self-assigning admin appears in their own rollup beside
        # the people they assigned, and one row is returned by BOTH view=created and view=assigned.
        # That is the honest rendering of "I owe this work too" and must not be filtered out —
        # hiding an admin's own overdue task from the overdue count is the failure this board exists
        # to prevent, merely committed by the person running it.
        if not is_admin(assigner):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="You cannot assign a task to yourself",
            )
        # Return BEFORE the tier rule, which would otherwise refuse what was just allowed.
        return assignee
    if not is_master_admin(assigner) and role_rank(assignee) >= role_rank(assigner):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only assign tasks to users below your own tier",
        )
    return assignee


def dedupe(values: list[str] | None) -> list[str]:
    """Order-preserving de-duplication with blank ids dropped."""
    seen: set[str] = set()
    out: list[str] = []
    for value in values or []:
        clean = (value or "").strip()
        if clean and clean not in seen:
            seen.add(clean)
            out.append(clean)
    return out


def normalize_record_types(values: list[str] | None) -> list[str]:
    """Lower-case, de-duplicate and canonically order the requested record types, rejecting any the
    system does not know how to route or count."""
    requested = {value.strip().lower() for value in (values or []) if value and value.strip()}
    unknown = sorted(requested - RECORD_TYPES)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unknown recordTypes {unknown}; expected any of {RECORD_TYPE_ORDER}",
        )
    return [kind for kind in RECORD_TYPE_ORDER if kind in requested]


@dataclass(slots=True)
class ResolvedScope:
    """A scope whose every id has been proven to exist, with the rows already loaded so the caller
    can title the task and echo names back without a second round trip."""

    workshop: Any | None = None
    recordTypes: list[str] = field(default_factory=list)
    artisans: list[Any] = field(default_factory=list)
    sections: list[Any] = field(default_factory=list)
    targetCount: int | None = None

    @property
    def artisanIds(self) -> list[str]:
        return [artisan.id for artisan in self.artisans]

    @property
    def sectionIds(self) -> list[str]:
        return [section.id for section in self.sections]


async def resolve_scope(
    *,
    workshop_id: str | None,
    record_types: list[str] | None,
    artisan_ids: list[str] | None,
    section_ids: list[str] | None,
    target_count: int | None,
    require_work: bool = True,
) -> ResolvedScope:
    """Validate every dimension of a scope and load the rows it points at.

    Ids are checked here rather than left to a foreign key, because ``artisanIds`` and
    ``sectionIds`` are plain ``String[]`` columns: Postgres will happily store a typo and the task
    would then silently scope to nothing at all. ``require_work`` is the "a task must ask for
    something" rule; it is relaxed on PATCH for legacy rows that never carried a scope.
    """
    workshop = None
    if workshop_id:
        workshop = await db.workshop.find_unique(where={"id": workshop_id})
        if not workshop:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop not found")

    types = normalize_record_types(record_types)

    wanted_artisans = dedupe(artisan_ids)
    artisans: list[Any] = []
    if wanted_artisans:
        artisans = await db.artisan.find_many(
            where={"id": {"in": wanted_artisans}}, order={"name": "asc"}
        )
        missing = sorted(set(wanted_artisans) - {artisan.id for artisan in artisans})
        if missing:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"Unknown artisanIds: {missing}",
            )

    wanted_sections = dedupe(section_ids)
    sections: list[Any] = []
    if wanted_sections:
        sections = await db.questionnairesection.find_many(
            where={"id": {"in": wanted_sections}}, order={"sortOrder": "asc"}
        )
        missing = sorted(set(wanted_sections) - {section.id for section in sections})
        if missing:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"Unknown sectionIds: {missing}",
            )

    # WHICH INSTRUMENT THOSE SECTIONS HAVE TO COME FROM.
    #
    # `Workshop.questionnaireId` says which questionnaire is in use at a workshop; `sectionIds` says
    # which parts of it one person owns. The second must be a subset of the first, and nothing but
    # this check makes it one — `sectionIds` is a plain `String[]` column (schema.prisma), so
    # Postgres will store an id from any instrument at all and the task would then scope to sections
    # that are not on the form its assignee opens. The task would read as assigned, report progress
    # against a denominator of zero, and be impossible to complete.
    if sections:
        instrument_ids = {section.questionnaireId for section in sections}
        if len(instrument_ids) > 1:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "sectionIds must all belong to one questionnaire; these span "
                    f"{len(instrument_ids)}."
                ),
            )
        if workshop is not None:
            # A workshop with no binding resolves to the default, the same way the capture form
            # does — so the rule is the same rule the researcher's screen will obey, not a stricter
            # one invented here.
            expected = get_value(workshop, "questionnaireId") or await default_questionnaire_id()
            wrong = sorted(s.code for s in sections if s.questionnaireId != expected)
            if wrong:
                instrument = await require_questionnaire(expected)
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail=(
                        f"This workshop uses “{instrument.title}”. These sections belong to a "
                        f"different questionnaire and cannot be assigned here: {wrong}."
                    ),
                )

    if require_work and not types and not sections:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "A task needs work in it: pass recordTypes "
                f"(any of {RECORD_TYPE_ORDER}) and/or sectionIds."
            ),
        )

    return ResolvedScope(
        workshop=workshop,
        recordTypes=types,
        artisans=artisans,
        sections=sections,
        targetCount=target_count,
    )


# ---------------------------------------------------------------------------------------------
# Human-readable titles
# ---------------------------------------------------------------------------------------------


def _and_list(items: list[str]) -> str:
    if not items:
        return ""
    if len(items) == 1:
        return items[0]
    return f"{', '.join(items[:-1])} and {items[-1]}"


def scope_title(scope: ResolvedScope) -> str:
    """A readable default title derived from the scope.

    "Record products and tools for 3 artisans", "Record 10 tools", "Questionnaire sections C, D",
    "Questionnaire section F for Ramesh Bhai and Sita Devi". Generated whenever the admin does not
    type one, because a task list full of "Untitled" is useless to the person working through it.
    """
    parts: list[str] = []
    if scope.recordTypes:
        plural = scope.targetCount != 1
        labels = [RECORD_TYPE_LABELS[kind][1 if plural else 0] for kind in scope.recordTypes]
        count = f"{scope.targetCount} " if scope.targetCount else ""
        parts.append(f"Record {count}{_and_list(labels)}")
    if scope.sections:
        codes = ", ".join(section.code for section in scope.sections)
        noun = "section" if len(scope.sections) == 1 else "sections"
        # Lower-cased when it is the trailing half of a combined title ("Record products +
        # questionnaire sections C, D") so the whole thing still reads as one instruction.
        head = "Questionnaire" if not parts else "questionnaire"
        parts.append(f"{head} {noun} {codes}")

    title = " + ".join(parts) or "Field task"
    names = [artisan.name for artisan in scope.artisans]
    if len(names) == 1:
        title += f" for {names[0]}"
    elif len(names) == 2:
        title += f" for {names[0]} and {names[1]}"
    elif names:
        title += f" for {len(names)} artisans"
    if scope.workshop is not None and len(title) + len(scope.workshop.title) + 3 <= 300:
        title += f" ({scope.workshop.title})"
    return title[:300]


# ---------------------------------------------------------------------------------------------
# Derived (data-backed) progress
# ---------------------------------------------------------------------------------------------


async def _count_records(
    kind: str, assignee_id: str, workshop_id: str | None, artisan_ids: list[str]
) -> int:
    """How many records of ``kind`` this assignee has actually created inside the task's scope."""
    in_artisans: dict[str, Any] | None = {"in": artisan_ids} if artisan_ids else None

    if kind == "artisan":
        where: dict[str, Any] = {"createdById": assignee_id}
        if workshop_id:
            # An artisan reaches a workshop two ways — the explicit column and the WorkshopArtisan
            # join, kept in lock-step for new rows but the only link on older ones — so both
            # readings have to count, or work recorded before the column would look undone.
            where["OR"] = [
                {"workshopId": workshop_id},
                {"workshops": {"some": {"workshopId": workshop_id}}},
            ]
        if in_artisans:
            where["id"] = in_artisans
        return await db.artisan.count(where=where)

    if kind in {"product", "tool"}:
        delegate = db.productdocumentation if kind == "product" else db.tooldocumentation
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisanId"] = in_artisans
        return await delegate.count(where=where)

    if kind == "process":
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            # A process has no artisan of its own; it inherits one through its parent product.
            where["product"] = {"is": {"artisanId": in_artisans}}
        return await db.process.count(where=where)

    if kind == "media":
        where = {"uploadedById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisanId"] = in_artisans
        return await db.mediafile.count(where=where)

    if kind == "questionnaire":
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisans"] = {"some": {"artisanId": in_artisans}}
        return await db.questionnaireinterview.count(where=where)

    return 0


async def _count_workshop_artisans(workshop_id: str) -> int:
    """How many artisans a workshop actually has — the denominator for "sections for EVERYONE".

    Uses the shared ``artisan_workshop_clause`` rather than a bare ``workshopId ==`` test,
    because an artisan reaches a workshop three ways (the column, the WorkshopArtisan roster, and
    having sat in an interview taken there) and this repository's single most repeated defect is a
    scope that reads those three one way on one screen and another way on the next. The View Data
    completion matrix counts its rows with this exact predicate (the ``artisan_workshop_clause``
    call in routes/questionnaire.py), so "12 artisans" on the matrix and "of 12 artisans" on a task
    cannot disagree.
    """
    return await db.artisan.count(where=artisan_workshop_clause([workshop_id], False))


async def _count_sections(
    assignee_id: str, workshop_id: str | None, artisan_ids: list[str], section_ids: list[str]
) -> tuple[int, int, int]:
    """Questionnaire coverage this assignee has actually produced, in all three readings at once:
    ``(pairs, sections, unlinked)``.

      - ``pairs``    - distinct (artisan, section) ANSWERED pairs. The unit that grows as the same
                       sections are covered for more and more artisans.
      - ``sections`` - distinct sections answered at all, for anybody. Saturates at the section
                       count after the FIRST artisan.
      - ``unlinked`` - sections whose only answers sit on an interview with no artisan attached, so
                       they can contribute to ``sections`` but never to ``pairs``.

    ALL THREE ARE RETURNED AND THE CALLER PICKS, because the honest unit depends on which
    denominator actually exists - see ``_section_target``. This used to return one number, chosen
    here, with the argument that "all artisans" has no honest denominator so pairs would be a
    percentage of nothing. That argument was right about a task with no workshop and wrong about
    the normal case: a workshop HAS a roster, so ``sections x roster`` is a real denominator and
    the pair count is the thing that climbs as the researcher works through the artisans. Deciding
    it here meant the caller could not use the roster it had already counted.

    Only non-empty answers written BY this assignee count: a section somebody else filled in is
    their progress, not this person's.
    """
    where: dict[str, Any] = {
        "answeredById": assignee_id,
        "question": {"is": {"sectionId": {"in": section_ids}}},
    }
    if workshop_id:
        where["interview"] = {"is": {"workshopId": workshop_id}}
    responses = await db.questionnaireresponse.find_many(
        where=where,
        include={"question": True, "interview": {"include": {"artisans": True}}},
    )

    scope = set(artisan_ids)
    pairs: set[tuple[str, str]] = set()
    plain_sections: set[str] = set()
    unlinked: set[str] = set()
    for response in responses:
        if is_empty_value(response.answerText):
            continue
        section_id = get_value(response.question, "sectionId")
        if not section_id or section_id not in section_ids:
            continue
        plain_sections.add(section_id)
        links = get_value(response.interview, "artisans") or []
        if not links:
            # An answer that names no artisan cannot say any artisan's section is covered. Counted
            # separately rather than dropped, so a task sitting at 0% because its interviews were
            # never linked to anybody can SAY that, instead of looking like work never done - the
            # same shortfall the completion matrix reports as ``unassignedInterviews``.
            unlinked.add(section_id)
            continue
        for link in links:
            if not scope or link.artisanId in scope:
                pairs.add((link.artisanId, section_id))
    return len(pairs), len(plain_sections), len(unlinked)


def _section_target(task: Any, roster: int | None = None) -> int:
    """The denominator for the questionnaire half of a task, and the unit it is counted in.

      - artisans NAMED                              -> sections x named artisans (PAIRS)
      - "every artisan", workshop roster known      -> sections x roster         (PAIRS)
      - "every artisan", no workshop / empty roster -> sections                  (SECTIONS)

    THE MIDDLE ROW IS THE OWNER'S ACTUAL REQUEST. "Sections covered for all artisans should progress
    automatically as they record for more and more artisans": under the old denominator it could
    not, and not because derivation was missing - because the target was ``len(sections)`` while the
    count was "sections answered for ANYBODY", so a researcher who finished both sections for ONE
    artisan out of forty read 2 of 2, 100%, done. Every subsequent artisan moved the bar by zero.

    THE DENOMINATOR CAN GROW, SO THE PERCENTAGE CAN FALL. Adding an artisan to the workshop adds
    ``len(sections)`` to the target, and a task showing 100% drops the moment the roster does. That
    is the correct reading of "for all artisans" - more artisans IS more work - and it is why the
    roster is counted live rather than frozen onto the row at assignment time, where it would go
    stale the first time somebody was added and never be noticed again.
    """
    sections = len(task.sectionIds or [])
    if not sections:
        return 0
    named = len(task.artisanIds or [])
    if named:
        return sections * named
    if roster and roster > 0:
        return sections * roster
    return sections


def _derived_target(task: Any, roster: int | None = None) -> int | None:
    """The denominator ``derivedCount`` should be read against, or ``None`` when the scope has no
    honest one (record types with no ``targetCount`` means "as many as apply").

    ``roster`` defaults to None so this stays a PURE function, usable as the fallback in
    ``serialize_task`` when derivation did not run; ``derive_progress`` passes the live roster it
    has already counted. With ``roster=None`` it returns exactly what it always returned.

    A MIXED TASK WITH AN OPEN-ENDED RECORD HALF STILL RETURNS None, deliberately. "Record products
    (as many as apply) + sections C and D" has an honest denominator for one half and none for the
    other; adding them would present ``sections_done / sections_target`` as though it described the
    whole task. The section half stays visible on ``derivedBreakdown``, which is where a UI should
    read it - it is the single PERCENTAGE that has to stay silent, not the numbers behind it.
    """
    total = 0
    if task.recordTypes:
        if not task.targetCount:
            return None
        total += task.targetCount
    total += _section_target(task, roster)
    return total or None


def _record_key(task: Any, kind: str, artisan_key: tuple) -> tuple:
    return ("record", kind, task.assigneeId, task.workshopId, artisan_key)


def _section_key(task: Any, artisan_key: tuple) -> tuple:
    # NO `questionnaireId` IN THIS KEY, AND NONE IS NEEDED. `sectionIds` are IDS, and an id already
    # names exactly one instrument — two instruments' section "F" are two different ids, so two
    # tasks scoped to them never share a signature and never share a cached count. Adding the
    # instrument here would widen the key without changing a single grouping. Said out loud because
    # the neighbouring code-keyed maps in routes/questionnaire.py DID have to change, and the next
    # reader will arrive here looking for the same bug.
    return ("sections", task.assigneeId, task.workshopId, artisan_key, tuple(sorted(task.sectionIds)))


def _roster_key(task: Any) -> tuple:
    """One COUNT per WORKSHOP on the page, shared by every task that needs it.

    The roster is a property of the workshop alone - not of the assignee, the sections or the
    record types - so a batch handed to eight researchers at one workshop asks for it ONCE. That is
    the whole cost of the automatic "for all artisans" denominator: one extra count per distinct
    workshop, not one per task, which keeps this inside the budget the module docstring sets.
    """
    return ("roster", task.workshopId)


def _needs_roster(task: Any) -> bool:
    """A questionnaire task scoped to EVERY artisan at a known workshop - the only shape whose
    denominator has to be looked up. Named artisans carry their own denominator, and a task with no
    workshop has no roster to ask about."""
    return bool(task.sectionIds) and not (task.artisanIds or []) and bool(task.workshopId)


async def derive_progress(tasks: list[Any]) -> dict[str, dict[str, Any]]:
    """``taskId -> {"derivedCount", "derivedTarget", "derivedBreakdown"}`` for a whole page.

    Counts are keyed by scope signature and de-duplicated before they run, then executed
    concurrently under a semaphore — a batch handed to eight people is eight different creators, so
    the win comes from never running the same COUNT twice inside one response and from overlapping
    the round trips. Any failure degrades that task to ``None`` instead of failing the request:
    progress reporting must never be able to take the task list down.
    """
    if not tasks or len(tasks) > DERIVED_TASK_LIMIT:
        return {}

    jobs: dict[tuple, None] = {}
    for task in tasks:
        artisan_key = tuple(sorted(task.artisanIds or []))
        for kind in task.recordTypes or []:
            if kind in RECORD_TYPES:
                jobs[_record_key(task, kind, artisan_key)] = None
        if task.sectionIds:
            jobs[_section_key(task, artisan_key)] = None
            if _needs_roster(task):
                jobs[_roster_key(task)] = None
    if not jobs:
        return {}

    semaphore = asyncio.Semaphore(DERIVED_CONCURRENCY)

    async def run(key: tuple) -> Any:
        async with semaphore:
            if key[0] == "record":
                return await _count_records(key[1], key[2], key[3], list(key[4]))
            if key[0] == "roster":
                return await _count_workshop_artisans(key[1])
            return await _count_sections(key[1], key[2], list(key[3]), list(key[4]))

    keys = list(jobs)
    results = await asyncio.gather(*(run(key) for key in keys), return_exceptions=True)
    counts: dict[tuple, Any] = {}
    for key, result in zip(keys, results, strict=True):
        if isinstance(result, BaseException):
            logger.warning("Derived task progress failed for %s: %s", key, result)
            counts[key] = None
        else:
            counts[key] = result

    derived: dict[str, dict[str, Any]] = {}
    for task in tasks:
        artisan_key = tuple(sorted(task.artisanIds or []))
        breakdown: dict[str, int] = {}
        roster: int | None = None

        # A TASK WITH NO MEASURABLE SCOPE DERIVES None, NEVER 0 - AND THAT USED TO DEPEND ON ITS
        # NEIGHBOURS. "Food + collect TA details" has no recordTypes and no sectionIds, so it
        # contributes no job; the early return above then gave it `derivedCount: null` when it was
        # ALONE on the page, but this loop fell straight through to `total = 0` and shipped
        # `derivedCount: 0` the moment any OTHER task on the same page had a scope. Same row, two
        # different answers, decided by pagination - and the 0 reads on screen as "this person has
        # produced nothing", which is a specific accusation about a task the database cannot
        # measure at all. Seeded from the scope instead, so the answer is a property of the row.
        measurable = bool(task.recordTypes) or bool(task.sectionIds)
        total: int | None = 0 if measurable else None

        for kind in task.recordTypes or []:
            if kind not in RECORD_TYPES:
                continue
            value = counts.get(_record_key(task, kind, artisan_key))
            if value is None:
                total = None
                continue
            breakdown[kind] = value
            if total is not None:
                total += value
        if task.sectionIds:
            value = counts.get(_section_key(task, artisan_key))
            if _needs_roster(task):
                roster = counts.get(_roster_key(task))
            if not isinstance(value, tuple):
                total = None
            else:
                pairs, plain, unlinked = value
                # WHICH UNIT, decided here because only here are both the coverage numbers and the
                # roster in hand. Pairs whenever a real denominator of artisans exists (named, or
                # the workshop's own roster); bare sections only when there is nothing to divide by,
                # which is the pre-existing behaviour kept for workshop-less tasks rather than
                # replaced. `_section_target` makes exactly the same choice, and the two MUST agree
                # or the numerator and denominator are counting different things.
                uses_pairs = bool(task.artisanIds) or bool(roster and roster > 0)
                covered = pairs if uses_pairs else plain
                breakdown["sections"] = covered
                if uses_pairs and unlinked:
                    # Answers that name no artisan. They cannot raise `pairs`, so without this the
                    # difference between "nobody has started" and "the interviews were never linked
                    # to an artisan" is invisible on a stuck 0%.
                    breakdown["unlinkedSections"] = unlinked
                if total is not None:
                    total += covered
        derived[task.id] = {
            "derivedCount": total,
            "derivedTarget": _derived_target(task, roster),
            "derivedBreakdown": breakdown,
            # The roster this task's denominator was built from, echoed so a UI can say "6 of 24
            # (2 sections x 12 artisans)" rather than presenting a number nobody can reconstruct.
            "derivedArtisanCount": roster,
        }
    return derived


# ---------------------------------------------------------------------------------------------
# Serialisation
# ---------------------------------------------------------------------------------------------


def progress_percent(task: Any) -> int | None:
    """Self-reported completion, 0-100. ``None`` when the task is open-ended (no ``targetCount``)
    and unfinished — inventing a number there would be a lie the accountability view then acts on.

    SUBMITTED reads 100 here for the same reason DONE does: this function is the REPORTED half of
    the pair, and a submission is precisely a report that the work is finished. Whether it actually
    is finished is ``derivedPercent``'s question, and the two sitting side by side at "100% claimed,
    25% found" is the single most useful thing an approver can be shown.
    """
    task_status = get_value(task, "status")
    if task_status in {"DONE", SUBMITTED}:
        return 100
    if task_status == "CANCELLED":
        return 0
    target = get_value(task, "targetCount")
    if target:
        reported = min(get_value(task, "progressCount") or 0, target)
        return min(100, round(100 * reported / target))
    return None


def derived_percent(numbers: dict[str, Any]) -> int | None:
    """Data-backed completion, 0-100, or ``None`` when either half of the fraction is unknown."""
    count = numbers.get("derivedCount")
    target = numbers.get("derivedTarget")
    if count is None or not target:
        return None
    return min(100, round(100 * min(count, target) / target))


def _progress_unit(task: Any, numbers: dict[str, Any]) -> str:
    """The noun the derived numbers are counted in, so a label can say what it is measuring."""
    breakdown = numbers.get("derivedBreakdown") or {}
    has_sections = "sections" in breakdown
    kinds = [key for key in breakdown if key in RECORD_TYPES]
    if has_sections and not kinds:
        # Pairs or bare sections — `_section_target` made this choice and the label must echo it,
        # or "6 of 24 sections" reads as a questionnaire with 24 sections in it.
        per_artisan = bool(task.artisanIds) or bool(numbers.get("derivedArtisanCount"))
        return "artisan sections" if per_artisan else "sections"
    if kinds and not has_sections:
        if len(kinds) == 1:
            return RECORD_TYPE_LABELS[kinds[0]][1]
        return "records"
    return "items"


def effective_progress(task: Any, numbers: dict[str, Any]) -> dict[str, Any]:
    """THE ONE NUMBER A PROGRESS BAR SHOULD BE DRAWN FROM, plus where it came from and what to
    write under it.

    ``percentComplete`` stays exactly what it was (self-reported) because the accountability board
    already renders it against ``derivedCount`` and silently changing its meaning would rewrite that
    comparison into a comparison of a thing with itself. This is additive: three new fields that say
    which measure is authoritative for THIS row, so the assignee's card and the admin's board do not
    each have to re-derive the precedence and drift apart.

    PRECEDENCE, and the argument for each step:
      1. DONE -> 100. Somebody with authority agreed. Nothing measured outranks that.
      2. CANCELLED -> 0. The work was withdrawn; it is not 100% of anything.
      3. A DERIVED fraction, whenever both halves are known. This is the owner's "should progress
         automatically as they record for more and more artisans" - no number typed by anyone.
      4. Otherwise the REPORTED fraction, when a quota exists to divide by.
      5. SUBMITTED with nothing measurable -> 100. The assignee declares it finished and the
         database has no way to contradict them; showing their handed-in task at 0% would be the
         system calling them a liar on no evidence.
      6. Otherwise None. "Food + collect TA details" has no measurable scope and no quota, and a bar
         at 0% would read as "nothing done" rather than "nothing countable". The UI MUST render the
         state pill here and no bar - a fake 0% is the defect this branch exists to prevent.
    """
    task_status = get_value(task, "status")
    if task_status == "DONE":
        return {"percent": 100, "source": "status", "label": STATUS_LABELS["DONE"]}
    if task_status == "CANCELLED":
        return {"percent": 0, "source": "status", "label": "Withdrawn"}

    count = numbers.get("derivedCount")
    target = numbers.get("derivedTarget")
    percent = derived_percent(numbers)
    if percent is not None:
        return {
            "percent": percent,
            "source": "derived",
            "label": f"{count} of {target} {_progress_unit(task, numbers)} recorded",
        }
    if count is not None and count > 0 and not target:
        # Open-ended and moving: a count with no honest denominator. No percentage, but the number
        # itself is real and hiding it would waste the only measurement there is.
        return {
            "percent": None,
            "source": "derived",
            "label": f"{count} {_progress_unit(task, numbers)} recorded",
        }

    quota = get_value(task, "targetCount")
    if quota:
        reported = min(get_value(task, "progressCount") or 0, quota)
        return {
            "percent": min(100, round(100 * reported / quota)),
            "source": "reported",
            "label": f"{reported} of {quota} reported",
        }
    if task_status == SUBMITTED:
        return {"percent": 100, "source": "status", "label": "Handed in, awaiting approval"}
    return {"percent": None, "source": None, "label": "No measurable target"}


def is_overdue(task: Any) -> bool:
    due = get_value(task, "dueAt")
    if not due or get_value(task, "status") not in LIVE_STATUSES:
        return False
    # Postgres hands back tz-aware values, but never trust that into a comparison that would 500.
    if due.tzinfo is None:
        due = due.replace(tzinfo=UTC)
    return due < datetime.now(UTC)


async def load_scope_lookups(tasks: list[Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    """Artisan and section rows referenced anywhere on this page, in two queries, so the UI never
    has to follow up per task."""
    artisan_ids: set[str] = set()
    section_ids: set[str] = set()
    for task in tasks:
        artisan_ids.update(task.artisanIds or [])
        section_ids.update(task.sectionIds or [])
    artisans = (
        await db.artisan.find_many(where={"id": {"in": sorted(artisan_ids)}}) if artisan_ids else []
    )
    sections = (
        await db.questionnairesection.find_many(where={"id": {"in": sorted(section_ids)}})
        if section_ids
        else []
    )
    return (
        {artisan.id: artisan for artisan in artisans},
        {section.id: section for section in sections},
    )


def user_brief(user: Any) -> dict[str, Any] | None:
    """Just enough to name a person on a task board — never any privilege or password material."""
    if user is None:
        return None
    role = role_value(user)
    return {
        "id": get_value(user, "id"),
        "name": get_value(user, "name"),
        "email": get_value(user, "email"),
        "role": role,
        "roleLabel": ROLE_LABELS.get(role, role),
    }


def serialize_task(
    task: Any,
    artisan_map: dict[str, Any],
    section_map: dict[str, Any],
    derived: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    """The stored row plus everything the UI would otherwise have to fetch for itself.

    Strictly ADDITIVE: every key the original endpoint returned is still present and unchanged; the
    resolved names, labels and progress numbers are extra.
    """
    payload = public_encode(task)
    payload["workshopTitle"] = get_value(get_value(task, "workshop"), "title")
    payload["artisans"] = [
        {
            "id": artisan_id,
            "name": artisan_map[artisan_id].name,
            "place": artisan_map[artisan_id].place,
        }
        for artisan_id in (task.artisanIds or [])
        if artisan_id in artisan_map
    ]
    payload["sections"] = sorted(
        (
            {
                "id": section_id,
                "code": section_map[section_id].code,
                "title": section_map[section_id].title,
                "sortOrder": section_map[section_id].sortOrder,
            }
            for section_id in (task.sectionIds or [])
            if section_id in section_map
        ),
        # Questionnaire order, not payload order, so a task reads "sections C, D" the way the
        # questionnaire itself is numbered.
        key=lambda item: item["sortOrder"],
    )
    payload["recordTypeLabels"] = [
        RECORD_TYPE_LABELS[kind][1] for kind in (task.recordTypes or []) if kind in RECORD_TYPES
    ]
    payload["percentComplete"] = progress_percent(task)
    payload["isOverdue"] = is_overdue(task)
    numbers = derived.get(task.id) or {}
    payload["derivedCount"] = numbers.get("derivedCount")
    payload["derivedTarget"] = numbers.get("derivedTarget", _derived_target(task))
    payload["derivedBreakdown"] = numbers.get("derivedBreakdown", {})
    payload["derivedArtisanCount"] = numbers.get("derivedArtisanCount")
    payload["derivedPercent"] = derived_percent(
        {
            "derivedCount": payload["derivedCount"],
            "derivedTarget": payload["derivedTarget"],
        }
    )

    # THE REVIEW STATE, SAID IN WORDS AND IN BOOLEANS. Three hand-written clients read this payload
    # and none of them is generated from it, so every one of them comparing `status == "SUBMITTED"`
    # for itself is three chances to miss the new value in silence — on Android especially, where
    # `ignoreUnknownKeys` turns a miss into a wrong screen rather than a crash.
    task_status = get_value(task, "status")
    payload["statusLabel"] = STATUS_LABELS.get(task_status, task_status)
    payload["isAwaitingReview"] = task_status == SUBMITTED
    # "Still on my list" — the owner's "it should keep popping up until it is approved", as one
    # boolean the client filters on instead of re-deriving the status set.
    payload["isOutstanding"] = task_status in OUTSTANDING_STATUSES

    effective = effective_progress(task, numbers)
    payload["effectivePercent"] = effective["percent"]
    payload["progressSource"] = effective["source"]
    payload["progressLabel"] = effective["label"]
    return payload


async def serialize_tasks(tasks: list[Any], with_derived: bool = True) -> list[dict[str, Any]]:
    artisan_map, section_map = await load_scope_lookups(tasks)
    derived = await derive_progress(tasks) if with_derived else {}
    return [serialize_task(task, artisan_map, section_map, derived) for task in tasks]


def batch_summary(tasks: list[Any], serialized: list[dict[str, Any]]) -> dict[str, Any]:
    """One assignment action rolled back up: who it went to, and how far the group has got.

    ``tasks`` are the raw rows of a single batch (they share a scope) and ``serialized`` the
    matching enriched output, reused so artisan/section names are resolved exactly once.
    """
    head = tasks[0]
    head_payload = serialized[0]
    counts = dict.fromkeys(STATUS_COUNT_KEYS, 0)
    for task in tasks:
        counts[task.status] = counts.get(task.status, 0) + 1

    target = head.targetCount
    # Percent means "of the work handed out": against targetCount when there is one (so a partly
    # filled quota shows up), otherwise simply how many of the assignees finished.
    if target:
        denominator = target * len(tasks)
        numerator = sum(min(task.progressCount or 0, target) for task in tasks)
    else:
        denominator = len(tasks)
        # APPROVED ONLY. A submission is a request, not a completion, and counting it here would
        # make a batch read "5 of 5 done" while nobody had looked at any of it — which is the exact
        # illusion the review state was added to remove. What the submissions deserve is their own
        # number beside this one, so a UI can draw the awaiting slice rather than absorb it.
        numerator = counts["DONE"]

    derived_values = [item.get("derivedCount") for item in serialized]
    known = [value for value in derived_values if value is not None]

    return {
        "batchId": head.batchId,
        # Stable grouping key even for the pre-batch rows, which have no batchId of their own.
        "key": head.batchId or f"task:{head.id}",
        "title": head.title,
        "description": head.description,
        "dueAt": public_encode(head.dueAt),
        "createdAt": public_encode(head.createdAt),
        "createdBy": user_brief(get_value(head, "createdBy")),
        "workshopId": head.workshopId,
        "workshopTitle": head_payload.get("workshopTitle"),
        "recordTypes": head.recordTypes or [],
        "recordTypeLabels": head_payload.get("recordTypeLabels", []),
        "artisans": head_payload.get("artisans", []),
        "sections": head_payload.get("sections", []),
        "targetCount": target,
        "assigneeCount": len(tasks),
        "statusCounts": counts,
        "doneCount": counts["DONE"],
        "openCount": counts["OPEN"] + counts["IN_PROGRESS"],
        # Waiting on the REVIEWER, not on the assignee. Kept out of `openCount` so the admin's
        # "who is behind" ordering does not chase people who have already handed their work in.
        "awaitingReviewCount": counts[SUBMITTED],
        "outstandingCount": sum(counts[key] for key in OUTSTANDING_STATUSES),
        "overdueCount": sum(1 for item in serialized if item.get("isOverdue")),
        "reportedTotal": sum(task.progressCount or 0 for task in tasks),
        "derivedTotal": sum(known) if known else None,
        "percentComplete": round(100 * numerator / denominator) if denominator else 0,
        "assignees": [
            {
                "taskId": task.id,
                "user": user_brief(get_value(task, "assignee")),
                "status": task.status,
                "statusLabel": item.get("statusLabel"),
                "isAwaitingReview": item.get("isAwaitingReview"),
                "progressCount": task.progressCount or 0,
                "derivedCount": item.get("derivedCount"),
                "percentComplete": item.get("percentComplete"),
                "effectivePercent": item.get("effectivePercent"),
                # On a SUBMITTED row this is WHEN IT WAS HANDED IN; on a DONE row it is when the
                # work was finished, which for an approved task is still the submission. It is
                # not re-stamped — see the module docstring on why no second column was added.
                "completedAt": public_encode(task.completedAt),
            }
            for task, item in zip(tasks, serialized, strict=True)
        ],
    }


# ---------------------------------------------------------------------------------------------
# Assignment
# ---------------------------------------------------------------------------------------------


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_task(payload: TaskCreate, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    """Single-assignee create. The scope fields are optional here — a bare "title + assignee" task
    is still valid for ad-hoc work — but anything supplied is validated exactly like a batch."""
    await assert_assignable(current_user, payload.assigneeId)
    scope = await resolve_scope(
        workshop_id=payload.workshopId,
        record_types=payload.recordTypes,
        artisan_ids=payload.artisanIds,
        section_ids=payload.sectionIds,
        target_count=payload.targetCount,
        require_work=False,
    )
    # title_case=False: a task title is an INSTRUCTION ("Record 10 tools for Ramesh Bhai"), not a
    # name, so the name-casing every other write path applies would mangle it into "Record 10 Tools".
    data = clean_data(payload.model_dump(exclude_unset=True), title_case=False)
    data["recordTypes"] = scope.recordTypes
    data["artisanIds"] = scope.artisanIds
    data["sectionIds"] = scope.sectionIds
    data["createdById"] = current_user.id
    created = await db.assignedtask.create(data=data, include=INCLUDE)
    return (await serialize_tasks([created]))[0]


@router.post("/batch", status_code=status.HTTP_201_CREATED)
async def create_task_batch(
    payload: TaskBatchCreate, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Assign one scope to several people at once — THE assignment endpoint.

    Writes one row per assignee, all sharing a generated ``batchId``, so the group stays a single
    manageable unit afterwards. Everything is validated BEFORE the first row is written, so a bad
    assignee or a typo'd artisan id can never leave half a batch behind.
    """
    assignee_ids = dedupe(payload.assigneeIds)
    if not assignee_ids:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="assigneeIds must contain at least one user",
        )

    scope = await resolve_scope(
        workshop_id=payload.workshopId,
        record_types=payload.recordTypes,
        artisan_ids=payload.artisanIds,
        section_ids=payload.sectionIds,
        target_count=payload.targetCount,
        require_work=True,
    )
    # Validate every assignee up front (existence + the rank rule) so the batch is all-or-nothing.
    for assignee_id in assignee_ids:
        await assert_assignable(current_user, assignee_id)

    title = (payload.title or "").strip() or scope_title(scope)
    batch_id = f"batch_{uuid4().hex}"
    base: dict[str, Any] = {
        "title": title,
        "description": payload.description,
        "dueAt": payload.dueAt,
        "workshopId": scope.workshop.id if scope.workshop else None,
        "recordTypes": scope.recordTypes,
        "artisanIds": scope.artisanIds,
        "sectionIds": scope.sectionIds,
        "targetCount": scope.targetCount,
        "batchId": batch_id,
        "createdById": current_user.id,
    }

    created = [
        await db.assignedtask.create(data={**base, "assigneeId": assignee_id}, include=INCLUDE)
        for assignee_id in assignee_ids
    ]
    tasks = await serialize_tasks(created)
    return {
        "batchId": batch_id,
        "title": title,
        "created": len(tasks),
        "batch": batch_summary(created, tasks),
        "tasks": tasks,
    }


# ---------------------------------------------------------------------------------------------
# Reading
# ---------------------------------------------------------------------------------------------


@router.get("")
async def list_tasks(
    current_user: Any = Depends(get_current_user),
    view: str = Query("assigned"),
    statusFilter: str | None = Query(None, alias="status"),
    outstanding: bool = Query(False),
    workshopId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    batchId: str | None = Query(None),
    withDerived: bool = Query(True),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    """Tasks visible to the caller. view=assigned (default) is my to-do list; view=created and
    view=all are the admin's planning views.

    Every item carries its resolved workshop title, artisan names and section codes/titles plus
    both the reported and the derived progress, so a task board renders from this one call. Pass
    ``withDerived=false`` to skip the data-backed counts when only the list itself is needed.

    ``outstanding=true`` IS NOT A CONVENIENCE. It is the only correct way to ask for "the tasks
    still on my screen", because that is THREE statuses now (OPEN, IN_PROGRESS and SUBMITTED) and
    ``status`` takes one. Filtering the three out of an unfiltered page in the client is wrong in a
    way that hides itself: the page is twenty rows deep, so a researcher with twenty-one tasks gets
    a list that silently omits outstanding work — and under-reporting outstanding work is the single
    failure this whole feature exists to prevent.
    """
    if view not in VIEWS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"view must be one of {sorted(VIEWS)}",
        )
    where: dict[str, Any] = {}
    if view == "assigned":
        # Hard-pinned to the caller: "my tasks" can never be pointed at somebody else's list.
        where["assigneeId"] = current_user.id
    else:
        if not is_admin(current_user):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
        if view == "created":
            where["createdById"] = current_user.id
        if assigneeId:
            where["assigneeId"] = assigneeId
    if statusFilter and outstanding:
        # Refused rather than silently resolved. Either order of precedence would be a guess, and
        # a caller asking `status=DONE&outstanding=true` has a bug a quiet answer would hide.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Pass either status or outstanding=true, not both",
        )
    if statusFilter:
        assert_status_value(statusFilter)
        where["status"] = statusFilter
    elif outstanding:
        where["status"] = {"in": sorted(OUTSTANDING_STATUSES)}
    if workshopId:
        where["workshopId"] = workshopId
    if batchId:
        where["batchId"] = batchId

    page, page_size, skip = normalize_pagination(page, pageSize)
    total = await db.assignedtask.count(where=where)
    tasks = await db.assignedtask.find_many(
        where=where,
        skip=skip,
        take=page_size,
        include=INCLUDE,
        order=[{"dueAt": "asc"}, {"createdAt": "desc"}],
    )
    items = await serialize_tasks(tasks, with_derived=withDerived)
    return page_payload(items, total, page, page_size)


@router.get("/batches")
async def list_task_batches(
    current_user: Any = Depends(require_admin),
    view: str = Query("all"),
    workshopId: str | None = Query(None),
    batchId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    statusFilter: str | None = Query(None, alias="status"),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    """Assignments grouped back into the action that created them.

    ``workshopId``/``assigneeId``/``status`` select which batches are SHOWN; the progress reported
    is always for the WHOLE batch, because "3 of 5 done" stops meaning anything if the filter has
    silently dropped two of the five. Tasks written before batching existed — and single-assignee
    creates — come back as one-person batches with ``batchId: null``.
    """
    if view not in {"created", "all"}:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="view must be one of ['all', 'created']",
        )
    where: dict[str, Any] = {}
    if view == "created":
        where["createdById"] = current_user.id
    if workshopId:
        where["workshopId"] = workshopId
    if batchId:
        where["batchId"] = batchId
    if statusFilter:
        assert_status_value(statusFilter)
    if assigneeId or statusFilter:
        # Two passes: pick the batches by matching MEMBER, then re-load those batches in full.
        member_where = dict(where)
        if assigneeId:
            member_where["assigneeId"] = assigneeId
        if statusFilter:
            member_where["status"] = statusFilter
        matches = await db.assignedtask.find_many(
            where=member_where, take=ROLLUP_SCAN_LIMIT, order={"createdAt": "desc"}
        )
        batch_ids = sorted({task.batchId for task in matches if task.batchId})
        loose_ids = sorted({task.id for task in matches if not task.batchId})
        if not batch_ids and not loose_ids:
            return page_payload([], 0, page, pageSize)
        where = {"OR": [{"batchId": {"in": batch_ids}}, {"id": {"in": loose_ids}}]}

    rows = await db.assignedtask.find_many(
        where=where, include=INCLUDE, take=ROLLUP_SCAN_LIMIT, order={"createdAt": "desc"}
    )
    grouped: dict[str, list[Any]] = {}
    for task in rows:
        grouped.setdefault(task.batchId or f"task:{task.id}", []).append(task)

    # Newest batch first, keyed off its newest member so editing a batch does not reshuffle the list.
    keys = sorted(grouped, key=lambda key: max(t.createdAt for t in grouped[key]), reverse=True)
    total = len(keys)
    page, page_size, skip = normalize_pagination(page, pageSize)
    window = keys[skip : skip + page_size]

    # Enrich only the page's members — one lookup pass for all of them, not one per batch.
    visible = [task for key in window for task in grouped[key]]
    serialized = {item["id"]: item for item in await serialize_tasks(visible)}
    items = [
        batch_summary(grouped[key], [serialized[task.id] for task in grouped[key]]) for key in window
    ]
    return page_payload(items, total, page, page_size)


@router.get("/progress")
async def task_progress(
    _: Any = Depends(require_admin),
    workshopId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    includeFinished: bool = Query(True),
) -> dict[str, Any]:
    """Per-assignee accountability rollup: who has what, and how far along they actually are.

    Reported progress sits next to derived progress on every line, so "says 8, repository says 2"
    is visible at a glance. Scope it to a workshop with ``workshopId`` (the normal use); leave that
    off for an organisation-wide picture.
    """
    workshop = None
    if workshopId:
        workshop = await db.workshop.find_unique(where={"id": workshopId})
        if not workshop:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop not found")

    where: dict[str, Any] = {}
    if workshopId:
        where["workshopId"] = workshopId
    if assigneeId:
        where["assigneeId"] = assigneeId
    if not includeFinished:
        # OUTSTANDING, not LIVE. "Hide the finished ones" must not hide the ones waiting on the
        # admin reading this very screen — a submitted task is the opposite of finished business.
        where["status"] = {"in": sorted(OUTSTANDING_STATUSES)}

    rows = await db.assignedtask.find_many(
        where=where, include=INCLUDE, take=ROLLUP_SCAN_LIMIT, order={"createdAt": "desc"}
    )
    serialized = {item["id"]: item for item in await serialize_tasks(rows)}

    by_user: dict[str, list[Any]] = {}
    for task in rows:
        by_user.setdefault(task.assigneeId, []).append(task)

    assignees: list[dict[str, Any]] = []
    for tasks in by_user.values():
        items = [serialized[task.id] for task in tasks]
        counts = dict.fromkeys(STATUS_COUNT_KEYS, 0)
        for task in tasks:
            counts[task.status] = counts.get(task.status, 0) + 1
        target_total = sum(task.targetCount or 0 for task in tasks)
        reported_total = sum(
            min(task.progressCount or 0, task.targetCount)
            if task.targetCount
            else (task.progressCount or 0)
            for task in tasks
        )
        known = [item["derivedCount"] for item in items if item.get("derivedCount") is not None]
        # With quotas the honest measure is quota fill; without them it is tasks finished.
        if target_total:
            percent = round(100 * min(reported_total, target_total) / target_total)
        else:
            percent = round(100 * counts["DONE"] / len(tasks)) if tasks else 0
        assignees.append(
            {
                "user": user_brief(get_value(tasks[0], "assignee")),
                "taskCount": len(tasks),
                "statusCounts": counts,
                "openCount": counts["OPEN"] + counts["IN_PROGRESS"],
                "awaitingReviewCount": counts[SUBMITTED],
                "outstandingCount": sum(counts[key] for key in OUTSTANDING_STATUSES),
                "overdueCount": sum(1 for item in items if item.get("isOverdue")),
                "targetTotal": target_total or None,
                "reportedTotal": reported_total,
                "derivedTotal": sum(known) if known else None,
                "percentComplete": percent,
                "tasks": items,
            }
        )
    # Busiest-outstanding first: the person with the most unfinished work is the one to chase.
    assignees.sort(
        key=lambda row: (-row["openCount"], -row["taskCount"], (row["user"] or {}).get("name") or "")
    )

    return {
        "workshopId": workshopId,
        "workshopTitle": get_value(workshop, "title"),
        "assigneeCount": len(assignees),
        "taskCount": len(rows),
        "doneCount": sum(1 for task in rows if task.status == "DONE"),
        "openCount": sum(1 for task in rows if task.status in LIVE_STATUSES),
        # THE ADMIN'S OWN QUEUE. Every one of these is a researcher waiting on a decision from
        # whoever is looking at this board, which is the one number on it that is about the reader.
        "awaitingReviewCount": sum(1 for task in rows if task.status in REVIEW_STATUSES),
        "outstandingCount": sum(1 for task in rows if task.status in OUTSTANDING_STATUSES),
        "overdueCount": sum(1 for item in serialized.values() if item.get("isOverdue")),
        # True when the scan window was hit, so the UI can say "narrow this down" instead of
        # quietly presenting a partial rollup as the whole truth.
        "truncated": len(rows) >= ROLLUP_SCAN_LIMIT,
        "assignees": assignees,
    }


@router.get("/options")
async def task_options(
    current_user: Any = Depends(require_admin),
    workshopId: str | None = Query(None),
) -> dict[str, Any]:
    """Everything the assignment dialog needs, in one call: the record-type catalogue, the users
    this admin is allowed to assign to, the workshops, the artisans (narrowed to the workshop when
    one is given) and the questionnaire sections."""
    users = await db.user.find_many(order={"name": "asc"}, take=500)
    assignable = [
        user_brief(user)
        for user in users
        if user.id != current_user.id
        and (is_master_admin(current_user) or role_rank(user) < role_rank(current_user))
    ]

    workshops = await db.workshop.find_many(order={"date": "desc"}, take=200)
    artisan_where: dict[str, Any] = {}
    if workshopId:
        # Both readings of "at this workshop" — the explicit column and the join — so artisans
        # linked only through WorkshopArtisan still show up in the picker.
        artisan_where["OR"] = [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]
    artisans = await db.artisan.find_many(where=artisan_where, order={"name": "asc"}, take=500)
    # THE SECTIONS THIS DIALOG MAY OFFER. Narrowed to the chosen workshop's instrument, because
    # `resolve_scope` will 422 any section from another one — and a dialog that offers a choice the
    # save then refuses reads as a broken dialog, not as a rule. With no workshop chosen the list
    # spans every instrument and each entry carries its instrument so the picker can say which.
    section_where: dict[str, Any] = {"isActive": True}
    if workshopId:
        workshop_row = await db.workshop.find_unique(where={"id": workshopId})
        section_where["questionnaireId"] = (
            get_value(workshop_row, "questionnaireId") if workshop_row else None
        ) or await default_questionnaire_id()
    sections = await db.questionnairesection.find_many(
        where=section_where, order={"sortOrder": "asc"}
    )
    instruments = await db.questionnaire.find_many()
    instrument_titles = {row.id: row.title for row in instruments}

    return {
        "recordTypes": [
            {
                "value": kind,
                "label": RECORD_TYPE_LABELS[kind][0],
                "pluralLabel": RECORD_TYPE_LABELS[kind][1],
            }
            for kind in RECORD_TYPE_ORDER
        ],
        "assignees": assignable,
        "workshops": [
            {"id": w.id, "title": w.title, "place": w.place, "date": public_encode(w.date)}
            for w in workshops
        ],
        "artisans": [{"id": a.id, "name": a.name, "place": a.place} for a in artisans],
        "sections": [
            {
                "id": s.id,
                "code": s.code,
                "title": s.title,
                "sortOrder": s.sortOrder,
                "questionnaireId": s.questionnaireId,
                "questionnaireTitle": instrument_titles.get(s.questionnaireId),
            }
            for s in sections
        ],
    }


@router.get("/summary")
async def my_task_summary(
    current_user: Any = Depends(get_current_user),
    workshopId: str | None = Query(None),
) -> dict[str, Any]:
    """MY workload in one object — what the full-width card at the top of the assignee's screen is
    drawn from. Always about the caller; there is no ``assigneeId`` to point it elsewhere.

    WHY THIS IS NOT A FIELD ON ``GET /tasks?view=assigned``. That endpoint is PAGED. A card saying
    "4 tasks remaining" computed from a page of twenty is wrong for anyone with twenty-one tasks,
    and wrong in the direction that matters: it under-reports outstanding work, which is the one
    number the card exists to make impossible to miss. This scans the caller's whole (bounded) list
    and says ``truncated`` when it could not.

    WHY IT IS NOT ``GET /tasks/progress``. That route is ``require_admin`` and rolls up EVERYBODY.
    A researcher cannot call it and must not be able to — their own summary is not an accountability
    board, and handing them one to read their own line off would hand them everyone else's too.

    THE BUDGET. One ``find_many`` plus whatever ``derive_progress`` needs, and ``derive_progress``
    already declines above :data:`DERIVED_TASK_LIMIT` rows (``derivedCount: null``), which this
    reports as ``derivationSkipped`` instead of silently showing a self-reported bar labelled as
    measured. No per-task round trips are added here.
    """
    where: dict[str, Any] = {"assigneeId": current_user.id}
    if workshopId:
        where["workshopId"] = workshopId
    rows = await db.assignedtask.find_many(
        where=where, take=ROLLUP_SCAN_LIMIT, order=[{"dueAt": "asc"}, {"createdAt": "desc"}]
    )
    derived = await derive_progress(rows)

    now = datetime.now(UTC)
    soon = now + timedelta(hours=DUE_SOON_HOURS)
    counts = dict.fromkeys(STATUS_COUNT_KEYS, 0)
    percents: list[int] = []
    measured = 0
    overdue = 0
    due_soon = 0
    next_due: datetime | None = None

    for task in rows:
        counts[task.status] = counts.get(task.status, 0) + 1
        if task.status == "CANCELLED":
            # A withdrawn assignment is not work. Leaving it in the denominator would let an admin
            # tidying up their own mis-assignments quietly drag somebody's bar down.
            continue

        numbers = derived.get(task.id) or {}
        effective = effective_progress(task, numbers)
        if effective["percent"] is None:
            # UNMEASURABLE, NOT ZERO — but a card that averages percentages needs a number for
            # every task or the bar silently describes a subset. The status is the honest stand-in:
            # handed in or approved reads 100, anything else reads 0. `measuredCount` says how many
            # of the figures behind the bar were actually counted from the repository, so the card
            # can caption it instead of implying the whole thing was derived.
            percents.append(100 if task.status in {"DONE", SUBMITTED} else 0)
        else:
            percents.append(effective["percent"])
            if effective["source"] == "derived":
                measured += 1

        if is_overdue(task):
            overdue += 1
            continue
        if task.status not in OUTSTANDING_STATUSES or not task.dueAt:
            continue
        # DUE SOON AND NEXT DUE ARE BOTH ABOUT WORK THAT IS STILL COMING. An already-overdue task is
        # counted once, by `overdueCount`, and deliberately kept out of `nextDueAt` — a card whose
        # "next due" is a date in the past is reporting the same emergency twice under two headings
        # while hiding the genuine next deadline behind it.
        due = task.dueAt if task.dueAt.tzinfo else task.dueAt.replace(tzinfo=UTC)
        if due <= soon:
            due_soon += 1
        if next_due is None or due < next_due:
            next_due = due

    return {
        "assignee": user_brief(current_user),
        "workshopId": workshopId,
        "taskCount": len(rows),
        "statusCounts": counts,
        # REMAINING = what is still on YOU. The number the card leads with, and the reason
        # SUBMITTED is not in it: a researcher who has handed everything in has nothing left to do,
        # and telling them they still have four tasks would be telling them to redo them.
        "remainingCount": counts["OPEN"] + counts["IN_PROGRESS"],
        # AWAITING REVIEW = handed in, not yet agreed. Still on the screen (the owner's "it should
        # not disappear until approved") but visibly not "to do".
        "awaitingReviewCount": counts[SUBMITTED],
        # Everything that has not been approved yet — remaining + awaiting review. This is the count
        # of cards the assignee will actually see in their list.
        "outstandingCount": sum(counts[key] for key in OUTSTANDING_STATUSES),
        "approvedCount": counts["DONE"],
        "cancelledCount": counts["CANCELLED"],
        "overdueCount": overdue,
        "dueSoonCount": due_soon,
        "nextDueAt": public_encode(next_due),
        "percentComplete": round(sum(percents) / len(percents)) if percents else 0,
        # How many of the tasks behind that percentage were MEASURED from the repository rather
        # than inferred from a status or a typed figure. A card claiming automatic progress has to
        # be able to say how much of it was automatic.
        "measuredCount": measured,
        "derivationSkipped": len(rows) > DERIVED_TASK_LIMIT,
        "truncated": len(rows) >= ROLLUP_SCAN_LIMIT,
    }


@router.get("/{task_id}")
async def get_task(task_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """One task, enriched exactly like a list item. Visible to the assignee, the creator and admins."""
    task = await require_task(task_id)
    if (
        task.assigneeId != current_user.id
        and task.createdById != current_user.id
        and not is_admin(current_user)
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the assignee, the creator, or an admin can view this task",
        )
    return (await serialize_tasks([task]))[0]


# ---------------------------------------------------------------------------------------------
# Mutation
# ---------------------------------------------------------------------------------------------


@router.patch("/{task_id}")
async def update_task(
    task_id: str,
    payload: TaskUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The creator or an admin may edit everything (scope, CANCELLED, reassignment included); the
    assignee may only move the status between OPEN, IN_PROGRESS and SUBMITTED, and report
    ``progressCount``.

    ``DONE`` IS NOT THE ASSIGNEE'S TO WRITE. Their "Mark done" lands on :data:`SUBMITTED`; the
    manager test below is what turns that into ``DONE``. The admin override is untouched by this —
    it goes down the manager branch exactly as it did, which is why marking somebody else's task
    done still works in one PATCH with no new route and no new permission.
    """
    task = await require_task(task_id)
    data = payload.model_dump(exclude_unset=True)
    # Explicit nulls only make sense for the nullable/clearable columns (description, dueAt,
    # recordType, recordId, workshopId, targetCount). For the required ones — and for the list
    # columns, where an empty LIST is how you clear — a null means "no change", not "clear".
    for field_name in (
        "title",
        "assigneeId",
        "status",
        "progressCount",
        "recordTypes",
        "artisanIds",
        "sectionIds",
    ):
        if field_name in data and data[field_name] is None:
            data.pop(field_name)
    new_status = data.get("status")
    if new_status is not None:
        assert_status_value(new_status)

    is_manager = task.createdById == current_user.id or is_admin(current_user)
    if is_manager:
        if data.get("assigneeId") and data["assigneeId"] != task.assigneeId:
            await assert_assignable(current_user, data["assigneeId"])
    elif task.assigneeId == current_user.id:
        # The assignee owns two things and only two: where the task stands, and how much of it is
        # done. Scope, due date and reassignment stay with whoever handed the work out.
        extra_fields = set(data) - {"status", "progressCount"}
        if extra_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Assignees can only update the task status and report progressCount",
            )
        if new_status == "CANCELLED":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the task creator or an admin can cancel a task",
            )
        if task.status == "DONE" and new_status is not None:
            # AN APPROVAL IS SOMEBODY ELSE'S DECISION AND THE ASSIGNEE MAY NOT UNDO IT. Before the
            # review state this was harmless — DONE was the assignee's own declaration, so letting
            # them take it back was letting them correct themselves. Now DONE means "an admin
            # agreed", and without this clause the person whose work was approved could PATCH it
            # straight back to IN_PROGRESS and erase the approval (`completedAt` cleared with it),
            # which makes the whole approval step decorative. Reopening is the manager's, the same
            # way approving is.
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the task creator or an admin can reopen an approved task",
            )
        if new_status == "DONE":
            # THE OWNER'S RULE: "the admin/master admin need to approve the work as done when the
            # researcher has marked it as done". Rewritten rather than REFUSED, for two reasons.
            #
            # 1. THE FIELD APP IN PEOPLE'S HANDS ALREADY SENDS "DONE". Android updates over the air
            #    and researchers are offline for days; a 422 would brick "Mark done" on every build
            #    older than the one shipping with this change, out where nobody can fix it. A
            #    rewrite makes those builds correct by default — they send their old word, the row
            #    lands in review, and the response hands back `status: "SUBMITTED"` so even an old
            #    client is not lied to about where the task ended up.
            # 2. THERE IS NOTHING AMBIGUOUS TO ASK ABOUT. An assignee sending DONE means exactly
            #    "I have finished this". The only thing they were never entitled to is the second
            #    half of that sentence — that somebody with authority agrees.
            new_status = data["status"] = SUBMITTED
    else:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the assignee, the creator, or an admin can update this task",
        )

    if {"workshopId", "recordTypes", "artisanIds", "sectionIds", "targetCount"} & set(data):
        # Re-validate the MERGED scope, so a patch touching one dimension cannot leave the row
        # pointing at a deleted artisan or empty of work. Tasks created without a scope (the legacy
        # single-record path) are allowed to stay that way.
        scope = await resolve_scope(
            workshop_id=data.get("workshopId", task.workshopId),
            record_types=data.get("recordTypes", task.recordTypes),
            artisan_ids=data.get("artisanIds", task.artisanIds),
            section_ids=data.get("sectionIds", task.sectionIds),
            target_count=data.get("targetCount", task.targetCount),
            require_work=bool(task.recordTypes or task.sectionIds),
        )
        # Scalar-list columns take an explicit {"set": [...]} on update, unlike create.
        if "recordTypes" in data:
            data["recordTypes"] = {"set": scope.recordTypes}
        if "artisanIds" in data:
            data["artisanIds"] = {"set": scope.artisanIds}
        if "sectionIds" in data:
            data["sectionIds"] = {"set": scope.sectionIds}

    target = data["targetCount"] if "targetCount" in data else task.targetCount
    if "progressCount" in data:
        # Never let a quota read as over-run: "8 of 5 done" is not progress, it is a bad client.
        reported = max(0, int(data["progressCount"]))
        data["progressCount"] = min(reported, target) if target else reported

    if new_status is not None:
        if new_status in {SUBMITTED, "DONE"}:
            if task.status not in {SUBMITTED, "DONE"}:
                # STAMPED AT THE FIRST DECLARATION THAT THE WORK IS FINISHED, and deliberately NOT
                # re-stamped when an admin later approves it. `completedAt` therefore keeps meaning
                # "when the work was finished", so `completedAt > dueAt` stays a fact about the
                # person who did it. Move the stamp to approval instead and a researcher who handed
                # in three days early is recorded as late whenever the reviewer is slow — the board
                # would be publishing the reviewer's backlog as the researcher's lateness.
                data["completedAt"] = datetime.now(UTC)
            # Finishing a quota task means the quota was met; without this the rollups would go on
            # showing "0 of 10" for everyone who never touched the progress field. Applies on the
            # SUBMIT, not on the approval, because the submission is the claim and this field is
            # the claim's number — an approver comparing it against `derivedCount` needs it already
            # filled in at the moment they are asked to decide.
            if target and "progressCount" not in data and (task.progressCount or 0) < target:
                data["progressCount"] = target
        else:
            data["completedAt"] = None

    if not data:
        return (await serialize_tasks([task]))[0]
    updated = await db.assignedtask.update(where={"id": task_id}, data=data, include=INCLUDE)
    return (await serialize_tasks([updated]))[0]


@router.delete("/batch/{batch_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task_batch(batch_id: str, current_user: Any = Depends(require_admin)) -> None:
    """Withdraw a whole assignment in one action. Only the admin who sent it (or the master admin)
    may unsend it — otherwise one admin could quietly delete another's assignments."""
    tasks = await db.assignedtask.find_many(where={"batchId": batch_id})
    if not tasks:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Batch not found")
    if not is_master_admin(current_user) and any(
        task.createdById != current_user.id for task in tasks
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the admin who created this batch (or the master admin) can delete it",
        )
    await db.assignedtask.delete_many(where={"batchId": batch_id})


@router.delete("/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task(task_id: str, current_user: Any = Depends(get_current_user)) -> None:
    task = await require_task(task_id)
    if task.createdById != current_user.id and not is_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the task creator or an admin can delete a task",
        )
    await db.assignedtask.delete(where={"id": task_id})
