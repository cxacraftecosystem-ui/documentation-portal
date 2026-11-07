import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder
from prisma import Json
from pydantic import Field, ValidationError

from app.core.db import db
from app.questionnaire.live import live_media_where
from app.core.deps import (
    ROLE_RANK,
    can_access_review,
    can_edit_others_record,
    can_review_record,
    enum_or_raw,
    get_current_user,
    get_value,
    is_admin,
    is_master_admin,
    require_reviewer,
    values_match,
)
from app.schemas.common import APIModel, ReviewAction
from app.schemas.questionnaire import QuestionnaireInterviewUpdate
from app.questionnaire import review as v2_review
from app.questionnaire import shims_fr as v2_shims
from app.schemas.records import (
    ArtisanUpdate,
    ProcessUpdate,
    ProductUpdate,
    ToolUpdate,
    WorkshopUpdate,
    drop_masked_identity_numbers,
)
from app.services import aadhaar_fingerprint, email_outbox, review_reasons
from app.services.access import record_revision
from app.services.records import clean_data, decimal_to_string, jsonify_metadata, merge_field_provenance
from app.services.records import review_update
from app.services.records import public_encode
from app.services.review_reasons import reason_label
from app.services.review_queue import reviewable_creator_roles
from app.services.workshop_access import record_needs_admin_approval, stamp_workshop_submission

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/review", tags=["review"])

LATE_SUBMISSION_LOG_NOTE = "Late workshop submission — approved by an admin."

EDIT_LOG_PREFIX = "EDITED"


_DELEGATE_ATTRS: dict[str, tuple[str, str, str]] = {
    "artisan": ("artisan", "ARTISAN", "createdBy"),
    "workshop": ("workshop", "WORKSHOP", "createdBy"),
    "product": ("productdocumentation", "PRODUCT", "createdBy"),
    "tool": ("tooldocumentation", "TOOL", "createdBy"),
    "process": ("process", "PROCESS", "createdBy"),
    "questionnaire": ("questionnaireinterview", "QUESTIONNAIRE", "createdBy"),
    "media": ("mediafile", "MEDIA", "uploadedBy"),
}


_RECORD_TYPE_ALIASES = {"questionnaire_session": "questionnaire"}


def canonical_type(record_type: str) -> str:
    key = record_type.lower()
    return _RECORD_TYPE_ALIASES.get(key, key)


def delegate_for(record_type: str, client: Any = None) -> tuple[Any, str, str]:
    key = canonical_type(record_type)
    if key not in _DELEGATE_ATTRS:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unsupported review record type")
    attr, log_type, creator_relation = _DELEGATE_ATTRS[key]
    return getattr(client or db, attr), log_type, creator_relation


_PENDING_SOURCES: list[tuple[str, Any, tuple[str, ...]]] = [
    ("artisan", db.artisan, ("name",)),
    ("workshop", db.workshop, ("title",)),
    ("product", db.productdocumentation, ("productName", "artisanName", "craftName")),
    ("tool", db.tooldocumentation, ("toolkitName", "englishName", "craftName")),
    ("process", db.process, ("name",)),
    ("questionnaire", db.questionnaireinterview, ("title",)),
]


PENDING_TAKE = 200


def _reviewable_creator_roles(reviewer: Any) -> list[str] | None:
    return reviewable_creator_roles(reviewer)


def _label_for(row: Any, fields: tuple[str, ...]) -> str:
    for field in fields:
        value = get_value(row, field)
        if value:
            return str(value)
    return str(get_value(row, "id") or "Record")


def _creator_summary(creator: Any) -> dict[str, Any] | None:
    if creator is None:
        return None
    return {
        "id": get_value(creator, "id"),
        "name": get_value(creator, "name"),
        "role": str(getattr(get_value(creator, "role"), "value", get_value(creator, "role"))),
    }


PENDING_MAX_PAGE_SIZE = 100

_PENDING_TABLES: dict[str, str] = {
    "artisan": '"Artisan"',
    "workshop": '"Workshop"',
    "product": '"ProductDocumentation"',
    "tool": '"ToolDocumentation"',
    "process": '"Process"',
    "questionnaire": '"QuestionnaireInterview"',
}

_LOG_TYPES = {
    "artisan": "ARTISAN",
    "workshop": "WORKSHOP",
    "product": "PRODUCT",
    "tool": "TOOL",
    "process": "PROCESS",
    "questionnaire": "QUESTIONNAIRE",
    "media": "MEDIA",
}


@dataclass(frozen=True)
class PendingFilters:
    roles: list[str] | None = None
    workshop_id: str | None = None
    created_by: str | None = None
    record_types: tuple[str, ...] | None = None
    newest_first: bool = False
    artisan_ids: tuple[str, ...] | None = None

    def sources(self) -> list[tuple[str, Any, tuple[str, ...]]]:
        if not self.record_types:
            return list(_PENDING_SOURCES)
        return [source for source in _PENDING_SOURCES if source[0] in self.record_types]


def pending_where(record_type: str, filters: PendingFilters) -> dict[str, Any]:
    where: dict[str, Any] = {"status": "PENDING"}
    if record_type == "questionnaire" and v2_shims.enabled():
        where = {"OR": [{"status": "PENDING"}, {"status": "APPROVED", "pendingChangesAt": {"not": None}}]}
    if filters.roles is not None:
        where["createdBy"] = {"is": {"role": {"in": filters.roles}}}
    if filters.created_by:
        where["createdById"] = filters.created_by
    workshop = filters.workshop_id
    if workshop:
        if record_type == "workshop":
            where["id"] = workshop
        elif record_type == "artisan":
            where["OR"] = [{"workshopId": workshop}, {"workshops": {"some": {"workshopId": workshop}}}]
        else:
            where["workshopId"] = workshop
    artisans = list(filters.artisan_ids or [])
    if artisans:
        scope: dict[str, Any]
        if record_type == "artisan":
            scope = {"id": {"in": artisans}}
        elif record_type in {"product", "tool"}:
            scope = {"artisanId": {"in": artisans}}
        elif record_type == "process":
            scope = {"product": {"is": {"artisanId": {"in": artisans}}}}
        elif record_type == "questionnaire":
            scope = {"artisans": {"some": {"artisanId": {"in": artisans}}}}
        else:
            scope = {"id": {"in": []}}
        where = {"AND": [where, scope]}
    return where


def pending_keys_sql(filters: PendingFilters, *, limit: int, offset: int) -> tuple[str, list[Any]]:
    params: list[Any] = []

    def param(value: Any) -> str:
        params.append(value)
        return f"${len(params)}"

    role_clause = ""
    if filters.roles is not None:
        roles = [role for role in filters.roles if role in ROLE_RANK]
        quoted = ", ".join(f"'{role}'" for role in roles) or "NULL"
        role_clause = f' AND u."role"::text IN ({quoted})'
    creator = f' AND t."createdById" = {param(filters.created_by)}' if filters.created_by else ""
    workshop_ref = param(filters.workshop_id) if filters.workshop_id else None
    artisan_refs = ", ".join(param(artisan_id) for artisan_id in (filters.artisan_ids or ()))

    parts: list[str] = []
    for record_type, _delegate, _labels in filters.sources():
        table = _PENDING_TABLES[record_type]
        clause = ""
        if workshop_ref:
            if record_type == "workshop":
                clause = f' AND t."id" = {workshop_ref}'
            elif record_type == "artisan":
                clause = (
                    f' AND (t."workshopId" = {workshop_ref} OR EXISTS (SELECT 1 FROM "WorkshopArtisan" wa'
                    f' WHERE wa."artisanId" = t."id" AND wa."workshopId" = {workshop_ref}))'
                )
            else:
                clause = f' AND t."workshopId" = {workshop_ref}'
        if artisan_refs:
            if record_type == "artisan":
                clause += f' AND t."id" IN ({artisan_refs})'
            elif record_type in {"product", "tool"}:
                clause += f' AND t."artisanId" IN ({artisan_refs})'
            elif record_type == "process":
                clause += (
                    ' AND EXISTS (SELECT 1 FROM "ProductDocumentation" p'
                    f' WHERE p."id" = t."productId" AND p."artisanId" IN ({artisan_refs}))'
                )
            elif record_type == "questionnaire":
                clause += (
                    ' AND EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" qa'
                    f' WHERE qa."interviewId" = t."id" AND qa."artisanId" IN ({artisan_refs}))'
                )
            else:
                clause += " AND FALSE"
        if record_type == "questionnaire":
            clause += ' AND t."deletedAt" IS NULL AND t."mergedIntoId" IS NULL'
        join = ' JOIN "User" u ON u."id" = t."createdById"' if role_clause else ""
        parts.append(
            f"SELECT '{record_type}' AS \"recordType\", t.\"id\" AS \"id\", t.\"submittedAt\" AS \"submittedAt\""
            f" FROM {table} t{join} WHERE t.\"status\" = 'PENDING'{role_clause}{creator}{clause}"
        )
    direction = "DESC" if filters.newest_first else "ASC"
    sql = (
        "SELECT q.\"recordType\", q.\"id\" FROM (" + " UNION ALL ".join(parts) + ") q"
        f' ORDER BY q."submittedAt" {direction}, q."recordType" ASC, q."id" ASC'
        f" LIMIT {int(limit)} OFFSET {int(offset)}"
    )
    return sql, params


async def pending_page_keys(filters: PendingFilters, *, limit: int, offset: int) -> list[tuple[str, str]]:
    if not filters.sources():
        return []
    sql, params = pending_keys_sql(filters, limit=limit, offset=offset)
    rows = await db.query_raw(sql, *params)
    return [(str(row["recordType"]), str(row["id"])) for row in rows]


def _sort_moment(item: dict[str, Any]) -> str:
    return str(item.get("submittedAt") or item.get("createdAt") or "")


async def _previous_reviews(rows: list[tuple[str, Any]]) -> dict[tuple[str, str], dict[str, Any]]:
    reviewed = [(record_type, row) for record_type, row in rows if get_value(row, "reviewedAt")]
    if not reviewed:
        return {}
    ids = [str(get_value(row, "id")) for _, row in reviewed]
    reviewer_ids = {str(get_value(row, "reviewedById")) for _, row in reviewed if get_value(row, "reviewedById")}
    logs: list[Any] = []
    reviewers: list[Any] = []
    try:
        logs = await db.reviewlog.find_many(
            where={"recordId": {"in": ids}, "status": {"in": ["NEEDS_REVISION", "REJECTED"]}},
            order={"createdAt": "desc"},
        )
        if reviewer_ids:
            reviewers = await db.user.find_many(where={"id": {"in": sorted(reviewer_ids)}})
    except Exception:
        logs, reviewers = [], []
    names = {str(get_value(user, "id")): get_value(user, "name") for user in reviewers}
    latest_reason: dict[str, Any] = {}
    for log in logs:
        latest_reason.setdefault(str(get_value(log, "recordId")), log)
    out: dict[tuple[str, str], dict[str, Any]] = {}
    for record_type, row in reviewed:
        row_id = str(get_value(row, "id"))
        log = latest_reason.get(row_id)
        reason = get_value(log, "reason") if log is not None else None
        out[(record_type, row_id)] = {
            "notes": get_value(row, "reviewNotes"),
            "reviewerName": names.get(str(get_value(row, "reviewedById") or "")),
            "reviewedAt": jsonable_encoder(get_value(row, "reviewedAt")),
            "status": str(enum_or_raw(get_value(log, "status"))) if log is not None else None,
            "reason": reason,
            "reasonLabel": reason_label(reason),
        }
    return out


def _pending_item(
    record_type: str, row: Any, label_fields: tuple[str, ...], previous: dict[str, Any] | None = None
) -> dict[str, Any]:
    submitted = get_value(row, "submittedAt") or get_value(row, "createdAt")
    return {
        "recordType": record_type,
        "id": get_value(row, "id"),
        "label": _label_for(row, label_fields),
        "place": get_value(row, "place"),
        "status": "PENDING",
        "workshopId": get_value(row, "workshopId") if record_type != "workshop" else get_value(row, "id"),
        "createdAt": jsonable_encoder(get_value(row, "createdAt")),
        "submittedAt": jsonable_encoder(submitted),
        "createdBy": _creator_summary(get_value(row, "createdBy")),
        "needsAdminApproval": record_needs_admin_approval(row),
        "resubmitted": previous is not None,
        "previousReview": previous,
    }


@router.get("/reasons")
async def list_review_reasons(_: Any = Depends(require_reviewer)) -> list[dict[str, Any]]:
    return review_reasons.as_payload()


@router.get("/pending")
async def list_pending_reviews(
    reviewer: Any = Depends(require_reviewer),
    page: int | None = Query(default=None, ge=1),
    page_size: int = Query(default=20, ge=1, le=PENDING_MAX_PAGE_SIZE, alias="pageSize"),
    workshop_id: str | None = Query(default=None, alias="workshopId"),
    created_by: str | None = Query(default=None, alias="createdById"),
    record_type: str | None = Query(default=None, alias="recordType"),
    order: str = Query(default="oldest", pattern="^(oldest|newest)$"),
    artisan_id: str | None = Query(default=None, alias="artisanId"),
) -> dict[str, Any]:
    paged = page is not None
    types: tuple[str, ...] | None = None
    if record_type:
        wanted = {part.strip().lower() for part in record_type.split(",") if part.strip()}
        unknown = wanted - set(_PENDING_TABLES)
        if unknown:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"Unknown record type: {', '.join(sorted(unknown))}",
            )
        types = tuple(sorted(wanted))
    roles = _reviewable_creator_roles(reviewer)
    if roles is not None and not roles:
        if paged:
            return {"items": [], "total": 0, "page": page, "pageSize": page_size}
        return {"items": [], "total": 0, "truncated": False}
    filters = PendingFilters(
        roles=roles,
        workshop_id=workshop_id or None,
        created_by=created_by or None,
        record_types=types,
        newest_first=order == "newest",
        artisan_ids=tuple(sorted({part.strip() for part in (artisan_id or "").split(",") if part.strip()})) or None,
    )
    sources = filters.sources()
    order_by = {"submittedAt": "desc" if filters.newest_first else "asc"}

    if not paged:
        loaded: list[tuple[str, Any, tuple[str, ...]]] = []
        truncated = False
        for source_type, delegate, label_fields in sources:
            rows = await delegate.find_many(
                where=pending_where(source_type, filters),
                include={"createdBy": True},
                order=order_by,
                take=PENDING_TAKE,
            )
            if len(rows) >= PENDING_TAKE:
                truncated = True
            loaded.extend((source_type, row, label_fields) for row in rows)
        previous = await _previous_reviews([(t, row) for t, row, _ in loaded])
        items = [
            _pending_item(t, row, labels, previous.get((t, str(get_value(row, "id")))))
            for t, row, labels in loaded
        ]
        items.sort(key=_sort_moment, reverse=filters.newest_first)
        return {"items": items, "total": len(items), "truncated": truncated}

    total = 0
    for source_type, delegate, _labels in sources:
        total += await delegate.count(where=pending_where(source_type, filters))
    keys = await pending_page_keys(filters, limit=page_size, offset=(page - 1) * page_size)
    wanted_ids: dict[str, list[str]] = {}
    for key_type, key_id in keys:
        wanted_ids.setdefault(key_type, []).append(key_id)
    by_key: dict[tuple[str, str], tuple[Any, tuple[str, ...]]] = {}
    for source_type, delegate, label_fields in sources:
        ids = wanted_ids.get(source_type)
        if not ids:
            continue
        rows = await delegate.find_many(where={"id": {"in": ids}}, include={"createdBy": True})
        for row in rows:
            by_key[(source_type, str(get_value(row, "id")))] = (row, label_fields)
    ordered = [(key, by_key[key]) for key in keys if key in by_key]
    previous = await _previous_reviews([(key[0], row) for key, (row, _) in ordered])
    items = [_pending_item(key[0], row, labels, previous.get(key)) for key, (row, labels) in ordered]
    counts = await pending_media_counts([key for key, _ in ordered], reviewer)
    for item, (key, _) in zip(items, ordered, strict=True):
        item["pendingMediaCount"] = counts.get(key, 0)
    return {"items": items, "total": total, "page": page, "pageSize": page_size}


_MEDIA_TAGS: dict[str, tuple[str, ...]] = {
    "artisan": ("artisan",),
    "workshop": ("workshop",),
    "product": ("product",),
    "tool": ("tool",),
    "process": ("process",),
    "questionnaire": ("questionnaire", "questionnaireinterview", "questionnaire_session"),
}
_MEDIA_COLUMNS: dict[str, str] = {
    "product": "productId",
    "tool": "toolId",
    "questionnaire": "questionnaireInterviewId",
}
MEDIA_WITH_RECORD_NOTE = "Approved with its {kind} record."
MEDIA_REJECTABLE_STATUSES = ("PENDING", "APPROVED")


def _media_clauses(key: str, record_id: str, step_ids: list[str]) -> list[dict[str, Any]]:
    clauses: list[dict[str, Any]] = [
        {"linkedRecordType": {"equals": tag, "mode": "insensitive"}, "linkedRecordId": record_id}
        for tag in _MEDIA_TAGS.get(key, ())
    ]
    if key == "process" and step_ids:
        clauses.append(
            {"linkedRecordType": {"equals": "processstep", "mode": "insensitive"}, "linkedRecordId": {"in": step_ids}}
        )
    column = _MEDIA_COLUMNS.get(key)
    if column:
        clauses.append({"linkedRecordType": None, column: record_id})
    if key == "artisan":
        clauses.append(
            {"linkedRecordType": None, "artisanId": record_id, "productId": None, "toolId": None, "questionnaireInterviewId": None}
        )
    return clauses


def _media_belongs(media: Any, key: str, record_id: str, step_ids: list[str]) -> bool:
    tag = str(get_value(media, "linkedRecordType") or "").lower()
    linked = get_value(media, "linkedRecordId")
    if tag:
        if tag in _MEDIA_TAGS.get(key, ()) and linked == record_id:
            return True
        return key == "process" and tag == "processstep" and linked in step_ids
    column = _MEDIA_COLUMNS.get(key)
    if column and get_value(media, column) == record_id:
        return True
    return key == "artisan" and get_value(media, "artisanId") == record_id and not any(
        get_value(media, other) for other in ("productId", "toolId", "questionnaireInterviewId")
    )


async def _process_steps(process_ids: list[str], client: Any = None) -> dict[str, list[str]]:
    if not process_ids:
        return {}
    steps = await (client or db).processstep.find_many(where={"processId": {"in": process_ids}})
    by_process: dict[str, list[str]] = {pid: [] for pid in process_ids}
    for step in steps:
        by_process.setdefault(str(get_value(step, "processId")), []).append(str(get_value(step, "id")))
    return by_process


async def media_of_records(
    keys: list[tuple[str, str]], statuses: tuple[str, ...] = ("PENDING",), client: Any = None
) -> dict[tuple[str, str], list[Any]]:
    wanted = [(t.lower(), rid) for t, rid in keys if t.lower() in _MEDIA_TAGS]
    found: dict[tuple[str, str], list[Any]] = {key: [] for key in wanted}
    if not wanted:
        return found
    steps = await _process_steps([rid for t, rid in wanted if t == "process"], client)
    clauses = [clause for t, rid in wanted for clause in _media_clauses(t, rid, steps.get(rid, []))]
    files = await (client or db).mediafile.find_many(
        where=live_media_where({"status": {"in": list(statuses)}, "OR": clauses}), include={"uploadedBy": True}
    )
    for media in files:
        for t, rid in wanted:
            if _media_belongs(media, t, rid, steps.get(rid, [])):
                found[(t, rid)].append(media)
    return found


def _uploader_role(media: Any) -> Any:
    uploader = get_value(media, "uploadedBy")
    return get_value(uploader, "role") if uploader else None


def cascade_eligible(files: list[Any], reviewer: Any) -> list[Any]:
    admin = is_admin(reviewer)
    return [
        media
        for media in files
        if can_review_record(reviewer, _uploader_role(media)) and (admin or not record_needs_admin_approval(media))
    ]


async def pending_media_counts(keys: list[tuple[str, str]], reviewer: Any) -> dict[tuple[str, str], int]:
    grouped = await media_of_records(keys)
    return {key: len(cascade_eligible(files, reviewer)) for key, files in grouped.items()}


async def approve_media_with_record(
    client: Any, kind: str, files: list[Any], reviewer_id: str, reviewed_at: datetime, *, note: str | None = None
) -> int:
    approved = 0
    for media in files:
        data: dict[str, Any] = {"status": "APPROVED", "reviewedById": reviewer_id, "reviewedAt": reviewed_at}
        if record_needs_admin_approval(media):
            extra = get_value(media, "extraMetadata") or {}
            submission = extra.get("workshopSubmission") or {}
            data["extraMetadata"] = Json({**extra, "workshopSubmission": {**submission, "needsAdminApproval": False}})
        media_id = str(get_value(media, "id"))
        changed = await client.mediafile.update_many(where={"id": media_id, "status": "PENDING"}, data=data)
        if not changed:
            continue
        approved += 1
        await client.reviewlog.create(
            data={
                "recordType": "MEDIA",
                "recordId": media_id,
                "status": "APPROVED",
                "notes": note or MEDIA_WITH_RECORD_NOTE.format(kind=kind),
                "reviewerId": reviewer_id,
            }
        )
    return approved


async def approve_record_media(
    record_type: str, record_id: str, reviewer: Any, *, client: Any = None, reviewed_at: datetime | None = None
) -> int:
    key = canonical_type(record_type)
    if key not in _MEDIA_TAGS:
        return 0
    grouped = await media_of_records([(key, record_id)], client=client)
    files = cascade_eligible(grouped.get((key, record_id), []), reviewer)
    if key == "questionnaire" and files and v2_shims.enabled():
        excluded = await v2_review.excluded_media_ids(client or db, record_id)
        files = [f for f in files if str(get_value(f, "id")) not in excluded]
    if not files:
        return 0
    return await approve_media_with_record(
        client or db, key, files, reviewer.id, reviewed_at or datetime.now(UTC)
    )


ALREADY_DECIDED = "Someone has already reviewed this record. Reload it to see where it stands."

REVIEW_TX_TIMEOUT = timedelta(seconds=30)
REVIEW_TX_MAX_WAIT = timedelta(seconds=15)


async def set_review_status(
    record_type: str,
    record_id: str,
    new_status: str,
    payload: ReviewAction,
    reviewer: Any,
    *,
    notify: bool = True,
) -> dict[str, Any]:
    record_type = canonical_type(record_type)
    delegate, log_type, creator_relation = delegate_for(record_type)
    record = await delegate.find_unique(where={"id": record_id}, include={creator_relation: True})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    creator = get_value(record, creator_relation)
    if not can_review_record(reviewer, get_value(creator, "role") if creator else None):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only review records created by users below your own tier",
        )
    v2_session = record_type.lower() == "questionnaire" and v2_shims.enabled()
    current_status = str(enum_or_raw(get_value(record, "status")))
    reopened = v2_session and get_value(record, "pendingChangesAt") is not None
    if current_status != "PENDING" and not reopened:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=ALREADY_DECIDED)
    late = record_needs_admin_approval(record)
    if late and new_status == "APPROVED" and not is_admin(reviewer):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This record was submitted outside its workshop's dates. Only an admin or master "
                "admin can approve a late submission."
            ),
        )
    reason = review_reasons.reason_for(getattr(payload, "reason", None)) if new_status != "APPROVED" else None
    data = review_update(new_status, payload.notes, reviewer.id)
    log_notes = payload.notes
    if new_status == "APPROVED" and late:
        extra = get_value(record, "extraMetadata")
        submission = extra.get("workshopSubmission")
        data["extraMetadata"] = Json(
            {**extra, "workshopSubmission": {**submission, "needsAdminApproval": False}}
        )
        notes = (payload.notes or "").strip()
        log_notes = f"{notes}\n\n{LATE_SUBMISSION_LOG_NOTE}" if notes else LATE_SUBMISSION_LOG_NOTE
    log_data: dict[str, Any] = {
        "recordType": log_type,
        "recordId": record_id,
        "status": new_status,
        "notes": log_notes,
        "reviewerId": reviewer.id,
    }
    if reason is not None:
        log_data["reason"] = reason.code
    if v2_session and new_status == "APPROVED":
        await v2_review.assert_may_approve(db, record_id)
        data["pendingChangesAt"] = None
    media_approved = 0
    async with db.tx(max_wait=REVIEW_TX_MAX_WAIT, timeout=REVIEW_TX_TIMEOUT) as tx:
        tx_delegate, _log_type, _relation = delegate_for(record_type, tx)
        if not await tx_delegate.update_many(where={"id": record_id, "status": current_status}, data=data):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=ALREADY_DECIDED)
        updated = await tx_delegate.find_unique(where={"id": record_id})
        await tx.reviewlog.create(data=log_data)
        if new_status == "APPROVED" and record_type.lower() != "media":
            media_approved = await approve_record_media(
                record_type, record_id, reviewer, client=tx, reviewed_at=data["reviewedAt"]
            )
            if v2_session:
                await v2_review.approve_revisions(tx, record_id)
    if notify:
        label_fields = next((labels for t, _d, labels in _PENDING_SOURCES if t == record_type.lower()), ("name", "title"))
        await email_outbox.notify_review_decision(
            record_type=record_type.lower(),
            record_id=record_id,
            record_label=_label_for(record, (*label_fields, "originalFilename")),
            creator_id=get_value(creator, "id") if creator else None,
            decision=new_status,
            actor=reviewer,
            note=payload.notes,
            reason_label=reason.label if reason else None,
        )
    answer = public_encode(updated)
    if new_status == "APPROVED" and record_type.lower() != "media":
        answer["mediaApproved"] = media_approved
    return answer


@router.post("/{record_type}/{record_id}/approve")
async def approve_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    return await set_review_status(record_type, record_id, "APPROVED", payload, reviewer)


def _resolve_refusal(payload: ReviewAction) -> tuple[str, ReviewAction]:
    code = getattr(payload, "reason", None)
    if not code:
        return "REJECTED", payload
    reason = review_reasons.reason_for(code)
    if reason is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Pick a reason from the list.",
        )
    notes = (payload.notes or "").strip()
    if reason.needs_note and not notes:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Say what is wrong when the reason is Other.",
        )
    carried = ReviewAction(notes=notes or reason.label, reason=reason.code)
    return ("NEEDS_REVISION" if reason.fixable else "REJECTED"), carried


@router.post("/{record_type}/{record_id}/reject")
async def reject_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    new_status, carried = _resolve_refusal(payload)
    return await set_review_status(record_type, record_id, new_status, carried, reviewer)


class BulkItem(APIModel):
    recordType: str
    id: str


class BulkApproval(APIModel):
    items: list[BulkItem] = Field(default_factory=list, max_length=200)
    notes: str | None = None


@router.post("/bulk-approve")
async def bulk_approve(payload: BulkApproval, reviewer: Any = Depends(require_reviewer)) -> dict[str, Any]:
    if not payload.items:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Choose at least one record.")
    results: list[dict[str, Any]] = []
    approved: list[dict[str, Any]] = []
    media_approved = 0
    seen: set[tuple[str, str]] = set()
    for item in payload.items:
        key = (item.recordType.lower(), item.id)
        if key in seen:
            continue
        seen.add(key)
        try:
            updated = await set_review_status(
                key[0], item.id, "APPROVED", ReviewAction(notes=payload.notes), reviewer, notify=False
            )
        except HTTPException as exc:
            results.append(
                {"recordType": key[0], "id": item.id, "outcome": "FAILED", "detail": jsonable_encoder(exc.detail)}
            )
            continue
        results.append({"recordType": key[0], "id": item.id, "outcome": "APPROVED"})
        media_approved += int(updated.get("mediaApproved") or 0)
        label_fields = next((labels for t, _d, labels in _PENDING_SOURCES if t == key[0]), ("name", "title"))
        approved.append(
            {
                "recordType": key[0],
                "id": item.id,
                "label": _label_for(updated, (*label_fields, "originalFilename")),
                "creatorId": updated.get("createdById") or updated.get("uploadedById"),
            }
        )
    await email_outbox.notify_bulk_approvals(approved, actor=reviewer)
    return {
        "results": results,
        "approved": sum(1 for r in results if r["outcome"] == "APPROVED"),
        "failed": sum(1 for r in results if r["outcome"] == "FAILED"),
        "mediaApproved": media_approved,
    }


@router.get("/{record_type}/{record_id}/media")
async def record_media_review(
    record_type: str, record_id: str, reviewer: Any = Depends(require_reviewer)
) -> dict[str, Any]:
    key = canonical_type(record_type)
    delegate, _log_type, _relation = delegate_for(key)
    if key == "media":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="A file has no files of its own")
    record = await delegate.find_unique(where={"id": record_id})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    files = (await media_of_records([(key, record_id)], MEDIA_REJECTABLE_STATUSES)).get((key, record_id), [])
    pending = [media for media in files if str(enum_or_raw(get_value(media, "status"))) == "PENDING"]
    record_pending = str(enum_or_raw(get_value(record, "status"))) == "PENDING"
    return {
        "approvesWith": len(cascade_eligible(pending, reviewer)) if record_pending else 0,
        "rejectable": [
            str(get_value(media, "id")) for media in files if can_review_record(reviewer, _uploader_role(media))
        ],
    }


_MINE_SOURCES = _PENDING_SOURCES


@router.get("/mine")
async def my_returned_records(
    current_user: Any = Depends(get_current_user),
    status_filter: str | None = Query(default=None, alias="status"),
) -> dict[str, Any]:
    wanted = ["NEEDS_REVISION", "REJECTED"]
    if status_filter:
        value = status_filter.strip().upper()
        if value not in wanted:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="status must be NEEDS_REVISION or REJECTED",
            )
        wanted = [value]
    uid = get_value(current_user, "id")
    loaded: list[tuple[str, Any, tuple[str, ...]]] = []
    counts = {"NEEDS_REVISION": 0, "REJECTED": 0}
    for record_type, delegate, label_fields in _MINE_SOURCES:
        rows = await delegate.find_many(
            where={"createdById": uid, "status": {"in": ["NEEDS_REVISION", "REJECTED"]}},
            order={"reviewedAt": "desc"},
            take=PENDING_TAKE,
        )
        for row in rows:
            state = str(enum_or_raw(get_value(row, "status")))
            if state in wanted:
                loaded.append((record_type, row, label_fields))
        for state in ("NEEDS_REVISION", "REJECTED"):
            counts[state] += int(await delegate.count(where={"createdById": uid, "status": state}) or 0)
    ids = [str(get_value(row, "id")) for _, row, _ in loaded]
    reviewer_ids = sorted({str(get_value(row, "reviewedById")) for _, row, _ in loaded if get_value(row, "reviewedById")})
    logs = (
        await db.reviewlog.find_many(
            where={"recordId": {"in": ids}, "status": {"in": ["NEEDS_REVISION", "REJECTED"]}},
            order={"createdAt": "desc"},
        )
        if ids
        else []
    )
    reviewers = await db.user.find_many(where={"id": {"in": reviewer_ids}}) if reviewer_ids else []
    names = {str(get_value(u, "id")): get_value(u, "name") for u in reviewers}
    reasons: dict[str, Any] = {}
    for log in logs:
        reasons.setdefault(str(get_value(log, "recordId")), get_value(log, "reason"))
    items = []
    for record_type, row, label_fields in loaded:
        row_id = str(get_value(row, "id"))
        reason = reasons.get(row_id)
        items.append(
            {
                "recordType": record_type,
                "id": row_id,
                "label": _label_for(row, label_fields),
                "place": get_value(row, "place"),
                "status": str(enum_or_raw(get_value(row, "status"))),
                "reviewNotes": get_value(row, "reviewNotes"),
                "reason": reason,
                "reasonLabel": reason_label(reason),
                "reviewerName": names.get(str(get_value(row, "reviewedById") or "")),
                "reviewedAt": jsonable_encoder(get_value(row, "reviewedAt")),
            }
        )
    items.sort(key=lambda item: str(item.get("reviewedAt") or ""), reverse=True)
    shown = sum(counts[state] for state in wanted)
    return {"items": items, "total": len(items), "counts": counts, "truncated": shown > len(items)}


@router.get("/{record_type}/{record_id}/latest")
async def latest_review(
    record_type: str, record_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    delegate, log_type, creator_relation = delegate_for(record_type)
    record = await delegate.find_unique(where={"id": record_id}, include={creator_relation: True})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    creator = get_value(record, creator_relation)
    uid = get_value(current_user, "id")
    allowed = (
        (creator is not None and get_value(creator, "id") == uid)
        or is_admin(current_user)
        or (can_access_review(current_user) and can_review_record(current_user, get_value(creator, "role") if creator else None))
    )
    if not allowed:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only its creator or a reviewer can see this")
    state = str(enum_or_raw(get_value(record, "status")))
    may_review = bool(
        can_access_review(current_user)
        and can_review_record(current_user, get_value(creator, "role") if creator else None)
        and (creator is None or get_value(creator, "id") != uid or is_master_admin(current_user))
    )
    base = {
        "status": state,
        "canReview": may_review,
        "needsAdminApproval": record_needs_admin_approval(record),
    }
    if not get_value(record, "reviewedAt"):
        return {**base, "review": None}
    log = await db.reviewlog.find_first(
        where={"recordType": log_type, "recordId": record_id, "status": {"in": ["NEEDS_REVISION", "REJECTED", "APPROVED"]}},
        order={"createdAt": "desc"},
    )
    reviewer_id = get_value(record, "reviewedById")
    reviewer = await db.user.find_unique(where={"id": reviewer_id}) if reviewer_id else None
    reason = get_value(log, "reason") if log is not None else None
    return {
        **base,
        "review": {
            "status": str(enum_or_raw(get_value(log, "status"))) if log is not None else state,
            "notes": get_value(record, "reviewNotes"),
            "reason": reason,
            "reasonLabel": reason_label(reason),
            "reviewerName": get_value(reviewer, "name") if reviewer else None,
            "reviewedAt": jsonable_encoder(get_value(record, "reviewedAt")),
        },
    }


class MediaReviewEdit(APIModel):
    caption: str | None = None
    transcriptText: str | None = None
    transcriptSummary: str | None = None


_EDIT_SCHEMAS: dict[str, type[APIModel]] = {
    "artisan": ArtisanUpdate,
    "workshop": WorkshopUpdate,
    "product": ProductUpdate,
    "tool": ToolUpdate,
    "process": ProcessUpdate,
    "questionnaire": QuestionnaireInterviewUpdate,
    "media": MediaReviewEdit,
}

_NOT_REVIEW_EDITABLE = frozenset(
    {
        "status",
        "extraMetadata",
        "workshopId",
        "location",
        "artisanIds",
        "craftIds",
        "responses",
        "steps",
        "recordedAt",
        "recordedTimezone",
        "formContract",
    }
)


class ReviewEdit(APIModel):
    fields: dict[str, Any] = Field(default_factory=dict)
    note: str | None = None
    approve: bool = False


@router.post("/{record_type}/{record_id}/edit")
async def edit_reviewed_record(
    record_type: str,
    record_id: str,
    payload: ReviewEdit,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    key = canonical_type(record_type)
    delegate, log_type, creator_relation = delegate_for(record_type)
    schema = _EDIT_SCHEMAS.get(key)
    if schema is None:  # pragma: no cover
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Unsupported review record type"
        )

    if not payload.fields:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Send at least one field to change in 'fields'",
        )
    blocked = sorted(set(payload.fields) & _NOT_REVIEW_EDITABLE)
    if blocked:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"These fields cannot be changed from a review edit: {', '.join(blocked)}. "
                "Use approve/reject/revise for the status, and the record's own edit screen for "
                "its workshop, location and linked records."
            ),
        )
    try:
        parsed = schema(**payload.fields)
    except ValidationError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=jsonable_encoder(exc.errors(include_url=False)),
        ) from exc

    record = await delegate.find_unique(where={"id": record_id}, include={creator_relation: True})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    creator = get_value(record, creator_relation)
    if not can_edit_others_record(reviewer, get_value(creator, "role") if creator else None):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Editing someone else's record needs Professor access or above, and only for a "
                "record created below your own tier. Approve, reject, or send it back for revision "
                "with your comments instead."
            ),
        )
    if record_needs_admin_approval(record) and not is_admin(reviewer):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This record was submitted outside its workshop's dates. Only an admin or master "
                "admin can edit or approve a late submission."
            ),
        )

    data = drop_masked_identity_numbers(decimal_to_string(clean_data(parsed.model_dump(exclude_unset=True))))
    if not data:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Send at least one field to change in 'fields'",
        )
    if key == "artisan":
        try:
            aadhaar_fingerprint.seal(data, previous=record)
        except aadhaar_fingerprint.FingerprintKeyMissing as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Aadhaar numbers can't be saved right now. Try again shortly.",
            ) from exc
    changed = sorted(
        {
            aadhaar_fingerprint.NUMBER_FIELD
            if field in (aadhaar_fingerprint.FINGERPRINT_FIELD, aadhaar_fingerprint.LAST4_FIELD)
            else field
            for field, value in data.items()
            if not values_match(get_value(record, field), value)
        }
    )

    await record_revision(record, reviewer, data, key)
    stamp_workshop_submission(data, record=record)
    merge_field_provenance(data, reviewer, previous=record)
    jsonify_metadata(data)
    updated = await delegate.update(where={"id": record_id}, data=data)

    summary = f"{EDIT_LOG_PREFIX}: {', '.join(changed)}" if changed else f"{EDIT_LOG_PREFIX}: no values changed"
    note = (payload.note or "").strip()
    await db.reviewlog.create(
        data={
            "recordType": log_type,
            "recordId": record_id,
            "status": str(enum_or_raw(get_value(record, "status"))),
            "notes": f"{summary}\n\n{note}" if note else summary,
            "reviewerId": reviewer.id,
        }
    )

    if payload.approve:
        return await set_review_status(record_type, record_id, "APPROVED", ReviewAction(notes=payload.note), reviewer)
    return public_encode(updated)


@router.post("/{record_type}/{record_id}/revise")
async def send_record_for_revision(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    reason = review_reasons.reason_for(payload.reason) if payload.reason else None
    if payload.reason and reason is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Pick a reason from the list.",
        )
    notes = (payload.notes or "").strip()
    if not notes and (reason is None or reason.needs_note):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Comments are required when sending a record back for revision",
        )
    if not notes:
        payload = ReviewAction(notes=reason.label, reason=reason.code)
    return await set_review_status(record_type, record_id, "NEEDS_REVISION", payload, reviewer)
