import asyncio
import logging
import os
import tempfile
from contextlib import asynccontextmanager, suppress
from typing import Any

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router
from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
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


async def _keep_db_connected() -> None:
    """Keep retrying the DB connect, forever, in the background.

    This is the recovery path when the Supabase transaction pooler is momentarily at its
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
            with suppress(Exception):
                await db.disconnect()  # tear down any half-initialized engine before reconnecting
            await db.connect()
            await db.query_raw("SELECT 1")  # prove the link works; is_connected() alone can lie
            logger.info("Database connected (background reconnect succeeded)")
            return
        except Exception as exc:  # noqa: BLE001 - any connect failure should back off, not crash
            logger.warning("Background DB reconnect failed: %s — retrying in %.0fs", exc, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)


@asynccontextmanager
async def lifespan(app: FastAPI):
    db_reconnect_task: asyncio.Task[None] | None = None
    try:
        await connect_db()
    except Exception as exc:  # noqa: BLE001 - never crash-loop on a full pooler; recover in background
        logger.error(
            "Initial DB connect failed (%s); starting anyway and reconnecting in the background "
            "so a saturated pooler cannot crash-loop the service",
            exc,
        )
        db_reconnect_task = asyncio.create_task(_keep_db_connected())
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


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="Field Documentation Repository API",
        version="0.1.0",
        description="API-first backend for artisan, craft, workshop, product, tool, media and review records.",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health", tags=["health"])
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(api_router)
    return app


app = create_app()
