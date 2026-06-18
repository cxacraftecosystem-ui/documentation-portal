from typing import Any

from fastapi import APIRouter, Depends, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user, require_master_admin
from app.schemas.app_release import AppReleasePublishRequest

router = APIRouter(prefix="/app", tags=["app"])


@router.post("/release", status_code=status.HTTP_201_CREATED)
async def publish_release(
    payload: AppReleasePublishRequest,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    """Master-admin only: record a new app release. The APK has already been uploaded to object
    storage (via /media/presign) by the publishing device; this stores the version metadata so other
    devices can discover and self-install it."""
    created = await db.apprelease.create(
        data={
            "versionCode": payload.versionCode,
            "versionName": payload.versionName,
            "objectKey": payload.objectKey,
            "url": payload.url,
            "notes": payload.notes,
            "publishedById": current_user.id,
        }
    )
    return jsonable_encoder(created)


@router.get("/release/latest")
async def latest_release(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The current release (highest versionCode), or an empty object when none has been published."""
    release = await db.apprelease.find_first(
        order=[{"versionCode": "desc"}, {"publishedAt": "desc"}],
    )
    return jsonable_encoder(release) if release else {}
