from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user, is_admin
from app.services.access_roster import pending_count
from app.services.concurrency import gather_reads
from app.services.records import own_rows_where, viewable_where

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


def rows_to_recent(rows: list[Any], record_type: str) -> list[dict[str, Any]]:
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
            "createdByName": getattr(getattr(row, "createdBy", None), "name", None),
        }
        for row in rows
    ]


def _count_of(groups: list[Any], state: str) -> int:
    return sum(group["_count"]["_all"] for group in groups if str(group["status"]) == state)


def _totals_by_status(groups: list[Any]) -> tuple[int, int]:
    counts = {str(group["status"]): group["_count"]["_all"] for group in groups}
    return sum(counts.values()), counts.get("PENDING", 0)


@router.get("/stats")
async def dashboard_stats(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    view_where, media_view_where = await gather_reads(
        viewable_where(current_user),
        viewable_where(current_user, owner_field="uploadedById"),
    )
    own_where = await own_rows_where(current_user)
    own_media_where = await own_rows_where(current_user, owner_field="uploadedById")

    grouped = (db.artisan, db.workshop, db.productdocumentation, db.tooldocumentation)

    def pending(where: dict[str, Any]) -> dict[str, Any]:
        return {**where, "status": "PENDING"}

    (
        artisan_groups,
        workshop_groups,
        product_groups,
        tool_groups,
        media,
        pending_interviews,
        pending_processes,
        recent_artisans,
        recent_workshops,
        recent_products,
        recent_tools,
        mine_artisan_groups,
        mine_workshop_groups,
        mine_product_groups,
        mine_tool_groups,
        mine_media,
        mine_interview_groups,
        mine_process_groups,
    ) = await gather_reads(
        *(delegate.group_by(by=["status"], count=True, where=view_where) for delegate in grouped),
        db.mediafile.count(where=media_view_where),
        db.questionnaireinterview.count(where=pending(view_where)),
        db.process.count(where=pending(view_where)),
        *(
            delegate.find_many(
                where=view_where,
                take=5,
                order={"createdAt": "desc"},
                include={"createdBy": True},
            )
            for delegate in grouped
        ),
        *(delegate.group_by(by=["status"], count=True, where=own_where) for delegate in grouped),
        db.mediafile.count(where=own_media_where),
        db.questionnaireinterview.group_by(by=["status"], count=True, where=own_where),
        db.process.group_by(by=["status"], count=True, where=own_where),
    )

    artisans, pending_artisans = _totals_by_status(artisan_groups)
    workshops, pending_workshops = _totals_by_status(workshop_groups)
    products, pending_products = _totals_by_status(product_groups)
    tools, pending_tools = _totals_by_status(tool_groups)
    pending_submissions = (
        pending_artisans
        + pending_workshops
        + pending_products
        + pending_tools
        + pending_interviews
        + pending_processes
    )

    mine_artisans, mine_pending_artisans = _totals_by_status(mine_artisan_groups)
    mine_workshops, mine_pending_workshops = _totals_by_status(mine_workshop_groups)
    mine_products, mine_pending_products = _totals_by_status(mine_product_groups)
    mine_tools, mine_pending_tools = _totals_by_status(mine_tool_groups)
    _, mine_pending_interviews = _totals_by_status(mine_interview_groups)
    _, mine_pending_processes = _totals_by_status(mine_process_groups)
    mine_groups = (
        mine_artisan_groups,
        mine_workshop_groups,
        mine_product_groups,
        mine_tool_groups,
        mine_interview_groups,
        mine_process_groups,
    )
    mine_sent_back = sum(_count_of(groups, "NEEDS_REVISION") for groups in mine_groups)
    mine_rejected = sum(_count_of(groups, "REJECTED") for groups in mine_groups)

    recent = [
        *rows_to_recent(recent_artisans, "artisan"),
        *rows_to_recent(recent_workshops, "workshop"),
        *rows_to_recent(recent_products, "product"),
        *rows_to_recent(recent_tools, "tool"),
    ]
    recent = sorted(recent, key=lambda item: item["createdAt"], reverse=True)[:10]

    pending_access_requests = await pending_count() if is_admin(current_user) else None

    return jsonable_encoder(
        {
            "pendingAccessRequests": pending_access_requests,
            "totalArtisans": artisans,
            "totalWorkshops": workshops,
            "totalProductRecords": products,
            "totalToolRecords": tools,
            "totalMediaFiles": media,
            "pendingSubmissions": pending_submissions,
            "recentSubmissions": recent,
            "mine": {
                "totalArtisans": mine_artisans,
                "totalWorkshops": mine_workshops,
                "totalProductRecords": mine_products,
                "totalToolRecords": mine_tools,
                "totalMediaFiles": mine_media,
                "pendingSubmissions": (
                    mine_pending_artisans
                    + mine_pending_workshops
                    + mine_pending_products
                    + mine_pending_tools
                    + mine_pending_interviews
                    + mine_pending_processes
                ),
                "sentBack": mine_sent_back,
                "rejected": mine_rejected,
            },
        }
    )
