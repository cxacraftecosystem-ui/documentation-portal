import asyncio
from typing import Any, Coroutine, TypeVar

from app.core.config import get_settings

T = TypeVar("T")


def pool_width() -> int:
    return max(1, get_settings().database_connection_limit)


async def gather_reads(*coros: Coroutine[Any, Any, T], limit: int | None = None) -> list[T]:
    width = limit if limit is not None else pool_width()
    if width >= len(coros):
        return list(await asyncio.gather(*coros))

    semaphore = asyncio.Semaphore(width)

    async def run(coro: Coroutine[Any, Any, T]) -> T:
        async with semaphore:
            return await coro

    return list(await asyncio.gather(*(run(coro) for coro in coros)))
