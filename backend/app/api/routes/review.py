from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.encoders import jsonable_encoder
from prisma import Json
from pydantic import Field, ValidationError

from app.core.db import db
from app.core.deps import can_review_record, enum_or_raw, get_value, is_admin, require_reviewer, values_match
from app.schemas.common import APIModel, ReviewAction
from app.schemas.questionnaire import QuestionnaireInterviewUpdate
from app.schemas.records import ArtisanUpdate, ProcessUpdate, ProductUpdate, ToolUpdate, WorkshopUpdate
from app.services.access import record_revision
from app.services.records import clean_data, decimal_to_string, jsonify_metadata, merge_field_provenance
from app.services.records import review_update
from app.services.records import public_encode
from app.services.workshop_access import record_needs_admin_approval, stamp_workshop_submission

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


@router.get("/pending")
async def list_pending_reviews(reviewer: Any = Depends(require_reviewer)) -> dict[str, Any]:
    """Records still awaiting review (status == PENDING), newest first, across record types.

    Filtered by the peer-review hierarchy: only records whose creator ranks strictly below the
    viewer show up (the master admin sees everything). Each item carries ``needsAdminApproval``:
    true means it was submitted outside its workshop's dates, so only an admin can approve it."""
    items: list[dict[str, Any]] = []
    for record_type, delegate, label_fields in _PENDING_SOURCES:
        rows = await delegate.find_many(
            where={"status": "PENDING"},
            include={"createdBy": True},
            order={"createdAt": "desc"},
            take=200,
        )
        for row in rows:
            creator = get_value(row, "createdBy")
            if not can_review_record(reviewer, get_value(creator, "role") if creator else None):
                continue
            items.append(
                {
                    "recordType": record_type,
                    "id": get_value(row, "id"),
                    "label": _label_for(row, label_fields),
                    "place": get_value(row, "place"),
                    "createdAt": jsonable_encoder(get_value(row, "createdAt")),
                    "createdBy": _creator_summary(creator),
                    "needsAdminApproval": record_needs_admin_approval(row),
                }
            )
    items.sort(key=lambda item: item.get("createdAt") or "", reverse=True)
    return {"items": items, "total": len(items)}


async def set_review_status(
    record_type: str,
    record_id: str,
    new_status: str,
    payload: ReviewAction,
    reviewer: Any,
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
    await db.reviewlog.create(
        data={
            "recordType": log_type,
            "recordId": record_id,
            "status": new_status,
            "notes": log_notes,
            "reviewerId": reviewer.id,
        }
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


@router.post("/{record_type}/{record_id}/reject")
async def reject_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    return await set_review_status(record_type, record_id, "REJECTED", payload, reviewer)


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
#   column updates, and a review edit is for correcting field values.
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
    that shortcut, under exactly the same authority as the other review actions:

    * the peer-review ladder (``can_review_record``): only a record created by someone strictly below
      the reviewer, master admin excepted;
    * the admin-only gate for a record flagged ``needsAdminApproval`` (submitted after its workshop
      ended) — the whole edit is admin-only there, not just the optional approval, because acting on a
      late submission at all is the decision reserved for admins.

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
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Send at least one field to change in 'fields'",
        )
    blocked = sorted(set(payload.fields) & _NOT_REVIEW_EDITABLE)
    if blocked:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
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
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=jsonable_encoder(exc.errors(include_url=False)),
        ) from exc

    record = await delegate.find_unique(where={"id": record_id}, include={creator_relation: True})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    creator = get_value(record, creator_relation)
    if not can_review_record(reviewer, get_value(creator, "role") if creator else None):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only review records created by users below your own tier",
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
    # stringify decimals before anything compares the payload against the stored row.
    data = decimal_to_string(clean_data(parsed.model_dump(exclude_unset=True)))
    if not data:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
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

    When the creator next edits the record it returns to PENDING for a fresh review pass."""
    if not payload.notes or not payload.notes.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Comments are required when sending a record back for revision",
        )
    return await set_review_status(record_type, record_id, "NEEDS_REVISION", payload, reviewer)
