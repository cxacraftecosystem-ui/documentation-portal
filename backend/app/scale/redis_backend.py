import asyncio
from typing import Any

from app.scale.flags import log_once

_FAILURES_BEFORE_OFFLINE = 3


class RedisBackend:
    name = "redis"

    def __init__(self, client: Any, *, timeout_seconds: float) -> None:
        self._client = client
        self._timeout = timeout_seconds
        self._failures = 0
        self._offline = False
        self.errors = 0

    async def _call(self, what: str, coro: Any) -> Any:
        if self._offline:
            coro.close()
            return None
        try:
            async with asyncio.timeout(self._timeout):
                result = await coro
        except asyncio.CancelledError:
            raise
        except BaseException as exc:  # noqa: BLE001 - a cache may never propagate its own failure
            self.errors += 1
            self._failures += 1
            log_once(
                f"redis.{what}.{type(exc).__name__}",
                "Redis cache %s failed (%s: %s). Requests are being served from the database "
                "instead. After %s consecutive failures this process stops calling Redis until it "
                "restarts.",
                what,
                type(exc).__name__,
                exc,
                _FAILURES_BEFORE_OFFLINE,
            )
            if self._failures >= _FAILURES_BEFORE_OFFLINE and not self._offline:
                self._offline = True
                log_once(
                    "redis.offline",
                    "Redis cache disabled for this process after %s consecutive failures; every "
                    "request now goes to the database, exactly as it does with the cache off. "
                    "Restart the service once Redis is reachable again.",
                    _FAILURES_BEFORE_OFFLINE,
                )
            return None
        self._failures = 0
        return result

    async def get(self, key: str) -> bytes | None:
        value = await self._call("get", self._client.get(key))
        return value if isinstance(value, (bytes, bytearray)) else None

    async def set(self, key: str, payload: bytes, ttl_seconds: float) -> None:
        await self._call("set", self._client.set(key, payload, px=max(1, int(ttl_seconds * 1000))))

    async def drop_prefix(self, prefix: str) -> int:
        return 0

    async def counter(self, name: str) -> int:
        value = await self._call("counter", self._client.get(name))
        if value is None:
            return 0
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    async def increment(self, name: str) -> int:
        value = await self._call("increment", self._client.incr(name))
        return int(value) if value is not None else 0

    async def window_hit(self, key: str, window_seconds: float) -> int | None:
        count = await self._call("window_hit", self._client.incr(key))
        if count is None:
            return None
        if count == 1:
            await self._call("window_expire", self._client.pexpire(key, int(window_seconds * 1000)))
        return int(count)

    async def stats(self) -> dict[str, object]:
        return {
            "backend": "redis",
            "offline": self._offline,
            "errors": self.errors,
            "timeoutSeconds": self._timeout,
        }

    async def close(self) -> None:
        try:
            await self._client.aclose()
        except Exception:  # noqa: BLE001 - shutdown must not fail because a cache would not close
            pass


async def create_redis_backend(url: str, *, timeout_seconds: float) -> RedisBackend | None:
    try:
        import redis.asyncio as redis_asyncio
    except ImportError:
        log_once(
            "redis.import",
            "SCALE_CACHE_BACKEND=redis but the redis package is not installed. Falling back to the "
            "in-process cache. Install it with: pip install -e '.[cache]'",
        )
        return None

    try:
        client = redis_asyncio.from_url(
            url,
            socket_connect_timeout=timeout_seconds,
            socket_timeout=timeout_seconds,
            decode_responses=False,
            max_connections=8,
        )
        async with asyncio.timeout(timeout_seconds * 2):
            await client.ping()
    except Exception as exc:  # noqa: BLE001 - an unreachable cache is a degradation, not an outage
        log_once(
            f"redis.connect.{type(exc).__name__}",
            "Could not reach the Redis cache (%s: %s). Falling back to the in-process cache. "
            "Check SCALE_REDIS_URL.",
            type(exc).__name__,
            exc,
        )
        return None

    return RedisBackend(client, timeout_seconds=timeout_seconds)
