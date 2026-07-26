import asyncio
import logging
import os
import tempfile
import time
from contextlib import asynccontextmanager, suppress
from typing import Any

from fastapi import FastAPI, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
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


class UnhandledErrorMiddleware:
    """Turn any unhandled exception into a readable JSON 500 — from *inside* the CORS layer.

    THE FAILURE THIS EXISTS TO PREVENT, observed in production: approving a pending questionnaire
    raised ``FieldNotFoundError`` (the table was missing ``reviewNotes``). Starlette's built-in
    ``ServerErrorMiddleware`` caught it and returned a bare ``text/plain`` 500 — but that middleware
    sits OUTSIDE every middleware the app adds, so the response carried no
    ``access-control-allow-origin``. A browser cannot read a cross-origin response without that
    header, so the fetch simply rejected and the web UI said **"Failed to fetch"**, while Android
    (no CORS) showed the honest **HTTP 500**. One schema gap presented as a network fault on one
    client and a server fault on the other, and neither message named the real problem.

    Registering ``@app.exception_handler(Exception)`` does NOT fix that: Starlette special-cases the
    ``Exception`` key onto ``ServerErrorMiddleware``, so the response is still produced outside CORS.
    Verified — that approach yielded JSON but still no CORS header.

    So this is ASGI middleware installed BELOW ``CORSMiddleware``. Catching here means the error
    becomes an ordinary response that then travels back out through CORS and the security-header
    layer, arriving at the client fully readable.

    It never swallows anything: the traceback is logged at exception level exactly as before. Only
    the *shape* of the reply changes. ``HTTPException`` and friends are untouched — they are handled
    upstream by the router and never reach here.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        started = False

        async def wrapped_send(message: Message) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, receive, wrapped_send)
        except Exception as exc:  # noqa: BLE001 - deliberate catch-all; re-raised when unusable
            method = scope.get("method", "?")
            path = scope.get("path", "?")
            logger.exception("Unhandled error on %s %s: %s", method, path, exc)
            if started:
                # The response is already on the wire; anything we send now would corrupt it. Let it
                # surface so the server closes the connection rather than emitting a half-response.
                raise
            payload = {
                "detail": "Something went wrong on the server. The error has been logged.",
                # The exception TYPE is safe and genuinely useful to whoever is debugging; the
                # message may carry internals, so it stays in the log only.
                "error": type(exc).__name__,
                "path": path,
            }
            response = JSONResponse(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=payload
            )
            await response(scope, receive, send)


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


# --- Readiness ------------------------------------------------------------------------------------
# The readiness probe's own deadline. Shorter than an uptime monitor's request timeout on purpose, so
# a stalled database comes back as an explicit 503 the monitor can quote rather than as a client-side
# timeout, which says only that *something* did not answer. Also far below CloudFront's origin-response
# timeout, so the probe can never be the request that holds a connection open. The pooler lives in a
# different AWS region from this box, so a healthy round trip is a couple of hundred milliseconds and
# the ceiling sits about ten times above that: high enough that ordinary cross-region latency is never
# mistaken for an outage, low enough to cut short a pool that has stopped handing out connections.
_READINESS_TIMEOUT_SECONDS = 3.0


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
    # Added BEFORE CORS, which makes it the INNERMOST of the two — load-bearing, see the class
    # docstring. An unhandled error must become a normal response *below* CORS so the CORS layer can
    # still stamp `access-control-allow-origin` on the way out.
    app.add_middleware(UnhandledErrorMiddleware)
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
        """Liveness for the CloudFront origin. It must stay dumb — do not make it touch the database.

        The background watchdog can spend minutes reconnecting to a saturated pooler (see
        ``_keep_db_connected``). If this check failed during that window CloudFront would drop the box
        as an unhealthy origin, and a database that was busy recovering on its own would become a
        total outage instead. So a 200 here means only "the process is serving requests".

        Which is exactly why it is the wrong thing to alert on: point uptime alerting at
        ``/health/ready`` below, which answers the question this one deliberately refuses to.
        """
        return {"status": "ok"}

    @app.get("/health/ready", tags=["health"])
    async def health_ready() -> JSONResponse:
        """Readiness: does the database actually answer? This is what uptime alerting should watch.

        200 means one trivial query completed inside the deadline; 503 means it did not. ``latencyMs``
        is reported on both paths deliberately — this box has a documented history of connection-pool
        exhaustion whose first symptom is a probe that still succeeds but takes seconds, so an alert
        on rising latency fires while there is still time to act, where an alert on outright failure
        only fires once researchers are already locked out.

        Unauthenticated, because an uptime monitor carries no token — so the body is a boolean and a
        duration and nothing more. No host, no connection string, no driver text. Whatever actually
        broke goes to the server log, which is the place it is safe to be specific.

        It never raises: a readiness probe that 500s is an outage signal of its own, and it would sit
        on top of the one being reported.
        """
        started = time.perf_counter()
        reachable = True
        try:
            async with asyncio.timeout(_READINESS_TIMEOUT_SECONDS):
                # Observe, never repair. ``ensure_db_connected`` would be the tempting call here, but
                # reconnecting means disconnecting first, which would kill in-flight queries and race
                # the watchdog that already owns recovery. A probe that heals what it measures cannot
                # tell you how often it was broken.
                await db.query_raw("SELECT 1")
        except TimeoutError:
            reachable = False
            logger.warning(
                "Readiness probe: no answer from the database within %.1fs", _READINESS_TIMEOUT_SECONDS
            )
        except Exception as exc:  # noqa: BLE001 - deliberate catch-all; this endpoint must never 500
            reachable = False
            logger.warning("Readiness probe failed: %s", exc)
        return JSONResponse(
            status_code=status.HTTP_200_OK if reachable else status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "status": "ready" if reachable else "unavailable",
                "database": reachable,
                "latencyMs": round((time.perf_counter() - started) * 1000, 1),
            },
            # A remembered "ready" is precisely the failure-reporting-success shape this endpoint
            # exists to break, so nothing between here and the monitor may cache the verdict.
            headers={"cache-control": "no-store"},
        )

    app.include_router(api_router)
    return app


app = create_app()
