from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, File, Query, UploadFile, status
from fastapi.encoders import jsonable_encoder

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user
from app.schemas.media import MediaCompleteRequest, PresignRequest, PresignResponse
from app.services.ai import analyze_measurement_image, transcribe_audio
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    add_date_range,
    attach_location,
    clean_data,
    contains,
    media_relation_data,
    require_record,
    visibility_where,
)
from app.services.s3 import make_object_key, presign_put_url, public_url_for_key

router = APIRouter(prefix="/media", tags=["media"])

INCLUDE = {"uploadedBy": True, "location": True, "artisan": True, "workshop": True, "product": True, "tool": True}


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


@router.post("/transcribe")
async def transcribe_media_audio(
    file: UploadFile = File(...),
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await transcribe_audio(file, get_settings())


@router.post("/analyze-measurement")
async def analyze_media_measurement(
    file: UploadFile = File(...),
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await analyze_measurement_image(file, get_settings())


@router.post("/complete", status_code=status.HTTP_201_CREATED)
async def complete_media_upload(
    payload: MediaCompleteRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = get_settings()
    data = clean_data(payload.model_dump())
    data = await attach_location(data)
    data["bucket"] = data.get("bucket") or settings.aws_s3_bucket
    data["url"] = data.get("url") or public_url_for_key(data["objectKey"])
    data["uploadedById"] = current_user.id
    data.update(media_relation_data(data.get("linkedRecordType"), data.get("linkedRecordId")))
    created = await db.mediafile.create(data=data, include=INCLUDE)
    return jsonable_encoder(created)


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
    return page_payload(jsonable_encoder(items), total, page, page_size)


@router.get("/{media_id}")
async def get_media(media_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    media = await require_record(db.mediafile, media_id) if not media else media
    return jsonable_encoder(media)


@router.delete("/{media_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_media(media_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.mediafile, media_id)
    await db.mediafile.delete(where={"id": media_id})
