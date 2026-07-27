from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user
from app.services.concurrency import gather_reads
from app.services.records import visibility_where

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
        }
        for row in rows
    ]


def _totals_by_status(groups: list[Any]) -> tuple[int, int]:
    """Fold one ``group_by(status)`` result into (every row, the PENDING ones).

    Prisma hands back a row per status actually present, so a table with nothing pending simply has
    no PENDING group — hence the ``.get`` rather than an index.
    """
    counts = {str(group["status"]): group["_count"]["_all"] for group in groups}
    return sum(counts.values()), counts.get("PENDING", 0)


@router.get("/stats")
async def dashboard_stats(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    # Independent of one another, and each reads the grant table for anyone below professor — so a
    # researcher pays one round trip here instead of two.
    owner_where, media_where = await gather_reads(
        visibility_where(current_user),
        visibility_where(current_user, owner_field="uploadedById"),
    )

    # This endpoint used to issue fourteen reads one after another: five totals, four "recent"
    # lists, five pending counts. On a cross-region link where a round trip costs ~750ms and the
    # query itself costs a fraction of a millisecond, that was 10.1s of almost pure waiting.
    #
    # Two things fix it, and the first matters more than the second. The four record tables are
    # counted TWICE each — once for the total, once for the PENDING subset — so a single
    # ``group_by`` over `status` answers both questions in one trip and removes four reads outright.
    # What remains is ten mutually independent reads, which then go out together. Measured against
    # production data: 10.1s sequential -> 7.8s from the grouping alone -> 950ms once gathered.
    grouped = (db.artisan, db.workshop, db.productdocumentation, db.tooldocumentation)
    pending_where = dict(owner_where)
    pending_where["status"] = "PENDING"

    (
        artisan_groups,
        workshop_groups,
        product_groups,
        tool_groups,
        media,
        pending_interviews,
        recent_artisans,
        recent_workshops,
        recent_products,
        recent_tools,
    ) = await gather_reads(
        *(delegate.group_by(by=["status"], count=True, where=owner_where) for delegate in grouped),
        db.mediafile.count(where=media_where),
        db.questionnaireinterview.count(where=pending_where),
        *(
            delegate.find_many(where=owner_where, take=5, order={"createdAt": "desc"})
            for delegate in grouped
        ),
    )

    artisans, pending_artisans = _totals_by_status(artisan_groups)
    workshops, pending_workshops = _totals_by_status(workshop_groups)
    products, pending_products = _totals_by_status(product_groups)
    tools, pending_tools = _totals_by_status(tool_groups)
    pending_submissions = (
        pending_artisans + pending_workshops + pending_products + pending_tools + pending_interviews
    )

    recent = [
        *rows_to_recent(recent_artisans, "artisan"),
        *rows_to_recent(recent_workshops, "workshop"),
        *rows_to_recent(recent_products, "product"),
        *rows_to_recent(recent_tools, "tool"),
    ]
    recent = sorted(recent, key=lambda item: item["createdAt"], reverse=True)[:10]

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
