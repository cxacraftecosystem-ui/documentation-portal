"""Move on the recordings still marked ``AWAITING_CONSENT`` (consent assumed by default, 19 Oct 2026).

WHY. Until 19 Oct 2026 a recording whose artisans had not answered waited (``AWAITING_CONSENT``) and
moved on only when someone recorded a grant. The owner then decided: "the consent should be assumed
by default, people can revoke it if required". The gate (``services/consent_gate.py``) now sends
anything nobody refused, but the recordings already parked stay parked until something asks again.

WHAT IT DOES, PER RECORDING STILL ``AWAITING_CONSENT`` (and not removed): asks the one door,
``services/transcription_enqueue.enqueue``, again. That re-reads the gate, so a recording someone has
refused since becomes ``REFUSED_CONSENT`` instead, and it respects the pause: while transcription is
paused the job is written as QUEUED and nothing is sent until the owner lifts it.

DRY RUN BY DEFAULT, inside a READ ONLY transaction; it refuses a database host you did not name and
``--apply`` refuses to start without the dry run's report approved by the owner. Idempotent: a
recording that has moved on is no longer ``AWAITING_CONSENT``. NEVER run it as part of a test.

    cd backend
    python scripts/release_awaiting_consent.py --allow-host <db host>              # dry run + report
    python scripts/release_awaiting_consent.py --allow-host <db host> --apply \\
        --approved-report ../docs/ops/<report with an Approved-by line>
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import io
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import qv2_guard  # noqa: E402

from app.services import naming  # noqa: E402

REASON = "consent assumed by default"
ROWS_SQL = (
    'SELECT m."id" FROM "MediaFile" m '
    "WHERE m.\"transcriptStatus\" = 'AWAITING_CONSENT' AND m.\"removedAt\" IS NULL "
    'ORDER BY m."createdAt", m."id"'
)


def report(media_ids: list[str]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["what", "count"])
    writer.writerow(["recordings AWAITING_CONSENT to ask the gate again", len(media_ids)])
    writer.writerow(["provider calls made by this script", "none (the queue honours the pause)"])
    for media_id in media_ids:
        writer.writerow([f"media {media_id}", 1])
    return buffer.getvalue()


async def apply(client: Any, media_ids: list[str]) -> dict[str, int]:
    from app.services import transcription_enqueue

    outcomes: dict[str, int] = {}
    for media_id in media_ids:
        result = await transcription_enqueue.enqueue(media_id, REASON, client=client)
        outcomes[result["status"]] = outcomes.get(result["status"], 0) + 1
    return outcomes


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    try:
        async with client.tx() as tx:
            await qv2_guard.assert_read_only(tx)
            ids = [str(r["id"]) for r in await tx.query_raw(ROWS_SQL)]
        text = report(ids)
        name = naming.export_filename(entity="Questionnaire", what="Consent-Release-Dry-Run-Report", ext="csv")
        if not args.apply:
            qv2_guard.write_report(args.out, name, text)
            print(text)
            print("DRY RUN: nothing was written. The owner approves the report before --apply.")
            return 0
        approver, day = qv2_guard.check_approval(args.approved_report, text)
        outcomes = await apply(client, ids)
        print(f"APPLIED (approved by {approver} on {day}): {outcomes}")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    qv2_guard.add_common_arguments(parser)
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
