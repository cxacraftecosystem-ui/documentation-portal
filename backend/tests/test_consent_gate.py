"""The consent gate (DESIGN 3.11; test 35c): the rules in order, REFUSED wins over GRANTED, consent
is assumed by default (19 Oct 2026: no answer is sent, a refusal stops it, a later grant allows
again), and recording, upload, filing and approval never consult it."""

from __future__ import annotations

import ast
from pathlib import Path

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

from app.services.consent_gate import ALLOW, REFUSED, decide

BACKEND = Path(__file__).resolve().parents[1]


def test_the_rules_in_order() -> None:
    assert decide("GRANTED", ["REFUSED"]) == ALLOW, "rule 1: the owner's attestation of the recording wins"
    assert decide(None, ["GRANTED", "REFUSED", "GRANTED"]) == REFUSED, "rule 3: any REFUSED subject"
    assert decide(None, ["GRANTED", "GRANTED"]) == ALLOW, "rule 4: every subject GRANTED"
    assert decide(None, ["GRANTED", None]) == ALLOW, "rule 4: no answer counts as agreeing"
    assert decide(None, [None, "NOT_RECORDED"]) == ALLOW, "rule 4: nobody has answered at all"
    assert decide(None, []) == ALLOW, "rule 4: no artisan subject at all"
    assert decide(None, [None, "REFUSED"]) == REFUSED, "rule 3: one refusal among the unanswered"
    # The one consent store (19 Oct 2026): a refusal recorded for the recording itself wins, and a
    # subject's refusal recorded AFTER the recording's grant (a withdrawal) wins over that grant.
    assert decide("REFUSED", ["GRANTED"]) == REFUSED
    grant = {"state": "GRANTED", "recordedAt": "2026-10-18T00:00:00+05:30"}
    assert decide(grant, [{"state": "REFUSED", "recordedAt": "2026-10-17T10:00:00+05:30"}]) == ALLOW
    assert decide(grant, [{"state": "REFUSED", "recordedAt": "2026-10-20T10:00:00+05:30"}]) == REFUSED


def test_recording_upload_filing_and_approval_never_consult_the_gate() -> None:
    """The gate is imported only where something is SENT outside: transcription, the media queue,
    refinement, drafting and search embeddings; never by the session, coverage, review,
    recordings-complete paths."""
    allowed = {
        "app/services/transcription_enqueue.py", "app/services/media_queue.py", "app/api/routes/media.py",
        "app/questionnaire/answers.py", "app/api/routes/questionnaire_v2.py",
        "app/questionnaire/recordings.py", "app/questionnaire/sessions.py",
        "app/questionnaire/corpus.py", "app/services/consent_gate.py", "app/questionnaire/ports.py", "app/services/questionnaire_ports.py",
        "app/services/recording_consent.py", "app/api/routes/answer_drafts.py",
        "app/services/search_indexer.py",
        # 0.0.6: View data / Download data leave out the words of a recording whose consent was
        # refused (owner request). It reads the verdict; it never sends anything anywhere.
        "app/services/transcript_export.py",
    }
    for path in BACKEND.joinpath("app").rglob("*.py"):
        rel = path.relative_to(BACKEND).as_posix()
        if "consent_gate" in path.read_text(encoding="utf-8") and rel not in allowed:
            raise AssertionError(f"{rel} consults the consent gate")
    for rel in ("app/questionnaire/review.py", "app/questionnaire/coverage.py", "app/api/routes/review.py"):
        assert "consent_gate" not in BACKEND.joinpath(rel).read_text(encoding="utf-8")
    # In recordings.py and sessions.py the gate only DESCRIBES a take (verdict_detail / current
    # consent for display); complete() never branches on it.
    tree = ast.parse(BACKEND.joinpath("app/questionnaire/recordings.py").read_text(encoding="utf-8"))
    complete = next(n for n in tree.body if isinstance(n, ast.AsyncFunctionDef) and n.name == "complete")
    assert "consent_gate" not in ast.unparse(complete)


@needs_db
def test_the_gate_against_the_ledger(monkeypatch) -> None:
    from app.services import consent_gate
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        a, b, c = ids.artisans
        s, _ = await sessions.create_session(session_create(ids, [a, b, c]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0))
        media = take["mediaFileId"]
        assert take["consent"] == {"verdict": ALLOW, "source": None}, "no answer: consent assumed by default"
        status = await client.query_raw('SELECT "transcriptStatus" FROM "MediaFile" WHERE "id" = $1', media)
        assert status[0]["transcriptStatus"] == "QUEUED", "queued (the pause still holds the send)"
        for artisan in (a, b):
            await consent_gate.record_artisan_consent(artisan, state="GRANTED", basis="VERBAL_RECORDED", actor_id=ids.lead.id, client=client)
        assert await consent_gate.verdict(media, client=client) == ALLOW
        await consent_gate.record_artisan_consent(c, state="REFUSED", basis="VERBAL_RECORDED", actor_id=ids.lead.id, client=client)
        assert await consent_gate.verdict(media, client=client) == REFUSED, "one artisan switching off stops it"
        await consent_gate.record_artisan_consent(c, state="GRANTED", basis="VERBAL_RECORDED", actor_id=ids.lead.id, client=client)
        assert await consent_gate.verdict(media, client=client) == ALLOW, "a refusal can be changed; the latest row counts"
        await client.disconnect()

    run(go())
