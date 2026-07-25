from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query

from app.core.db import db
from app.core.deps import get_current_user
from app.services.pagination import normalize_pagination
from app.services.records import add_date_range, contains, visibility_where
from app.services.records import public_encode

router = APIRouter(prefix="/search", tags=["search"])


@router.get("")
async def global_search(
    current_user: Any = Depends(get_current_user),
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(10, ge=1, le=50),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)

    artisan_where: dict[str, Any] = {}
    workshop_where: dict[str, Any] = {}
    product_where: dict[str, Any] = {}
    tool_where: dict[str, Any] = {}
    media_where: dict[str, Any] = {}
    # Row-visibility joins each where under AND, so the free-text ORs assigned below never overwrite it.
    for where, owner_field in (
        (artisan_where, "createdById"),
        (workshop_where, "createdById"),
        (product_where, "createdById"),
        (tool_where, "createdById"),
        (media_where, "uploadedById"),
    ):
        vis = await visibility_where(current_user, owner_field=owner_field)
        if vis:
            where["AND"] = [vis]

    if q:
        artisan_where["OR"] = [{"name": contains(q)}, {"localName": contains(q)}, {"place": contains(q)}]
        workshop_where["OR"] = [{"title": contains(q)}, {"place": contains(q)}, {"description": contains(q)}]
        product_where["OR"] = [
            {"productName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        tool_where["OR"] = [
            {"toolkitName": contains(q)},
            {"englishName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        media_where["OR"] = [{"originalFilename": contains(q)}, {"caption": contains(q)}, {"mimeType": contains(q)}]

    if craftId:
        artisan_where["craftId"] = craftId
        product_where["craftId"] = craftId
        tool_where["craftId"] = craftId
    if place:
        artisan_where["place"] = contains(place)
        workshop_where["place"] = contains(place)
        product_where["place"] = contains(place)
        tool_where["place"] = contains(place)
    if artisanId:
        product_where["artisanId"] = artisanId
        tool_where["artisanId"] = artisanId
    if mediaType:
        media_where["mediaType"] = mediaType

    # Workshops filter on startDate (matching the /workshops list route); rows created before
    # startDate existed fall back to the legacy single `date`. Nested under AND so it composes with
    # the free-text OR built above. Ordering itself is createdAt desc, like every record list.
    if dateFrom or dateTo:
        date_range: dict[str, Any] = {}
        if dateFrom:
            date_range["gte"] = dateFrom
        if dateTo:
            date_range["lte"] = dateTo
        workshop_where.setdefault("AND", []).append(
            {"OR": [{"startDate": date_range}, {"startDate": None, "date": date_range}]}
        )
    add_date_range(product_where, "createdAt", dateFrom, dateTo)
    add_date_range(tool_where, "createdAt", dateFrom, dateTo)
    add_date_range(media_where, "createdAt", dateFrom, dateTo)

    # One count per bucket, so the client can page properly. The five buckets share one page/pageSize
    # but each has its own length, and without totals a UI can only guess at "is there a next page"
    # by checking whether some bucket happened to fill the page. Counted sequentially (the same way
    # /dashboard/stats does it) rather than gathered: this runs on a single web worker with a small
    # Prisma connection pool, and five concurrent counts on top of five concurrent reads is exactly
    # the burst that exhausted the pooler before.
    totals = {
        "artisans": await db.artisan.count(where=artisan_where),
        "workshops": await db.workshop.count(where=workshop_where),
        "products": await db.productdocumentation.count(where=product_where),
        "tools": await db.tooldocumentation.count(where=tool_where),
        "media": await db.mediafile.count(where=media_where),
    }

    artisans = await db.artisan.find_many(where=artisan_where, skip=skip, take=page_size, order={"createdAt": "desc"})
    workshops = await db.workshop.find_many(
        where=workshop_where, skip=skip, take=page_size, order={"createdAt": "desc"}
    )
    products = await db.productdocumentation.find_many(
        where=product_where,
        include={"media": True},
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    tools = await db.tooldocumentation.find_many(
        where=tool_where,
        include={"media": True},
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    media = await db.mediafile.find_many(where=media_where, skip=skip, take=page_size, order={"createdAt": "desc"})

    # `totals` / `total` / `pageCount` are ADDITIVE: every pre-existing key keeps its name and shape
    # so older clients (and the Android app) are untouched.
    return public_encode(
        {
            "query": q,
            "page": page,
            "pageSize": page_size,
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
