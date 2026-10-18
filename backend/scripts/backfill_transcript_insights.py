"""Queue summaries, key quotes and translations for transcripts that already exist.

DRY RUN BY DEFAULT. Without ``--execute`` nothing is written: the script prints how many transcripts
are eligible, how many already have a job queued, how many it would queue, their total characters
(the cost estimate: roughly two provider calls per transcript) and the first 20 media ids.

``--execute`` QUEUES, IT NEVER GENERATES. Jobs go in at priority 90, below every other job type,
with no requester, so the worker drains them a batch at a time on the deployment's key. Re-running
is safe: a media file with an insights job already queued or processing is skipped.

It refuses (exit 2) while the feature is switched off, because queued jobs would sit undrained.

Eligible: ``transcriptStatus`` COMPLETED or EMPTY, a non-empty transcript, and either no current
insight or, with ``--include-stale``, a current insight that was not edited by a researcher and
whose source hash no longer matches the transcript.

Usage:
    python -m scripts.backfill_transcript_insights                       # DRY RUN
    python -m scripts.backfill_transcript_insights --limit 50 --execute  # queue 50
    python -m scripts.backfill_transcript_insights --media-id ck... --execute
    python -m scripts.backfill_transcript_insights --since 2026-09-01 --include-stale
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from datetime import UTC, datetime
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services import transcript_insights as insights

BACKFILL_PRIORITY = 90
REFUSAL = "Turn on AI_FEATURES_ENABLED and AI_TRANSCRIPT_INSIGHTS_ENABLED first."
PAGE = 200


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Queue transcript insights for existing transcripts.")
    parser.add_argument("--execute", action="store_true", help="queue jobs (default: dry run)")
    parser.add_argument("--limit", type=int, default=None, help="queue at most N")
    parser.add_argument("--media-id", default=None, help="only this media file")
    parser.add_argument("--since", default=None, help="only media created on or after YYYY-MM-DD")
    parser.add_argument(
        "--include-stale", action="store_true",
        help="also re-queue unedited insights whose transcript has changed",
    )
    args = parser.parse_args(argv)
    if args.since:
        try:
            args.since = datetime.strptime(args.since, "%Y-%m-%d").replace(tzinfo=UTC)
        except ValueError:
            parser.error("--since must be YYYY-MM-DD")
    if args.limit is not None and args.limit < 1:
        parser.error("--limit must be at least 1")
    return args


def needs_insight(media: Any, current: Any | None, include_stale: bool) -> bool:
    """Is this media file eligible? Pure, so it is tested without a database."""
    if str(getattr(media, "transcriptStatus", "") or "").upper() not in {"COMPLETED", "EMPTY"}:
        return False
    if not insights.select_sources(media)[0]:
        return False
    if current is None:
        return True
    return bool(
        include_stale
        and current.editedAt is None
        and current.sourceHash != insights.media_source_hash(media)
    )


async def plan(args: argparse.Namespace) -> dict[str, Any]:
    """``{eligible, already_queued, to_queue: [ids], characters}``. Reads only."""
    where: dict[str, Any] = {
        "transcriptStatus": {"in": ["COMPLETED", "EMPTY"]},
        "transcriptText": {"not": None},
    }
    if args.media_id:
        where["id"] = args.media_id
    if args.since:
        where["createdAt"] = {"gte": args.since}
    eligible = 0
    already_queued = 0
    to_queue: list[str] = []
    characters = 0
    cursor: str | None = None
    while True:
        page = await db.mediafile.find_many(
            where=where,
            order={"id": "asc"},
            take=PAGE,
            **({"skip": 1, "cursor": {"id": cursor}} if cursor else {}),
        )
        if not page:
            break
        cursor = page[-1].id
        ids = [m.id for m in page]
        current = await insights.current_insights(ids)
        jobs = await insights.latest_jobs(ids)
        for media in page:
            if not needs_insight(media, current.get(media.id), args.include_stale):
                continue
            eligible += 1
            job = jobs.get(media.id)
            if job is not None and job.status in insights.ACTIVE_JOB_STATUSES:
                already_queued += 1
                continue
            if args.limit is None or len(to_queue) < args.limit:
                to_queue.append(media.id)
                primary, original = insights.select_sources(media)
                characters += len(primary) + len(original or "")
        if len(page) < PAGE:
            break
    return {
        "eligible": eligible,
        "already_queued": already_queued,
        "to_queue": to_queue,
        "characters": characters,
    }


async def run(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.execute and not insights.enabled():
        print(REFUSAL)
        return 2
    print("MODE:", "EXECUTE" if args.execute else "DRY RUN (no changes)")
    report = await plan(args)
    print(
        f"eligible={report['eligible']} already_queued={report['already_queued']} "
        f"{'queueing' if args.execute else 'would_queue'}={len(report['to_queue'])} "
        f"characters={report['characters']}"
    )
    for media_id in report["to_queue"][:20]:
        print(f"  {media_id}")
    if not args.execute:
        return 0
    queued = 0
    for media_id in report["to_queue"]:
        if await insights.enqueue(media_id, requested_by_id=None, priority=BACKFILL_PRIORITY):
            queued += 1
    print(f"queued={queued}")
    return 0


async def main(argv: list[str] | None = None) -> int:
    await connect_db()
    try:
        return await run(sys.argv[1:] if argv is None else argv)
    finally:
        await disconnect_db()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
