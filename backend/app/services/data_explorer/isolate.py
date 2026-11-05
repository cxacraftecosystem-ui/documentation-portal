from __future__ import annotations

from typing import Any

from fastapi import HTTPException, status
from fastapi.responses import RedirectResponse

from app.core.db import db
from app.services.data_explorer import downloads, signing

OPEN_STATES = ("QUEUED", "PROCESSING")
NOT_ALLOWED = "Clear voices is not available for this recording."


def isolated(media: Any) -> bool:
    return bool(getattr(media, "cleanedObjectKey", None))


def _audio(media: Any) -> bool:
    return downloads.kind_of(media) == "AUDIO" and bool(getattr(media, "objectKey", None))


async def _switched_on() -> bool:
    from app.services import audio_isolation, managed_secrets

    if not await audio_isolation.enabled():
        return False
    return bool(managed_secrets.peek_secret(audio_isolation.KEY_NAME))


async def refusal(user: Any, media: Any) -> str | None:
    from app.services import transcript_portal

    if not _audio(media) or isolated(media) or getattr(media, "removedAt", None):
        return NOT_ALLOWED
    if not transcript_portal.may_edit(user, media):
        return "Only the person who uploaded this recording, or an admin, can clear its voices."
    if not await _switched_on():
        return NOT_ALLOWED
    allowed, reason = await transcript_portal.may_send_audio(media)
    if not allowed:
        return reason or NOT_ALLOWED
    return None


async def can_isolate(user: Any, media: Any) -> bool:
    try:
        return await refusal(user, media) is None
    except Exception:  # noqa: BLE001
        return False


async def _open_job(media_id: str) -> Any:
    from app.services import audio_isolation

    return await db.mediaprocessingjob.find_first(
        where={"mediaFileId": media_id, "jobType": audio_isolation.JOB_TYPE, "status": {"in": list(OPEN_STATES)}}
    )


async def request(user: Any, media: Any) -> dict[str, Any]:
    from app.services import audio_isolation

    if isolated(media):
        return {"status": "ready", "isolated": True}
    reason = await refusal(user, media)
    if reason is not None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=reason)
    running = await _open_job(str(media.id))
    if running is None:
        await audio_isolation.enqueue_if_enabled(str(media.id))
        running = await _open_job(str(media.id))
    if running is None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=NOT_ALLOWED)
    state = str(getattr(running, "status", "") or "").split(".")[-1].upper()
    return {"status": "running" if state == "PROCESSING" else "queued", "isolated": False}


def isolated_redirect(media: Any) -> RedirectResponse:
    key = getattr(media, "cleanedObjectKey", None)
    if not key:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No isolated copy")
    mime = downloads.base_mime(getattr(media, "cleanedMimeType", None)) or "audio/mpeg"
    name = downloads.display_filename(media, fallback=str(media.id))
    url, max_age = signing.signed_get(str(key), filename=name, mime_type=mime, inline=True)
    return RedirectResponse(
        url,
        status_code=status.HTTP_307_TEMPORARY_REDIRECT,
        headers=signing.redirect_headers(max_age),
    )
