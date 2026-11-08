from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import JSONResponse, Response

from app.core.db import db
from app.core.deps import get_current_user, require_dataset_downloader
from app.services.data_explorer import filters as explorer_filters
from app.services.datareport import access, build, bundles

router = APIRouter(
    prefix="/data",
    tags=["data-report"],
    dependencies=[Depends(require_dataset_downloader)],
)

go_router = APIRouter(
    prefix="/data",
    tags=["data-report"],
    dependencies=[Depends(get_current_user)],
)

CHUNK = 1000


def _explorer() -> Any:
    from app.api.routes import data_explorer

    return data_explorer


def _not_found(what: str = "Not found") -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=what)


@go_router.get("/go/media/{media_id}/download", response_model=None)
async def go_download(
    media_id: str,
    as_: str | None = Query(None, alias="as"),
    current_user: Any = Depends(get_current_user),
) -> Response:
    from app.services.data_explorer import downloads
    from app.services.media_naming import display_filename

    media = await access.file_for(current_user, media_id)
    answer = downloads.original_redirect(media)
    if (as_ or "").strip().lower() == "json":
        location = answer.headers.get("location")
        if not location:
            raise _not_found("File not found")
        return JSONResponse(
            {
                "url": location,
                "fileName": display_filename(media, fallback=str(media.id)),
                "mediaId": str(media.id),
                "mediaType": downloads.kind_of(media),
            }
        )
    return answer


@go_router.get("/go/transcript/{media_id}/{fmt}")
async def go_transcript(
    media_id: str,
    fmt: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    if fmt not in ("docx", "srt"):
        raise _not_found()
    media = await access.transcript_for(current_user, media_id)
    track, timestamps = ("both", "true") if fmt == "docx" else ("original", "true")
    return {
        "mediaId": str(media.id),
        "format": fmt,
        "apiPath": f"/transcripts/{media.id}/download?format={fmt}&track={track}&timestamps={timestamps}",
        "openHref": access.media_href(current_user, str(media.id)),
    }


@go_router.get("/go/open/{kind}/{record_id}")
async def go_open(
    kind: str,
    record_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    href = await access.record_href(current_user, kind, record_id)
    return {"kind": kind, "id": record_id, "href": href}


async def _visible_ids(user: Any, ids: list[str]) -> tuple[list[str], int]:
    scope = await _explorer().download_scope(user)
    visible: list[str] = []
    total = 0
    for start in range(0, len(ids), CHUNK):
        chunk = ids[start : start + CHUNK]
        rows = await db.query_raw(
            'SELECT "id", "sizeBytes" FROM "MediaFile" WHERE "id" = ANY($1::text[]) AND "removedAt" IS NULL',
            chunk,
        )
        allowed = {str(r["id"]): int(r.get("sizeBytes") or 0) for r in rows}
        if getattr(scope, "media", None) and allowed:
            kept = await db.mediafile.find_many(where=_explorer()._live_media_where(scope, list(allowed)))
            keep = {str(m.id) for m in kept}
            allowed = {k: v for k, v in allowed.items() if k in keep}
        for mid in chunk:
            if mid in allowed:
                visible.append(mid)
                total += allowed[mid]
    return visible, total


@router.get("/bundles/{bundle_id}")
async def get_bundle(bundle_id: str, current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    row = await bundles.load(db, bundle_id)
    if row is None:
        raise _not_found("This download link is not available.")
    ids = [str(i) for i in (row.mediaIds or [])]
    visible, size = await _visible_ids(current_user, ids)
    return {
        "id": str(row.id),
        "label": row.label,
        "path": row.path or "",
        "createdAt": row.createdAt.isoformat() if row.createdAt else None,
        "fileCount": len(visible),
        "bytes": size,
        "listedCount": len(ids),
        "hiddenCount": len(ids) - len(visible),
        "mediaIds": visible,
        "manifestPath": f"/data/bundles/{row.id}/manifest",
        "chunk": _explorer().MANIFEST_MAX_MEDIA_IDS,
    }


@router.get("/bundles/{bundle_id}/manifest", response_model=None)
async def bundle_manifest(
    bundle_id: str,
    stream: bool = Query(False),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    row = await bundles.load(db, bundle_id)
    if row is None:
        raise _not_found("This download link is not available.")
    explorer = _explorer()
    ids = [str(i) for i in (row.mediaIds or [])]
    step = explorer.MANIFEST_MAX_MEDIA_IDS
    files: list[dict[str, Any]] = []
    truncated = False
    for start in range(0, len(ids), step):
        part, cut = await explorer._selection(current_user, [], ids[start : start + step], None, fill=not stream)
        files.extend(part)
        truncated = truncated or cut
    return await explorer.manifest_response(files, truncated, stream=stream)


@router.get("/report/sheets")
async def report_sheets(
    path: str = "",
    status_value: str | None = Query(None, alias="status"),
    q: str | None = None,
    flt: explorer_filters.Filter = Depends(explorer_filters.from_request),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    from app.services.data_explorer import report_cache

    try:
        return await build.catalog(current_user, path, status=status_value, q=q, flt=flt)
    except report_cache.ReportBusy as busy:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=report_cache.REPORT_BUSY,
            headers={"Retry-After": str(busy.retry_after)},
        ) from None

