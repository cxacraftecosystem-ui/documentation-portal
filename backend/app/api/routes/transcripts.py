"""The transcript API (``shared/transcript-contract.json``): read, copy, download, edit, Ask, draft.

IDENTICAL IN BOTH PORTALS (listed in ``shared/transcription.sha256``). Who may read, edit and
send is each portal's own rule, in ``services/transcript_portal.py``.

Every route answers 404 ``{"detail": {"code": "NOT_FOUND"}}`` while the AI transcript switch is
off (AI_FEATURES_ENABLED + AI_TRANSCRIPT_INSIGHTS_ENABLED), AFTER authentication, and a transcript
the caller may not read is the same 404.

The static paths (``/batch``, ``/glossary``, ``/budget``) are declared before ``/{media_id}``.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import get_current_user
from app.services import transcript_pipeline as pipeline, transcript_portal
from app.transcription.transcript import render
from app.transcription.transcript.schema import (
    AskBody,
    BudgetBody,
    CopyBody,
    QuoteCardBody,
    SpeakerMappingBody,
    TurnEditBody,
)

router = APIRouter(prefix="/transcripts", tags=["transcripts"])

BATCH_MAX = 50
GLOSSARY_MAX = 100

MEDIA_TYPES = {
    "md": "text/markdown; charset=utf-8",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "srt": "application/x-subrip; charset=utf-8",
    "vtt": "text/vtt; charset=utf-8",
}


def _error(code: str, message: str, status_code: int) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})


def _not_found() -> HTTPException:
    return _error("NOT_FOUND", "Not found", status.HTTP_404_NOT_FOUND)


def require_enabled(_user: Any = Depends(get_current_user)) -> None:
    if not pipeline.enabled():
        raise _not_found()


ENABLED = [Depends(require_enabled)]


def _refused(exc: pipeline.PipelineRefused) -> HTTPException:
    return _error(exc.code, exc.message, exc.status)


async def _media(media_id: str, user: Any) -> Any:
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None or str(media.id) not in await transcript_portal.readable_ids([media], user):
        raise _not_found()
    return media


def _require_admin(user: Any) -> None:
    if not transcript_portal.is_admin(user):
        raise _error("FORBIDDEN", "Only an admin can see the transcription budget.", status.HTTP_403_FORBIDDEN)


@router.get("/batch", dependencies=ENABLED)
async def batch(ids: str = Query("", max_length=4_000), user: Any = Depends(get_current_user)) -> dict[str, Any]:
    wanted = list(dict.fromkeys(part.strip() for part in ids.split(",") if part.strip()))
    if len(wanted) > BATCH_MAX:
        raise _error("VALIDATION_FAILED", f"Ask for at most {BATCH_MAX} at a time.", status.HTTP_422_UNPROCESSABLE_CONTENT)
    if not wanted:
        return {"items": []}
    rows = await db.mediafile.find_many(where={"id": {"in": wanted}})
    readable = await transcript_portal.readable_ids(rows, user)
    return {"items": await pipeline.summary_items([r for r in rows if str(r.id) in readable], user)}


@router.get("/glossary", dependencies=ENABLED)
async def glossary(
    craftId: str | None = Query(None, max_length=64),
    q: str | None = Query(None, max_length=60),
    limit: int = Query(50, ge=1, le=GLOSSARY_MAX),
    cursor: str | None = Query(None, max_length=64),
    _user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await pipeline.glossary(craftId, q, limit, cursor)


@router.get("/budget", dependencies=ENABLED)
async def get_budget(user: Any = Depends(get_current_user)) -> dict[str, Any]:
    _require_admin(user)
    return await pipeline.budget_view()


@router.put("/budget", dependencies=ENABLED)
async def put_budget(body: BudgetBody, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    _require_admin(user)
    view = await pipeline.set_budget(body.monthlyUsd, getattr(user, "id", None))
    await pipeline.check_budget()
    return view


@router.get("/{media_id}", dependencies=ENABLED)
async def read(media_id: str, version: int | None = Query(None, ge=1), user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    return await pipeline.build_view(media, user, version_number=version)


@router.get("/{media_id}/versions", dependencies=ENABLED)
async def list_versions(media_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.services import transcript_store

    media = await _media(media_id, user)
    admin = transcript_portal.is_admin(user)
    rows = await transcript_store.versions(str(media.id))
    return {
        "items": [
            {
                "version": v.version,
                "createdAt": pipeline._iso(v.createdAt),
                "current": v.supersededAt is None,
                "status": "READY",
                "badges": pipeline._badges(v) if transcript_portal.may_see_spend(user) else [],
                "turnCount": v.turnCount,
                "durationMs": v.durationMs,
                "costUsd": round((v.sttCostUsd or 0) + (v.translationCostUsd or 0), 4) if admin else None,
            }
            for v in rows
        ]
    }


@router.put("/{media_id}/speakers", dependencies=ENABLED)
async def put_speakers(media_id: str, body: SpeakerMappingBody, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    await transcript_portal.guard_write(user, media)
    try:
        await pipeline.map_speakers(media, user, body.baseRevision, body.mappings)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None
    return await pipeline.build_view(media, user)


@router.get("/{media_id}/speakers/suggestions", dependencies=ENABLED)
async def speaker_suggestions(media_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    return {"items": await pipeline.speaker_suggestions(media)}


@router.patch("/{media_id}/turns/{turn_id}", dependencies=ENABLED)
async def patch_turn(media_id: str, turn_id: str, body: TurnEditBody, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    await transcript_portal.guard_write(user, media)
    if body.text is None and body.english is None:
        raise _error("VALIDATION_FAILED", "Send the new original or the new English.", status.HTTP_422_UNPROCESSABLE_CONTENT)
    try:
        return await pipeline.edit_turn(
            media, user, turn_id, base_revision=body.baseRevision, text=body.text, english=body.english
        )
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None


@router.get("/{media_id}/turns/{turn_id}/history", dependencies=ENABLED)
async def turn_history(media_id: str, turn_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    try:
        return {"items": await pipeline.turn_history(media, turn_id)}
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None


@router.post("/{media_id}/turns/{turn_id}/retranslate", dependencies=ENABLED)
async def retranslate(media_id: str, turn_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    await transcript_portal.guard_write(user, media)
    try:
        return await pipeline.retranslate_turn(media, user, turn_id)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None


@router.post("/{media_id}/retry", dependencies=ENABLED, status_code=status.HTTP_202_ACCEPTED)
async def retry(media_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.services import transcript_insights

    media = await _media(media_id, user)
    await transcript_portal.guard_write(user, media)
    may, refusal = await transcript_portal.may_send(media)
    if not may:
        raise _error("CONSENT_REFUSED", refusal or "Consent was refused.", status.HTTP_409_CONFLICT)
    from app.services import transcript_store

    if await transcript_store.current_version(str(media.id)) is not None or (media.transcriptText or "").strip():
        await transcript_insights.enqueue(str(media.id), requested_by_id=getattr(user, "id", None))
    else:
        await pipeline.requeue_transcription(media)
    return await pipeline.build_view(media, user)


@router.post("/{media_id}/transcribe-now", dependencies=ENABLED, status_code=status.HTTP_202_ACCEPTED)
async def transcribe_now(media_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    if not transcript_portal.is_admin(user):
        raise _error("FORBIDDEN", "Only an admin can transcribe now.", status.HTTP_403_FORBIDDEN)
    try:
        await pipeline.transcribe_now(media)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None
    return await pipeline.build_view(media, user)


@router.post("/{media_id}/copy", dependencies=ENABLED)
async def copy(media_id: str, body: CopyBody, user: Any = Depends(get_current_user)) -> dict[str, str]:
    media = await _media(media_id, user)
    try:
        doc, _context = await pipeline.render_doc(media)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None
    if body.turnIds is None:
        return {"text": render.copy_all(doc, body.track)}
    known = {t.id for t in doc.turns}
    if any(tid not in known for tid in body.turnIds):
        raise _error("TURN_UNKNOWN", "That turn isn't in this transcript.", status.HTTP_404_NOT_FOUND)
    if body.single:
        if len(body.turnIds) != 1:
            raise _error("VALIDATION_FAILED", "A single copy names one turn.", status.HTTP_422_UNPROCESSABLE_CONTENT)
        turn = next(t for t in doc.turns if t.id == body.turnIds[0])
        return {"text": render.copy_single(turn, body.track)}
    return {"text": render.copy_selected(doc, body.turnIds, body.track)}


@router.get("/{media_id}/download", dependencies=ENABLED)
async def download(
    media_id: str,
    format: str = Query(..., pattern="^(md|docx|srt|vtt)$"),
    track: str = Query("both", pattern="^(original|english|both)$"),
    timestamps: bool = Query(True),
    user: Any = Depends(get_current_user),
) -> Response:
    from app.services import naming

    media = await _media(media_id, user)
    try:
        doc, context = await pipeline.render_doc(media)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None
    if format in {"srt", "vtt"} and track == "both":
        track = "original"
    if format == "md":
        content: bytes = render.copy_all(doc, track, timestamps=timestamps).encode("utf-8")
    elif format == "docx":
        content = render.docx(doc, track, timestamps=timestamps)
    elif format == "srt":
        content = render.srt(doc, track).encode("utf-8")
    else:
        content = render.vtt(doc, track).encode("utf-8")
    name = pipeline.download_name(media, context, format, track)
    return Response(
        content=content,
        media_type=MEDIA_TYPES[format],
        headers={"Content-Disposition": naming.content_disposition(name)},
    )


@router.post("/{media_id}/ask", dependencies=ENABLED)
async def ask(media_id: str, body: AskBody, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    try:
        return await pipeline.ask(media, body.question)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None


@router.post("/{media_id}/autodraft", dependencies=ENABLED)
async def autodraft(media_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    await transcript_portal.guard_write(user, media)
    try:
        return await pipeline.autodraft(media, user)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None


@router.post("/{media_id}/quote-card", dependencies=ENABLED)
async def quote_card(media_id: str, body: QuoteCardBody, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media(media_id, user)
    try:
        return await pipeline.quote_card(media, body.turnIds)
    except pipeline.PipelineRefused as exc:
        raise _refused(exc) from None
