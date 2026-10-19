"""Interview summaries, key quotes and translations: read, edit, regenerate.

Every route answers 404 ``{"detail": "Not found"}`` while the AI capability flag is off, and the
clients hide the section on a 404. Reading is open to every signed-in account, as transcripts are
in this repository. Editing and regenerating follow ``POST /media/{id}/transcript``: the uploader
or an admin.

INCLUDED IN ``api/router.py`` BEFORE ``media.router``. ``GET /media/insights`` would otherwise be
matched by ``GET /media/{media_id}``.

Nothing here generates. Regenerate queues a job and returns 202; the worker does the work.
Regenerate asks the one consent store first and answers 409 with the reason when the transcript may
not leave the server; the worker asks again at the moment of sending.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import JSONResponse

from app.core.db import db
from app.core.deps import get_current_user, is_admin
from app.schemas.transcript_insights import TranscriptInsightPatch, TranscriptInsightRegenerate
from app.services import transcript_insights as insights
from prisma import Json

router = APIRouter(prefix="/media", tags=["media"])

BATCH_MAX = 50


def require_enabled(_user: Any = Depends(get_current_user)) -> None:
    """404 while the switch is off, AFTER authentication, so a caller with no session is refused
    as on every other route rather than told whether the feature exists."""
    if not insights.enabled():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")


def may_edit(user: Any, media: Any) -> bool:
    return bool(is_admin(user) or getattr(media, "uploadedById", None) == getattr(user, "id", None))


async def _media_or_404(media_id: str) -> Any:
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    return media


async def _view(media: Any, user: Any) -> dict[str, Any]:
    media_id = str(media.id)
    row = await insights.current_insight(media_id)
    job = (await insights.latest_jobs([media_id])).get(media_id)
    return insights.build_view(media, row, job, may_edit=may_edit(user, media))


def _require_edit(user: Any, media: Any) -> None:
    if not may_edit(user, media):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the uploader or an admin can change this summary.",
        )


@router.get("/insights", dependencies=[Depends(require_enabled)])
async def list_insights(
    ids: str = Query("", max_length=4_000),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """``{"items": {mediaId: view}}`` for up to 50 media ids, translation text left out. Ids that do
    not exist are left out."""
    wanted = list(dict.fromkeys(part.strip() for part in ids.split(",") if part.strip()))
    if len(wanted) > BATCH_MAX:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Ask for at most {BATCH_MAX} at a time.",
        )
    if not wanted:
        return {"items": {}}
    media_rows = await db.mediafile.find_many(where={"id": {"in": wanted}})
    found = [str(m.id) for m in media_rows]
    rows = await insights.current_insights(found)
    jobs = await insights.latest_jobs(found)
    items = {
        str(m.id): insights.build_view(
            m,
            rows.get(str(m.id)),
            jobs.get(str(m.id)),
            may_edit=may_edit(current_user, m),
            include_translation=False,
        )
        for m in media_rows
    }
    return {"items": items}


@router.get("/{media_id}/insights", dependencies=[Depends(require_enabled)])
async def get_insight(media_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media_or_404(media_id)
    return await _view(media, current_user)


@router.patch("/{media_id}/insights", dependencies=[Depends(require_enabled)], response_model=None)
async def patch_insight(
    media_id: str,
    payload: TranscriptInsightPatch,
    current_user: Any = Depends(get_current_user),
) -> Any:
    """Edit the current summary. Stamps ``editedAt``/``editedById`` from the server; the frozen
    machine output in ``generated`` is never touched."""
    media = await _media_or_404(media_id)
    _require_edit(current_user, media)
    row = await insights.current_insight(media_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    if row.version != payload.version:
        return JSONResponse(
            status_code=status.HTTP_409_CONFLICT,
            content={"detail": "This summary changed since you opened it.", "code": "VERSION_CONFLICT"},
        )
    data: dict[str, Any] = {
        "editedAt": datetime.now(UTC),
        "editedById": current_user.id,
    }
    if payload.summaryLines is not None:
        data["summaryLines"] = payload.summaryLines
    if payload.quotes is not None:
        data["quotes"] = Json([quote.model_dump() for quote in payload.quotes])
    if payload.translationText is not None:
        text = payload.translationText.strip()
        data["translationText"] = text or None
        if text and row.translationStatus == insights.FAILED:
            data["translationStatus"] = insights.COMPLETED
            data["translationNote"] = None
    # Conditional on the version still being current, so a regeneration landing between the read
    # above and this write is a conflict rather than an edit on a superseded row.
    updated = await db.transcriptinsight.update_many(
        where={"id": row.id, "version": payload.version, "supersededAt": None}, data=data
    )
    if updated != 1:
        return JSONResponse(
            status_code=status.HTTP_409_CONFLICT,
            content={"detail": "This summary changed since you opened it.", "code": "VERSION_CONFLICT"},
        )
    return await _view(media, current_user)


@router.post(
    "/{media_id}/insights/regenerate",
    dependencies=[Depends(require_enabled)],
    status_code=status.HTTP_202_ACCEPTED,
    response_model=None,
)
async def regenerate_insight(
    media_id: str,
    payload: TranscriptInsightRegenerate,
    current_user: Any = Depends(get_current_user),
) -> Any:
    """Queue a new version. The run uses the caller's own AI key when they have one."""
    media = await _media_or_404(media_id)
    _require_edit(current_user, media)
    primary, _original = insights.select_sources(media)
    if not primary:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="There is no transcript to summarise yet.",
        )
    refusal = await insights.consent_refusal(media)
    if refusal is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=refusal)
    active = await insights.active_job(media_id)
    row = await insights.current_insight(media_id)
    edited = row is not None and row.editedAt is not None
    # An active automatic job skips an edited summary, so on an edited one it is no answer to this
    # ask: the job is claimed for the caller instead. Any other active job already is the answer.
    if active is not None and not (edited and getattr(active, "requestedById", None) is None):
        return await _view(media, current_user)
    if edited and not payload.discardEdits:
        return JSONResponse(
            status_code=status.HTTP_409_CONFLICT,
            content={"detail": "Regenerating replaces your edits.", "code": "EDITS_WOULD_BE_REPLACED"},
        )
    # A claimed job runs on the caller's key, as a queued Regenerate would.
    if active is None or not await insights.claim_for_requester(active.id, current_user.id):
        await insights.enqueue(media_id, requested_by_id=current_user.id)
    return await _view(media, current_user)
