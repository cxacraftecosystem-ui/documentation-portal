from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import get_current_user
from app.services.pagination import normalize_pagination
from app.services.records import add_date_range, contains, visibility_where
from app.services.records import public_encode

router = APIRouter(prefix="/search", tags=["search"])

# The five buckets, in the order they are counted, read and returned. Also the order `types` is
# echoed back in, so a client comparing what it asked for against what it got is comparing like
# with like.
SEARCH_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools", "media")

# Every bucket is ordered by createdAt desc, like every record list in this API.
_ORDER = {"createdAt": "desc"}


def _resolve_types(raw: list[str] | None) -> set[str]:
    """Which buckets this request searches. Absent, empty, or all-blank means all five.

    Accepts both spellings a client might reach for — repeated parameters
    (``?types=artisans&types=media``) and one comma-joined value (``?types=artisans,media``) —
    because the web and Android build query strings differently, and a filter that quietly searched
    everything because it was spelled the other way would look exactly like the filter not working.

    An unrecognised bucket name is a 422 rather than a silent omission. Dropping it would answer a
    request for "artisan" (singular, a plausible typo) with a perfectly well-formed empty result,
    and the client would report "no matches" for data that is sitting right there — a wrong answer
    dressed as a correct one.
    """
    if not raw:
        return set(SEARCH_TYPES)
    wanted = {
        part.strip().lower()
        for value in raw
        for part in str(value).split(",")
        if part.strip()
    }
    if not wanted:
        return set(SEARCH_TYPES)
    unknown = sorted(wanted - set(SEARCH_TYPES))
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown search type{'s' if len(unknown) > 1 else ''}: {', '.join(unknown)}. "
                f"Valid types are {', '.join(SEARCH_TYPES)}."
            ),
        )
    return wanted


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

    # Row visibility is resolved ONCE per owner column rather than once per bucket. It reads the
    # grant table, and the four record buckets all key off `createdById`, so the previous
    # bucket-by-bucket loop issued the same query five times before the search had even begun — on
    # the same small connection pool this route then shares five ways.
    record_visibility = await visibility_where(current_user, owner_field="createdById")
    media_visibility = await visibility_where(current_user, owner_field="uploadedById")

    # Row-visibility joins each where under AND, so the free-text ORs assigned below never overwrite
    # it — and neither does anything else that needs its own OR.
    artisan_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    workshop_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    product_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    tool_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    media_where: dict[str, Any] = {"AND": [media_visibility]} if media_visibility else {}

    # Each filter below writes its own key, so every active one ANDs with the rest: a query plus a
    # place plus a date range narrows to the rows satisfying all three, never their union. The five
    # where-dicts are built unconditionally — they cost nothing until a query runs against them, and
    # keeping the filter logic in one unbranched block is what makes it checkable at a glance.
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
    # Artisans were the one bucket the date range never reached: passing dateFrom returned every
    # artisan ever recorded alongside four correctly-filtered buckets, which reads as the filter
    # being broken rather than as artisans being exempt. Same column as the three below.
    add_date_range(artisan_where, "createdAt", dateFrom, dateTo)
    add_date_range(product_where, "createdAt", dateFrom, dateTo)
    add_date_range(tool_where, "createdAt", dateFrom, dateTo)
    add_date_range(media_where, "createdAt", dateFrom, dateTo)

    # One count per bucket, so the client can page properly. The five buckets share one page/pageSize
    # but each has its own length, and without totals a UI can only guess at "is there a next page"
    # by checking whether some bucket happened to fill the page. Counted sequentially (the same way
    # /dashboard/stats does it) rather than gathered: this runs on a single web worker with a small
    # Prisma connection pool, and five concurrent counts on top of five concurrent reads is exactly
    # the burst that exhausted the pooler before.
    #
    # A bucket `types` excluded is never counted and never read. It reports 0, which keeps it out of
    # `total` and — because `pageCount` is the longest bucket's page count — stops a 500-row bucket
    # nobody asked for from advertising pages that would come back empty.
    totals = {
        "artisans": await db.artisan.count(where=artisan_where) if "artisans" in selected else 0,
        "workshops": await db.workshop.count(where=workshop_where) if "workshops" in selected else 0,
        "products": (
            await db.productdocumentation.count(where=product_where) if "products" in selected else 0
        ),
        "tools": await db.tooldocumentation.count(where=tool_where) if "tools" in selected else 0,
        "media": await db.mediafile.count(where=media_where) if "media" in selected else 0,
    }

    artisans = (
        await db.artisan.find_many(where=artisan_where, skip=skip, take=page_size, order=_ORDER)
        if "artisans" in selected
        else []
    )
    workshops = (
        await db.workshop.find_many(where=workshop_where, skip=skip, take=page_size, order=_ORDER)
        if "workshops" in selected
        else []
    )
    products = (
        await db.productdocumentation.find_many(
            where=product_where,
            include={"media": True},
            skip=skip,
            take=page_size,
            order=_ORDER,
        )
        if "products" in selected
        else []
    )
    tools = (
        await db.tooldocumentation.find_many(
            where=tool_where,
            include={"media": True},
            skip=skip,
            take=page_size,
            order=_ORDER,
        )
        if "tools" in selected
        else []
    )
    media = (
        await db.mediafile.find_many(where=media_where, skip=skip, take=page_size, order=_ORDER)
        if "media" in selected
        else []
    )

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
