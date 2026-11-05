from __future__ import annotations

import asyncio
import io
import logging
from typing import Any

logger = logging.getLogger(__name__)

MAX_IMAGES = 150
MAX_BYTES = 6 * 1024 * 1024
EDGE = 96
SOURCE_EDGE = 256
SOURCE_MAX = 2 * 1024 * 1024


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


async def collect(media: list[Any]) -> dict[str, bytes]:
    from app.services.data_explorer import downloads

    images = [m for m in media if str(getattr(_v(m, "mediaType"), "value", _v(m, "mediaType")) or "").upper().endswith("IMAGE")][:MAX_IMAGES]
    out: dict[str, bytes] = {}
    total = 0
    for m in images:
        mid = str(_v(m, "id"))
        data = await asyncio.to_thread(_fetch, downloads.thumbnail_key(mid, SOURCE_EDGE))
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
