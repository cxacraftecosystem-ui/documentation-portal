from __future__ import annotations

import argparse
import asyncio

from app.core.db import connect_db, disconnect_db
from app.services import search_indexer
from app.services.unified_search import LOCAL_TYPES


async def main() -> None:
    parser = argparse.ArgumentParser(description="Queue records for the search index.")
    parser.add_argument("--type", choices=LOCAL_TYPES, default=None)
    parser.add_argument(
        "--drain", action="store_true", help="build the queue now instead of leaving it to the worker"
    )
    args = parser.parse_args()
    await connect_db()
    try:
        queued = await search_indexer.reindex_all(args.type)
        print(f"queued {queued} records")
        if args.drain:
            built = 0
            while handled := await search_indexer.drain(batch=100):
                built += handled
            print(f"indexed {built} records")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
