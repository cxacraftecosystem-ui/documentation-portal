"""The dashboard's counters.

WHAT CHANGED AND WHY. Every total here used to be filtered by the old row-visibility predicate, which
below Professor meant "rows you created". So a researcher's dashboard did not describe the repository
— it described their own upload history, under labels that said otherwise ("Artisans", "Media files"),
while a professor standing next to them read the true totals off the same labels. An account that had
uploaded nothing opened a dashboard of zeroes and a "recent submissions" list with nothing in it, which
is indistinguishable from an empty repository.

Reading is open now (see the banner above ``records.viewable_where``), so the totals are the
repository's totals — and because "how much have I contributed" is a genuinely useful second question
rather than a thing to delete, it is answered explicitly beside them in ``mine``. Two labelled
numbers, neither pretending to be the other.

Nothing here is a download. The counts are counts and the recent list is titles; taking the data out
still goes through /export and /data, which are gated as they always were.
"""
from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user
from app.services.concurrency import gather_reads
from app.services.records import own_rows_where, viewable_where

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


def rows_to_recent(rows: list[Any], record_type: str) -> list[dict[str, Any]]:
    """Turn one table's newest rows into the shared shape the recent-activity list renders.

    ``createdByName`` is read off the included relation and the relation itself never enters the
    returned dict. That is deliberate: this module encodes with ``jsonable_encoder`` rather than
    ``records.public_encode``, so an embedded User object would ship its ``passwordHash``. Naming the
    one field wanted is what keeps that impossible instead of merely unlikely.
    """
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
            # Whose work this is. It was never shown while the list could only hold the reader's own
            # rows; now that the list is the repository's, an unattributed title is a title nobody
            # can follow up on.
            "createdByName": getattr(getattr(row, "createdBy", None), "name", None),
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
    # Both predicates are resolved before the wave rather than inside it: neither is a query (one is
    # empty, the other reads the caller's id), so awaiting them costs no round trip.
    view_where, media_view_where = await gather_reads(
        viewable_where(current_user),
        viewable_where(current_user, owner_field="uploadedById"),
    )
    own_where = await own_rows_where(current_user)
    own_media_where = await own_rows_where(current_user, owner_field="uploadedById")

    # This endpoint used to issue fourteen reads one after another: five totals, four "recent"
    # lists, five pending counts. On a cross-region link where a round trip costs ~750ms and the
    # query itself costs a fraction of a millisecond, that was 10.1s of almost pure waiting.
    #
    # Two things fix it, and the first matters more than the second. The four record tables are
    # counted TWICE each — once for the total, once for the PENDING subset — so a single
    # ``group_by`` over `status` answers both questions in one trip and removes four reads outright.
    # What remains is mutually independent, and goes out together. Measured against production data:
    # 10.1s sequential -> 7.8s from the grouping alone -> 950ms once gathered.
    #
    # The `mine` half rides the SAME wave rather than a second one. It is four more group_bys and two
    # more counts against indexed owner columns, all independent of everything else here, so it adds
    # rows to the one round trip instead of adding a round trip.
    grouped = (db.artisan, db.workshop, db.productdocumentation, db.tooldocumentation)

    def pending(where: dict[str, Any]) -> dict[str, Any]:
        """``where`` narrowed to PENDING, without mutating the caller's dict."""
        return {**where, "status": "PENDING"}

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
        mine_artisan_groups,
        mine_workshop_groups,
        mine_product_groups,
        mine_tool_groups,
        mine_media,
        mine_pending_interviews,
    ) = await gather_reads(
        *(delegate.group_by(by=["status"], count=True, where=view_where) for delegate in grouped),
        db.mediafile.count(where=media_view_where),
        db.questionnaireinterview.count(where=pending(view_where)),
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
        db.questionnaireinterview.count(where=pending(own_where)),
    )

    artisans, pending_artisans = _totals_by_status(artisan_groups)
    workshops, pending_workshops = _totals_by_status(workshop_groups)
    products, pending_products = _totals_by_status(product_groups)
    tools, pending_tools = _totals_by_status(tool_groups)
    pending_submissions = (
        pending_artisans + pending_workshops + pending_products + pending_tools + pending_interviews
    )

    mine_artisans, mine_pending_artisans = _totals_by_status(mine_artisan_groups)
    mine_workshops, mine_pending_workshops = _totals_by_status(mine_workshop_groups)
    mine_products, mine_pending_products = _totals_by_status(mine_product_groups)
    mine_tools, mine_pending_tools = _totals_by_status(mine_tool_groups)

    recent = [
        *rows_to_recent(recent_artisans, "artisan"),
        *rows_to_recent(recent_workshops, "workshop"),
        *rows_to_recent(recent_products, "product"),
        *rows_to_recent(recent_tools, "tool"),
    ]
    recent = sorted(recent, key=lambda item: item["createdAt"], reverse=True)[:10]

    return jsonable_encoder(
        {
            # The repository. These are what the labels have always claimed to be.
            "totalArtisans": artisans,
            "totalWorkshops": workshops,
            "totalProductRecords": products,
            "totalToolRecords": tools,
            "totalMediaFiles": media,
            "pendingSubmissions": pending_submissions,
            "recentSubmissions": recent,
            # This account's own contribution, asked for explicitly. Same keys, so a client can
            # render the two rows from one template.
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
                ),
            },
        }
    )
