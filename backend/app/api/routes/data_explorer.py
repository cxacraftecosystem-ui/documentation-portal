from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Query, status
from fastapi.responses import JSONResponse, Response, StreamingResponse

from app.core.db import db
from app.core.deps import require_dataset_downloader
from app.services import naming, transcript_export, transcript_pipeline
from app.services.data_explorer import (
    browse,
    downloads,
    slim,
    stats,
    transcript_zip,
    tree,
    walk,
)
from app.services.media_naming import transcript_filename

router = APIRouter(
    prefix="/data",
    tags=["data-explorer"],
    dependencies=[Depends(require_dataset_downloader)],
)

MANIFEST_MAX_PATHS = 50
MANIFEST_MAX_MEDIA_IDS = 2000
LANGUAGE_SCOPE_MAX = 20000
NDJSON = "application/x-ndjson"
STREAM_CHUNK = 200

_LANGUAGES_SQL = (
    'SELECT code, count(DISTINCT v."mediaFileId")::int AS count '
    'FROM "TranscriptVersion" v '
    'JOIN "MediaFile" m ON m."id" = v."mediaFileId" '
    'CROSS JOIN LATERAL unnest(v."languages") AS code '
    'WHERE v."supersededAt" IS NULL AND m."removedAt" IS NULL '
)


def _browser() -> Any:
    from app.api.routes import data_browser

    return data_browser


async def download_scope(user: Any) -> Any:
    scope = await _browser()._scope_for(user)
    narrowed = getattr(scope, "for_download", None)
    return narrowed() if callable(narrowed) else scope


def _live_media_where(scope: Any, media_id: str | list[str]) -> dict[str, Any]:
    ident: dict[str, Any] = {"id": media_id} if isinstance(media_id, str) else {"id": {"in": media_id}}
    parts: list[dict[str, Any]] = [ident, {"removedAt": None}]
    if getattr(scope, "media", None):
        parts.append(scope.media)
    return {"AND": parts}


async def media_for(user: Any, media_id: str, *, named: bool = True, lean: bool = True) -> Any:
    scope = await download_scope(user)
    kwargs: dict[str, Any] = {"where": _live_media_where(scope, media_id)}
    if named:
        kwargs["include"] = _browser()._NAMING_INCLUDE
    media = await (slim.find_one(**kwargs) if lean else db.mediafile.find_first(**kwargs))
    if media is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Media not found")
    return media


def _include(raw: Any) -> set[str] | None:
    picked = stats.parse_include(raw)
    if picked is None:
        return None
    unknown = picked - set(stats.TYPE_TOKENS)
    if unknown:
        raise HTTPException(
            status_code=422,
            detail=f"include takes {', '.join(stats.TYPE_TOKENS)}",
        )
    return picked


async def _folder_name(norm: str, used: set[str]) -> str:
    crumbs = await _browser()._resolve_crumbs(norm)
    name = str(crumbs[-1]["name"]) if crumbs else "Repository"
    name = name.replace("/", "-").strip() or "Folder"
    candidate, n = name, 2
    while candidate.lower() in used:
        candidate = f"{name}_{n}"
        n += 1
    used.add(candidate.lower())
    return candidate


async def _selection_media_entries(scope: Any, media_ids: list[str]) -> list[dict[str, Any]]:
    if not media_ids:
        return []
    rows = await slim.find_media(
        where=_live_media_where(scope, media_ids),
        include=_browser()._NAMING_INCLUDE,
        take=MANIFEST_MAX_MEDIA_IDS,
    )
    return _browser()._media_entries("", rows)


async def _selection(
    user: Any, paths: list[str], media_ids: list[str], include: set[str] | None, *, fill: bool
) -> tuple[list[dict[str, Any]], bool]:
    if len(paths) > MANIFEST_MAX_PATHS or len(media_ids) > MANIFEST_MAX_MEDIA_IDS:
        raise HTTPException(
            status_code=422,
            detail=f"Pick at most {MANIFEST_MAX_PATHS} folders and {MANIFEST_MAX_MEDIA_IDS} files at a time.",
        )
    scope = await download_scope(user)
    browser = _browser()
    used: set[str] = set()
    pairs: list[tuple[str, str]] = []
    for raw in dict.fromkeys(p for p in paths if isinstance(p, str)):
        norm = browser._norm(raw)
        pairs.append((norm, await _folder_name(norm, used) if norm else "Repository"))
    entries = await _selection_media_entries(scope, list(dict.fromkeys(str(m) for m in media_ids if m)))
    return await walk.manifest_files(
        browser._list_level, scope, pairs, include, media_entries=entries, fill=fill
    )


async def _with_transcript_sizes(files: list[dict[str, Any]]) -> None:
    wanted = [str(f["_fill"]) for f in files if f.get("_fill")]
    if not wanted:
        return
    sizes = await slim.transcript_sizes(wanted)
    for entry in files:
        mid = entry.get("_fill")
        if mid is not None:
            entry["sizeBytes"] = sizes.get(str(mid))


def manifest_headers(files: list[dict[str, Any]], totals: dict[str, Any], truncated: bool) -> dict[str, str]:
    return {
        "X-Dataset-Total": str(len(files)),
        "X-Dataset-Media": str(walk.media_count(files)),
        "X-Dataset-Bytes": str(totals["bytes"]),
        "X-Dataset-Truncated": "true" if truncated else "false",
        "X-Dataset-Skipped": "0",
    }


def manifest_response(files: list[dict[str, Any]], truncated: bool, *, stream: bool) -> Response:
    totals = stats.totals(files, truncated=truncated)
    headers = manifest_headers(files, totals, truncated)
    if not stream:
        return JSONResponse(
            {
                "files": files,
                "totalFiles": len(files),
                "totalMedia": walk.media_count(files),
                "totalBytes": totals["bytes"],
                "byType": totals["byType"],
                "truncated": truncated,
            },
            headers=headers,
        )

    async def body() -> AsyncIterator[bytes]:
        count = len(files)
        for start in range(0, count, STREAM_CHUNK):
            chunk = files[start : start + STREAM_CHUNK]
            await walk.fill_texts(chunk)
            lines = [json.dumps(entry, ensure_ascii=False) for entry in chunk if entry is not None]
            for index in range(start, min(start + STREAM_CHUNK, count)):
                files[index] = None  # type: ignore[call-overload]
            yield ("\n".join(lines) + "\n").encode("utf-8")

    headers["Content-Disposition"] = 'attachment; filename="manifest.ndjson"'
    return StreamingResponse(body(), media_type=NDJSON, headers=headers)


@router.get("/stats")
async def folder_stats(
    path: str = "",
    include: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    browser = _browser()
    scope = await download_scope(current_user)
    files, truncated = await walk.manifest_files(
        browser._list_level, scope, [(browser._norm(path), "")], _include(include), fill=False
    )
    await _with_transcript_sizes(files)
    return stats.totals(files, truncated=truncated)


@router.post("/stats")
async def selection_stats(
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    files, truncated = await _selection(
        current_user,
        list(body.get("paths") or []),
        list(body.get("mediaIds") or []),
        _include(body.get("include")),
        fill=False,
    )
    await _with_transcript_sizes(files)
    return stats.totals(files, truncated=truncated)


@router.post("/manifest", response_model=None)
async def selection_manifest(
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    stream = bool(body.get("stream"))
    files, truncated = await _selection(
        current_user,
        list(body.get("paths") or []),
        list(body.get("mediaIds") or []),
        _include(body.get("include")),
        fill=not stream,
    )
    return manifest_response(files, truncated, stream=stream)


@router.get("/media/{media_id}/transcript")
async def media_transcript(
    media_id: str, current_user: Any = Depends(require_dataset_downloader)
) -> dict[str, Any]:
    media = await media_for(current_user, media_id)
    if str(media.id) in await transcript_export.withheld_ids([str(media.id)]):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transcript not found")
    content = (await slim.transcript_texts([str(media.id)])).get(str(media.id), "")
    if not content.strip() and transcript_pipeline.enabled():
        try:
            doc, _context = await transcript_pipeline.render_doc(media)
            from app.transcription.transcript import render

            content = render.copy_all(doc, "both", timestamps=True)
        except transcript_pipeline.PipelineRefused:
            content = ""
    if not content.strip():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transcript not found")
    return {
        "mediaId": str(media.id),
        "name": transcript_filename(media),
        "content": content,
        "language": getattr(media, "transcriptLanguage", None),
    }


@router.get("/media/{media_id}/download")
async def media_download(
    media_id: str,
    format: str | None = None,
    fallback: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    media = await media_for(current_user, media_id)
    return await downloads.media_download(
        media, fmt=format, fallback=(fallback or "original").strip().lower() == "original"
    )


@router.get("/media/{media_id}/preview")
async def media_preview(
    media_id: str, current_user: Any = Depends(require_dataset_downloader)
) -> Response:
    return downloads.preview_redirect(await media_for(current_user, media_id))


@router.get("/media/{media_id}/thumbnail")
async def media_thumbnail(
    media_id: str,
    size: int | None = Query(None, ge=16, le=2048),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    return await downloads.thumbnail_redirect(await media_for(current_user, media_id, named=False), size)


async def _load_doc(media_id: str) -> Any:
    media = await slim.find_one(where={"AND": [{"id": media_id}, {"removedAt": None}]})
    if media is None:
        return None
    try:
        doc, _context = await transcript_pipeline.render_doc(media)
    except transcript_pipeline.PipelineRefused:
        return None
    return doc



@router.get("/transcript-bundle")
async def transcript_bundle(
    path: str = "", current_user: Any = Depends(require_dataset_downloader)
) -> StreamingResponse:
    browser = _browser()
    scope = await download_scope(current_user)
    norm = browser._norm(path)
    files, _truncated = await walk.manifest_files(
        browser._list_level, scope, [(norm, "")], {"transcripts"}, fill=False
    )
    files = [
        f
        for f in files
        if f.get("mediaId") and (f.get("transcriptFile") or "content" in f or f.get("_fill"))
    ]
    crumbs = await browser._resolve_crumbs(norm)
    level = crumbs[-1]["name"] if crumbs else "Repository"
    name = naming.export_filename(
        "Dataset",
        "Transcripts-All-Formats",
        "zip",
        subject="All Records" if level == "Repository" else level,
    )
    return StreamingResponse(
        transcript_zip.folder_zip(files, _load_doc, load_texts=slim.transcript_texts),
        media_type="application/zip",
        headers={"Content-Disposition": naming.content_disposition(name)},
    )


@router.get("/transcripts/languages")
async def transcript_languages(
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    from app.transcription.transcript.turns import language_label

    scope = await download_scope(current_user)
    if getattr(scope, "media", None):
        picked = await db.mediafile.group_by(
            by=["id"],
            where={"AND": [scope.media, {"removedAt": None}]},
            take=LANGUAGE_SCOPE_MAX,
            order={"id": "asc"},
        )
        ids = [str(row["id"]) for row in picked]
        if not ids:
            return {"languages": []}
        rows = await db.query_raw(
            _LANGUAGES_SQL + 'AND v."mediaFileId" = ANY($1::text[]) GROUP BY code ORDER BY count DESC, code',
            ids,
        )
    else:
        rows = await db.query_raw(_LANGUAGES_SQL + "GROUP BY code ORDER BY count DESC, code")
    return {
        "languages": [
            {"code": str(row["code"]), "label": language_label(str(row["code"])), "count": int(row["count"])}
            for row in rows
            if row.get("code")
        ]
    }


def _kind(type_key: str) -> str:
    key = (type_key or "").strip().lower()
    if key not in browse.KINDS:
        raise HTTPException(
            status_code=422,
            detail=f"type takes {', '.join(browse.KINDS)}",
        )
    return key


@router.get("/browse")
async def browse_page(
    type: str,
    page: int = Query(1, ge=1),
    pageSize: int = Query(browse.PAGE_SIZE_DEFAULT, ge=1, le=browse.PAGE_SIZE_MAX),
    q: str | None = Query(None, max_length=200),
    workshopIds: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    scope = await download_scope(current_user)
    page_number, size = browse.page_args(page, pageSize)
    workshops = [w.strip() for w in (workshopIds or "").split(",") if w.strip()] or None
    return await browse.page(
        _kind(type), scope, page_number=page_number, page_size=size, q=q, workshop_ids=workshops
    )


@router.get("/browse/{type}/{record_id}/media")
async def browse_record_media(
    type: str,
    record_id: str,
    page: int = Query(1, ge=1),
    pageSize: int = Query(browse.PAGE_SIZE_DEFAULT, ge=1, le=browse.PAGE_SIZE_MAX),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    kind = _kind(type)
    linked = browse.record_media_where(kind, record_id)
    if linked is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown record type")
    scope = await download_scope(current_user)
    parts: list[dict[str, Any]] = [linked, {"removedAt": None}]
    if getattr(scope, "media", None):
        parts.append(scope.media)
    where = {"AND": parts}
    page_number, size = browse.page_args(page, pageSize)
    rows = await slim.find_media(
        where=where,
        include=_browser()._NAMING_INCLUDE,
        take=size,
        skip=(page_number - 1) * size,
        order=[{"createdAt": "desc"}, {"id": "desc"}],
    )
    total = await db.mediafile.count(where=where)
    entries = _browser()._media_entries("", rows)
    items, _count, _next = await tree.shape(entries, lean=True)
    return {"items": items, **browse.envelope(total, page_number, size)}
