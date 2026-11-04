from __future__ import annotations

import asyncio
import logging
import signal
import time
from contextlib import suppress

from app.core.config import get_settings
from app.core.db import connect_db, disconnect_db, ensure_db_connected
from app.questionnaire.sessions import maybe_close_stale_sessions
from app.services import artisan_duplicates, transcript_backfill
from app.services.email_outbox import process_next_email_jobs
from app.services.media_queue import process_next_media_jobs, queue_work_due, run_insights_lane
from app.services.search_indexer import (
    compact_queue as compact_search_queue,
)
from app.services.search_indexer import (
    drain as drain_search_index,
)
from app.services.search_indexer import (
    embed_pending as embed_search_documents,
)
from app.services.weekly_digest import run_weekly_digests
from app.services.workshop_auto_map import maybe_run_nightly_auto_map
from app.transcription import workdir

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("app.worker")

SEARCH_INDEX_INTERVAL_SECONDS = 15.0
SEARCH_INDEX_BATCH = 100
SEARCH_EMBED_BATCH = 32
SEARCH_COMPACT_INTERVAL_SECONDS = 600.0

_last_search_compaction = {"at": float("-inf")}


async def run_search_index_pass(settings) -> None:
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
        with suppress(NotImplementedError):
            loop.add_signal_handler(sig, stop.set)

    with suppress(Exception):
        await connect_db()

    logger.info("Media queue worker started (interval=%.1fs)", interval)
    with suppress(Exception):
        await asyncio.to_thread(workdir.on_worker_start)
    lane = asyncio.create_task(run_insights_lane(stop, settings))
    last_search_pass = 0.0
    try:
        while not stop.is_set():
            media_due, mail_due = True, True
            try:
                await ensure_db_connected()
                media_due, mail_due = await queue_work_due()
                if media_due:
                    await process_next_media_jobs(
                        limit=settings.media_queue_batch_size,
                        worker_id="queue-service",
                        settings=settings,
                        skip_insights=True,
                    )
            except Exception:
                logger.exception("Media queue iteration failed; backing off")
            try:
                if mail_due:
                    await process_next_email_jobs(worker_id="queue-service", settings=settings)
            except Exception:
                logger.exception("E-mail outbox iteration failed; backing off")
            try:
                await maybe_run_nightly_auto_map()
            except Exception:
                logger.exception("Nightly workshop filing failed; will retry")
            try:
                await run_weekly_digests(settings=settings)
            except Exception:
                logger.exception("Weekly digest pass failed")
            if time.monotonic() - last_search_pass >= SEARCH_INDEX_INTERVAL_SECONDS:
                last_search_pass = time.monotonic()
                try:
                    await run_search_index_pass(settings)
                except Exception:
                    logger.exception("Search index pass failed; backing off")
            try:
                await artisan_duplicates.maybe_run(settings)
            except Exception:
                logger.exception("Duplicate-artisan scan failed; it runs again tomorrow")
            try:
                await maybe_close_stale_sessions()
            except Exception:
                logger.exception("Nightly session close failed; will retry")
            try:
                await transcript_backfill.maybe_run()
            except Exception:
                logger.exception("Transcript backfill pass failed; it runs again later")
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(stop.wait(), timeout=interval)
    finally:
        stop.set()
        lane.cancel()
        with suppress(asyncio.CancelledError, Exception):
            await lane
        await disconnect_db()
        logger.info("Media queue worker stopped")


def main() -> None:
    asyncio.run(_run())


if __name__ == "__main__":
    main()
