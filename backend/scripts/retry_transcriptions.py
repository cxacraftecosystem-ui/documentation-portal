from __future__ import annotations

import asyncio
import pathlib
import sys
from collections import Counter
from datetime import UTC, datetime

HERE = pathlib.Path(__file__).resolve().parent
BACKEND = HERE.parent / "backend" if (HERE.parent / "backend").is_dir() else HERE.parent
sys.path.insert(0, str(BACKEND))

from app.core.db import connect_db, db, disconnect_db  # noqa: E402
from app.services import app_settings  # noqa: E402


async def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    await connect_db()
    try:
        paused = await app_settings.load_transcription_paused()
        failed = await db.mediaprocessingjob.find_many(
            where={"jobType": "TRANSCRIPTION", "status": "FAILED"}
        )
        open_jobs = await db.mediaprocessingjob.find_many(
            where={"jobType": "TRANSCRIPTION", "status": {"in": ["QUEUED", "PROCESSING"]}}
        )
        open_media = {job.mediaFileId for job in open_jobs}
        retryable = {job.mediaFileId for job in failed if job.mediaFileId not in open_media}
        reasons = Counter(str(job.error or "no error recorded")[:120] for job in failed)
        now = datetime.now(UTC)
        held = [
            job
            for job in open_jobs
            if job.status == "QUEUED" and job.runAfter and job.runAfter > now
        ]
        print(f"Transcription is {'PAUSED' if paused else 'running'}.")
        print(f"FAILED jobs: {len(failed)}; recordings a retry would requeue: {len(retryable)}.")
        for reason, count in reasons.most_common(15):
            print(f"  {count:5d}  {reason}")
        print(f"QUEUED jobs: {len(open_jobs)}; held back for later: {len(held)}.")
        if held:
            print(f"  next due: {min(job.runAfter for job in held).isoformat()}")
        print("DRY RUN: nothing was changed and no provider was called.")
    finally:
        await disconnect_db()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
