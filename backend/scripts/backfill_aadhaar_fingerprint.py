"""Fingerprint every Aadhaar number the artisan register still holds in full. Dry run by default.

WHY THIS EXISTS. Since 2026-10-17 the register keeps ``Artisan.aadhaarFingerprint`` (an HMAC of the
number under ``AADHAAR_FINGERPRINT_KEY``) and ``Artisan.aadhaarLast4``, never the number
(``services/aadhaar_fingerprint.py``). Rows written before that still carry the number in the legacy
``aadhaarNumber`` column. This script computes the two new columns for them, in batches, and
verifies what it wrote. It does NOT clear the legacy column: the guarded migration in
``prisma/deferred_migrations/`` does that, and refuses to run until this script reports nothing left.

It also rewrites the edit history (``RecordRevision.changes``) of artisans written before the
ledger stopped recording identity values: an ``aadhaarNumber`` entry that still holds a real old or
new value becomes the same valueless placeholder a new entry gets.

Run order: backend/DEPLOY_AWS.md, "Aadhaar fingerprint". Run it on the box, after the release that added
the columns is live, with the release's own ``.env`` (it needs the same key the API uses).

IDEMPOTENT. A row whose fingerprint already matches is skipped, and every write is conditional on
the row still holding exactly what was read, so a researcher's edit between the read and the write
wins and the row is counted as "changed under us". Running it twice changes nothing the second time.

IT PRINTS COUNTS ONLY: never an artisan id, a name, a number, its last four or a fingerprint.

Usage, from ``backend/`` (the release directory on the box)::

    python -m scripts.backfill_aadhaar_fingerprint                 # DRY RUN, writes nothing
    python -m scripts.backfill_aadhaar_fingerprint --apply
    python -m scripts.backfill_aadhaar_fingerprint --apply --retract-unusable

``--retract-unusable`` clears a legacy value that is not a valid 12-digit Aadhaar (it cannot be
fingerprinted, and it is not a number anybody holds); without it such rows are counted and left, and
the drop migration will refuse until somebody corrects them.

Exit status: 0 when nothing is left to do (or, in a dry run, always), 2 when the key is not
configured, 1 when ``--apply`` left rows unfinished.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from dataclasses import dataclass
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services import aadhaar_fingerprint as fp
from app.services.artisan_identity import aadhaar_error, normalize_aadhaar

DEFAULT_BATCH = 200


@dataclass
class Tally:
    seen: int = 0
    done_already: int = 0
    filled: int = 0
    would_fill: int = 0
    verified: int = 0
    unusable: int = 0
    retracted: int = 0
    clashes: int = 0
    other_fingerprint: int = 0
    raced: int = 0
    revisions_seen: int = 0
    revisions_scrubbed: int = 0
    revisions_would_scrub: int = 0

    @property
    def unfinished(self) -> int:
        return (
            self.would_fill
            + (self.unusable - self.retracted)
            + self.clashes
            + self.other_fingerprint
            + self.raced
            + self.revisions_would_scrub
        )

    def lines(self, apply: bool) -> list[str]:
        filled = f"filled {self.filled}, verified {self.verified}" if apply else (
            f"would fill {self.would_fill}"
        )
        return [
            f"artisans holding a legacy number: {self.seen}",
            f"already fingerprinted {self.done_already}, {filled}",
            f"not a valid Aadhaar {self.unusable} (retracted {self.retracted})",
            f"fingerprint already taken by another artisan {self.clashes}",
            f"fingerprint present but for a different number {self.other_fingerprint}",
            f"changed under us {self.raced}",
            f"edit-history entries holding a value: {self.revisions_seen}, "
            + (
                f"scrubbed {self.revisions_scrubbed}"
                if apply
                else f"would scrub {self.revisions_would_scrub}"
            ),
        ]


async def _artisans(*, batch: int, only_ids: list[str] | None) -> Any:
    """Every artisan with a legacy number, in id order, ``batch`` at a time."""
    cursor: str | None = None
    while True:
        where: dict[str, Any] = {"aadhaarNumber": {"not": None}}
        if only_ids is not None:
            where["id"] = {"in": only_ids}
        if cursor is not None:
            where["id"] = {**where.get("id", {}), "gt": cursor}
        rows = await db.artisan.find_many(where=where, order={"id": "asc"}, take=batch)
        if not rows:
            return
        yield rows
        cursor = rows[-1].id


async def _fill(tally: Tally, rows: list[Any], *, key: str, apply: bool, retract: bool) -> None:
    written: dict[str, str] = {}
    for row in rows:
        tally.seen += 1
        stored = row.aadhaarNumber
        number = normalize_aadhaar(stored)
        if not number or aadhaar_error(number):
            tally.unusable += 1
            if apply and retract:
                count = await db.artisan.update_many(
                    where={"id": row.id, "aadhaarNumber": stored, "aadhaarFingerprint": None},
                    data={"aadhaarNumber": None},
                )
                if count == 1:
                    tally.retracted += 1
                else:
                    tally.raced += 1
            continue
        expected = fp.fingerprint(number, key)
        if row.aadhaarFingerprint:
            if row.aadhaarFingerprint == expected and row.aadhaarLast4 == number[-4:]:
                tally.done_already += 1
            else:
                tally.other_fingerprint += 1
            continue
        if not apply:
            tally.would_fill += 1
            continue
        try:
            count = await db.artisan.update_many(
                where={"id": row.id, "aadhaarNumber": stored, "aadhaarFingerprint": None},
                data={"aadhaarFingerprint": expected, "aadhaarLast4": number[-4:]},
            )
        except Exception as exc:  # the unique index: another artisan already holds this number
            if "unique" in str(exc).lower():
                tally.clashes += 1
                continue
            raise
        if count == 1:
            tally.filled += 1
            written[row.id] = expected
        else:
            tally.raced += 1
    if written:
        # VERIFY BY READING BACK, not by trusting the count: the row must now carry the fingerprint
        # recomputed from its own legacy value.
        back = await db.artisan.find_many(where={"id": {"in": sorted(written)}})
        for row in back:
            number = normalize_aadhaar(row.aadhaarNumber)
            if (
                number
                and row.aadhaarFingerprint == written[row.id] == fp.fingerprint(number, key)
                and row.aadhaarLast4 == number[-4:]
            ):
                tally.verified += 1


def _scrubbed(entry: Any) -> Any:
    """The placeholder an ``aadhaarNumber`` ledger entry should hold, or None if it already does."""
    if (
        isinstance(entry, dict)
        and entry.get("redacted") is True
        and (entry.get("old"), entry.get("new")) in fp.LEDGER_PAIRS
    ):
        return None
    return fp.ledger_view(entry)


async def _scrub_revisions(tally: Tally, *, apply: bool, only_ids: list[str] | None) -> None:
    from prisma import Json

    rows = await db.query_raw(
        """SELECT "id", "recordId", "changes" FROM "RecordRevision"
           WHERE "recordType" = 'artisan' AND jsonb_typeof("changes") = 'object'
             AND "changes" ? 'aadhaarNumber'"""
    )
    for row in rows:
        if only_ids is not None and row["recordId"] not in only_ids:
            continue
        changes = row["changes"]
        if isinstance(changes, str):
            import json

            changes = json.loads(changes)
        placeholder = _scrubbed(changes.get("aadhaarNumber"))
        if placeholder is None:
            continue
        tally.revisions_seen += 1
        if not apply:
            tally.revisions_would_scrub += 1
            continue
        changes["aadhaarNumber"] = placeholder
        await db.recordrevision.update(where={"id": row["id"]}, data={"changes": Json(changes)})
        tally.revisions_scrubbed += 1


async def run(
    *,
    apply: bool,
    retract_unusable: bool = False,
    batch: int = DEFAULT_BATCH,
    only_ids: list[str] | None = None,
) -> Tally:
    """One pass. ``only_ids`` keeps a test to its own rows in a shared database."""
    key = fp.configured_key()
    tally = Tally()
    async for rows in _artisans(batch=batch, only_ids=only_ids):
        await _fill(tally, rows, key=key, apply=apply, retract=retract_unusable)
    await _scrub_revisions(tally, apply=apply, only_ids=only_ids)
    return tally


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is written")
    parser.add_argument(
        "--retract-unusable",
        action="store_true",
        help="clear legacy values that are not a valid Aadhaar (only with --apply)",
    )
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH)
    args = parser.parse_args(argv)

    try:
        fp.configured_key()
    except fp.FingerprintKeyMissing as exc:
        print(f"{exc} Nothing done.")
        return 2

    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise SystemExit(
            "Could not connect: re-run in UTF-8 mode: py -X utf8 -m scripts.backfill_aadhaar_fingerprint"
        ) from exc
    try:
        tally = await run(
            apply=args.apply,
            retract_unusable=args.retract_unusable,
            batch=max(1, args.batch_size),
        )
    finally:
        await disconnect_db()
    print("APPLIED:" if args.apply else "DRY RUN (pass --apply to write):")
    for line in tally.lines(args.apply):
        print(f"  {line}")
    left = tally.unfinished
    print(
        "Nothing left: the drop migration may now ship."
        if left == 0
        else f"{left} item(s) still need attention before the drop migration can run."
    )
    return 1 if args.apply and left else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
