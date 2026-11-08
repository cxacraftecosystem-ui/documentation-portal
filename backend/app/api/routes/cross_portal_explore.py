from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.api.routes.cross_portal_search import (
    ACTION_SEARCH,
    RATE_LIMIT,
    RATE_WINDOW,
    Requester,
    _asker,
    _log,
    _open_url,
    _peer_scope,
)
from app.core.db import db
from app.services import cross_portal, search_query
from app.services.cross_portal_peer import require_search_peer, signed
from app.services.records import plain

router = APIRouter(prefix="/cross-portal/peer", tags=["cross-portal"])

EXPLORE_LIMIT_MAX = 20
ROW_COLUMNS = (
    'd."entityType", d."entityId", d."title", d."subtitle", d."place", '
    'd."craftCodes", d."recordDate", d."openPath"'
)


class ExploreFilters(BaseModel):
    model_config = ConfigDict(extra="ignore")

    craftCodes: list[str] = Field(default_factory=list, max_length=200)
    artisanIds: list[str] = Field(default_factory=list, max_length=200)
    dateFrom: datetime | None = None
    dateTo: datetime | None = None


class PeerExplore(BaseModel):
    model_config = ConfigDict(extra="ignore")

    q: str = Field(default="", max_length=400)
    type: str | None = Field(default=None, max_length=40)
    page: int = Field(default=1, ge=1, le=1000)
    limit: int = Field(default=EXPLORE_LIMIT_MAX, ge=1, le=EXPLORE_LIMIT_MAX)
    filters: ExploreFilters = Field(default_factory=ExploreFilters)
    requester: Requester = Field(default_factory=Requester)


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        moment = value if value.tzinfo else value.replace(tzinfo=UTC)
        return moment.isoformat()
    return str(value)


def _item(settings: Any, row: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": row.get("entityType") or row.get("type"),
        "id": row.get("entityId") or row.get("id"),
        "title": row.get("title") or "",
        "subtitle": row.get("subtitle"),
        "place": row.get("place"),
        "craftCodes": list(row.get("craftCodes") or []),
        "recordDate": _iso(row.get("recordDate")),
        "openUrl": _open_url(settings, str(row.get("openPath") or ""))
        if row.get("openPath")
        else None,
    }


@router.post("/explore")
async def peer_explore(
    body: PeerExplore, request: Request, settings: Any = Depends(require_search_peer)
) -> Response:
    asker = _asker(request, body.requester)
    since = datetime.now(UTC) - RATE_WINDOW
    made = await db.crossportalaccesslog.count(
        where={"remoteUserId": asker, "action": ACTION_SEARCH, "createdAt": {"gte": since}}
    )
    if made >= RATE_LIMIT:
        await _log(asker, ACTION_SEARCH, "rate_limited")
        return signed(settings, request, {"detail": "Too many searches."}, status_code=429)
    asked = [body.type] if body.type in cross_portal.SEARCH_TYPES else None
    if body.type and asked is None:
        return signed(settings, request, {"items": [], "total": 0})
    scope = await _peer_scope(body.requester, asked)
    if not scope.types:
        await _log(asker, ACTION_SEARCH, "ok", resultCount=0)
        return signed(settings, request, {"items": [], "total": 0})
    scope.craft_codes = [plain(c).strip() for c in body.filters.craftCodes if c and c.strip()]
    scope.artisan_ids = [plain(a).strip() for a in body.filters.artisanIds if a and a.strip()]
    scope.date_from = body.filters.dateFrom
    scope.date_to = body.filters.dateTo
    offset = (body.page - 1) * body.limit
    text = " ".join(plain(body.q).split())[:200]

    if len(text) >= 2:
        result = await search_query.run(text, scope, limit=body.limit, offset=offset, qvec=None)
        rows = [
            {**item, "entityType": kind, "entityId": item.get("id")}
            for kind in scope.types
            for item in result.items.get(kind) or []
        ]
        total = sum(int(result.totals.get(kind, 0) or 0) for kind in scope.types)
        items = [_item(settings, r) for r in rows[: body.limit]]
    else:
        p = search_query._Params()
        where = search_query._where(scope, p)
        limit_ref, offset_ref = p.add(body.limit), p.add(offset)
        found = await db.query_raw(
            f'SELECT {ROW_COLUMNS} FROM "SearchDocument" d WHERE {where} '
            f'ORDER BY d."recordDate" DESC NULLS LAST, d."title" ASC, d."entityId" ASC '
            f"LIMIT {limit_ref} OFFSET {offset_ref}",
            *p.values,
        )
        counter = search_query._Params()
        counted = await db.query_raw(
            f'SELECT count(*)::int AS n FROM "SearchDocument" d WHERE {search_query._where(scope, counter)}',
            *counter.values,
        )
        total = int((counted[0] if counted else {}).get("n") or 0)
        items = [_item(settings, r) for r in found]
    await _log(asker, ACTION_SEARCH, "ok", resultCount=len(items))
    return signed(settings, request, {"items": items, "total": max(total, len(items))})
