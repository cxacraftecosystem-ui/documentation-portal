"""The opt-in shared backend. NOTHING here imports ``redis`` at module scope — read on.

THE RULE THIS FILE EXISTS TO OBEY. ``redis`` is an optional dependency (the ``cache`` extra in
pyproject.toml), so on every machine that has not installed it — which is all of them today — a
module-level ``import redis`` would raise ImportError the moment anything imported this file, and
the API would not boot. Importing inside ``create_redis_backend`` means the package is touched only
after an operator has set SCALE_CACHE_BACKEND=redis, and even then a missing package is one log
line and a fall back to the in-process cache. The client object is therefore typed ``Any``
throughout: annotating it properly would require the import this file must not have.

WHEN IS REDIS THE RIGHT ANSWER HERE. Only when more than one process must share an invalidation.
Today the API is one uvicorn web worker on one box, so the memory backend is not a compromise — it
is strictly better, since it has no serialisation, no socket and no service to be down. Redis earns
its place the day a second web process appears, because two memory caches cannot invalidate each
other and a generation counter that lives in only one of them is not a generation counter.

FAILURE BEHAVIOUR, in one sentence: every call is wrapped, a failure is a miss, and after three
consecutive failures this process stops calling Redis entirely. The alternative — retrying every
request — turns an unreachable cache into a per-request timeout, i.e. makes the endpoint slower than
having no cache at all, which is the one outcome a performance layer may never produce.
"""

import asyncio
from typing import Any

from app.scale.flags import log_once

# Consecutive failures after which this process gives up on Redis until it restarts. Three rather
# than one so a single dropped connection (a deploy of the cache, a network blip) is absorbed by the
# client's own reconnect; and terminal rather than time-boxed because a cache that flaps in and out
# produces exactly the intermittent staleness that is hardest to explain to whoever reports it.
_FAILURES_BEFORE_OFFLINE = 3


class RedisBackend:
    """Shared cache over Redis. Interchangeable with ``MemoryBackend`` — see ``backends.py``."""

    name = "redis"

    def __init__(self, client: Any, *, timeout_seconds: float) -> None:
        self._client = client
        self._timeout = timeout_seconds
        self._failures = 0
        self._offline = False
        self.errors = 0

    # --- failure containment ----------------------------------------------------------------

    async def _call(self, what: str, coro: Any) -> Any:
        """Await a Redis operation with a deadline, or return None and count the failure.

        The deadline is belt and braces: redis-py already carries socket timeouts, but a client that
        is mid-reconnect can spend longer than either of them inside its own retry logic, and this
        wrapper is what guarantees the request above it is never held hostage by the cache.
        """
        if self._offline:
            coro.close()  # never created a socket; closing keeps "coroutine was never awaited" quiet
            return None
        try:
            async with asyncio.timeout(self._timeout):
                result = await coro
        except asyncio.CancelledError:
            # The REQUEST was cancelled, not Redis. Counting it as a Redis failure would let a few
            # impatient clients disable the cache for everyone.
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

    # --- entries ------------------------------------------------------------------------------

    async def get(self, key: str) -> bytes | None:
        value = await self._call("get", self._client.get(key))
        return value if isinstance(value, (bytes, bytearray)) else None

    async def set(self, key: str, payload: bytes, ttl_seconds: float) -> None:
        # px, not ex: sub-second TTLs are legitimate here (a hot endpoint under a stampede) and ex
        # would silently round them to zero, which Redis rejects as an invalid expiry.
        await self._call("set", self._client.set(key, payload, px=max(1, int(ttl_seconds * 1000))))

    async def drop_prefix(self, prefix: str) -> int:
        """Deliberately does nothing. Generations already made these keys unreachable.

        The only way to enumerate them is SCAN, which walks the whole keyspace — a shared Redis may
        hold keys that are none of this application's business — and races every concurrent write.
        The entries are unreachable the instant their family's generation moves, and they expire on
        their own short TTL; paying an O(keyspace) sweep to reclaim bytes that Redis's own eviction
        policy already handles would be trading a real cost for an imaginary one.
        """
        return 0

    # --- generation counters --------------------------------------------------------------------

    async def counter(self, name: str) -> int:
        value = await self._call("counter", self._client.get(name))
        if value is None:
            return 0
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    async def increment(self, name: str) -> int:
        """INCR: atomic across every process, which is the whole reason to run a shared backend.

        A failure returns 0, and 0 is the "no such counter" value — so a failed invalidation reads
        as "generation unchanged" and the stale entries survive until their TTL. That is the correct
        way to lose: the alternative (guessing a new generation locally) would have two processes
        disagreeing about which generation is current, and a key minted under the wrong one is
        served to somebody.
        """
        value = await self._call("increment", self._client.incr(name))
        return int(value) if value is not None else 0

    # --- rate limiting ----------------------------------------------------------------------------

    async def window_hit(self, key: str, window_seconds: float) -> int | None:
        """Count one request in a shared fixed window; None when Redis could not answer.

        A fixed window rather than a token bucket because INCR+EXPIRE needs no Lua, no round trip to
        read state first, and no clock agreement between processes. Its known flaw is the boundary:
        a client can spend its whole allowance at the end of one window and again at the start of
        the next. For a courtesy limit protecting a small box from a runaway script that is an
        acceptable inaccuracy; it is not a security control (see rate_limit.py on why nothing here
        is).
        """
        count = await self._call("window_hit", self._client.incr(key))
        if count is None:
            return None
        if count == 1:
            # Only the request that created the window sets its lifetime, so a busy window is never
            # extended by later hits — that would let sustained traffic keep one window alive
            # forever and turn the limit into a permanent lockout.
            await self._call("window_expire", self._client.pexpire(key, int(window_seconds * 1000)))
        return int(count)

    # --- lifecycle --------------------------------------------------------------------------------

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
    """Connect to Redis and prove it answers, or return None so the caller can fall back.

    The PING is not ceremony. ``from_url`` is lazy — it constructs a pool and opens nothing — so
    without a probe a misconfigured URL would be discovered one request at a time, by whoever
    happened to hit the endpoint, instead of once at the first cached call with a log line naming
    the problem.
    """
    try:
        import redis.asyncio as redis_asyncio  # imported ONLY here: optional dependency, see docstring
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
            # Bytes in, bytes out. The cache stores encoded JSON and the decode belongs to the
            # facade, which knows what it encoded; letting the client guess an encoding would put a
            # second, invisible codec between the route and its own response.
            decode_responses=False,
            # A small pool: the cache is called once or twice per request and each call is
            # sub-millisecond, so concurrency here is bounded by the request concurrency of one
            # small box, not by anything Redis needs.
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
