"""Request rate limiting. Flag-gated, and when the flag is off the middleware is never added.

"NOT INSTALLED" IS THE POINT. A disabled middleware that checks a boolean and calls the next app is
still a coroutine, a stack frame and a wrapper around every request the server ever handles,
forever, for nothing. ``install_rate_limit`` returns without touching the app when the flag is off,
so the disabled configuration has the middleware stack it has today — one fewer layer, not one more
that does nothing.

WHAT THIS IS FOR, AND WHAT IT IS NOT. It is a courtesy backstop for a 1 GiB box: a phone stuck in a
sync loop, a script left running overnight, an accidental ``while true`` against ``/search`` (8.9s
per call — twenty of those in parallel is the whole machine). It is NOT a security control. The
identity it limits by is derived from headers the origin cannot fully verify, and an attacker who
can reach the origin directly can vary them freely. Abuse defence belongs at CloudFront/WAF, where
the traffic can be dropped before it costs anything. Writing that down here so nobody later mistakes
a 429 in the logs for a boundary that is holding.

IDENTITY. A bearer token, when present, is hashed and used as the key, so one signed-in user gets one
allowance across their phone and their laptop, and so a whole office behind one NAT is not one
bucket. Anonymous callers fall back to the client IP. The token is never stored or logged — only a
truncated digest of it, which cannot be replayed.
"""

import hashlib
import time
from collections import OrderedDict
from typing import Any

from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.scale import keys
from app.scale.cache import shared_window_hit
from app.scale.flags import rate_limit_enabled, settings

# Distinct callers tracked in-process. Each entry is a key string and two floats; 4096 of them is a
# few hundred kilobytes. The bound is what stops a caller who varies their identity every request
# from turning the limiter into a memory leak — the very shape of abuse it exists to survive.
_MAX_TRACKED_IDENTITIES = 4096

# Never limited. Health probes come from CloudFront and an uptime monitor on a fixed cadence, and a
# 429 to either reads as an outage. OPTIONS is exempt at the method level below: a rate-limited CORS
# preflight breaks the web app completely — the browser reports a CORS failure, not a 429, and no
# request that would have been within the limit ever gets sent.
_EXEMPT_PREFIXES = ("/health",)


class _TokenBuckets:
    """One token bucket per identity, bounded in number, evicting the least recently seen.

    A token bucket rather than a fixed window because it allows a short burst — opening the
    dashboard fires several requests at once, and that is normal behaviour, not abuse — while still
    capping the sustained rate. No lock: every caller is the event loop.
    """

    def __init__(self, *, capacity: float, refill_per_second: float) -> None:
        self._capacity = max(1.0, capacity)
        self._refill = max(0.001, refill_per_second)
        self._buckets: OrderedDict[str, tuple[float, float]] = OrderedDict()

    def take(self, identity: str) -> tuple[bool, float]:
        """Spend one token. Returns ``(allowed, retry_after_seconds)``."""
        now = time.monotonic()
        tokens, last = self._buckets.get(identity, (self._capacity, now))
        tokens = min(self._capacity, tokens + (now - last) * self._refill)
        if tokens < 1.0:
            self._buckets[identity] = (tokens, now)
            self._buckets.move_to_end(identity)
            return False, (1.0 - tokens) / self._refill
        self._buckets[identity] = (tokens - 1.0, now)
        self._buckets.move_to_end(identity)
        while len(self._buckets) > _MAX_TRACKED_IDENTITIES:
            self._buckets.popitem(last=False)
        return True, 0.0

    def tracked(self) -> int:
        return len(self._buckets)


def _identity(scope: Scope) -> str:
    """Who to charge this request to: the bearer token's digest, else the client address.

    The forwarded address is read left-most-first because that is where the original client sits in
    an ``X-Forwarded-For`` chain (browser -> CloudFront -> nginx -> here). It is also the only entry
    a client can set themselves, which is fine for a courtesy limit and would not be for anything
    else — see the module docstring.
    """
    forwarded = b""
    for name, value in scope.get("headers", []):
        if name == b"authorization":
            token = value.strip()
            if token[:7].lower() == b"bearer " and len(token) > 7:
                return "t:" + hashlib.sha256(token[7:]).hexdigest()[:16]
        elif name == b"x-forwarded-for" and not forwarded:
            forwarded = value
    if forwarded:
        first = forwarded.split(b",")[0].strip()
        if first:
            return "ip:" + first.decode("latin-1")
    client = scope.get("client")
    return f"ip:{client[0]}" if client else "ip:unknown"


class RateLimitMiddleware:
    """Pure-ASGI token-bucket limiter. Installed only by ``install_rate_limit``.

    Written against raw ASGI, like the other middleware in this app, so it adds no task group and no
    body buffering — it either passes the request straight through or answers it, and does nothing
    to the response at all.
    """

    def __init__(self, app: ASGIApp, *, requests: int, window_seconds: float) -> None:
        self.app = app
        self.limit = max(1, requests)
        self.window = max(1.0, window_seconds)
        self._buckets = _TokenBuckets(
            capacity=self.limit, refill_per_second=self.limit / self.window
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method") == "OPTIONS":
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        if path.startswith(_EXEMPT_PREFIXES):
            await self.app(scope, receive, send)
            return

        identity = _identity(scope)
        # A shared counter when one exists, so two processes cannot each grant the full allowance.
        # None means there is no shared store (see cache.shared_window_hit) and the in-process
        # bucket is both the correct and the only answer.
        count = await shared_window_hit(keys.rate_limit_key(identity), self.window)
        if count is None:
            allowed, retry_after = self._buckets.take(identity)
        else:
            allowed = count <= self.limit
            retry_after = self.window

        if allowed:
            await self.app(scope, receive, send)
            return

        response = JSONResponse(
            status_code=429,
            content={
                "detail": (
                    "Too many requests. Wait a moment and try again — this limit exists to keep "
                    "the server responsive for everyone."
                ),
                "retryAfterSeconds": int(retry_after) + 1,
            },
            headers={
                "retry-after": str(int(retry_after) + 1),
                "x-ratelimit-limit": str(self.limit),
                "x-ratelimit-remaining": "0",
                # A 429 is about this instant, not about this URL. Anything that cached it would go
                # on serving the refusal after the allowance had refilled.
                "cache-control": "no-store",
            },
        )
        await response(scope, receive, send)


def install_rate_limit(app: Any) -> bool:
    """Add the limiter to ``app`` if it is enabled. Returns whether it was installed.

    WHERE TO CALL IT, in ``create_app``: immediately AFTER ``app.add_middleware(
    UnhandledErrorMiddleware)`` and BEFORE ``app.add_middleware(CORSMiddleware, ...)``.

    That position is load-bearing, not cosmetic. Starlette runs the most recently added middleware
    outermost, so adding it before CORS puts the limiter INSIDE the CORS layer, and a 429 travels
    back out through CORS and picks up ``access-control-allow-origin``. Installed outside CORS, the
    same 429 reaches the browser without that header, the fetch rejects, and the web app reports
    "Failed to fetch" — the exact confusion ``UnhandledErrorMiddleware`` was written to end. It also
    sits outside the router, so a refused request costs no route resolution and no database work.
    """
    if not rate_limit_enabled():
        return False
    current = settings()
    app.add_middleware(
        RateLimitMiddleware,
        requests=current.scale_rate_limit_requests,
        window_seconds=current.scale_rate_limit_window_seconds,
    )
    return True
