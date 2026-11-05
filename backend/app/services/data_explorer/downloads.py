from __future__ import annotations

import asyncio
import io
import logging
from typing import Any

from fastapi import HTTPException, status
from fastapi.responses import RedirectResponse, Response, StreamingResponse

from app.services import naming
from app.services.data_explorer import convert, signing
from app.services.media_naming import display_filename

logger = logging.getLogger(__name__)

THUMBNAIL_SIZES = (256, 512)
THUMBNAIL_PREFIX = "thumbnails"
THUMBNAIL_MIME = "image/webp"
THUMBNAIL_SOURCE_MAX_BYTES = 64 * 1024 * 1024
THUMBNAIL_PIXELS_MAX = 50_000_000
THUMBNAIL_RESIZABLE_MODES = frozenset({"RGB", "RGBA", "L", "LA", "CMYK", "YCbCr"})
_THUMB_SLOT = asyncio.Semaphore(1)
THUMBNAIL_WAIT_SECONDS = 20.0
THUMBNAIL_RETRY_AFTER = "15"

PREVIEW_MIME_PREFIXES = ("image/", "video/", "audio/")
PREVIEW_MIME_EXACT = frozenset({"application/pdf"})
PREVIEW_MIME_UNSAFE = frozenset({"image/svg+xml"})
PREVIEW_TYPES = frozenset({"IMAGE", "VIDEO", "AUDIO", "PDF"})


def kind_of(media: Any) -> str:
    value = getattr(media, "mediaType", None)
    value = getattr(value, "value", value)
    return str(value or "OTHER").upper()


def base_mime(raw: str | None) -> str:
    return str(raw or "").split(";")[0].strip().lower()


def _mime(media: Any) -> str:
    return base_mime(getattr(media, "mimeType", None)) or "application/octet-stream"


def previewable(media: Any) -> bool:
    if kind_of(media) not in PREVIEW_TYPES:
        return False
    mime = _mime(media)
    if mime in PREVIEW_MIME_UNSAFE:
        return False
    return mime in PREVIEW_MIME_EXACT or mime.startswith(PREVIEW_MIME_PREFIXES)


def entry_links(media_id: str, media_type: str, mime: str | None = None) -> dict[str, str]:
    base = f"/data/media/{media_id}"
    links = {"downloadPath": f"{base}/download"}
    kind = (media_type or "OTHER").upper()
    safe_mime = base_mime(mime) not in PREVIEW_MIME_UNSAFE
    if kind == "IMAGE" and safe_mime:
        links["thumbnailPath"] = f"{base}/thumbnail"
    if kind in PREVIEW_TYPES and safe_mime:
        links["previewPath"] = f"{base}/preview"
    return links


def _redirect(url: str, max_age: int) -> RedirectResponse:
    return RedirectResponse(
        url,
        status_code=status.HTTP_307_TEMPORARY_REDIRECT,
        headers=signing.redirect_headers(max_age),
    )


def original_redirect(media: Any, *, name: str | None = None) -> Response:
    object_key = getattr(media, "objectKey", None)
    filename = name or display_filename(media, fallback=str(media.id))
    if object_key:
        url, max_age = signing.signed_get(
            str(object_key), filename=filename, mime_type=_mime(media)
        )
        response = _redirect(url, max_age)
        response.headers["X-Conversion"] = "none"
        return response
    stored = getattr(media, "url", None)
    if stored:
        return _redirect(str(stored), 0)
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Media not found")


def preview_redirect(media: Any) -> Response:
    if not previewable(media):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="This file can't be previewed. Download it instead.",
        )
    object_key = getattr(media, "objectKey", None)
    if not object_key:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Media not found")
    filename = display_filename(media, fallback=str(media.id))
    url, max_age = signing.signed_get(
        str(object_key), filename=filename, mime_type=_mime(media), inline=True
    )
    return _redirect(url, max_age)


def _original_name(media: Any) -> str:
    return display_filename(media, fallback=str(media.id))


async def _fallback(media: Any, reason: str) -> Response:
    convert.record("fallback", str(media.id), reason)
    response = original_redirect(media, name=_original_name(media))
    response.headers["X-Conversion"] = "original"
    return response


def _refuse(code: int, detail: str) -> HTTPException:
    return HTTPException(status_code=code, detail=detail)


async def converted_audio(media: Any, *, fallback: bool) -> Response:
    from app.services.s3 import ObjectTooLarge, discard_temp, download_to_temp
    from app.transcription import workdir

    media_id = str(media.id)
    if not convert.available():
        convert.record("failed", media_id, "ffmpeg is not installed")
        if fallback:
            return await _fallback(media, "ffmpeg is not installed")
        raise _refuse(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "This recording can't be converted right now. Download the original instead.",
        )
    if int(getattr(media, "sizeBytes", 0) or 0) > convert.MAX_SOURCE_BYTES:
        if fallback:
            return await _fallback(media, "too large")
        raise _refuse(
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            "This recording is too large to convert. Download the original instead.",
        )
    if not await convert.acquire():
        convert.record("busy", media_id, "another conversion is running")
        if fallback:
            return await _fallback(media, "busy")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Another recording is being converted. Try again in a minute.",
            headers={"Retry-After": "30"},
        )
    source_path: str | None = None
    handed_over = False
    try:
        try:
            source_path = await workdir.owned(
                download_to_temp, media.objectKey, suffix=".src", max_bytes=convert.MAX_SOURCE_BYTES
            )
        except ObjectTooLarge:
            if fallback:
                return await _fallback(media, "too large")
            raise _refuse(
                status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                "This recording is too large to convert. Download the original instead.",
            ) from None
        except Exception as exc:
            convert.record("failed", media_id, type(exc).__name__)
            if fallback:
                return await _fallback(media, "fetch failed")
            raise _refuse(
                status.HTTP_502_BAD_GATEWAY,
                "The recording couldn't be fetched just now. Try again.",
            ) from exc
        try:
            process, head, mode = await convert.first_chunk(source_path, media_id)
        except Exception as exc:
            convert.record("failed", media_id, str(exc)[:200])
            if fallback:
                return await _fallback(media, "conversion failed")
            raise _refuse(
                status.HTTP_503_SERVICE_UNAVAILABLE,
                "This recording couldn't be converted. Download the original instead.",
            ) from exc
        path = source_path

        def cleanup() -> None:
            discard_temp(path)

        handed_over = True
        return StreamingResponse(
            convert.rest(process, head, media_id, mode, cleanup),
            media_type="video/mp4",
            headers={
                "Content-Disposition": naming.content_disposition(
                    display_filename(media, extension=".mp4")
                ),
                "X-Conversion": "converted",
            },
        )
    finally:
        if not handed_over:
            convert.release()
            if source_path:
                discard_temp(source_path)


async def media_download(media: Any, *, fmt: str | None, fallback: bool) -> Response:
    is_audio = kind_of(media) == "AUDIO"
    wanted = (fmt or "").strip().lower() or ("mp4" if is_audio else None)
    if is_audio and wanted == "mp4":
        return await converted_audio(media, fallback=fallback)
    return original_redirect(media)


def thumbnail_key(media_id: str, size: int) -> str:
    return f"{THUMBNAIL_PREFIX}/{size}/{media_id}.webp"


class ThumbnailTooLarge(ValueError):
    pass


def render_thumbnail(source: bytes | str, size: int) -> bytes:
    from PIL import Image, ImageOps

    handle = io.BytesIO(source) if isinstance(source, bytes) else source
    with Image.open(handle) as opened:
        opened.draft("RGB", (size * 2, size * 2))
        width, height = opened.size
        if width * height > THUMBNAIL_PIXELS_MAX:
            raise ThumbnailTooLarge(f"{width}x{height} is over {THUMBNAIL_PIXELS_MAX} pixels")
        picture: Any = opened
        if picture.mode not in THUMBNAIL_RESIZABLE_MODES:
            alpha = "A" in picture.getbands() or "transparency" in picture.info
            picture = picture.convert("RGBA" if alpha else "RGB")
        picture.thumbnail((size, size))
        picture = ImageOps.exif_transpose(picture)
        if picture.mode not in ("RGB", "RGBA"):
            picture = picture.convert("RGBA" if "A" in picture.getbands() else "RGB")
        out = io.BytesIO()
        picture.save(out, format="WEBP", quality=78, method=4)
        return out.getvalue()


async def _render_from_storage(object_key: str, edge: int) -> bytes:
    from app.services.s3 import ObjectTooLarge, discard_temp, download_to_temp
    from app.transcription import workdir

    try:
        path = await workdir.owned(
            download_to_temp, object_key, suffix=".img", max_bytes=THUMBNAIL_SOURCE_MAX_BYTES
        )
    except ObjectTooLarge as exc:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="No thumbnail"
        ) from exc
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No thumbnail") from exc
    try:
        return await asyncio.to_thread(render_thumbnail, path, edge)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="No thumbnail"
        ) from exc
    finally:
        discard_temp(path)


def _thumbnail_size(size: int | None) -> int:
    if size is None:
        return THUMBNAIL_SIZES[0]
    return min(THUMBNAIL_SIZES, key=lambda candidate: abs(candidate - int(size)))


def thumbnailable(media: Any) -> bool:
    return (
        kind_of(media) == "IMAGE"
        and _mime(media) not in PREVIEW_MIME_UNSAFE
        and bool(getattr(media, "objectKey", None))
    )


async def ensure_thumbnail(media: Any, edge: int, *, wait: float | None = None) -> bytes | None:
    from app.services import s3

    if not thumbnailable(media):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No thumbnail")
    key = thumbnail_key(str(media.id), edge)
    if await asyncio.to_thread(s3.object_exists, key):
        return None
    try:
        await asyncio.wait_for(
            _THUMB_SLOT.acquire(), timeout=THUMBNAIL_WAIT_SECONDS if wait is None else wait
        )
    except TimeoutError:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Thumbnails are being made. Try again shortly.",
            headers={"Retry-After": THUMBNAIL_RETRY_AFTER},
        ) from None
    try:
        if await asyncio.to_thread(s3.object_exists, key):
            return None
        data = await _render_from_storage(str(media.objectKey), edge)
        await asyncio.to_thread(s3.put_object_bytes, key, data, mime_type=THUMBNAIL_MIME)
        return data
    finally:
        _THUMB_SLOT.release()


async def thumbnail_redirect(media: Any, size: int | None) -> Response:
    edge = _thumbnail_size(size)
    await ensure_thumbnail(media, edge)
    key = thumbnail_key(str(media.id), edge)
    name = f"{media.id!s}-{edge}.webp"
    url, max_age = signing.signed_get(key, filename=name, mime_type=THUMBNAIL_MIME, inline=True)
    return _redirect(url, max_age)
