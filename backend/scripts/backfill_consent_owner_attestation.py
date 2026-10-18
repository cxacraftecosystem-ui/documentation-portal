"""Record the owner's consent attestation of 18 Oct 2026 (owner correction (c); DESIGN 7.6).

THE OWNER CONFIRMED that consent exists for every recording made before this date, including the
ones still waiting for transcription. This writes that statement into the consent ledger, once per
recording, so the consent gate lets those files through and nobody is ever asked about them:

    Consent{subjectType: RECORDING, subjectId: <mediaFileId>, purpose: EXTERNAL_PROCESSING,
            state: GRANTED, basis: OWNER_ATTESTATION, source: "owner attestation",
            actorId: <the owner's user id>, recordedAt: 2026-10-18T00:00+05:30,
            note: "Owner attested on 18 Oct 2026 that consent exists for all recordings made before this date."}

SCOPE: every MediaFile of type AUDIO or VIDEO created on or before 2026-10-18 23:59 IST that still
exists (soft-removed ones included; hard-deleted ones are gone). Files made later get NO row: they
need per-artisan consent.

IT ALSO sets ``MediaFile.consentAtSend = GRANTED`` where a transcript already exists (the ~408 sent
before any consent record). It changes NO job, NO status and NO transcript, writes no flag and
queues nothing: transcription stays paused (correction (d)).

DRY RUN BY DEFAULT, idempotent (the ledger's unique key makes a second apply write nothing), and it
refuses a database host you did not name. NEVER run it as part of a test or from a laptop against
production; the owner approves the dry-run report first.

    cd backend
    python scripts/backfill_consent_owner_attestation.py --allow-host <db host>          # dry run + report
    python scripts/backfill_consent_owner_attestation.py --allow-host <db host> --apply \\
        --owner-email <owner> --approved-report ../docs/ops/<report with Approved-by line>
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import io
import sys
from datetime import UTC
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import qv2_guard  # noqa: E402

from app.services import naming  # noqa: E402
from app.services.consent_gate import ATTESTATION_CUTOFF, ATTESTATION_RECORDED_AT  # noqa: E402

NOTE = "Owner attested on 18 Oct 2026 that consent exists for all recordings made before this date."
SOURCE = "owner attestation"
RECORDED_AT = ATTESTATION_RECORDED_AT.astimezone(UTC).replace(tzinfo=None).isoformat(timespec="milliseconds")
CUTOFF = ATTESTATION_CUTOFF.astimezone(UTC).replace(tzinfo=None).isoformat(timespec="milliseconds")
SCOPE_SQL = (
    'FROM "MediaFile" m WHERE m."mediaType" IN (\'AUDIO\', \'VIDEO\') AND m."createdAt" <= $1::timestamp'
)
MISSING_SQL = (
    " AND NOT EXISTS (SELECT 1 FROM \"Consent\" c WHERE c.\"subjectType\" = 'RECORDING' AND c.\"subjectId\" = m.\"id\" "
    "AND c.\"purpose\" = 'EXTERNAL_PROCESSING' AND c.\"source\" = $2 AND c.\"recordedAt\" = $3::timestamp)"
)


async def plan(client: Any) -> dict[str, Any]:
    rows = await client.query_raw(
        'SELECT m."mediaType"::text AS type, COALESCE(m."transcriptStatus", \'(none)\') AS status, count(*) AS n, '
        "count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM \"Consent\" c WHERE c.\"subjectType\" = 'RECORDING' "
        "AND c.\"subjectId\" = m.\"id\" AND c.\"purpose\" = 'EXTERNAL_PROCESSING' AND c.\"source\" = $2 "
        'AND c."recordedAt" = $3::timestamp)) AS missing, '
        "count(*) FILTER (WHERE m.\"transcriptText\" IS NOT NULL AND m.\"consentAtSend\" IS NULL) AS send_mark "
        + SCOPE_SQL + " GROUP BY 1, 2 ORDER BY 1, 2",
        CUTOFF, SOURCE, RECORDED_AT,
    )
    later = await client.query_raw(
        'SELECT count(*) AS n FROM "MediaFile" m WHERE m."mediaType" IN (\'AUDIO\', \'VIDEO\') AND m."createdAt" > $1::timestamp',
        CUTOFF,
    )
    return {"rows": rows, "later": int(later[0]["n"]) if later else 0}


def report(p: dict[str, Any]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["mediaType", "transcriptStatus", "inScope", "rowsToWrite", "consentAtSendToMark"])
    totals = [0, 0, 0]
    for r in p["rows"]:
        writer.writerow([r["type"], r["status"], int(r["n"]), int(r["missing"]), int(r["send_mark"])])
        totals[0] += int(r["n"])
        totals[1] += int(r["missing"])
        totals[2] += int(r["send_mark"])
    writer.writerow(["TOTAL", "", *totals])
    writer.writerow(["created after the cutoff (no row; per-artisan consent needed)", "", p["later"], 0, 0])
    writer.writerow(["source", SOURCE, "recordedAt", "2026-10-18T00:00+05:30", ""])
    writer.writerow(["jobs, statuses and transcripts changed", "none", "", "", ""])
    return buffer.getvalue()


async def apply(client: Any, owner_id: str) -> tuple[int, int]:
    written = await client.execute_raw(
        'INSERT INTO "Consent" ("id", "subjectType", "subjectId", "purpose", "state", "basis", "source", "version", "note", '
        '"actorId", "recordedAt") SELECT md5(random()::text || m."id"), \'RECORDING\', m."id", \'EXTERNAL_PROCESSING\', \'GRANTED\', '
        "'OWNER_ATTESTATION', $2, 1, $4, $5, $3::timestamp " + SCOPE_SQL
        + ' ON CONFLICT ("subjectType", "subjectId", "purpose", "source", "recordedAt") DO NOTHING',
        CUTOFF, SOURCE, RECORDED_AT, NOTE, owner_id,
    )
    marked = await client.execute_raw(
        'UPDATE "MediaFile" m SET "consentAtSend" = \'GRANTED\' WHERE m."mediaType" IN (\'AUDIO\', \'VIDEO\') '
        'AND m."createdAt" <= $1::timestamp AND m."transcriptText" IS NOT NULL AND m."consentAtSend" IS NULL',
        CUTOFF,
    )
    return int(written or 0), int(marked or 0)


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    try:
        async with client.tx() as tx:
            await qv2_guard.assert_read_only(tx)
            text = report(await plan(tx))
        name = naming.export_filename(entity="Artisan", what="Consent-Backfill-Dry-Run-Report", ext="csv")
        if not args.apply:
            qv2_guard.write_report(args.out, name, text)
            print(text)
            print("DRY RUN: nothing was written. The owner approves the report before --apply.")
            return 0
        approver, day = qv2_guard.check_approval(args.approved_report, text)
        owner_email = (args.owner_email or approver).strip().lower()
        owners = await client.query_raw('SELECT "id" FROM "User" WHERE lower("email") = $1', owner_email)
        if not owners:
            raise qv2_guard.Refused(f"no user with the owner's email {owner_email!r}")
        async with client.tx() as tx:
            written, marked = await apply(tx, str(owners[0]["id"]))
        print(f"APPLIED (approved by {approver} on {day}): {written} consent rows written, {marked} files marked consentAtSend=GRANTED.")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    qv2_guard.add_common_arguments(parser)
    parser.add_argument("--owner-email", default=None, help="the owner's account (defaults to the Approved-by email)")
    args = parser.parse_args(argv)
    return asyncio.run(run(args))


if __name__ == "__main__":
    raise SystemExit(main())
