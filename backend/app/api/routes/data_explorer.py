from __future__ import annotations

import asyncio
import contextlib
import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Query, status
from fastapi.responses import JSONResponse, Response, StreamingResponse

from app.core.db import db
from app.core.deps import require_dataset_downloader
from app.services import naming, transcript_export, transcript_pipeline
from app.services.data_explorer import (
    browse,
    downloads,
    isolate,
    overview,
    peaks,
    report_options,
    saved,
    signing,
    slim,
    stats,
    transcript_docx,
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


async def manifest_response(files: list[dict[str, Any]], truncated: bool, *, stream: bool) -> Response:
    totals = stats.totals(files, truncated=truncated)
    headers = manifest_headers(files, totals, truncated)
    if not stream:
        await walk.fill_digests(files)
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
            await walk.fill_digests(chunk)
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
    return await manifest_response(files, truncated, stream=stream)


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
    media_id: str,
    variant: str | None = Query(None, pattern="^(original|isolated)$"),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    media = await media_for(current_user, media_id)
    if variant == "isolated":
        return isolate.isolated_redirect(media)
    return downloads.preview_redirect(media)


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


TRANSCRIPT_LIST_MAX = 500
TRANSCRIPT_SNIPPET_CHARS = 160


def _snippet(text: str, words: list[str]) -> str:
    flat = " ".join(text.split())
    lower = flat.lower()
    at = min((lower.find(w) for w in words if lower.find(w) >= 0), default=0)
    start = max(0, at - TRANSCRIPT_SNIPPET_CHARS // 3)
    piece = flat[start : start + TRANSCRIPT_SNIPPET_CHARS].strip()
    lead = "…" if start > 0 else ""
    tail = "…" if start + TRANSCRIPT_SNIPPET_CHARS < len(flat) else ""
    return f"{lead}{piece}{tail}"


async def _folder_transcripts(
    scope: Any, norm: str, offset: int = 0
) -> tuple[dict[str, dict[str, Any]], dict[str, Any], bool]:
    browser = _browser()
    files, truncated = await walk.manifest_files(
        browser._list_level, scope, [(norm, "")], {"transcripts", "audios", "videos"}, fill=False
    )
    seen: dict[str, dict[str, Any]] = {}
    for f in files:
        media_id = f.get("mediaId")
        if not media_id or media_id in seen:
            continue
        kind = str(f.get("mediaType") or "").upper()
        if kind not in {"AUDIO", "VIDEO", "TRANSCRIPT"}:
            continue
        seen[str(media_id)] = {
            "mediaId": str(media_id),
            "name": f.get("originalFilename") or str(f.get("path") or "").rsplit("/", 1)[-1],
            "path": f.get("path"),
            "mediaType": kind if kind != "TRANSCRIPT" else None,
        }
        if len(seen) >= offset + TRANSCRIPT_LIST_MAX:
            truncated = True
            break
    if offset:
        seen = dict(list(seen.items())[offset:])
    ids = list(seen)
    rows = await db.mediafile.find_many(
        where=_live_media_where(scope, ids) if ids else {"id": {"in": []}}
    )
    return seen, {str(r.id): r for r in rows}, truncated


@router.get("/transcripts/list")
async def transcript_list(
    path: str = "",
    q: str | None = Query(None, max_length=200),
    offset: int = Query(0, ge=0, le=walk.MAX_MANIFEST_FILES),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    offset = offset if isinstance(offset, int) else 0
    scope = await download_scope(current_user)
    seen, by_id, truncated = await _folder_transcripts(scope, _browser()._norm(path), offset)
    window = len(seen)
    words = [w for w in (q or "").lower().split() if w]
    items: list[dict[str, Any]] = []
    for media_id, item in seen.items():
        row = by_id.get(media_id)
        if row is None:
            continue
        text = str(getattr(row, "transcriptText", None) or "")
        item["mediaType"] = item["mediaType"] or str(row.mediaType).split(".")[-1]
        item["name"] = row.originalFilename or item["name"]
        item["hasText"] = bool(text.strip())
        item["capturedAt"] = row.createdAt.isoformat() if row.createdAt else None
        if words:
            lower = text.lower()
            if not all(w in lower for w in words):
                continue
            item["snippet"] = _snippet(text, words)
        items.append(item)
    return {
        "items": items,
        "total": len(items),
        "truncated": truncated,
        "query": q or None,
        "offset": offset,
        "nextOffset": offset + window if truncated and window else None,
    }


DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


async def _transcripts_docx(scope: Any, norm: str, track: str) -> bytes | None:
    from app.transcription.transcript import render

    seen, by_id, _truncated = await _folder_transcripts(scope, norm)
    ordered = [by_id[mid] for mid in seen if mid in by_id]
    withheld = await transcript_export.withheld_ids([str(m.id) for m in ordered])
    combined = transcript_docx.Combined()
    for media in ordered:
        if str(media.id) in withheld:
            continue
        try:
            doc, _context = await transcript_pipeline.render_doc(media)
        except transcript_pipeline.PipelineRefused:
            continue
        if not doc.turns and not (doc.english_text or "").strip():
            continue
        single = await asyncio.to_thread(render.docx, doc, track)
        await asyncio.to_thread(combined.add, single)
    return combined.finish()


@router.get("/transcripts/export")
async def transcript_export_docx(
    path: str = "",
    format: str = Query("docx", pattern="^docx$"),
    track: str = Query("both", pattern="^(original|english|both)$"),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    from app.services.data_explorer import report_cache

    browser = _browser()
    scope = await download_scope(current_user)
    norm = browser._norm(path)
    try:
        async with report_cache.QUEUE.slot():
            payload = await _transcripts_docx(scope, norm, track)
    except report_cache.ReportBusy as busy:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=report_cache.REPORT_BUSY,
            headers={"Retry-After": str(busy.retry_after)},
        ) from None
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No transcripts in this folder")
    crumbs = await browser._resolve_crumbs(norm)
    level = crumbs[-1]["name"] if crumbs else "Repository"
    name = naming.export_filename(
        "Dataset",
        "Transcripts",
        "docx",
        subject="All Records" if level == "Repository" else level,
    )
    return Response(
        content=payload,
        media_type=DOCX_MIME,
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


@router.get("/report-options")
async def get_report_options(current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    row = await db.datareportpreference.find_unique(where={"userId": str(current_user.id)})
    stored = saved.options_out(row)
    if stored is None:
        return {"options": None}
    try:
        return {"options": report_options.stored_options(stored)}
    except report_options.OptionsInvalid:
        return {"options": None}


@router.put("/report-options")
async def put_report_options(
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    from prisma import Json

    try:
        options = report_options.stored_options(body.get("options"))
    except report_options.OptionsInvalid as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    uid = str(current_user.id)
    await db.datareportpreference.upsert(
        where={"userId": uid},
        data={"create": {"userId": uid, "options": Json(options)}, "update": {"options": Json(options)}},
    )
    return {"options": options}


VISIT_COUNT_SECONDS = 10.0
_NEW_SINCE_SQL = (
    'SELECT count(*) FILTER (WHERE m."createdAt" > $2::timestamp)::int AS "files", '
    'count(*) FILTER (WHERE (m."createdAt" > $2::timestamp AND m."transcriptText" IS NOT NULL '
    "AND m.\"transcriptText\" <> '') OR EXISTS (SELECT 1 FROM \"TranscriptVersion\" v "
    'WHERE v."mediaFileId" = m."id" AND v."createdAt" > $2::timestamp))::int AS "transcripts" '
    'FROM "MediaFile" m WHERE m."id" = ANY($1::text[]) AND m."removedAt" IS NULL'
)
_GLOBAL_HEADS = frozenset({"by-workshop", "workshops", "by-uploader", "by-type", "media-types", "users"})


def _utc_naive(moment: datetime) -> str:
    if moment.tzinfo is not None:
        moment = moment.astimezone(UTC).replace(tzinfo=None)
    return moment.isoformat(sep=" ")


async def _visible(scope: Any, path: str) -> bool:
    try:
        await _browser()._list_level(path, scope)
    except HTTPException:
        return False
    return True


async def _new_since(scope: Any, norm: str, since: datetime) -> tuple[int, int]:
    segs = [s for s in norm.split("/") if s]
    if not segs or (len(segs) == 1 and segs[0] in _GLOBAL_HEADS):
        parts: list[dict[str, Any]] = [{"removedAt": None}]
        if getattr(scope, "media", None):
            parts.append(scope.media)
        files = await db.mediafile.count(where={"AND": [*parts, {"createdAt": {"gt": since}}]})
        texts = await db.mediafile.count(
            where={
                "AND": [
                    *parts,
                    {
                        "OR": [
                            {"AND": [{"createdAt": {"gt": since}}, {"transcriptText": {"not": None}}]},
                            {"transcriptVersions": {"some": {"createdAt": {"gt": since}}}},
                        ]
                    },
                ]
            }
        )
        return files, texts
    browser = _browser()
    found, _truncated = await walk.manifest_files(browser._list_level, scope, [(norm, "")], None, fill=False)
    ids = list(dict.fromkeys(str(f["mediaId"]) for f in found if f.get("mediaId")))
    files = texts = 0
    stamp = _utc_naive(since)
    for start in range(0, len(ids), MANIFEST_MAX_MEDIA_IDS):
        rows = await db.query_raw(_NEW_SINCE_SQL, ids[start : start + MANIFEST_MAX_MEDIA_IDS], stamp)
        row = rows[0] if rows else {}
        files += int(row.get("files") or 0)
        texts += int(row.get("transcripts") or 0)
    return files, texts


@router.post("/visits")
async def record_visit(
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    browser = _browser()
    try:
        norm = saved.path_of(body.get("path"), browser._norm)
    except saved.Invalid as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    scope = await download_scope(current_user)
    if not await _visible(scope, norm):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
    uid = str(current_user.id)
    where = {"userId_path": {"userId": uid, "path": norm}}
    previous = await db.datafoldervisit.find_unique(where=where)
    now = datetime.now(UTC)
    await db.datafoldervisit.upsert(
        where=where,
        data={"create": {"userId": uid, "path": norm, "visitedAt": now}, "update": {"visitedAt": now}},
    )
    since = previous.visitedAt if previous is not None else None
    files = texts = 0
    if since is not None:
        try:
            files, texts = await asyncio.wait_for(_new_since(scope, norm, since), VISIT_COUNT_SECONDS)
        except TimeoutError:
            files = texts = 0
    return {
        "path": norm,
        "visitedAt": now.isoformat(),
        "previousVisitAt": since.isoformat() if since is not None else None,
        "newFiles": files,
        "newTranscripts": texts,
    }


_VIEW_INCLUDE = {"owner": True}


async def _owned_view(view_id: str, uid: str) -> Any:
    row = await db.datasavedview.find_unique(where={"id": view_id}, include=_VIEW_INCLUDE)
    if row is None or str(row.ownerId) != uid:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="View not found")
    return row


@router.get("/views")
async def list_views(current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    uid = str(current_user.id)
    mine = await db.datasavedview.find_many(
        where={"ownerId": uid},
        include=_VIEW_INCLUDE,
        order={"updatedAt": "desc"},
        take=saved.VIEWS_PER_OWNER,
    )
    others = await db.datasavedview.find_many(
        where={"AND": [{"shared": True}, {"ownerId": {"not": uid}}]},
        include=_VIEW_INCLUDE,
        order={"updatedAt": "desc"},
        take=saved.SHARED_LIST_MAX,
    )
    scope = await _browser()._scope_for(current_user)
    seen: dict[str, bool] = {}
    shared: list[dict[str, Any]] = []
    for row in others:
        if row.path not in seen:
            seen[row.path] = await _visible(scope, row.path)
        if seen[row.path]:
            shared.append(saved.view_out(row, uid))
    return {"mine": [saved.view_out(r, uid) for r in mine], "shared": shared}


@router.post("/views")
async def create_view(
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    from prisma import Json

    browser = _browser()
    try:
        name = saved.name_of(body.get("name"))
        path = saved.path_of(body.get("path"), browser._norm)
        state = saved.state_of(body.get("state"))
        shared = saved.shared_of(body.get("shared"))
    except saved.Invalid as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    uid = str(current_user.id)
    if await db.datasavedview.count(where={"ownerId": uid}) >= saved.VIEWS_PER_OWNER:
        raise HTTPException(
            status_code=422, detail=f"You can keep {saved.VIEWS_PER_OWNER} views. Delete one first."
        )
    if not await _visible(await browser._scope_for(current_user), path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
    row = await db.datasavedview.create(
        data={"ownerId": uid, "name": name, "path": path, "state": Json(state), "shared": shared},
        include=_VIEW_INCLUDE,
    )
    return saved.view_out(row, uid)


@router.get("/views/{view_id}")
async def read_view(view_id: str, current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    uid = str(current_user.id)
    row = await db.datasavedview.find_unique(where={"id": view_id}, include=_VIEW_INCLUDE)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="View not found")
    if str(row.ownerId) != uid and (
        not row.shared or not await _visible(await _browser()._scope_for(current_user), row.path)
    ):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="View not found")
    return saved.view_out(row, uid)


@router.patch("/views/{view_id}")
async def update_view(
    view_id: str,
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    uid = str(current_user.id)
    current = await _owned_view(view_id, uid)
    data: dict[str, Any] = {}
    try:
        if "name" in body:
            data["name"] = saved.name_of(body.get("name"))
        if "shared" in body:
            data["shared"] = saved.shared_of(body.get("shared"))
    except saved.Invalid as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    if not data:
        return saved.view_out(current, uid)
    row = await db.datasavedview.update(where={"id": view_id}, data=data, include=_VIEW_INCLUDE)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="View not found")
    return saved.view_out(row, uid)


@router.delete("/views/{view_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_view(view_id: str, current_user: Any = Depends(require_dataset_downloader)) -> Response:
    await _owned_view(view_id, str(current_user.id))
    await db.datasavedview.delete(where={"id": view_id})
    return Response(status_code=status.HTTP_204_NO_CONTENT)


def _peaks_source(media: Any) -> str:
    object_key = getattr(media, "objectKey", None)
    if object_key:
        url, _age = signing.signed_get(
            str(object_key),
            filename=str(media.id),
            mime_type=downloads.base_mime(getattr(media, "mimeType", None)) or "application/octet-stream",
        )
        return url
    stored = getattr(media, "url", None)
    if stored:
        return str(stored)
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No waveform")


@router.get("/media/{media_id}/peaks")
async def media_peaks(media_id: str, current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    from prisma import Json

    media = await media_for(current_user, media_id, named=False)
    if downloads.kind_of(media) not in peaks.KINDS:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No waveform")
    voices = {"isolated": isolate.isolated(media), "canIsolate": await isolate.can_isolate(current_user, media)}
    stored = await db.mediawaveform.find_unique(where={"mediaFileId": str(media.id)})
    if stored is not None and isinstance(stored.peaks, list) and stored.peaks:
        return {"peaks": stored.peaks, "durationMs": stored.durationMs, **voices}
    try:
        values, duration = await peaks.peaks_of(_peaks_source(media))
    except peaks.DecodeBusy:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Waveforms are being made. Try again shortly.",
            headers={"Retry-After": "15"},
        ) from None
    except peaks.DecodeFailed:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No waveform") from None
    with contextlib.suppress(Exception):
        await db.mediawaveform.upsert(
            where={"mediaFileId": str(media.id)},
            data={
                "create": {"mediaFileId": str(media.id), "peaks": Json(values), "durationMs": duration},
                "update": {"peaks": Json(values), "durationMs": duration},
            },
        )
    return {"peaks": values, "durationMs": duration, **voices}


@router.post("/media/{media_id}/isolate", status_code=status.HTTP_202_ACCEPTED)
async def media_isolate(media_id: str, current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    media = await media_for(current_user, media_id, named=False)
    if downloads.kind_of(media) != "AUDIO":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not a recording")
    return await isolate.request(current_user, media)


@router.get("/overview")
async def workshop_overview(
    path: str = "", current_user: Any = Depends(require_dataset_downloader)
) -> dict[str, Any]:
    browser = _browser()
    scope = await download_scope(current_user)
    return await overview.workshop_overview(browser, browser._norm(path), scope)
