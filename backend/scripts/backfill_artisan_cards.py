"""Issue an artisan card to every artisan who has no live one. Dry run by default.

Cards are also issued lazily (on the first card read, register page or print), so this script is
what makes every row carry a card from the day the release goes out: the Android register caches
card codes, and a phone can only match a scanned card it has already been told about.

Run it on the box after the release with the ``20261023090000_artisan_cards`` migration is live,
with the release's own ``.env`` (it needs ARTISAN_CARD_KEY so that cards are active).

IDEMPOTENT. It only ever adds a card where none is live, in batches of 500 inside one transaction
each; the partial unique index ``ArtisanCard_one_live_per_artisan`` means a card issued by a
request in the meantime is kept, not duplicated. Running it twice issues nothing the second time.

IT PRINTS COUNTS ONLY: never an artisan id, a name or a card code.

Usage, from ``backend/``::

    python -m scripts.backfill_artisan_cards            # DRY RUN, writes nothing
    python -m scripts.backfill_artisan_cards --apply

Exit status: 0 when every artisan has a live card (or in a dry run), 2 when cards are not active
on this server, 1 when ``--apply`` left artisans without one.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from dataclasses import dataclass
from typing import Any

DEFAULT_BATCH = 500


@dataclass
class Tally:
    seen: int = 0
    had_card: int = 0
    issued: int = 0
    would_issue: int = 0

    @property
    def missing(self) -> int:
        return self.seen - self.had_card - self.issued


async def run(client: Any, settings: Any, *, apply: bool, batch: int = DEFAULT_BATCH) -> Tally:
    from app.services import artisan_card_store as store

    tally = Tally()
    cursor: str | None = None
    while True:
        query: dict[str, Any] = {"take": batch, "order": {"id": "asc"}}
        if cursor:
            query["cursor"] = {"id": cursor}
            query["skip"] = 1
        rows = await client.artisan.find_many(**query)
        if not rows:
            break
        cursor = rows[-1].id
        ids = [row.id for row in rows]
        tally.seen += len(ids)
        live = await store.live_cards(client, ids)
        tally.had_card += len(live)
        missing = [i for i in ids if i not in live]
        if not missing:
            continue
        if not apply:
            tally.would_issue += len(missing)
            continue
        cards = await store.ensure_cards(client, missing, settings=settings, user_id=None)
        tally.issued += len(cards)
    return tally


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is written")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH)
    args = parser.parse_args(argv)

    from app.core.config import get_settings
    from app.core.db import connect_db, db, disconnect_db
    from app.services import artisan_card

    settings = get_settings()
    if not artisan_card.active(settings):
        print(
            "Artisan cards are not active here (ARTISAN_CARDS_ENABLED and ARTISAN_CARD_KEY). Nothing done."
        )
        return 2
    await connect_db()
    try:
        tally = await run(db, settings, apply=args.apply, batch=max(1, args.batch_size))
    finally:
        await disconnect_db()
    print("APPLIED:" if args.apply else "DRY RUN (pass --apply to write):")
    print(f"  artisans seen: {tally.seen}")
    print(f"  already had a live card: {tally.had_card}")
    if args.apply:
        print(f"  cards issued: {tally.issued}")
    else:
        print(f"  would issue: {tally.would_issue}")
    if args.apply and tally.missing:
        print(f"{tally.missing} artisan(s) still have no card.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
