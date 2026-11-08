import asyncio
import logging
import math
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prisma.errors import UniqueViolationError
from pydantic import ValidationError

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    get_current_user,
    is_content_admin,
    require_admin,
    require_content_admin,
)
from app.questionnaire import chips as questionnaire_chips
from app.questionnaire import common as v2_common
from app.questionnaire import filters as questionnaire_filters
from app.questionnaire import recordings as v2_recordings
from app.questionnaire import shims_fr as v2_shims
from app.questionnaire.live import live_media_where, live_session_where
from app.questionnaire.schemas import CompleteRequest as V2CompleteRequest
from app.questionnaire.schemas import MultipartCreateRequest as V2MultipartCreateRequest
from app.questionnaire.schemas import PresignRequest as V2PresignRequest
from app.schemas.media import (
    MediaCompleteRequest,
    MediaRelinkRequest,
    MultipartAbortRequest,
    MultipartCompleteRequest,
    MultipartCompleteResponse,
    MultipartCreateRequest,
    MultipartPresignPartsRequest,
    MultipartPresignPartsResponse,
    PresignRequest,
    TranscriptRefineRequest,
    TranscriptUpdateRequest,
)
from app.services import (
    consent_gate,
    job_supersede,
    media_mime,
    paid_ai_allowance,
    recording_consent,
    transcribe_choice,
    transcript_insights,
    transcription_jobs,
)
from app.services import s3 as s3_service
from app.services import transcription_enqueue as transcription
from app.services.ai import (
    analyze_measurement_image_bytes,
    refine_transcript_text,
    transcribe_audio_bytes,
)
from app.services.app_settings import load_transcription_paused
from app.services.artisan_merge import redirect_merged_artisans
from app.services.media_naming import display_filename, interview_record
from app.services.media_queue import (
    enqueue_media_processing_jobs,
    process_next_media_jobs,
    transcribe_media_now,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    add_date_range,
    attach_location,
    clean_data,
    contains,
    jsonify_metadata,
    media_relation_data,
    media_url_owners,
    public_encode,
    require_record,
    viewable_where,
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
from app.services.uploads import read_upload_bounded
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

MULTIPART_PART_SIZE = 16 * 1024 * 1024

MEASUREMENT_MAX_UPLOAD_BYTES = 8 * 1024 * 1024
DICTATION_MAX_UPLOAD_BYTES = 25 * 1024 * 1024

router = APIRouter(prefix="/media", tags=["media"])

logger = logging.getLogger(__name__)

_MEDIA_OWN_FILTERS = ("q", "dateFrom", "dateTo", "designWorkshopId")


def _questionnaire_media_filters(request: Request | None) -> Any:
    return questionnaire_filters.parse(request.query_params if request is not None else {}).without(*_MEDIA_OWN_FILTERS)


async def _with_questionnaire_chips(payload: Any) -> Any:
    rows = payload.get("items") if isinstance(payload, dict) and isinstance(payload.get("items"), list) else (
        payload if isinstance(payload, list) else [payload])
    ids = [str(r.get("id")) for r in rows if isinstance(r, dict) and r.get("questionnaireInterviewId")]
    if ids:
        found = await questionnaire_chips.for_media(db, ids)
        for r in rows:
            if isinstance(r, dict) and str(r.get("id")) in found:
                r["questionnaireChips"] = found[str(r.get("id"))]
    await _with_transcript_previews(rows)
    return payload


async def _with_transcript_previews(rows: list[Any]) -> None:
    from app.services import transcript_pipeline

    texts = {
        str(r.get("id")): (r.get("transcriptText") if isinstance(r.get("transcriptText"), str) and r.get("transcriptText").strip() else None)
        for r in rows if isinstance(r, dict) and r.get("id")
    }
    if not any(texts.values()):
        for r in rows:
            if isinstance(r, dict) and r.get("id"):
                r["transcriptPreview"] = None
        return
    found = await transcript_pipeline.previews(texts)
    for r in rows:
        if isinstance(r, dict) and r.get("id"):
            r["transcriptPreview"] = found.get(str(r.get("id")))


_UNSET_URLS = object()

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


async def _interview_labels(rows: list[Any]) -> dict[str, tuple[str, str]]:
    ids = sorted({r.questionnaireInterviewId for r in rows if r.questionnaireInterviewId})
    if not ids:
        return {}
    interviews = await db.questionnaireinterview.find_many(
        where={"id": {"in": ids}}, include={"artisans": {"include": {"artisan": True}}}
    )
    return {i.id: interview_record(i) for i in interviews}


async def _public(rows: Any, viewer: Any = None, *, media_urls: Any = _UNSET_URLS) -> Any:
    many = isinstance(rows, list)
    items = list(rows) if many else [rows]
    labels = await _interview_labels([r for r in items if r is not None])
    if media_urls is _UNSET_URLS:
        media_urls = await media_url_owners(viewer) if viewer is not None else set()
    encoded = public_encode(rows, viewer, media_urls=media_urls)
    for row, out in zip(items, encoded if many else [encoded]):
        if row is None or not isinstance(out, dict):
            continue
        kind, name = labels.get(row.questionnaireInterviewId or "", ("", ""))
        out["displayFilename"] = display_filename(
            row, record_type=kind or None, record_name=name or None, fallback=row.id
        )
    return encoded


V2_TYPE = "questionnaire_session"


async def _body(request: Request) -> dict[str, Any]:
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001
        raise RequestValidationError([{"type": "json_invalid", "loc": ("body",), "msg": "JSON body expected", "input": None}]) from None
    if not isinstance(body, dict):
        raise RequestValidationError([{"type": "dict_type", "loc": ("body",), "msg": "An object is expected", "input": body}])
    return body


def _old(model: Any, body: dict[str, Any]) -> Any:
    try:
        return model.model_validate(body)
    except ValidationError as exc:
        raise RequestValidationError(exc.errors()) from None


async def _v2(model: Any, body: dict[str, Any]) -> Any:
    from app.api.routes.questionnaire_v2 import v2_enabled
    from app.questionnaire.common import fail

    if not v2_enabled():
        fail("NOT_FOUND")
    try:
        return model.model_validate(body)
    except ValidationError as exc:
        errors = [{"loc": [str(p) for p in e.get("loc", ())], "msg": e.get("msg")} for e in exc.errors()]
        fail("VALIDATION_FAILED", errors[0]["msg"] if errors else None, errors=errors)


@router.post("/presign")
async def presign_media_upload(
    request: Request,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    body = await _body(request)
    if str(body.get("linkedRecordType") or "").lower() == V2_TYPE:
        return await v2_recordings.presign(await _v2(V2PresignRequest, body), current_user)
    payload = _old(PresignRequest, body)
    settings = get_settings()
    _assert_uploadable(payload.mimeType, payload.sizeBytes, settings)
    object_key = make_object_key(current_user.id, payload.filename)
    return {
        "uploadUrl": presign_put_url(object_key, payload.mimeType, payload.sizeBytes),
        "method": "PUT",
        "objectKey": object_key,
        "bucket": settings.aws_s3_bucket,
        "headers": {"Content-Type": payload.mimeType},
        "publicUrl": None,
    }


def _assert_uploadable(mime_type: str, size_bytes: int | None, settings: Any) -> None:
    normalized = media_mime.normalized_mime(mime_type)
    if not media_mime.is_uploadable_mime(normalized):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"{normalized or 'That file type'} cannot be uploaded here.",
        )
    if media_mime.over_ceiling(size_bytes, settings):
        raise _too_large(settings)


def _too_large(settings: Any) -> HTTPException:
    ceiling = media_mime.upload_ceiling(settings)
    return HTTPException(
        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        detail=f"That file is over the {ceiling // (1024 * 1024)} MB upload limit; split it or send a smaller one.",
    )


async def _assert_stored_object_within_ceiling(object_key: str, settings: Any) -> None:
    if not media_mime.upload_ceiling(settings):
        return
    head = await asyncio.to_thread(s3_service.head_object, object_key)
    if head is None or not media_mime.over_ceiling(head.size_bytes, settings):
        return
    try:
        await asyncio.to_thread(s3_service.delete_object, object_key)
    except Exception:
        logger.warning("Could not delete an over-limit upload %s", object_key)
    raise _too_large(settings)


def _assert_owns_object(object_key: str, current_user: Any) -> None:
    if not object_key.startswith(f"media/{current_user.id}/"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only manage your own uploads")


@router.post("/multipart/create")
async def create_multipart(
    request: Request,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    body = await _body(request)
    if str(body.get("linkedRecordType") or "").lower() == V2_TYPE:
        return await v2_recordings.multipart_create(await _v2(V2MultipartCreateRequest, body), current_user)
    payload = _old(MultipartCreateRequest, body)
    settings = get_settings()
    _assert_uploadable(payload.mimeType, payload.sizeBytes, settings)
    object_key = make_object_key(current_user.id, payload.filename)
    upload_id = await asyncio.to_thread(create_multipart_upload, object_key, payload.mimeType)
    part_count = max(1, math.ceil(payload.sizeBytes / MULTIPART_PART_SIZE))
    return {
        "objectKey": object_key,
        "uploadId": upload_id,
        "bucket": settings.aws_s3_bucket,
        "partSize": MULTIPART_PART_SIZE,
        "partCount": part_count,
        "publicUrl": None,
    }


@router.post("/multipart/presign-parts", response_model=MultipartPresignPartsResponse)
async def presign_multipart_parts(
    payload: MultipartPresignPartsRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _assert_owns_object(payload.objectKey, current_user)
    ceiling_part = media_mime.max_part_number(get_settings())
    if len(payload.partNumbers) > ceiling_part or any(n > ceiling_part for n in payload.partNumbers):
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="That upload has more parts than the upload limit allows.",
        )
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
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="No parts to complete")
    await asyncio.to_thread(complete_multipart_upload, payload.objectKey, payload.uploadId, parts)
    await _assert_stored_object_within_ceiling(payload.objectKey, settings)
    return {
        "objectKey": payload.objectKey,
        "bucket": settings.aws_s3_bucket,
        "publicUrl": None,
    }


@router.get("/uploads/{client_clip_key}")
async def upload_state(client_clip_key: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.api.routes.questionnaire_v2 import require_v2

    await require_v2()
    return await v2_recordings.upload_state(client_clip_key, current_user)


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
    request: Request,
    file: UploadFile = File(...),
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    if await transcription.is_paused():
        v2_common.fail("TRANSCRIPTION_PAUSED", "Transcription is paused. Type the answer for now.")
    content = await read_upload_bounded(
        file,
        DICTATION_MAX_UPLOAD_BYTES,
        request=request,
        purpose="recording",
        remedy=(
            "Upload a longer recording as workshop audio instead — it is transcribed in the "
            "background."
        ),
    )
    if await load_transcription_paused():
        return transcription_jobs.paused_result()
    return await transcribe_audio_bytes(
        content,
        file.filename or "recording.webm",
        file.content_type or "audio/webm",
        get_settings(),
        user_id=current_user.id,
    )


@router.post("/analyze-measurement")
async def analyze_media_measurement(
    request: Request,
    file: UploadFile = File(...),
    dimension: str | None = Query(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    content = await read_upload_bounded(
        file,
        MEASUREMENT_MAX_UPLOAD_BYTES,
        request=request,
        purpose="grid-sheet photograph",
        remedy="Photograph the grid sheet alone rather than the whole workbench.",
    )
    try:
        return await analyze_measurement_image_bytes(
            content,
            file.filename or "measurement.jpg",
            file.content_type or "image/jpeg",
            get_settings(),
            dimension,
            user_id=current_user.id,
        )
    except paid_ai_allowance.AllowanceSpent as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=exc.message,
            headers={"Retry-After": str(exc.retry_after)},
        ) from None


async def _finish_pending_media(existing: Any, processing_requests: list[str] | None, user_id: str, settings: Any) -> dict[str, Any]:
    processing_requests = await transcribe_choice.resolve_retry(existing, processing_requests)
    if processing_requests and not (existing.processingJobs or []):
        await enqueue_media_processing_jobs(existing, processing_requests, user_id, settings)
        existing = await db.mediafile.find_unique(where={"id": existing.id}, include=INCLUDE)
    return await _public(existing, media_urls={user_id})


@router.post("/complete", status_code=status.HTTP_201_CREATED)
async def complete_media_upload(
    request: Request,
    current_user: Any = Depends(get_current_user),
) -> Any:
    body = await _body(request)
    if str(body.get("linkedRecordType") or "").lower() == V2_TYPE:
        recording, created = await v2_recordings.complete(await _v2(V2CompleteRequest, body), current_user)
        return JSONResponse(recording, status_code=status.HTTP_201_CREATED if created else status.HTTP_200_OK)
    payload = _old(MediaCompleteRequest, body)
    answer = await _complete_old(payload, current_user)
    await v2_shims.after_legacy_complete(answer, payload, current_user)
    return answer


async def _complete_old(payload: MediaCompleteRequest, current_user: Any) -> dict[str, Any]:
    settings = get_settings()
    processing_requests = payload.processingRequests

    existing = await db.mediafile.find_unique(where={"objectKey": payload.objectKey}, include=INCLUDE)
    if existing is not None:
        if getattr(existing, "uploadedById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="This upload belongs to another user",
            )
        return await _finish_pending_media(existing, processing_requests, current_user.id, settings)

    _assert_owns_object(payload.objectKey, current_user)

    await redirect_merged_artisans(payload)
    data = clean_data(payload.model_dump(exclude={"processingRequests"}))
    data = await attach_location(data)
    data["bucket"] = data.get("bucket") or settings.aws_s3_bucket
    data["url"] = public_url_for_key(data["objectKey"])
    data["uploadedById"] = current_user.id
    relation_data = media_relation_data(data.get("linkedRecordType"), data.get("linkedRecordId"))
    parent: Any = None
    if relation_data:
        delegate = _relink_delegate(str(data["linkedRecordType"]).lower())
        parent = await delegate.find_unique(where={"id": data["linkedRecordId"]}) if delegate else None
        if parent is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Linked record not found")
    data.update(relation_data)
    if parent is None:
        parent = await _tagged_parent(data.get("linkedRecordType"), data.get("linkedRecordId"))

    inherit_parent_workshop(data, parent)

    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data.setdefault("status", "PENDING")
    pin_pending_if_late(data, current_user, check=check)

    jsonify_metadata(data)
    try:
        created = await db.mediafile.create(data=data, include=INCLUDE)
    except UniqueViolationError:
        racer = await db.mediafile.find_unique(where={"objectKey": payload.objectKey}, include=INCLUDE)
        if racer is not None:
            return await _finish_pending_media(racer, processing_requests, current_user.id, settings)
        raise
    processing_requests = await transcribe_choice.resolve_upload(created, processing_requests)
    await enqueue_media_processing_jobs(created, processing_requests, current_user.id, settings)
    created = await db.mediafile.find_unique(where={"id": created.id}, include=INCLUDE)
    return await _public(created, current_user)


@router.get("")
async def list_media(
    current_user: Any = Depends(get_current_user),
    request: Request = None,  # type: ignore[assignment]
    search: str | None = None,
    mediaType: str | None = None,
    linkedRecordType: str | None = None,
    linkedRecordId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    uploadedBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    vis = await viewable_where(current_user, owner_field="uploadedById")
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [{"originalFilename": contains(search)}, {"caption": contains(search)}, {"mimeType": contains(search)}]
        by_chips = await questionnaire_filters.media_text_ids(
            search.strip()[: questionnaire_filters.MAX_TEXT], client=db, viewer=current_user
        )
        if by_chips:
            where["OR"].append({"id": {"in": by_chips}})
    questionnaire_only = _questionnaire_media_filters(request)
    if questionnaire_filters.media_filtered(questionnaire_only):
        where.setdefault("AND", []).append(
            {"id": {"in": await questionnaire_filters.media_ids_matching(questionnaire_only, client=db)}}
        )
    if mediaType:
        where["mediaType"] = mediaType
    if linkedRecordType:
        where["linkedRecordType"] = linkedRecordType
    if linkedRecordId:
        where["linkedRecordId"] = linkedRecordId
    if statusFilter:
        where["status"] = statusFilter
    if uploadedBy:
        where["uploadedById"] = uploadedBy
    add_date_range(where, "createdAt", dateFrom, dateTo)
    where = {"AND": [live_media_where(where), {"OR": [
        {"questionnaireInterviewId": None},
        {"questionnaireInterview": {"is": live_session_where()}},
    ]}]}
    total = await db.mediafile.count(where=where)
    items = await db.mediafile.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return await _with_questionnaire_chips(page_payload(await _public(items, current_user), total, page, page_size))


ORPHAN_FK_FIELDS = {
    "artisan": "artisanId",
    "craft": "craftId",
    "workshop": "workshopId",
    "product": "productId",
    "tool": "toolId",
    "questionnaire": "questionnaireInterviewId",
    "questionnaireinterview": "questionnaireInterviewId",
    "questionnaire_session": "questionnaireInterviewId",
}


async def _tagged_parent(record_type: str | None, record_id: str | None) -> Any:
    normalized = (record_type or "").strip().lower()
    if not normalized or not record_id:
        return None
    try:
        if normalized == "processstep":
            step = await db.processstep.find_unique(where={"id": record_id})
            process_id = getattr(step, "processId", None)
            return await db.process.find_unique(where={"id": process_id}) if process_id else None
        delegate = {
            "process": db.process,
            "artisan": db.artisan,
            "craft": db.craft,
            "product": db.productdocumentation,
            "tool": db.tooldocumentation,
            "questionnaire": db.questionnaireinterview,
            "questionnaireinterview": db.questionnaireinterview,
            "media": db.mediafile,
            "misc": db.mediafile,
        }.get(normalized)
        return await delegate.find_unique(where={"id": record_id}) if delegate else None
    except Exception:  # noqa: BLE001
        return None


def inherit_parent_workshop(
    data: dict[str, Any], parent: Any, *, replace: bool = False
) -> dict[str, Any]:
    if data.get("workshopId") and not replace:
        return data
    inherited = getattr(parent, "workshopId", None) if parent is not None else None
    if inherited:
        data["workshopId"] = inherited
    return data


def _relink_delegate(record_type: str) -> Any:
    return {
        "artisan": db.artisan,
        "craft": db.craft,
        "workshop": db.workshop,
        "product": db.productdocumentation,
        "tool": db.tooldocumentation,
        "questionnaire": db.questionnaireinterview,
        "questionnaireinterview": db.questionnaireinterview,
        "questionnaire_session": db.questionnaireinterview,
        "media": db.mediafile,
        "misc": db.mediafile,
    }.get(record_type)


@router.get("/orphans")
async def list_orphan_media(current_user: Any = Depends(require_content_admin)) -> list[dict[str, Any]]:
    conditions = [
        {"linkedRecordType": rec_type, "linkedRecordId": {"not": None}, fk: None}
        for rec_type, fk in ORPHAN_FK_FIELDS.items()
    ]
    rows = await db.mediafile.find_many(
        where=live_media_where({"OR": conditions}),
        include=INCLUDE,
        order={"createdAt": "desc"},
    )
    return await _public(rows, current_user)


@router.post("/{media_id}/relink")
async def relink_media(
    media_id: str,
    payload: MediaRelinkRequest,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    await redirect_merged_artisans(payload)
    rec_type = payload.linkedRecordType.lower()
    delegate = _relink_delegate(rec_type)
    if delegate is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported record type for re-linking")
    target = await delegate.find_unique(where={"id": payload.linkedRecordId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Target record not found")
    take = await db.questionnairerecording.find_unique(where={"mediaFileId": media.id})
    if take is not None:
        if rec_type not in ("questionnaire", "questionnaireinterview", "questionnaire_session"):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This recording is a questionnaire take. Move it to another session instead.",
            )
        await v2_recordings.relink_take(take.id, payload.linkedRecordId, current_user)
        moved = await db.mediafile.find_unique(where={"id": media.id}, include=INCLUDE)
        return await _public(moved, current_user)
    data: dict[str, Any] = {
        "linkedRecordType": rec_type,
        "linkedRecordId": payload.linkedRecordId,
    }
    data.update(media_relation_data(rec_type, payload.linkedRecordId))
    inherit_parent_workshop(data, target, replace=True)
    updated = await db.mediafile.update(where={"id": media.id}, data=data, include=INCLUDE)
    return await _public(updated, current_user)


@router.post("/{media_id}/refine-transcript")
async def refine_media_transcript(
    media_id: str,
    payload: TranscriptRefineRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    if not is_content_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only refine the transcript of media you uploaded",
        )
    verdict = await consent_gate.verdict(media_id)
    if verdict != consent_gate.ALLOW:
        from app.questionnaire.common import fail

        fail("CONSENT_REFUSED" if verdict == consent_gate.REFUSED else "CONSENT_NOT_RECORDED")
    transcript = getattr(media, "transcriptText", None)
    return await refine_transcript_text(transcript, payload.translate, get_settings())


@router.post("/{media_id}/transcribe-now")
async def transcribe_media_now_route(
    media_id: str,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    if not transcribe_choice.transcribable(media):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only audio and video files can be transcribed.",
        )
    try:
        await transcribe_media_now(media, get_settings())
    except transcription_jobs.TranscriptionPaused as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except recording_consent.ConsentRefused as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    updated = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    return await _public(updated, current_user)


@router.post("/{media_id}/transcript")
async def set_media_transcript(
    media_id: str,
    payload: TranscriptUpdateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    if not is_content_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only edit the transcript of media you uploaded",
        )
    updated = await db.mediafile.update(
        where={"id": media.id},
        data={
            "transcriptText": payload.text,
            "transcriptStatus": "COMPLETED",
            "transcriptError": None,
            "transcriptEditedAt": datetime.now(UTC),
            "transcriptEditedById": current_user.id,
        },
        include=INCLUDE,
    )
    if transcript_insights.enabled():
        await transcript_insights.enqueue_after_transcript(media.id)
    return await _public(updated, current_user)


@router.get("/jobs")
async def list_media_processing_jobs(
    current_user: Any = Depends(get_current_user),
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {} if is_content_admin(current_user) else {"requestedById": current_user.id}
    if statusFilter:
        where["status"] = statusFilter
    where = await job_supersede.without_superseded(where, client=db)
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
    _: Any = Depends(require_content_admin),
) -> dict[str, int]:
    return await process_next_media_jobs(limit=limit, worker_id="manual-api")


@router.post("/jobs/{job_id}/retry")
async def retry_media_processing_job(
    job_id: str,
    _: Any = Depends(require_content_admin),
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
    if not objectKey.startswith(f"media/{current_user.id}/"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only delete your own staged uploads")
    existing = await db.mediafile.find_first(where={"objectKey": objectKey})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Object is attached to a saved media file; delete that record instead",
        )
    await asyncio.to_thread(delete_object, objectKey)


def _object_missing(object_key: str) -> bool:
    from botocore.exceptions import ClientError

    from app.services import s3 as stored_objects

    try:
        stored_objects._client().head_object(Bucket=get_settings().aws_s3_bucket, Key=object_key)
    except ClientError as exc:
        error = exc.response.get("Error") or {}
        code = str(error.get("Code") or "")
        answered = (exc.response.get("ResponseMetadata") or {}).get("HTTPStatusCode")
        return code in {"403", "404", "NoSuchKey", "NotFound", "AccessDenied"} or answered in (403, 404)
    except Exception:  # noqa: BLE001
        return False
    return False


@router.get("/{media_id}")
async def get_media(
    media_id: str, verify: bool = False, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    media = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    media = await require_record(db.mediafile, media_id) if not media else media
    out = await _with_questionnaire_chips(await _public(media, current_user))
    key = getattr(media, "objectKey", None)
    if verify is True and key and out.get("url"):
        out["fileMissing"] = await asyncio.to_thread(_object_missing, str(key))
    return out


@router.delete("/{media_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_media(media_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    media = await require_record(db.mediafile, media_id)
    take = await db.questionnairerecording.find_unique(where={"mediaFileId": media_id})
    if take is not None:
        await v2_recordings.remove(take.id, current_user)
        return
    object_key = getattr(media, "objectKey", None)
    await db.mediafile.delete(where={"id": media_id})
    if object_key:
        still_referenced = await db.mediafile.find_first(where={"objectKey": object_key})
        if still_referenced is None:
            try:
                await asyncio.to_thread(delete_object, object_key)
            except Exception:  # noqa: BLE001
                pass
