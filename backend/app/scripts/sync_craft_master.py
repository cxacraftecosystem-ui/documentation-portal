"""Bring the craft tables into line with ``app/data/craft_master_list.json`` (docs/CRAFTS.md).

    cd backend && python -m app.scripts.sync_craft_master --dry-run
    cd backend && python -m app.scripts.sync_craft_master

Upserts by slug and never deletes; a seeded craft that has left the file becomes LEGACY. Run it after
the file is revised. The same file in both portals.
"""

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
