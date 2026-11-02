"""Queue the translation of every transcript that never got one (D38, 0.0.9): a one-off sweep.

    cd backend && python -m app.scripts.queue_pending_translations            # dry run: counts only
    cd backend && python -m app.scripts.queue_pending_translations --apply    # queue them
    cd backend && python -m app.scripts.queue_pending_translations --json     # the counts as JSON

IDENTICAL IN BOTH PORTALS (``shared/transcription.sha256``). Before 0.0.9 a transcript made before
the turn pipeline got its turns the first time it was opened or exported (``ensure_version``) but
no TRANSCRIPT_INSIGHTS job, so its turns stayed untranslated. From 0.0.9 ``ensure_version`` queues
that job itself; this sweep catches the recordings that were opened before it did, and the ones
never opened at all (``generate`` makes their turns inside the job).

A CANDIDATE is a recording with transcript text whose current version is missing (no turns yet) or
has a turn that needs English and has none. It is QUEUED unless, in this order:

* an insights job for it is queued or running (``already-active``);
* Gemini declined its translation (``declined``): asking again gets the same answer, so a person
  adds the English on the transcript page instead;
* a researcher edited its summary (``edited-insight-skipped``): an automatic run never replaces it;
* an insights job completed after its current version was made (``already-run``): the turns it
  left untranslated failed, and Retry on the page is the way to ask again;
* the AI transcript switch is off (``feature-off``);
* consent refuses the send, re-read now (``consent-refused``).

DRY RUN BY DEFAULT: it changes nothing and prints COUNTS ONLY, never transcript text, names or ids.
``--apply`` queues through ``transcript_insights.enqueue_after_transcript``, which refuses again
when a job became active meanwhile, so running it twice queues nothing new.

MEMORY. Recordings are read a page of ids at a time (keyset on the id, ``--page`` rows) with explicit
columns and every flag worked out in the database; no transcript text is read. Only a candidate that
may be queued is loaded whole, one at a time, for the consent rule.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from collections import Counter
from typing import Any

PAGE = 200

SCANNED = "scanned"
CANDIDATES = "candidates"
QUEUED = "queued"
WOULD_QUEUE = "would-queue"
NOT_QUEUED = "not-queued"
ALREADY_ACTIVE = "already-active"
DECLINED = "declined"
EDITED = "edited-insight-skipped"
ALREADY_RUN = "already-run"
FEATURE_OFF = "feature-off"
CONSENT_REFUSED = "consent-refused"

BUCKETS = (
    SCANNED, CANDIDATES, ALREADY_ACTIVE, DECLINED, EDITED, ALREADY_RUN, FEATURE_OFF, CONSENT_REFUSED,
    WOULD_QUEUE, QUEUED, NOT_QUEUED,
)

#: One page of recordings with a transcript, every flag the rules need, no transcript text.
PAGE_SQL = (
    'SELECT m."id", v."id" AS "versionId", v."translationStatus" AS "versionStatus", '
    "EXISTS (SELECT 1 FROM \"TranscriptTurn\" t WHERE t.\"versionId\" = v.\"id\" "
    "AND t.\"translationStatus\" <> 'ALREADY_ENGLISH' "
    "AND COALESCE(btrim(t.\"editedEnglish\"), '') = '' AND COALESCE(btrim(t.\"machineEnglish\"), '') = '') AS \"untranslated\", "
    "EXISTS (SELECT 1 FROM \"MediaProcessingJob\" j WHERE j.\"mediaFileId\" = m.\"id\" "
    "AND j.\"jobType\"::text = 'TRANSCRIPT_INSIGHTS' AND j.\"status\"::text IN ('QUEUED', 'PROCESSING')) AS \"active\", "
    "EXISTS (SELECT 1 FROM \"MediaProcessingJob\" j WHERE j.\"mediaFileId\" = m.\"id\" "
    "AND j.\"jobType\"::text = 'TRANSCRIPT_INSIGHTS' AND j.\"status\"::text = 'COMPLETED' "
    "AND v.\"id\" IS NOT NULL AND COALESCE(j.\"completedAt\", j.\"updatedAt\") >= v.\"createdAt\") AS \"ranSince\", "
    "(SELECT j.\"error\" FROM \"MediaProcessingJob\" j WHERE j.\"mediaFileId\" = m.\"id\" "
    "AND j.\"jobType\"::text = 'TRANSCRIPT_INSIGHTS' ORDER BY j.\"createdAt\" DESC LIMIT 1) AS \"lastError\", "
    "(SELECT j.\"status\"::text FROM \"MediaProcessingJob\" j WHERE j.\"mediaFileId\" = m.\"id\" "
    "AND j.\"jobType\"::text = 'TRANSCRIPT_INSIGHTS' ORDER BY j.\"createdAt\" DESC LIMIT 1) AS \"lastStatus\", "
    "EXISTS (SELECT 1 FROM \"TranscriptInsight\" i WHERE i.\"mediaFileId\" = m.\"id\" "
    "AND i.\"supersededAt\" IS NULL AND i.\"editedAt\" IS NOT NULL) AS \"edited\" "
    'FROM "MediaFile" m '
    'LEFT JOIN LATERAL (SELECT "id", "translationStatus", "createdAt" FROM "TranscriptVersion" '
    'WHERE "mediaFileId" = m."id" AND "supersededAt" IS NULL ORDER BY "version" DESC LIMIT 1) v ON true '
    "WHERE m.\"id\" > $1 AND m.\"transcriptText\" IS NOT NULL AND btrim(m.\"transcriptText\") <> '' "
    "{scope}"
    'ORDER BY m."id" LIMIT $2'
)

#: ``--only``: the sweep narrowed to named recordings (a check after a fix; the tests).
SCOPE_IDS = 'AND m."id" = ANY($3::text[]) '


def is_candidate(row: dict[str, Any]) -> bool:
    """No current version yet (``generate`` makes the turns), or a turn that needs English lacks it."""
    return row.get("versionId") is None or bool(row.get("untranslated"))


def classify(row: dict[str, Any], *, enabled: bool, declined_message: str) -> str | None:
    """The bucket a candidate stops in before consent is read, or None when it may be queued.

    The rules, in the module docstring's order. ``declined_message`` is ``gemini.MESSAGES[DECLINED]``:
    a recording declined before 0.0.9 carries only that sentence on its last job."""
    if row.get("active"):
        return ALREADY_ACTIVE
    if row.get("versionStatus") == "DECLINED" or (
        row.get("lastStatus") == "FAILED" and (row.get("lastError") or "").strip() == declined_message
    ):
        return DECLINED
    if row.get("edited"):
        return EDITED
    if row.get("ranSince"):
        return ALREADY_RUN
    if not enabled:
        return FEATURE_OFF
    return None


async def sweep(*, apply: bool, page: int = PAGE, ids: list[str] | None = None, client: Any = None) -> Counter[str]:
    """The counts (see :data:`BUCKETS`). ``apply`` False reads only; ``ids`` narrows the sweep."""
    from app.core.db import db
    from app.services import transcript_insights, transcript_pipeline, transcript_portal
    from app.transcription.transcript import gemini

    conn = client if client is not None else db
    enabled = transcript_pipeline.enabled()
    declined_message = gemini.MESSAGES[gemini.DECLINED]
    counts: Counter[str] = Counter(dict.fromkeys(BUCKETS, 0))
    after = ""
    while True:
        if ids is None:
            rows = await conn.query_raw(PAGE_SQL.format(scope=""), after, max(1, int(page)))
        else:
            rows = await conn.query_raw(PAGE_SQL.format(scope=SCOPE_IDS), after, max(1, int(page)), list(ids))
        if not rows:
            break
        after = str(rows[-1]["id"])
        for row in rows:
            counts[SCANNED] += 1
            if not is_candidate(row):
                continue
            counts[CANDIDATES] += 1
            stop = classify(row, enabled=enabled, declined_message=declined_message)
            if stop is not None:
                counts[stop] += 1
                continue
            media = await conn.mediafile.find_unique(where={"id": str(row["id"])})
            if media is None:
                continue
            may, _refusal = await transcript_portal.may_send(media)
            del media
            if not may:
                counts[CONSENT_REFUSED] += 1
                continue
            if not apply:
                counts[WOULD_QUEUE] += 1
                continue
            job = await transcript_insights.enqueue_after_transcript(str(row["id"]))
            counts[QUEUED if job is not None else NOT_QUEUED] += 1
        if len(rows) < page:
            break
    return counts


def report(counts: Counter[str], *, apply: bool) -> str:
    mode = "applied" if apply else "dry run (nothing queued; --apply to queue)"
    lines = [f"queue_pending_translations: {mode}"]
    lines += [f"  {bucket}: {counts.get(bucket, 0)}" for bucket in BUCKETS]
    return "\n".join(lines)


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Queue the translation of transcripts that never got one.")
    parser.add_argument("--apply", action="store_true", help="queue the jobs (default: a dry run)")
    parser.add_argument("--json", action="store_true", help="print the counts as JSON")
    parser.add_argument("--page", type=int, default=PAGE, help=f"recordings read per page (default {PAGE})")
    parser.add_argument("--only", default="", help="comma-separated media ids: sweep just these")
    args = parser.parse_args(argv)
    only = [part.strip() for part in args.only.split(",") if part.strip()] or None

    from app.core.db import connect_db, disconnect_db

    await connect_db()
    try:
        counts = await sweep(apply=args.apply, page=args.page, ids=only)
    finally:
        await disconnect_db()
    if args.json:
        print(json.dumps({"applied": args.apply, "counts": {b: counts.get(b, 0) for b in BUCKETS}}, indent=2))
    else:
        print(report(counts, apply=args.apply))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
