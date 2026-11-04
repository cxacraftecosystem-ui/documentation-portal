from __future__ import annotations

import threading
import time
from collections import OrderedDict
from collections.abc import Callable, Hashable
from typing import Any

EXPIRES_IN_SECONDS = 3600
REUSE_SECONDS = 1800
CACHE_ENTRIES = 4096

_lock = threading.Lock()
_cache: OrderedDict[Hashable, tuple[str, float, int]] = OrderedDict()


def reuse_window(expires_in: int) -> int:
    return max(1, min(REUSE_SECONDS, int(expires_in) // 2))


def cached(
    key: Hashable, expires_in: int, sign: Callable[[], str], *, now: float | None = None
) -> tuple[str, int]:
    moment = time.monotonic() if now is None else now
    window = reuse_window(expires_in)
    with _lock:
        hit = _cache.get(key)
        if hit is not None and moment - hit[1] < hit[2]:
            _cache.move_to_end(key)
            return hit[0], max(0, int(hit[2] - (moment - hit[1])))
    url = sign()
    with _lock:
        _cache[key] = (url, moment, window)
        _cache.move_to_end(key)
        while len(_cache) > CACHE_ENTRIES:
            _cache.popitem(last=False)
    return url, window


def signed_get(
    object_key: str,
    *,
    filename: str,
    mime_type: str,
    inline: bool = False,
    expires_in: int = EXPIRES_IN_SECONDS,
) -> tuple[str, int]:
    from app.services import s3

    return s3.presign_get_url_aged(
        object_key,
        filename=filename,
        mime_type=mime_type or "application/octet-stream",
        expires_in=expires_in,
        disposition="inline" if inline else "attachment",
    )


def forget(object_key: str | None = None) -> None:
    with _lock:
        if object_key is None:
            _cache.clear()
            return
        for key in [k for k in _cache if isinstance(k, tuple) and k and k[0] == object_key]:
            _cache.pop(key, None)


def size() -> int:
    with _lock:
        return len(_cache)


def redirect_headers(max_age: int) -> dict[str, Any]:
    return {"Cache-Control": f"private, max-age={max(0, int(max_age))}"}
