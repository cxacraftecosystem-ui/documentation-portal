from __future__ import annotations

import argparse
import asyncio
import os
import sys
from pathlib import Path
from typing import Any

FALLBACK_ERROR = "The transcription didn't finish."

STALE_CTE = (
    'WITH latest AS ('
    ' SELECT DISTINCT ON (j."mediaFileId") j."mediaFileId", j."id", j."status"::text AS status, j."error", j."completedAt"'
    ' FROM "MediaProcessingJob" j WHERE j."jobType"::text = \'TRANSCRIPTION\''
    ' ORDER BY j."mediaFileId", j."createdAt" DESC'
    '), stale AS ('
    ' SELECT m."id" AS "mediaId", m."transcriptStatus" AS stored, l."id" AS "jobId", l."completedAt",'
    " COALESCE(NULLIF(BTRIM(l.\"error\"), ''), $1) AS reason"
    ' FROM "MediaFile" m JOIN latest l ON l."mediaFileId" = m."id"'
    ' WHERE m."transcriptStatus" IN (\'QUEUED\', \'PROCESSING\')'
    " AND COALESCE(BTRIM(m.\"transcriptText\"), '') = ''"
    ' AND l.status = \'FAILED\''
    ' AND NOT EXISTS (SELECT 1 FROM "MediaProcessingJob" o WHERE o."mediaFileId" = m."id"'
    ' AND o."jobType"::text = \'TRANSCRIPTION\' AND o."status"::text IN (\'QUEUED\', \'PROCESSING\'))'
    ')'
)
SELECT_SQL = STALE_CTE + ' SELECT "mediaId", stored, "jobId", "completedAt", reason FROM stale ORDER BY "mediaId"'
UPDATE_SQL = (
    STALE_CTE
    + ' UPDATE "MediaFile" m SET "transcriptStatus" = \'FAILED\', "transcriptError" = s.reason'
    ' FROM stale s WHERE m."id" = s."mediaId" AND m."transcriptStatus" IN (\'QUEUED\', \'PROCESSING\')'
)


def backend_root(given: str | None) -> Path:
    if given:
        return Path(given).resolve()
    return Path(__file__).resolve().parents[2] / "backend"


def load_env(path: Path) -> None:
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip().removeprefix("export ").strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        os.environ.setdefault(key, value)


async def repair(client: Any, *, apply: bool) -> dict[str, Any]:
    rows = await client.query_raw(SELECT_SQL, FALLBACK_ERROR)
    changed = 0
    if apply and rows:
        changed = int(await client.execute_raw(UPDATE_SQL, FALLBACK_ERROR))
    return {"apply": apply, "found": len(rows), "changed": changed, "rows": rows}


async def run(backend: Path, apply: bool) -> int:
    sys.path.insert(0, str(backend))
    load_env(backend / ".env")
    from app.core.db import db

    await db.connect()
    try:
        answer = await repair(db, apply=apply)
    finally:
        await db.disconnect()
    for row in answer["rows"]:
        reason = str(row.get("reason") or "")
        print(f"  {row['mediaId']}  {row['stored']} -> FAILED  job {row['jobId']} ended {row.get('completedAt')}  {reason[:120]}")
    print(f"Found {answer['found']} recording(s) still QUEUED or PROCESSING whose last transcription job FAILED.")
    if apply:
        print(f"Marked {answer['changed']} FAILED with their job's reason. Running again finds none.")
    else:
        print("Dry run: nothing was changed. Run with --apply to mark them FAILED.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Mark recordings FAILED whose transcriptStatus still says QUEUED or PROCESSING after their last "
            "TRANSCRIPTION job FAILED, so the transcript view offers Retry. Dry run unless --apply."
        )
    )
    parser.add_argument("--apply", action="store_true", help="write the change; without it nothing is written")
    parser.add_argument("--backend", help="the backend directory whose app package and .env to use")
    args = parser.parse_args()
    return asyncio.run(run(backend_root(args.backend), args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
