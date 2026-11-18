from __future__ import annotations

import argparse
import asyncio
import json
import sys


async def _run(dry_run: bool) -> dict[str, object]:
    from app.core.config import get_settings
    from app.core.db import connect_db, db, disconnect_db
    from app.services import cross_portal, field_voices_portal
    from app.services.field_voices.refresh import Refresher

    settings = get_settings()
    await connect_db()
    try:
        counts = await Refresher(db, field_voices_portal, settings, dry_run=dry_run).run()
    finally:
        await disconnect_db()
    return {
        "portal": field_voices_portal.PORTAL,
        "dryRun": dry_run,
        "gateOn": cross_portal.field_voices_active(settings),
        "imagesOn": cross_portal.field_voices_images_active(settings),
        "counts": counts,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="refresh_field_voices")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    result = asyncio.run(_run(args.dry_run))
    sys.stdout.write(json.dumps(result, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
