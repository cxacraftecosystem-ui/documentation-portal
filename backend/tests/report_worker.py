from __future__ import annotations

import asyncio
import contextlib
import inspect
import tempfile
from collections.abc import AsyncIterator, Iterator
from pathlib import Path
from typing import Any

from app.services.datareport import jobs


@contextlib.asynccontextmanager
async def running_worker(users: dict[str, Any] | None = None, *, idle: float = 0.01) -> AsyncIterator[Path]:
    saved = (jobs.ROOT, jobs.POLL_SECONDS, jobs.load_user)
    where = Path(tempfile.mkdtemp(prefix="report-store-"))
    jobs.ROOT = where
    jobs.POLL_SECONDS = 0.01
    if users is not None:

        async def load(user_id: str) -> Any:
            return users.get(user_id)

        jobs.load_user = load
    stop = asyncio.Event()
    lane = asyncio.create_task(jobs.run_lane(stop, idle_seconds=idle))
    try:
        yield where
    finally:
        stop.set()
        with contextlib.suppress(asyncio.CancelledError, TimeoutError):
            await asyncio.wait_for(lane, 30)
        jobs.ROOT, jobs.POLL_SECONDS, jobs.load_user = saved


@contextlib.contextmanager
def store_only() -> Iterator[Path]:
    saved = (jobs.ROOT, jobs.POLL_SECONDS)
    where = Path(tempfile.mkdtemp(prefix="report-store-"))
    jobs.ROOT = where
    jobs.POLL_SECONDS = 0.01
    try:
        yield where
    finally:
        jobs.ROOT, jobs.POLL_SECONDS = saved


def body(response: Any) -> bytes:
    path = getattr(response, "path", None)
    if path:
        return Path(path).read_bytes()
    return bytes(response.body)


def report_kwargs(route: Any, **kwargs: Any) -> dict[str, Any]:
    if "status_value" in inspect.signature(route).parameters:
        kwargs.setdefault("status_value", None)
    return kwargs
