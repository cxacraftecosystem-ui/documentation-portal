"""Fill each recording's verified SHA-256 (``MediaFile.contentSha256`` + ``shaVerifiedAt``), so dedup can find repeats.

Streams each AUDIO or VIDEO object from storage in 1 MB blocks and hashes it; nothing is held in the
heap and NO PROVIDER IS CALLED. It writes the column the questionnaire's duplicate rules read
(``contentSha256``, stamped ``shaVerifiedAt``; audit Low of 21 Oct 2026: it used to write only the
older ``checksum``), and keeps ``checksum`` equal for the readers of that column. Rows already
verified are skipped, so the script is idempotent. It writes no job and changes no transcript.

Like every data script here it is a DRY RUN by default and refuses a database host not named in
``--allow-host`` (``scripts/qv2_guard.py``): production has to be typed by a person.

    python scripts/backfill_media_checksums.py --allow-host <db host>               # DRY RUN: counts only
    python scripts/backfill_media_checksums.py --allow-host <db host> --fix         # hash and write
    python scripts/backfill_media_checksums.py --allow-host <db host> --fix --limit 200
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import pathlib
import sys
from typing import Any

HERE = pathlib.Path(__file__).resolve().parent
BACKEND = HERE.parent / "backend" if (HERE.parent / "backend").is_dir() else HERE.parent
sys.path.insert(0, str(BACKEND))

from scripts import qv2_guard  # noqa: E402

BLOCK = 1024 * 1024
TODO_SQL = (
    'SELECT "id", "objectKey" FROM "MediaFile" WHERE "mediaType" IN (\'AUDIO\', \'VIDEO\') AND "shaVerifiedAt" IS NULL '
    'ORDER BY "createdAt", "id"'
)


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while block := handle.read(BLOCK):
            digest.update(block)
    return digest.hexdigest()


async def todo(client: Any, limit: int = 0) -> list[dict[str, Any]]:
    rows = await client.query_raw(TODO_SQL)
    return rows[:limit] if limit else rows


async def fill(client: Any, rows: list[dict[str, Any]], *, fetch: Any, discard: Any) -> tuple[int, int]:
    written = failed = 0
    for row in rows:
        path = None
        try:
            path = await asyncio.to_thread(fetch, row["objectKey"])
            checksum = await asyncio.to_thread(_sha256, path)
            await client.execute_raw(
                'UPDATE "MediaFile" SET "contentSha256" = $2, "shaVerifiedAt" = now(), "checksum" = $2 WHERE "id" = $1',
                row["id"], checksum,
            )
            written += 1
        except Exception as exc:  # noqa: BLE001 - one unreadable object must not stop the run
            failed += 1
            print(f"  {row['id']}: could not hash ({type(exc).__name__})")
        finally:
            discard(path)
    return written, failed


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    try:
        rows = await todo(client, args.limit)
        print(f"{len(rows)} recording(s) without a verified SHA-256.")
        if not args.fix:
            print("DRY RUN: nothing was read from storage or written.")
            return 0
        from app.services.s3 import discard_temp, download_to_temp

        written, failed = await fill(client, rows, fetch=download_to_temp, discard=discard_temp)
        print(f"Wrote {written} checksum(s); {failed} could not be read. No provider was called.")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--database-url", default=None, help="defaults to $DATABASE_URL; the host must be in --allow-host")
    parser.add_argument("--allow-host", action="append", default=[], help="a database host this run may touch (repeatable)")
    parser.add_argument("--fix", "--execute", action="store_true", help="hash and write (default: dry run)")
    parser.add_argument("--limit", type=int, default=0, help="stop after this many rows (0 = all)")
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
