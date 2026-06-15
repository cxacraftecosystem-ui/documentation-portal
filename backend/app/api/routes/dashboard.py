from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user
from app.services.records import visibility_where

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


async def recent_for(delegate: Any, record_type: str, where: dict[str, Any]) -> list[dict[str, Any]]:
    rows = await delegate.find_many(where=where, take=5, order={"createdAt": "desc"})
    return [
        {
            "id": row.id,
            "type": record_type,
            "status": str(row.status),
            "createdAt": row.createdAt,
            "title": getattr(row, "name", None)
            or getattr(row, "title", None)
            or getattr(row, "productName", None)
            or getattr(row, "toolkitName", None),
            "place": getattr(row, "place", None),
        }
        for row in rows
    ]


@router.get("/stats")
async def dashboard_stats(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    owner_where = visibility_where(current_user)
    media_where = visibility_where(current_user, owner_field="uploadedById")

    artisans = await db.artisan.count(where=owner_where)
    workshops = await db.workshop.count(where=owner_where)
    products = await db.productdocumentation.count(where=owner_where)
    tools = await db.tooldocumentation.count(where=owner_where)
    media = await db.mediafile.count(where=media_where)

    recent = []
    recent.extend(await recent_for(db.artisan, "artisan", owner_where))
    recent.extend(await recent_for(db.workshop, "workshop", owner_where))
    recent.extend(await recent_for(db.productdocumentation, "product", owner_where))
    recent.extend(await recent_for(db.tooldocumentation, "tool", owner_where))
    recent = sorted(recent, key=lambda item: item["createdAt"], reverse=True)[:10]

    pending_where = dict(owner_where)
    pending_where["status"] = "PENDING"
    pending_submissions = (
        await db.artisan.count(where=pending_where)
        + await db.workshop.count(where=pending_where)
        + await db.productdocumentation.count(where=pending_where)
        + await db.tooldocumentation.count(where=pending_where)
    )

    return jsonable_encoder(
        {
            "totalArtisans": artisans,
            "totalWorkshops": workshops,
            "totalProductRecords": products,
            "totalToolRecords": tools,
            "totalMediaFiles": media,
            "pendingSubmissions": pending_submissions,
            "recentSubmissions": recent,
        }
    )
