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
    assert_can_delete,
    get_current_user,
    get_value,
    is_admin,
    is_content_admin,
    is_empty_value,
    is_master_admin,
    require_content_admin,
    role_rank,
    role_value,
)
from app.schemas.tasks import TaskBatchCreate, TaskCreate, TaskUpdate
from app.services.email_outbox import notify_task_change
from app.services.pagination import normalize_pagination, page_payload
from app.services.questionnaire_instruments import (
    default_questionnaire_id,
    require_questionnaire,
)
from app.services.record_filters import artisan_workshop_clause
from app.services.concurrency import gather_reads
from app.services.records import clean_data, public_encode
from app.services.task_status import LIVE_STATUSES, SUBMITTED

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/tasks", tags=["tasks"])

TASK_STATUSES = {"OPEN", "IN_PROGRESS", SUBMITTED, "DONE", "CANCELLED"}
STATUS_COUNT_KEYS = ("OPEN", "IN_PROGRESS", SUBMITTED, "DONE", "CANCELLED")

REVIEW_STATUSES = {SUBMITTED}
OUTSTANDING_STATUSES = LIVE_STATUSES | REVIEW_STATUSES

STATUS_LABELS = {
    "OPEN": "To do",
    "IN_PROGRESS": "In progress",
    SUBMITTED: "Under review",
    "DONE": "Approved",
    "CANCELLED": "Cancelled",
}

INCLUDE = {"assignee": True, "createdBy": True, "workshop": True}

VIEWS = {"assigned", "created", "all"}

RECORD_TYPE_ORDER = ["artisan", "product", "process", "tool", "questionnaire", "media"]
RECORD_TYPES = set(RECORD_TYPE_ORDER)

RECORD_TYPE_LABELS: dict[str, tuple[str, str]] = {
    "artisan": ("artisan", "artisans"),
    "product": ("product", "products"),
    "process": ("process", "processes"),
    "tool": ("tool", "tools"),
    "questionnaire": ("questionnaire interview", "questionnaire interviews"),
    "media": ("media file", "media files"),
}

DERIVED_TASK_LIMIT = 150
DERIVED_CONCURRENCY = 8

ROLLUP_SCAN_LIMIT = 2000

DUE_SOON_HOURS = 48



async def require_task(task_id: str) -> Any:
    task = await db.assignedtask.find_unique(where={"id": task_id}, include=INCLUDE)
    if not task:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found")
    return task


def assert_status_value(value: str) -> None:
    if value not in TASK_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"status must be one of {sorted(TASK_STATUSES)}",
        )


async def assert_assignable(assigner: Any, assignee_id: str) -> Any:
    return check_assignable(assigner, await db.user.find_unique(where={"id": assignee_id}))


def check_assignable(assigner: Any, assignee: Any | None) -> Any:
    if not assignee:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Assignee not found")
    if assignee.id == get_value(assigner, "id"):
        if not is_admin(assigner):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="You cannot assign a task to yourself",
            )
        return assignee
    if not is_master_admin(assigner) and role_rank(assignee) >= role_rank(assigner):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only assign tasks to users below your own tier",
        )
    return assignee


def dedupe(values: list[str] | None) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for value in values or []:
        clean = (value or "").strip()
        if clean and clean not in seen:
            seen.add(clean)
            out.append(clean)
    return out


def normalize_record_types(values: list[str] | None) -> list[str]:
    requested = {value.strip().lower() for value in (values or []) if value and value.strip()}
    unknown = sorted(requested - RECORD_TYPES)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Unknown recordTypes {unknown}; expected any of {RECORD_TYPE_ORDER}",
        )
    return [kind for kind in RECORD_TYPE_ORDER if kind in requested]


@dataclass(slots=True)
class ResolvedScope:
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
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"Unknown sectionIds: {missing}",
            )

    if sections:
        instrument_ids = {section.questionnaireId for section in sections}
        if len(instrument_ids) > 1:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    "sectionIds must all belong to one questionnaire; these span "
                    f"{len(instrument_ids)}."
                ),
            )
        if workshop is not None:
            expected = get_value(workshop, "questionnaireId") or await default_questionnaire_id()
            wrong = sorted(s.code for s in sections if s.questionnaireId != expected)
            if wrong:
                instrument = await require_questionnaire(expected)
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                    detail=(
                        f"This workshop uses “{instrument.title}”. These sections belong to a "
                        f"different questionnaire and cannot be assigned here: {wrong}."
                    ),
                )

    if require_work and not types and not sections:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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



def _and_list(items: list[str]) -> str:
    if not items:
        return ""
    if len(items) == 1:
        return items[0]
    return f"{', '.join(items[:-1])} and {items[-1]}"


def scope_title(scope: ResolvedScope) -> str:
    parts: list[str] = []
    if scope.recordTypes:
        plural = scope.targetCount != 1
        labels = [RECORD_TYPE_LABELS[kind][1 if plural else 0] for kind in scope.recordTypes]
        count = f"{scope.targetCount} " if scope.targetCount else ""
        parts.append(f"Record {count}{_and_list(labels)}")
    if scope.sections:
        codes = ", ".join(section.code for section in scope.sections)
        noun = "section" if len(scope.sections) == 1 else "sections"
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



def _record_scope(
    kind: str, assignee_id: str, workshop_id: str | None, artisan_ids: list[str]
) -> tuple[Any, dict[str, Any]] | None:
    in_artisans: dict[str, Any] | None = {"in": artisan_ids} if artisan_ids else None

    if kind == "artisan":
        where: dict[str, Any] = {"createdById": assignee_id}
        if workshop_id:
            where["OR"] = [
                {"workshopId": workshop_id},
                {"workshops": {"some": {"workshopId": workshop_id}}},
            ]
        if in_artisans:
            where["id"] = in_artisans
        return db.artisan, where

    if kind in {"product", "tool"}:
        delegate = db.productdocumentation if kind == "product" else db.tooldocumentation
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisanId"] = in_artisans
        return delegate, where

    if kind == "process":
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["product"] = {"is": {"artisanId": in_artisans}}
        return db.process, where

    if kind == "media":
        where = {"uploadedById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisanId"] = in_artisans
        return db.mediafile, where

    if kind == "questionnaire":
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisans"] = {"some": {"artisanId": in_artisans}}
        return db.questionnaireinterview, where

    return None


async def _count_records(
    kind: str, assignee_id: str, workshop_id: str | None, artisan_ids: list[str]
) -> int:
    scoped = _record_scope(kind, assignee_id, workshop_id, artisan_ids)
    if scoped is None:
        return 0
    delegate, where = scoped
    return await delegate.count(where=where)


async def _count_workshop_artisans(workshop_id: str) -> int:
    return await db.artisan.count(where=artisan_workshop_clause([workshop_id], False))


async def _count_sections(
    assignee_id: str, workshop_id: str | None, artisan_ids: list[str], section_ids: list[str]
) -> tuple[int, int, int]:
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
            unlinked.add(section_id)
            continue
        for link in links:
            if not scope or link.artisanId in scope:
                pairs.add((link.artisanId, section_id))
    from app.services import questionnaire_readers

    for subject_key, section_id in await questionnaire_readers.covered_pairs(assignee_id, section_ids, workshop_id=workshop_id):
        plain_sections.add(section_id)
        if subject_key.startswith("A:") and (not scope or subject_key[2:] in scope):
            pairs.add((subject_key[2:], section_id))
    return len(pairs), len(plain_sections), len(unlinked)


def _section_target(task: Any, roster: int | None = None) -> int:
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
    return ("sections", task.assigneeId, task.workshopId, artisan_key, tuple(sorted(task.sectionIds)))


def _roster_key(task: Any) -> tuple:
    return ("roster", task.workshopId)


def _needs_roster(task: Any) -> bool:
    return bool(task.sectionIds) and not (task.artisanIds or []) and bool(task.workshopId)


async def derive_progress(tasks: list[Any]) -> dict[str, dict[str, Any]]:
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
                uses_pairs = bool(task.artisanIds) or bool(roster and roster > 0)
                covered = pairs if uses_pairs else plain
                breakdown["sections"] = covered
                if uses_pairs and unlinked:
                    breakdown["unlinkedSections"] = unlinked
                if total is not None:
                    total += covered
        derived[task.id] = {
            "derivedCount": total,
            "derivedTarget": _derived_target(task, roster),
            "derivedBreakdown": breakdown,
            "derivedArtisanCount": roster,
        }
    return derived



def progress_percent(task: Any) -> int | None:
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
    count = numbers.get("derivedCount")
    target = numbers.get("derivedTarget")
    if count is None or not target:
        return None
    return min(100, round(100 * min(count, target) / target))


def _progress_unit(task: Any, numbers: dict[str, Any]) -> str:
    breakdown = numbers.get("derivedBreakdown") or {}
    has_sections = "sections" in breakdown
    kinds = [key for key in breakdown if key in RECORD_TYPES]
    if has_sections and not kinds:
        per_artisan = bool(task.artisanIds) or bool(numbers.get("derivedArtisanCount"))
        return "artisan sections" if per_artisan else "sections"
    if kinds and not has_sections:
        if len(kinds) == 1:
            return RECORD_TYPE_LABELS[kinds[0]][1]
        return "records"
    return "items"


def effective_progress(task: Any, numbers: dict[str, Any]) -> dict[str, Any]:
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
    if due.tzinfo is None:
        due = due.replace(tzinfo=UTC)
    return due < datetime.now(UTC)


async def load_scope_lookups(tasks: list[Any]) -> tuple[dict[str, Any], dict[str, Any]]:
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

    task_status = get_value(task, "status")
    payload["statusLabel"] = STATUS_LABELS.get(task_status, task_status)
    payload["isAwaitingReview"] = task_status == SUBMITTED
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
    head = tasks[0]
    head_payload = serialized[0]
    counts = dict.fromkeys(STATUS_COUNT_KEYS, 0)
    for task in tasks:
        counts[task.status] = counts.get(task.status, 0) + 1

    target = head.targetCount
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
                "completedAt": public_encode(task.completedAt),
            }
            for task, item in zip(tasks, serialized, strict=True)
        ],
    }



@router.post("", status_code=status.HTTP_201_CREATED)
async def create_task(payload: TaskCreate, current_user: Any = Depends(require_content_admin)) -> dict[str, Any]:
    await assert_assignable(current_user, payload.assigneeId)
    scope = await resolve_scope(
        workshop_id=payload.workshopId,
        record_types=payload.recordTypes,
        artisan_ids=payload.artisanIds,
        section_ids=payload.sectionIds,
        target_count=payload.targetCount,
        require_work=False,
    )
    data = clean_data(payload.model_dump(exclude_unset=True), title_case=False)
    data["recordTypes"] = scope.recordTypes
    data["artisanIds"] = scope.artisanIds
    data["sectionIds"] = scope.sectionIds
    data["createdById"] = current_user.id
    created = await db.assignedtask.create(data=data, include=INCLUDE)
    return (await serialize_tasks([created]))[0]


@router.post("/batch", status_code=status.HTTP_201_CREATED)
async def create_task_batch(
    payload: TaskBatchCreate, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    assignee_ids = dedupe(payload.assigneeIds)
    if not assignee_ids:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
    people = {person.id: person for person in await db.user.find_many(where={"id": {"in": assignee_ids}})}
    for assignee_id in assignee_ids:
        check_assignable(current_user, people.get(assignee_id))

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

    await db.assignedtask.create_many(data=[{**base, "assigneeId": assignee_id} for assignee_id in assignee_ids])
    position = {assignee_id: index for index, assignee_id in enumerate(assignee_ids)}
    created = sorted(
        await db.assignedtask.find_many(where={"batchId": batch_id}, include=INCLUDE, order={"id": "asc"}),
        key=lambda task: position.get(task.assigneeId, len(position)),
    )
    tasks = await serialize_tasks(created)
    return {
        "batchId": batch_id,
        "title": title,
        "created": len(tasks),
        "batch": batch_summary(created, tasks),
        "tasks": tasks,
    }



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
    if view not in VIEWS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"view must be one of {sorted(VIEWS)}",
        )
    where: dict[str, Any] = {}
    if view == "assigned":
        where["assigneeId"] = current_user.id
    else:
        if not is_content_admin(current_user):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
        if view == "created":
            where["createdById"] = current_user.id
        if assigneeId:
            where["assigneeId"] = assigneeId
    if statusFilter and outstanding:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
        order=[{"dueAt": "asc"}, {"createdAt": "desc"}, {"id": "asc"}],
    )
    items = await serialize_tasks(tasks, with_derived=withDerived)
    return page_payload(items, total, page, page_size)


@router.get("/batches")
async def list_task_batches(
    current_user: Any = Depends(require_content_admin),
    view: str = Query("all"),
    workshopId: str | None = Query(None),
    batchId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    statusFilter: str | None = Query(None, alias="status"),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    if view not in {"created", "all"}:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
        member_where = dict(where)
        if assigneeId:
            member_where["assigneeId"] = assigneeId
        if statusFilter:
            member_where["status"] = statusFilter
        matches = await db.assignedtask.find_many(
            where=member_where, take=ROLLUP_SCAN_LIMIT, order=[{"createdAt": "desc"}, {"id": "asc"}]
        )
        batch_ids = sorted({task.batchId for task in matches if task.batchId})
        loose_ids = sorted({task.id for task in matches if not task.batchId})
        if not batch_ids and not loose_ids:
            return page_payload([], 0, page, pageSize)
        where = {"OR": [{"batchId": {"in": batch_ids}}, {"id": {"in": loose_ids}}]}

    rows = await db.assignedtask.find_many(
        where=where, include=INCLUDE, take=ROLLUP_SCAN_LIMIT, order=[{"createdAt": "desc"}, {"id": "asc"}]
    )
    grouped: dict[str, list[Any]] = {}
    for task in rows:
        grouped.setdefault(task.batchId or f"task:{task.id}", []).append(task)

    keys = sorted(grouped, key=lambda key: max(t.createdAt for t in grouped[key]), reverse=True)
    total = len(keys)
    page, page_size, skip = normalize_pagination(page, pageSize)
    window = keys[skip : skip + page_size]

    visible = [task for key in window for task in grouped[key]]
    serialized = {item["id"]: item for item in await serialize_tasks(visible)}
    items = [
        batch_summary(grouped[key], [serialized[task.id] for task in grouped[key]]) for key in window
    ]
    return page_payload(items, total, page, page_size)


@router.get("/progress")
async def task_progress(
    _: Any = Depends(require_content_admin),
    workshopId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    includeFinished: bool = Query(True),
) -> dict[str, Any]:
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
        where["status"] = {"in": sorted(OUTSTANDING_STATUSES)}

    rows = await db.assignedtask.find_many(
        where=where, include=INCLUDE, take=ROLLUP_SCAN_LIMIT, order=[{"createdAt": "desc"}, {"id": "asc"}]
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
        "awaitingReviewCount": sum(1 for task in rows if task.status in REVIEW_STATUSES),
        "outstandingCount": sum(1 for task in rows if task.status in OUTSTANDING_STATUSES),
        "overdueCount": sum(1 for item in serialized.values() if item.get("isOverdue")),
        "truncated": len(rows) >= ROLLUP_SCAN_LIMIT,
        "assignees": assignees,
    }


@router.get("/options")
async def task_options(
    current_user: Any = Depends(require_content_admin),
    workshopId: str | None = Query(None),
) -> dict[str, Any]:
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
        artisan_where["OR"] = [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]
    artisans = await db.artisan.find_many(where=artisan_where, order={"name": "asc"}, take=500)
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
    where: dict[str, Any] = {"assigneeId": current_user.id}
    if workshopId:
        where["workshopId"] = workshopId
    rows = await db.assignedtask.find_many(
        where=where, take=ROLLUP_SCAN_LIMIT, order=[{"dueAt": "asc"}, {"createdAt": "desc"}, {"id": "asc"}]
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
            continue

        numbers = derived.get(task.id) or {}
        effective = effective_progress(task, numbers)
        if effective["percent"] is None:
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
        "remainingCount": counts["OPEN"] + counts["IN_PROGRESS"],
        "awaitingReviewCount": counts[SUBMITTED],
        "outstandingCount": sum(counts[key] for key in OUTSTANDING_STATUSES),
        "approvedCount": counts["DONE"],
        "cancelledCount": counts["CANCELLED"],
        "overdueCount": overdue,
        "dueSoonCount": due_soon,
        "nextDueAt": public_encode(next_due),
        "percentComplete": round(sum(percents) / len(percents)) if percents else 0,
        "measuredCount": measured,
        "derivationSkipped": len(rows) > DERIVED_TASK_LIMIT,
        "truncated": len(rows) >= ROLLUP_SCAN_LIMIT,
    }


PENDING_BATCH_LIMIT = 50


def _can_see_task(task: Any, user: Any) -> bool:
    return task.assigneeId == user.id or task.createdById == user.id or is_content_admin(user)


def _review_query(task: Any, kinds: list[str]) -> dict[str, str]:
    review_query: dict[str, str] = {"createdById": task.assigneeId, "recordType": ",".join(kinds)}
    if task.workshopId:
        review_query["workshopId"] = task.workshopId
    if task.artisanIds:
        review_query["artisanId"] = ",".join(sorted(str(a) for a in task.artisanIds))
    return review_query


def _pending_kinds(task: Any) -> list[str]:
    return [kind for kind in (task.recordTypes or []) if kind in PENDING_REVIEW_KINDS] or list(PENDING_REVIEW_KINDS)


@router.get("/pending-records")
async def tasks_pending_records(
    ids: str = Query(..., description="Comma-separated task ids, at most PENDING_BATCH_LIMIT"),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    wanted = list(dict.fromkeys(part.strip() for part in ids.split(",") if part.strip()))
    if len(wanted) > PENDING_BATCH_LIMIT:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Ask for at most {PENDING_BATCH_LIMIT} tasks at once",
        )
    if not wanted:
        return {"tasks": {}}
    tasks = [task for task in await db.assignedtask.find_many(where={"id": {"in": wanted}}) if _can_see_task(task, current_user)]
    jobs: dict[tuple, Any] = {}
    plan: dict[str, list[tuple[str, tuple]]] = {}
    for task in tasks:
        artisans = list(task.artisanIds or [])
        entries: list[tuple[str, tuple]] = []
        for kind in _pending_kinds(task):
            scoped = _record_scope(kind, task.assigneeId, task.workshopId, artisans)
            if scoped is None:
                continue
            key = (kind, task.assigneeId, task.workshopId, tuple(sorted(artisans)))
            if key not in jobs:
                delegate, where = scoped
                jobs[key] = delegate.count(where={**where, "status": "PENDING"})
            entries.append((kind, key))
        plan[task.id] = entries
    keys = list(jobs)
    counted = dict(zip(keys, await gather_reads(*(jobs[key] for key in keys)))) if keys else {}
    out: dict[str, Any] = {}
    for task in tasks:
        by_type = {kind: int(counted[key]) for kind, key in plan[task.id]}
        out[task.id] = {
            "total": sum(by_type.values()),
            "byType": by_type,
            "reviewQuery": _review_query(task, _pending_kinds(task)),
        }
    return {"tasks": out}


@router.get("/{task_id}")
async def get_task(task_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    task = await require_task(task_id)
    if (
        task.assigneeId != current_user.id
        and task.createdById != current_user.id
        and not is_content_admin(current_user)
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the assignee, the creator, or an admin can view this task",
        )
    return (await serialize_tasks([task]))[0]


PENDING_REVIEW_KINDS = ("artisan", "product", "tool", "process", "questionnaire")
PENDING_LIST_LIMIT = 20

_PENDING_LABELS = {
    "artisan": ("name",),
    "product": ("productName", "artisanName", "craftName"),
    "tool": ("toolkitName", "englishName", "craftName"),
    "process": ("name",),
    "questionnaire": ("title",),
}


@router.get("/{task_id}/pending-records")
async def task_pending_records(task_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    task = await require_task(task_id)
    if not _can_see_task(task, current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the assignee, the creator, or an admin can view this task",
        )
    kinds = _pending_kinds(task)
    by_type: dict[str, int] = {}
    items: list[dict[str, Any]] = []
    for kind in kinds:
        scoped = _record_scope(kind, task.assigneeId, task.workshopId, list(task.artisanIds or []))
        if scoped is None:
            continue
        delegate, where = scoped
        pending_where = {**where, "status": "PENDING"}
        count, rows = await gather_reads(
            delegate.count(where=pending_where),
            delegate.find_many(where=pending_where, order={"submittedAt": "asc"}, take=PENDING_LIST_LIMIT),
        )
        by_type[kind] = int(count)
        for row in rows:
            label = next(
                (str(get_value(row, f)) for f in _PENDING_LABELS[kind] if get_value(row, f)),
                str(get_value(row, "id")),
            )
            items.append(
                {
                    "recordType": kind,
                    "id": get_value(row, "id"),
                    "label": label,
                    "submittedAt": public_encode(get_value(row, "submittedAt") or get_value(row, "createdAt")),
                }
            )
    items.sort(key=lambda item: str(item.get("submittedAt") or ""))
    return {
        "total": sum(by_type.values()),
        "byType": by_type,
        "items": items[:PENDING_LIST_LIMIT],
        "reviewQuery": _review_query(task, kinds),
    }



@router.patch("/{task_id}")
async def update_task(
    task_id: str,
    payload: TaskUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    task = await require_task(task_id)
    data = payload.model_dump(exclude_unset=True)
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

    is_creator = task.createdById == current_user.id
    is_manager = is_admin(current_user) or (is_creator and is_content_admin(current_user))
    if is_manager:
        if data.get("assigneeId") and data["assigneeId"] != task.assigneeId:
            await assert_assignable(current_user, data["assigneeId"])
    elif task.assigneeId == current_user.id:
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
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the task creator or an admin can reopen an approved task",
            )
        if new_status == "DONE":
            new_status = data["status"] = SUBMITTED
    else:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the assignee, the creator, or an admin can update this task",
        )

    if {"workshopId", "recordTypes", "artisanIds", "sectionIds", "targetCount"} & set(data):
        scope = await resolve_scope(
            workshop_id=data.get("workshopId", task.workshopId),
            record_types=data.get("recordTypes", task.recordTypes),
            artisan_ids=data.get("artisanIds", task.artisanIds),
            section_ids=data.get("sectionIds", task.sectionIds),
            target_count=data.get("targetCount", task.targetCount),
            require_work=bool(task.recordTypes or task.sectionIds),
        )
        if "recordTypes" in data:
            data["recordTypes"] = {"set": scope.recordTypes}
        if "artisanIds" in data:
            data["artisanIds"] = {"set": scope.artisanIds}
        if "sectionIds" in data:
            data["sectionIds"] = {"set": scope.sectionIds}

    target = data["targetCount"] if "targetCount" in data else task.targetCount
    if "progressCount" in data:
        reported = max(0, int(data["progressCount"]))
        data["progressCount"] = min(reported, target) if target else reported

    if new_status is not None:
        if new_status in {SUBMITTED, "DONE"}:
            if task.status not in {SUBMITTED, "DONE"}:
                data["completedAt"] = datetime.now(UTC)
            if target and "progressCount" not in data and (task.progressCount or 0) < target:
                data["progressCount"] = target
        else:
            data["completedAt"] = None

    if not data:
        return (await serialize_tasks([task]))[0]
    updated = await db.assignedtask.update(where={"id": task_id}, data=data, include=INCLUDE)
    await notify_task_change(
        updated,
        before=str(getattr(task.status, "value", task.status) or ""),
        after=data.get("status"),
        actor=current_user,
        by_manager=is_manager,
    )
    return (await serialize_tasks([updated]))[0]


@router.delete("/batch/{batch_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task_batch(batch_id: str, current_user: Any = Depends(require_content_admin)) -> None:
    tasks = await db.assignedtask.find_many(where={"batchId": batch_id})
    if not tasks:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Batch not found")
    await db.assignedtask.delete_many(where={"batchId": batch_id})


@router.delete("/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task(task_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    task = await require_task(task_id)
    await db.assignedtask.delete(where={"id": task.id})
