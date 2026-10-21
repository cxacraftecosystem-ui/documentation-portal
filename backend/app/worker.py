"""Standalone media-processing queue worker.

Runs the transcription / measurement queue in its OWN process (systemd unit
``fieldrepo-queue.service``), completely separate from the web (uvicorn) process. This split is a
deliberate fix for a production outage, and the reasons it MUST stay separate are:

1. **No multiprocess supervisor to SIGKILL it.** The web service runs as a SINGLE uvicorn process.
   With ``--workers >1`` uvicorn runs a multiprocess supervisor that health-pings each worker over a
   pipe (the worker answers from a daemon thread) and **SIGKILLs any worker that fails to pong within
   ``timeout_worker_healthcheck``**. On this small, CPU-credit-throttled EC2 box a heavy transcription
   chunk — run via ``asyncio.to_thread`` — starved that pong thread for long enough that the supervisor
   killed the worker mid-job. A SIGKILLed process never runs its shutdown hook, so its Prisma
   query-engine subprocess was orphaned (reparented to init). One orphan per kill cycle eventually
   exhausted the Supabase pooler's client-connection ceiling, after which EVERY DB call (login
   included) returned HTTP 500 while ``/health`` (which touches no DB) kept returning 200.

2. **Request latency isolation.** Transcription/measurement read whole media files into memory and run
   ffmpeg + AI calls. Keeping that off the request-serving process means API responses stay fast and
   never trip CloudFront's origin-response timeout (the earlier HTTP 504 class of failure).

This process is a plain ``asyncio.run`` loop — no supervisor, no health-ping, nothing that can kill it
mid-flight. systemd restarts it (``Restart=always``) only if it actually exits, and ``KillMode=
control-group`` guarantees its query-engine is reaped on stop/restart so it can never be orphaned.
"""

from __future__ import annotations

import asyncio
import logging
import signal
import time
from contextlib import suppress

from app.core.config import get_settings
from app.core.db import connect_db, disconnect_db, ensure_db_connected
from app.services import artisan_duplicates
from app.services.email_outbox import process_next_email_jobs
from app.services.media_queue import process_next_media_jobs, queue_work_due
from app.services.questionnaire_v2.sessions import maybe_close_stale_sessions
from app.services.workshop_auto_map import maybe_run_nightly_auto_map
from app.services.weekly_digest import run_weekly_digests
from app.services.search_indexer import (
    compact_queue as compact_search_queue,
)
from app.services.search_indexer import (
    drain as drain_search_index,
)
from app.services.search_indexer import (
    embed_pending as embed_search_documents,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("app.worker")

#: Seconds between search-index passes (docs/SEARCH.md). Small batches, because the box is
#: memory-bound: 100 records and 32 embeddings a pass at most.
SEARCH_INDEX_INTERVAL_SECONDS = 15.0
SEARCH_INDEX_BATCH = 100
SEARCH_EMBED_BATCH = 32
#: Seconds between queue compactions while UNIFIED_SEARCH_ENABLED is off.
SEARCH_COMPACT_INTERVAL_SECONDS = 600.0

_last_search_compaction = {"at": float("-inf")}


async def run_search_index_pass(settings) -> None:
    """One pass of the search indexer: drain the queue, then embed what changed. While
    UNIFIED_SEARCH_ENABLED is off nothing is built; the queue is only compacted to one row per
    record every ten minutes, so it stays bounded. Embeddings are a no-op unless meaning ranking
    is available."""
    if not settings.unified_search_enabled:
        now = time.monotonic()
        if now - _last_search_compaction["at"] >= SEARCH_COMPACT_INTERVAL_SECONDS:
            _last_search_compaction["at"] = now
            await compact_search_queue()
        return
    await drain_search_index(batch=SEARCH_INDEX_BATCH)
    await asyncio.sleep(0)
    await embed_search_documents(batch=SEARCH_EMBED_BATCH, settings=settings)


async def _run() -> None:
    settings = get_settings()
    interval = max(settings.media_queue_interval_seconds, 1.0)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        with suppress(NotImplementedError):  # add_signal_handler is POSIX-only
            loop.add_signal_handler(sig, stop.set)

    # Connect using the same resilient retry the web app uses. If it ultimately fails we do NOT crash:
    # the loop below keeps retrying, so a momentarily-full pooler can drain without a restart storm.
    with suppress(Exception):
        await connect_db()

    logger.info("Media queue worker started (interval=%.1fs)", interval)
    last_search_pass = 0.0
    try:
        while not stop.is_set():
            # One read says whether either queue has anything to do (media_queue.queue_work_due); an
            # idle tick then skips both drains instead of paying their five reads to find nothing.
            media_due, mail_due = True, True
            try:
                # Self-heal like the web app: probe the connection each iteration and, when the probe
                # fails (or a previous iteration broke the engine), disconnect-first then reconnect —
                # is_connected() alone can read True while the engine is actually unusable.
                await ensure_db_connected()
                media_due, mail_due = await queue_work_due()
                if media_due:
                    await process_next_media_jobs(
                        limit=settings.media_queue_batch_size,
                        worker_id="queue-service",
                        settings=settings,
                    )
            except Exception:  # one bad iteration must never kill the worker
                logger.exception("Media queue iteration failed; backing off")
            # The e-mail outbox rides the same loop, in its own try so neither can hold the other
            # back. A no-op when mail is not configured.
            try:
                if mail_due:
                    await process_next_email_jobs(worker_id="queue-service", settings=settings)
            except Exception:  # one bad iteration must never kill the worker
                logger.exception("E-mail outbox iteration failed; backing off")
            # The nightly workshop filing (services/workshop_auto_map). Its own try for the same
            # reason; it reads the settings row at most every ten minutes and runs once a night.
            try:
                await maybe_run_nightly_auto_map()
            except Exception:  # one bad iteration must never kill the worker
                logger.exception("Nightly workshop filing failed; will retry")
            # The Monday summary, in its own try after the outbox. It runs with mail off too (the
            # in-app half still goes), and outside Monday 08:00-23:59 IST it returns without a query.
            try:
                await run_weekly_digests(settings=settings)
            except Exception:  # one bad pass must never kill the worker
                logger.exception("Weekly digest pass failed")
            # THE SEARCH INDEX RIDES IT TOO, every SEARCH_INDEX_INTERVAL_SECONDS, in its own try.
            if time.monotonic() - last_search_pass >= SEARCH_INDEX_INTERVAL_SECONDS:
                last_search_pass = time.monotonic()
                try:
                    await run_search_index_pass(settings)
                except Exception:  # one bad pass must never kill the worker
                    logger.exception("Search index pass failed; backing off")
            # The duplicate-artisan scan and the 30-day merge-snapshot purge: each at most once a
            # day per process (cheap when not due), each in its own try for the same reason.
            try:
                await artisan_duplicates.maybe_run(settings)
            except Exception:  # one bad iteration must never kill the worker
                logger.exception("Duplicate-artisan scan failed; it runs again tomorrow")
            try:
                await maybe_close_stale_sessions()
            except Exception:  # one bad iteration must never kill the worker
                logger.exception("Nightly session close failed; will retry")
            # Sleep for the interval but wake immediately on shutdown.
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(stop.wait(), timeout=interval)
    finally:
        await disconnect_db()
        logger.info("Media queue worker stopped")


def main() -> None:
    asyncio.run(_run())


if __name__ == "__main__":
    main()
