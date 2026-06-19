import asyncio
import math
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, is_admin, require_admin
from app.schemas.media import (
    MediaCompleteRequest,
    MediaRelinkRequest,
    MultipartAbortRequest,
    MultipartCompleteRequest,
    MultipartCompleteResponse,
    MultipartCreateRequest,
    MultipartCreateResponse,
    MultipartPresignPartsRequest,
    MultipartPresignPartsResponse,
    PresignRequest,
    PresignResponse,
    TranscriptRefineRequest,
    TranscriptUpdateRequest,
)
from app.services.ai import analyze_measurement_image, refine_transcript_text, transcribe_audio
from app.services.media_queue import enqueue_media_processing_jobs, process_next_media_jobs
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    public_encode,
    add_date_range,
    attach_location,
    clean_data,
    contains,
    jsonify_metadata,
    media_relation_data,
    require_record,
    visibility_where,
)
from app.services.s3 import (
    abort_multipart_upload,
    complete_multipart_upload,
    create_multipart_upload,
    delete_object,
    make_object_key,
    presign_put_url,
    presign_upload_part,
    public_url_for_key,
)

# S3 multipart part size. >= 5 MiB (S3 minimum for all but the last part); 16 MiB keeps the part
# count low for large videos while staying small enough to retry a single part cheaply.
MULTIPART_PART_SIZE = 16 * 1024 * 1024

router = APIRouter(prefix="/media", tags=["media"])

INCLUDE = {
    "uploadedBy": True,
    "location": True,
    "artisan": True,
    "craft": True,
    "workshop": True,
    "product": True,
    "tool": True,
    "processingJobs": True,
}


@router.post("/presign", response_model=PresignResponse)
async def presign_media_upload(
    payload: PresignRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = get_settings()
    object_key = make_object_key(current_user.id, payload.filename)
    return {
        "uploadUrl": presign_put_url(object_key, payload.mimeType),
        "method": "PUT",
        "objectKey": object_key,
        "bucket": settings.aws_s3_bucket,
        "headers": {"Content-Type": payload.mimeType},
        "publicUrl": public_url_for_key(object_key),
    }


def _assert_owns_object(object_key: str, current_user: Any) -> None:
    """Multipart object keys live under media/<user_id>/ — refuse to touch another user's upload."""
    if not object_key.startswith(f"media/{current_user.id}/"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only manage your own uploads")


@router.post("/multipart/create", response_model=MultipartCreateResponse)
async def create_multipart(
    payload: MultipartCreateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Start a multipart (chunked) upload for a large file. The client uploads each part straight to
    S3 via the presigned URLs from /multipart/presign-parts, then calls /multipart/complete; S3
    stitches the parts into a single object."""
    settings = get_settings()
    object_key = make_object_key(current_user.id, payload.filename)
    upload_id = await asyncio.to_thread(create_multipart_upload, object_key, payload.mimeType)
    part_count = max(1, math.ceil(payload.sizeBytes / MULTIPART_PART_SIZE))
    return {
        "objectKey": object_key,
        "uploadId": upload_id,
        "bucket": settings.aws_s3_bucket,
        "partSize": MULTIPART_PART_SIZE,
        "partCount": part_count,
        "publicUrl": public_url_for_key(object_key),
    }


@router.post("/multipart/presign-parts", response_model=MultipartPresignPartsResponse)
async def presign_multipart_parts(
    payload: MultipartPresignPartsRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _assert_owns_object(payload.objectKey, current_user)
    urls: dict[str, str] = {}
    for part_number in payload.partNumbers:
        urls[str(part_number)] = await asyncio.to_thread(
            presign_upload_part, payload.objectKey, payload.uploadId, part_number
        )
    return {"urls": urls}


@router.post("/multipart/complete", response_model=MultipartCompleteResponse)
async def complete_multipart(
    payload: MultipartCompleteRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _assert_owns_object(payload.objectKey, current_user)
    settings = get_settings()
    parts = sorted(
        ({"PartNumber": part.partNumber, "ETag": part.etag} for part in payload.parts),
        key=lambda item: item["PartNumber"],
    )
    if not parts:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No parts to complete")
    await asyncio.to_thread(complete_multipart_upload, payload.objectKey, payload.uploadId, parts)
    return {
        "objectKey": payload.objectKey,
        "bucket": settings.aws_s3_bucket,
        "publicUrl": public_url_for_key(payload.objectKey),
    }


@router.post("/multipart/abort")
async def abort_multipart(
    payload: MultipartAbortRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, bool]:
    _assert_owns_object(payload.objectKey, current_user)
    await asyncio.to_thread(abort_multipart_upload, payload.objectKey, payload.uploadId)
    return {"aborted": True}


@router.post("/transcribe")
async def transcribe_media_audio(
    file: UploadFile = File(...),
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await transcribe_audio(file, get_settings())


@router.post("/analyze-measurement")
async def analyze_media_measurement(
    file: UploadFile = File(...),
    dimension: str | None = Query(default=None),
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Analyse a grid-sheet photo and estimate a measurement. When ``dimension`` is one of
    length/breadth/height the result carries a single ``valueInches``; otherwise it returns the
    legacy length+breadth pair."""
    return await analyze_measurement_image(file, get_settings(), dimension)


@router.post("/complete", status_code=status.HTTP_201_CREATED)
async def complete_media_upload(
    payload: MediaCompleteRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = get_settings()
    processing_requests = payload.processingRequests
    data = clean_data(payload.model_dump(exclude={"processingRequests"}))
    data = await attach_location(data)
    data["bucket"] = data.get("bucket") or settings.aws_s3_bucket
    data["url"] = data.get("url") or public_url_for_key(data["objectKey"])
    data["uploadedById"] = current_user.id
    data.update(media_relation_data(data.get("linkedRecordType"), data.get("linkedRecordId")))
    jsonify_metadata(data)
    created = await db.mediafile.create(data=data, include=INCLUDE)
    await enqueue_media_processing_jobs(created, processing_requests, current_user.id, settings)
    created = await db.mediafile.find_unique(where={"id": created.id}, include=INCLUDE)
    return public_encode(created)


@router.get("")
async def list_media(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    mediaType: str | None = None,
    linkedRecordType: str | None = None,
    linkedRecordId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where = visibility_where(current_user, owner_field="uploadedById")
    if search:
        where["OR"] = [{"originalFilename": contains(search)}, {"caption": contains(search)}, {"mimeType": contains(search)}]
    if mediaType:
        where["mediaType"] = mediaType
    if linkedRecordType:
        where["linkedRecordType"] = linkedRecordType
    if linkedRecordId:
        where["linkedRecordId"] = linkedRecordId
    if statusFilter:
        where["status"] = statusFilter
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total = await db.mediafile.count(where=where)
    items = await db.mediafile.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(public_encode(items), total, page, page_size)


# linkedRecordType -> the typed foreign-key column that should point at the parent record. When a
# parent record is deleted its media FK is SET NULL (so the file and its S3 object are NOT lost), but
# these string tag columns survive, leaving the row an "orphan": still tagged with a type/id, no live
# parent. We surface and re-link those instead of letting the recordings disappear from every screen.
ORPHAN_FK_FIELDS = {
    "artisan": "artisanId",
    "craft": "craftId",
    "workshop": "workshopId",
    "product": "productId",
    "tool": "toolId",
    "questionnaire": "questionnaireInterviewId",
    "questionnaireinterview": "questionnaireInterviewId",
}


def _relink_delegate(record_type: str) -> Any:
    """The Prisma delegate for a re-link target record type (or None if unsupported)."""
    return {
        "artisan": db.artisan,
        "craft": db.craft,
        "workshop": db.workshop,
        "product": db.productdocumentation,
        "tool": db.tooldocumentation,
        "questionnaire": db.questionnaireinterview,
        "questionnaireinterview": db.questionnaireinterview,
    }.get(record_type)


@router.get("/orphans")
async def list_orphan_media(_: Any = Depends(require_admin)) -> list[dict[str, Any]]:
    """Admin-only recovery list: media still tagged to a record type/id whose parent no longer exists
    (the typed FK was nulled when the parent was deleted). The file is intact in object storage; this
    lets an admin see it again — and re-link it to a live record via ``/media/{id}/relink``."""
    conditions = [
        {"linkedRecordType": rec_type, "linkedRecordId": {"not": None}, fk: None}
        for rec_type, fk in ORPHAN_FK_FIELDS.items()
    ]
    rows = await db.mediafile.find_many(
        where={"OR": conditions},
        include=INCLUDE,
        order={"createdAt": "desc"},
    )
    return public_encode(rows)


@router.post("/{media_id}/relink")
async def relink_media(
    media_id: str,
    payload: MediaRelinkRequest,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Re-attach an orphaned (or mis-linked) media file to an existing record. Validates the target
    record exists, then sets both the string tag columns and the typed foreign key so it reappears
    under that record everywhere."""
    media = await require_record(db.mediafile, media_id)
    rec_type = payload.linkedRecordType.lower()
    delegate = _relink_delegate(rec_type)
    if delegate is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported record type for re-linking")
    target = await delegate.find_unique(where={"id": payload.linkedRecordId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Target record not found")
    data: dict[str, Any] = {
        "linkedRecordType": rec_type,
        "linkedRecordId": payload.linkedRecordId,
    }
    data.update(media_relation_data(rec_type, payload.linkedRecordId))
    updated = await db.mediafile.update(where={"id": media.id}, data=data, include=INCLUDE)
    return public_encode(updated)


@router.post("/{media_id}/refine-transcript")
async def refine_media_transcript(
    media_id: str,
    payload: TranscriptRefineRequest,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Refine this media file's existing transcript into a clean interviewer/interviewee conversation
    (Markdown), optionally translating it to English. On-demand and billable — the client warns the
    user about extra cost before calling. Returns the refined text; it is not persisted, so each call
    reflects the current transcript and the user stays in control of when the cost is incurred.

    Declared before the ``GET /{media_id}`` catch-all so the ``{media_id}/refine-transcript`` path is
    matched as this route, not swallowed by the single-segment id route.
    """
    media = await require_record(db.mediafile, media_id)
    transcript = getattr(media, "transcriptText", None)
    return await refine_transcript_text(transcript, payload.translate, get_settings())


@router.post("/{media_id}/transcript")
async def set_media_transcript(
    media_id: str,
    payload: TranscriptUpdateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Replace a media file's stored transcript with approved text (e.g. an AI-refined transcript the
    user accepted). Allowed for the uploader or an admin, mirroring the media-delete permission. Marks
    the transcript COMPLETED and clears any prior error. Declared before ``GET /{media_id}`` so the
    two-segment path resolves here."""
    media = await require_record(db.mediafile, media_id)
    if not is_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only edit the transcript of media you uploaded",
        )
    updated = await db.mediafile.update(
        where={"id": media.id},
        data={"transcriptText": payload.text, "transcriptStatus": "COMPLETED", "transcriptError": None},
        include=INCLUDE,
    )
    return public_encode(updated)


@router.get("/jobs")
async def list_media_processing_jobs(
    current_user: Any = Depends(get_current_user),
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {} if is_admin(current_user) else {"requestedById": current_user.id}
    if statusFilter:
        where["status"] = statusFilter
    total = await db.mediaprocessingjob.count(where=where)
    jobs = await db.mediaprocessingjob.find_many(
        where=where,
        include={"mediaFile": True, "requestedBy": True, "product": True, "tool": True},
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(public_encode(jobs), total, page, page_size)


@router.post("/jobs/process")
async def process_media_processing_jobs(
    limit: int | None = Query(default=None, ge=1, le=25),
    _: Any = Depends(require_admin),
) -> dict[str, int]:
    return await process_next_media_jobs(limit=limit, worker_id="manual-api")


@router.post("/jobs/{job_id}/retry")
async def retry_media_processing_job(
    job_id: str,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    job = await require_record(db.mediaprocessingjob, job_id)
    updated = await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": "QUEUED",
            "runAfter": datetime.now(UTC),
            "lockedAt": None,
            "lockedBy": None,
            "completedAt": None,
            "error": None,
        },
        include={"mediaFile": True},
    )
    return public_encode(updated)


@router.delete("/object", status_code=status.HTTP_204_NO_CONTENT)
async def delete_staged_object(objectKey: str, current_user: Any = Depends(get_current_user)) -> None:
    """Delete a staged S3 object that was pre-uploaded but never attached to a saved record.

    Scoped to the caller's own ``media/<user_id>/`` prefix, and refuses to touch any object that is
    already referenced by a MediaFile (those are deleted through the normal media delete route).
    """
    if not objectKey.startswith(f"media/{current_user.id}/"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only delete your own staged uploads")
    existing = await db.mediafile.find_first(where={"objectKey": objectKey})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Object is attached to a saved media file; delete that record instead",
        )
    await asyncio.to_thread(delete_object, objectKey)


@router.get("/{media_id}")
async def get_media(media_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    media = await require_record(db.mediafile, media_id) if not media else media
    return public_encode(media)


@router.delete("/{media_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_media(media_id: str, current_user: Any = Depends(get_current_user)) -> None:
    """Remove a saved media file and (best-effort) its S3 object.

    Admins may delete any media; everyone else may delete only media they uploaded — so a
    contributor can prune attachments on their own records straight from the edit screen without
    holding full delete rights on the parent record.
    """
    media = await require_record(db.mediafile, media_id)
    if not is_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only delete media you uploaded",
        )
    object_key = getattr(media, "objectKey", None)
    await db.mediafile.delete(where={"id": media_id})
    # Drop the underlying object too, but only once no other MediaFile still references it, and never
    # let a storage hiccup fail the request — the database row (the user-visible record) is gone.
    if object_key:
        still_referenced = await db.mediafile.find_first(where={"objectKey": object_key})
        if still_referenced is None:
            try:
                await asyncio.to_thread(delete_object, object_key)
            except Exception:  # noqa: BLE001 - best-effort storage cleanup
                pass
