"""The legacy backfill (DESIGN 7.3; tests 28 and 29) on a seeded fixture of 30 interviews modelled on
the assessment: burst copies in one interview, copies across interviews, artisan-less interviews,
empty approved ones and a batch uploaded outside its workshop's window.

It needs a database of its own, because the script reads every legacy row: point
``QV2_BACKFILL_DATABASE_URL`` at a freshly migrated throwaway database (never production).
"""

from __future__ import annotations

import argparse
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.parse import urlsplit

import pytest
from qv2_fixtures import run

URL = os.environ.get("QV2_BACKFILL_DATABASE_URL", "").strip()
pytestmark = pytest.mark.skipif(not URL, reason="QV2_BACKFILL_DATABASE_URL is not set (its own migrated throwaway database)")


async def _connect():
    from prisma import Prisma

    client = Prisma(datasource={"url": URL})
    await client.connect()
    return client


async def seed_legacy(client) -> dict:
    await client.execute_raw('DELETE FROM "QuestionnaireRecording"')
    for table in ("MediaProcessingJob", "MediaFile", "QuestionnaireInterviewResearcher", "QuestionnaireInterviewArtisan", "QuestionnaireInterview",
                  "QuestionnaireQuestion", "QuestionnaireSection", "Questionnaire", "WorkshopCraft", "Workshop", "Artisan", "Craft", "Location", "User"):
        await client.execute_raw(f'DELETE FROM "{table}"')
    await client.execute_raw("INSERT INTO \"User\" (\"id\", \"email\", \"name\", \"role\", \"updatedAt\") VALUES ('u1', 'u1@x.test', 'Ankur', 'RESEARCHER', now()), "
                             "('u2', 'u2@x.test', 'Nandini', 'RESEARCHER', now()), ('owner', 'owner@x.test', 'Owner', 'MASTER_ADMIN', now())")
    await client.execute_raw("INSERT INTO \"Questionnaire\" (\"id\", \"title\", \"sortOrder\", \"updatedAt\") VALUES ('q', '2nd Craft Toolkit', 1, now())")
    for order, code in enumerate("ABF", start=1):
        await client.execute_raw("INSERT INTO \"QuestionnaireSection\" (\"id\", \"questionnaireId\", \"code\", \"title\", \"sortOrder\", \"updatedAt\") "
                                 "VALUES ($1, 'q', $2, $3, $4, now())", f"sec{code}", code, f"Section {code}", order)
        for n in range(1, 8):
            await client.execute_raw(
                "INSERT INTO \"QuestionnaireQuestion\" (\"id\", \"questionnaireId\", \"sectionCode\", \"sectionTitle\", \"prompt\", \"sortOrder\", \"sectionId\", \"updatedAt\") "
                "VALUES ($1, 'q', $2, $3, $4, $5, $6, '2026-06-01')", f"q{code}{n}", code, f"Section {code}", f"Prompt {code}{n} distinct words {n}{code}", n, f"sec{code}",
            )
    await client.execute_raw("INSERT INTO \"Workshop\" (\"id\", \"title\", \"date\", \"startDate\", \"endDate\", \"place\", \"createdById\", \"updatedAt\") "
                             "VALUES ('w2', '2nd workshop', '2026-06-19', '2026-06-19', '2026-06-24', 'Kolkata', 'u1', now())")
    for i in range(31):
        await client.execute_raw("INSERT INTO \"Artisan\" (\"id\", \"name\", \"place\", \"createdById\", \"updatedAt\") VALUES ($1, $2, 'Kolkata', 'u1', now())",
                                 f"a{i}", f"Artisan {i}")
    media_n = 0

    async def interview(iid, artisans, *, status="PENDING", title="section F", language="Bangla"):
        await client.execute_raw(
            "INSERT INTO \"QuestionnaireInterview\" (\"id\", \"title\", \"questionnaireId\", \"workshopId\", \"createdById\", \"status\", \"language\", \"createdAt\", \"updatedAt\") "
            "VALUES ($1, $2, 'q', 'w2', 'u1', $3::\"RecordStatus\", $4, '2026-06-20', now())", iid, title, status, language,
        )
        for k, a in enumerate(artisans):
            await client.execute_raw("INSERT INTO \"QuestionnaireInterviewArtisan\" (\"interviewId\", \"artisanId\", \"sortOrder\") VALUES ($1, $2, $3)", iid, a, k)

    async def clip(iid, caption, sha, when, uploader="u1", transcript=None):
        nonlocal media_n
        media_n += 1
        mid = f"m{media_n:03d}"
        await client.execute_raw(
            "INSERT INTO \"MediaFile\" (\"id\", \"originalFilename\", \"mediaType\", \"mimeType\", \"sizeBytes\", \"bucket\", \"objectKey\", \"caption\", "
            "\"linkedRecordType\", \"linkedRecordId\", \"questionnaireInterviewId\", \"uploadedById\", \"contentSha256\", \"shaVerifiedAt\", \"recordedAt\", "
            "\"createdAt\", \"transcriptStatus\", \"updatedAt\") VALUES ($1, $2, 'AUDIO', 'audio/mp4', 10, 'b', $3, $4, 'questionnaire', $5, $5, $6, $7, now(), "
            "$8::timestamp, $8::timestamp, $9, now())",
            mid, f"{mid}.m4a", f"media/{uploader}/{mid}/{mid}.m4a", caption, iid, uploader, sha, when, transcript,
        )
        return mid

    base = datetime(2026, 6, 20, 5, 0)
    # 24 ordinary interviews, one artisan each, two distinct clips each
    for i in range(24):
        iid = f"i{i:02d}"
        await interview(iid, [f"a{i}"])
        await clip(iid, "Section audio: A", f"{i:02d}a" * 16, (base + timedelta(minutes=i)).isoformat())
        await clip(iid, "Section audio: B", f"{i:02d}b" * 16, (base + timedelta(minutes=i, hours=1)).isoformat())
    # a burst: 5 byte-identical copies in one interview
    await interview("i24", ["a24"])
    for k in range(5):
        await clip("i24", "Section audio: F", "ff" * 32, (base + timedelta(seconds=k)).isoformat(), transcript="COMPLETED" if k == 3 else None)
    # one file uploaded to three interviews (cross-interview copies)
    for iid, a in (("i25", "a25"), ("i26", "a26"), ("i27", "a27")):
        await interview(iid, [a])
        await clip(iid, "Section audio: A", "cc" * 32, base.isoformat())
    # artisan-less, empty approved, out-of-window (the 30 Jun batch)
    await interview("i28", [])
    await clip("i28", "Section audio: A", "28" * 32, base.isoformat())
    await interview("i29", ["a29"], status="APPROVED")
    await interview("i30", ["a30"], language="Sora")
    await clip("i30", "Section audio: A", "30" * 32, datetime(2026, 6, 30, 9, 0).isoformat())
    return {"clips": media_n}


def _args(tmp: Path, **kw) -> argparse.Namespace:
    base = {"database_url": URL, "allow_host": [urlsplit(URL).hostname], "apply": False, "approved_report": None,
            "out": str(tmp), "run_id": None, "undo": False, "sha_only": False}
    return argparse.Namespace(**{**base, **kw})


def test_dry_run_apply_twice_and_undo(tmp_path: Path) -> None:
    from scripts import questionnaire_v2_backfill as backfill

    async def seed_and_plan():
        client = await _connect()
        info = await seed_legacy(client)
        plan = await backfill.dry_plan(client)
        await client.disconnect()
        return info, plan

    info, plan = run(seed_and_plan())
    totals = plan.totals()
    assert totals["clips"] == info["clips"] == 58
    assert totals["duplicatesSameInterview"] == 4 and totals["duplicatesCrossInterview"] == 2 and totals["groupSessions"] == 1
    assert totals["recordings"] == 58 - 6
    assert totals["artisanless"] == 1 and totals["emptyApproved"] == 1 and totals["datesOutOfWindow"] == 1
    assert totals["unmappedLanguages"] == 1
    kept = next(c for c in plan.clips if c.interview_id == "i24" and c.kind == "RECORDING")
    assert kept.media_id == "m052", "the copy with the COMPLETED transcript is kept"
    group = next(s for s in plan.sessions if s.group)
    assert sorted(group.participants) == ["a25", "a26", "a27"]
    for c in plan.clips:
        if c.kind == "RECORDING":
            session = next(s for s in plan.sessions if s.session_id == c.session_id)
            assert c.session_id == group.session_id or set(session.participants) >= set(), "subjects come only from participants"

    assert run(backfill.run(_args(tmp_path))) == 0
    md = next(tmp_path.glob("FieldRepository_Questionnaire_*_Backfill-Dry-Run-Report.md"))

    async def counts():
        client = await _connect()
        r = await client.query_raw('SELECT (SELECT count(*) FROM "QuestionnaireRecording") AS rec, '
                                   "(SELECT count(*) FROM \"QuestionnaireInterview\" WHERE \"origin\" = 'LEGACY_RECONSTRUCTED') AS legacy, "
                                   '(SELECT count(*) FROM "MediaFile" WHERE "removedAt" IS NOT NULL) AS removed')
        bad = await client.query_raw(
            'SELECT count(*) AS n FROM "QuestionnaireRecordingSubject" s JOIN "QuestionnaireRecording" r ON r."id" = s."recordingId" '
            'JOIN "MediaFile" m ON m."id" = r."mediaFileId" WHERE NOT EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" p '
            'WHERE p."interviewId" = m."questionnaireInterviewId" AND p."artisanId" = s."artisanId")'
        )
        await client.disconnect()
        return {k: int(v) for k, v in r[0].items()}, int(bad[0]["n"])

    assert run(counts())[0] == {"rec": 0, "legacy": 0, "removed": 0}, "the dry run wrote nothing"
    with pytest.raises(SystemExit):
        run(backfill.run(_args(tmp_path, apply=True, run_id="r1", approved_report=str(md))))
    md.write_text(md.read_text(encoding="utf-8") + "Approved-by: owner@x.test 2026-10-25\n", encoding="utf-8")
    assert run(backfill.run(_args(tmp_path, apply=True, run_id="r1", approved_report=str(md)))) == 0
    after, bad = run(counts())
    assert after == {"rec": 52, "legacy": 32, "removed": 6} and bad == 0, "no subject who is not a participant"
    assert run(backfill.run(_args(tmp_path))) == 0
    again = next(tmp_path.glob("FieldRepository_Questionnaire_*_Backfill-Dry-Run-Report.md"))
    second = run(_plan_only())
    assert second.totals()["clips"] == 0 and second.totals()["legacySessions"] == 0, "apply twice -> 0 changes"
    assert again.exists()
    assert run(backfill.run(_args(tmp_path, undo=True, run_id="r1"))) == 0
    assert run(counts())[0] == {"rec": 0, "legacy": 0, "removed": 0}, "undo restores the counts"


async def _plan_only():
    from scripts import questionnaire_v2_backfill as backfill

    client = await _connect()
    plan = await backfill.dry_plan(client)
    await client.disconnect()
    return plan


def test_the_purge_takes_only_verified_copies_older_than_30_days(tmp_path: Path) -> None:
    from scripts import questionnaire_purge as purge

    async def go():
        client = await _connect()
        await seed_legacy(client)
        old = (datetime.now(UTC) - timedelta(days=31)).replace(tzinfo=None).isoformat()
        recent = (datetime.now(UTC) - timedelta(days=3)).replace(tzinfo=None).isoformat()
        await client.execute_raw('UPDATE "MediaFile" SET "removedAt" = $1::timestamp, "extraMetadata" = \'{"removedAs": "VERIFIED_DUPLICATE"}\' WHERE "id" IN (\'m049\', \'m050\')', old)
        await client.execute_raw('UPDATE "MediaFile" SET "removedAt" = $1::timestamp, "extraMetadata" = \'{"removedAs": "VERIFIED_DUPLICATE"}\' WHERE "id" = \'m051\'', recent)
        await client.execute_raw('UPDATE "MediaFile" SET "removedAt" = $1::timestamp WHERE "id" = \'m001\'', old)  # a distinct take, removed
        dupes = await purge.duplicate_candidates(client, now=datetime.now(UTC))
        await client.disconnect()
        return dupes

    dupes = run(go())
    assert sorted(d["id"] for d in dupes) == ["m049", "m050"], "never a distinct take, never before 30 days"
    orphans_csv = tmp_path / "orphans.csv"
    orphans_csv.write_text("objectKey,sizeBytes,reportedAt\nmedia/x/old.m4a,5,2026-08-01T00:00:00Z\nmedia/x/new.m4a,5,2099-01-01T00:00:00Z\n", encoding="utf-8")
    found = purge.orphan_candidates(str(orphans_csv), now=datetime(2026, 10, 25, tzinfo=UTC), known=set())
    assert [o["objectKey"] for o in found] == ["media/x/old.m4a"]
    assert run(purge.run(argparse.Namespace(database_url=URL, allow_host=[urlsplit(URL).hostname], apply=False, approved_report=None,
                                            out=str(tmp_path), orphans_csv=None))) == 0


# ── 21 Oct audit: C6 (catch-up), M1 (undecided takes stay PENDING), M25 (undo restores) ──────
async def _clip(client, mid: str, iid: str, caption: str, sha: str, *, status: str = "PENDING") -> None:
    await client.execute_raw(
        "INSERT INTO \"MediaFile\" (\"id\", \"originalFilename\", \"mediaType\", \"mimeType\", \"sizeBytes\", \"bucket\", \"objectKey\", \"caption\", "
        "\"linkedRecordType\", \"linkedRecordId\", \"questionnaireInterviewId\", \"uploadedById\", \"contentSha256\", \"shaVerifiedAt\", \"recordedAt\", "
        "\"createdAt\", \"status\", \"updatedAt\") VALUES ($1, $2, 'AUDIO', 'audio/mp4', 10, 'b', $3, $4, 'questionnaire', $5, $5, 'u1', $6, now(), "
        "'2026-06-21T05:00:00'::timestamp, '2026-06-21T05:00:00'::timestamp, $7::\"RecordStatus\", now())",
        mid, f"{mid}.m4a", f"media/u1/{mid}/{mid}.m4a", caption, iid, sha, status,
    )


def test_undecided_takes_stay_pending_catch_up_files_late_clips_and_undo_restores_only_what_it_changed(tmp_path: Path) -> None:
    from scripts import questionnaire_needs_decision_pending as fixup
    from scripts import questionnaire_v2_backfill as backfill

    async def setup():
        client = await _connect()
        await seed_legacy(client)
        # Two different clips of one section in one sitting, already APPROVED: both wait for a decision.
        await client.execute_raw(
            "INSERT INTO \"QuestionnaireInterview\" (\"id\", \"title\", \"questionnaireId\", \"workshopId\", \"createdById\", \"status\", \"createdAt\", \"updatedAt\") "
            "VALUES ('i40', 'two takes', 'q', 'w2', 'u1', 'APPROVED', '2026-06-21', now())"
        )
        await client.execute_raw("INSERT INTO \"QuestionnaireInterviewArtisan\" (\"interviewId\", \"artisanId\", \"sortOrder\") VALUES ('i40', 'a30', 0)")
        await _clip(client, "m400", "i40", "Section audio: A", "40" * 32, status="APPROVED")
        await _clip(client, "m401", "i40", "Section audio: A", "41" * 32, status="APPROVED")
        plan = await backfill.dry_plan(client)
        await backfill.apply_plan(client, plan, "r9")
        status = await client.query_raw('SELECT "id", "status"::text AS s FROM "MediaFile" WHERE "id" IN (\'m400\', \'m401\') ORDER BY "id"')
        # Clips that arrive into reconstructed sessions after the backfill (audit C6).
        await _clip(client, "m900", "i00", "Section audio: F", "90" * 32)
        await _clip(client, "m901", "i01", "an unplaceable clip", "91" * 32, status="APPROVED")
        await client.disconnect()
        return status

    status = run(setup())
    assert [r["s"] for r in status] == ["PENDING", "PENDING"], "a take waiting for a decision is never approved with its session (M1)"

    assert run(backfill.run(_args(tmp_path, catch_up=True))) == 0
    report = next(tmp_path.glob("FieldRepository_Questionnaire_*_Catch-Up-Dry-Run-Report.md"))
    assert "| clips | 2 |" in report.read_text(encoding="utf-8")

    async def missing() -> int:
        client = await _connect()
        rows = await client.query_raw(
            "SELECT count(*) AS n FROM \"MediaFile\" m WHERE m.\"mediaType\" IN ('AUDIO', 'VIDEO') AND m.\"removedAt\" IS NULL "
            'AND m."questionnaireInterviewId" IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "QuestionnaireRecording" r WHERE r."mediaFileId" = m."id")'
        )
        await client.disconnect()
        return int(rows[0]["n"])

    assert run(missing()) == 2, "the catch-up dry run wrote nothing"
    with pytest.raises(SystemExit):
        run(backfill.run(_args(tmp_path, catch_up=True, apply=True, run_id="c1", approved_report=str(report))))
    report.write_text(report.read_text(encoding="utf-8") + "Approved-by: owner@x.test 2026-10-25\n", encoding="utf-8")
    assert run(backfill.run(_args(tmp_path, catch_up=True, apply=True, run_id="c1", approved_report=str(report)))) == 0
    assert run(missing()) == 0, "the check before the flip: no live clip without a recording"

    async def caught() -> list:
        client = await _connect()
        rows = await client.query_raw(
            'SELECT m."id", r."filing"::text AS filing, m."status"::text AS s FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
            "WHERE m.\"id\" IN ('m900', 'm901') ORDER BY m.\"id\""
        )
        await client.disconnect()
        return [(r["id"], r["filing"], r["s"]) for r in rows]

    assert run(caught()) == [("m900", "FILED", "PENDING"), ("m901", "NEEDS_DECISION", "PENDING")]
    assert run(backfill.run(_args(tmp_path, undo=True, run_id="c1"))) == 0
    assert run(missing()) == 2, "the catch-up undoes on its own run id"

    # The fix-up for the live rows (M1): dry run, approval, apply, undo.
    async def approve_one() -> None:
        client = await _connect()
        await client.execute_raw('UPDATE "MediaFile" SET "status" = \'APPROVED\' WHERE "id" = \'m400\'')
        await client.disconnect()

    run(approve_one())
    fix_args = dict(database_url=URL, allow_host=[urlsplit(URL).hostname], apply=False, approved_report=None, out=str(tmp_path),
                    run_id=None, undo=False)
    assert run(fixup.run(argparse.Namespace(**fix_args))) == 0
    fix_report = next(tmp_path.glob("FieldRepository_Questionnaire_*_Needs-Decision-Pending-Dry-Run-Report.csv"))
    assert "m400" in fix_report.read_text(encoding="utf-8")
    fix_report.write_text(fix_report.read_text(encoding="utf-8") + "Approved-by: owner@x.test 2026-10-25\n", encoding="utf-8")
    assert run(fixup.run(argparse.Namespace(**{**fix_args, "apply": True, "run_id": "f1", "approved_report": str(fix_report)}))) == 0

    async def m400() -> str:
        client = await _connect()
        rows = await client.query_raw('SELECT "status"::text AS s FROM "MediaFile" WHERE "id" = \'m400\'')
        await client.disconnect()
        return rows[0]["s"]

    assert run(m400()) == "PENDING"
    assert run(fixup.run(argparse.Namespace(**{**fix_args, "undo": True, "run_id": "f1"}))) == 0
    assert run(m400()) == "APPROVED"

    # Undo of the backfill (M25): refused while a reconstructed session holds native content...
    async def native(add: bool) -> None:
        client = await _connect()
        if add:
            await _clip(client, "m950", "i02", "native", "95" * 32)
            await client.execute_raw(
                'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", "rosterCount", '
                "\"recordedById\", \"provenance\", \"updatedAt\") VALUES ('r950', 'm950', 'native-950', now(), 'DEVICE', 1, 'u2', 'NATIVE', now())"
            )
        else:
            await client.execute_raw("DELETE FROM \"QuestionnaireRecording\" WHERE \"id\" = 'r950'")
            await client.execute_raw("DELETE FROM \"MediaFile\" WHERE \"id\" = 'm950'")
            # ...and somebody joined i02 after the backfill.
            await client.execute_raw(
                "INSERT INTO \"QuestionnaireInterviewResearcher\" (\"interviewId\", \"userId\", \"role\") VALUES ('i02', 'u2', 'CONTRIBUTOR') "
                "ON CONFLICT DO NOTHING"
            )
        await client.disconnect()

    run(native(True))
    with pytest.raises(SystemExit):
        run(backfill.run(_args(tmp_path, undo=True, run_id="r9")))
    run(native(False))
    assert run(backfill.run(_args(tmp_path, undo=True, run_id="r9"))) == 0

    async def restored() -> tuple:
        client = await _connect()
        row = await client.query_raw('SELECT "origin"::text AS o, "legacyTitle", "state"::text AS st, "title" FROM "QuestionnaireInterview" WHERE "id" = \'i02\'')
        people = await client.query_raw('SELECT "userId" FROM "QuestionnaireInterviewResearcher" WHERE "interviewId" = \'i02\'')
        status = await client.query_raw('SELECT "status"::text AS s FROM "MediaFile" WHERE "id" = \'m401\'')
        await client.disconnect()
        return row[0], sorted(p["userId"] for p in people), status[0]["s"]

    row, people, m401 = run(restored())
    assert row["o"] == "NATIVE" and row["legacyTitle"] is None and row["st"] == "OPEN" and row["title"] == "section F"
    assert people == ["u2"], "only the researchers the run added are taken away; one who joined later stays"
    assert m401 == "APPROVED", "an undecided take's status is put back by the undo"
