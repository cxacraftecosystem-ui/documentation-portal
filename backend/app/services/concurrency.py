"""Running several independent database reads at once, without oversubscribing the pool.

WHY THIS EXISTS. The database sits in a different AWS region from the web box, and a single Prisma
round trip measured 756ms from a warm client — against tables whose server-side execution time is
0.04–0.24ms. Virtually the entire cost of a request is therefore latency, paid once per query, and
the only number that moves a page is HOW MANY QUERIES RUN ONE AFTER ANOTHER. ``/dashboard/stats``
issued fourteen sequential reads and took 10.1s; the same fourteen gathered took 1.6s.

WHY IT IS BOUNDED. A bare ``asyncio.gather`` would let one request hold every connection in the
pool, and this codebase has been bitten by pooler exhaustion before — twice. The bound keeps a
single request from asking for more connections than the pool actually has, so surplus reads queue
in a semaphore we control instead of inside the query engine. Measured at 4, 8 and 16 simultaneous
dashboard requests, gathering never produced a connection error and stayed at or ahead of the
sequential version, so the bound is insurance rather than a throttle on the common case.
"""

import asyncio
from typing import Any, Coroutine, TypeVar

from app.core.config import get_settings

T = TypeVar("T")


def pool_width() -> int:
    """How many reads may be in flight at once: the configured Prisma pool, never below one.

    Read from settings rather than hard-coded because the pool size is the thing that actually
    constrains us — if someone lowers ``DATABASE_CONNECTION_LIMIT`` the gather has to narrow with
    it, or we are back to queueing inside the engine where we cannot see it.
    """
    return max(1, get_settings().database_connection_limit)


async def gather_reads(*coros: Coroutine[Any, Any, T], limit: int | None = None) -> list[T]:
    """Await independent read coroutines concurrently, at most ``limit`` in flight.

    Results come back positionally, so callers can unpack them exactly as they would have read
    them in sequence — which is what keeps a converted route diffable against the one it replaced.

    Only for reads that do not depend on one another. Anything ordered, or anything inside a
    transaction, must stay sequential.
    """
    width = limit if limit is not None else pool_width()
    if width >= len(coros):
        return list(await asyncio.gather(*coros))

    semaphore = asyncio.Semaphore(width)

    async def run(coro: Coroutine[Any, Any, T]) -> T:
        async with semaphore:
            return await coro

    return list(await asyncio.gather(*(run(coro) for coro in coros)))
