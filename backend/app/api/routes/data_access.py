from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    can_access_review,
    can_review_record,
    get_current_user,
    get_value,
    is_admin,
    is_content_admin,
)
from app.schemas.access import (
    TIERS,
    CommentIn,
    CommentUpdate,
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
from app.services import email_outbox, mailer
from app.services.artisan_merge import survivor_of
from app.services.records import public_encode

router = APIRouter(prefix="/data-access", tags=["data-access"])

GRANT_INCLUDE = {"owner": True, "grantee": True, "requestedBy": True, "decidedBy": True, "scopeItems": True}

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
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Invalid access tier")


async def _resolve_record(record_type: str, record_id: str) -> tuple[Any, str | None]:
    entry = RECORD_DELEGATES.get(record_type.lower())
    if entry is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported record type")
    delegate, owner_field = entry
    record = await delegate.find_unique(where={"id": record_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record, get_value(record, owner_field)


async def _resolve_record_owner(record_type: str, record_id: str) -> str | None:
    _record, owner_id = await _resolve_record(record_type, record_id)
    return owner_id


async def _reviews_owner(user: Any, owner_id: str | None) -> bool:
    if not owner_id or not can_access_review(user):
        return False
    owner = await db.user.find_unique(where={"id": owner_id})
    return owner is not None and can_review_record(user, get_value(owner, "role"))


_LABEL_FIELDS = ("name", "title", "productName", "toolkitName", "englishName", "originalFilename")


def _record_label(record: Any) -> str:
    for field in _LABEL_FIELDS:
        value = get_value(record, field)
        if value:
            return str(value)
    return "a record"


async def _comment_label(record_type: str, record_id: str) -> str:
    if not mailer.mail_configured():
        return "a record"
    try:
        record, _owner = await _resolve_record(record_type, record_id)
    except Exception:
        return "a record"
    return _record_label(record)


def _scope_create(scope_items: list[Any] | None) -> list[dict[str, Any]]:
    return [{"recordType": s.recordType.lower(), "recordId": s.recordId} for s in (scope_items or [])]


@router.get("/tiers")
async def list_tiers(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    return [{"tier": tier, "description": TIER_DESCRIPTIONS[tier]} for tier in ("DOWNLOAD", "COMMENT", "EDIT")]


@router.get("/grants")
async def my_grants(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
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
    _validate_tier(payload.tier)
    uid = get_value(current_user, "id")
    if payload.ownerId == uid:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="You already own your data")
    owner = await db.user.find_unique(where={"id": payload.ownerId})
    if owner is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Owner not found")
    request_note = payload.requestNote
    existing = await db.dataaccessgrant.find_unique(
        where={"ownerId_granteeId": {"ownerId": payload.ownerId, "granteeId": uid}}
    )
    if existing is not None and _status_str(existing.status) == "GRANTED":
        granted_tier = _tier_str(existing.tier)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"You already have an active grant ({granted_tier}) from this researcher. "
            "To raise the tier or broaden the scope, ask the owner — they can adjust the "
            "existing grant from their Sharing page without interrupting your access.",
        )
    grant = await _upsert_grant(
        payload.ownerId,
        uid,
        tier=payload.tier,
        all_data=payload.allData,
        scope_items=payload.scopeItems,
        status_value="PENDING",
        request_note=request_note,
        decision_note=None,
        requested_by=uid,
        decided_by=None,
    )
    await email_outbox.notify_access_request(
        owner_id=payload.ownerId, requester=current_user, tier=payload.tier, note=request_note
    )
    return public_encode(grant)


@router.post("/grants", status_code=status.HTTP_201_CREATED)
async def grant_access(payload: DataAccessGrantIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
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
    if payload.status not in {"GRANTED", "DENIED"}:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="status must be GRANTED or DENIED")
    _validate_tier(payload.tier)
    grant = await _require_owned_grant(grant_id, current_user)
    if _status_str(grant.status) != "PENDING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This request has already been decided. Change access from the list.",
        )
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
    await email_outbox.notify_access_decision(
        grantee_id=get_value(grant, "granteeId"),
        owner=current_user,
        decision=payload.status,
        note=payload.decisionNote,
    )
    return public_encode(grant)


@router.patch("/grants/{grant_id}")
async def update_grant(grant_id: str, payload: DataAccessUpdateIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    if payload.status is not None and payload.status not in {"GRANTED", "REVOKED"}:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="status must be GRANTED or REVOKED")
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
    if grant.ownerId != uid and grant.granteeId != uid and not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not your grant")
    await db.dataaccessgrant.delete(where={"id": grant.id})


def _tier_str(value: Any) -> str:
    return str(getattr(value, "value", value))


def _status_str(value: Any) -> str:
    return str(getattr(value, "value", value))


@router.get("/comments")
async def list_comments(
    recordType: str = Query(..., min_length=1),
    recordId: str = Query(..., min_length=1),
    _: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    rows = await db.entrycomment.find_many(
        where={"recordType": recordType.lower(), "recordId": recordId},
        include={"author": True},
        order={"createdAt": "asc"},
    )
    return public_encode(rows)


@router.post("/comments", status_code=status.HTTP_201_CREATED)
async def add_comment(payload: CommentIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    if payload.recordType.strip().lower() == "artisan":
        payload.recordId = await survivor_of(payload.recordId) or payload.recordId
    owner_id = await _resolve_record_owner(payload.recordType, payload.recordId)
    tier = await effective_tier_for_record(current_user, owner_id, payload.recordType, payload.recordId)
    if not tier_at_least(tier, "COMMENT") and not await _reviews_owner(current_user, owner_id):
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
    await email_outbox.notify_comment(
        record_type=payload.recordType.lower(),
        record_id=payload.recordId,
        record_label=await _comment_label(payload.recordType, payload.recordId),
        owner_id=owner_id,
        actor=current_user,
        body=payload.body,
    )
    return public_encode(created)


@router.patch("/comments/{comment_id}")
async def update_comment(
    comment_id: str, payload: CommentUpdate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    comment = await db.entrycomment.find_unique(where={"id": comment_id})
    if comment is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Comment not found")
    if comment.authorId != get_value(current_user, "id"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only edit your own comments")
    updated = await db.entrycomment.update(
        where={"id": comment_id}, data={"body": payload.body}, include={"author": True}
    )
    return public_encode(updated)


@router.delete("/comments/{comment_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_comment(comment_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    comment = await db.entrycomment.find_unique(where={"id": comment_id})
    if comment is None:
        return
    await db.entrycomment.delete(where={"id": comment_id})


@router.get("/revisions")
async def list_revisions(
    recordType: str = Query(..., min_length=1),
    recordId: str = Query(..., min_length=1),
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    owner_id = await _resolve_record_owner(recordType, recordId)
    if (
        not is_content_admin(current_user)
        and owner_id != get_value(current_user, "id")
        and not await _reviews_owner(current_user, owner_id)
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the owner, a reviewer or an admin can view edit history",
        )
    rows = await db.recordrevision.find_many(
        where={"recordType": recordType.lower(), "recordId": recordId},
        include={"editedBy": True},
        order={"createdAt": "asc"},
    )
    return public_encode(rows)
