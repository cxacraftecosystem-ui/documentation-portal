"""Copy every ``RecordingConsent`` row into the ``Consent`` ledger (the one consent store, 19 Oct 2026).

WHY. Until 19 Oct 2026 this portal kept consent in two tables: ``RecordingConsent`` (written by the
artisan form's "Recordings may be transcribed" switch, ``/media/{id}/recording-consent`` and the
first owner-attestation backfill) and the ``Consent`` ledger (the questionnaire). The owner decided
to keep ONE. Nothing writes ``RecordingConsent`` any more and every reader asks the ledger; until
this script has run, ``services/consent_gate.py`` also folds in the rows not yet copied.

WHAT IT WRITES, PER ``RecordingConsent`` ROW NOT COPIED BEFORE, one ledger row:

    a row for one recording (mediaFileId)  ->  subjectType RECORDING, subjectId <mediaFileId>
    a row for an artisan (artisanId)       ->  subjectType ARTISAN,   subjectId <artisanId>
    purpose EXTERNAL_PROCESSING; state = decision; recordedAt = attestedAt; note kept;
    source 'owner attestation' -> basis OWNER_ATTESTATION, source 'owner attestation';
    any other source            -> basis VERBAL_RECORDED,  source 'researcher';
    actorId = attestedById; migratedFrom = 'RecordingConsent:<id>'.

A row whose ``attestedById`` is empty or names no account that still exists cannot be copied as is
(the ledger names who recorded every consent). The dry run counts them; ``--apply`` attributes them
to ``--fallback-actor-email`` (default: the approver) and says so in the note. A row whose decision
is not GRANTED or REFUSED is reported and never copied.

IT CHANGES NOTHING ELSE: ``RecordingConsent`` is left as it is (a later release drops it), no job,
status or transcript is touched, and nothing is queued; transcription stays paused.

DRY RUN BY DEFAULT, inside a READ ONLY transaction; it refuses a database host you did not name and
``--apply`` refuses to start without the dry run's report approved by the owner. Idempotent: the
``migratedFrom`` key is unique, so a second apply copies nothing. NEVER run it as part of a test,
and never against production without the owner's approval of the dry-run report.

    cd backend
    python scripts/migrate_recording_consent_to_ledger.py --allow-host <db host>              # dry run + report
    python scripts/migrate_recording_consent_to_ledger.py --allow-host <db host> --apply \\
        --approved-report ../docs/ops/<report with an Approved-by line> [--fallback-actor-email <owner>]
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import io
import sys
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import qv2_guard  # noqa: E402

from app.services import naming  # noqa: E402
from app.transcription import ledger  # noqa: E402

TABLE = "RecordingConsent"
FALLBACK_NOTE = "Copied from RecordingConsent; the account that recorded it is no longer on record."
ROWS_SQL = (
    'SELECT rc."id", rc."mediaFileId", rc."artisanId", rc."decision", rc."source", rc."attestedById", '
    'rc."attestedAt", rc."note", (u."id" IS NOT NULL) AS "actorExists", '
    "EXISTS (SELECT 1 FROM \"Consent\" c WHERE c.\"migratedFrom\" = 'RecordingConsent:' || rc.\"id\") AS copied "
    'FROM "RecordingConsent" rc LEFT JOIN "User" u ON u."id" = rc."attestedById" '
    'ORDER BY rc."attestedAt", rc."createdAt", rc."id"'
)


def mapped(row: dict[str, Any]) -> dict[str, Any] | None:
    """The ledger row one ``RecordingConsent`` row becomes (``ledger.append``'s arguments), or None
    when it cannot be copied (no subject, or a decision that is not GRANTED or REFUSED)."""
    decision = str(row.get("decision") or "")
    if decision not in ledger.DECISIONS:
        return None
    if row.get("mediaFileId"):
        subject_type, subject_id = ledger.RECORDING, str(row["mediaFileId"])
    elif row.get("artisanId"):
        subject_type, subject_id = ledger.ARTISAN, str(row["artisanId"])
    else:
        return None
    owner = row.get("source") == ledger.SOURCE_OWNER
    recorded = row.get("attestedAt")
    if isinstance(recorded, str):
        recorded = datetime.fromisoformat(recorded)
    if isinstance(recorded, datetime) and recorded.tzinfo is None:
        recorded = recorded.replace(tzinfo=UTC)
    return {
        "subject_type": subject_type,
        "subject_id": subject_id,
        "purpose": ledger.EXTERNAL_PROCESSING,
        "state": decision,
        "basis": ledger.OWNER_ATTESTATION if owner else ledger.VERBAL_RECORDED,
        "source": ledger.SOURCE_OWNER if owner else ledger.SOURCE_RESEARCHER,
        "note": row.get("note"),
        "actor_id": str(row.get("attestedById") or ""),
        "recorded_at": recorded,
        "migrated_from": ledger.legacy_key(TABLE, str(row["id"])),
    }


def plan(rows: list[dict[str, Any]]) -> dict[str, Any]:
    counts: Counter[tuple[str, str, str, str]] = Counter()
    to_copy: list[tuple[dict[str, Any], bool]] = []  # (ledger row, needs the fallback actor)
    copied = unreadable = 0
    for row in rows:
        if row.get("copied"):
            copied += 1
            continue
        target = mapped(row)
        if target is None:
            unreadable += 1
            continue
        needs_actor = not (row.get("attestedById") and row.get("actorExists"))
        to_copy.append((target, needs_actor))
        counts[(target["subject_type"], target["state"], target["source"], "fallback actor" if needs_actor else "")] += 1
    return {"total": len(rows), "copied": copied, "unreadable": unreadable, "to_copy": to_copy, "counts": counts}


def report(p: dict[str, Any]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["subjectType", "state", "source", "actor", "rowsToCopy"])
    for (subject_type, state, source, actor), n in sorted(p["counts"].items()):
        writer.writerow([subject_type, state, source, actor or "as recorded", n])
    writer.writerow(["TOTAL to copy", "", "", "", len(p["to_copy"])])
    writer.writerow(["already copied", "", "", "", p["copied"]])
    writer.writerow(["not copyable (no subject, or a decision other than GRANTED/REFUSED)", "", "", "", p["unreadable"]])
    writer.writerow(["RecordingConsent rows in all", "", "", "", p["total"]])
    writer.writerow(["RecordingConsent rows changed or deleted", "none", "", "", ""])
    writer.writerow(["jobs, statuses and transcripts changed", "none", "", "", ""])
    return buffer.getvalue()


async def apply(client: Any, p: dict[str, Any], fallback_actor_id: str | None) -> int:
    written = 0
    for target, needs_actor in p["to_copy"]:
        row = dict(target)
        if needs_actor:
            if not fallback_actor_id:
                raise qv2_guard.Refused("rows name no account that still exists; pass --fallback-actor-email")
            row["actor_id"] = fallback_actor_id
            row["note"] = " ".join(part for part in ((row.get("note") or "").strip(), FALLBACK_NOTE) if part)
        if await ledger.append(client, **row) is not None:
            written += 1
    return written


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    try:
        async with client.tx() as tx:
            await qv2_guard.assert_read_only(tx)
            text = report(plan([dict(r) for r in await tx.query_raw(ROWS_SQL)]))
        name = naming.export_filename(entity="Artisan", what="Consent-Store-Merge-Dry-Run-Report", ext="csv")
        if not args.apply:
            qv2_guard.write_report(args.out, name, text)
            print(text)
            print("DRY RUN: nothing was written. The owner approves the report before --apply.")
            return 0
        approver, day = qv2_guard.check_approval(args.approved_report, text)
        fallback_id = None
        email = (args.fallback_actor_email or approver).strip().lower()
        users = await client.query_raw('SELECT "id" FROM "User" WHERE lower("email") = $1', email)
        if users:
            fallback_id = str(users[0]["id"])
        async with client.tx() as tx:
            p = plan([dict(r) for r in await tx.query_raw(ROWS_SQL)])
            written = await apply(tx, p, fallback_id)
        print(f"APPLIED (approved by {approver} on {day}): {written} consent rows copied into the ledger.")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    qv2_guard.add_common_arguments(parser)
    parser.add_argument(
        "--fallback-actor-email", default=None,
        help="the account rows naming no existing account are attributed to (defaults to the approver)",
    )
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
