"""Fill ``MediaFile.checksum`` with each recording's SHA-256, so dedup can find repeats.

Streams each AUDIO object from storage to a temp file in 1 MB blocks and hashes it; nothing is held
in the heap and NO PROVIDER IS CALLED. Rows whose checksum is already a SHA-256 are skipped, so the
script is idempotent. It writes no job and changes no transcript. Works the same in both portals.

    python scripts/backfill_media_checksums.py                 # DRY RUN: counts only, reads no object
    python scripts/backfill_media_checksums.py --fix           # hash and write
    python scripts/backfill_media_checksums.py --fix --limit 200
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
BACKEND = HERE.parent / "backend" if (HERE.parent / "backend").is_dir() else HERE.parent
sys.path.insert(0, str(BACKEND))

from app.core.db import connect_db, db, disconnect_db  # noqa: E402
from app.services.s3 import discard_temp, download_to_temp  # noqa: E402

SHA256 = re.compile(r"^[0-9a-f]{64}$")
BLOCK = 1024 * 1024


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while block := handle.read(BLOCK):
            digest.update(block)
    return digest.hexdigest()


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--fix", "--execute", action="store_true", help="hash and write (default: dry run)"
    )
    parser.add_argument("--limit", type=int, default=0, help="stop after this many rows (0 = all)")
    args = parser.parse_args()
    await connect_db()
    try:
        rows = await db.mediafile.find_many(
            where={"mediaType": "AUDIO"}, order={"createdAt": "asc"}
        )
        todo = [row for row in rows if not SHA256.match(str(row.checksum or "").strip().lower())]
        if args.limit:
            todo = todo[: args.limit]
        print(f"{len(rows)} recording(s); {len(todo)} without a SHA-256 checksum.")
        if not args.fix:
            print("DRY RUN: nothing was read from storage or written.")
            return 0
        written = failed = 0
        for row in todo:
            path = None
            try:
                path = await asyncio.to_thread(download_to_temp, row.objectKey)
                checksum = await asyncio.to_thread(_sha256, path)
                await db.mediafile.update(where={"id": row.id}, data={"checksum": checksum})
                written += 1
            except Exception as exc:  # noqa: BLE001 - one unreadable object must not stop the run
                failed += 1
                print(f"  {row.id}: could not hash ({type(exc).__name__})")
            finally:
                discard_temp(path)
        print(f"Wrote {written} checksum(s); {failed} could not be read. No provider was called.")
    finally:
        await disconnect_db()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
