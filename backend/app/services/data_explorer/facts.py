from __future__ import annotations

import asyncio
import concurrent.futures
import io
import json
import logging
import os
import threading
from typing import Any

from app.core.db import db

log = logging.getLogger(__name__)

FACTS_KEY = "facts"
MEASURE_MAX_BYTES = 64 * 1024 * 1024
MEASURE_PER_LISTING = 12
MEASURE_SECONDS = 20.0
PARSE_MAX_BYTES = MEASURE_MAX_BYTES
MAX_PAGES = 100_000
MAX_PIXELS = 200_000_000
MAX_SIDE = 100_000
IMAGE_FORMATS = ("JPEG", "PNG", "GIF", "WEBP", "BMP", "TIFF", "MPO", "ICO", "AVIF", "HEIF")
_SLOTS = asyncio.Semaphore(1)
_parser = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="facts-parse")
_stuck = threading.Event()


def _meta(media: Any) -> dict[str, Any]:
    raw = getattr(media, "extraMetadata", None) if not isinstance(media, dict) else media.get("extraMetadata")
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError:
            return {}
    return raw if isinstance(raw, dict) else {}


def stored(media: Any) -> dict[str, int]:
    found = _meta(media).get(FACTS_KEY)
    if not isinstance(found, dict):
        return {}
    out: dict[str, int] = {}
    for key in ("width", "height", "pages"):
        value = found.get(key)
        if isinstance(value, int) and value > 0:
            out[key] = value
    return out


def _kind(media: Any) -> str:
    value = getattr(media, "mediaType", None)
    return str(getattr(value, "value", value) or "").upper()


def _mime(media: Any) -> str:
    return str(getattr(media, "mimeType", "") or "").lower()


def measurable(media: Any) -> bool:
    if not getattr(media, "objectKey", None) or getattr(media, "id", None) is None:
        return False
    if _meta(media).get(FACTS_KEY) is not None:
        return False
    if _kind(media) == "IMAGE":
        return _mime(media) != "image/svg+xml"
    return _mime(media) == "application/pdf" or str(getattr(media, "originalFilename", "")).lower().endswith(".pdf")


def entry_facts(media: Any) -> dict[str, Any]:
    known = stored(media)
    out: dict[str, Any] = {}
    if "width" in known and "height" in known:
        out["pixels"] = {"width": known["width"], "height": known["height"]}
    if "pages" in known:
        out["pages"] = known["pages"]
    return out


def _too_big(source: bytes | str) -> bool:
    if isinstance(source, bytes):
        return len(source) > PARSE_MAX_BYTES
    try:
        return os.path.getsize(source) > PARSE_MAX_BYTES
    except OSError:
        return True


def _image_formats() -> list[str]:
    from PIL import Image

    Image.init()
    return [name for name in IMAGE_FORMATS if name in Image.OPEN]


def image_size(source: bytes | str) -> dict[str, int]:
    from PIL import Image

    if _too_big(source):
        return {}
    handle = io.BytesIO(source) if isinstance(source, bytes) else source
    try:
        with Image.open(handle, formats=_image_formats()) as opened:
            width, height = opened.size
            if width <= 0 or height <= 0 or width > MAX_SIDE or height > MAX_SIDE or width * height > MAX_PIXELS:
                return {}
            orientation = None
            if opened.format in ("JPEG", "MPO", "TIFF") or "exif" in opened.info:
                orientation = opened.getexif().get(0x0112)
    except Exception:
        log.info("facts: unreadable image", exc_info=True)
        return {}
    if orientation in (5, 6, 7, 8):
        width, height = height, width
    return {"width": int(width), "height": int(height)}


def pdf_pages(source: bytes | str) -> dict[str, int]:
    from pypdf import PdfReader

    if _too_big(source):
        return {}
    handle = io.BytesIO(source) if isinstance(source, bytes) else source
    try:
        reader = PdfReader(handle, strict=False)
        declared = reader.trailer["/Root"].get_object()["/Pages"].get_object().get("/Count")
        count = int(declared.get_object() if hasattr(declared, "get_object") else declared)
    except Exception:
        log.info("facts: unreadable pdf", exc_info=True)
        return {}
    if count <= 0 or count > MAX_PAGES:
        return {}
    return {"pages": count}


def measure_file(media: Any, path: str) -> dict[str, int]:
    if _kind(media) == "IMAGE":
        return image_size(path)
    return pdf_pages(path)


async def bounded_measure(media: Any, path: str, *, seconds: float | None = None) -> dict[str, int]:
    if _stuck.is_set():
        return {}
    future = _parser.submit(measure_file, media, path)
    try:
        found = await asyncio.wait_for(asyncio.wrap_future(future), timeout=MEASURE_SECONDS if seconds is None else seconds)
    except TimeoutError:
        _stuck.set()
        future.add_done_callback(lambda _done: _stuck.clear())
        log.warning("facts: measuring %s took too long; recorded as unknown", getattr(media, "id", "?"))
        return {}
    return found if isinstance(found, dict) else {}


async def remember(media_id: str, found: dict[str, int] | None) -> None:
    row = await db.mediafile.find_unique(where={"id": media_id})
    if row is None:
        return
    meta = dict(_meta(row))
    meta[FACTS_KEY] = found or {}
    from prisma import Json

    await db.mediafile.update(where={"id": media_id}, data={"extraMetadata": Json(meta)})


async def measure(media: Any) -> dict[str, int]:
    from app.services.s3 import ObjectTooLarge, discard_temp, download_to_temp_async

    media_id = str(media.id)
    path = None
    try:
        path = await download_to_temp_async(str(media.objectKey), suffix=".fact", max_bytes=MEASURE_MAX_BYTES)
        found = await bounded_measure(media, path)
    except ObjectTooLarge:
        found = {}
    except Exception:
        log.info("facts: could not measure %s", media_id, exc_info=True)
        found = {}
    finally:
        discard_temp(path)
    await remember(media_id, found)
    return found


SWEEP_DELAY_SECONDS = 3.0
_queue: dict[str, Any] = {}
_sweeper: list[asyncio.Task[None]] = []


async def _sweep() -> None:
    await asyncio.sleep(SWEEP_DELAY_SECONDS)
    while _queue:
        media_id, media = next(iter(_queue.items()))
        try:
            async with _SLOTS:
                await measure(media)
        except Exception:
            log.info("facts: background measure failed for %s", media_id, exc_info=True)
        finally:
            _queue.pop(media_id, None)
    _sweeper.clear()


def schedule(media_rows: list[Any], limit: int = MEASURE_PER_LISTING) -> int:
    added = 0
    for media in media_rows:
        if added >= limit:
            break
        if media is None or not measurable(media):
            continue
        media_id = str(media.id)
        if media_id in _queue:
            continue
        _queue[media_id] = media
        added += 1
    if added and not _sweeper:
        try:
            _sweeper.append(asyncio.get_running_loop().create_task(_sweep()))
        except RuntimeError:
            _queue.clear()
    return added
