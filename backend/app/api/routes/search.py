from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query

from app.core.db import db
from app.core.deps import get_current_user
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination
from app.services.record_filters import RECORD_TYPES, build_record_wheres, resolve_types
from app.services.records import public_encode

router = APIRouter(prefix="/search", tags=["search"])

# The five buckets, in the order they are counted, read and returned. Also the order `types` is
# echoed back in, so a client comparing what it asked for against what it got is comparing like
# with like. Re-exported from services/record_filters rather than restated: the map reads the same
# tuple, and two copies of this list is exactly how one screen quietly grows a sixth bucket.
SEARCH_TYPES: tuple[str, ...] = RECORD_TYPES

# Every bucket is ordered by createdAt desc, like every record list in this API.
_ORDER = {"createdAt": "desc"}


def _resolve_types(raw: list[str] | None) -> set[str]:
    """The bucket selection, resolved by services/record_filters so the map applies the same rule.

    Kept as a module-level name because the route reads better for it, and because anything that
    already imports it from here keeps working.
    """
    return resolve_types(raw)


@router.get("")
async def global_search(
    current_user: Any = Depends(get_current_user),
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    # Which buckets to search. Repeatable; omitted means all five.
    types: list[str] | None = Query(None),
    # The record time range, as CONCRETE dates. The clients offer presets (today, 7/30/90 days, this
    # month, this year, custom) and resolve them to a from/to pair themselves, deliberately: a preset
    # is a phrase in a UI, and putting phrases in the API would mean a new preset — or a client whose
    # idea of "this month" starts on a different weekday — needs a backend release. Either bound may
    # stand alone, so "everything since the workshop" needs no artificial end date.
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(10, ge=1, le=50),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    selected = _resolve_types(types)

    # Every filter below — visibility, free text, craft, place, artisan, media type, date range —
    # is built by services/record_filters, which is also what the map endpoint asks. One vocabulary,
    # one implementation, so the two screens can never answer the same question differently.
    wheres = await build_record_wheres(
        current_user,
        q=q,
        craft_id=craftId,
        place=place,
        artisan_id=artisanId,
        media_type=mediaType,
        date_from=dateFrom,
        date_to=dateTo,
    )
    artisan_where = wheres["artisans"]
    workshop_where = wheres["workshops"]
    product_where = wheres["products"]
    tool_where = wheres["tools"]
    media_where = wheres["media"]

    # One count per bucket, so the client can page properly. The five buckets share one page/pageSize
    # but each has its own length, and without totals a UI can only guess at "is there a next page"
    # by checking whether some bucket happened to fill the page.
    #
    # A bucket `types` excluded is never counted and never read. It reports 0, which keeps it out of
    # `total` and — because `pageCount` is the longest bucket's page count — stops a 500-row bucket
    # nobody asked for from advertising pages that would come back empty.
    #
    # The count and the read for every selected bucket go out TOGETHER. They were sequential, on the
    # reasoning that ten concurrent queries would exhaust the pooler — but that fear dates from when
    # each of two uvicorn workers held a pool of forty. The box now runs one web worker with a pool
    # of ten, and `gather_reads` will not exceed it. Re-measured against production at 4, 8 and 16
    # simultaneous searches: no connection errors at any level, and the gathered version was faster
    # or level every time. An all-bucket search went from 8.25s to 1.43s, because on a cross-region
    # link ten sequential reads is ten times ~750ms of waiting and almost no database work.
    #
    # An unselected bucket contributes NO coroutine — it must not cost a round trip to return 0 —
    # so the reads are collected with their bucket names and zipped back up after the gather.
    planned: list[tuple[str, Any]] = []
    if "artisans" in selected:
        planned.append(("artisans", db.artisan.count(where=artisan_where)))
        planned.append(
            ("artisans_rows", db.artisan.find_many(where=artisan_where, skip=skip, take=page_size, order=_ORDER))
        )
    if "workshops" in selected:
        planned.append(("workshops", db.workshop.count(where=workshop_where)))
        planned.append(
            ("workshops_rows", db.workshop.find_many(where=workshop_where, skip=skip, take=page_size, order=_ORDER))
        )
    if "products" in selected:
        planned.append(("products", db.productdocumentation.count(where=product_where)))
        planned.append((
            "products_rows",
            db.productdocumentation.find_many(
                where=product_where, include={"media": True}, skip=skip, take=page_size, order=_ORDER
            ),
        ))
    if "tools" in selected:
        planned.append(("tools", db.tooldocumentation.count(where=tool_where)))
        planned.append((
            "tools_rows",
            db.tooldocumentation.find_many(
                where=tool_where, include={"media": True}, skip=skip, take=page_size, order=_ORDER
            ),
        ))
    if "media" in selected:
        planned.append(("media", db.mediafile.count(where=media_where)))
        planned.append(
            ("media_rows", db.mediafile.find_many(where=media_where, skip=skip, take=page_size, order=_ORDER))
        )

    results = dict(zip([name for name, _ in planned], await gather_reads(*(coro for _, coro in planned))))

    totals = {name: results.get(name, 0) for name in SEARCH_TYPES}
    artisans = results.get("artisans_rows", [])
    workshops = results.get("workshops_rows", [])
    products = results.get("products_rows", [])
    tools = results.get("tools_rows", [])
    media = results.get("media_rows", [])

    # `totals` / `total` / `pageCount` are ADDITIVE: every pre-existing key keeps its name and shape
    # so older clients (and the Android app) are untouched. `types` joins them on the same terms —
    # the RESOLVED set, in bucket order, so a client can show "searching artisans and media" without
    # re-deriving what an omitted parameter meant.
    return public_encode(
        {
            "query": q,
            "page": page,
            "pageSize": page_size,
            "types": [name for name in SEARCH_TYPES if name in selected],
            "artisans": artisans,
            "workshops": workshops,
            "products": products,
            "tools": tools,
            "media": media,
            "totals": totals,
            "total": sum(totals.values()),
            # The pager walks all five buckets at once, so the last page is the last page of the
            # LONGEST bucket; at least 1 so an empty result still reads as "page 1 of 1".
            "pageCount": max(1, (max(totals.values()) + page_size - 1) // page_size),
        }
    )
