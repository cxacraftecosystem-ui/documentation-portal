"""Purge what owner decision 4 allows, and nothing else (DESIGN 3.8 and 7.5).

PURGED, 30 DAYS AFTER REMOVAL:
* a soft-removed file whose removal was a VERIFIED byte-identical copy: ``shaVerifiedAt`` is set
  and a LIVE, verified file with the same ``contentSha256`` still exists (that one is kept);
* a bucket object listed in the orphan report (``--orphans-csv``) that is still unclaimed (no
  MediaFile names it) 30 days after the report.

NEVER PURGED AUTOMATICALLY: a distinct take, whatever its state. An admin hard-purge from "Removed
takes" stays manual and only after the session is approved.

Every purge writes the key, size and sha to RecordRevision first, so a report can say what went.

DRY RUN BY DEFAULT; ``--apply`` needs the owner's approved report (the go-ahead is an owner item)
and refuses an unlisted database host. It is meant to run from the nightly job queue AFTER that
go-ahead; it has not been run against production.

    cd backend
    python scripts/questionnaire_purge.py --allow-host <db host> [--orphans-csv <report.csv>]
    python scripts/questionnaire_purge.py --allow-host <db host> --apply --approved-report <approved.csv>
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import io
import json
import sys
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import qv2_guard  # noqa: E402

from app.services import naming  # noqa: E402

GRACE = timedelta(days=30)


async def duplicate_candidates(client: Any, *, now: datetime) -> list[dict[str, Any]]:
    cutoff = (now - GRACE).astimezone(UTC).replace(tzinfo=None).isoformat(timespec="milliseconds")
    return await client.query_raw(
        'SELECT m."id", m."objectKey", m."sizeBytes", m."contentSha256", m."removedAt", '
        '(SELECT k."id" FROM "MediaFile" k WHERE k."contentSha256" = m."contentSha256" AND k."id" <> m."id" '
        '  AND k."removedAt" IS NULL AND k."shaVerifiedAt" IS NOT NULL ORDER BY k."createdAt" LIMIT 1) AS kept '
        'FROM "MediaFile" m WHERE m."removedAt" IS NOT NULL AND m."removedAt" < $1::timestamp '
        'AND m."shaVerifiedAt" IS NOT NULL AND m."contentSha256" IS NOT NULL '
        "AND m.\"extraMetadata\"->>'removedAs' = 'VERIFIED_DUPLICATE' "
        'AND EXISTS (SELECT 1 FROM "MediaFile" k WHERE k."contentSha256" = m."contentSha256" AND k."id" <> m."id" '
        '  AND k."removedAt" IS NULL AND k."shaVerifiedAt" IS NOT NULL) ORDER BY m."removedAt", m."id"',
        cutoff,
    )


def orphan_candidates(path: str | None, *, now: datetime, known: set[str]) -> list[dict[str, Any]]:
    """Rows of the orphan report (``objectKey, sizeBytes, reportedAt``) still unclaimed after 30 days."""
    if not path:
        return []
    out = []
    with open(path, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            key = (row.get("objectKey") or "").strip()
            reported = row.get("reportedAt") or ""
            try:
                when = datetime.fromisoformat(reported.replace("Z", "+00:00"))
            except ValueError:
                continue
            when = when if when.tzinfo else when.replace(tzinfo=UTC)
            if key and key not in known and now - when >= GRACE:
                out.append({"objectKey": key, "sizeBytes": row.get("sizeBytes"), "reportedAt": reported})
    return out


def report(dupes: list[dict[str, Any]], orphans: list[dict[str, Any]], as_of_day: str | None = None) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["kind", "mediaFileId", "objectKey", "sizeBytes", "contentSha256", "keptMediaFileId", "since"])
    for d in dupes:
        writer.writerow(["VERIFIED_DUPLICATE", d["id"], d["objectKey"], d["sizeBytes"], d["contentSha256"], d.get("kept"), str(d["removedAt"])[:10]])
    for o in orphans:
        writer.writerow(["UNCLAIMED_ORPHAN", "", o["objectKey"], o.get("sizeBytes") or "", "", "", str(o["reportedAt"])[:10]])
    writer.writerow(["TOTAL", len(dupes) + len(orphans), "", "", "", "", ""])
    writer.writerow(["never purged", "distinct takes", "", "", "", "", ""])
    if as_of_day:
        writer.writerow(["as of", as_of_day, "", "", "", "", ""])
    return buffer.getvalue()


async def purge(client: Any, dupes: list[dict[str, Any]], orphans: list[dict[str, Any]], actor_id: str, *, delete_object: Any) -> int:
    done = 0
    for d in dupes:
        async with client.tx() as tx:
            await tx.execute_raw(
                'INSERT INTO "RecordRevision" ("id", "recordType", "recordId", "editedById", "changes") VALUES ($1, \'media\', $2, $3, $4::jsonb)',
                uuid4().hex, d["id"], actor_id,
                json.dumps({"purged": {"objectKey": d["objectKey"], "sizeBytes": int(d["sizeBytes"] or 0), "sha256": d["contentSha256"], "keptMediaFileId": d.get("kept")}}),
            )
            rec = await tx.query_raw('SELECT "id" FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1', d["id"])
            for r in rec:
                await tx.execute_raw('UPDATE "QuestionnaireAnswerRevision" SET "sourceRecordingId" = NULL WHERE "sourceRecordingId" = $1', r["id"])
                await tx.execute_raw('UPDATE "QuestionnaireRecording" SET "continuationOfId" = NULL WHERE "continuationOfId" = $1', r["id"])
                await tx.execute_raw('UPDATE "QuestionnaireRecording" SET "replacesRecordingId" = NULL WHERE "replacesRecordingId" = $1', r["id"])
                await tx.execute_raw('DELETE FROM "QuestionnaireRecording" WHERE "id" = $1', r["id"])
            await tx.execute_raw('DELETE FROM "MediaFile" WHERE "id" = $1', d["id"])
        still = await client.query_raw('SELECT 1 FROM "MediaFile" WHERE "objectKey" = $1', d["objectKey"])
        if not still:
            await asyncio.to_thread(delete_object, d["objectKey"])
        done += 1
    for o in orphans:
        await client.execute_raw(
            'INSERT INTO "RecordRevision" ("id", "recordType", "recordId", "editedById", "changes") VALUES ($1, \'media\', $2, $3, $4::jsonb)',
            uuid4().hex, o["objectKey"], actor_id, json.dumps({"purged": {"objectKey": o["objectKey"], "sizeBytes": o.get("sizeBytes"), "orphan": True}}),
        )
        await asyncio.to_thread(delete_object, o["objectKey"])
        done += 1
    return done


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    # The report is computed AS OF a fixed day, not "now", so the owner's approval does not go stale
    # overnight (audit Low); --apply repeats the dry run's --as-of.
    now = qv2_guard.as_of(args)
    try:
        async with client.tx() as tx:
            await qv2_guard.assert_read_only(tx)
            dupes = await duplicate_candidates(tx, now=now)
            keys = {str(r["objectKey"]) for r in await tx.query_raw('SELECT "objectKey" FROM "MediaFile"')}
        orphans = orphan_candidates(args.orphans_csv, now=now, known=keys)
        text = report(dupes, orphans, now.astimezone(timezone(timedelta(hours=5, minutes=30))).date().isoformat())
        name = naming.export_filename(entity="Dataset", what="Purge-Dry-Run-Report", ext="csv")
        if not args.apply:
            qv2_guard.write_report(args.out, name, text)
            print(f"DRY RUN: {len(dupes)} verified duplicates and {len(orphans)} unclaimed orphans would be purged. Nothing was written.")
            return 0
        approver, _day = qv2_guard.check_approval(args.approved_report, text)
        owners = await client.query_raw('SELECT "id" FROM "User" WHERE lower("email") = $1', approver.lower())
        if not owners:
            raise qv2_guard.Refused(f"no user with the approver's email {approver!r}")
        from app.services.s3 import delete_object

        done = await purge(client, dupes, orphans, str(owners[0]["id"]), delete_object=delete_object)
        print(f"APPLIED: {done} purged.")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    qv2_guard.add_common_arguments(parser)
    qv2_guard.add_as_of(parser)
    parser.add_argument("--orphans-csv", default=None, help="the orphan report (objectKey,sizeBytes,reportedAt)")
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
