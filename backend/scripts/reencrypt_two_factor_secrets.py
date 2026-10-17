"""Move every two-step secret onto the current ``TWO_FACTOR_ENCRYPTION_KEY``.

DRY RUN BY DEFAULT. Without ``--apply`` it only counts: how many secrets are already on the current
key, how many would move, and how many cannot be opened with any configured key (those are reported
by account id, never by content — the person needs a recovery code or an administrator's reset).

IDEMPOTENT. A secret already on the current key is skipped; each write is conditional on the
ciphertext still being the one this run read, so it is safe beside a running API (which re-encrypts
on use too) and safe to run twice. Both the live secret and a pending (scanned, not yet confirmed)
one are moved.

    cd backend && python -m scripts.reencrypt_two_factor_secrets           # count only
    cd backend && python -m scripts.reencrypt_two_factor_secrets --apply   # write
    cd backend && python -m scripts.reencrypt_two_factor_secrets --generate-key   # print a new key

EXIT CODES: 0 every secret is readable; 3 some cannot be opened with any configured key (the deploy
fails on this: a key was lost on the way to the box); 2 no key is configured; 1 the run itself broke.

Rotation: set the new key, move the old one to ``TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS``, restart,
run with ``--apply``, and drop the old key once a dry run reports ``would move: 0`` and
``unreadable: 0``.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from dataclasses import dataclass, field

COLUMNS = ("twoFactorSecret", "twoFactorPendingSecret")
#: Distinct from a crash's 1, so the deploy can tell "a key is missing" from "the database was busy".
UNREADABLE_EXIT = 3


@dataclass
class Report:
    current: int = 0
    would_move: int = 0
    moved: int = 0
    raced: int = 0
    unreadable: list[str] = field(default_factory=list)

    def lines(self, applied: bool) -> list[str]:
        out = [
            f"already on the current key: {self.current}",
            f"{'moved' if applied else 'would move'}: {self.moved if applied else self.would_move}",
        ]
        if applied and self.raced:
            out.append(f"changed by somebody else meanwhile (left alone, run again): {self.raced}")
        out.append(f"unreadable with every configured key: {len(self.unreadable)}")
        out.extend(f"  unreadable: {entry}" for entry in self.unreadable)
        return out


async def reencrypt_all(db_client, *, apply: bool) -> Report:
    """The whole pass, against a connected Prisma client (or a test's fake)."""
    from app.core.deps import invalidate_cached_user
    from app.services import two_factor

    report = Report()
    for column in COLUMNS:
        rows = await db_client.user.find_many(where={column: {"not": None}})
        for row in rows:
            stored = getattr(row, column, None)
            if not stored:
                continue
            if not two_factor.needs_reencryption(stored):
                report.current += 1
                continue
            plain = two_factor.decrypt_secret(stored)
            if not plain:
                report.unreadable.append(f"{row.id} ({column})")
                continue
            report.would_move += 1
            if not apply:
                continue
            won = await db_client.user.update_many(
                where={"id": row.id, column: stored},
                data={column: two_factor.encrypt_secret(plain)},
            )
            if won:
                report.moved += 1
                invalidate_cached_user(row.id)
            else:
                report.raced += 1
    return report


async def _run(apply: bool) -> int:
    from app.core.config import get_settings
    from app.core.db import connect_db, db, disconnect_db

    if not (get_settings().two_factor_encryption_key or "").strip():
        print("TWO_FACTOR_ENCRYPTION_KEY is not set: there is no key to move secrets onto.", file=sys.stderr)
        return 2
    await connect_db()
    try:
        report = await reencrypt_all(db, apply=apply)
    finally:
        await disconnect_db()
    print("APPLIED" if apply else "DRY RUN (nothing written; pass --apply to write)")
    for line in report.lines(apply):
        print(line)
    return UNREADABLE_EXIT if report.unreadable else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--apply", action="store_true", help="write; without it, only count")
    parser.add_argument("--generate-key", action="store_true", help="print a new key entry and exit")
    parser.add_argument("--key-id", default=None, help="the key id for --generate-key (default k<YYYYMMDD>)")
    args = parser.parse_args(argv)
    if args.generate_key:
        from app.core.two_factor_keys import generate_key

        print(generate_key(args.key_id))
        return 0
    return asyncio.run(_run(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
