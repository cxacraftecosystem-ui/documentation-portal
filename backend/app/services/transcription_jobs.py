"""A queued recording through the shared transcription service: dedup first, then the chain.

IDENTICAL IN BOTH PORTALS. ``media_queue`` calls :func:`transcribe_recording` for every
TRANSCRIPTION job and for "Transcribe now"; everything that is the same in both portals lives here
so the two queues cannot drift on it:

* **The pause** (:func:`paused_result`): a held recording answers with a calm status, not an error.
* **Dedup**: the recording's SHA-256 (``MediaFile.checksum`` when it is already a SHA-256, else
  computed from the temp file and written back) plus the options hash. A cache hit copies the stored
  transcript at no cost and is recorded as a zero-cost usage row.
* **Throttles per job**: a provider that throttled this job ``THROTTLES_PER_JOB`` times is skipped
  for it, so the job moves to the next provider instead of spinning.
* **Language hint**: ``extraMetadata.languageHint`` on the recording, else its location's state.
* **What is written**: segments, detected language and provider beside the transcript.
"""

from __future__ import annotations

import logging
import re
from typing import Any

from app.core.db import db
from app.services import ai
from app.services.transcription_store import PrismaTranscriptionStore, cached_result
from app.transcription import dedup, languages, schedule, service
from app.transcription.chain import THROTTLES_PER_JOB, UsageEvent
from app.transcription.contract import AudioInput
from prisma import Json

logger = logging.getLogger(__name__)

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class TranscriptionPaused(Exception):
    """Raised by "Transcribe now" while the pause is set; routes answer 409 with this sentence."""

    def __init__(self) -> None:
        super().__init__(schedule.PAUSED_MESSAGE)


def paused_result() -> dict[str, Any]:
    """What an inline dictation answers while paused: UNAVAILABLE, which both clients already
    render as a calm message (and then fall back to on-device speech where they have it)."""
    return {
        "available": False,
        "status": "UNAVAILABLE",
        "paused": True,
        "text": None,
        "formattedTranscript": None,
        "message": schedule.PAUSED_MESSAGE,
    }


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
    """Count one throttle against *provider* on this job, in ``MediaProcessingJob.result``."""
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


async def language_hint(media: Any) -> list[str]:
    """The recording's own hint first, else the language(s) its location's state suggests."""
    metadata = _value(media, "extraMetadata")
    if isinstance(metadata, dict):
        hint = metadata.get("languageHint")
        if isinstance(hint, str) and hint.strip():
            return [hint.strip()]
        if isinstance(hint, list):
            return [str(item).strip() for item in hint if str(item).strip()]
    location_id = _value(media, "locationId")
    if location_id:
        try:
            location = await db.location.find_unique(where={"id": location_id})
        except Exception:  # noqa: BLE001 - a hint is never worth failing a job over
            location = None
        if location is not None:
            return languages.bias_for_state(getattr(location, "state", None))
    return []


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
    """Transcribe one stored recording. Never called while paused (callers check first).

    ``transcribe`` is the portal's ``ai.transcribe_audio_bytes`` as the caller holds it, so a test
    that replaces the queue's reference still intercepts the call.
    """
    transcribe = transcribe or ai.transcribe_audio_bytes
    media_id = str(_value(media, "id"))
    filename = _value(media, "originalFilename") or "recording.webm"
    mime_type = _value(media, "mimeType") or "audio/webm"
    bias = await language_hint(media)
    store = PrismaTranscriptionStore()
    audio = AudioInput(None if source_path else content, source_path, filename, mime_type)
    sha = await _sha256(media, audio)
    options_hash = dedup.options_hash(ai.transcription_options(settings, language_bias=bias))
    if sha:
        try:
            hit = await store.cache_get(sha, options_hash)
        except Exception:  # noqa: BLE001 - no cache is the old behaviour
            hit = None
        if hit is not None:
            result = service.result_dict(cached_result(hit))
            result["cacheHit"] = True
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
    # THE TRANSCRIPT PIPELINE (31 Oct 2026): this recording's own key terms (its artisans, its craft,
    # the glossary heard for that craft, then the master list), at most 100, most specific first.
    from app.services import transcript_pipeline

    extra: dict[str, Any] = {}
    terms: list[str] = []
    if transcript_pipeline.accepts_keyterms(transcribe):
        terms = await transcript_pipeline.keyterms_for(media)
        if terms:
            extra["keyterms"] = terms
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
    """The MediaFile columns the service adds beside ``transcriptText``, for a run that produced text."""
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
