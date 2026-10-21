"""Android releases: what the newest build is, and how anyone gets hold of it.

**One resolver, not two.** The phone's over-the-air check and the web portal's download link are the
same question asked by two clients — "which build is current?" — and they used to answer it
separately: the phone asked this API, while the browser was handed a URL that had been *copied* into
a page. Anything copied drifts. So both now go through :func:`_latest_release`, and the download is a
fixed, versionless address (``/app/download``) that re-resolves on every click instead of a link to
one particular build. A page left open overnight, a bookmarked link, a URL pasted into a chat — all
of them fetch whatever is newest at the moment they are used, because none of them names a build.
"""

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

# The type Android's package installer expects. Sent explicitly because a browser handed
# application/octet-stream may offer to "open" the file with something else, and because the type
# stored on the object is only ever as right as whatever uploaded it.
APK_MEDIA_TYPE = "application/vnd.android.package-archive"


async def _latest_release() -> Any | None:
    """THE published release — the single definition of "latest" this API has.

    Highest version code wins, because that is the number devices compare; the publish timestamp only
    breaks a tie. Every caller below goes through here, so the update prompt on a phone and the
    download button in a browser can never be talking about different builds.
    """
    return await db.apprelease.find_first(
        order=[{"versionCode": "desc"}, {"publishedAt": "desc"}],
    )


def _durable_url(release: Any) -> str | None:
    """The long-lived, unsigned URL for a release's APK — what an installed phone downloads.

    Derived from the object key rather than read from the stored ``url`` column. The column is a
    convenience copy written at publish time and can only ever be as correct as the storage config
    was on that day; the key is the fact. The column is the fallback for the reverse case — a
    deployment with no public base URL configured, where nothing can be derived.
    """
    key = getattr(release, "objectKey", None)
    return (public_url_for_key(key) if key else None) or getattr(release, "url", None)


def _download_filename(release: Any) -> str:
    """``FieldRepository_Android-App_Release-Build_v1.1.17.apk`` — the version, in the name, in the user's Downloads folder.

    Whoever is sideloading this is comparing it against what is already on the phone, and often
    keeping several. A name that carries the version answers "which build is this?" without them
    having to install it and look.
    """
    version = str(getattr(release, "versionName", "") or getattr(release, "versionCode", "") or "latest")
    # NAMING 2.1: FieldRepository_Android-App_Release-Build_v0.0.13.apk
    return naming.export_filename(
        "Android-App", "Release-Build", "apk", dated=False, sequence=f"v{version}"
    )


@router.post("/release", status_code=status.HTTP_201_CREATED)
async def publish_release(
    payload: AppReleasePublishRequest,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    """Master-admin only: record a new app release. The APK has already been uploaded to object
    storage (via /media/presign) by the publishing device; this stores the version metadata so other
    devices can discover and self-install it.

    The version code is stored AS SENT (2026-10-21). It used to be re-derived from the version name,
    but the name restarted at 0.0.1 while the code must keep rising (Android will not install a
    lower code over an installed app), so the code is set in android/app/build.gradle.kts and every
    publisher sends it. The publishers refuse a code at or below the published one.

    The download URL is critical: other devices only show the forced-update prompt when the release
    carries a non-blank URL. So if the client didn't send one, derive it from the object key here — a
    release must never be published without a way to download it, or the push silently does nothing.
    """
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
    """The current release (highest versionCode), or an empty object when none has been published.

    The URL is recomputed from the object key on the way out (see :func:`_durable_url`), so a row
    saved with a blank or stale ``url`` column still tells a device where the APK is — a forced-update
    check must never skip a real release over a bookkeeping detail.
    """
    release = await _latest_release()
    if not release:
        return {}
    data = jsonable_encoder(release)
    url = _durable_url(release)
    if url:
        data["url"] = url
    return data


@router.get("/download")
async def download_latest_apk() -> RedirectResponse:
    """Redirect to the newest published APK. This is the address the web portal's download button
    points at, and it deliberately names no version.

    **Unauthenticated, on purpose.** A browser cannot attach a bearer token to a plain link
    navigation, so an authenticated endpoint here would mean a button that only works via JavaScript
    — and it would be guarding nothing: the object this redirects to is world-readable already,
    because every phone in the field fetches exactly that URL with no credentials at all when it
    self-updates.

    **Uncacheable, on purpose.** This response is the only moving part in the whole arrangement: the
    link is fixed and the bytes are immutable, so an intermediary that cached *this* redirect for a
    day would pin every new visitor to yesterday's build and reintroduce the exact staleness the
    fixed address exists to prevent.
    """
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
            # Signed, so S3 will honour the filename and content type we ask for; an anonymous GET
            # carrying those overrides is refused outright.
            target = presign_get_url(key, filename=_download_filename(release), mime_type=APK_MEDIA_TYPE)
        except Exception:
            # Misconfigured storage credentials must cost the visitor a tidy filename, not the
            # download itself — the unsigned URL still serves the same bytes.
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
