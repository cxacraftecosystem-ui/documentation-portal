"""Approve the still-pending files of records that were approved before files followed their record.

THE OWNER'S DECISION (2026-10-18). Approving a record approves its files that are still PENDING; a
reviewer can still reject any one file on its own. ``review.set_review_status`` does that from this
release on, in the approval's own transaction. Records approved BEFORE it left their files pending —
about 895 of them on the live database at the last dry run — and this script brings them into line,
once, with exactly the rules a live approval applies:

* only PENDING files move. A REJECTED file, or one already approved, keeps its decision;
* each file takes its record's reviewer and review moment, and gets its own ReviewLog line;
* the review ladder holds: a file uploaded by somebody at or above the record's reviewer stays
  pending for a reviewer who may judge it, and a late file (outside its workshop's dates) stays
  pending unless the record's reviewer was an admin;
* a record with no reviewer on file is skipped and counted, never guessed at.

DRY BY DEFAULT. Without ``--apply`` it reads and prints counts per record type and writes nothing.
With ``--apply`` it writes in batches of ``--batch-size`` records, one transaction per batch.

SAFE TO RE-RUN. It only ever looks at PENDING files, so a second ``--apply`` finds nothing to do and
says so; a run stopped part-way resumes where it stopped.

RUN IT AFTER THE RELEASE, on the box, against the live database (see docs/DEPLOYMENT.md). Never from a
laptop and never as part of a test run.

    cd backend
    python scripts/approve_media_of_approved_records.py            # dry run: counts only
    python scripts/approve_media_of_approved_records.py --apply    # write, in batches
"""

from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from app.api.routes.review import (
    _MEDIA_TAGS,
    approve_media_with_record,
    cascade_eligible,
    delegate_for,
    media_of_records,
)
from app.core.deps import get_value

#: The audit line each file approved here carries — the live note, marked as the backfill's.
BACKFILL_NOTE = "Approved with its {kind} record (backfill)."
#: One batch's transaction: up to ``--batch-size`` records and two statements per file.
BATCH_TX_TIMEOUT = timedelta(seconds=120)


@dataclass
class TypeCounts:
    records: int = 0
    files: int = 0
    left_pending: int = 0
    no_reviewer: int = 0


@dataclass
class Report:
    by_type: dict[str, TypeCounts] = field(default_factory=dict)
    applied: bool = False

    @property
    def files(self) -> int:
        return sum(counts.files for counts in self.by_type.values())

    def lines(self) -> list[str]:
        verb = "Approved" if self.applied else "Would approve"
        out = [f"{'type':<14}{'records':>9}{'files':>8}{'left':>7}{'no rev.':>9}"]
        for kind, counts in self.by_type.items():
            out.append(f"{kind:<14}{counts.records:>9}{counts.files:>8}{counts.left_pending:>7}{counts.no_reviewer:>9}")
        out.append(f"{verb} {self.files} file{'' if self.files == 1 else 's'} in total.")
        if not self.applied:
            out.append("Dry run: nothing was written. Pass --apply to write.")
        return out


async def _approved_batches(client: Any, kind: str, batch_size: int):
    """APPROVED records of one type, ``batch_size`` at a time, in id order (a stable cursor)."""
    delegate, _log_type, _relation = delegate_for(kind, client)
    cursor: str | None = None
    while True:
        where: dict[str, Any] = {"status": "APPROVED"}
        if cursor:
            where["id"] = {"gt": cursor}
        rows = await delegate.find_many(where=where, order={"id": "asc"}, take=batch_size)
        if not rows:
            return
        yield rows
        cursor = str(get_value(rows[-1], "id"))


async def run(client: Any, *, apply: bool, batch_size: int = 200, kinds: tuple[str, ...] | None = None) -> Report:
    report = Report(applied=apply)
    for kind in kinds or tuple(_MEDIA_TAGS):
        counts = report.by_type.setdefault(kind, TypeCounts())
        async for rows in _approved_batches(client, kind, batch_size):
            keys = [(kind, str(get_value(row, "id"))) for row in rows]
            pending = await media_of_records(keys, client=client)
            reviewer_ids = {str(get_value(row, "reviewedById")) for row in rows if get_value(row, "reviewedById")}
            users = await client.user.find_many(where={"id": {"in": sorted(reviewer_ids)}}) if reviewer_ids else []
            reviewers = {str(get_value(user, "id")): user for user in users}
            plan: list[tuple[Any, list[Any]]] = []
            for row, key in zip(rows, keys, strict=True):
                files = pending.get(key, [])
                if not files:
                    continue
                reviewer = reviewers.get(str(get_value(row, "reviewedById") or ""))
                if reviewer is None or not get_value(row, "reviewedAt"):
                    counts.no_reviewer += 1
                    counts.left_pending += len(files)
                    continue
                eligible = cascade_eligible(files, reviewer)
                counts.left_pending += len(files) - len(eligible)
                if eligible:
                    counts.records += 1
                    counts.files += len(eligible)
                    plan.append((row, eligible))
            if not apply or not plan:
                continue
            async with client.tx(timeout=BATCH_TX_TIMEOUT) as tx:
                written = 0
                for row, eligible in plan:
                    written += await approve_media_with_record(
                        tx,
                        kind,
                        eligible,
                        str(get_value(row, "reviewedById")),
                        get_value(row, "reviewedAt"),
                        note=BACKFILL_NOTE.format(kind=kind),
                    )
            # A file decided between the read and the write is skipped by the write; count what landed.
            counts.files -= sum(len(eligible) for _row, eligible in plan) - written
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--apply", action="store_true", help="write the approvals (default: dry run)")
    parser.add_argument("--batch-size", type=int, default=200, help="records per batch and transaction")
    parser.add_argument("--type", action="append", choices=sorted(_MEDIA_TAGS), help="limit to one record type")
    args = parser.parse_args()

    async def go() -> None:
        from app.core.db import connect_db, db, disconnect_db

        await connect_db()
        try:
            report = await run(db, apply=args.apply, batch_size=max(1, args.batch_size), kinds=tuple(args.type or ()) or None)
        finally:
            await disconnect_db()
        print("\n".join(report.lines()))

    asyncio.run(go())


if __name__ == "__main__":
    main()
