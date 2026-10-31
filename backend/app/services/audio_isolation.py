"""Voice Isolator on transcribed recordings: the queue job, the switch and the stored copy (0.0.5).

IDENTICAL IN BOTH PORTALS (``shared/transcription.sha256``). What only one portal knows (whose
consent to read) comes from ``services/transcript_portal.py``.

OFF BY DEFAULT. ``AppSetting.audioIsolationEnabled`` is set from Settings → API keys by an
administrator. While it is on, every recording that finishes transcribing gets one AUDIO_ISOLATION
job: the original is sent to ElevenLabs Voice Isolator (consent re-read immediately before the send),
and the cleaned audio is stored under the SAME FILE NAME in a key of its own, beside the original.
``MediaFile.objectKey`` is never changed, so the recording as made stays exactly as it was (and its
checksum stays true); players use the cleaned copy and offer the original.

A busy ElevenLabs, no route, or credit run out parks the job and asks again later without spending
an attempt; a key without the Voice Isolator permission closes the job with that sentence.
"""

from __future__ import annotations

import asyncio
import logging
import os
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from app.core.config import get_settings
from app.core.db import db
from app.services import app_settings, managed_secrets, transcript_portal
from app.transcription import isolation, workdir

logger = logging.getLogger(__name__)

JOB_TYPE = "AUDIO_ISOLATION"
KEY_NAME = "ELEVENLABS_API_KEY"
#: After transcription and translation: cleaning never holds a transcript back.
PRIORITY = 95


async def enabled() -> bool:
    try:
        row = await app_settings.load_app_settings()
    except Exception as exc:  # noqa: BLE001 - unreadable settings mean off
        logger.warning("audio isolation: settings unreadable (%s)", type(exc).__name__)
        return False
    return bool(getattr(row, "audioIsolationEnabled", False))


async def set_enabled(value: bool, updated_by_id: str | None) -> bool:
    await app_settings.get_or_create_app_settings(updated_by_id)
    row = await db.appsetting.update(
        where={"id": app_settings.SINGLETON_ID},
        data={"audioIsolationEnabled": bool(value), "updatedById": updated_by_id},
    )
    return bool(getattr(row, "audioIsolationEnabled", False))


def _eligible(media: Any) -> bool:
    return (
        media is not None
        and str(getattr(media, "mediaType", "") or "").upper() == "AUDIO"
        and not getattr(media, "cleanedObjectKey", None)
        and not getattr(media, "removedAt", None)
        and bool(getattr(media, "objectKey", None))
    )


async def enqueue_if_enabled(media_id: str) -> Any | None:
    """One AUDIO_ISOLATION job for a recording that has just been transcribed; None when off."""
    try:
        if not await enabled():
            return None
        media = await db.mediafile.find_unique(where={"id": media_id})
        if not _eligible(media):
            return None
        open_job = await db.mediaprocessingjob.find_first(
            where={"mediaFileId": media_id, "jobType": JOB_TYPE, "status": {"in": ["QUEUED", "PROCESSING"]}}
        )
        if open_job is not None:
            return None
        return await db.mediaprocessingjob.create(
            data={
                "jobType": JOB_TYPE,
                "status": "QUEUED",
                "priority": PRIORITY,
                "maxAttempts": max(get_settings().media_queue_job_max_attempts, 1),
                "mediaFileId": media_id,
            }
        )
    except Exception as exc:  # noqa: BLE001 - never let the clean-up fail a transcription
        logger.warning("audio isolation: could not queue media %s (%s)", media_id, type(exc).__name__)
        return None


def cleaned_key(media: Any, mime_type: str) -> str:
    """``media/<uploader>/<uuid>/<the original's file name, the cleaned type's extension>``."""
    owner = str(getattr(media, "uploadedById", "") or "system")
    return f"media/{owner}/{uuid4().hex}/{isolation.cleaned_leaf(str(media.objectKey), mime_type)}"


async def process(media: Any) -> dict[str, Any]:
    """Clean one recording. Raises :class:`isolation.IsolationError` for the queue to hold or fail."""
    from app.services import s3

    if not await enabled():
        return {"status": "SKIPPED", "reason": "off"}
    fresh = await db.mediafile.find_unique(where={"id": media.id})
    if not _eligible(fresh):
        return {"status": "SKIPPED", "reason": "not-eligible"}
    may, refusal = await transcript_portal.may_send_audio(fresh)
    if not may:
        return {"status": "REFUSED", "reason": refusal or "consent"}
    key = await managed_secrets.get_secret(KEY_NAME)
    if not key:
        return {"status": "SKIPPED", "reason": "no-key"}
    leaf = str(fresh.objectKey).rsplit("/", 1)[-1]
    suffix = "." + leaf.rsplit(".", 1)[-1] if "." in leaf else ""
    path = await workdir.owned(s3.download_to_temp, fresh.objectKey, suffix=suffix)
    try:
        result = await asyncio.to_thread(
            isolation.isolate, path, fresh.originalFilename or leaf, fresh.mimeType or "audio/mpeg", key=key
        )
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
    new_key = cleaned_key(fresh, result.mime_type)
    await asyncio.to_thread(s3.put_object_bytes, new_key, result.content, mime_type=result.mime_type)
    await db.mediafile.update(
        where={"id": fresh.id},
        data={"cleanedObjectKey": new_key, "cleanedMimeType": result.mime_type, "cleanedAt": datetime.now(UTC)},
    )
    logger.info("audio isolation: media %s cleaned (%d bytes)", fresh.id, len(result.content))
    return {"status": "COMPLETED", "bytes": len(result.content)}
