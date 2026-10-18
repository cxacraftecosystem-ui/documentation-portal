"""The owner's consent attestation (correction (c); DESIGN 7.6, test 35d).

The dry run writes nothing (inside a verified READ ONLY transaction); apply writes one RECORDING row
per audio or video file made on or before 18 Oct 2026 with source "owner attestation"; a second
apply writes 0; no job, status or transcript changes; a later file gets no row; apply refuses
without the owner's approved report and refuses an unlisted host.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import pytest
from qv2_fixtures import URL, connect, needs_db, run, seed

pytestmark = needs_db


def _args(tmp: Path, **kw) -> argparse.Namespace:
    from urllib.parse import urlsplit

    base = {"database_url": URL, "allow_host": [urlsplit(URL).hostname], "apply": False, "approved_report": None,
            "out": str(tmp), "owner_email": None}
    return argparse.Namespace(**{**base, **kw})


def test_dry_run_apply_and_idempotence(tmp_path: Path, monkeypatch) -> None:
    from scripts import backfill_consent_owner_attestation as script
    from scripts import qv2_guard

    async def setup():
        client = await connect()
        ids = await seed(client)
        rows = [("old", "2026-10-18T10:00:00", "COMPLETED", "said things"), ("pending", "2026-09-20T10:00:00", "QUEUED", None),
                ("new", "2026-10-19T10:00:00", None, None)]
        for name, created, status, text in rows:
            await client.execute_raw(
                'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
                '"uploadedById", "transcriptStatus", "transcriptText", "createdAt", "updatedAt") VALUES ($1, $2, \'AUDIO\', \'audio/mp4\', 1, '
                "'b', $3, $4, $5, $6, $7::timestamp, now())",
                f"{name}{ids.tag}", f"{name}.m4a", f"media/x/{name}{ids.tag}.m4a", ids.lead.id, status, text, created,
            )
        jobs_before = await client.query_raw('SELECT count(*) AS n FROM "MediaProcessingJob"')
        await client.disconnect()
        return ids, int(jobs_before[0]["n"])

    ids, jobs_before = run(setup())
    with pytest.raises(SystemExit):
        run(script.run(_args(tmp_path, allow_host=["not-this-host"])))
    assert run(script.run(_args(tmp_path))) == 0
    report = next(tmp_path.glob("FieldRepository_Artisan_*_Consent-Backfill-Dry-Run-Report.csv"))

    async def count(kind: str) -> int:
        client = await connect()
        rows = await client.query_raw(
            "SELECT count(*) AS n FROM \"Consent\" WHERE \"subjectType\" = 'RECORDING' AND \"subjectId\" = ANY($1::text[]) AND \"source\" = 'owner attestation'",
            [f"{n}{ids.tag}" for n in (("old", "pending") if kind == "in" else ("new",))],
        )
        await client.disconnect()
        return int(rows[0]["n"])

    assert run(count("in")) == 0, "the dry run wrote nothing"
    with pytest.raises(SystemExit):
        run(script.run(_args(tmp_path, apply=True, approved_report=str(report))))  # no Approved-by line yet
    report.write_text(report.read_text(encoding="utf-8") + f"Approved-by: {ids.admin.email} 2026-10-18\n", encoding="utf-8")
    assert qv2_guard.APPROVAL.search(report.read_text(encoding="utf-8"))
    assert run(script.run(_args(tmp_path, apply=True, approved_report=str(report)))) == 0
    assert run(count("in")) == 2 and run(count("new")) == 0

    async def after() -> tuple[int, list]:
        client = await connect()
        jobs = await client.query_raw('SELECT count(*) AS n FROM "MediaProcessingJob"')
        media = await client.query_raw(
            'SELECT "id", "transcriptStatus", "consentAtSend"::text AS c FROM "MediaFile" WHERE "id" = ANY($1::text[]) ORDER BY "id"',
            [f"old{ids.tag}", f"pending{ids.tag}"],
        )
        await client.disconnect()
        return int(jobs[0]["n"]), media

    jobs_after, media = run(after())
    assert jobs_after == jobs_before, "no job is written"
    by = {m["id"]: m for m in media}
    assert by[f"old{ids.tag}"]["c"] == "GRANTED" and by[f"pending{ids.tag}"]["c"] is None
    assert by[f"pending{ids.tag}"]["transcriptStatus"] == "QUEUED", "statuses are untouched"
    # A second apply: the data moved (rows now exist), so the old approval no longer matches.
    with pytest.raises(SystemExit):
        run(script.run(_args(tmp_path, apply=True, approved_report=str(report))))
    assert run(script.run(_args(tmp_path))) == 0
    fresh = next(tmp_path.glob("FieldRepository_Artisan_*_Consent-Backfill-Dry-Run-Report.csv"))
    fresh.write_text(fresh.read_text(encoding="utf-8") + f"Approved-by: {ids.admin.email} 2026-10-18\n", encoding="utf-8")
    assert run(script.run(_args(tmp_path, apply=True, approved_report=str(fresh)))) == 0
    assert run(count("in")) == 2, "a second apply writes 0"
