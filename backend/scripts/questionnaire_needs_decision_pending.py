"""Set the files of takes waiting for a decision back to PENDING (audit M1; DESIGN 7.3).

The backfill's docstring promised "NEEDS_DECISION takes stay PENDING", but nothing wrote it: on the
live database 26 of the 29 NEEDS_DECISION recordings have their file APPROVED, inside 14 approved
sessions. A take nobody has decided on must never ride an approval, so this sets each such file to
PENDING and records what it was, per run, so ``--undo`` puts it back. The session's own status is not
touched (it stays APPROVED; the take reaches review through the inbox's "Needs a decision").

DRY RUN BY DEFAULT, in a verified READ ONLY transaction, writing a report whose content does not
depend on the day. ``--apply`` needs ``--run-id`` and the owner's approval of THAT report
(``Approved-by: <owner email> <YYYY-MM-DD>``), refuses a host not named in ``--allow-host``, and is
idempotent. It has NOT been run against production; the owner runs the dry run and approves it.

    cd backend
    python scripts/questionnaire_needs_decision_pending.py --allow-host <db host>               # dry run + report
    python scripts/questionnaire_needs_decision_pending.py --allow-host <db host> --apply \\
        --run-id <id> --approved-report ../docs/ops/<report with Approved-by line>
    python scripts/questionnaire_needs_decision_pending.py --allow-host <db host> --undo --run-id <id>
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import io
import json
import sys
from datetime import timedelta
from pathlib import Path
from typing import Any
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import qv2_guard  # noqa: E402

from app.services import naming  # noqa: E402

CANDIDATES_SQL = (
    'SELECT r."id" AS "recordingId", m."id" AS "mediaFileId", m."status"::text AS status, r."decisionKind", '
    'm."questionnaireInterviewId" AS "sessionId", i."status"::text AS "sessionStatus" '
    'FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
    'LEFT JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
    "WHERE r.\"filing\" = 'NEEDS_DECISION' AND r.\"removedAt\" IS NULL AND m.\"removedAt\" IS NULL AND m.\"status\" <> 'PENDING' "
    'ORDER BY m."questionnaireInterviewId", r."id"'
)


async def candidates(client: Any) -> list[dict[str, Any]]:
    return await client.query_raw(CANDIDATES_SQL)


def report(rows: list[dict[str, Any]]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["recordingId", "mediaFileId", "statusNow", "statusAfter", "decisionKind", "sessionId", "sessionStatus"])
    for r in rows:
        writer.writerow([r["recordingId"], r["mediaFileId"], r["status"], "PENDING", r.get("decisionKind") or "", r.get("sessionId") or "",
                         r.get("sessionStatus") or ""])
    sessions = {r.get("sessionId") for r in rows if r.get("sessionStatus") == "APPROVED"}
    writer.writerow(["TOTAL", len(rows), "", "", "", f"approved sessions: {len(sessions)}", ""])
    writer.writerow(["unchanged", "session status, recordings, transcripts, jobs", "", "", "", "", ""])
    return buffer.getvalue()


async def apply(client: Any, rows: list[dict[str, Any]], run_id: str, actor_id: str) -> int:
    done = 0
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=120)) as tx:
        for r in rows:
            changed = await tx.execute_raw(
                'UPDATE "MediaFile" SET "status" = \'PENDING\' WHERE "id" = $1 AND "status"::text = $2', r["mediaFileId"], r["status"]
            )
            if not changed:
                continue
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireRecordingEvent" ("id", "recordingId", "kind", "before", "after", "actorId") '
                "VALUES ($1, $2, 'STATUS_FIXUP', $3::jsonb, $4::jsonb, $5)",
                uuid4().hex, r["recordingId"], json.dumps({"status": r["status"]}), json.dumps({"status": "PENDING", "runId": run_id}), actor_id,
            )
            done += 1
    return done


async def undo(client: Any, run_id: str) -> int:
    rows = await client.query_raw(
        'SELECT e."id", e."before"->>\'status\' AS status, r."mediaFileId" FROM "QuestionnaireRecordingEvent" e '
        'JOIN "QuestionnaireRecording" r ON r."id" = e."recordingId" '
        "WHERE e.\"kind\" = 'STATUS_FIXUP' AND e.\"after\"->>'runId' = $1",
        run_id,
    )
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=120)) as tx:
        for r in rows:
            await tx.execute_raw(
                'UPDATE "MediaFile" SET "status" = $2::"RecordStatus" WHERE "id" = $1 AND "status" = \'PENDING\'', r["mediaFileId"], r["status"]
            )
            await tx.execute_raw('DELETE FROM "QuestionnaireRecordingEvent" WHERE "id" = $1', r["id"])
    return len(rows)


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    try:
        if args.undo:
            if not args.run_id:
                raise qv2_guard.Refused("--undo needs --run-id")
            print(f"UNDONE: {await undo(client, args.run_id)} files restored for run {args.run_id}.")
            return 0
        async with client.tx() as tx:
            await qv2_guard.assert_read_only(tx)
            rows = await candidates(tx)
        text = report(rows)
        name = naming.export_filename(entity="Questionnaire", what="Needs-Decision-Pending-Dry-Run-Report", ext="csv")
        if not args.apply:
            qv2_guard.write_report(args.out, name, text)
            print(f"DRY RUN: {len(rows)} files of takes waiting for a decision would be set to PENDING. Nothing was written.")
            return 0
        if not args.run_id:
            raise qv2_guard.Refused("--apply needs --run-id")
        approver, day = qv2_guard.check_approval(args.approved_report, text)
        owners = await client.query_raw('SELECT "id" FROM "User" WHERE lower("email") = $1', approver.lower())
        if not owners:
            raise qv2_guard.Refused(f"no user with the approver's email {approver!r}")
        done = await apply(client, rows, args.run_id, str(owners[0]["id"]))
        print(f"APPLIED run {args.run_id} (approved by {approver} on {day}): {done} files set to PENDING.")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    qv2_guard.add_common_arguments(parser)
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--undo", action="store_true")
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
