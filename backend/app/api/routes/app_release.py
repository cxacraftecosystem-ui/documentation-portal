import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.encoders import jsonable_encoder
from fastapi.responses import RedirectResponse

from app.core.db import db
from app.core.deps import get_current_user, require_master_admin
from app.schemas.app_release import AppReleasePublishRequest
from app.services import naming
from app.services.s3 import presign_get_url, public_url_for_key

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/app", tags=["app"])

APK_MEDIA_TYPE = "application/vnd.android.package-archive"


async def _latest_release() -> Any | None:
    return await db.apprelease.find_first(
        order=[{"versionCode": "desc"}, {"publishedAt": "desc"}],
    )


def _durable_url(release: Any) -> str | None:
    key = getattr(release, "objectKey", None)
    return (public_url_for_key(key) if key else None) or getattr(release, "url", None)


RELEASE_URL_TTL_SECONDS = 6 * 3600


def _signed_release_url(release: Any) -> str | None:
    key = getattr(release, "objectKey", None)
    if not key:
        return getattr(release, "url", None)
    try:
        return presign_get_url(
            key,
            filename=_download_filename(release),
            mime_type=APK_MEDIA_TYPE,
            expires_in=RELEASE_URL_TTL_SECONDS,
        )
    except Exception:
        logger.exception("Could not sign the release URL for %s", getattr(release, "id", "?"))
        return None


def _download_filename(release: Any) -> str:
    version = str(getattr(release, "versionName", "") or getattr(release, "versionCode", "") or "latest")
    return naming.export_filename(
        "Android-App", "Release-Build", "apk", dated=False, sequence=f"v{version}"
    )


@router.post("/release", status_code=status.HTTP_201_CREATED)
async def publish_release(
    payload: AppReleasePublishRequest,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    url = payload.url or public_url_for_key(payload.objectKey)
    created = await db.apprelease.create(
        data={
            "versionCode": payload.versionCode,
            "versionName": payload.versionName,
            "objectKey": payload.objectKey,
            "url": url,
            "notes": payload.notes,
            "publishedById": current_user.id,
        }
    )
    return jsonable_encoder(created)


@router.get("/release/latest")
async def latest_release(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    release = await _latest_release()
    if not release:
        return {}
    data = jsonable_encoder(release)
    data["url"] = _signed_release_url(release)
    return data


@router.get("/download")
async def download_latest_apk() -> RedirectResponse:
    release = await _latest_release()
    if release is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No Android build has been published yet, so there is nothing to download.",
        )

    key = getattr(release, "objectKey", None)
    target: str | None = None
    if key:
        try:
            target = presign_get_url(key, filename=_download_filename(release), mime_type=APK_MEDIA_TYPE)
        except Exception:
            logger.exception("Could not sign a download URL for release %s", getattr(release, "id", "?"))
    target = target or _durable_url(release)

    if not target:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="The app download isn't available right now. Try again later.",
        )

    response = RedirectResponse(target, status_code=status.HTTP_307_TEMPORARY_REDIRECT)
    response.headers["Cache-Control"] = "no-store, max-age=0"
    return response
