import asyncio
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value, role_value
from app.questionnaire.live import live_media_where
from app.services import cross_portal, search_embeddings, search_query
from app.services import unified_search as us
from app.services.concurrency import gather_reads
from app.services.cross_portal_client import (
    RECORD_TIMEOUT_SECONDS,
    PeerUnavailable,
    peer_call,
    unavailable_sentence,
)
from app.services.data_explorer import filters as explorer_filters
from app.services.pagination import normalize_pagination
from app.services.record_filters import RECORD_TYPES, build_record_wheres, resolve_types
from app.services.records import media_url_owners, plain, public_encode

router = APIRouter(prefix="/search", tags=["search"])

SEARCH_TYPES: tuple[str, ...] = RECORD_TYPES

_ORDER = {"createdAt": "desc"}


def _resolve_types(raw: list[str] | None) -> set[str]:
    return resolve_types(raw)


@router.get("")
async def global_search(
    current_user: Any = Depends(get_current_user),
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    types: list[str] | None = Query(None),
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    workshopIds: list[str] | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(10, ge=1, le=50),
    flt: explorer_filters.Filter = Depends(explorer_filters.from_request),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    selected = _resolve_types(types)

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
    wheres = explorer_filters.search_wheres(wheres, flt)
    artisan_where = wheres["artisans"]
    workshop_where = wheres["workshops"]
    product_where = wheres["products"]
    tool_where = wheres["tools"]
    media_where = wheres["media"]

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
            "pageCount": max(1, (max(totals.values()) + page_size - 1) // page_size),
        },
        current_user,
        media_urls=await media_url_owners(current_user),
    )


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
    if not isinstance(values, list | tuple):
        return []
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
    artisanIds: list[str] | None = Query(None),
    sort: str | None = Query(None, max_length=40),
    hasTranscript: str | None = Query(None, pattern="^(yes|no|any)$"),
) -> dict[str, Any]:
    settings = _unified_settings_or_404()
    text = _clean_query(q)
    try:
        chosen = search_query.parse_sort(sort if isinstance(sort, str) else None)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Sort by relevance:desc, name:asc, name:desc, recorded:asc or recorded:desc.",
        ) from None
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
    local_scope.artisan_ids = _split(artisanIds)
    local_scope.media_transcript = hasTranscript if hasTranscript in ("yes", "no") else None
    offset = (page - 1) * limit

    peer_available = cross_portal.search_active(settings) and us.may_search_across(current_user)
    remote_status = us.STATUS_OFF
    craft_codes: list[str] = []
    if scope == "all" and cross_portal.search_active(settings):
        if not us.may_search_across(current_user):
            remote_status = us.STATUS_NOT_PERMITTED
        elif workshops or local_scope.artisan_ids:
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
        return await search_query.run(
            text, local_scope, limit=limit, offset=offset, qvec=qvec, sort=chosen
        )

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
            sort=None if chosen == search_query.DEFAULT_SORT else search_query.sort_text(chosen),
        )

    local, (remote_status, peer) = await asyncio.gather(_local(), _remote())
    remote_groups = await us.drop_unconsented(peer.get("groups") or []) if peer else []
    await search_query.transcript_moments(local.items.get("transcripts", []), text)
    links = await us.artisan_links([str(i["id"]) for i in local.items.get("artisans", [])])
    groups = us.merge(
        local.items,
        local.totals,
        remote_groups,
        limit=limit * 2,
        links=links,
        sort=chosen,
    )
    suggestion = None if any(g["items"] for g in groups) else await search_query.did_you_mean(text)
    suggestion_total = 0
    if suggestion:
        suggested = await search_query.run(suggestion, local_scope, limit=1)
        suggestion_total = sum(int(n) for n in suggested.totals.values())
    return {
        "query": text,
        "suggestion": suggestion,
        "suggestionTotal": suggestion_total,
        "mode": local.mode,
        "groups": groups,
        "remote": {
            "portal": us.PEER_PORTAL,
            "label": us.PEER_LABEL,
            "status": remote_status,
            "available": peer_available,
        },
        "typesRefused": refused,
        "page": page,
        "limit": limit,
        "sort": {"key": chosen[0], "dir": chosen[1]},
        "sorts": list(search_query.SORT_KEYS),
        **({"type": type} if type else {}),
    }


@router.get("/remote/{kind}/{record_id}")
async def remote_record(
    kind: str,
    record_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
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
