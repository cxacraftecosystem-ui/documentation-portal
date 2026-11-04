from __future__ import annotations

import asyncio
import logging
import time
from collections import deque
from datetime import UTC, datetime, timedelta
from typing import Any

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.db import db

logger = logging.getLogger(__name__)

CLIENT_APP_HEADER = b"x-client-app"
CLIENT_APPS = frozenset({"web", "android", "api"})
DEFAULT_CLIENT_APP = "api"
UNMATCHED_ROUTE = "<unmatched>"
UNRECORDED = frozenset({"/health", "/health/ready"})

BUFFER_CEILING = 5000
FLUSH_ROWS = 500
FLUSH_INTERVAL_SECONDS = 15.0
RETENTION_DAYS = 30
PURGE_INTERVAL_SECONDS = 6 * 3600.0

_buffer: deque[dict[str, Any]] = deque(maxlen=BUFFER_CEILING)
_dropped = {"count": 0}
_last_purge = {"at": float("-inf")}


def client_app(scope: Scope) -> str:
    for name, value in scope.get("headers") or ():
        if name == CLIENT_APP_HEADER:
            picked = value.decode("latin-1").strip().lower()
            return picked if picked in CLIENT_APPS else DEFAULT_CLIENT_APP
    return DEFAULT_CLIENT_APP


def record(*, route: str, method: str, status_code: int, duration_ms: int, app_name: str) -> None:
    if route in UNRECORDED:
        return
    if len(_buffer) >= BUFFER_CEILING:
        _dropped["count"] += 1
    _buffer.append(
        {
            "routeTemplate": route[:300],
            "method": method[:10],
            "statusCode": int(status_code),
            "durationMs": max(0, int(duration_ms)),
            "clientApp": app_name,
        }
    )


def pending() -> int:
    return len(_buffer)


async def flush() -> int:
    rows: list[dict[str, Any]] = []
    while _buffer and len(rows) < FLUSH_ROWS:
        rows.append(_buffer.popleft())
    if not rows:
        return 0
    try:
        await db.usageevent.create_many(data=rows)
    except Exception:  # noqa: BLE001
        logger.warning("usage log: %d timings were not kept", len(rows), exc_info=True)
        return 0
    return len(rows)


async def flush_all() -> int:
    total = 0
    while _buffer:
        written = await flush()
        if not written:
            break
        total += written
    return total


async def purge(now: datetime | None = None) -> int:
    moment = time.monotonic()
    if moment - _last_purge["at"] < PURGE_INTERVAL_SECONDS:
        return 0
    _last_purge["at"] = moment
    cutoff = (now or datetime.now(UTC)) - timedelta(days=RETENTION_DAYS)
    return int(await db.usageevent.delete_many(where={"createdAt": {"lt": cutoff}}))


async def run_flush_worker() -> None:
    while True:
        await asyncio.sleep(FLUSH_INTERVAL_SECONDS)
        try:
            await flush_all()
            await purge()
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            logger.warning("usage log pass failed", exc_info=True)


class TimingMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        started = time.perf_counter()
        seen = {"status": 500}

        async def tracked(message: Message) -> None:
            if message["type"] == "http.response.start":
                seen["status"] = int(message["status"])
            await send(message)

        try:
            await self.app(scope, receive, tracked)
        finally:
            route = getattr(scope.get("route"), "path_format", None) or UNMATCHED_ROUTE
            record(
                route=str(route),
                method=str(scope.get("method") or ""),
                status_code=seen["status"],
                duration_ms=int((time.perf_counter() - started) * 1000),
                app_name=client_app(scope),
            )
