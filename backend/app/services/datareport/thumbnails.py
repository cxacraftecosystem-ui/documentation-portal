from __future__ import annotations

import asyncio
import io
import logging
import time
from typing import Any

logger = logging.getLogger(__name__)

MAX_IMAGES = 150
MAX_BYTES = 6 * 1024 * 1024
EDGE = 96
SOURCE_EDGE = 256
SOURCE_MAX = 2 * 1024 * 1024
MAX_RENDERS = 60
RENDER_SECONDS = 90.0
RENDER_WAIT = 5.0


def _v(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def to_png(data: bytes, edge: int = EDGE) -> bytes | None:
    try:
        from PIL import Image
    except ImportError:
        return None
    try:
        with Image.open(io.BytesIO(data)) as opened:
            picture: Any = opened.convert("RGB")
            picture.thumbnail((edge, edge))
            out = io.BytesIO()
            picture.save(out, format="PNG", optimize=True)
            return out.getvalue()
    except Exception:  # noqa: BLE001 - an unreadable picture is skipped, never fatal
        return None


def _fetch(key: str) -> bytes | None:
    from app.services import s3

    try:
        if not s3.object_exists(key):
            return None
        return s3.get_object_bytes(key, max_bytes=SOURCE_MAX)
    except Exception:  # noqa: BLE001
        return None


def _is_image(m: Any) -> bool:
    return str(getattr(_v(m, "mediaType"), "value", _v(m, "mediaType")) or "").upper().endswith("IMAGE")


async def _sources(ids: list[str]) -> dict[str, Any]:
    from app.core.db import db

    if not ids:
        return {}
    try:
        rows = await db.mediafile.find_many(where={"id": {"in": ids}})
    except Exception:
        logger.warning("report thumbnails: media lookup failed", exc_info=True)
        return {}
    return {str(r.id): r for r in rows}


async def _generate(media: Any) -> bytes | None:
    from app.services.data_explorer import downloads

    try:
        made = await downloads.ensure_thumbnail(media, SOURCE_EDGE, wait=RENDER_WAIT)
    except Exception:  # noqa: BLE001 - a picture that cannot be thumbnailed is left out
        return None
    if made is not None:
        return made
    return await asyncio.to_thread(_fetch, downloads.thumbnail_key(str(media.id), SOURCE_EDGE))


async def collect(media: list[Any]) -> dict[str, bytes]:
    from app.services.data_explorer import downloads

    images = [m for m in media if _is_image(m)][:MAX_IMAGES]
    out: dict[str, bytes] = {}
    missing: list[str] = []
    total = 0
    for m in images:
        mid = str(_v(m, "id"))
        data = await asyncio.to_thread(_fetch, downloads.thumbnail_key(mid, SOURCE_EDGE))
        if not data:
            missing.append(mid)
            continue
        png = await asyncio.to_thread(to_png, data)
        if not png:
            continue
        if total + len(png) > MAX_BYTES:
            return out
        total += len(png)
        out[mid] = png
    sources = await _sources(missing[:MAX_RENDERS])
    deadline = time.monotonic() + RENDER_SECONDS
    for mid in missing[:MAX_RENDERS]:
        source = sources.get(mid)
        if source is None or not downloads.thumbnailable(source):
            continue
        if time.monotonic() > deadline:
            break
        data = await _generate(source)
        if not data:
            continue
        png = await asyncio.to_thread(to_png, data)
        if not png:
            continue
        if total + len(png) > MAX_BYTES:
            break
        total += len(png)
        out[mid] = png
    return out
