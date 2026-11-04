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

SCOPE_IDS = 'AND m."id" = ANY($3::text[]) '


def is_candidate(row: dict[str, Any]) -> bool:
    return row.get("versionId") is None or bool(row.get("untranslated"))


def classify(row: dict[str, Any], *, enabled: bool, declined_message: str) -> str | None:
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
