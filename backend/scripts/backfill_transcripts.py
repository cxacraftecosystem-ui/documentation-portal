"""Backfill the transcript pipeline: queued, failed and consented-but-untranscribed recordings.

IDENTICAL IN BOTH PORTALS (``shared/transcription.sha256``). Design section 4.8 and decision 5:
after switch-on, transcribe and translate oldest first, in batches, within the monthly budget.

DRY RUN BY DEFAULT: it prints COUNTS only (no names, no titles, no transcript text) and changes
nothing. ``--apply`` queues the next budget-bounded batch on the existing queue, oldest recording
first; the queue still holds every job while transcription is paused, so applying before the
coordinator un-pauses only lines them up.

    cd backend && python scripts/backfill_transcripts.py                 # counts only
    cd backend && python scripts/backfill_transcripts.py --apply --batch 25

A batch stops at ``--batch`` recordings or when the estimated cost would pass what is left of this
month's budget, whichever comes first. The estimates and the batch rule live in
``app/services/transcript_backfill.py``, which the Settings switch also runs as a background job.
"""

from __future__ import annotations

import argparse
import asyncio
import pathlib
import sys

BACKEND = pathlib.Path(__file__).resolve().parents[1]
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))


async def run(apply: bool, batch: int) -> int:
    from app.core.db import connect_db, db, disconnect_db
    from app.services import media_queue, transcript_backfill as backfill, transcript_pipeline

    await connect_db()
    try:
        candidates, refused = await backfill.collect()
        meter = await transcript_pipeline.meter()
        left = None if meter.monthly_usd is None else max(0.0, meter.monthly_usd - meter.spent_usd)
        summary = backfill.summarise(candidates, refused)
        chosen = backfill.next_batch(candidates, limit=batch, budget_left_usd=left)
        print(f"Candidates: {summary['recordings']} (queued {summary['queued']}, failed {summary['failed']}, "
              f"untranscribed {summary['untranscribed']}); left out for consent: {refused}.")
        print(f"Estimated audio: {summary['minutes']:.0f} min; estimated cost of all: USD "
              f"{summary['costUsd']:.2f}.")
        print(f"This month: USD {meter.spent_usd:.2f} spent of "
              f"{'no budget' if meter.monthly_usd is None else f'{meter.monthly_usd:.2f}'}.")
        print(f"Next batch: {len(chosen)} recordings, estimated USD {sum(c.estimate_usd for c in chosen):.2f}.")
        if not apply:
            print("Dry run: nothing was changed. Run with --apply to queue the next batch.")
            return 0
        queued = 0
        for candidate in chosen:
            if candidate.kind == backfill.QUEUED:
                continue
            media = await db.mediafile.find_unique(where={"id": candidate.media_id})
            if media is None:
                continue
            jobs = await media_queue.enqueue_media_processing_jobs(
                media, ["TRANSCRIPTION"], getattr(media, "uploadedById", None)
            )
            queued += 1 if jobs else 0
        already = sum(1 for c in chosen if c.kind == backfill.QUEUED)
        print(f"Queued {queued} recordings; {already} were already queued.")
        return 0
    finally:
        await disconnect_db()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apply", action="store_true", help="queue the next batch (default: counts only)")
    parser.add_argument("--batch", type=int, default=25, help="recordings per batch (default 25)")
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    return asyncio.run(run(args.apply, max(1, args.batch)))


if __name__ == "__main__":
    raise SystemExit(main())
