from __future__ import annotations

import argparse
import asyncio
import pathlib
import sys
from typing import Any

BACKEND = pathlib.Path(__file__).resolve().parents[1]
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))


async def clear(*, dry_run: bool, client: Any = None) -> dict[str, Any]:
    from app.services import job_supersede

    counts = await job_supersede.superseded_counts(client)
    found = sum(counts.values())
    removed = 0 if dry_run or not found else await job_supersede.delete_superseded(client)
    return {"dryRun": dry_run, "found": found, "byJobType": counts, "removed": removed}


async def run(dry_run: bool) -> int:
    from app.core.db import connect_db, disconnect_db

    await connect_db()
    try:
        answer = await clear(dry_run=dry_run)
    finally:
        await disconnect_db()
    for job_type, n in answer["byJobType"].items():
        print(f"  {job_type:<20} {n} failed job(s) superseded by a later completed one")
    print(f"Found {answer['found']} superseded failed job(s).")
    if dry_run:
        print("Dry run: nothing was changed. Run without --dry-run to remove them.")
    else:
        print(f"Removed {answer['removed']}.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Remove FAILED media jobs that a later COMPLETED job for the same file and type superseded."
    )
    parser.add_argument("--dry-run", action="store_true", help="count only, change nothing")
    args = parser.parse_args()
    return asyncio.run(run(args.dry_run))


if __name__ == "__main__":
    raise SystemExit(main())
