"""Task assignment — who has to record what, where, and how far along they actually are.

THE MODEL
---------
One ``AssignedTask`` row is always exactly ONE assignee. Handing the same scope to N people writes
N rows sharing a ``batchId``, so an admin manages "record tools for these 5 researchers" as a single
unit (``GET /tasks/batches``) while each person still owns, sees and reports progress on their own
row. That is why there is no many-to-many join here: the row IS one person's to-do item.

A task's SCOPE is five orthogonal dimensions (see ``app.schemas.tasks``):
``workshopId`` x ``recordTypes[]`` x ``artisanIds[]`` x ``sectionIds[]`` x ``targetCount``.
Everything an admin needs to express falls out of combining them — ``recordTypes=[product]`` +
``artisanIds=[A,B,C]`` is "record products for artisans A, B and C"; ``sectionIds=[F]`` +
``artisanIds=[A,B]`` is "answer section F for artisans A and B". An empty scope (no record types
AND no sections) is rejected on create: a task with no work in it is a bug, not an empty state.

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
"""
import asyncio
import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime
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
from app.services.records import clean_data, public_encode

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/tasks", tags=["tasks"])

TASK_STATUSES = {"OPEN", "IN_PROGRESS", "DONE", "CANCELLED"}
# Statuses that still represent outstanding work — the "open"/"overdue" counters on the rollups.
LIVE_STATUSES = {"OPEN", "IN_PROGRESS"}

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
    """The assignee must exist and rank strictly below the assigner — except the master admin,
    who may assign work to anyone but themselves."""
    assignee = await db.user.find_unique(where={"id": assignee_id})
    if not assignee:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Assignee not found")
    if assignee.id == get_value(assigner, "id"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="You cannot assign a task to yourself",
        )
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


async def _count_sections(
    assignee_id: str, workshop_id: str | None, artisan_ids: list[str], section_ids: list[str]
) -> int:
    """Questionnaire coverage this assignee has actually produced.

    The unit deliberately follows how the task was scoped, so the number stays comparable to a
    target:
      - artisans named -> distinct (artisan, section) PAIRS answered; target = artisans x sections
      - "all artisans" -> distinct SECTIONS answered; target = sections (there is no honest
        denominator for "everyone", so counting pairs would be a percentage of nothing)
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
    for response in responses:
        if is_empty_value(response.answerText):
            continue
        section_id = get_value(response.question, "sectionId")
        if not section_id or section_id not in section_ids:
            continue
        if not scope:
            plain_sections.add(section_id)
            continue
        for link in get_value(response.interview, "artisans") or []:
            if link.artisanId in scope:
                pairs.add((link.artisanId, section_id))
    return len(pairs) if scope else len(plain_sections)


def _derived_target(task: Any) -> int | None:
    """The denominator ``derivedCount`` should be read against, or ``None`` when the scope has no
    honest one (record types with no ``targetCount`` means "as many as apply")."""
    total = 0
    if task.recordTypes:
        if not task.targetCount:
            return None
        total += task.targetCount
    if task.sectionIds:
        total += len(task.sectionIds) * max(1, len(task.artisanIds or []))
    return total or None


def _record_key(task: Any, kind: str, artisan_key: tuple) -> tuple:
    return ("record", kind, task.assigneeId, task.workshopId, artisan_key)


def _section_key(task: Any, artisan_key: tuple) -> tuple:
    return ("sections", task.assigneeId, task.workshopId, artisan_key, tuple(sorted(task.sectionIds)))


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
    if not jobs:
        return {}

    semaphore = asyncio.Semaphore(DERIVED_CONCURRENCY)

    async def run(key: tuple) -> int:
        async with semaphore:
            if key[0] == "record":
                return await _count_records(key[1], key[2], key[3], list(key[4]))
            return await _count_sections(key[1], key[2], list(key[3]), list(key[4]))

    keys = list(jobs)
    results = await asyncio.gather(*(run(key) for key in keys), return_exceptions=True)
    counts: dict[tuple, int | None] = {}
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
        total: int | None = 0
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
            if value is None:
                total = None
            else:
                breakdown["sections"] = value
                if total is not None:
                    total += value
        derived[task.id] = {
            "derivedCount": total,
            "derivedTarget": _derived_target(task),
            "derivedBreakdown": breakdown,
        }
    return derived


# ---------------------------------------------------------------------------------------------
# Serialisation
# ---------------------------------------------------------------------------------------------


def progress_percent(task: Any) -> int | None:
    """Self-reported completion, 0-100. ``None`` when the task is open-ended (no ``targetCount``)
    and unfinished — inventing a number there would be a lie the accountability view then acts on."""
    task_status = get_value(task, "status")
    if task_status == "DONE":
        return 100
    if task_status == "CANCELLED":
        return 0
    target = get_value(task, "targetCount")
    if target:
        reported = min(get_value(task, "progressCount") or 0, target)
        return min(100, round(100 * reported / target))
    return None


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
    counts = dict.fromkeys(("OPEN", "IN_PROGRESS", "DONE", "CANCELLED"), 0)
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
        "overdueCount": sum(1 for item in serialized if item.get("isOverdue")),
        "reportedTotal": sum(task.progressCount or 0 for task in tasks),
        "derivedTotal": sum(known) if known else None,
        "percentComplete": round(100 * numerator / denominator) if denominator else 0,
        "assignees": [
            {
                "taskId": task.id,
                "user": user_brief(get_value(task, "assignee")),
                "status": task.status,
                "progressCount": task.progressCount or 0,
                "derivedCount": item.get("derivedCount"),
                "percentComplete": item.get("percentComplete"),
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
    if statusFilter:
        assert_status_value(statusFilter)
        where["status"] = statusFilter
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
        where["status"] = {"in": sorted(LIVE_STATUSES)}

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
        counts = dict.fromkeys(("OPEN", "IN_PROGRESS", "DONE", "CANCELLED"), 0)
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
    sections = await db.questionnairesection.find_many(
        where={"isActive": True}, order={"sortOrder": "asc"}
    )

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
            {"id": s.id, "code": s.code, "title": s.title, "sortOrder": s.sortOrder}
            for s in sections
        ],
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
    assignee may only move the status between OPEN, IN_PROGRESS and DONE, and report
    ``progressCount``."""
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
        if new_status == "DONE":
            if task.status != "DONE":
                data["completedAt"] = datetime.now(UTC)
            # Finishing a quota task means the quota was met; without this the rollups would go on
            # showing "DONE — 0 of 10" for everyone who never touched the progress field.
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
