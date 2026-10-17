import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder
from prisma import Json
from pydantic import Field, ValidationError

from app.core.db import db
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
    role_rank,
    values_match,
)
from app.schemas.common import APIModel, ReviewAction
from app.schemas.questionnaire import QuestionnaireInterviewUpdate
from app.schemas.records import (
    ArtisanUpdate,
    ProcessUpdate,
    ProductUpdate,
    ToolUpdate,
    WorkshopUpdate,
    drop_masked_identity_numbers,
)
from app.services import email_outbox, review_reasons
from app.services.access import record_revision
from app.services.records import clean_data, decimal_to_string, jsonify_metadata, merge_field_provenance
from app.services.records import review_update
from app.services.records import public_encode
from app.services.review_reasons import reason_label
from app.services.workshop_access import record_needs_admin_approval, stamp_workshop_submission

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/review", tags=["review"])

# Appended to the ReviewLog notes when an approval clears a late-submission flag, so the audit trail
# says WHY this approval mattered: the record was submitted after its workshop had ended.
LATE_SUBMISSION_LOG_NOTE = "Late workshop submission — approved by an admin."

# ReviewLog has NO action column: its only verb is ``status``, typed as the ``RecordStatus`` ENUM
# (DRAFT | PENDING | APPROVED | REJECTED | NEEDS_REVISION). Adding an EDITED value would need a schema
# change plus a migration, and a reviewer edit is not a status in any case — it deliberately leaves the
# record's status alone. So the edit log row carries the record's UNCHANGED status and says what
# happened in ``notes``, prefixed with this marker so a UI (or a human) can pick edits out of the
# per-record log without parsing prose.
EDIT_LOG_PREFIX = "EDITED"


def delegate_for(record_type: str) -> tuple[Any, str, str]:
    """Delegate, ReviewLog record type, and the name of the relation pointing at the creator."""
    mapping = {
        "artisan": (db.artisan, "ARTISAN", "createdBy"),
        "workshop": (db.workshop, "WORKSHOP", "createdBy"),
        "product": (db.productdocumentation, "PRODUCT", "createdBy"),
        "tool": (db.tooldocumentation, "TOOL", "createdBy"),
        # Processes and interviews carry a status and can be pinned PENDING by the late-submission
        # gate, so they must be reviewable — without these a late process or interview was
        # unapprovable by anyone and sat in PENDING forever.
        "process": (db.process, "PROCESS", "createdBy"),
        "questionnaire": (db.questionnaireinterview, "QUESTIONNAIRE", "createdBy"),
        "media": (db.mediafile, "MEDIA", "uploadedBy"),
    }
    key = record_type.lower()
    if key not in mapping:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unsupported review record type")
    return mapping[key]


# Record types surfaced in the review queue, with the field used as a human label. Media is
# excluded so the queue stays focused on the substantive records researchers submit for approval.
_PENDING_SOURCES: list[tuple[str, Any, tuple[str, ...]]] = [
    ("artisan", db.artisan, ("name",)),
    ("workshop", db.workshop, ("title",)),
    ("product", db.productdocumentation, ("productName", "artisanName", "craftName")),
    ("tool", db.tooldocumentation, ("toolkitName", "englishName", "craftName")),
    ("process", db.process, ("name",)),
    ("questionnaire", db.questionnaireinterview, ("title",)),
]


# Per-record-type row cap on the UNPAGED queue (the answer older clients in the field still read). It
# is a cap on REVIEWABLE rows (see _reviewable_creator_roles), not on pending rows in general.
PENDING_TAKE = 200


def _reviewable_creator_roles(reviewer: Any) -> list[str] | None:
    """The creator roles this reviewer may act on, or None for "everyone" (the master admin).

    The peer-review ladder as DATA, so it can go into the query: the same comparison
    ``can_review_record`` makes — ranks STRICTLY below the reviewer — against the creator's role
    column. Every reviewable model has a required ``createdById`` and ``UserRole`` is NOT NULL, so the
    "no creator role on file" case ``can_review_record`` defends against does not arise here.
    """
    if is_master_admin(reviewer):
        return None
    rank = role_rank(reviewer)
    return [role for role, value in ROLE_RANK.items() if value < rank]


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


# Largest page a caller may ask for. The queue is read by people, twenty rows at a time on the web.
PENDING_MAX_PAGE_SIZE = 100

# The table behind each queue record type, for the one-page key query (B13). Model names are the
# table names in this schema (no @@map anywhere).
_PENDING_TABLES: dict[str, str] = {
    "artisan": '"Artisan"',
    "workshop": '"Workshop"',
    "product": '"ProductDocumentation"',
    "tool": '"ToolDocumentation"',
    "process": '"Process"',
    "questionnaire": '"QuestionnaireInterview"',
}

# ReviewLog's enum value per queue record type.
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
    """What narrows the queue. ``roles`` None = every creator (the master admin)."""

    roles: list[str] | None = None
    workshop_id: str | None = None
    created_by: str | None = None
    record_types: tuple[str, ...] | None = None
    newest_first: bool = False
    #: A task's artisans (B12): only records about these artisans. A workshop record has none.
    artisan_ids: tuple[str, ...] | None = None

    def sources(self) -> list[tuple[str, Any, tuple[str, ...]]]:
        if not self.record_types:
            return list(_PENDING_SOURCES)
        return [source for source in _PENDING_SOURCES if source[0] in self.record_types]


def pending_where(record_type: str, filters: PendingFilters) -> dict[str, Any]:
    """The Prisma filter for one record type's slice of the queue."""
    where: dict[str, Any] = {"status": "PENDING"}
    if filters.roles is not None:
        where["createdBy"] = {"is": {"role": {"in": filters.roles}}}
    if filters.created_by:
        where["createdById"] = filters.created_by
    workshop = filters.workshop_id
    if workshop:
        if record_type == "workshop":
            where["id"] = workshop
        elif record_type == "artisan":
            # An artisan reaches a workshop through the column or the roster join; both count.
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
            # A process has no artisan of its own; it inherits one through its parent product.
            scope = {"product": {"is": {"artisanId": {"in": artisans}}}}
        elif record_type == "questionnaire":
            scope = {"artisans": {"some": {"artisanId": {"in": artisans}}}}
        else:
            scope = {"id": {"in": []}}
        where = {"AND": [where, scope]}
    return where


def pending_keys_sql(filters: PendingFilters, *, limit: int, offset: int) -> tuple[str, list[Any]]:
    """ONE page of queue keys across every record type, longest-waiting first, as one SQL statement.

    The queue spans six tables, so "page 10" used to mean reading the newest 200 rows of each and
    sorting them in Python (1,200 rows to show 20). This asks Postgres for exactly the page:
    ``(recordType, id, submittedAt)`` from a UNION ALL, ordered and cut there. The rows themselves
    are then read by id. Roles come from ``ROLE_RANK`` (validated, never from the request) and are
    inlined; the workshop and creator ids travel as parameters.
    """
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
    """The (recordType, id) pairs on one page, in queue order. A seam the tests replace."""
    if not filters.sources():
        return []
    sql, params = pending_keys_sql(filters, limit=limit, offset=offset)
    rows = await db.query_raw(sql, *params)
    return [(str(row["recordType"]), str(row["id"])) for row in rows]


def _sort_moment(item: dict[str, Any]) -> str:
    return str(item.get("submittedAt") or item.get("createdAt") or "")


async def _previous_reviews(rows: list[tuple[str, Any]]) -> dict[tuple[str, str], dict[str, Any]]:
    """The last send-back or rejection for each RESUBMITTED pending row, keyed (recordType, id).

    A pending record with a ``reviewedAt`` was reviewed before and came back: the reviewer sees what
    was asked last time beside it, instead of reviewing the fix blind.
    """
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
    except Exception:  # the queue must still load; the flag is a help, not the record
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
        # When it (re-)entered the queue — the order the queue is served in.
        "submittedAt": jsonable_encoder(submitted),
        "createdBy": _creator_summary(get_value(row, "createdBy")),
        "needsAdminApproval": record_needs_admin_approval(row),
        "resubmitted": previous is not None,
        "previousReview": previous,
    }


@router.get("/reasons")
async def list_review_reasons(_: Any = Depends(require_reviewer)) -> list[dict[str, Any]]:
    """The short list of reasons for not accepting a record. ``fixable`` reasons send it back."""
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
    """Records awaiting review (status == PENDING) across record types, LONGEST-WAITING FIRST.

    The order is by ``submittedAt`` — when the record entered the queue, or came back into it after
    being sent back — so a fixed record goes to the front rather than sinking behind newer work.
    ``order=newest`` reverses it. Each item says whether it is a ``resubmitted`` record and carries
    the ``previousReview`` (the note and reason it was sent back with).

    Filtered by the peer-review ladder IN THE QUERY (only creators ranked strictly below the viewer;
    the master admin sees everyone), and optionally by ``workshopId``, ``createdById`` (a task's
    assignee, from Tasks), ``recordType`` and ``artisanId`` (comma-separated: a task's artisans). ``needsAdminApproval`` marks a record submitted outside
    its workshop's dates, which only an admin may approve.

    TWO SHAPES, the second additive (UX audit 2026-10-16):

    - **No ``page``** — the original answer for clients already in the field: up to ``PENDING_TAKE``
      rows per record type, merged; ``truncated`` says when a type hit that take.
    - **``page`` given** — exactly one page of the whole queue, read as one page (one key query
      across the six tables, then the page's rows by id), with the true ``total``.
    """
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
        # Review access with nobody beneath them on the ladder — a granted ``canReview`` on the
        # bottom tier. There is no record they may act on; six queries to prove it are wasted.
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
    return {"items": items, "total": total, "page": page, "pageSize": page_size}


# Media hangs off its record through a tag pair (linkedRecordType/linkedRecordId) and, on older rows,
# only through a typed column. Approving the record approves the files that hang off it (C21).
_MEDIA_TAGS: dict[str, tuple[str, ...]] = {
    "artisan": ("artisan",),
    "workshop": ("workshop",),
    "product": ("product",),
    "tool": ("tool",),
    "process": ("process",),
    "questionnaire": ("questionnaire", "questionnaireinterview"),
}
_MEDIA_COLUMNS: dict[str, str] = {
    "product": "productId",
    "tool": "toolId",
    "questionnaire": "questionnaireInterviewId",
}


async def approve_record_media(record_type: str, record_id: str, reviewer: Any) -> int:
    """Approve the still-pending files of a record that was just approved. Returns how many.

    A file submitted outside its workshop's dates waits for an admin exactly as a record does: a
    non-admin's approval of the parent leaves it pending, and an admin's clears its flag too.
    """
    key = record_type.lower()
    tags = _MEDIA_TAGS.get(key)
    if not tags:
        return 0
    clauses: list[dict[str, Any]] = [
        {"linkedRecordType": {"equals": tag, "mode": "insensitive"}, "linkedRecordId": record_id} for tag in tags
    ]
    if key == "process":
        steps = await db.processstep.find_many(where={"processId": record_id})
        step_ids = [str(get_value(step, "id")) for step in steps]
        if step_ids:
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
    files = await db.mediafile.find_many(where={"status": "PENDING", "OR": clauses}, include={"uploadedBy": True})
    # The review ladder applies to each file as it does to a record: a file uploaded by somebody at or
    # above the reviewer's own rank (an admin's photograph on a volunteer's record) stays pending for
    # a reviewer who may judge it.
    files = [
        f
        for f in files
        if can_review_record(reviewer, get_value(get_value(f, "uploadedBy"), "role") if get_value(f, "uploadedBy") else None)
    ]
    if not files:
        return 0
    admin = is_admin(reviewer)
    now = datetime.now(UTC)
    plain = [str(get_value(f, "id")) for f in files if not record_needs_admin_approval(f)]
    late = [f for f in files if record_needs_admin_approval(f)]
    approved_ids: list[str] = []
    approved = 0
    if plain:
        approved += int(
            await db.mediafile.update_many(
                where={"id": {"in": plain}, "status": "PENDING"},
                data={"status": "APPROVED", "reviewedById": reviewer.id, "reviewedAt": now},
            )
            or 0
        )
        approved_ids.extend(plain)
    if admin:
        for media in late:
            extra = get_value(media, "extraMetadata") or {}
            submission = extra.get("workshopSubmission") or {}
            await db.mediafile.update(
                where={"id": str(get_value(media, "id"))},
                data={
                    "status": "APPROVED",
                    "reviewedById": reviewer.id,
                    "reviewedAt": now,
                    "extraMetadata": Json({**extra, "workshopSubmission": {**submission, "needsAdminApproval": False}}),
                },
            )
            approved += 1
            approved_ids.append(str(get_value(media, "id")))
    # Every file approved through its parent leaves the same audit line a file approved alone does.
    for media_id in approved_ids:
        await db.reviewlog.create(
            data={
                "recordType": "MEDIA",
                "recordId": media_id,
                "status": "APPROVED",
                "notes": f"Approved with its {key} record.",
                "reviewerId": reviewer.id,
            }
        )
    return approved


async def set_review_status(
    record_type: str,
    record_id: str,
    new_status: str,
    payload: ReviewAction,
    reviewer: Any,
    *,
    notify: bool = True,
) -> dict[str, Any]:
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
    # A record submitted after its workshop ended carries needsAdminApproval in its extraMetadata.
    # ONLY an admin or master admin may approve it — the normal review ladder would otherwise let a
    # professor sign off on a researcher's late entry, which is exactly the decision reserved for
    # admins. Rejecting or sending it back for revision stays open to any qualified reviewer.
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
        # Approval IS that admin decision, so clear the flag (preserving the rest of the metadata)
        # so it keeps reflecting reality instead of flagging an already-approved record forever.
        extra = get_value(record, "extraMetadata")
        submission = extra.get("workshopSubmission")
        data["extraMetadata"] = Json(
            {**extra, "workshopSubmission": {**submission, "needsAdminApproval": False}}
        )
        notes = (payload.notes or "").strip()
        log_notes = f"{notes}\n\n{LATE_SUBMISSION_LOG_NOTE}" if notes else LATE_SUBMISSION_LOG_NOTE
    updated = await delegate.update(where={"id": record_id}, data=data)
    log_data: dict[str, Any] = {
        "recordType": log_type,
        "recordId": record_id,
        "status": new_status,
        "notes": log_notes,
        "reviewerId": reviewer.id,
    }
    if reason is not None:
        log_data["reason"] = reason.code
    await db.reviewlog.create(data=log_data)
    if new_status == "APPROVED" and record_type.lower() != "media":
        try:
            await approve_record_media(record_type, record_id, reviewer)
        except Exception:  # the record's own approval is saved; a file left pending is reviewable alone
            logger.exception("review: could not approve the files of %s %s", record_type, record_id)
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
    return public_encode(updated)


@router.post("/{record_type}/{record_id}/approve")
async def approve_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    return await set_review_status(record_type, record_id, "APPROVED", payload, reviewer)


def _resolve_refusal(payload: ReviewAction) -> tuple[str, ReviewAction]:
    """Which status a "Reject" with this reason really means, and the note it carries.

    No reason (an older client): a plain rejection, as before. A FIXABLE reason sends the record
    back for revision instead — the creator is told what to fix and the record is not a dead end.
    "Other" needs the reviewer's own words.
    """
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
    """Reject a record. With a FIXABLE ``reason`` it is sent back for revision instead (C19); the
    answer's ``status`` says which happened."""
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
    """Approve many records in one request (B9).

    Each record passes exactly the checks a single approval does — the review ladder and the
    admin-only gate for a late submission — and a refusal of one never stops the rest: every item
    comes back with its own ``outcome`` (``APPROVED`` or ``FAILED`` with the reason). The creators
    hear about it in ONE e-mail each, not one per record.
    """
    if not payload.items:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Choose at least one record.")
    results: list[dict[str, Any]] = []
    approved: list[dict[str, Any]] = []
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
    }


# The record types a creator's own "sent back" list covers, with the label fields of each.
_MINE_SOURCES = _PENDING_SOURCES


@router.get("/mine")
async def my_returned_records(
    current_user: Any = Depends(get_current_user),
    status_filter: str | None = Query(default=None, alias="status"),
) -> dict[str, Any]:
    """The caller's OWN records a reviewer sent back (``NEEDS_REVISION``) or rejected, across record
    types, each with the reviewer's note and reason — the uploader's "Sent back to you" list (A2).

    ``status`` narrows to one of the two; omitted, both come back, newest decision first.
    ``counts`` gives each, so a tile can say "Sent back to you (3)" without a second request.
    """
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
        # The counts are TRUE totals, not the rows read above (at most PENDING_TAKE per type), so the
        # dashboard tile and this list agree past the cap.
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
    """The last review decision on one record — what its edit form and its read-only page show.

    For the record's creator, an admin, and anyone who may review it. Answers ``{"review": null}``
    when nobody has decided anything yet.
    """
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
    """The media fields a reviewer may correct. MediaFile has no ``*Update`` schema of its own — the
    media routes patch it through purpose-built endpoints (relink, transcript refine) — so the
    reviewable surface is spelled out here: the human-written caption and the machine transcript a
    reviewer is often the first person qualified to fix."""

    caption: str | None = None
    transcriptText: str | None = None
    transcriptSummary: str | None = None


# The payload for each record type is validated against the SAME pydantic model its own PATCH route
# uses, so a reviewer edit cannot bypass a rule the normal edit path enforces (Aadhaar/Pehchan
# normalisation, length limits, decimal parsing) and cannot invent a column that does not exist —
# ``APIModel`` is ``extra="forbid"``, so an unknown key is a 422 rather than a 500 from Prisma.
_EDIT_SCHEMAS: dict[str, type[APIModel]] = {
    "artisan": ArtisanUpdate,
    "workshop": WorkshopUpdate,
    "product": ProductUpdate,
    "tool": ToolUpdate,
    "process": ProcessUpdate,
    "questionnaire": QuestionnaireInterviewUpdate,
    "media": MediaReviewEdit,
}

# Keys that are valid on the normal PATCH route but must never travel through a review edit:
#
# - ``status`` — an edit must not be a back-door approval. Status moves through approve/reject/revise
#   (or this endpoint's own ``approve`` flag), where the late-submission gate is applied.
# - ``extraMetadata`` — holds the SERVER-OWNED workshop-submission stamp; letting a reviewer post it
#   would let them clear ``needsAdminApproval`` and then approve their way past the admin gate.
# - ``workshopId`` — moving a record between workshops is a workshop submission with its own
#   assignment + window checks (``enforce_workshop_submission``); it belongs on the record's own PATCH.
# - the relation lists and ``location`` — those are separate writes (join rows, a Location row), not
#   column updates, and a review edit is for correcting field values. ``artisanIds``/``craftIds``
#   were named here for ``WorkshopUpdate``'s rosters and now cover ``ToolUpdate`` too, which gained
#   the same two keys with migration 20260915100000 — same reason, no new entry needed. A reviewer
#   who needs to change which crafts a tool covers sends them to the tool's own PATCH, where the
#   404/403 checks on the ids live.
# - ``formContract`` — a statement about the artisan FORM a save came from, not a column; a review
#   edit corrects fields and is not that form (``services/artisan_form_contract``).
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
    """``fields`` is validated against the record type's own update schema (see ``_EDIT_SCHEMAS``).

    ``approve=true`` runs the ordinary approval immediately after the edit — the common "fix the typo
    and sign it off" case — as a SECOND, separately logged action, so the audit trail still shows the
    edit and the approval as two distinct decisions and the approval still passes the admin gate.
    """

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
    """Fix a record's field values in place from the review queue, instead of bouncing it back.

    Sending a record back for revision costs a round trip through its creator for what is often a
    misspelt village or a craft name in the wrong column — work the reviewer can simply do. This is
    that shortcut, and it is the one review action that WRITES the record, so it is gated on the
    editing authority rather than the reviewing one:

    * ``can_edit_others_record``: the peer-review ladder narrowed to Professor and above. Reviewing
      reaches further down the ladder than editing deliberately — a field contributor reviews a
      volunteer's submission and a researcher reviews a field contributor's, and neither of them
      gets to rewrite it. Everyone below the floor still has approve / reject / revise;
    * the admin-only gate for a record flagged ``needsAdminApproval`` (submitted after its workshop
      ended) — the whole edit is admin-only there, not just the optional approval, because acting on a
      late submission at all is the decision reserved for admins.

    That predicate is also what makes the record's own PATCH route and this one agree about who may
    overwrite a populated field. There, an editor who is neither the author nor an admin is put
    through ``assert_can_contribute_fields``, which locks every field that already has a value —
    UNLESS ``may_edit_lower_ranked_record`` says they outrank its author, which is
    ``can_edit_others_record``. So the callers this route now admits are exactly the callers the
    PATCH route waves past that lock; no second field-level check is needed here, and before the
    gate was narrowed there was no honest way to say that.

    The change is attributable twice over: a ``RecordRevision`` captures the exact old -> new values
    (the same edit history ``services/access.py`` writes for a granted cross-researcher edit) and a
    ``ReviewLog`` row records that this reviewer edited the record and why.

    The status is NOT touched unless ``approve`` is set — an edit is not an approval.
    """
    key = record_type.lower()
    delegate, log_type, creator_relation = delegate_for(record_type)
    schema = _EDIT_SCHEMAS.get(key)
    if schema is None:  # pragma: no cover - delegate_for already rejects unknown types
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

    # Same shape as every PATCH route: clean (which also title-cases the name-like fields) and
    # stringify decimals before anything compares the payload against the stored row. An identity
    # number that came back as its own mask is dropped here for the same reason PATCH /artisans
    # drops it — a reviewer's form is fed by the same masked responses.
    data = drop_masked_identity_numbers(decimal_to_string(clean_data(parsed.model_dump(exclude_unset=True))))
    if not data:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Send at least one field to change in 'fields'",
        )
    changed = sorted(
        field for field, value in data.items() if not values_match(get_value(record, field), value)
    )

    # Audit BEFORE provenance is merged in, so the revision holds real content changes only.
    await record_revision(record, reviewer, data, key)
    # Provenance rebuilds extraMetadata from this payload, which would drop the workshop-submission
    # stamp; carry it forward first, exactly as the record's own PATCH route does.
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
            # The record's own, unchanged status — see EDIT_LOG_PREFIX for why the verb lives in notes.
            "status": str(enum_or_raw(get_value(record, "status"))),
            "notes": f"{summary}\n\n{note}" if note else summary,
            "reviewerId": reviewer.id,
        }
    )

    if payload.approve:
        # Re-reads the freshly edited row and re-applies the late-submission gate, so approving here
        # is identical to calling /approve straight afterwards — including its own ReviewLog entry.
        return await set_review_status(record_type, record_id, "APPROVED", ReviewAction(notes=payload.note), reviewer)
    return public_encode(updated)


@router.post("/{record_type}/{record_id}/revise")
async def send_record_for_revision(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    """Send the record back to its creator with mandatory comments (status NEEDS_REVISION).

    When the creator next edits the record it returns to PENDING for a fresh review pass. An
    optional ``reason`` from ``GET /review/reasons`` is recorded with it."""
    if not payload.notes or not payload.notes.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Comments are required when sending a record back for revision",
        )
    if payload.reason and review_reasons.reason_for(payload.reason) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Pick a reason from the list.",
        )
    return await set_review_status(record_type, record_id, "NEEDS_REVISION", payload, reviewer)
