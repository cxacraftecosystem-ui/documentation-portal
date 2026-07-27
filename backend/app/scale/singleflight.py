"""Single-flight: one in-flight computation per key, however many callers ask for it.

THE FAILURE THIS PREVENTS. A cached list expires. The next N concurrent requests all miss, all run
the same query, and all write the same answer back — so the moment a hot key expires the database
sees a burst of identical work. That is a cache stampede, and it is worst exactly where caching
helps most: the endpoints slow enough that many requests overlap inside one query's duration.
``/dashboard/stats`` measured 10.5s live; ten overlapping refreshes of it are ten times the work
for one answer.

So the first caller to miss becomes the leader and runs the loader; everyone else awaits the
leader's result. Per process, which is the right scope — the shared-Redis alternative needs a
distributed lock, and a distributed lock that can be lost or expire early is a new class of bug in
exchange for saving a query on a second worker.

NEVER HANG A REQUEST, AND NEVER FAIL ONE ON SOMEONE ELSE'S ACCOUNT. Followers wait with a timeout:
a leader stuck on a slow query must not turn one slow request into N hung ones, so on timeout the
follower runs the loader itself — exactly what it would have done with no stampede guard at all.
Followers shield the shared future, so a client that disconnects mid-wait cancels only its own
wait. And if the LEADER's client disconnects, its followers are handed a sentinel rather than the
leader's cancellation: one abandoned browser tab must not cancel the requests that were waiting
behind it.
"""

import asyncio
from typing import Any, Awaitable, Callable

from app.scale.flags import log_once


class _LeaderGone(Exception):
    """The leader's request was cancelled before it produced a value; followers should reload."""


class SingleFlight:
    """Deduplicates concurrent loads by key, within one process."""

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
                # shield: this waiter's own timeout/cancellation must not cancel the shared work.
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
        # Retrieve the outcome on completion so a leader whose followers all went away cannot leave
        # an "exception was never retrieved" warning behind. The leader still raises for itself.
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
    """The namespace segment of a cache key, for a log line that names the endpoint, not the user."""
    parts = key.split(":")
    return parts[2] if len(parts) > 2 else key
