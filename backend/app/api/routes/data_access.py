"""Cross-researcher data sharing: tiered access requests/grants, comments, and edit history.

Tiers (strictly increasing): DOWNLOAD < COMMENT < EDIT — see app.services.access for the enforcement
used across the record routes. This module owns the request/grant lifecycle plus the comment and
revision read/write endpoints.
"""
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import get_current_user, get_value, is_admin
from app.schemas.access import (
    TIERS,
    CommentIn,
    DataAccessDecisionIn,
    DataAccessGrantIn,
    DataAccessRequestIn,
    DataAccessUpdateIn,
)
from app.services.access import (
    TIER_DESCRIPTIONS,
    effective_tier_for_record,
    tier_at_least,
)
from app.services.records import public_encode

router = APIRouter(prefix="/data-access", tags=["data-access"])

GRANT_INCLUDE = {"owner": True, "grantee": True, "requestedBy": True, "decidedBy": True, "scopeItems": True}

# record type -> (delegate, owner-id field). Used to resolve a record's owner for comment/edit checks.
RECORD_DELEGATES: dict[str, tuple[Any, str]] = {
    "artisan": (db.artisan, "createdById"),
    "craft": (db.craft, "createdById"),
    "workshop": (db.workshop, "createdById"),
    "product": (db.productdocumentation, "createdById"),
    "tool": (db.tooldocumentation, "createdById"),
    "process": (db.process, "createdById"),
    "questionnaire": (db.questionnaireinterview, "createdById"),
    "questionnaireinterview": (db.questionnaireinterview, "createdById"),
    "media": (db.mediafile, "uploadedById"),
}


def _validate_tier(tier: str | None) -> None:
    if tier is not None and tier not in TIERS:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid access tier")


async def _resolve_record_owner(record_type: str, record_id: str) -> str | None:
    entry = RECORD_DELEGATES.get(record_type.lower())
    if entry is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported record type")
    delegate, owner_field = entry
    record = await delegate.find_unique(where={"id": record_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return get_value(record, owner_field)


def _scope_create(scope_items: list[Any] | None) -> list[dict[str, Any]]:
    return [{"recordType": s.recordType.lower(), "recordId": s.recordId} for s in (scope_items or [])]


@router.get("/tiers")
async def list_tiers(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    """The tier catalogue with human definitions, so the UI can show exactly what each tier confers."""
    return [{"tier": tier, "description": TIER_DESCRIPTIONS[tier]} for tier in ("DOWNLOAD", "COMMENT", "EDIT")]


@router.get("/grants")
async def my_grants(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Everything access-related for the current user, split by perspective:
    - incoming: requests/grants where I am the OWNER (others want or have access to my data)
    - outgoing: requests/grants where I am the GRANTEE (my access to others' data)
    """
    uid = get_value(current_user, "id")
    incoming = await db.dataaccessgrant.find_many(
        where={"ownerId": uid}, include=GRANT_INCLUDE, order={"updatedAt": "desc"}
    )
    outgoing = await db.dataaccessgrant.find_many(
        where={"granteeId": uid}, include=GRANT_INCLUDE, order={"updatedAt": "desc"}
    )
    return public_encode({"incoming": incoming, "outgoing": outgoing})


async def _upsert_grant(
    owner_id: str,
    grantee_id: str,
    *,
    tier: str,
    all_data: bool,
    scope_items: list[Any] | None,
    status_value: str,
    request_note: str | None,
    decision_note: str | None,
    requested_by: str | None,
    decided_by: str | None,
) -> Any:
    """Create or replace the single (owner, grantee) grant row, rebuilding its subset items."""
    existing = await db.dataaccessgrant.find_unique(
        where={"ownerId_granteeId": {"ownerId": owner_id, "granteeId": grantee_id}}
    )
    data: dict[str, Any] = {
        "tier": tier,
        "status": status_value,
        "allData": all_data,
    }
    if request_note is not None:
        data["requestNote"] = request_note
    if decision_note is not None:
        data["decisionNote"] = decision_note
    if requested_by is not None:
        data["requestedById"] = requested_by
    if decided_by is not None:
        data["decidedById"] = decided_by
        from datetime import UTC, datetime

        data["decidedAt"] = datetime.now(UTC)
    if existing is None:
        grant = await db.dataaccessgrant.create(
            data={**data, "ownerId": owner_id, "granteeId": grantee_id}
        )
    else:
        grant = await db.dataaccessgrant.update(where={"id": existing.id}, data=data)
        await db.dataaccessscopeitem.delete_many(where={"grantId": grant.id})
    if not all_data:
        for item in _scope_create(scope_items):
            await db.dataaccessscopeitem.create(data={**item, "grantId": grant.id})
    return await db.dataaccessgrant.find_unique(where={"id": grant.id}, include=GRANT_INCLUDE)


@router.post("/requests", status_code=status.HTTP_201_CREATED)
async def request_access(payload: DataAccessRequestIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """A researcher requests access to another researcher's data (PENDING until the owner decides)."""
    _validate_tier(payload.tier)
    uid = get_value(current_user, "id")
    if payload.ownerId == uid:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="You already own your data")
    owner = await db.user.find_unique(where={"id": payload.ownerId})
    if owner is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Owner not found")
    grant = await _upsert_grant(
        payload.ownerId,
        uid,
        tier=payload.tier,
        all_data=payload.allData,
        scope_items=payload.scopeItems,
        status_value="PENDING",
        request_note=payload.requestNote,
        decision_note=None,
        requested_by=uid,
        decided_by=None,
    )
    return public_encode(grant)


@router.post("/grants", status_code=status.HTTP_201_CREATED)
async def grant_access(payload: DataAccessGrantIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """An owner proactively grants a researcher access to their data (immediately GRANTED)."""
    _validate_tier(payload.tier)
    uid = get_value(current_user, "id")
    if payload.granteeId == uid:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="You already have your own data")
    grantee = await db.user.find_unique(where={"id": payload.granteeId})
    if grantee is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Grantee not found")
    grant = await _upsert_grant(
        uid,
        payload.granteeId,
        tier=payload.tier,
        all_data=payload.allData,
        scope_items=payload.scopeItems,
        status_value="GRANTED",
        request_note=None,
        decision_note=payload.decisionNote,
        requested_by=None,
        decided_by=uid,
    )
    return public_encode(grant)


async def _require_owned_grant(grant_id: str, current_user: Any) -> Any:
    grant = await db.dataaccessgrant.find_unique(where={"id": grant_id}, include={"scopeItems": True})
    if grant is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Grant not found")
    if grant.ownerId != get_value(current_user, "id") and not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the data owner can change this")
    return grant


@router.post("/grants/{grant_id}/decide")
async def decide_request(grant_id: str, payload: DataAccessDecisionIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The owner approves (GRANTED) or denies (DENIED) a pending request, optionally adjusting tier/scope."""
    if payload.status not in {"GRANTED", "DENIED"}:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="status must be GRANTED or DENIED")
    _validate_tier(payload.tier)
    grant = await _require_owned_grant(grant_id, current_user)
    grant = await _upsert_grant(
        grant.ownerId,
        grant.granteeId,
        tier=payload.tier or _tier_str(grant.tier),
        all_data=payload.allData if payload.allData is not None else grant.allData,
        scope_items=payload.scopeItems if payload.scopeItems is not None else grant.scopeItems,
        status_value=payload.status,
        request_note=None,
        decision_note=payload.decisionNote,
        requested_by=None,
        decided_by=get_value(current_user, "id"),
    )
    return public_encode(grant)


@router.patch("/grants/{grant_id}")
async def update_grant(grant_id: str, payload: DataAccessUpdateIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The owner edits an existing grant (change tier, scope, or revoke/reinstate)."""
    if payload.status is not None and payload.status not in {"GRANTED", "REVOKED"}:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="status must be GRANTED or REVOKED")
    _validate_tier(payload.tier)
    grant = await _require_owned_grant(grant_id, current_user)
    grant = await _upsert_grant(
        grant.ownerId,
        grant.granteeId,
        tier=payload.tier or _tier_str(grant.tier),
        all_data=payload.allData if payload.allData is not None else grant.allData,
        scope_items=payload.scopeItems if payload.scopeItems is not None else grant.scopeItems,
        status_value=payload.status or _status_str(grant.status),
        request_note=None,
        decision_note=None,
        requested_by=None,
        decided_by=get_value(current_user, "id"),
    )
    return public_encode(grant)


@router.post("/grants/{grant_id}/revoke")
async def revoke_grant(grant_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    grant = await _require_owned_grant(grant_id, current_user)
    updated = await db.dataaccessgrant.update(
        where={"id": grant.id}, data={"status": "REVOKED"}, include=GRANT_INCLUDE
    )
    return public_encode(updated)


@router.delete("/grants/{grant_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_grant(grant_id: str, current_user: Any = Depends(get_current_user)) -> None:
    grant = await db.dataaccessgrant.find_unique(where={"id": grant_id})
    if grant is None:
        return
    uid = get_value(current_user, "id")
    # Either party (or an admin) may remove the relationship entirely.
    if grant.ownerId != uid and grant.granteeId != uid and not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not your grant")
    await db.dataaccessgrant.delete(where={"id": grant.id})


def _tier_str(value: Any) -> str:
    return str(getattr(value, "value", value))


def _status_str(value: Any) -> str:
    return str(getattr(value, "value", value))


# ----------------------------------------------------------------------------- comments

@router.get("/comments")
async def list_comments(
    recordType: str = Query(..., min_length=1),
    recordId: str = Query(..., min_length=1),
    _: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """Comments on a record. Readable by any authenticated user (all records are viewable); posting
    requires COMMENT tier (or owner/admin)."""
    rows = await db.entrycomment.find_many(
        where={"recordType": recordType.lower(), "recordId": recordId},
        include={"author": True},
        order={"createdAt": "asc"},
    )
    return public_encode(rows)


@router.post("/comments", status_code=status.HTTP_201_CREATED)
async def add_comment(payload: CommentIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    owner_id = await _resolve_record_owner(payload.recordType, payload.recordId)
    tier = await effective_tier_for_record(current_user, owner_id, payload.recordType, payload.recordId)
    if not tier_at_least(tier, "COMMENT"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need comment access to this researcher's data. Request it from them.",
        )
    created = await db.entrycomment.create(
        data={
            "recordType": payload.recordType.lower(),
            "recordId": payload.recordId,
            "authorId": get_value(current_user, "id"),
            "body": payload.body,
        },
        include={"author": True},
    )
    return public_encode(created)


@router.delete("/comments/{comment_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_comment(comment_id: str, current_user: Any = Depends(get_current_user)) -> None:
    comment = await db.entrycomment.find_unique(where={"id": comment_id})
    if comment is None:
        return
    if comment.authorId != get_value(current_user, "id") and not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only delete your own comments")
    await db.entrycomment.delete(where={"id": comment_id})


# ----------------------------------------------------------------------------- revisions (edit history)

@router.get("/revisions")
async def list_revisions(
    recordType: str = Query(..., min_length=1),
    recordId: str = Query(..., min_length=1),
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """The edit history of a record (original values are reconstructable from the first edit's `old`).
    Visible to admins and the record's owner so the original and every subsequent edit can be reviewed.
    """
    owner_id = await _resolve_record_owner(recordType, recordId)
    if not is_admin(current_user) and owner_id != get_value(current_user, "id"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the owner or an admin can view edit history")
    rows = await db.recordrevision.find_many(
        where={"recordType": recordType.lower(), "recordId": recordId},
        include={"editedBy": True},
        order={"createdAt": "asc"},
    )
    return public_encode(rows)
