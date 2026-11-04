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
from app.api.routes.auth import (
    CONFIRM_SIGN_IN_HEADER,
    SESSION_TOKEN_HEADER,
    SIGN_IN_PROOF_HEADER,
    TWO_FACTOR_REQUIRED_HEADER,
)
from app.api.routes.users import EXISTING_ACCOUNT_HEADER
from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.core.deps import PASSWORD_CHANGE_REQUIRED_HEADER
from app.core.idempotency import IdempotencyMiddleware
from app.core.security import verify_jwt_configuration
from app.services import aadhaar_fingerprint, usage_log
from app.services.app_versions import AppVersionMiddleware
from app.services.email_outbox import process_next_email_jobs
from app.services.media_queue import process_next_media_jobs, queue_work_due
from app.transcription import workdir

logger = logging.getLogger(__name__)

_QUEUE_LOCK_PATH = os.path.join(tempfile.gettempdir(), "fieldrepo-media-queue.lock")


def _acquire_queue_worker_lock() -> Any | None:
    try:
        import fcntl
    except ImportError:
        return object()
    try:
        handle = open(_QUEUE_LOCK_PATH, "w")  # noqa: SIM115
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        handle.write(str(os.getpid()))
        handle.flush()
        return handle
    except OSError:
        return None


_DB_PROBE_INTERVAL_SECONDS = 15.0


async def _keep_db_connected() -> None:
    delay = 2.0
    while True:
        try:
            await db.query_raw("SELECT 1")
            delay = 2.0
            await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
            continue
        except Exception as exc:  # noqa: BLE001
            if getattr(exc, "code", None) == "P2024":
                logger.warning("DB probe pool-timeout (P2024); pool saturated by load, not reconnecting")
                await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
                continue
            await asyncio.sleep(2.0)
            try:
                await db.query_raw("SELECT 1")
                continue
            except Exception as exc2:  # noqa: BLE001
                logger.warning("DB health probe failed twice: %s — reconnecting in the background", exc2)
        try:
            with suppress(Exception):
                await db.disconnect()
            await db.connect()
            await db.query_raw("SELECT 1")
            logger.info("Database connected (background reconnect succeeded)")
            delay = 2.0
        except Exception as exc:  # noqa: BLE001
            logger.warning("Background DB reconnect failed: %s — retrying in %.0fs", exc, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        await connect_db()
    except Exception as exc:  # noqa: BLE001
        logger.error(
            "Initial DB connect failed (%s); starting anyway and reconnecting in the background "
            "so a saturated pooler cannot crash-loop the service",
            exc,
        )
    db_reconnect_task: asyncio.Task[None] = asyncio.create_task(_keep_db_connected())
    usage_task: asyncio.Task[None] = asyncio.create_task(usage_log.run_flush_worker())
    settings = get_settings()
    queue_task: asyncio.Task[None] | None = None
    queue_lock: Any | None = None
    if settings.media_queue_worker_enabled:
        queue_lock = _acquire_queue_worker_lock()
        if queue_lock is not None:
            logger.info("Media queue worker elected in pid %s", os.getpid())
            try:
                await asyncio.to_thread(workdir.on_worker_start)
            except Exception:
                logger.warning("transcription workdir sweep failed", exc_info=True)
            queue_task = asyncio.create_task(_media_queue_worker())
            app.state.media_queue_task = queue_task
            app.state.media_queue_lock = queue_lock
        else:
            logger.info("Media queue worker already running elsewhere; pid %s serves requests only", os.getpid())
    try:
        yield
    finally:
        usage_task.cancel()
        with suppress(asyncio.CancelledError):
            await usage_task
        with suppress(Exception):
            await usage_log.flush_all()
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
        media_due, mail_due = await queue_work_due()
        try:
            if media_due:
                await process_next_media_jobs(
                    limit=settings.media_queue_batch_size,
                    worker_id="fastapi-background",
                    settings=settings,
                )
        except Exception:
            logger.exception("Media processing queue worker failed")
        try:
            if mail_due:
                await process_next_email_jobs(worker_id="fastapi-background", settings=settings)
        except Exception:
            logger.exception("E-mail outbox drain failed")
        await asyncio.sleep(interval)


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

_API_CSP = b"default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

_DOCS_CSP = (
    b"default-src 'none'; "
    b"script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; "
    b"style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com 'unsafe-inline'; "
    b"img-src 'self' https://fastapi.tiangolo.com data:; "
    b"font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com; "
    b"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
)
_DOCS_PATHS = frozenset({"/docs", "/docs/oauth2-redirect", "/redoc"})

_FORWARDED_PROTO_HEADERS = frozenset({b"x-forwarded-proto", b"cloudfront-forwarded-proto", b"x-forwarded-scheme"})


def _request_is_https(scope: Scope) -> bool:
    if scope.get("scheme") == "https":
        return True
    for name, value in scope.get("headers", []):
        if name in _FORWARDED_PROTO_HEADERS:
            if value.decode("latin-1").split(",")[0].strip().lower() == "https":
                return True
        elif name == b"x-forwarded-ssl" and value.strip().lower() == b"on":
            return True
    return False


class UnhandledErrorMiddleware:
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
        except Exception as exc:
            method = scope.get("method", "?")
            path = scope.get("path", "?")
            logger.exception("Unhandled error on %s %s: %s", method, path, exc)
            if started:
                raise
            payload = {
                "detail": "Something went wrong. Please try again.",
                "error": type(exc).__name__,
                "path": path,
            }
            response = JSONResponse(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=payload
            )
            await response(scope, receive, send)


class SecurityHeadersMiddleware:
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


_READINESS_TIMEOUT_SECONDS = 3.0


def create_app() -> FastAPI:
    settings = get_settings()
    verify_jwt_configuration()
    if not (settings.two_factor_encryption_key or "").strip():
        from app.services.two_factor import warn_legacy_key

        warn_legacy_key()
    expose_docs = settings.backend_expose_docs
    app = FastAPI(
        title="Field Documentation Repository API",
        version="0.1.0",
        description="API-first backend for artisan, craft, workshop, product, tool, media and review records.",
        lifespan=lifespan,
        docs_url="/docs" if expose_docs else None,
        redoc_url="/redoc" if expose_docs else None,
        openapi_url="/openapi.json" if expose_docs else None,
    )
    cors_origins = settings.cors_origins
    allow_credentials = settings.cors_allow_credentials
    if not allow_credentials:
        logger.error(
            "BACKEND_CORS_ORIGINS contains a wildcard (%s); credentialed CORS is DISABLED because "
            "'*' plus credentials would let any website call this API as a signed-in user. Set "
            "BACKEND_CORS_ORIGINS to the explicit frontend origin(s).",
            ", ".join(cors_origins),
        )
    app.add_middleware(usage_log.TimingMiddleware)
    app.add_middleware(IdempotencyMiddleware)
    app.add_middleware(AppVersionMiddleware)
    app.add_middleware(UnhandledErrorMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins,
        allow_credentials=allow_credentials,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=[
            PASSWORD_CHANGE_REQUIRED_HEADER,
            SESSION_TOKEN_HEADER,
            CONFIRM_SIGN_IN_HEADER,
            TWO_FACTOR_REQUIRED_HEADER,
            SIGN_IN_PROOF_HEADER,
            EXISTING_ACCOUNT_HEADER,
            "ETag",
        ],
    )
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

    @app.get("/health/ready", tags=["health"])
    async def health_ready() -> JSONResponse:
        started = time.perf_counter()
        reachable = True
        try:
            async with asyncio.timeout(_READINESS_TIMEOUT_SECONDS):
                await db.query_raw("SELECT 1")
        except TimeoutError:
            reachable = False
            logger.warning(
                "Readiness probe: no answer from the database within %.1fs", _READINESS_TIMEOUT_SECONDS
            )
        except Exception as exc:  # noqa: BLE001
            reachable = False
            logger.warning("Readiness probe failed: %s", exc)
        return JSONResponse(
            status_code=status.HTTP_200_OK if reachable else status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "status": "ready" if reachable else "unavailable",
                "database": reachable,
                "latencyMs": round((time.perf_counter() - started) * 1000, 1),
            },
            headers={"cache-control": "no-store"},
        )

    app.include_router(api_router)
    return app


aadhaar_fingerprint.install_access_log_redaction()
app = create_app()
