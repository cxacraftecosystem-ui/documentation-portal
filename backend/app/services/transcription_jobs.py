from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import re
from typing import Any

from app.core.db import db
from app.services import ai
from app.services.transcription_store import PrismaTranscriptionStore, cached_result
from app.transcription import (
    audio as transcription_audio,
    dedup,
    languages,
    schedule,
    service,
    workdir,
)
from app.transcription.chain import THROTTLES_PER_JOB, UsageEvent
from app.transcription.contract import AudioInput
from prisma import Json

logger = logging.getLogger(__name__)

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class TranscriptionPaused(Exception):
    def __init__(self) -> None:
        super().__init__(schedule.PAUSED_MESSAGE)


def paused_result() -> dict[str, Any]:
    return {
        "available": False,
        "status": "UNAVAILABLE",
        "paused": True,
        "text": None,
        "formattedTranscript": None,
        "message": schedule.PAUSED_MESSAGE,
    }


NO_SOUND_MESSAGE = "This video has no sound that could be read. Nothing was spent."


def no_sound_result() -> dict[str, Any]:
    return {
        "available": False,
        "status": "UNAVAILABLE",
        "text": None,
        "formattedTranscript": None,
        "message": NO_SOUND_MESSAGE,
    }


def video_sound_name(filename: str) -> str:
    stem = os.path.splitext(os.path.basename(filename or ""))[0] or "recording"
    return f"{stem}{transcription_audio.VIDEO_AUDIO_SUFFIX}"


async def _video_sound(source_path: str | None, content: bytes | None) -> str | None:
    spill: str | None = None
    if not source_path:
        if not content:
            return None
        with workdir.named_file(prefix="transcription_source_") as handle:
            handle.write(content)
            spill = handle.name
        source_path = spill
    try:
        return await asyncio.to_thread(transcription_audio.extract_to_workdir, source_path)
    finally:
        if spill is not None:
            with contextlib.suppress(OSError):
                os.remove(spill)


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


def throttle_counts(job: Any) -> dict[str, int]:
    result = _value(job, "result")
    counts = result.get("throttles") if isinstance(result, dict) else None
    return {str(k): int(v) for k, v in (counts or {}).items() if isinstance(v, int | float)}


def skipped_providers(job: Any) -> set[str]:
    return {provider for provider, n in throttle_counts(job).items() if n >= THROTTLES_PER_JOB}


async def note_throttle(job: Any, provider: str | None) -> None:
    if not provider or job is None:
        return
    counts = throttle_counts(job)
    counts[provider] = counts.get(provider, 0) + 1
    result = _value(job, "result")
    merged = {**(result if isinstance(result, dict) else {}), "throttles": counts}
    try:
        await db.mediaprocessingjob.update(where={"id": job.id}, data={"result": Json(merged)})
    except Exception as exc:  # noqa: BLE001 - a lost count only costs one extra attempt
        logger.warning("could not count a throttle on job %s (%s)", job.id, type(exc).__name__)


async def spoken_language(media: Any) -> str:
    chosen = languages.from_metadata(_value(media, "extraMetadata"))
    if chosen is not None:
        return chosen
    media_id = _value(media, "id")
    if media_id:
        try:
            rows = await db.query_raw(
                'SELECT "languageCode" FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1 LIMIT 1',
                str(media_id),
            )
        except Exception:  # noqa: BLE001
            rows = []
        if rows:
            recorded = languages.choice_of(rows[0].get("languageCode"))
            if recorded is not None:
                return recorded
    return languages.AUTO


def _accepts(transcribe: Any, name: str) -> bool:
    import inspect

    try:
        parameters = inspect.signature(transcribe).parameters
    except TypeError, ValueError:
        return False
    return name in parameters or any(p.kind is inspect.Parameter.VAR_KEYWORD for p in parameters.values())


async def _sha256(media: Any, audio: AudioInput) -> str | None:
    checksum = str(_value(media, "checksum") or "").strip().lower()
    if _SHA256.match(checksum):
        return checksum
    try:
        digest = dedup.sha256_of(audio)
    except OSError:
        return None
    try:
        await db.mediafile.update(where={"id": _value(media, "id")}, data={"checksum": digest})
    except Exception as exc:  # noqa: BLE001 - the digest is still usable for this call
        logger.warning(
            "could not store the checksum of media %s (%s)", _value(media, "id"), type(exc).__name__
        )
    return digest


async def transcribe_recording(
    media: Any,
    settings: Any,
    *,
    source_path: str | None,
    content: bytes | None = None,
    user_id: str | None = None,
    job: Any = None,
    transcribe: Any = None,
) -> dict[str, Any]:
    transcribe = transcribe or ai.transcribe_audio_bytes
    media_id = str(_value(media, "id"))
    filename = _value(media, "originalFilename") or "recording.webm"
    mime_type = _value(media, "mimeType") or "audio/webm"
    spoken = await spoken_language(media)
    bias = languages.bias(spoken)
    store = PrismaTranscriptionStore()
    audio = AudioInput(None if source_path else content, source_path, filename, mime_type)
    sha = await _sha256(media, audio)
    options_hash = dedup.options_hash(
        ai.transcription_options(settings, language_bias=bias, spoken_language=spoken)
    )
    if sha:
        try:
            hit = await store.cache_get(sha, options_hash)
        except Exception:  # noqa: BLE001 - no cache is the old behaviour
            hit = None
        if hit is not None:
            result = service.result_dict(cached_result(hit))
            result["cacheHit"] = True
            result["spokenLanguage"] = spoken
            from app.services import transcript_pipeline

            await transcript_pipeline.store_transcription(media, result)
            try:
                await store.record_usage(
                    UsageEvent(hit.provider, hit.model, None, None, 0, None, result["status"], 0.0),
                    media_id=media_id,
                    cache_hit=True,
                    job_id=_value(job, "id"),
                )
            except Exception as exc:  # noqa: BLE001 - a lost ledger row never costs the transcript
                logger.warning(
                    "could not record a cache hit for media %s (%s)", media_id, type(exc).__name__
                )
            return result
    from app.services import transcript_pipeline

    extra: dict[str, Any] = {}
    if _accepts(transcribe, "spoken_language"):
        extra["spoken_language"] = spoken
    terms: list[str] = []
    if transcript_pipeline.accepts_keyterms(transcribe):
        terms = await transcript_pipeline.keyterms_for(media)
        if terms:
            extra["keyterms"] = terms
    extracted: str | None = None
    if transcription_audio.is_video(_value(media, "mediaType"), mime_type):
        extracted = await _video_sound(source_path, content)
        if extracted is None:
            return no_sound_result()
        source_path, content = extracted, None
        filename = video_sound_name(filename)
        mime_type = transcription_audio.VIDEO_AUDIO_MIME
    try:
        result = await transcribe(
            content if not source_path else None,
            filename,
            mime_type,
            settings,
            source_path=source_path,
            user_id=user_id,
            media_id=media_id,
            job_id=_value(job, "id"),
            language_bias=bias,
            skip_providers=skipped_providers(job) if job is not None else None,
            **extra,
        )
    finally:
        if extracted is not None:
            with contextlib.suppress(OSError):
                os.remove(extracted)
    if isinstance(result, dict):
        result["spokenLanguage"] = spoken
    if result.get("status") == "COMPLETED":
        await transcript_pipeline.store_transcription(media, result, keyterm_count=len(terms))
        await transcript_pipeline.check_budget()
    if result.get("status") == "RATE_LIMITED" and job is not None:
        await note_throttle(job, result.get("provider"))
    if sha and result.get("status") == "COMPLETED" and result.get("provider"):
        try:
            from app.transcription.contract import Segment, SttResult

            await store.cache_put(
                sha,
                options_hash,
                SttResult(
                    "COMPLETED",
                    result.get("text") or "",
                    str(result.get("provider")),
                    str(result.get("model") or ""),
                    segments=[
                        Segment(
                            int(s.get("startMs") or 0),
                            int(s.get("endMs") or 0),
                            s.get("speaker"),
                            str(s.get("text") or ""),
                        )
                        for s in result.get("segments") or []
                    ],
                    language=result.get("languageCode"),
                    speakers=int(result.get("speakers") or 0),
                ),
            )
        except Exception as exc:  # noqa: BLE001 - the transcript is already in hand
            logger.warning(
                "could not cache the transcript of media %s (%s)", media_id, type(exc).__name__
            )
    return result


def transcript_extras(result: dict[str, Any]) -> dict[str, Any]:
    if result.get("status") not in {"COMPLETED", "EMPTY"}:
        return {}
    extras: dict[str, Any] = {}
    segments = result.get("segments")
    if segments:
        extras["transcriptSegments"] = Json(segments)
    if result.get("languageCode"):
        extras["transcriptLanguage"] = str(result["languageCode"])
    if result.get("provider"):
        extras["transcriptProvider"] = (
            "cache" if result.get("cacheHit") else str(result["provider"])
        )
    return extras
