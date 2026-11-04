from __future__ import annotations

import argparse
import asyncio
import json

from app.services import craft_master


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Sync the craft master list into the database.")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    from app.core.db import connect_db, db, disconnect_db

    await connect_db()
    try:
        report = await craft_master.sync_master(db, dry_run=args.dry_run)
    finally:
        await disconnect_db()
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
