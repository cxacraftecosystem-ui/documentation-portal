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
month's budget, whichever comes first. Estimates: Scribe v2 at USD 0.22 an hour (by the stored
duration, else 64 kbit/s from the file size) plus Gemini at about USD 0.002 a minute.
"""

from __future__ import annotations

import argparse
import asyncio
import pathlib
import sys
from dataclasses import dataclass
from typing import Any

BACKEND = pathlib.Path(__file__).resolve().parents[1]
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

STT_USD_PER_MINUTE = 0.22 / 60
GEMINI_USD_PER_MINUTE = 0.002
ASSUMED_BYTES_PER_SECOND = 8_000
QUEUED = "queued"
FAILED = "failed"
UNTRANSCRIBED = "untranscribed"


@dataclass
class Candidate:
    media_id: str
    kind: str  # queued | failed | untranscribed
    recorded_at: Any
    minutes: float

    @property
    def estimate_usd(self) -> float:
        return round(self.minutes * (STT_USD_PER_MINUTE + GEMINI_USD_PER_MINUTE), 6)


def minutes_of(duration_ms: Any, size_bytes: Any) -> float:
    if duration_ms:
        return max(0.0, float(duration_ms) / 60_000)
    return max(0.0, float(size_bytes or 0) / ASSUMED_BYTES_PER_SECOND / 60)


def next_batch(candidates: list[Candidate], *, limit: int, budget_left_usd: float | None) -> list[Candidate]:
    """Oldest first; stop at ``limit`` or before the estimate passes what is left of the budget."""
    ordered = sorted(candidates, key=lambda c: (str(c.recorded_at or ""), c.media_id))
    chosen: list[Candidate] = []
    spent = 0.0
    for candidate in ordered:
        if len(chosen) >= limit:
            break
        if budget_left_usd is not None and spent + candidate.estimate_usd > budget_left_usd:
            break
        chosen.append(candidate)
        spent += candidate.estimate_usd
    return chosen


async def collect() -> tuple[list[Candidate], int]:
    """Every candidate whose consent allows a send, and how many were left out for consent."""
    from app.core.db import db
    from app.services import transcript_portal

    rows = await db.query_raw(
        'SELECT m."id", m."recordedAt", m."sizeBytes"::float8 AS size, m."transcriptStatus", '
        "(SELECT j.\"status\"::text FROM \"MediaProcessingJob\" j WHERE j.\"mediaFileId\" = m.\"id\" "
        "AND j.\"jobType\" = 'TRANSCRIPTION' ORDER BY j.\"createdAt\" DESC LIMIT 1) AS job, "
        '(SELECT r."durationMs" FROM "QuestionnaireRecording" r WHERE r."mediaFileId" = m."id") AS duration '
        'FROM "MediaFile" m WHERE m."mediaType" = \'AUDIO\' AND m."removedAt" IS NULL '
        "AND btrim(coalesce(m.\"transcriptText\", '')) = ''"
    )
    candidates: list[Candidate] = []
    refused = 0
    for row in rows:
        job = row.get("job")
        if job == "PROCESSING":
            continue
        kind = QUEUED if job == "QUEUED" else FAILED if job == "FAILED" else UNTRANSCRIBED
        media = await db.mediafile.find_unique(where={"id": row["id"]})
        if media is None:
            continue
        may, _reason = await transcript_portal.may_send(media)
        if not may:
            refused += 1
            continue
        candidates.append(Candidate(str(row["id"]), kind, row.get("recordedAt"), minutes_of(row.get("duration"), row.get("size"))))
    return candidates, refused


async def run(apply: bool, batch: int) -> int:
    from app.core.db import connect_db, db, disconnect_db
    from app.services import media_queue, transcript_pipeline

    await connect_db()
    try:
        candidates, refused = await collect()
        meter = await transcript_pipeline.meter()
        left = None if meter.monthly_usd is None else max(0.0, meter.monthly_usd - meter.spent_usd)
        counts = {kind: sum(1 for c in candidates if c.kind == kind) for kind in (QUEUED, FAILED, UNTRANSCRIBED)}
        total_minutes = sum(c.minutes for c in candidates)
        chosen = next_batch(candidates, limit=batch, budget_left_usd=left)
        print(f"Candidates: {len(candidates)} (queued {counts[QUEUED]}, failed {counts[FAILED]}, "
              f"untranscribed {counts[UNTRANSCRIBED]}); left out for consent: {refused}.")
        print(f"Estimated audio: {total_minutes:.0f} min; estimated cost of all: USD "
              f"{sum(c.estimate_usd for c in candidates):.2f}.")
        print(f"This month: USD {meter.spent_usd:.2f} spent of "
              f"{'no budget' if meter.monthly_usd is None else f'{meter.monthly_usd:.2f}'}.")
        print(f"Next batch: {len(chosen)} recordings, estimated USD {sum(c.estimate_usd for c in chosen):.2f}.")
        if not apply:
            print("Dry run: nothing was changed. Run with --apply to queue the next batch.")
            return 0
        queued = 0
        for candidate in chosen:
            if candidate.kind == QUEUED:
                continue  # already on the queue, oldest first
            media = await db.mediafile.find_unique(where={"id": candidate.media_id})
            if media is None:
                continue
            jobs = await media_queue.enqueue_media_processing_jobs(
                media, ["TRANSCRIPTION"], getattr(media, "uploadedById", None) or ""
            )
            queued += 1 if jobs else 0
        print(f"Queued {queued} recordings; {sum(1 for c in chosen if c.kind == QUEUED)} were already queued.")
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
