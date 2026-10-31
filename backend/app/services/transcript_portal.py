"""documentation-portal's half of the transcript pipeline: what only this portal knows.

NOT SHARED. designer-portal has a file of the same name and the same functions, written over its
own access rules and consent gate. The shared pipeline (``services/transcript_pipeline.py``) and
routes (``api/routes/transcripts.py``) call only the functions below.

* reading is open to every signed-in account, as transcripts are in this repository;
* editing follows ``POST /media/{id}/transcript``: the uploader or a content admin; the view says
  so up front (``readOnly``) by the same rule the writes refuse by;
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


def may_see_spend(user: Any) -> bool:
    """Cost and the model names: ADMIN, MASTER_ADMIN and MINISTRY_ADMIN only (0.0.5)."""
    return bool(is_content_admin(user))


def may_edit(user: Any, media: Any) -> bool:
    return bool(
        is_content_admin(user) or getattr(media, "uploadedById", None) == getattr(user, "id", None)
    )


async def readable_ids(rows: list[Any], viewer: Any) -> set[str]:
    return {str(getattr(row, "id", "")) for row in rows if getattr(row, "id", None)} if viewer else set()


#: Why a reader may not change this transcript, by ``readOnlyReason`` code (shared contract). The
#: words are the 403 every transcript write already answers with. This portal has no workshop
#: posts, so ``HELD_POST`` never arises here.
READ_ONLY_MESSAGES: dict[str, str] = {
    "NOT_EDITOR": "Only the uploader or an admin can change this transcript.",
}


async def _write_refusal(user: Any, media: Any) -> tuple[str, Any] | None:
    """``(code, the 403's detail)`` when ``user`` may not change this transcript; None when they may.

    THE ONE RULE. :func:`guard_write` refuses every transcript write by it and :func:`read_only`
    tells the view by it, so the controls a client draws can never disagree with the server.
    """
    if not may_edit(user, media):
        return "NOT_EDITOR", {"code": "FORBIDDEN", "message": READ_ONLY_MESSAGES["NOT_EDITOR"]}
    return None


async def read_only(user: Any, media: Any) -> dict[str, str] | None:
    """``{code, message}`` when ``user`` may read this transcript but not change it; None when they may."""
    refusal = await _write_refusal(user, media)
    return None if refusal is None else {"code": refusal[0], "message": READ_ONLY_MESSAGES[refusal[0]]}


async def guard_write(user: Any, media: Any) -> None:
    """Raises 403 when ``user`` may not change this recording's transcript."""
    refusal = await _write_refusal(user, media)
    if refusal is not None:
        from fastapi import HTTPException, status  # here: the queue worker never loads FastAPI

        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=refusal[1])


async def may_send(media: Any) -> tuple[bool, str | None]:
    """Whether this recording's words may go to Gemini now. A failed read refuses."""
    try:
        verdict = await recording_consent.gate.verdict(media)
    except Exception as exc:  # noqa: BLE001 - an unreadable consent store sends nothing
        logger.warning("consent unreadable for media %s (%s)", getattr(media, "id", None), type(exc).__name__)
        return False, "The consent record could not be read, so nothing was sent."
    return bool(verdict.may_send), verdict.refusal


async def may_send_audio(media: Any) -> tuple[bool, str | None]:
    """Whether the RECORDING itself may go to ElevenLabs now (Voice Isolator). A failed read refuses."""
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


def audio_url(media: Any, *, original: bool = False) -> str | None:
    """The player's signed URL: the Voice Isolator copy when there is one (0.0.5), else the recording
    as made. ``original=True`` is always the recording as made."""
    cleaned = None if original else getattr(media, "cleanedObjectKey", None)
    try:
        from app.services.s3 import presign_get_url

        return presign_get_url(
            cleaned or media.objectKey,
            filename=media.originalFilename or "recording",
            mime_type=(getattr(media, "cleanedMimeType", None) if cleaned else None) or media.mimeType or "audio/mpeg",
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
