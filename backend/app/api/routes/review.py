from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import require_admin
from app.schemas.common import ReviewAction
from app.services.records import require_record, review_update

router = APIRouter(prefix="/review", tags=["review"])


def delegate_for(record_type: str) -> tuple[Any, str]:
    mapping = {
        "artisan": (db.artisan, "ARTISAN"),
        "workshop": (db.workshop, "WORKSHOP"),
        "product": (db.productdocumentation, "PRODUCT"),
        "tool": (db.tooldocumentation, "TOOL"),
        "media": (db.mediafile, "MEDIA"),
    }
    key = record_type.lower()
    if key not in mapping:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unsupported review record type")
    return mapping[key]


async def set_review_status(
    record_type: str,
    record_id: str,
    new_status: str,
    payload: ReviewAction,
    reviewer: Any,
) -> dict[str, Any]:
    delegate, log_type = delegate_for(record_type)
    await require_record(delegate, record_id)
    updated = await delegate.update(
        where={"id": record_id},
        data=review_update(new_status, payload.notes, reviewer.id),
    )
    await db.reviewlog.create(
        data={
            "recordType": log_type,
            "recordId": record_id,
            "status": new_status,
            "notes": payload.notes,
            "reviewerId": reviewer.id,
        }
    )
    return jsonable_encoder(updated)


@router.post("/{record_type}/{record_id}/approve")
async def approve_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_admin),
) -> dict[str, Any]:
    return await set_review_status(record_type, record_id, "APPROVED", payload, reviewer)


@router.post("/{record_type}/{record_id}/reject")
async def reject_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_admin),
) -> dict[str, Any]:
    return await set_review_status(record_type, record_id, "REJECTED", payload, reviewer)
