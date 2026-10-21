import asyncio
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value, role_value
from app.services import cross_portal, search_embeddings, search_query
from app.services import unified_search as us
from app.questionnaire.live import live_media_where
from app.services.concurrency import gather_reads
from app.services.cross_portal_client import (
    RECORD_TIMEOUT_SECONDS,
    PeerUnavailable,
    peer_call,
    unavailable_sentence,
)
from app.services.pagination import normalize_pagination
from app.services.record_filters import RECORD_TYPES, build_record_wheres, resolve_types
from app.services.records import media_url_owners, plain, public_encode

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
    # The workshop scope. Repeatable or comma-joined ids, plus the reserved value "none" for records
    # that are not linked to a workshop at all; omitted means every workshop. Spelled exactly as
    # ``GET /map/points`` spells it, because both are built from one client-side filter object — a
    # search box that disagreed with the map about what "this workshop" contains would leave no way to
    # tell which of the two was lying.
    workshopIds: list[str] | None = Query(None),
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
        workshop_ids=workshopIds,
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
        media_where = live_media_where(media_where)
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
    #
    # ``media_urls`` is resolved rather than left to the cheap default. Search is the widest read in the
    # app and the media bucket is one of its five, so this is exactly the surface where "everyone may
    # look, taking data out stays earned" has to hold: every media ROW comes back, and the fetchable
    # ``url`` on it comes back only for uploaders the caller may download from. One query, and only
    # below professor — see ``records.media_url_owners``.
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
        },
        current_user,
        media_urls=await media_url_owners(current_user),
    )


# ── ONE SEARCH ACROSS BOTH PORTALS (docs/SEARCH.md) ─────────────────────────────────────────────
#
# ``GET /search/unified`` ranks this portal's index (services/search_query) and, for the roles in
# ``cross_portal.CROSS_PORTAL_SEARCH_ROLES``, asks the designer portal at the same time over the
# signed channel. ``GET /search`` above is untouched: while UNIFIED_SEARCH_ENABLED is off the new
# route answers 404 and every client falls back to it.

UNIFIED_Q_MIN = 2
UNIFIED_Q_MAX = 200
UNIFIED_LIMIT_DEFAULT = 5
UNIFIED_LIMIT_MAX = 20


def _unified_settings_or_404() -> Any:
    settings = get_settings()
    if not settings.unified_search_enabled:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


def _clean_query(q: str) -> str:
    cleaned = " ".join(plain(q).split())
    if not UNIFIED_Q_MIN <= len(cleaned) <= UNIFIED_Q_MAX:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Search for {UNIFIED_Q_MIN} to {UNIFIED_Q_MAX} characters.",
        )
    return cleaned


def _split(values: list[str] | None) -> list[str]:
    return [
        plain(part.strip())
        for value in values or []
        for part in str(value).split(",")
        if part.strip()
    ]


@router.get("/unified")
async def unified_search(
    current_user: Any = Depends(get_current_user),
    q: str = Query(..., max_length=UNIFIED_Q_MAX * 2),
    types: list[str] | None = Query(None),
    scope: str = Query("all", pattern="^(all|local)$"),
    limit: int = Query(UNIFIED_LIMIT_DEFAULT, ge=1, le=UNIFIED_LIMIT_MAX),
    type: str | None = None,
    page: int = Query(1, ge=1, le=1000),
    craftId: str | None = None,
    place: str | None = Query(None, max_length=200),
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    workshopIds: list[str] | None = Query(None),
) -> dict[str, Any]:
    settings = _unified_settings_or_404()
    text = _clean_query(q)
    try:
        asked = us.parse_types([type] if type else types)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unknown search type: {exc}.",
        ) from None
    local_scope, refused = await us.account_scope(current_user, asked)
    craft = plain(craftId).strip() if craftId else None
    workshops = _split(workshopIds)
    local_scope.craft_ids = [craft] if craft else []
    local_scope.place = (plain(place).strip() or None) if place else None
    local_scope.date_from = dateFrom
    local_scope.date_to = dateTo
    local_scope.workshop_ids = workshops
    offset = (page - 1) * limit if type else 0

    # The other portal: who may ask, and whether this question can be asked there at all.
    remote_status = us.STATUS_OFF
    craft_codes: list[str] = []
    if scope == "all" and cross_portal.search_active(settings):
        if not us.may_search_across(current_user):
            remote_status = us.STATUS_NOT_PERMITTED
        elif workshops:
            remote_status = us.STATUS_FILTERED_OUT
        else:
            remote_status = us.STATUS_OK
            if craft:
                row = await db.craft.find_unique(where={"id": craft})
                if row is None or not row.craftCode:
                    remote_status = us.STATUS_FILTERED_OUT
                else:
                    craft_codes = [row.craftCode]

    async def _local() -> Any:
        qvec = None
        if await search_embeddings.available(settings):
            qvec = await search_embeddings.embed_query(text, settings=settings)
        return await search_query.run(text, local_scope, limit=limit, offset=offset, qvec=qvec)

    async def _remote() -> tuple[str, dict[str, Any]]:
        if remote_status != us.STATUS_OK:
            return remote_status, {}
        return await us.ask_peer(
            settings,
            current_user,
            q=text,
            types=asked,
            limit=limit,
            one_type=type,
            page=page,
            filters={
                "craftCodes": craft_codes,
                "place": local_scope.place,
                "dateFrom": dateFrom.isoformat() if dateFrom else None,
                "dateTo": dateTo.isoformat() if dateTo else None,
            },
        )

    local, (remote_status, peer) = await asyncio.gather(_local(), _remote())
    remote_groups = await us.drop_unconsented(peer.get("groups") or []) if peer else []
    # One group, paged: this page of each side, merged, so nothing is dropped between pages.
    groups = us.merge(
        local.items, local.totals, remote_groups, limit=limit * 2 if type else limit
    )
    return {
        "query": text,
        "mode": local.mode,
        "groups": groups,
        "remote": {"portal": us.PEER_PORTAL, "label": us.PEER_LABEL, "status": remote_status},
        "typesRefused": refused,
        **({"type": type, "page": page, "limit": limit} if type else {}),
    }


@router.get("/remote/{kind}/{record_id}")
async def remote_record(
    kind: str,
    record_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """One record from the designer portal, as a read-only card. Logged as RECORD_VIEW."""
    settings = get_settings()
    if not cross_portal.search_active(settings) or kind not in cross_portal.SEARCH_TYPES:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    if not us.may_search_across(current_user):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    record_id = plain(record_id).strip()[:64]
    user_id = str(get_value(current_user, "id") or "")
    try:
        code, payload = await peer_call(
            settings,
            "POST",
            "/search/record",
            body={
                "type": kind,
                "id": record_id,
                "requester": {
                    "userId": user_id,
                    "email": str(get_value(current_user, "email") or ""),
                    "role": role_value(current_user),
                },
            },
            requested_by=user_id,
            timeout=RECORD_TIMEOUT_SECONDS,
            search=True,
        )
    except PeerUnavailable as exc:
        await _log_record_view(user_id, kind, record_id, "unavailable")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)
        ) from None
    record = payload.get("record") if isinstance(payload, dict) else None
    if code != 200 or not isinstance(record, dict):
        if code == 404 or code == 200:
            await _log_record_view(user_id, kind, record_id, "not_found")
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
        await _log_record_view(user_id, kind, record_id, "unavailable")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=unavailable_sentence(settings),
        )
    linked = record.get("linkedArtisanIds")
    if isinstance(linked, list) and linked:
        # The same consent rule as the search list: a designer record naming one of OUR artisans
        # who has not agreed to share is not shown here either.
        kept = await us.drop_unconsented([{"items": [{"linkedArtisanIds": linked}]}])
        if not kept[0]["items"]:
            await _log_record_view(user_id, kind, record_id, "not_found")
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    await _log_record_view(user_id, kind, record_id, "ok")
    fields = [
        {"label": str(f.get("label")), "value": str(f.get("value"))}
        for f in record.get("fields") or []
        if isinstance(f, dict) and f.get("label") and f.get("value")
    ]
    return {
        "portal": us.PEER_PORTAL,
        "type": kind,
        "id": record_id,
        "title": str(record.get("title") or ""),
        "subtitle": record.get("subtitle"),
        "fields": fields,
        "snippet": str(record.get("snippet") or ""),
        "recordDate": record.get("recordDate"),
        "openUrl": record.get("openUrl"),
    }


async def _log_record_view(user_id: str, kind: str, record_id: str, outcome: str) -> None:
    await db.crossportalaccesslog.create(
        data={
            "userId": user_id or None,
            "action": "RECORD_VIEW",
            "outcome": outcome,
            "entityType": kind,
            "entityId": record_id,
        }
    )
