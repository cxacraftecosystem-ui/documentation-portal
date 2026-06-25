import asyncio
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi.encoders import jsonable_encoder
from prisma import Json

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services.ai import analyze_measurement_image_bytes, refine_transcript_text, transcribe_audio_bytes
from app.services.app_settings import (
    load_app_settings,
    transcription_mode,
    within_processing_window,
)
from app.services.s3 import get_object_bytes

TRANSCRIPTION = "TRANSCRIPTION"
MEASUREMENT = "MEASUREMENT"
QUEUED = "QUEUED"
PROCESSING = "PROCESSING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
UNAVAILABLE = "UNAVAILABLE"

STALE_PROCESSING_AFTER = timedelta(minutes=30)


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
    if not requests:
        return []

    media_id = str(_value(media, "id"))
    target_data = _target_data(media)
    created_jobs: list[Any] = []
    for request in sorted(requests):
        job = await db.mediaprocessingjob.create(
            data={
                "jobType": request,
                "status": QUEUED,
                "priority": 50 if request == TRANSCRIPTION else 70,
                "maxAttempts": max(settings.media_queue_job_max_attempts, 1),
                "mediaFileId": media_id,
                "requestedById": requested_by_id,
                **target_data,
            }
        )
        created_jobs.append(job)

    if TRANSCRIPTION in requests:
        await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": QUEUED})

    if MEASUREMENT in requests:
        await _mark_measurement_queued(media_id, target_data)

    return created_jobs


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
    where: dict[str, Any] = {"status": QUEUED, "runAfter": {"lte": datetime.now(UTC)}}
    # Off-peak optimization: when the master admin has enabled the processing window and we're outside
    # it, defer the heavy TRANSCRIPTION (and its auto-refinement) work to the window. Lighter
    # MEASUREMENT jobs still run immediately so product/tool dimensions keep auto-filling during the day.
    app_settings = await load_app_settings()
    if not within_processing_window(app_settings):
        where["jobType"] = {"not": TRANSCRIPTION}
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
        except Exception as exc:  # noqa: BLE001
            failed += 1
            await _handle_job_failure(job.id, exc)
    return {"processed": processed, "succeeded": succeeded, "failed": failed}


async def transcribe_media_now(media: Any, settings: Settings | None = None) -> dict[str, Any]:
    """Transcribe one audio media file immediately and store the result, applying the transcription
    mode configured in the settings page (RAW / REFINED / REFINED+TRANSLATED). This is the admin
    "Transcribe now" action: it bypasses the queue AND the off-peak window so the result is produced on
    the spot. Mirrors the worker's TRANSCRIPTION path so a manual run and a queued run agree. Never
    raises on an AI failure — it records the status/error on the media row and returns the result so the
    caller can surface it."""
    settings = settings or get_settings()
    media_id = str(_value(media, "id"))
    await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": PROCESSING})
    content = await asyncio.to_thread(get_object_bytes, _value(media, "objectKey"))
    result = await transcribe_audio_bytes(
        content,
        _value(media, "originalFilename") or "recording.webm",
        _value(media, "mimeType") or "audio/webm",
        settings,
    )
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
    transcript = result.get("formattedTranscript") or result.get("text")
    await db.mediafile.update(
        where={"id": media_id},
        data={
            "transcriptStatus": status,
            "transcriptText": transcript,
            "transcriptSummary": result.get("text"),
            "transcriptError": None if status in {COMPLETED, "EMPTY"} else result.get("message"),
        },
    )
    return result


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
    job = await db.mediaprocessingjob.find_unique(where={"id": job_id})
    if not job or job.status != QUEUED:
        return None
    now = datetime.now(UTC)
    return await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": PROCESSING,
            "lockedAt": now,
            "lockedBy": worker_id,
            "startedAt": now,
            "attempts": job.attempts + 1,
            "error": None,
        },
        include={"mediaFile": True},
    )


async def _process_job(job: Any, settings: Settings) -> None:
    media = job.mediaFile
    content = await asyncio.to_thread(get_object_bytes, media.objectKey)
    if job.jobType == TRANSCRIPTION:
        result = await transcribe_audio_bytes(
            content,
            media.originalFilename or "recording.webm",
            media.mimeType or "audio/webm",
            settings,
        )
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
        result = await analyze_measurement_image_bytes(
            content,
            media.originalFilename or "measurement.jpg",
            media.mimeType or "image/jpeg",
            settings,
        )
        await _apply_measurement_result(job, result)
        return
    raise RuntimeError(f"Unsupported media processing job type: {job.jobType}")


async def _apply_transcription_result(job: Any, result: dict[str, Any]) -> None:
    status = str(result.get("status") or FAILED).upper()
    message = result.get("message")
    transcript = result.get("formattedTranscript") or result.get("text")
    media_data = {
        "transcriptStatus": status,
        "transcriptText": transcript,
        "transcriptSummary": result.get("text"),
        "transcriptError": None if status in {COMPLETED, "EMPTY"} else message,
    }
    await db.mediafile.update(where={"id": job.mediaFileId}, data=media_data)
    if status in {COMPLETED, "EMPTY"}:
        await _complete_job(job.id, result)
    elif status == UNAVAILABLE:
        await _finalize_unavailable_job(job.id, result, message)
    else:
        raise RuntimeError(message or "Transcription failed")


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
        raise RuntimeError(message or "Measurement analysis failed")


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
            "completedAt": datetime.now(UTC),
            "result": Json(jsonable_encoder(result)),
            "error": str(message or "Required AI API key is not configured."),
        },
    )


async def _handle_job_failure(job_id: str, exc: Exception) -> None:
    job = await db.mediaprocessingjob.find_unique(where={"id": job_id})
    if not job:
        return
    now = datetime.now(UTC)
    error = str(exc)[:2000]
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
            "error": error,
        },
    )
