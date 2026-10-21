import asyncio
import logging
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from app.core.fastapi_lazy import jsonable_encoder
from prisma.errors import UniqueViolationError

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services import recording_consent, transcription_jobs
from app.services.ai import (
    analyze_measurement_image_bytes,
    redact_secrets,
    refine_transcript_text,
    transcribe_audio_bytes,
)
from app.services.app_settings import (
    load_app_settings,
    transcription_mode,
    transcription_paused,
    within_processing_window,
)
from app.services import transcript_insights
from app.services import transcription_enqueue as transcription
from app.services.s3 import discard_temp, download_to_temp, get_object_bytes
from app.services.transcription_store import open_key
from app.transcription import schedule
from prisma import Json

TRANSCRIPTION = "TRANSCRIPTION"
MEASUREMENT = "MEASUREMENT"
SHA_VERIFY = "SHA_VERIFY"
#: Summary, key quotes and translation of a transcript (services/transcript_insights). Text only:
#: it never loads the audio, and it is not held back by the processing window or the transcription
#: rate-limit cooldown. Drained only while its AI capability flag is on.
TRANSCRIPT_INSIGHTS = "TRANSCRIPT_INSIGHTS"
QUEUED = "QUEUED"
PROCESSING = "PROCESSING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
UNAVAILABLE = "UNAVAILABLE"
RATE_LIMITED = "RATE_LIMITED"

STALE_PROCESSING_AFTER = timedelta(minutes=30)

# Rate-limit backoff. When transcription is throttled (HTTP 429/503), we pause transcription for a
# growing cooldown and requeue the job WITHOUT consuming an attempt — so every clip is transcribed
# eventually instead of burning out its retries. Measurement jobs keep flowing through the cooldown.
RATE_LIMIT_BASE_SECONDS = 30
RATE_LIMIT_MAX_SECONDS = 900

# Single elected worker (see main.py), so module-level cooldown state is safe.
_rate_limit_cooldown_until: datetime | None = None
_consecutive_rate_limits = 0


logger = logging.getLogger(__name__)

#: What the job panel says about a failure nobody anticipated. The exception itself goes to the log.
UNEXPECTED_JOB_FAILURE = "This file couldn't be processed. Try again."


class JobFailure(RuntimeError):
    """A failure whose message is already a sentence for the person reading the job panel: a
    provider's own refusal, or an unsupported job type. Anything else is logged and stored as
    :data:`UNEXPECTED_JOB_FAILURE`, so developer text never reaches a screen."""


class RateLimited(Exception):
    """Raised when a transcription call is throttled. Carries an optional provider Retry-After (s)."""

    def __init__(self, retry_after: float | None = None) -> None:
        super().__init__("Transcription rate-limited")
        self.retry_after = retry_after




def _transcription_in_cooldown(now: datetime | None = None) -> bool:
    return _rate_limit_cooldown_until is not None and (now or datetime.now(UTC)) < _rate_limit_cooldown_until


def _enter_rate_limit_cooldown(retry_after: float | None) -> None:
    global _rate_limit_cooldown_until, _consecutive_rate_limits
    _consecutive_rate_limits += 1
    if retry_after and retry_after > 0:
        delay = max(retry_after, RATE_LIMIT_BASE_SECONDS)
    else:
        delay = min(RATE_LIMIT_BASE_SECONDS * (2 ** (_consecutive_rate_limits - 1)), RATE_LIMIT_MAX_SECONDS)
    _rate_limit_cooldown_until = datetime.now(UTC) + timedelta(seconds=delay)


def _clear_rate_limit_cooldown() -> None:
    global _rate_limit_cooldown_until, _consecutive_rate_limits
    _rate_limit_cooldown_until = None
    _consecutive_rate_limits = 0


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


def _job_requests(media: Any, requested: list[str] | None) -> set[str]:
    media_type = str(_value(media, "mediaType") or "").upper()
    normalized = {str(item).strip().upper() for item in requested or [] if str(item).strip()}
    if requested is None and media_type == "AUDIO":
        normalized.add(TRANSCRIPTION)
    if media_type != "AUDIO":
        normalized.discard(TRANSCRIPTION)
    if media_type != "IMAGE":
        normalized.discard(MEASUREMENT)
    return normalized & {TRANSCRIPTION, MEASUREMENT}


def _target_data(media: Any) -> dict[str, str]:
    data: dict[str, str] = {}
    product_id = _value(media, "productId")
    tool_id = _value(media, "toolId")
    record_type = str(_value(media, "linkedRecordType") or "").lower()
    linked_record_id = _value(media, "linkedRecordId")
    if record_type == "product" and linked_record_id:
        product_id = linked_record_id
    if record_type == "tool" and linked_record_id:
        tool_id = linked_record_id
    if product_id:
        data["productId"] = str(product_id)
    if tool_id:
        data["toolId"] = str(tool_id)
    return data


async def enqueue_media_processing_jobs(
    media: Any,
    processing_requests: list[str] | None,
    requested_by_id: str,
    settings: Settings | None = None,
) -> list[Any]:
    settings = settings or get_settings()
    requests = _job_requests(media, processing_requests)
    if TRANSCRIPTION in requests:
        # THE CONSENT RULE (18 Oct 2026): without a recorded GRANTED answer the recording is kept and
        # simply not sent. The clip says why, so nobody waits for a transcript that is not coming.
        verdict = await recording_consent.gate.verdict(media)
        if not verdict.may_send:
            requests.discard(TRANSCRIPTION)
            await _record_consent_refusal(media, verdict)
    if not requests:
        return []

    media_id = str(_value(media, "id"))
    target_data = _target_data(media)
    created_jobs: list[Any] = []
    for request in sorted(requests):
        # AT MOST ONE OPEN JOB PER CLIP AND TYPE, so re-uploading or re-saving a stage never pays
        # twice. `openKey` is the database's guarantee; this read covers rows from before it existed.
        existing = await db.mediaprocessingjob.find_first(
            where={"mediaFileId": media_id, "jobType": request, "status": {"in": [QUEUED, PROCESSING]}}
        )
        if existing is not None:
            created_jobs.append(existing)
            continue
        try:
            job = await _create_open_job(
                {
                "jobType": request,
                "status": QUEUED,
                "priority": 50 if request == TRANSCRIPTION else 70,
                "maxAttempts": max(settings.media_queue_job_max_attempts, 1),
                "mediaFileId": media_id,
                "requestedById": requested_by_id,
                **target_data,
                }
            )
        except UniqueViolationError:
            raced = await db.mediaprocessingjob.find_first(
                where={"openKey": open_key(media_id, request)}
            )
            if raced is not None:
                created_jobs.append(raced)
            continue
        created_jobs.append(job)

    if TRANSCRIPTION in requests:
        await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": QUEUED})

    if MEASUREMENT in requests:
        await _mark_measurement_queued(media_id, target_data)

    return created_jobs


async def _create_open_job(data: dict[str, Any]) -> Any:
    """Create a QUEUED job carrying its ``openKey`` (cleared again when the job finishes)."""
    return await db.mediaprocessingjob.create(
        data={**data, "openKey": open_key(str(data["mediaFileId"]), str(data["jobType"]))}
    )


async def _fail_for_consent(job: Any, refusal: str | None) -> None:
    """Close a job consent does not permit: FAILED, no retry, nothing sent, the reason on the job."""
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": FAILED,
            "lockedAt": None,
            "lockedBy": None,
            "openKey": None,
            "completedAt": datetime.now(UTC),
            "error": str(refusal or "")[:2000],
        },
    )


async def _record_consent_refusal(media: Any, verdict: Any) -> None:
    """Write why a recording was not sent onto the clip. Never over a transcript that exists."""
    media_id = str(_value(media, "id") or "")
    if not media_id or str(_value(media, "transcriptText") or "").strip():
        return
    # A refusal is REFUSED_CONSENT, the status a later GRANTED answer looks for to send it again
    # (audit M7); FAILED is kept for a consent store that could not be read, which retries itself.
    held = "REFUSED_CONSENT" if getattr(verdict, "decision", None) == "REFUSED" else FAILED
    try:
        await db.mediafile.update(
            where={"id": media_id},
            data={"transcriptStatus": held, "transcriptError": verdict.refusal},
        )
    except Exception as exc:  # noqa: BLE001 - the refusal held; this is only the note
        logger.warning("could not note the consent refusal on media %s (%s)", media_id, type(exc).__name__)


async def _mark_measurement_queued(media_id: str, target_data: dict[str, str]) -> None:
    data = {"measurementImageId": media_id, "measurementAnalysisStatus": QUEUED}
    if target_data.get("productId"):
        await db.productdocumentation.update(where={"id": target_data["productId"]}, data=data)
    if target_data.get("toolId"):
        await db.tooldocumentation.update(where={"id": target_data["toolId"]}, data=data)


async def process_next_media_jobs(
    limit: int | None = None,
    worker_id: str = "api-worker",
    settings: Settings | None = None,
) -> dict[str, int]:
    settings = settings or get_settings()
    batch_size = max(1, limit or settings.media_queue_batch_size)
    await recover_stale_processing_jobs()
    now = datetime.now(UTC)
    where: dict[str, Any] = {"status": QUEUED, "runAfter": {"lte": now}}
    # Lighter MEASUREMENT jobs always run; transcription only when the gate below allows it.
    app_settings = await load_app_settings()
    # THE PAUSE FIRST (owner hold, 18 Oct 2026): while AppSetting.transcriptionPaused is set no
    # transcription job is selected at all, so every one stays QUEUED and untouched. Then the
    # nightly window. The idle-load bypass is gone: a quiet box says nothing about provider credit.
    allow_transcription = (
        not transcription_paused(app_settings)
        and within_processing_window(app_settings)
        and not _transcription_in_cooldown(now)
    )
    held_back: list[str] = []
    if not allow_transcription:
        held_back.append(TRANSCRIPTION)
    if not transcript_insights.enabled():
        # Switched off: leave queued insights jobs where they are rather than failing them.
        held_back.append(TRANSCRIPT_INSIGHTS)
    if len(held_back) == 1:
        where["jobType"] = {"not": held_back[0]}
    elif held_back:
        where["jobType"] = {"not_in": held_back}
    jobs = await db.mediaprocessingjob.find_many(
        where=where,
        include={"mediaFile": True},
        order=[{"priority": "asc"}, {"createdAt": "asc"}],
        take=batch_size,
    )

    processed = 0
    succeeded = 0
    failed = 0
    for job in jobs:
        processed += 1
        try:
            locked = await _lock_job(job.id, worker_id)
            if locked is None:
                continue
            await _process_job(locked, settings)
            succeeded += 1
            if locked.jobType == TRANSCRIPTION:
                _clear_rate_limit_cooldown()  # a clean success ends any backoff
        except RateLimited as exc:
            # Throttled: requeue this job without consuming an attempt, then pause transcription for a
            # cooldown and stop draining the batch so we don't keep hammering a throttled provider.
            await _defer_rate_limited_job(job, exc.retry_after)
            _enter_rate_limit_cooldown(exc.retry_after)
            break
        except Exception as exc:  # noqa: BLE001
            failed += 1
            await _handle_job_failure(job.id, exc)
    return {"processed": processed, "succeeded": succeeded, "failed": failed}


async def _defer_rate_limited_job(job: Any, retry_after: float | None) -> None:
    """Requeue a throttled transcription job, restoring its pre-lock attempt count so rate limiting
    never exhausts its retries — it will be picked up again after the cooldown."""
    delay = retry_after if (retry_after and retry_after > 0) else RATE_LIMIT_BASE_SECONDS
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "attempts": job.attempts,
            "runAfter": datetime.now(UTC) + timedelta(seconds=delay),
            "error": "Rate-limited; awaiting cooldown before automatic retry.",
        },
    )


async def transcribe_media_now(media: Any, settings: Settings | None = None) -> dict[str, Any]:
    """Transcribe one audio media file immediately and store the result, applying the transcription
    mode configured in the settings page (RAW / REFINED / REFINED+TRANSLATED). This is the admin
    "Transcribe now" action: it bypasses the queue AND the off-peak window so the result is produced on
    the spot. Mirrors the worker's TRANSCRIPTION path so a manual run and a queued run agree. Never
    raises on an AI failure — it records the status/error on the media row and returns the result so the
    caller can surface it."""
    settings = settings or get_settings()
    verdict = await recording_consent.gate.verdict(media)
    if not verdict.may_send:
        raise recording_consent.ConsentRefused(verdict)
    # THE PAUSE HOLDS HERE TOO, after consent (the more useful sentence). "Transcribe now"
    # bypasses the queue and the window, never the pause.
    if transcription_paused(await load_app_settings()):
        raise transcription_jobs.TranscriptionPaused()
    media_id = str(_value(media, "id"))
    await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": PROCESSING})
    source_path = await asyncio.to_thread(download_to_temp, _value(media, "objectKey"))
    try:
        result = await transcription_jobs.transcribe_recording(
            media,
            settings,
            source_path=source_path,
            # The uploader: it is one researcher's own recording, so their own key is offered first.
            user_id=_value(media, "uploadedById"),
            transcribe=transcribe_audio_bytes,
        )
    finally:
        discard_temp(source_path)
    mode = transcription_mode(await load_app_settings())
    if result.get("status") == "COMPLETED" and mode in {"REFINED", "REFINED_TRANSLATED"} and result.get("text"):
        refined = await refine_transcript_text(result.get("text"), mode == "REFINED_TRANSLATED", settings)
        if refined.get("status") == "COMPLETED" and refined.get("refined"):
            result = {
                **result,
                "formattedTranscript": refined["refined"],
                "transcriptionMode": mode,
                "translated": mode == "REFINED_TRANSLATED",
            }
    status = str(result.get("status") or FAILED).upper()
    if status == RATE_LIMITED:
        # Throttled even on a manual run — keep it QUEUED so the background worker finishes it later.
        await db.mediafile.update(
            where={"id": media_id},
            data={"transcriptStatus": QUEUED, "transcriptError": result.get("message")},
        )
        # "Queued" must never be a dead end: if no queue job exists for this clip (e.g. it was
        # uploaded without a transcription request), create one so the worker actually picks it up.
        pending = await db.mediaprocessingjob.find_first(
            where={
                "mediaFileId": media_id,
                "jobType": TRANSCRIPTION,
                "status": {"in": [QUEUED, PROCESSING]},
            }
        )
        if pending is None:
            job_data: dict[str, Any] = {
                "jobType": TRANSCRIPTION,
                "status": QUEUED,
                "priority": 50,
                "maxAttempts": max(settings.media_queue_job_max_attempts, 1),
                "mediaFileId": media_id,
                **_target_data(media),
            }
            # requestedById is a real FK to User, so fall back to NULL (not a placeholder string)
            # when the uploader is unknown.
            uploader_id = _value(media, "uploadedById")
            if uploader_id:
                job_data["requestedById"] = str(uploader_id)
            try:
                await _create_open_job(job_data)
            except UniqueViolationError:
                pass  # another request opened the job a moment ago; one is enough
        return result
    write = _transcript_write(result, status)
    await db.mediafile.update(where={"id": media_id}, data=write)
    if status == COMPLETED and write.get("transcriptText"):
        await transcript_insights.enqueue_after_transcript(media_id)
    return result


def _transcript_write(result: dict[str, Any], status: str) -> dict[str, Any]:
    """The MediaFile columns to write for a finished transcription run.

    A FAILED or UNAVAILABLE run carries ``text: None`` (see services/ai.py), and writing that empty
    result verbatim NULLED OUT a transcript that was already stored: re-running transcription on a
    clip that already had a good transcript — the admin "Transcribe now" button, or a requeued job —
    destroyed it the moment the provider was down, and nothing else holds a copy. A run that produced
    no text therefore records only its status and its error; the previous transcript stays, and the
    failure is still visible in ``transcriptStatus``/``transcriptError``. Clearing a transcript stays
    a deliberate act: POST /media/{id}/transcript is the one path that overwrites stored text.
    """
    data: dict[str, Any] = {
        "transcriptStatus": status,
        "transcriptError": None if status in {COMPLETED, "EMPTY"} else result.get("message"),
    }
    transcript = result.get("formattedTranscript") or result.get("text")
    if transcript:
        data.update(transcription_jobs.transcript_extras(result))
        data["transcriptText"] = transcript
        data["transcriptSummary"] = result.get("text")
    return data


async def recover_stale_processing_jobs() -> int:
    cutoff = datetime.now(UTC) - STALE_PROCESSING_AFTER
    stale_jobs = await db.mediaprocessingjob.find_many(
        where={"status": PROCESSING, "lockedAt": {"lt": cutoff}},
        take=25,
    )
    for job in stale_jobs:
        await db.mediaprocessingjob.update(
            where={"id": job.id},
            data={
                "status": QUEUED,
                "lockedAt": None,
                "lockedBy": None,
                "runAfter": datetime.now(UTC),
                "error": "Recovered after worker interruption.",
            },
        )
    return len(stale_jobs)


async def _lock_job(job_id: str, worker_id: str) -> Any | None:
    # Read first for the attempts counter, then claim ATOMICALLY: the update only matches while the
    # row is still QUEUED, so of two concurrent claimers exactly one sees count==1 and the loser
    # backs off with None instead of double-processing the job.
    job = await db.mediaprocessingjob.find_unique(where={"id": job_id})
    if not job or job.status != QUEUED:
        return None
    now = datetime.now(UTC)
    claimed = await db.mediaprocessingjob.update_many(
        where={"id": job_id, "status": QUEUED},
        data={
            "status": PROCESSING,
            "lockedAt": now,
            "lockedBy": worker_id,
            "startedAt": now,
            "attempts": job.attempts + 1,
            "error": None,
        },
    )
    if claimed != 1:
        return None
    return await db.mediaprocessingjob.find_unique(where={"id": job_id}, include={"mediaFile": True})


async def _process_job(job: Any, settings: Settings) -> None:
    media = job.mediaFile
    if job.jobType == SHA_VERIFY:
        # Questionnaire v2: hash the stored object (streamed, never held whole) and apply the
        # identical-bytes rules. Calls no outside service.
        from app.services.questionnaire_v2 import recordings as v2_recordings

        result = await v2_recordings.verify_sha(media.id)
        await _complete_job(job.id, result)
        return
    # Insights are text only: branch BEFORE the S3 fetch so a summary never pulls the audio into
    # memory on a 1 GiB box.
    if job.jobType == TRANSCRIPT_INSIGHTS:
        await _process_insights_job(job, media)
        return
    if job.jobType == TRANSCRIPTION and await transcription.is_paused():
        # The pause can be set after this job was selected: hand it back untouched, no provider call.
        await db.mediaprocessingjob.update(
            where={"id": job.id},
            data={
                "status": QUEUED,
                "lockedAt": None,
                "lockedBy": None,
                "attempts": max(int(job.attempts or 0) - 1, 0),
            },
        )
        return
    if job.jobType == TRANSCRIPTION:
        # RE-READ AT THE MOMENT OF SENDING, so a consent withdrawn after the job was queued holds.
        # One question over the one consent store (services/recording_consent, services/consent_gate).
        verdict = await recording_consent.gate.verdict(media)
        if not verdict.may_send:
            await _record_consent_refusal(media, verdict)
            await _fail_for_consent(job, verdict.refusal)
            return
        # Streamed to a temp file, never read whole into the heap (the designer-portal shape).
        source_path = await asyncio.to_thread(download_to_temp, media.objectKey)
        try:
            result = await transcription_jobs.transcribe_recording(
                media,
                settings,
                source_path=source_path,
                # The person who ASKED for this job; the uploader for a sweep; else the organisation.
                user_id=getattr(job, "requestedById", None) or getattr(media, "uploadedById", None),
                job=job,
                transcribe=transcribe_audio_bytes,
            )
        finally:
            discard_temp(source_path)
        # Apply the configured transcription mode: RAW keeps the plain transcript; REFINED rewrites it
        # into a clean interviewer/interviewee dialogue; REFINED_TRANSLATED also translates to English.
        # The refined text is stored as the transcript (raw stays in transcriptSummary) and still lands
        # COMPLETED — i.e. awaiting human approval through the existing transcript-approval flow.
        mode = transcription_mode(await load_app_settings())
        if result.get("status") == "COMPLETED" and mode in {"REFINED", "REFINED_TRANSLATED"} and result.get("text"):
            refined = await refine_transcript_text(result.get("text"), mode == "REFINED_TRANSLATED", settings)
            if refined.get("status") == "COMPLETED" and refined.get("refined"):
                result = {
                    **result,
                    "formattedTranscript": refined["refined"],
                    "transcriptionMode": mode,
                    "translated": mode == "REFINED_TRANSLATED",
                }
        await _apply_transcription_result(job, result)
        return
    if job.jobType == MEASUREMENT:
        content = await asyncio.to_thread(get_object_bytes, media.objectKey)
        result = await analyze_measurement_image_bytes(
            content,
            media.originalFilename or "measurement.jpg",
            media.mimeType or "image/jpeg",
            settings,
        )
        await _apply_measurement_result(job, result)
        return
    raise JobFailure(f"Unsupported media processing job type: {job.jobType}")


async def _apply_transcription_result(job: Any, result: dict[str, Any]) -> None:
    status = str(result.get("status") or FAILED).upper()
    message = result.get("message")
    if status == RATE_LIMITED and result.get("reason") == "budget":
        # EVERY BUDGET IS USED UP: wait for the next window, no attempt spent, no ladder climbed.
        await _defer_for_budget(job, message)
        return
    if status == RATE_LIMITED:
        # Leave the clip QUEUED and let the queue back off + retry without consuming an attempt.
        await db.mediafile.update(
            where={"id": job.mediaFileId},
            data={"transcriptStatus": QUEUED, "transcriptError": message},
        )
        raise RateLimited(result.get("retryAfter"))
    await db.mediafile.update(
        where={"id": job.mediaFileId}, data=_transcript_write(result, status)
    )
    if status in {COMPLETED, "EMPTY"}:
        await _complete_job(job.id, result)
        if status == COMPLETED and (result.get("formattedTranscript") or result.get("text")):
            await transcript_insights.enqueue_after_transcript(job.mediaFileId)
    elif status == UNAVAILABLE:
        await _finalize_unavailable_job(job.id, result, message)
    else:
        raise JobFailure(message or "Transcription failed")


async def _process_insights_job(job: Any, media: Any) -> None:
    """Summary, quotes and translation for one transcript. The requester's own key when a person
    asked (Regenerate); the deployment's key for automatic runs and the backfill.

    A refused key, a declined request or a declined model gives the same answer every time, so it
    closes the job at once; a busy or unreachable provider retries.

    CONSENT IS RE-READ HERE, AT SEND TIME, as the TRANSCRIPTION branch does: a job queued under a
    grant does not leave after the consent was withdrawn. A refusal fails the job and sends nothing.
    """
    # Read fresh: a Regenerate can claim an automatic job after it was queued or picked up.
    requested_by = await transcript_insights.job_requester(job)
    if requested_by is None:
        # An automatic run never replaces a researcher's edits, even if they edited after it was
        # queued.
        current = await transcript_insights.current_insight(job.mediaFileId)
        if current is not None and current.editedAt is not None:
            await _complete_job(job.id, {"status": "SKIPPED", "reason": "edited"})
            return
    refusal = await transcript_insights.consent_refusal(media)
    if refusal is not None:
        await _fail_for_consent(job, refusal)
        return
    try:
        outcome = await transcript_insights.generate(media, user_id=requested_by)
    except transcript_insights.InsightsUnavailable as exc:
        await _finalize_unavailable_job(job.id, {"status": UNAVAILABLE}, str(exc))
        return
    except transcript_insights.InsightsFailed as exc:
        if exc.retry:
            raise JobFailure(str(exc)) from None
        await _finalize_unavailable_job(job.id, {"status": FAILED}, str(exc))
        return
    await _complete_job(job.id, outcome)


async def _defer_for_budget(job: Any, message: Any) -> None:
    now = datetime.now(UTC)
    opens = schedule.next_window_start(await load_app_settings(), now)
    until = opens if opens > now else now + timedelta(hours=1)
    await db.mediafile.update(
        where={"id": job.mediaFileId},
        data={"transcriptStatus": QUEUED, "transcriptError": message},
    )
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "attempts": max(int(job.attempts or 0) - 1, 0),
            "runAfter": until,
            "error": str(message or "The transcription budget is used up.")[:2000],
        },
    )


async def _apply_measurement_result(job: Any, result: dict[str, Any]) -> None:
    status = str(result.get("status") or FAILED).upper()
    message = result.get("message")
    analysis = result.get("analysis")
    metadata = _merge_measurement_metadata(job.mediaFile.extraMetadata, result)
    await db.mediafile.update(where={"id": job.mediaFileId}, data={"extraMetadata": Json(metadata)})

    if job.productId:
        await _apply_measurement_to_product(job.productId, job.mediaFileId, status, analysis)
    if job.toolId:
        await _apply_measurement_to_tool(job.toolId, job.mediaFileId, status, analysis)

    if status == COMPLETED:
        await _complete_job(job.id, result)
    elif status == UNAVAILABLE:
        await _finalize_unavailable_job(job.id, result, message)
    else:
        raise JobFailure(message or "Measurement analysis failed")


def _merge_measurement_metadata(existing: Any, result: dict[str, Any]) -> dict[str, Any]:
    if isinstance(existing, dict):
        metadata = dict(existing)
    else:
        metadata = {}
    metadata["measurementProcessing"] = jsonable_encoder(result)
    return metadata


async def _apply_measurement_to_product(
    product_id: str,
    media_id: str,
    status: str,
    analysis: dict[str, Any] | None,
) -> None:
    product = await db.productdocumentation.find_unique(where={"id": product_id})
    if not product:
        return
    data = _measurement_update_data(media_id, status, analysis, product)
    await db.productdocumentation.update(where={"id": product_id}, data=data)


async def _apply_measurement_to_tool(
    tool_id: str,
    media_id: str,
    status: str,
    analysis: dict[str, Any] | None,
) -> None:
    tool = await db.tooldocumentation.find_unique(where={"id": tool_id})
    if not tool:
        return
    data = _measurement_update_data(media_id, status, analysis, tool)
    await db.tooldocumentation.update(where={"id": tool_id}, data=data)


def _measurement_update_data(
    media_id: str,
    status: str,
    analysis: dict[str, Any] | None,
    record: Any,
) -> dict[str, Any]:
    """The columns a finished MEASUREMENT job writes onto the product or tool it was filed against.

    ⚠ THE TWO DIMENSION WRITES BELOW HAVE NO HUMAN IN THEM ANYWHERE, AND THAT IS AN OPEN DEFECT.
    ``lengthInches`` and ``breadthInches`` are documented dimensions: ``record_fields`` prints them
    in the "Dimensions (LxBxH in)" cell, which reaches the data browser, every .xlsx sheet and the
    CSV exports, where somebody costs a production run from them. This function fills them from a
    vision model's estimate whenever the column happens to be empty, and it does NOT go through
    ``records.merge_field_provenance`` — so the number lands carrying no provenance stamp at all,
    not even the false human one that ``services/measurement_provenance`` was written to end. A
    background worker read a photograph, nobody saw the answer, and a costed dimension changed.

    IT IS LEFT ALONE HERE ON PURPOSE. Closing it is a behaviour change to the media pipeline — stop
    writing the two columns, keep ``measurementAnalysis`` so a client can still show the reading and
    a person can accept it through the ordinary save path — and that belongs to whoever owns this
    queue, not to the provenance work. What that work DID close is the path with a person on it:
    ``POST /media/analyze-measurement`` now states its method, and a save that carries the marker
    stores it beside ``{by, byName, at}``.
    """
    data: dict[str, Any] = {
        "measurementImageId": media_id,
        "measurementAnalysisStatus": status,
    }
    if analysis:
        data["measurementAnalysis"] = Json(jsonable_encoder(analysis))
        length = _decimal_or_none(analysis.get("lengthInches"))
        breadth = _decimal_or_none(analysis.get("breadthInches"))
        if length is not None and _value(record, "lengthInches") is None:
            data["lengthInches"] = str(length)
        if breadth is not None and _value(record, "breadthInches") is None:
            data["breadthInches"] = str(breadth)
    return data


def _decimal_or_none(value: Any) -> Decimal | None:
    if value in (None, ""):
        return None
    try:
        return Decimal(str(value)).quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError):
        return None


async def _complete_job(job_id: str, result: dict[str, Any]) -> None:
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": COMPLETED,
            "lockedAt": None,
            "lockedBy": None,
            "openKey": None,
            "completedAt": datetime.now(UTC),
            "result": Json(jsonable_encoder(result)),
            "error": None,
        },
    )


async def _finalize_unavailable_job(job_id: str, result: dict[str, Any], message: Any) -> None:
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": FAILED,
            "lockedAt": None,
            "lockedBy": None,
            "openKey": None,
            "completedAt": datetime.now(UTC),
            "result": Json(jsonable_encoder(result)),
            "error": str(message or "This feature isn't available right now."),
        },
    )


async def _handle_job_failure(job_id: str, exc: Exception) -> None:
    job = await db.mediaprocessingjob.find_unique(where={"id": job_id})
    if not job:
        return
    now = datetime.now(UTC)
    # This column is served with the job, and the exception reaching it is ANY exception the job
    # raised — including one built by a library out of a URL that carried a credential. services/ai
    # keeps its own results clean; this is the guard for everything else that can fail in here.
    if isinstance(exc, JobFailure):
        error = redact_secrets(str(exc))[:2000]
    else:
        logger.error("media job %s failed unexpectedly: %s", job_id, redact_secrets(repr(exc)))
        error = UNEXPECTED_JOB_FAILURE
    exhausted = job.attempts >= job.maxAttempts
    retry_delay = timedelta(minutes=min(60, 2 ** max(job.attempts - 1, 0)))
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": FAILED if exhausted else QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "runAfter": now if exhausted else now + retry_delay,
            "completedAt": now if exhausted else None,
            **({"openKey": None} if exhausted else {}),
            "error": error,
        },
    )
