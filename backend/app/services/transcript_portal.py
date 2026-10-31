"""documentation-portal's half of the transcript pipeline: what only this portal knows.

NOT SHARED. designer-portal has a file of the same name and the same functions, written over its
own access rules and consent gate. The shared pipeline (``services/transcript_pipeline.py``) and
routes (``api/routes/transcripts.py``) call only the functions below.

* reading is open to every signed-in account, as transcripts are in this repository;
* editing follows ``POST /media/{id}/transcript``: the uploader or a content admin;
* consent is the one consent store (``recording_consent.gate``), re-read immediately before every
  send; a store that cannot be read refuses.
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.roles import is_admin as _is_admin
from app.core.roles import is_content_admin
from app.services import recording_consent

logger = logging.getLogger(__name__)

PORTAL = "documentation-portal"


def is_admin(user: Any) -> bool:
    return bool(_is_admin(user))


def may_edit(user: Any, media: Any) -> bool:
    return bool(
        is_content_admin(user) or getattr(media, "uploadedById", None) == getattr(user, "id", None)
    )


async def readable_ids(rows: list[Any], viewer: Any) -> set[str]:
    return {str(getattr(row, "id", "")) for row in rows if getattr(row, "id", None)} if viewer else set()


async def guard_write(user: Any, media: Any) -> None:
    if not may_edit(user, media):
        from fastapi import HTTPException, status  # here: the queue worker never loads FastAPI

        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": "FORBIDDEN", "message": "Only the uploader or an admin can change this transcript."},
        )


async def may_send(media: Any) -> tuple[bool, str | None]:
    """Whether this recording's words may go to Gemini now. A failed read refuses."""
    try:
        verdict = await recording_consent.gate.verdict(media)
    except Exception as exc:  # noqa: BLE001 - an unreadable consent store sends nothing
        logger.warning("consent unreadable for media %s (%s)", getattr(media, "id", None), type(exc).__name__)
        return False, "The consent record could not be read, so nothing was sent."
    return bool(verdict.may_send), verdict.refusal


async def extra_context(media: Any, context: dict[str, Any]) -> None:
    """Nothing beyond the shared tables in this portal."""
    return


def entity_for(media: Any, context: dict[str, Any]) -> str:
    if context.get("recordingId") or context.get("interviewId"):
        return "Interview"
    if getattr(media, "artisanId", None):
        return "Artisan"
    if getattr(media, "workshopId", None):
        return "Workshop"
    return "Interview"


def kind_label(media: Any, context: dict[str, Any]) -> str:
    return "Interview" if (context.get("artisans") or context.get("interviewId")) else "Recording"


def audio_url(media: Any) -> str | None:
    try:
        from app.services.s3 import presign_get_url

        return presign_get_url(
            media.objectKey,
            filename=media.originalFilename or "recording",
            mime_type=media.mimeType or "audio/mpeg",
        )
    except Exception as exc:  # noqa: BLE001 - the transcript reads without playback
        logger.warning("could not sign the audio of media %s (%s)", getattr(media, "id", None), type(exc).__name__)
        return None


async def requeue_transcription(media: Any) -> None:
    """Retry for a recording whose transcription never finished: back on the queue."""
    from app.services import media_queue

    await media_queue.enqueue_media_processing_jobs(
        media, ["TRANSCRIPTION"], getattr(media, "uploadedById", None) or ""
    )


async def transcribe_now(media: Any) -> dict[str, Any]:
    """The admin "Transcribe now", with this portal's refusals as contract errors."""
    from app.services import media_queue, transcription_jobs
    from app.transcription.transcript.errors import PipelineRefused

    try:
        return await media_queue.transcribe_media_now(media)
    except recording_consent.ConsentRefused as exc:
        raise PipelineRefused("CONSENT_REFUSED", str(exc) or "Consent was refused.") from None
    except transcription_jobs.TranscriptionPaused as exc:
        raise PipelineRefused("TRANSCRIPTION_PAUSED", str(exc)) from None
