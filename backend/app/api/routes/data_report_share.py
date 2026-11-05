from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Request, status
from fastapi.responses import JSONResponse, Response
from fastapi.security import HTTPAuthorizationCredentials

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    _user_from_bearer,
    bearer_scheme,
    can_download_dataset,
    require_dataset_downloader,
    resolve_user,
)
from app.scale.rate_limit import client_address
from app.services.data_explorer import report_cache, saved
from app.services.datareport import share_links, share_snapshot

router = APIRouter(tags=["data-report-share"])

NOT_FOUND = "This link has expired or was turned off."
PUBLIC_HEADERS = {
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Robots-Tag": "noindex, nofollow",
}


def _browser() -> Any:
    from app.api.routes import data_browser

    return data_browser


def _secret() -> str:
    return str(get_settings().jwt_secret)


def _url(share_id: str, locale: Any = "en") -> str:
    settings = get_settings()
    return share_links.share_url(settings.next_public_app_url, share_links.token_for(share_id, _secret()), locale)


def _now() -> datetime:
    return datetime.now(UTC)


async def _owned(share_id: str, uid: str) -> Any:
    row = await db.datareportshare.find_unique(where={"id": share_id})
    if row is None or str(row.ownerId) != uid:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    return row


async def _view_counts(ids: list[str]) -> dict[str, tuple[int, Any]]:
    if not ids:
        return {}
    rows = await db.query_raw(
        'SELECT "shareId", count(*)::int AS "views", max("openedAt") AS "last" '
        'FROM "DataReportShareView" WHERE "shareId" = ANY($1::text[]) GROUP BY "shareId"',
        ids,
    )
    return {str(r["shareId"]): (int(r["views"] or 0), r.get("last")) for r in rows}


def _out(row: Any, counts: dict[str, tuple[int, Any]], now: datetime) -> dict[str, Any]:
    views, last = counts.get(str(row.id), (0, None))
    if isinstance(last, str):
        try:
            last = datetime.fromisoformat(last)
        except ValueError:
            last = None
    options = row.options if isinstance(row.options, dict) else {}
    return share_links.share_out(
        row, url=_url(str(row.id), options.get("locale")), now=now, views=views, last_opened=last
    )


@router.post("/data/report/shares")
async def create_share(
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    from prisma import Json

    browser = _browser()
    try:
        path = saved.path_of(body.get("path"), browser._norm)
        days = share_links.days_of(body.get("expiresInDays"))
        options = share_links.options_of(body.get("options"))
        filters = share_links.filters_of(body.get("filters"))
    except (saved.Invalid, share_links.Invalid) as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    uid = str(current_user.id)
    now = _now()
    active = await db.datareportshare.count(
        where={"AND": [{"ownerId": uid}, {"revokedAt": None}, {"expiresAt": {"gt": now}}]}
    )
    if active >= share_links.SHARES_PER_OWNER:
        raise HTTPException(
            status_code=422,
            detail=f"You can keep {share_links.SHARES_PER_OWNER} live links. Turn one off first.",
        )
    try:
        snapshot = await browser._queued(lambda: share_snapshot.build(
                browser, current_user, path, filters=filters, now=now, locale=str(options.get("locale") or "en")
            ))
    except report_cache.ReportBusy as busy:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=report_cache.REPORT_BUSY,
            headers={"Retry-After": str(busy.retry_after)},
        ) from None
    share_id = share_links.new_id()
    token = share_links.token_for(share_id, _secret())
    row = await db.datareportshare.create(
        data={
            "id": share_id,
            "tokenHash": share_links.token_hash(token),
            "ownerId": uid,
            "path": path,
            "title": str(snapshot.get("title") or "Repository")[: saved.NAME_MAX],
            "options": Json({**options, "filters": filters}),
            "snapshot": Json(snapshot),
            "expiresAt": share_links.expiry(now, days),
        }
    )
    return _out(row, {}, now)


@router.get("/data/report/shares")
async def list_shares(
    path: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    where: dict[str, Any] = {"ownerId": str(current_user.id)}
    if path is not None and path.strip():
        where = {"AND": [where, {"path": _browser()._norm(path)}]}
    rows = await db.datareportshare.find_many(
        where=where, order={"createdAt": "desc"}, take=share_links.SHARES_PER_OWNER * 2
    )
    counts = await _view_counts([str(r.id) for r in rows])
    now = _now()
    return {"items": [_out(r, counts, now) for r in rows]}


@router.delete("/data/report/shares/{share_id}", status_code=status.HTTP_204_NO_CONTENT)
async def revoke_share(share_id: str, current_user: Any = Depends(require_dataset_downloader)) -> Response:
    row = await _owned(share_id, str(current_user.id))
    if row.revokedAt is None:
        await db.datareportshare.update(where={"id": share_id}, data={"revokedAt": _now()})
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/data/report/shares/{share_id}/views")
async def share_views(share_id: str, current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    await _owned(share_id, str(current_user.id))
    total = await db.datareportshareview.count(where={"shareId": share_id})
    rows = await db.datareportshareview.find_many(
        where={"shareId": share_id},
        include={"viewer": True},
        order={"openedAt": "desc"},
        take=share_links.VIEWS_LIST_MAX,
    )
    return {
        "items": [share_links.view_out(r) for r in rows],
        "total": total,
        "truncated": total > len(rows),
    }


async def _viewer_id(credentials: HTTPAuthorizationCredentials | None) -> str | None:
    if credentials is None:
        return None
    try:
        user = await _user_from_bearer(credentials, allowed_scopes=frozenset())
    except HTTPException:
        return None
    return str(user.id) if user is not None else None


async def _log_view(row: Any, request: Request, viewer_id: str | None, now: datetime) -> None:
    ip_hash = share_links.address_hash(client_address(request.scope), _secret())
    recent = await db.datareportshareview.find_first(
        where={
            "AND": [
                {"shareId": row.id},
                {"ipHash": ip_hash},
                {"viewerId": viewer_id},
                {"openedAt": {"gt": now - timedelta(seconds=share_links.REPEAT_VIEW_SECONDS)}},
            ]
        }
    )
    if recent is not None:
        return
    if await db.datareportshareview.count(where={"shareId": row.id}) >= share_links.VIEWS_KEPT_MAX:
        return
    await db.datareportshareview.create(
        data={
            "shareId": row.id,
            "viewerId": viewer_id,
            "ipHash": ip_hash,
            "userAgent": share_links.user_agent_of(request.headers.get("user-agent")),
            "openedAt": now,
        }
    )


@router.get("/report-share/{token}")
async def open_share(
    token: str,
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> JSONResponse:
    gone = JSONResponse({"detail": NOT_FOUND}, status_code=status.HTTP_404_NOT_FOUND, headers=PUBLIC_HEADERS)
    if not share_links.well_formed(token):
        return gone
    row = await db.datareportshare.find_unique(where={"tokenHash": share_links.token_hash(token)})
    now = _now()
    if row is None or not share_links.live(row, now):
        return gone
    owner = await resolve_user(str(row.ownerId))
    if owner is None or not can_download_dataset(owner):
        return gone
    await _log_view(row, request, await _viewer_id(credentials), now)
    return JSONResponse(share_links.public_out(row), headers=PUBLIC_HEADERS)
