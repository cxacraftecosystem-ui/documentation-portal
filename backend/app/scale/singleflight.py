import asyncio
from typing import Any, Awaitable, Callable

from app.scale.flags import log_once


class _LeaderGone(Exception):
    pass


class SingleFlight:
    def __init__(self) -> None:
        self._inflight: dict[str, asyncio.Future[Any]] = {}

    def inflight(self) -> int:
        return len(self._inflight)

    async def run(
        self,
        key: str,
        loader: Callable[[], Awaitable[Any]],
        *,
        timeout: float,
    ) -> Any:
        existing = self._inflight.get(key)
        if existing is not None:
            try:
                return await asyncio.wait_for(asyncio.shield(existing), timeout)
            except _LeaderGone:
                return await loader()
            except TimeoutError:
                log_once(
                    "singleflight.timeout",
                    "Cache single-flight waited %.1fs on namespace %s and gave up; running the "
                    "query directly. Raise SCALE_CACHE_SINGLEFLIGHT_TIMEOUT_SECONDS if the "
                    "underlying query is legitimately this slow.",
                    timeout,
                    _namespace_of(key),
                )
                return await loader()

        loop = asyncio.get_running_loop()
        future: asyncio.Future[Any] = loop.create_future()
        future.add_done_callback(lambda done: done.cancelled() or done.exception())
        self._inflight[key] = future
        try:
            result = await loader()
        except asyncio.CancelledError:
            self._inflight.pop(key, None)
            if not future.done():
                future.set_exception(_LeaderGone())
            raise
        except BaseException as exc:
            self._inflight.pop(key, None)
            if not future.done():
                future.set_exception(exc)
            raise
        self._inflight.pop(key, None)
        if not future.done():
            future.set_result(result)
        return result


def _namespace_of(key: str) -> str:
    parts = key.split(":")
    return parts[2] if len(parts) > 2 else key
