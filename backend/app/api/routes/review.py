from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_value, require_reviewer
from app.schemas.common import ReviewAction
from app.services.records import require_record, review_update
from app.services.records import public_encode

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


# Record types surfaced in the review queue, with the field used as a human label. Media is
# excluded so the queue stays focused on the substantive records researchers submit for approval.
_PENDING_SOURCES: list[tuple[str, Any, tuple[str, ...]]] = [
    ("artisan", db.artisan, ("name",)),
    ("workshop", db.workshop, ("title",)),
    ("product", db.productdocumentation, ("productName", "artisanName", "craftName")),
    ("tool", db.tooldocumentation, ("toolkitName", "englishName", "craftName")),
]


def _label_for(row: Any, fields: tuple[str, ...]) -> str:
    for field in fields:
        value = get_value(row, field)
        if value:
            return str(value)
    return str(get_value(row, "id") or "Record")


@router.get("/pending")
async def list_pending_reviews(_: Any = Depends(require_reviewer)) -> dict[str, Any]:
    """Records still awaiting review (status == PENDING), newest first, across record types."""
    items: list[dict[str, Any]] = []
    for record_type, delegate, label_fields in _PENDING_SOURCES:
        rows = await delegate.find_many(
            where={"status": "PENDING"},
            order={"createdAt": "desc"},
            take=200,
        )
        for row in rows:
            items.append(
                {
                    "recordType": record_type,
                    "id": get_value(row, "id"),
                    "label": _label_for(row, label_fields),
                    "place": get_value(row, "place"),
                    "createdAt": jsonable_encoder(get_value(row, "createdAt")),
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
