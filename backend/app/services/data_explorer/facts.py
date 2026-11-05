from __future__ import annotations

import asyncio
import io
import json
import logging
from typing import Any

from app.core.db import db

log = logging.getLogger(__name__)

FACTS_KEY = "facts"
MEASURE_MAX_BYTES = 64 * 1024 * 1024
MEASURE_PER_LISTING = 12
_SLOTS = asyncio.Semaphore(1)


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


def image_size(source: bytes | str) -> dict[str, int]:
    from PIL import Image

    handle = io.BytesIO(source) if isinstance(source, bytes) else source
    with Image.open(handle) as opened:
        width, height = opened.size
        orientation = opened.getexif().get(0x0112)
        if orientation in (5, 6, 7, 8):
            width, height = height, width
    return {"width": int(width), "height": int(height)}


def pdf_pages(source: bytes | str) -> dict[str, int]:
    from pypdf import PdfReader

    handle = io.BytesIO(source) if isinstance(source, bytes) else source
    return {"pages": len(PdfReader(handle).pages)}


def measure_file(media: Any, path: str) -> dict[str, int]:
    if _kind(media) == "IMAGE":
        return image_size(path)
    return pdf_pages(path)


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
        found = await asyncio.to_thread(measure_file, media, path)
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
