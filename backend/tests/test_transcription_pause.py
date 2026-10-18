"""Transcription is idle (owner correction (d); DESIGN 3.14, test 35e).

With ``transcriptionPaused = true`` (the shipped state), /media/complete, the sha worker,
transcribe-now, /recordings/{id}/transcribe and the queue make ZERO provider calls: every provider
entry point is replaced by one that fails the test, and every job stays QUEUED.
"""

from __future__ import annotations

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

pytestmark = needs_db


def test_nothing_reaches_a_provider_while_paused(monkeypatch) -> None:
    from app import transcription
    from app.services import consent_gate, media_queue
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client, paused=True)
        assert await transcription.is_paused(client) is True
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await consent_gate.record_artisan_consent(ids.artisans[0], state="GRANTED", basis="VERBAL_RECORDED", actor_id=ids.lead.id, client=client)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        assert take["transcript"]["status"] == "QUEUED" and take["transcript"]["paused"] is True
        retry = await recordings.transcribe(take["id"], ids.lead, client=client)
        assert retry == {"status": "QUEUED", "paused": True}
        media = await client.mediafile.find_unique(where={"id": take["mediaFileId"]})
        now_result = await media_queue.transcribe_media_now(media)
        assert now_result["status"] == "QUEUED" and now_result["paused"] is True
        # Other suites share this database and leave their own queued jobs; put this clip's first.
        await client.execute_raw('UPDATE "MediaProcessingJob" SET "priority" = 0 WHERE "mediaFileId" = $1', take["mediaFileId"])
        result = await media_queue.process_next_media_jobs(limit=5, worker_id="test")
        jobs = await client.query_raw(
            'SELECT "jobType"::text AS j, "status"::text AS s FROM "MediaProcessingJob" WHERE "mediaFileId" = $1', take["mediaFileId"]
        )
        assert {(j["j"], j["s"]) for j in jobs if j["j"] == "TRANSCRIPTION"} == {("TRANSCRIPTION", "QUEUED")}
        assert sum(1 for j in jobs if j["j"] == "TRANSCRIPTION") == 1, "a retry never opens a second job"
        assert ("SHA_VERIFY", "COMPLETED") in {(j["j"], j["s"]) for j in jobs}, "the sha worker ran, and called nobody"
        assert result["failed"] == 0
        await client.disconnect()

    run(go())


def test_a_missing_settings_row_reads_as_paused(monkeypatch) -> None:
    from app import transcription

    class Broken:
        async def query_raw(self, *args, **kwargs):
            raise RuntimeError("no database")

    class Empty:
        async def query_raw(self, *args, **kwargs):
            return []

    assert run(transcription.is_paused(Broken())) is True
    assert run(transcription.is_paused(Empty())) is True


def test_the_config_route_reports_the_pause(monkeypatch) -> None:
    from fastapi.testclient import TestClient

    from app.core import deps
    from app.main import app

    async def paused(client=None):
        return True

    from app import transcription

    monkeypatch.setattr(transcription, "is_paused", paused)
    app.dependency_overrides[deps.get_current_user] = lambda: type("U", (), {"id": "u", "role": "RESEARCHER"})()
    try:
        body = TestClient(app).get("/api/questionnaire/config").json()
    finally:
        app.dependency_overrides.pop(deps.get_current_user, None)
    assert body["transcriptionPaused"] is True and body["noteMaxChars"] == 80 and body["joinCodeLength"] == 6
