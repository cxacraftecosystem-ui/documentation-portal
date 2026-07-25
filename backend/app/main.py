import asyncio
import logging
import os
import tempfile
from contextlib import asynccontextmanager, suppress
from typing import Any

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.api.router import api_router
from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.core.security import verify_jwt_configuration
from app.services.media_queue import process_next_media_jobs

logger = logging.getLogger(__name__)

# A single, host-wide lock file used to elect ONE media-queue worker across all uvicorn worker
# processes. The transcription/measurement jobs run ffmpeg + AI calls and read whole media files into
# memory; letting every uvicorn worker drain the queue in parallel saturated the small EC2 box's CPU
# and RAM, which made ordinary API requests (presign, complete, …) slow enough that CloudFront's
# origin-response timeout fired and clients saw HTTP 504. Electing one worker keeps the others free to
# serve requests promptly.
_QUEUE_LOCK_PATH = os.path.join(tempfile.gettempdir(), "fieldrepo-media-queue.lock")


def _acquire_queue_worker_lock() -> Any | None:
    """Try to become THE media-queue worker for this host. Returns a held lock handle on success, or
    None if another process already holds it. Uses an OS advisory file lock (fcntl) where available;
    on platforms without fcntl (e.g. local Windows dev, which runs a single worker anyway) it simply
    grants the lock so the queue still runs."""
    try:
        import fcntl  # POSIX only (the EC2 host); absent on Windows dev boxes.
    except ImportError:
        return object()  # No multi-worker contention to arbitrate — run the queue here.
    try:
        handle = open(_QUEUE_LOCK_PATH, "w")  # noqa: SIM115 - kept open for the process lifetime
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        handle.write(str(os.getpid()))
        handle.flush()
        return handle
    except OSError:
        return None


# How often the watchdog probes a healthy connection. Cheap (one SELECT 1 through the transaction
# pooler) and fast enough that a dead connection is noticed well before users hit repeated 500s.
_DB_PROBE_INTERVAL_SECONDS = 15.0


async def _keep_db_connected() -> None:
    """Background DB watchdog: probe the connection forever, and reconnect whenever it is broken.

    It runs for the app's WHOLE lifetime — not just after a failed startup connect — because a
    connection that was healthy at startup can still die later (pooler restart, network blip), and
    without a watchdog the app would serve HTTP 500s until systemd restarted it. While the probe
    succeeds it does nothing but sleep, so the healthy path costs one SELECT 1 per interval.

    The reconnect path is the recovery for a Supabase transaction pooler momentarily at its
    200 client-connection ceiling. It must NEVER let the process exit: systemd restarts a
    dead uvicorn in seconds, and each restart spawns fresh query-engine connections, which
    amplifies a brief pooler spike into a self-sustaining storm that keeps the pooler full
    (the exact failure that took the API down twice). Staying alive and retrying gently —
    one connection attempt at a time — lets the pooler drain and the app self-heal with no
    restart. ``/health`` keeps returning 200 throughout (it does not touch the DB), so the
    box stays a healthy CloudFront origin while it waits.

    Why it disconnects first and probes with ``SELECT 1``: the Prisma client keeps its engine
    reference even when ``connect()`` *raised*, so ``is_connected()`` can read ``True`` while the
    engine is actually unusable. A naive ``while not db.is_connected()`` loop would then exit
    immediately and declare success without ever reconnecting. Disconnecting clears any such
    half-initialized engine, and the probe proves the link really works before we stop retrying.
    """
    delay = 2.0
    while True:
        try:
            await db.query_raw("SELECT 1")  # prove the link works; is_connected() alone can lie
            delay = 2.0
            await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
            continue
        except Exception as exc:  # noqa: BLE001 - a failed probe MAY mean the connection is broken
            # Never tear down a live engine on a false positive. P2024 means OUR pool is
            # momentarily saturated by real load — the engine is fine; reconnecting would kill
            # every in-flight query. Anything else gets one confirming probe before the
            # destructive disconnect, so a single transient blip can't cause a teardown.
            if getattr(exc, "code", None) == "P2024":
                logger.warning("DB probe pool-timeout (P2024); pool saturated by load, not reconnecting")
                await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
                continue
            await asyncio.sleep(2.0)
            try:
                await db.query_raw("SELECT 1")
                continue  # transient blip — the connection is actually fine
            except Exception as exc2:  # noqa: BLE001
                logger.warning("DB health probe failed twice: %s — reconnecting in the background", exc2)
        try:
            with suppress(Exception):
                await db.disconnect()  # tear down any half-initialized engine before reconnecting
            await db.connect()
            await db.query_raw("SELECT 1")
            logger.info("Database connected (background reconnect succeeded)")
            delay = 2.0
        except Exception as exc:  # noqa: BLE001 - any connect failure should back off, not crash
            logger.warning("Background DB reconnect failed: %s — retrying in %.0fs", exc, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        await connect_db()
    except Exception as exc:  # noqa: BLE001 - never crash-loop on a full pooler; recover in background
        logger.error(
            "Initial DB connect failed (%s); starting anyway and reconnecting in the background "
            "so a saturated pooler cannot crash-loop the service",
            exc,
        )
    # The watchdog runs for the app's whole life (its probe only ACTS when the connection is broken):
    # it both finishes a failed startup connect and heals a connection that dies later.
    db_reconnect_task: asyncio.Task[None] = asyncio.create_task(_keep_db_connected())
    settings = get_settings()
    queue_task: asyncio.Task[None] | None = None
    queue_lock: Any | None = None
    if settings.media_queue_worker_enabled:
        queue_lock = _acquire_queue_worker_lock()
        if queue_lock is not None:
            logger.info("Media queue worker elected in pid %s", os.getpid())
            queue_task = asyncio.create_task(_media_queue_worker())
            app.state.media_queue_task = queue_task
            app.state.media_queue_lock = queue_lock
        else:
            logger.info("Media queue worker already running elsewhere; pid %s serves requests only", os.getpid())
    try:
        yield
    finally:
        if db_reconnect_task:
            db_reconnect_task.cancel()
            with suppress(asyncio.CancelledError):
                await db_reconnect_task
        if queue_task:
            queue_task.cancel()
            with suppress(asyncio.CancelledError):
                await queue_task
        if queue_lock is not None and hasattr(queue_lock, "close"):
            with suppress(Exception):
                queue_lock.close()
        await disconnect_db()


async def _media_queue_worker() -> None:
    settings = get_settings()
    interval = max(settings.media_queue_interval_seconds, 1.0)
    while True:
        try:
            await process_next_media_jobs(
                limit=settings.media_queue_batch_size,
                worker_id="fastapi-background",
                settings=settings,
            )
        except Exception:
            logger.exception("Media processing queue worker failed")
        await asyncio.sleep(interval)


# --- Security response headers ------------------------------------------------------------------
# Stamped on every response. Header names are lower-case bytes because that is exactly the shape the
# ASGI `http.response.start` message carries — no per-request encoding work.
#
# X-Content-Type-Options   stops a browser from sniffing a JSON error body into HTML/JS and running it.
# X-Frame-Options          legacy clickjacking defence (CSP frame-ancestors below is the modern one,
#                          but older browsers only understand this).
# Referrer-Policy          keeps record ids / query strings out of the Referer header sent to third
#                          parties (S3, MapTiler, Google) when a link is followed.
# Permissions-Policy       a JSON API never needs camera/mic/geolocation, so every powerful feature is
#                          denied for any document that somehow ends up scoped to this origin.
# X-Permitted-…            blocks Adobe/Flash-era cross-domain policy files being honoured on the host.
_BASE_SECURITY_HEADERS: tuple[tuple[bytes, bytes], ...] = (
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"strict-origin-when-cross-origin"),
    (
        b"permissions-policy",
        b"accelerometer=(), autoplay=(), camera=(), display-capture=(), encrypted-media=(), "
        b"fullscreen=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), midi=(), "
        b"payment=(), picture-in-picture=(), screen-wake-lock=(), usb=(), xr-spatial-tracking=()",
    ),
    (b"x-permitted-cross-domain-policies", b"none"),
)

# CSP for the API itself: it returns JSON, so nothing may load, and nothing may frame it. This is
# the header that actually protects the *browser* if a response is ever rendered as a document
# (e.g. a stored-XSS attempt inside a transcript that a browser is tricked into treating as HTML).
_API_CSP = b"default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

# /docs and /redoc ARE real HTML pages, and FastAPI loads Swagger-UI / ReDoc from jsdelivr with an
# inline bootstrap script. They get a policy that permits exactly those assets and nothing else, so
# the strict API policy above doesn't silently break the interactive documentation. ReDoc's page
# additionally pulls Montserrat/Roboto from Google Fonts (a stylesheet on fonts.googleapis.com whose
# @font-face rules fetch from fonts.gstatic.com), so both hosts are allowed for styles/fonts only —
# without them /redoc renders in fallback fonts and logs a CSP violation on every load.
_DOCS_CSP = (
    b"default-src 'none'; "
    b"script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; "
    b"style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com 'unsafe-inline'; "
    b"img-src 'self' https://fastapi.tiangolo.com data:; "
    b"font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com; "
    b"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
)
_DOCS_PATHS = frozenset({"/docs", "/docs/oauth2-redirect", "/redoc"})

# Proxy headers that report the protocol the *viewer* used. CloudFront sets its own
# CloudFront-Forwarded-Proto; nginx sets X-Forwarded-Proto (to its own scheme, which is why
# SECURITY_FORCE_HSTS exists for the CloudFront -> nginx shape).
_FORWARDED_PROTO_HEADERS = frozenset({b"x-forwarded-proto", b"cloudfront-forwarded-proto", b"x-forwarded-scheme"})


def _request_is_https(scope: Scope) -> bool:
    """Whether this request reached the user over TLS, directly or through a terminating proxy.

    Only used to decide whether to emit HSTS. Trusting a forwarded header is safe *for this
    purpose*: the worst a spoofed header can do is add an HSTS header to a plaintext response, and
    browsers ignore HSTS delivered over plain HTTP.
    """
    if scope.get("scheme") == "https":
        return True
    for name, value in scope.get("headers", []):
        if name in _FORWARDED_PROTO_HEADERS:
            # A chain of proxies produces "https, http" — the left-most entry is the viewer's.
            if value.decode("latin-1").split(",")[0].strip().lower() == "https":
                return True
        elif name == b"x-forwarded-ssl" and value.strip().lower() == b"on":
            return True
    return False


class SecurityHeadersMiddleware:
    """Pure-ASGI middleware that adds the standard security headers to every response.

    Written against the raw ASGI interface rather than ``BaseHTTPMiddleware`` on purpose: it only
    needs to append a few headers to the response-start message, so it adds no task groups, no
    buffering and nothing that could interfere with the streamed CSV/media responses or with request
    cancellation. Existing headers are never overwritten, so a route may still set its own CSP.

    It is registered LAST, which makes it the OUTERMOST user middleware, so the headers also land on
    CORS preflight responses and on responses produced by exception handlers. (A response generated
    by Starlette's ServerErrorMiddleware — the last-resort 500 — sits outside every user middleware
    and therefore cannot be stamped; that response carries no data.)
    """

    def __init__(self, app: ASGIApp, *, hsts_value: bytes | None = None, force_hsts: bool = False) -> None:
        self.app = app
        self.hsts_value = hsts_value
        self.force_hsts = force_hsts

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        csp = _DOCS_CSP if scope.get("path") in _DOCS_PATHS else _API_CSP
        hsts = self.hsts_value if (self.hsts_value and (self.force_hsts or _request_is_https(scope))) else None

        async def send_with_security_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = list(message.get("headers") or [])
                present = {name.lower() for name, _ in headers}
                for name, value in _BASE_SECURITY_HEADERS:
                    if name not in present:
                        headers.append((name, value))
                if b"content-security-policy" not in present:
                    headers.append((b"content-security-policy", csp))
                if hsts and b"strict-transport-security" not in present:
                    headers.append((b"strict-transport-security", hsts))
                message["headers"] = headers
            await send(message)

        await self.app(scope, receive, send_with_security_headers)


def create_app() -> FastAPI:
    settings = get_settings()
    # Refuse to serve with a guessable token-signing secret. Done before anything else so the
    # failure is the first thing in the log rather than a subtle weakness nobody notices.
    verify_jwt_configuration()
    app = FastAPI(
        title="Field Documentation Repository API",
        version="0.1.0",
        description="API-first backend for artisan, craft, workshop, product, tool, media and review records.",
        lifespan=lifespan,
    )
    cors_origins = settings.cors_origins
    allow_credentials = settings.cors_allow_credentials
    if not allow_credentials:
        # settings.cors_allow_credentials is False only when BACKEND_CORS_ORIGINS contains "*".
        logger.error(
            "BACKEND_CORS_ORIGINS contains a wildcard (%s); credentialed CORS is DISABLED because "
            "'*' plus credentials would let any website call this API as a signed-in user. Set "
            "BACKEND_CORS_ORIGINS to the explicit frontend origin(s).",
            ", ".join(cors_origins),
        )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins,
        allow_credentials=allow_credentials,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    # Added AFTER CORS so it wraps it (Starlette runs the most recently added middleware outermost),
    # which is what puts the security headers on preflight responses too.
    hsts_value = (
        f"max-age={settings.security_hsts_max_age}; includeSubDomains".encode()
        if settings.security_hsts_enabled
        else None
    )
    app.add_middleware(
        SecurityHeadersMiddleware,
        hsts_value=hsts_value,
        force_hsts=settings.security_force_hsts,
    )

    @app.get("/health", tags=["health"])
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(api_router)
    return app


app = create_app()
