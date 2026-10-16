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

    def refund(self, identity: str) -> None:
        """Give one token back to a bucket that has already spent it.

        Only the credential path uses this: "charge the attempt, refund everything that was not a
        wrong password", because a check-then-charge two awaits apart would let a hundred parallel
        guesses all pass the check before any of them was counted. An evicted bucket is not
        resurrected, and its timestamp is left alone so the refill accrued since the take stands.
        """
        entry = self._buckets.get(identity)
        if entry is None:
            return
        tokens, last = entry
        self._buckets[identity] = (min(self._capacity, tokens + 1.0), last)

    def tracked(self) -> int:
        return len(self._buckets)


def _identity(scope: Scope, *, ignore_bearer: bool = False) -> str:
    """Who to charge this request to: the bearer token's digest, else the client address.

    ``ignore_bearer`` keys on the address alone — for an anonymous door (``forgot_password_attempt``),
    where a random Authorization header per request would otherwise mint a fresh bucket each time.

    The forwarded address is read left-most-first because that is where the original client sits in
    an ``X-Forwarded-For`` chain (browser -> CloudFront -> nginx -> here). It is also the only entry
    a client can set themselves, which is fine for a courtesy limit and would not be for anything
    else — see the module docstring.
    """
    forwarded = b""
    for name, value in scope.get("headers", []):
        if name == b"authorization" and not ignore_bearer:
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


#: "FORGOT YOUR PASSWORD?" PER CLIENT: five requests per fifteen minutes per network address.
#:
#: The route is anonymous and every request it accepts can end in an e-mail, so the general allowance
#: (and only when the scale flag is on) is no limit at all for it. NOT gated on the scale flag: it
#: guards an inbox, not a small box. The per-ADDRESS limit is a different thing and lives in
#: ``services/password_reset``, silently. Same names as the Design Prototype Workshop's.
FORGOT_PASSWORD_REQUESTS = 5
FORGOT_PASSWORD_WINDOW_SECONDS = 900.0

_forgot_password_buckets = _TokenBuckets(
    capacity=FORGOT_PASSWORD_REQUESTS,
    refill_per_second=FORGOT_PASSWORD_REQUESTS / FORGOT_PASSWORD_WINDOW_SECONDS,
)


#: "FORGOT YOUR PASSWORD?" FOR THE WHOLE PROCESS: thirty requests a minute, whoever sends them.
#:
#: The per-client bucket above is keyed on a network address, and an attacker with many addresses
#: meets each bucket fresh. Every request this door accepts can end in an e-mail, so the door as a
#: whole is capped too (security review, 2026-10-16). A 429 here says nothing about any account.
FORGOT_PASSWORD_GLOBAL_REQUESTS = 30
FORGOT_PASSWORD_GLOBAL_WINDOW_SECONDS = 60.0

_forgot_password_global = _TokenBuckets(
    capacity=FORGOT_PASSWORD_GLOBAL_REQUESTS,
    refill_per_second=FORGOT_PASSWORD_GLOBAL_REQUESTS / FORGOT_PASSWORD_GLOBAL_WINDOW_SECONDS,
)


def client_address(scope: Scope) -> str:
    """The network address a request came from, for a limit that must not be dodged by a header.

    NEVER THE LEFT-MOST ``X-Forwarded-For`` ENTRY (security review, 2026-10-16). That entry is
    whatever the client typed: ``X-Forwarded-For: 1.2.3.4`` sent by a script arrives here as
    ``1.2.3.4, <real address>``, so keying on it gave every request a fresh bucket. Each proxy we run
    APPENDS the address that connected to it, so the trustworthy entries are the right-most ones.
    ``TRUSTED_PROXY_HOPS`` (default 2: CloudFront, then the box's nginx) says how many proxies of ours
    append in front of the API, and the client is the entry the outermost of them wrote,
    ``entries[-hops]``. With fewer entries than that, the left-most was still written by a proxy of
    ours (nothing in front of it added one), so it is used. No header at all is a direct connection:
    the socket peer.

    Production is CloudFront -> nginx -> uvicorn, so 2 keys on the viewer address CloudFront appends:
    per person, rather than per CloudFront edge (which 1 would key on, and which every viewer that
    edge serves shares). It is unforgeable ONLY BECAUSE OF THE CLOUDFRONT LOCK: the box's security
    groups admit port 80 from CloudFront's origin-facing prefix list alone and port 443 not at all
    (checked live on 2026-10-17), so no request reaches nginx without passing CloudFront, and
    CloudFront always appends the address it saw. Without that lock a caller could talk to nginx
    directly and choose the entry 2 reads, so the deploy does not assume it: it tries both ports from
    the runner and pins 2 only when the box does not answer there, 1 otherwise. Any other deployment
    that is not locked must set ``TRUSTED_PROXY_HOPS=1`` (docs/SECURITY.md risk P1).
    """
    from app.core.config import get_settings

    hops = max(1, int(getattr(get_settings(), "trusted_proxy_hops", 2) or 2))
    forwarded = b""
    for name, value in scope.get("headers", []):
        if name == b"x-forwarded-for":
            # Several headers are one list, in order (RFC 9110 section 5.3).
            forwarded = value if not forwarded else forwarded + b"," + value
    entries = [part.strip().decode("latin-1") for part in forwarded.split(b",") if part.strip()]
    if not entries:
        client = scope.get("client")
        return f"ip:{client[0]}" if client else "ip:unknown"
    return "ip:" + (entries[-hops] if len(entries) >= hops else entries[0])


def forgot_password_attempt(scope: Scope) -> tuple[bool, float]:
    """Spend one "Forgot your password?" request from this client, then one from the whole door.
    ``(allowed, retry_after_seconds)``. The global bucket is charged only once the client's own
    allows the request, so one noisy client cannot spend the door's allowance on refused requests."""
    allowed, retry = _forgot_password_buckets.take(f"forgot:{client_address(scope)}")
    if not allowed:
        return allowed, retry
    return _forgot_password_global.take("forgot:all")


def reset_forgot_password_budget() -> None:
    """Forget every "Forgot your password?" bucket, the global one too. For the tests."""
    _forgot_password_buckets._buckets.clear()  # noqa: SLF001 - the module owns this object
    _forgot_password_global._buckets.clear()  # noqa: SLF001 - the module owns this object


# ── ONE BUCKET PER ACCOUNT FOR EVERY DOOR THAT CHECKS A PASSWORD (ported 2026-10-16) ───────────────
#
# The middleware above can only key on the network a request came from, and an attacker spread
# across many networks never meets it twice for one victim. This is the other half: ten wrong
# passwords per account per five minutes, shared by the sign-in and the change-password screen's
# "current password" (a stolen session would otherwise be an unlimited oracle against the one
# password that turns it into a permanent takeover). Charged before bcrypt, refunded when the
# password was right, so somebody typing their own password correctly never spends anything. Same
# names and numbers as the Design Prototype Workshop's.
_ACCOUNT_FAILURES = 10
_ACCOUNT_WINDOW_SECONDS = 300.0

_account_buckets = _TokenBuckets(
    capacity=_ACCOUNT_FAILURES,
    refill_per_second=_ACCOUNT_FAILURES / _ACCOUNT_WINDOW_SECONDS,
)


def account_credential_attempt(account_id: str) -> tuple[bool, float]:
    """Spend one guess against ``account_id``. Returns ``(allowed, retry_after_seconds)``."""
    return _account_buckets.take(f"acct:{account_id}")


def address_credential_attempt(address: str) -> tuple[bool, float]:
    """Spend one guess against a typed ``address`` that matches NO account (security review N1).

    Without it, an unknown address was never charged, so the eleventh wrong password answered 401
    for an address nobody holds and 429 for a real one — an enumeration oracle. The same buckets,
    the same capacity and refill, keyed on the normalised typed address instead of an account id: the
    two sequences are indistinguishable. Never refunded (no password can be right for no account).
    """
    return _account_buckets.take(f"addr:{address}")


def account_credential_refund(account_id: str) -> None:
    """Give the token back — called only after a password actually verified."""
    _account_buckets.refund(f"acct:{account_id}")


def reset_account_credential_budget() -> None:
    """Forget every account bucket. For the tests, which sign in wrongly more than ten times."""
    _account_buckets._buckets.clear()  # noqa: SLF001 - the module owns this object


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
                "detail": "Too many requests. Wait a moment and try again.",
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
