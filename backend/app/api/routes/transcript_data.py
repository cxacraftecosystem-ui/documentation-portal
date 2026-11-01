"""Transcripts in "View data" and "Download data" (0.0.6): a paged view and streamed downloads.

IDENTICAL IN BOTH PORTALS (listed in ``shared/transcription.sha256``). Same gate and row visibility as
the data browser: the dataset-download permission, then the browser's own scope (the download scope
where the portal has one), so a reader sees exactly the recordings the browser shows them.

* ``GET /data/transcripts``                       one page of recordings as JSON (``cursor``, ``limit``):
                                                   turns, summary, quotes, glossary, chips
* ``GET /data/transcripts/{kind}.csv|.ndjson``    every matching row, STREAMED: ``recordings`` (one row
                                                   per recording), ``turns`` (one per turn), ``glossary``
                                                   (one per term heard in a recording)

Filters (all optional, repeatable or comma-separated): ``language``, ``status``, ``speaker``,
``researcherId``, ``since``, ``until``, ``hasEnglish``, ``q``, and every questionnaire filter of
``app/questionnaire/filters.py`` (form, section, question, artisans, workshop, ...).

Consent and the administrator-only cost and model columns follow ``services/transcript_export``.
Memory stays flat: one batch of recordings is loaded, encoded, sent and dropped before the next.
"""

from __future__ import annotations

import csv
import io
import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse

from app.core.db import db
from app.core.deps import require_dataset_downloader
from app.services import naming, transcript_export, transcript_portal

router = APIRouter(prefix="/data/transcripts", tags=["data"])

KINDS = {
    "recordings": ("Transcripts", None),
    "turns": ("Transcript-Turns", transcript_export.TURN_COLUMNS),
    "glossary": ("Transcript-Glossary", transcript_export.GLOSSARY_COLUMNS),
}
PAGE_MAX = transcript_export.BATCH


async def _visible_fn(user: Any) -> Any:
    """The data browser's media scope as ``ids -> visible ids`` (None when it narrows nothing)."""
    from app.api.routes import data_browser

    scope = await data_browser._scope_for(user)
    if hasattr(scope, "for_download"):
        scope = scope.for_download()
    media_where = getattr(scope, "media", None) or {}
    if not media_where:
        return None

    async def visible(ids: list[str]) -> set[str]:
        rows = await db.mediafile.find_many(where={"AND": [{"id": {"in": ids}}, media_where]})
        return {str(r.id) for r in rows}

    return visible


async def _filters(request: Request) -> transcript_export.Filters:
    from app.questionnaire import filters as q_filters

    f = transcript_export.parse_filters(request.query_params)
    own = ("language", "status", "speaker", "researcherId", "since", "until", "hasEnglish", "q", "cursor", "limit")
    qf = q_filters.parse(request.query_params).without(*own)
    if not qf.empty():
        f.media_ids = await q_filters.media_ids_matching(qf)
    return f


@router.get("")
async def transcripts_page(
    request: Request,
    cursor: str | None = None,
    limit: int = Query(20, ge=1, le=PAGE_MAX),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    """One page of recordings with everything the transcript pipeline added, for the data browser."""
    f = await _filters(request)
    visible = await _visible_fn(current_user)
    sees_spend = transcript_portal.may_see_spend(current_user)
    page = await transcript_export.select_page(f, cursor, limit=limit)
    shown = page
    if visible is not None and page:
        allowed = await visible(page)
        shown = [mid for mid in page if mid in allowed]
    loaded = await transcript_export.load(shown, sees_spend=sees_spend) if shown else {}
    return {
        "items": [loaded[mid] for mid in shown if mid in loaded],
        "nextCursor": page[-1] if len(page) == limit else None,
        "filters": f.echo(),
    }


def _filename(kind: str, ext: str) -> str:
    return naming.file_name(entity="Dataset", what=KINDS[kind][0], ext=ext, date=datetime.now(UTC), time=True)


@router.get("/{kind}.{fmt}")
async def transcripts_download(
    kind: str,
    fmt: str,
    request: Request,
    current_user: Any = Depends(require_dataset_downloader),
) -> StreamingResponse:
    """Every matching row, streamed as CSV or NDJSON."""
    if kind not in KINDS or fmt not in ("csv", "ndjson"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": "NOT_FOUND", "message": "Not found"})
    f = await _filters(request)
    visible = await _visible_fn(current_user)
    sees_spend = transcript_portal.may_see_spend(current_user)
    columns = transcript_export.recording_columns(sees_spend) if kind == "recordings" else KINDS[kind][1]

    def rows_of(rec: dict[str, Any]) -> list[dict[str, Any]]:
        if kind == "recordings":
            return [transcript_export.recording_row(rec, sees_spend=sees_spend)]
        if kind == "turns":
            return transcript_export.turn_rows(rec)
        return transcript_export.glossary_rows(rec)

    async def body() -> AsyncIterator[bytes]:
        buffer = io.StringIO()
        writer = csv.writer(buffer) if fmt == "csv" else None
        if writer is not None:
            buffer.write("﻿")  # Excel opens UTF-8 (Devanagari) correctly with a BOM
            writer.writerow(columns)
        pending = 0
        async for rec in transcript_export.iter_recordings(f, sees_spend=sees_spend, visible=visible):
            for row in rows_of(rec):
                if writer is not None:
                    writer.writerow([row.get(c, "") for c in columns])
                else:
                    payload = rec if kind == "recordings" else row
                    buffer.write(json.dumps(payload, ensure_ascii=False) + "\n")
                pending += 1
            if pending >= 200 or buffer.tell() > 256_000:
                yield buffer.getvalue().encode("utf-8")
                buffer.seek(0)
                buffer.truncate(0)
                pending = 0
        tail = buffer.getvalue()
        if tail:
            yield tail.encode("utf-8")

    media_type = "text/csv; charset=utf-8" if fmt == "csv" else "application/x-ndjson"
    return StreamingResponse(
        body(), media_type=media_type, headers={"Content-Disposition": naming.content_disposition(_filename(kind, fmt))}
    )
