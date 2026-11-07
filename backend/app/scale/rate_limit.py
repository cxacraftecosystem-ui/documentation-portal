import hashlib
import hmac
import ipaddress
import time
from collections import OrderedDict
from typing import Any

from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.scale import keys
from app.scale.cache import shared_window_hit
from app.scale.flags import rate_limit_enabled, settings

_MAX_TRACKED_IDENTITIES = 4096

_EXEMPT_PREFIXES = ("/health",)


class _TokenBuckets:
    def __init__(self, *, capacity: float, refill_per_second: float) -> None:
        self._capacity = max(1.0, capacity)
        self._refill = max(0.001, refill_per_second)
        self._buckets: OrderedDict[str, tuple[float, float]] = OrderedDict()

    def take(self, identity: str) -> tuple[bool, float]:
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
        entry = self._buckets.get(identity)
        if entry is None:
            return
        tokens, last = entry
        self._buckets[identity] = (min(self._capacity, tokens + 1.0), last)

    def tracked(self) -> int:
        return len(self._buckets)


WEB_PROXY_SECRET_HEADER = b"x-web-proxy-secret"
VIEWER_ADDRESS_HEADER = b"x-viewer-address"


def trusted_viewer_address(scope: Scope) -> str | None:
    from app.core.config import get_settings

    secret = (getattr(get_settings(), "web_proxy_secret", None) or "").strip()
    if not secret:
        return None
    sent = None
    viewer = None
    for name, value in scope.get("headers", []):
        if name == WEB_PROXY_SECRET_HEADER and sent is None:
            sent = value
        elif name == VIEWER_ADDRESS_HEADER and viewer is None:
            viewer = value
    if sent is None or viewer is None:
        return None
    if not hmac.compare_digest(sent.strip(), secret.encode("utf-8")):
        return None
    try:
        return str(ipaddress.ip_address(viewer.strip().decode("latin-1")))
    except ValueError:
        return None


def _bearer_subject(token: bytes) -> str | None:
    from app.core.security import decode_access_token

    try:
        claims = decode_access_token(token.decode("latin-1"))
    except (ValueError, UnicodeDecodeError):
        return None
    subject = claims.get("sub")
    return str(subject) if subject else None


def _identity(scope: Scope, *, ignore_bearer: bool = False) -> str:
    if not ignore_bearer:
        for name, value in scope.get("headers", []):
            if name != b"authorization":
                continue
            token = value.strip()
            if token[:7].lower() == b"bearer " and len(token) > 7:
                subject = _bearer_subject(token[7:].strip())
                if subject:
                    return "u:" + hashlib.sha256(subject.encode("utf-8")).hexdigest()[:16]
            break
    return client_address(scope)


FORGOT_PASSWORD_REQUESTS = 5
FORGOT_PASSWORD_WINDOW_SECONDS = 900.0

_forgot_password_buckets = _TokenBuckets(
    capacity=FORGOT_PASSWORD_REQUESTS,
    refill_per_second=FORGOT_PASSWORD_REQUESTS / FORGOT_PASSWORD_WINDOW_SECONDS,
)


FORGOT_PASSWORD_GLOBAL_REQUESTS = 30
FORGOT_PASSWORD_GLOBAL_WINDOW_SECONDS = 60.0

_forgot_password_global = _TokenBuckets(
    capacity=FORGOT_PASSWORD_GLOBAL_REQUESTS,
    refill_per_second=FORGOT_PASSWORD_GLOBAL_REQUESTS / FORGOT_PASSWORD_GLOBAL_WINDOW_SECONDS,
)


def client_address(scope: Scope) -> str:
    from app.core.config import get_settings

    viewer = trusted_viewer_address(scope)
    if viewer:
        return "ip:" + viewer
    hops = max(1, int(getattr(get_settings(), "trusted_proxy_hops", 2) or 2))
    forwarded = b""
    for name, value in scope.get("headers", []):
        if name == b"x-forwarded-for":
            forwarded = value if not forwarded else forwarded + b"," + value
    entries = [part.strip().decode("latin-1") for part in forwarded.split(b",") if part.strip()]
    if not entries:
        client = scope.get("client")
        return f"ip:{client[0]}" if client else "ip:unknown"
    return "ip:" + (entries[-hops] if len(entries) >= hops else entries[0])


def forgot_password_attempt(scope: Scope) -> tuple[bool, float]:
    allowed, retry = _forgot_password_buckets.take(f"forgot:{client_address(scope)}")
    if not allowed:
        return allowed, retry
    return _forgot_password_global.take("forgot:all")


def reset_forgot_password_budget() -> None:
    _forgot_password_buckets._buckets.clear()  # noqa: SLF001
    _forgot_password_global._buckets.clear()  # noqa: SLF001


_ACCOUNT_FAILURES = 10
_ACCOUNT_WINDOW_SECONDS = 300.0

_account_buckets = _TokenBuckets(
    capacity=_ACCOUNT_FAILURES,
    refill_per_second=_ACCOUNT_FAILURES / _ACCOUNT_WINDOW_SECONDS,
)


def account_credential_attempt(account_id: str) -> tuple[bool, float]:
    return _account_buckets.take(f"acct:{account_id}")


def address_credential_attempt(address: str) -> tuple[bool, float]:
    return _account_buckets.take(f"addr:{address}")


def account_credential_refund(account_id: str) -> None:
    _account_buckets.refund(f"acct:{account_id}")


SOURCE_CREDENTIAL_FAILURES = 30
SOURCE_CREDENTIAL_WINDOW_SECONDS = 300.0

_source_buckets = _TokenBuckets(
    capacity=SOURCE_CREDENTIAL_FAILURES,
    refill_per_second=SOURCE_CREDENTIAL_FAILURES / SOURCE_CREDENTIAL_WINDOW_SECONDS,
)


def source_credential_attempt(scope: Scope) -> tuple[bool, float]:
    return _source_buckets.take(f"src:{client_address(scope)}")


def source_credential_refund(scope: Scope) -> None:
    _source_buckets.refund(f"src:{client_address(scope)}")


def reset_account_credential_budget() -> None:
    _account_buckets._buckets.clear()  # noqa: SLF001
    _source_buckets._buckets.clear()  # noqa: SLF001


class RateLimitMiddleware:
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
                "cache-control": "no-store",
            },
        )
        await response(scope, receive, send)


def install_rate_limit(app: Any) -> bool:
    if not rate_limit_enabled():
        return False
    current = settings()
    app.add_middleware(
        RateLimitMiddleware,
        requests=current.scale_rate_limit_requests,
        window_seconds=current.scale_rate_limit_window_seconds,
    )
    return True
