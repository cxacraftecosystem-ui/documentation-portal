"""Server-known uploads and the atomic /complete (DESIGN 3.3, 3.9, 5.5; tests 4-8, 22, 23, 33, 35b).
Real Postgres; S3 and every provider are stand-ins."""

from __future__ import annotations

import asyncio
import re
from datetime import UTC, datetime, timedelta

import pytest
from fastapi import HTTPException
from qv2_fixtures import (
    complete_request,
    connect,
    key_for,
    needs_db,
    no_outside_calls,
    patch_db,
    record_take,
    run,
    seed,
    session_create,
    t,
)

pytestmark = needs_db
KEY_RE = re.compile(r"^media/[^/]+/[0-9a-f]{32}/[A-Za-z0-9._-]{1,150}$")


def _presign(ids, session_id: str, clip: str, **extra):
    from app.schemas.questionnaire_v2 import PresignRequest

    return PresignRequest.model_validate({
        "linkedRecordType": "questionnaire_session", "sessionId": session_id, "clientClipKey": clip, "sizeBytes": 3_400_000,
        "contentType": "audio/mp4", "extension": "m4a", "targets": [{"questionLineageId": ids.questions["D3"]}],
        "subjectArtisanIds": [ids.artisans[0]], "deviceCapturedAt": "2026-06-20T12:54:00Z", "clockOffsetMs": 0, **extra,
    })


def test_presign_needs_a_session_and_is_idempotent_on_the_clip_key(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        stubs = no_outside_calls(monkeypatch)
        ids = await seed(client)
        with pytest.raises(HTTPException) as unknown:
            await recordings.presign(_presign(ids, "nope", "k1"), ids.lead)
        assert unknown.value.detail["code"] == "SESSION_UNKNOWN" and unknown.value.status_code == 404
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        sha = "a" * 64
        first = await recordings.presign(_presign(ids, s["id"], f"k-{ids.tag}", contentSha256=sha), ids.lead)
        again = await recordings.presign(_presign(ids, s["id"], f"k-{ids.tag}", contentSha256=sha), ids.lead)
        assert first["objectKey"] == again["objectKey"] and first["url"] != again["url"]
        assert KEY_RE.match(first["objectKey"]), first["objectKey"]
        leaf = first["objectKey"].rsplit("/", 1)[-1]
        assert leaf.startswith("FieldRepository_Interview_2026-06-20_1824_Bagru-Jaipur_") and leaf.endswith("_Giriraj-Prasad-Chhipa_Recording_D-Q03.m4a")
        assert first["headers"]["x-amz-checksum-sha256"] and stubs["calls"]["presign"][0][1] == first["headers"]["x-amz-checksum-sha256"]
        big = _presign(ids, s["id"], f"big-{ids.tag}", sizeBytes=40_000_000, extension="wav", contentType="audio/wav")
        m1 = await recordings.multipart_create(big, ids.lead)
        m2 = await recordings.multipart_create(big, ids.lead)
        assert m1["uploadId"] == m2["uploadId"] and m2["partsDone"] == [{"partNumber": 1, "etag": '"e1"'}]
        state = await recordings.upload_state(f"big-{ids.tag}", ids.lead)
        assert state["multipartUploadId"] == m1["uploadId"] and state["closed"] is False
        await client.disconnect()

    run(go())


def test_complete_is_atomic_and_idempotent_on_the_clip_key(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        body = complete_request(key_for(ids.lead), s["id"], clip=f"atomic-{ids.tag}", targets=[{"sectionId": ids.sections["D"]}])

        async def boom() -> None:
            raise RuntimeError("fault injected after the recording insert")

        with pytest.raises(RuntimeError):
            await recordings.complete(body, ids.lead, client=client, fault_after_insert=boom)
        left = await client.query_raw('SELECT count(*) AS n FROM "MediaFile" WHERE "objectKey" = $1', body.objectKey)
        assert int(left[0]["n"]) == 0
        first, created = await recordings.complete(body, ids.lead, client=client)
        replay_body = complete_request(key_for(ids.lead), s["id"], clip=f"atomic-{ids.tag}", targets=[{"sectionId": ids.sections["D"]}])
        replay, created_again = await recordings.complete(replay_body, ids.lead, client=client)
        assert created and not created_again and replay["id"] == first["id"]
        rows = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireRecording" WHERE "clientClipKey" = $1', f"atomic-{ids.tag}")
        assert int(rows[0]["n"]) == 1
        jobs = await client.query_raw('SELECT "jobType"::text AS j, "status"::text AS s FROM "MediaProcessingJob" WHERE "mediaFileId" = $1', first["mediaFileId"])
        assert {(j["j"], j["s"]) for j in jobs} >= {("SHA_VERIFY", "QUEUED")}
        assert "url" not in first and "publicUrl" not in first
        await client.disconnect()

    run(go())


def test_complete_never_refuses_on_coverage_and_flags_only_the_truly_ambiguous(monkeypatch) -> None:
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        target = [{"sectionId": ids.sections["D"]}]
        first = await record_take(client, ids, s["id"], ids.lead, targets=target, at=t(0))
        acked = await record_take(client, ids, s["id"], ids.lead, targets=target, at=t(0.5), ackedRecordingIds=[first["id"]],
                                  subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED", "repeatReason": "REDO"}])
        assert acked["filing"] == "FILED" and acked["subjects"][0]["repeatReason"] == "REDO" and acked["subjects"][0]["flag"] is None
        concurrent = await record_take(client, ids, s["id"], ids.other, targets=target, at=t(1), ackedRecordingIds=[first["id"]])
        assert concurrent["filing"] == "NEEDS_DECISION" and concurrent["subjects"][0]["flag"] == "CONCURRENT"
        from app.services.questionnaire_v2 import inbox

        mine = await inbox.inbox(ids.lead, client=client)
        assert concurrent["id"] in [d["recordingId"] for d in mine["needsDecision"]], "the session's researchers are asked"
        everyone = await inbox.inbox(ids.admin, client=client)
        assert concurrent["id"] in [d["recordingId"] for d in everyone["needsDecision"]]
        assert everyone["counts"]["total"] >= 1
        later, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], day="2026-06-23"), ids.other)
        late = await record_take(client, ids, later["id"], ids.prof, targets=target, at=t(72))
        assert late["filing"] == "FILED" and late["subjects"][0]["flag"] == "LATE_ARRIVAL"
        assert late["subjects"][0]["repeatReason"] == "RE_INTERVIEW"
        await client.disconnect()

    run(go())


def test_twenty_concurrent_completes_for_overlapping_subjects_do_not_deadlock(monkeypatch) -> None:
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:3]), ids.lead)
        groups = [ids.artisans[:2], ids.artisans[1:], [ids.artisans[2], ids.artisans[0]]]

        async def one(i: int) -> dict:
            subs = [{"artisanId": a, "role": "ANSWERED"} for a in groups[i % 3]]
            return await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["F"]}], at=t(i / 60), subjects=subs)

        results = await asyncio.wait_for(asyncio.gather(*(one(i) for i in range(20))), timeout=240)
        assert len({r["id"] for r in results}) == 20
        concurrent = sum(1 for r in results if r["filing"] == "NEEDS_DECISION")
        assert concurrent == 19, "every take after the first is an unseen same-target take within 2 h"
        await client.disconnect()

    run(go())


def test_a_late_clip_into_a_closed_session_and_a_non_participant_subject_are_accepted(monkeypatch) -> None:
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await client.execute_raw('UPDATE "QuestionnaireInterview" SET "state" = \'CLOSED\', "closedAt" = $2::timestamp WHERE "id" = $1',
                                 s["id"], "2026-06-20T07:00:00")
        before = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0))
        after = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(3),
                                  subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED"}, {"artisanId": ids.artisans[2], "role": "PRESENT"}])
        assert before["filing"] == "FILED" and before["subjects"][0]["flag"] is None
        assert after["subjects"][0]["flag"] == "AFTER_CLOSE"
        refreshed = await sessions.session_payload(client, s["id"])
        assert ids.artisans[2] in [p["artisanId"] for p in refreshed["participants"]]
        await client.disconnect()

    run(go())


def test_clock_correction_stale_offset_and_future_clamp() -> None:
    from app.services.questionnaire_v2.recordings import correct_capture_time

    now = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)
    device = datetime(2026, 6, 29, 12, 0, tzinfo=UTC)
    fixed, source, clamped = correct_capture_time(device_captured_at=device, clock_offset_ms=2 * 86_400_000, source="DEVICE",
                                                  session_date=None, server_now=now)
    assert fixed == datetime(2026, 7, 1, 12, 0, tzinfo=UTC) and source == "DEVICE" and not clamped
    _, stale, _ = correct_capture_time(device_captured_at=device, clock_offset_ms=0, source="DEVICE", session_date=None,
                                       server_now=now, offset_age=timedelta(days=8))
    assert stale == "UPLOAD_TIME"
    future, fsource, fclamped = correct_capture_time(device_captured_at=now + timedelta(days=3), clock_offset_ms=0,
                                                     source="DEVICE", session_date=None, server_now=now)
    assert future == now and fclamped and fsource == "UPLOAD_TIME"
    picked, psource, _ = correct_capture_time(device_captured_at=None, clock_offset_ms=None, source="SESSION_DATE",
                                              session_date="2026-06-20", server_now=now)
    assert picked == datetime(2026, 6, 20, 6, 30, tzinfo=UTC) and psource == "SESSION_DATE"


def test_old_keys_are_untouched_and_downloads_get_the_readable_name(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        old_key = f"media/{ids.lead.id}/0123456789abcdef0123456789abcdef/D_SEC_GIRIRAJ_001046_200620261824.m4a"
        rec = await recordings.complete(complete_request(old_key, s["id"], targets=[{"sectionId": ids.sections["D"]}],
                                                         at=datetime(2026, 6, 20, 12, 54, tzinfo=UTC)), ids.lead, client=client)
        rec = rec[0]
        stored = await client.query_raw('SELECT "objectKey" FROM "MediaFile" WHERE "id" = $1', rec["mediaFileId"])
        assert stored[0]["objectKey"] == old_key
        assert rec["downloadName"].endswith("_Giriraj-Prasad-Chhipa_Recording_D-All.m4a")
        url = await recordings.download_url(rec["id"], ids.lead, client=client)
        assert rec["downloadName"] in url
        await client.disconnect()

    run(go())
