"""Questionnaire v2, safe to switch on: the backend findings of the 21 Oct audit, pinned
(C:/dev/IIT/docs/questionnaire/AUDIT_FR_2026-10-21.md). Each test names the audit item it holds.

Real Postgres where the rule lives in SQL (``QV2_TEST_DATABASE_URL``, a migrated throwaway database,
never production); pure where it does not. S3 and every provider are stand-ins.
"""

from __future__ import annotations

import asyncio
import hashlib
import uuid
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

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
    settings_v2,
    t,
)


def _code(exc: pytest.ExceptionInfo) -> str:
    return exc.value.detail["code"]


def _presign(ids: Any, session_id: str, clip: str, **extra: Any) -> Any:
    from app.schemas.questionnaire_v2 import PresignRequest

    return PresignRequest.model_validate({
        "linkedRecordType": "questionnaire_session", "sessionId": session_id, "clientClipKey": clip, "sizeBytes": 3_400_000,
        "contentType": "audio/mp4", "extension": "m4a", "targets": [{"questionLineageId": ids.questions["D3"]}],
        "subjectArtisanIds": [ids.artisans[0]], "deviceCapturedAt": "2026-06-20T12:54:00Z", "clockOffsetMs": 0, **extra,
    })


def _today() -> str:
    from app.services.questionnaire_v2.common import today_ist

    return today_ist().isoformat()


# ── H1, H2: permissions on replace and decide ─────────────────────────────────────────────────
@needs_db
def test_h1_a_replace_removes_only_for_a_content_admin_and_only_in_the_same_session(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        other, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2], day="2026-06-21"), ids.other)
        mine = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        theirs = await record_take(client, ids, other["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(24))
        # A researcher naming somebody else's take in another session removes nothing.
        await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0.1),
                          replacesRecordingId=theirs["id"])
        assert (await recordings.recording_payload(client, theirs["id"]))["removedAt"] is None
        # Even their own take: removal is an admin's act; both are kept and the new one is the redo.
        redo = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0.2),
                                 replacesRecordingId=mine["id"], ackedRecordingIds=[mine["id"]])
        assert (await recordings.recording_payload(client, mine["id"]))["removedAt"] is None
        assert redo["subjects"][0]["repeatReason"] == "REDO"
        # An admin's replace in the same session does remove, and is restorable.
        admin_take = await record_take(client, ids, s["id"], ids.admin, targets=[{"sectionId": ids.sections["D"]}], at=t(0.3),
                                       replacesRecordingId=redo["id"], ackedRecordingIds=[mine["id"], redo["id"]])
        assert (await recordings.recording_payload(client, redo["id"]))["removedAt"] is not None
        # ...but never across sessions.
        await record_take(client, ids, s["id"], ids.admin, targets=[{"sectionId": ids.sections["F"]}], at=t(0.4),
                          replacesRecordingId=theirs["id"])
        assert (await recordings.recording_payload(client, theirs["id"]))["removedAt"] is None
        assert admin_take["filing"] == "FILED"
        await client.disconnect()

    run(go())


@needs_db
def test_h2_decide_needs_a_live_other_take_in_the_session_on_the_same_target(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import DecideRequest
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        elsewhere, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], day="2026-06-22"), ids.lead)
        one = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        two = await record_take(client, ids, s["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(0.5))
        assert two["filing"] == "NEEDS_DECISION"
        far = await record_take(client, ids, elsewhere["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(48))
        section_b = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0.6))
        for other in (two["id"], far["id"], section_b["id"], "no-such-take"):
            with pytest.raises(HTTPException) as bad:
                await recordings.decide(two["id"], DecideRequest(action="CONTINUATION", otherRecordingId=other), ids.lead, client=client)
            assert _code(bad) == "VALIDATION_FAILED", other
        # Keep one removes the other take: content admins only.
        with pytest.raises(HTTPException) as keep:
            await recordings.decide(two["id"], DecideRequest(action="KEEP_THIS", otherRecordingId=one["id"]), ids.prof, client=client)
        assert keep.value.status_code == 403
        kept = await recordings.decide(two["id"], DecideRequest(action="KEEP_THIS", otherRecordingId=one["id"]), ids.admin, client=client)
        assert kept["filing"] == "FILED"
        assert (await recordings.recording_payload(client, one["id"]))["removedAt"] is not None
        await client.disconnect()

    run(go())


# ── H4: merged sessions are followed ──────────────────────────────────────────────────────────
@needs_db
def test_h4_presign_complete_and_the_client_key_follow_a_merge_and_merges_never_chain(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        a_body = session_create(ids, ids.artisans[:1])
        a, _ = await sessions.create_session(a_body, ids.lead)
        b, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.lead)
        c, _ = await sessions.create_session(session_create(ids, ids.artisans[2:3]), ids.lead)
        await sessions.merge_into(a["id"], b["id"], ids.admin)
        # A merge naming a merged-away target lands where that session lives now.
        merged = await sessions.merge_into(c["id"], a["id"], ids.admin)
        assert merged["id"] == b["id"]
        intent = await recordings.presign(_presign(ids, a["id"], f"h4-{ids.tag}"), ids.lead)
        rows = await client.query_raw('SELECT "sessionId" FROM "MediaUploadIntent" WHERE "clientClipKey" = $1', f"h4-{ids.tag}")
        assert rows[0]["sessionId"] == b["id"]
        rec, _ = await recordings.complete(complete_request(intent["objectKey"], a["id"], clip=f"h4-{ids.tag}",
                                                            targets=[{"sectionId": ids.sections["D"]}]), ids.lead, client=client)
        assert rec["sessionId"] == b["id"], "a queued clip for a merged-away session is filed where it now lives"
        again, created = await sessions.create_session(a_body, ids.lead)
        assert again["id"] == b["id"] and created is False
        await client.disconnect()

    run(go())


# ── H8, H9: dead and closed uploads ───────────────────────────────────────────────────────────
@needs_db
def test_h8_a_dropped_multipart_upload_restarts_on_the_same_key_and_h9_a_closed_intent_signs_nothing(monkeypatch) -> None:
    from app.services import s3
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        stubs = no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        big = _presign(ids, s["id"], f"big-{ids.tag}", sizeBytes=40_000_000, extension="wav", contentType="audio/wav")
        first = await recordings.multipart_create(big, ids.lead)

        def gone(key: str, upload_id: str) -> list:
            raise s3.NoSuchUpload(upload_id)

        monkeypatch.setattr(s3, "list_uploaded_parts", gone)
        again = await recordings.multipart_create(big, ids.lead)
        assert again["uploadId"] != first["uploadId"] and again["objectKey"] == first["objectKey"] and again["partsDone"] == []
        state = await recordings.upload_state(f"big-{ids.tag}", ids.lead)
        assert state["multipartUploadId"] is None, "a dead id is cleared, never a 500"
        small = _presign(ids, s["id"], f"small-{ids.tag}")
        signed = await recordings.presign(small, ids.lead)
        await recordings.complete(complete_request(signed["objectKey"], s["id"], clip=f"small-{ids.tag}",
                                                   targets=[{"sectionId": ids.sections["D"]}]), ids.lead, client=client)
        presigned = len(stubs["calls"]["presign"])
        replay = await recordings.presign(small, ids.lead)
        assert replay == {"objectKey": signed["objectKey"], "closed": True}
        assert len(stubs["calls"]["presign"]) == presigned, "a filed clip's object is never signed again"
        assert (await recordings.multipart_create(_presign(ids, s["id"], f"small-{ids.tag}"), ids.lead))["closed"] is True
        await client.disconnect()

    run(go())


# ── H10 and the merged copy's targets: the sha worker is atomic and keeps the earliest ───────
@needs_db
def test_h10_the_sha_worker_keeps_the_earliest_copy_whichever_is_verified_first(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        same = lambda key: (hashlib.sha256(f"same bytes {ids.tag}".encode()).hexdigest(), 9)  # noqa: E731
        early = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0),
                                  subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED"}])
        late = await record_take(client, ids, s["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(0.1),
                                 subjects=[{"artisanId": ids.artisans[1], "role": "ANSWERED"}])
        # The LATER copy is verified first (no twin yet), then the earlier one meets it.
        assert (await recordings.verify_sha(late["mediaFileId"], client=client, hasher=same))["status"] == "VERIFIED"
        out = await recordings.verify_sha(early["mediaFileId"], client=client, hasher=same)
        assert out["status"] == "KEPT_EARLIEST"
        kept = await recordings.recording_payload(client, early["id"])
        gone = await recordings.recording_payload(client, late["id"])
        assert kept["removedAt"] is None and gone["removedAt"] is not None
        assert {x["artisanId"] for x in kept["subjects"]} == set(ids.artisans[:2])
        assert {x["targetKey"] for x in kept["targets"]} == {f"S:{ids.sections['B']}", f"S:{ids.sections['D']}"}, "the copy's targets join the kept take"
        # Across sessions: the later copy waits for a decision, PENDING, pointing at its twin, and its
        # transcription stays queued because the twin has no transcript to give it.
        other, _ = await sessions.create_session(session_create(ids, ids.artisans[2:3], day="2026-06-25"), ids.other)
        far = await record_take(client, ids, other["id"], ids.other, targets=[{"sectionId": ids.sections["B"]}], at=t(100))
        assert (await recordings.verify_sha(far["mediaFileId"], client=client, hasher=same))["status"] == "SAME_FILE_OTHER_SESSION"
        row = await client.query_raw(
            'SELECT "status"::text AS s, "extraMetadata"->\'questionnaireV2\'->>\'twinRecordingId\' AS twin FROM "MediaFile" WHERE "id" = $1',
            far["mediaFileId"],
        )
        assert row[0]["s"] == "PENDING" and row[0]["twin"] == early["id"]
        jobs = await client.query_raw(
            "SELECT \"status\"::text AS s FROM \"MediaProcessingJob\" WHERE \"mediaFileId\" = $1 AND \"jobType\" = 'TRANSCRIPTION'",
            far["mediaFileId"],
        )
        assert jobs and all(j["s"] == "QUEUED" for j in jobs)
        await client.disconnect()

    run(go())


# ── H5: the old-build shims ───────────────────────────────────────────────────────────────────
def _old_body(ids: Any, artisans: list[str], **extra: Any) -> dict[str, Any]:
    return {
        "title": "typed by an old build", "questionnaireId": ids.q, "language": "Bangla", "artisanIds": artisans,
        "location": {"latitude": 26.8, "longitude": 75.5, "state": "Rajasthan", "district": "Jaipur"}, **extra,
    }


@needs_db
def test_h5_a_volunteer_old_create_is_attached_to_the_open_session_never_a_new_403(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireInterviewCreate
    from app.services.questionnaire_v2 import sessions

    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client, workshop=False)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2], day=_today()), ids.lead)
        request = SimpleNamespace(headers={"x-app-version": "0.0.13"})
        body = _old_body(ids, ids.artisans[:2], responses=[{"questionId": ids.questions["D1"], "answerText": "Chisels"}])
        attached = await questionnaire.create_interview(QuestionnaireInterviewCreate.model_validate(body), request, ids.vol)
        assert attached["id"] == s["id"], "attached to the matching open session, not refused"
        researchers = await client.query_raw(
            'SELECT "userId", "role"::text AS role FROM "QuestionnaireInterviewResearcher" WHERE "interviewId" = $1', s["id"]
        )
        assert {r["userId"]: r["role"] for r in researchers}[ids.vol.id] == "VOLUNTEER"
        count = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireInterview" WHERE "questionnaireId" = $1', ids.q)
        assert int(count[0]["n"]) == 1, "no new session"
        # Nothing to attach to: refused exactly as the old route refused a volunteer with no row to fold into.
        with pytest.raises(HTTPException) as nothing:
            await questionnaire.create_interview(QuestionnaireInterviewCreate.model_validate(_old_body(ids, ids.artisans[2:3])), request, ids.vol)
        assert nothing.value.status_code == 403
        await client.disconnect()

    run(go())


@needs_db
def test_h5_old_responses_are_create_only_and_the_patch_shim_keeps_v2_whole(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireInterviewCreate, QuestionnaireInterviewUpdate
    from app.schemas.questionnaire_v2 import AnswersPut
    from app.services.questionnaire_v2 import answers, sessions

    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client, workshop=False)
        a0, a1, a2 = ids.artisans
        s, _ = await sessions.create_session(session_create(ids, [a0, a1]), ids.lead)
        await answers.put_answers(AnswersPut.model_validate({"items": [
            {"artisanId": a0, "questionLineageId": ids.questions["D1"], "text": "Teak", "sessionId": s["id"]}]}), ids.prof)
        request = SimpleNamespace(headers={})
        body = _old_body(ids, [a0], responses=[{"questionId": ids.questions["D1"], "answerText": "stale words"}])
        await questionnaire.create_interview(QuestionnaireInterviewCreate.model_validate(body), request, ids.lead)
        current = await answers.current_answer(client, a0, ids.questions["D1"])
        assert current["text"] == "Teak", "an old build's text never overwrites a newer answer"
        await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0),
                          subjects=[{"artisanId": a0, "role": "ANSWERED"}])
        before = await client.query_raw('SELECT "title" FROM "QuestionnaireInterview" WHERE "id" = $1', s["id"])
        patch = QuestionnaireInterviewUpdate.model_validate({
            "title": "renamed by hand", "artisanIds": [a1, a2],
            "responses": [{"questionId": ids.questions["D2"], "answerText": "Sheesham"}],
        })
        await questionnaire.update_interview(s["id"], patch, ids.lead)
        row = await client.query_raw('SELECT "title", "legacyTitle" FROM "QuestionnaireInterview" WHERE "id" = $1', s["id"])
        # The typed title is standardised like every interview title, then kept as legacyTitle.
        assert (row[0]["legacyTitle"] or "").lower() == "renamed by hand" and row[0]["title"].lower() != "renamed by hand"
        assert row[0]["title"].startswith("Group-Interview_") and before[0]["title"].startswith("Group-Interview_")
        people = await sessions.participant_ids(client, s["id"])
        assert set(people) == {a0, a1, a2}, "a participant with takes is never dropped by an old edit; a new one is added"
        written = await answers.current_answer(client, a2, ids.questions["D2"])
        assert written and written["text"] == "Sheesham"
        frozen = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireResponse" WHERE "interviewId" = $1', s["id"])
        assert int(frozen[0]["n"]) == 0, "nothing is written to the frozen table v2 never reads"
        await client.disconnect()

    run(go())


# ── C7, H22: the build headers, the config and the telemetry ──────────────────────────────────
def test_c7_the_config_carries_the_per_platform_build_minimums(monkeypatch) -> None:
    from app.api.routes import questionnaire_v2 as routes
    from app.core.config import get_settings
    from app.schemas.questionnaire_v2 import Config

    async def paused(*_a: Any, **_k: Any) -> bool:
        return True

    monkeypatch.setattr(routes.transcription, "is_paused", paused)
    monkeypatch.setattr(get_settings(), "questionnaire_min_android_version_code_for_create", 31, raising=False)
    monkeypatch.setattr(get_settings(), "questionnaire_min_web_version_code_for_create", None, raising=False)
    config = asyncio.run(routes.questionnaire_config(None))
    Config.model_validate(config)
    assert config["minAndroidVersionCodeForCreate"] == 31 and config["minWebVersionCodeForCreate"] is None
    assert config["minAppVersionForCreate"] is None, "a version NAME minimum can no longer be compared"


def test_h22_the_middleware_notes_a_signed_in_build_once_per_window(monkeypatch) -> None:
    from app.services import app_versions

    app_versions._last_write.clear()
    written: list[tuple] = []

    async def record(user_id: str, platform: str, code: int, name: str | None, **_k: Any) -> None:
        written.append((user_id, platform, code, name))

    monkeypatch.setattr(app_versions, "record", record)

    async def inner(scope: dict, receive: Any, send: Any) -> None:
        app_versions.note_user(SimpleNamespace(scope=scope), "u-1")

    middleware = app_versions.AppVersionMiddleware(inner)

    def scope(headers: dict[str, str]) -> dict:
        return {"type": "http", "headers": [(k.encode(), v.encode()) for k, v in headers.items()]}

    async def go() -> None:
        await middleware(scope({"x-app-platform": "android", "x-app-version-code": "31", "x-app-version": "0.0.1"}), None, None)
        await middleware(scope({"x-app-platform": "android", "x-app-version-code": "31"}), None, None)  # throttled
        await middleware(scope({"user-agent": "okhttp/4.12.0"}), None, None)  # a build before the header contract
        await middleware(scope({"x-app-version": "web-0.1.0"}), None, None)
        await middleware(scope({"user-agent": "python-requests"}), None, None)  # a script: not an app
        await asyncio.sleep(0)
        await asyncio.sleep(0)

    asyncio.run(go())
    assert written == [("u-1", "android", 31, "0.0.1"), ("u-1", "android", 0, None), ("u-1", "web", 0, "web-0.1.0")]


@needs_db
def test_h22_app_versions_are_upserted_and_summarised_for_the_admin(monkeypatch) -> None:
    from app.api.routes import app_versions as route
    from app.core.config import get_settings
    from app.services import app_versions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        ids = await seed(client)
        monkeypatch.setattr(get_settings(), "questionnaire_min_android_version_code_for_create", 31, raising=False)
        await app_versions.record(ids.lead.id, "android", 29, "0.0.1", client=client)
        await app_versions.record(ids.lead.id, "android", 29, None, client=client)
        await app_versions.record(ids.other.id, "android", 31, "0.0.1", client=client)
        rows = await client.query_raw('SELECT "versionName" FROM "AppClientVersion" WHERE "userId" = $1', ids.lead.id)
        assert [r["versionName"] for r in rows] == ["0.0.1"], "one row per build, the name kept"
        summary = await app_versions.summary(days=30, client=client)
        mine = {(a["userId"], a["versionCode"]) for a in summary["accounts"]}
        assert {(ids.lead.id, 29), (ids.other.id, 31)} <= mine
        assert summary["minimumVersionCodeForCreate"]["android"] == 31 and summary["accountsBelow"]["android"] >= 1
        with pytest.raises(HTTPException):
            from app.core.deps import require_admin

            await require_admin(ids.lead)
        assert (await route.app_versions_summary(30, ids.admin))["days"] == 30
        await client.disconnect()

    run(go())


# ── H13, H14: the decisions inbox names what its buttons act on, and "File it" works ─────────
@needs_db
def test_h13_inbox_rows_carry_the_other_take_and_h14_file_it_files_a_take_and_a_clip(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import FileMediaRequest, FileRecordingRequest
    from app.services.questionnaire_v2 import inbox, recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        one = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        two = await record_take(client, ids, s["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(0.5))
        rows = (await inbox.inbox(ids.lead, client=client))["needsDecision"]
        row = next(r for r in rows if r["recordingId"] == two["id"])
        assert row["otherRecordingId"] == one["id"] and row["otherRecordingIds"] == [one["id"]]
        # An unresolved take: "File it" names its targets and files it.
        await client.execute_raw(
            'UPDATE "QuestionnaireRecording" SET "filing" = \'NEEDS_DECISION\', "decisionKind" = \'UNRESOLVED_LEGACY\' WHERE "id" = $1', one["id"]
        )
        filed = await recordings.file_recording(one["id"], FileRecordingRequest.model_validate(
            {"targets": [{"questionLineageId": ids.questions["D2"]}]}), ids.lead, client=client)
        assert filed["filing"] == "FILED" and filed["provenance"] == "MANUAL"
        assert [x["targetKey"] for x in filed["targets"]] == [f"Q:{ids.questions['D2']}"]
        # A clip with no recording at all ("To file").
        media_id = f"tofile{ids.tag}"
        await client.execute_raw(
            'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
            '"linkedRecordType", "uploadedById", "updatedAt") VALUES ($1, \'clip.m4a\', \'AUDIO\', \'audio/mp4\', 5, \'b\', $2, '
            "'questionnaire', $3, now())",
            media_id, f"media/{ids.lead.id}/{uuid.uuid4().hex}/clip.m4a", ids.lead.id,
        )
        assert media_id in [m["mediaId"] for m in (await inbox.inbox(ids.lead, client=client))["toFile"]]
        rec = await recordings.file_media(media_id, FileMediaRequest.model_validate(
            {"sessionId": s["id"], "targets": [{"sectionId": ids.sections["B"]}]}), ids.lead, client=client)
        assert rec["sessionId"] == s["id"] and rec["provenance"] == "MANUAL" and rec["filing"] == "FILED"
        assert media_id not in [m["mediaId"] for m in (await inbox.inbox(ids.lead, client=client))["toFile"]]
        await client.disconnect()

    run(go())


# ── M2, M3, M9: coverage ──────────────────────────────────────────────────────────────────────
@needs_db
def test_m2_an_undecided_take_covers_nothing_and_a_retired_question_never_completes_a_section(monkeypatch) -> None:
    from app.services.questionnaire_v2 import coverage, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client, sections={"B": 2, "D": 2})
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        await client.execute_raw('UPDATE "QuestionnaireRecording" SET "filing" = \'NEEDS_DECISION\' WHERE "id" = $1', take["id"])
        grid = await coverage.grid(ids.q, artisan_ids=ids.artisans[:1], client=client)
        cell = next(sec for sec in grid["sections"] if sec["code"] == "D")["cells"][0]
        assert cell["state"] == "NEEDS_DECISION" and not coverage.section_covered(cell)
        for code in ("B1", "B2"):
            await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions[code]}], at=t(1))
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "retiredAt" = now(), "isActive" = false WHERE "id" = $1', ids.questions["B2"])
        await client.execute_raw(
            'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", "sortOrder", "sectionId", "updatedAt") '
            "VALUES ($1, $2, 'B', 'Section B', 'A new question nobody answered', 9, $3, now())", f"qB9{ids.tag}", ids.q, ids.sections["B"],
        )
        grid = await coverage.grid(ids.q, artisan_ids=ids.artisans[:1], client=client)
        b = next(sec for sec in grid["sections"] if sec["code"] == "B")["cells"][0]
        assert b["covered"] == 1 and b["total"] == 2 and not coverage.section_covered(b), "the retired B2 does not count"
        await client.disconnect()

    run(go())


@needs_db
def test_m3_the_snapshot_lists_every_changed_recording_and_m9_only_a_verified_hash_is_identical(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import CoverageCheckRequest, RecordingPatch
    from app.services.questionnaire_v2 import coverage, recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0),
                                 subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED"}])
        base = await coverage.snapshot(ids.q, ids.workshop, None, client=client)
        seq = int(base["asOfSeq"])
        await recordings.patch(take["id"], RecordingPatch(version=take["version"], subjects=[
            {"artisanId": ids.artisans[0], "role": "PRESENT"}]), ids.lead, client=client)
        delta = await coverage.snapshot(ids.q, ids.workshop, seq, client=client)
        assert take["id"] not in {x["recordingId"] for x in delta["takes"]}, "a PRESENT-only take has no take rows"
        assert take["id"] in delta["changedRecordingIds"], "...so the snapshot names it, and a client drops its old rows"
        assert int(delta["asOfSeq"]) >= seq
        sha = "ab" * 32
        await client.execute_raw('UPDATE "MediaFile" SET "contentSha256" = $2 WHERE "id" = $1', take["mediaFileId"], sha)
        ask = CoverageCheckRequest.model_validate({"questionnaireId": ids.q, "artisanIds": ids.artisans[:1],
                                                   "targets": [{"sectionId": ids.sections["D"]}], "contentSha256": sha})
        assert (await coverage.check(ask, ids.lead, client=client))["identical"] is None, "an unverified client hash hides nothing"
        await client.execute_raw('UPDATE "MediaFile" SET "shaVerifiedAt" = now() WHERE "id" = $1', take["mediaFileId"])
        assert (await coverage.check(ask, ids.lead, client=client))["identical"]["recordingId"] == take["id"]
        await client.disconnect()

    run(go())


# ── M5, M6: the clock offset's age, and a take is queued inside the transaction ───────────────
def test_m5_an_offset_older_than_seven_days_marks_the_time_estimated() -> None:
    from app.services.questionnaire_v2.recordings import FRESH_OFFSET, correct_capture_time, offset_age

    now = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)
    age = offset_age("2026-06-20T12:00:00Z", now)
    assert age is not None and age > FRESH_OFFSET and offset_age(None, now) is None
    _, source, _ = correct_capture_time(device_captured_at=now - timedelta(hours=1), clock_offset_ms=500, source="DEVICE",
                                        session_date=None, server_now=now, offset_age=age)
    assert source == "UPLOAD_TIME"
    _, fresh, _ = correct_capture_time(device_captured_at=now - timedelta(hours=1), clock_offset_ms=500, source="DEVICE",
                                       session_date=None, server_now=now, offset_age=offset_age("2026-06-30T12:00:00Z", now))
    assert fresh == "DEVICE"


@needs_db
def test_m5_complete_reads_the_offset_age_and_m6_the_transcription_job_is_written_with_the_take(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        stale = (datetime.now(UTC) - timedelta(days=9)).isoformat()
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0), clockOffsetMeasuredAt=stale)
        assert take["capturedAtSource"] == "UPLOAD_TIME" and take["estimated"] is True
        jobs = await client.query_raw(
            "SELECT \"status\"::text AS s FROM \"MediaProcessingJob\" WHERE \"mediaFileId\" = $1 AND \"jobType\" = 'TRANSCRIPTION'",
            take["mediaFileId"],
        )
        assert [j["s"] for j in jobs] == ["QUEUED"], "queued in the same transaction, paused, no provider called"
        # A take from before the fix that was never queued is queued by its replay.
        await client.execute_raw("DELETE FROM \"MediaProcessingJob\" WHERE \"mediaFileId\" = $1 AND \"jobType\" = 'TRANSCRIPTION'", take["mediaFileId"])
        await client.execute_raw('UPDATE "MediaFile" SET "transcriptStatus" = NULL WHERE "id" = $1', take["mediaFileId"])
        await recordings.complete(complete_request(key_for(ids.lead), s["id"], clip=take["clientClipKey"]), ids.lead, client=client)
        again = await client.query_raw(
            "SELECT count(*) AS n FROM \"MediaProcessingJob\" WHERE \"mediaFileId\" = $1 AND \"jobType\" = 'TRANSCRIPTION'", take["mediaFileId"]
        )
        assert int(again[0]["n"]) == 1
        await client.disconnect()

    run(go())


# ── M7: a refusal is REFUSED_CONSENT, and a later grant sends again by every link ─────────────
def test_m7_the_queue_writes_a_refusal_as_refused_consent(monkeypatch) -> None:
    from app.services import media_queue
    from app.transcription.consent import verdict_for

    notes: list[dict] = []

    class _Media:
        async def update(self, **kwargs: Any) -> None:
            notes.append(kwargs["data"])

    monkeypatch.setattr(media_queue, "db", SimpleNamespace(mediafile=_Media()))
    asyncio.run(media_queue._record_consent_refusal({"id": "m-1"}, verdict_for("REFUSED")))
    assert notes[-1]["transcriptStatus"] == "REFUSED_CONSENT"
    asyncio.run(media_queue._record_consent_refusal({"id": "m-2"}, verdict_for(None)))
    assert notes[-1]["transcriptStatus"] == media_queue.FAILED, "an unreadable store retries; it is not a refusal"


@needs_db
def test_m7_a_grant_requeues_held_recordings_by_every_link_the_gate_reads(monkeypatch) -> None:
    from app.services import consent_gate
    from app.services import transcription_enqueue as transcription
    from app.services.questionnaire_v2 import sessions
    from app.transcription.consent import CONSENT_REFUSED_MESSAGE

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        held = {}
        for name, status, error in (("legacy", "FAILED", CONSENT_REFUSED_MESSAGE), ("refused", "REFUSED_CONSENT", None), ("other", "FAILED", "network")):
            mid = f"{name}{ids.tag}"
            held[name] = mid
            await client.execute_raw(
                'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
                '"questionnaireInterviewId", "uploadedById", "transcriptStatus", "transcriptError", "updatedAt") '
                "VALUES ($1, 'x.m4a', 'AUDIO', 'audio/mp4', 5, 'b', $2, $3, $4, $5, $6, now())",
                mid, f"media/{ids.lead.id}/{mid}.m4a", s["id"], ids.lead.id, status, error,
            )
        await consent_gate.record_artisan_consent(ids.artisans[0], state="GRANTED", basis="VERBAL_RECORDED", actor_id=ids.lead.id, client=client)
        queued = await transcription.requeue_held(artisan_id=ids.artisans[0], requested_by_id=ids.lead.id, client=client)
        assert queued == 2, "the old FAILED-for-consent note and REFUSED_CONSENT, never an unrelated failure"
        rows = await client.query_raw('SELECT "id", "transcriptStatus" FROM "MediaFile" WHERE "id" = ANY($1::text[])', list(held.values()))
        by = {r["id"]: r["transcriptStatus"] for r in rows}
        assert by[held["legacy"]] == "QUEUED" and by[held["refused"]] == "QUEUED" and by[held["other"]] == "FAILED"
        await client.disconnect()

    run(go())


# ── M10, M11, M12: moves go through the service, and removed takes can be listed ─────────────
@needs_db
def test_m10_m11_a_relink_and_a_move_add_the_subjects_and_m12_removed_takes_are_listed(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import RecordingPatch
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        a, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        b, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2], day="2026-06-21"), ids.lead)
        c, _ = await sessions.create_session(session_create(ids, ids.artisans[2:3], day="2026-06-22"), ids.lead)
        take = await record_take(client, ids, a["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        moved = await recordings.relink_take(take["id"], b["id"], ids.admin, client=client)
        assert moved["sessionId"] == b["id"]
        assert ids.artisans[0] in await sessions.participant_ids(client, b["id"]), "the take's subject joins the session it moved to"
        events = await client.query_raw('SELECT "kind" FROM "QuestionnaireRecordingEvent" WHERE "recordingId" = $1', take["id"])
        assert "MOVE" in {e["kind"] for e in events}
        title = await client.query_raw('SELECT "title" FROM "QuestionnaireInterview" WHERE "id" = $1', b["id"])
        assert title[0]["title"].startswith("Group-Interview_"), "the title follows the new participant"
        patched = await recordings.patch(take["id"], RecordingPatch(version=moved["version"], sessionId=c["id"]), ids.lead, client=client)
        assert patched["sessionId"] == c["id"] and ids.artisans[0] in await sessions.participant_ids(client, c["id"])
        await recordings.remove(take["id"], ids.admin, client=client)
        listed = await recordings.removed_takes(ids.lead, session_id=c["id"], client=client)
        assert [r["id"] for r in listed["items"]] == [take["id"]]
        by_artisan = await recordings.removed_takes(ids.lead, artisan_id=ids.artisans[0], client=client)
        assert take["id"] in [r["id"] for r in by_artisan["items"]]
        await client.disconnect()

    run(go())


# ── M13, M14: changes after approval reach review, and a primary change is written ───────────
@needs_db
def test_m13_new_content_on_an_approved_session_is_back_in_review_and_m14_primary_changes_are_logged(monkeypatch) -> None:
    from app.api.routes import review
    from app.schemas.common import ReviewAction
    from app.services import email_outbox
    from app.services.questionnaire_v2 import inbox, recordings, sessions

    async def no_mail(**kwargs: Any) -> int:
        return 0

    monkeypatch.setattr(email_outbox, "notify_review_decision", no_mail)
    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        first = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        await review.set_review_status("questionnaire", s["id"], "APPROVED", ReviewAction(notes=None), ids.admin, notify=False)
        assert review.pending_where("questionnaire", review.PendingFilters())["OR"][1]["pendingChangesAt"] == {"not": None}
        second = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(30))
        row = await client.query_raw('SELECT "status"::text AS s, "pendingChangesAt" FROM "QuestionnaireInterview" WHERE "id" = $1', s["id"])
        assert row[0]["s"] == "APPROVED" and row[0]["pendingChangesAt"] is not None, "back in the review queue, still approved"
        await review.set_review_status("questionnaire", s["id"], "APPROVED", ReviewAction(notes=None), ids.admin, notify=False)
        row = await client.query_raw('SELECT "pendingChangesAt" FROM "QuestionnaireInterview" WHERE "id" = $1', s["id"])
        assert row[0]["pendingChangesAt"] is None
        # The later take is the same researcher's redo, so it is primary; making the first one primary
        # again is a change of primary, and the inbox shows it.
        await recordings.make_primary(first["id"], SimpleNamespace(artisanId=ids.artisans[0], targetKey=f"S:{ids.sections['D']}"),
                                      ids.prof, client=client)
        changed = (await inbox.inbox(ids.lead, client=client))["primaryChanged"]
        import json

        moves = [c["change"] if isinstance(c["change"], dict) else json.loads(c["change"]) for c in changed if c["recordingId"] == first["id"]]
        assert any(m["from"] == second["id"] for m in moves), "the inbox's Primary changed section is fed"
        await client.disconnect()

    run(go())


# ── M15: answers are about people who were there; proposals need the right to change the take ─
@needs_db
def test_m15_answer_writes_and_proposals_are_permissioned(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import AnswersPut
    from app.services.questionnaire_v2 import answers, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        out = await answers.put_answers(AnswersPut.model_validate({"items": [
            {"artisanId": ids.artisans[2], "questionLineageId": ids.questions["D1"], "text": "Not here", "sessionId": s["id"]},
            {"artisanId": ids.artisans[0], "questionLineageId": ids.questions["D1"], "text": "Here", "sessionId": s["id"]},
        ]}), ids.lead)
        assert [r["status"] for r in out["results"]] == ["FORBIDDEN", "SAVED"]
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D2"]}], at=t(0))
        with pytest.raises(HTTPException) as refused:
            await answers.proposals(take["id"], ids.vol)
        assert refused.value.status_code == 403
        await client.disconnect()

    run(go())


# ── M16: instruments and the builder ──────────────────────────────────────────────────────────
@needs_db
def test_m16_sessions_start_only_on_live_instruments_and_the_builder_keeps_coverage(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import (
        QuestionnaireDefaultUpdate,
        QuestionnaireQuestionReorder,
        QuestionnaireQuestionUpdate,
        QuestionnaireSectionUpdate,
        QuestionnaireUpdate,
    )
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        # The professor edits the form as its author (owner, 22 Oct 2026: author or content admin).
        await client.execute_raw('UPDATE "Questionnaire" SET "createdById" = $1 WHERE "id" = $2', ids.prof.id, ids.q)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D1"]}], at=t(0))
        # A reword of a question with takes supersedes it: same lineage and code, a new row.
        new = await questionnaire.update_question(ids.questions["D1"], QuestionnaireQuestionUpdate(prompt="Which chisel do you reach for first?"), ids.prof)
        assert new["id"] != ids.questions["D1"]
        old = await client.query_raw('SELECT "supersededById", "retiredAt" FROM "QuestionnaireQuestion" WHERE "id" = $1', ids.questions["D1"])
        assert old[0]["supersededById"] == new["id"] and old[0]["retiredAt"] is not None
        lineage = await client.query_raw('SELECT "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "id" = $1', new["id"])
        assert lineage[0]["lineageId"] == ids.questions["D1"] and lineage[0]["displayCode"] == ids.codes[ids.questions["D1"]]
        # A reorder that re-parents a question with takes is refused, flag or no flag.
        with pytest.raises(HTTPException) as moved:
            await questionnaire.reorder_questions(QuestionnaireQuestionReorder(sectionId=ids.sections["F"], questionIds=[new["id"]]), ids.prof)
        assert moved.value.status_code == 409
        # Deleting a section and restoring it brings back exactly the questions the delete switched off.
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "isActive" = false WHERE "id" = $1', ids.questions["B2"])
        await questionnaire.delete_section(ids.sections["B"], ids.admin)
        await questionnaire.update_section(ids.sections["B"], QuestionnaireSectionUpdate(isActive=True), ids.prof)
        active = await client.query_raw('SELECT "id", "isActive" FROM "QuestionnaireQuestion" WHERE "sectionId" = $1', ids.sections["B"])
        assert {r["id"]: r["isActive"] for r in active} == {ids.questions["B1"]: True, ids.questions["B2"]: False}
        # Retiring through PATCH is an admin's delete, like DELETE.
        other_q = f"q2{ids.tag}"
        await client.execute_raw('INSERT INTO "Questionnaire" ("id", "title", "sortOrder", "updatedAt") VALUES ($1, $2, 2, now())', other_q, f"Second {ids.tag}")
        with pytest.raises(HTTPException) as retire:
            await questionnaire.update_questionnaire(other_q, QuestionnaireUpdate(isActive=False), ids.prof)
        assert retire.value.status_code == 403
        # Clearing the default on another instrument never clears the real one.
        await questionnaire.set_default_questionnaire(ids.q, QuestionnaireDefaultUpdate(isDefault=True), ids.admin)
        await questionnaire.set_default_questionnaire(other_q, QuestionnaireDefaultUpdate(isDefault=False), ids.admin)
        default = await client.query_raw('SELECT "isDefault" FROM "Questionnaire" WHERE "id" = $1', ids.q)
        assert default[0]["isDefault"] is True
        # A session never starts on a retired instrument.
        await client.execute_raw('UPDATE "Questionnaire" SET "isActive" = false WHERE "id" = $1', other_q)
        body = session_create(ids, ids.artisans[:1], workshopId=None).model_copy(update={"questionnaireId": other_q})
        with pytest.raises(HTTPException) as retired:
            await sessions.create_session(body, ids.lead)
        assert _code(retired) == "VALIDATION_FAILED"
        await client.disconnect()

    run(go())


def test_m16_a_file_name_reads_the_frozen_display_code_after_a_section_rename() -> None:
    from app.services import naming
    from app.services.questionnaire_v2.recordings import target_ref

    ref = target_ref(section_code="E", display_code="D3", is_question=True)
    assert naming.recording_sequence([ref]) == "D-Q03", "D3 under a renamed section is still D-Q03, never E-All"
    assert naming.recording_sequence([target_ref(section_code="F2", display_code="F21", is_question=True)]) == "F2-Q01", "a section code ending in a digit"
    assert naming.recording_sequence([target_ref(section_code="B", display_code=None, is_question=False)]) == "B-All"


# ── M17: the artisan record lists each question once, and only live ones as live ──────────────
@needs_db
def test_m17_the_record_lists_a_reworded_question_once_and_a_deleted_one_as_not_live(monkeypatch) -> None:
    from app.services.questionnaire_v2 import record, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client, sections={"D": 3})
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D1"]}], at=t(0))
        new_id = f"qD1b{ids.tag}"
        await client.execute_raw(
            'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", "sortOrder", "sectionId", "updatedAt") '
            "VALUES ($1, $2, 'D', 'Section D', 'Reworded D1', 1, $3, now())", new_id, ids.q, ids.sections["D"],
        )
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "retiredAt" = now(), "isActive" = false, "supersededById" = $2 WHERE "id" = $1',
                                 ids.questions["D1"], new_id)
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "isActive" = false WHERE "id" = $1', ids.questions["D3"])  # builder delete
        out = await record.artisan_record(ids.artisans[0], ids.lead, questionnaire_id=ids.q, client=client)
        doc = out["instruments"][0]["document"]
        codes = [q["displayCode"] for sec in doc for q in sec["questions"]]
        assert codes.count(ids.codes[ids.questions["D1"]]) == 1, "a reworded question is listed once"
        assert ids.codes[ids.questions["D3"]] not in codes, "a question the builder deleted is not shown as live"
        d1 = next(q for sec in doc for q in sec["questions"] if q["displayCode"] == ids.codes[ids.questions["D1"]])
        assert d1["recordedUnderEarlierWording"] is True
        await client.disconnect()

    run(go())


# ── M24: the v2 rows move and the merged artisan goes in one transaction ─────────────────────
@needs_db
def test_m24_the_artisan_merge_moves_v2_rows_and_deletes_in_one_transaction(monkeypatch) -> None:
    from app.services.questionnaire_v2 import merge, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        keep, dupe = ids.artisans[0], ids.artisans[1]
        s, _ = await sessions.create_session(session_create(ids, [keep]), ids.lead)
        await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0),
                          subjects=[{"artisanId": dupe, "role": "ANSWERED"}])
        # The participant rows are the base merge's; this test runs only the v2 step and the delete.
        await client.execute_raw('DELETE FROM "QuestionnaireInterviewArtisan" WHERE "artisanId" = $1', dupe)

        async def boom(*_a: Any, **_k: Any) -> None:
            raise RuntimeError("the delete failed")

        real = merge.move_rows

        async def moved_then_fail(tx: Any, keep_id: str, merge_id: str) -> dict:
            out = await real(tx, keep_id, merge_id)
            await boom()
            return out

        monkeypatch.setattr(merge, "move_rows", moved_then_fail)
        with pytest.raises(RuntimeError):
            await merge.merge_and_delete(keep, dupe, client=client)
        still = await client.query_raw('SELECT "artisanId" FROM "QuestionnaireRecordingSubject" WHERE "artisanId" = $1', dupe)
        assert still, "a failure part-way moves nothing: one transaction"
        monkeypatch.setattr(merge, "move_rows", real)
        await merge.merge_and_delete(keep, dupe, client=client)
        gone = await client.query_raw('SELECT count(*) AS n FROM "Artisan" WHERE "id" = $1', dupe)
        moved = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireRecordingSubject" WHERE "artisanId" = $1', keep)
        assert int(gone[0]["n"]) == 0 and int(moved[0]["n"]) == 1
        await client.disconnect()

    run(go())


# ── M26: the session payload's query count does not grow with its takes ───────────────────────
class _Counting:
    """The Prisma client, counting raw reads."""

    def __init__(self, inner: Any) -> None:
        self._inner = inner
        self.reads = 0

    async def query_raw(self, *args: Any) -> Any:
        self.reads += 1
        return await self._inner.query_raw(*args)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._inner, name)


@needs_db
def test_m26_serializing_takes_costs_a_fixed_number_of_reads(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:3]), ids.lead)

        async def reads_for(n: int) -> int:
            rows = (await recordings.recording_rows(client, session_id=s["id"]))[:n]
            counting = _Counting(client)
            await recordings.serialize_recordings(counting, rows, ids.lead)
            return counting.reads

        for i in range(2):
            await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["F"]}], at=t(i * 3))
        two = await reads_for(2)
        for i in range(2, 10):
            await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["F"]}], at=t(i * 3))
        ten = await reads_for(10)
        assert ten == two, f"{two} reads for 2 takes, {ten} for 10: the consent reads are batched, never per take"
        await client.disconnect()

    run(go())


# ── M27: session insights are the takes' summaries ────────────────────────────────────────────
@needs_db
def test_m27_session_insights_return_the_takes_summaries(monkeypatch) -> None:
    from app.api.routes import questionnaire_v2 as routes
    from app.services import transcript_insights
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(1))
        monkeypatch.setattr(transcript_insights, "enabled", lambda: False)
        assert await routes.sessions_insights(s["id"], ids.lead) == {"items": []}
        monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
        await client.execute_raw('UPDATE "MediaFile" SET "transcriptStatus" = \'COMPLETED\', "transcriptText" = \'We dye with indigo.\' WHERE "id" = $1',
                                 take["mediaFileId"])
        out = await routes.sessions_insights(s["id"], ids.lead)
        assert [i["recordingId"] for i in out["items"]] == [take["id"]], "one item per take with a transcript"
        assert out["items"][0]["state"] == "NOT_GENERATED" and out["items"][0]["mediaId"] == take["mediaFileId"]
        await client.disconnect()

    run(go())


# ── Low items ─────────────────────────────────────────────────────────────────────────────────
@needs_db
def test_low_part_two_names_end_p2_and_complete_checks_its_object_and_its_caller(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        stubs = no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        part1 = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D3"]}], at=t(0))
        p2 = await recordings.presign(_presign(ids, s["id"], f"p2-{ids.tag}", continuationOfId=part1["id"]), ids.lead)
        assert p2["objectKey"].endswith("_Recording_D-Q03-P2.m4a"), p2["objectKey"]
        # The object must be there.
        missing = key_for(ids.lead)
        stubs["missing"].add(missing)
        with pytest.raises(HTTPException) as nothing:
            await recordings.complete(complete_request(missing, s["id"]), ids.lead, client=client)
        assert nothing.value.status_code == 404 and nothing.value.detail["reason"] == "UPLOAD_MISSING"
        # The object must be the one the clip was signed for.
        with pytest.raises(HTTPException) as wrong:
            await recordings.complete(complete_request(key_for(ids.lead), s["id"], clip=f"p2-{ids.tag}"), ids.lead, client=client)
        assert _code(wrong) == "VALIDATION_FAILED"
        # A volunteer who is not on the session is refused.
        with pytest.raises(HTTPException) as volunteer:
            await recordings.complete(complete_request(key_for(ids.vol), s["id"]), ids.vol, client=client)
        assert volunteer.value.status_code == 403
        await client.disconnect()

    run(go())


@needs_db
def test_low_an_expired_intent_is_rearmed_never_closed_and_the_sweep_aborts_its_upload(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        stubs = no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        big = _presign(ids, s["id"], f"old-{ids.tag}", sizeBytes=40_000_000, extension="wav", contentType="audio/wav")
        first = await recordings.multipart_create(big, ids.lead)
        await client.execute_raw("UPDATE \"MediaUploadIntent\" SET \"expiresAt\" = now() - interval '1 day' WHERE \"clientClipKey\" = $1", f"old-{ids.tag}")
        swept = await recordings.sweep_expired_intents(client=client)
        assert swept >= 1 and (first["objectKey"], first["uploadId"]) in stubs["calls"]["aborted"]
        again = await recordings.multipart_create(big, ids.lead)
        assert again["closed"] is False and again["objectKey"] == first["objectKey"] and again["uploadId"] != first["uploadId"]
        row = await client.query_raw('SELECT "expiresAt" > (now() AT TIME ZONE \'UTC\') AS fresh, "closedAt" FROM "MediaUploadIntent" WHERE "clientClipKey" = $1',
                                     f"old-{ids.tag}")
        assert row[0]["fresh"] is True and row[0]["closedAt"] is None
        await client.disconnect()

    run(go())


@needs_db
def test_low_continue_is_idempotent_and_join_checks_the_workshop_and_limits_guesses(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import ContinueRequest
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        ask = ContinueRequest(fromSessionId=s["id"], artisanIds=ids.artisans[:2], split="EACH", sessionDate="2026-06-23")
        one = await sessions.continue_sessions(ask, ids.lead)
        two = await sessions.continue_sessions(ask, ids.lead)
        assert [x["id"] for x in one["sessions"]] == [x["id"] for x in two["sessions"]], "a replay makes no new sessions"
        sessions._JOIN_MISSES.clear()
        for _ in range(sessions.JOIN_MISSES_PER_WINDOW):
            with pytest.raises(HTTPException):
                await sessions.join("ZZZZZ9", ids.other)
        with pytest.raises(HTTPException) as limited:
            await sessions.join("ZZZZZ8", ids.other)
        assert limited.value.status_code == 429
        sessions._JOIN_MISSES.clear()

        async def refuse(user: Any, workshop_id: Any, **_k: Any) -> None:
            raise HTTPException(status_code=403, detail="not assigned")

        from app.services import workshop_access

        monkeypatch.setattr(workshop_access, "enforce_workshop_submission", refuse)
        with pytest.raises(HTTPException) as unassigned:
            await sessions.join(s["joinCode"], ids.other)
        assert unassigned.value.status_code == 403
        await client.disconnect()

    run(go())


@needs_db
def test_low_an_unexpected_answer_error_is_a_conflict_that_carries_the_stored_answer(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import AnswersPut
    from app.services.questionnaire_v2 import answers, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        item = {"artisanId": ids.artisans[0], "questionLineageId": ids.questions["D1"], "text": "Teak", "sessionId": s["id"]}
        await answers.put_answers(AnswersPut.model_validate({"items": [item]}), ids.lead)

        async def broken(*_a: Any, **_k: Any) -> dict:
            raise RuntimeError("connection reset")

        monkeypatch.setattr(answers, "_write_one", broken)
        out = await answers.put_answers(AnswersPut.model_validate({"items": [{**item, "text": "Sal", "baseVersion": 1}]}), ids.lead)
        assert out["results"][0]["status"] == "CONFLICT" and out["results"][0]["current"]["text"] == "Teak"
        await client.disconnect()

    run(go())


@needs_db
def test_low_set_dates_is_one_row_per_workshop_day(monkeypatch) -> None:
    from app.services.questionnaire_v2 import inbox, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        made = []
        for day in ("2026-06-20", "2026-06-20", "2026-06-21"):
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], day=day), ids.lead)
            made.append(s["id"])
        await client.execute_raw(
            'UPDATE "QuestionnaireInterview" SET "origin" = \'LEGACY_RECONSTRUCTED\', "sessionDateEstimated" = true WHERE "id" = ANY($1::text[])', made
        )
        rows = [r for r in (await inbox.inbox(ids.admin, client=client))["setDates"] if r["workshopId"] == ids.workshop]
        assert sorted((r["day"], r["count"]) for r in rows) == [("2026-06-20", 2), ("2026-06-21", 1)]
        await client.disconnect()

    run(go())


@needs_db
def test_low_the_checksum_filler_writes_the_verified_sha_the_dedup_reads(monkeypatch, tmp_path) -> None:
    from scripts import backfill_media_checksums as filler

    async def go() -> None:
        client = await connect()
        ids = await seed(client)
        mid = f"sum{ids.tag}"
        await client.execute_raw(
            'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", "uploadedById", "updatedAt") '
            "VALUES ($1, 'x.m4a', 'AUDIO', 'audio/mp4', 5, 'b', $2, $3, now())", mid, f"media/x/{mid}.m4a", ids.lead.id,
        )
        blob = tmp_path / "blob.bin"
        blob.write_bytes(b"bytes of a recording")
        rows = [r for r in await filler.todo(client) if r["id"] == mid]
        written, failed = await filler.fill(client, rows, fetch=lambda _key: str(blob), discard=lambda _p: None)
        assert (written, failed) == (1, 0)
        row = await client.query_raw('SELECT "contentSha256", "shaVerifiedAt", "checksum" FROM "MediaFile" WHERE "id" = $1', mid)
        assert row[0]["contentSha256"] == hashlib.sha256(b"bytes of a recording").hexdigest() == row[0]["checksum"]
        assert row[0]["shaVerifiedAt"] is not None
        await client.disconnect()

    run(go())


def test_low_the_checksum_filler_refuses_an_unnamed_host() -> None:
    from scripts import backfill_media_checksums as filler

    with pytest.raises(SystemExit):
        filler.main(["--database-url", "postgresql://u:p@prod.example/db", "--allow-host", "throwaway.invalid"])


def test_low_an_approved_report_is_computed_as_of_a_fixed_day() -> None:
    import argparse

    from scripts import qv2_guard, questionnaire_purge

    end = qv2_guard.as_of(argparse.Namespace(as_of="2026-11-18", apply=True))
    assert end.isoformat() == "2026-11-18T18:29:59+00:00", "the end of the IST day"
    with pytest.raises(SystemExit):
        qv2_guard.as_of(argparse.Namespace(as_of=None, apply=True))
    text = questionnaire_purge.report([], [], "2026-11-18")
    assert "as of,2026-11-18" in text and text == questionnaire_purge.report([], [], "2026-11-18")


# ── The web's inbox actions (coordinator, 21 Oct): Not needed, and File it with named subjects ─
@needs_db
def test_not_needed_sets_a_clip_aside_on_the_server_once_and_file_it_takes_named_subjects(monkeypatch) -> None:
    from app.api.routes import questionnaire_v2 as routes
    from app.schemas.questionnaire_v2 import FileMediaRequest
    from app.services.questionnaire_v2 import inbox, recordings, sessions

    async def clip(client: Any, ids: Any, name: str) -> str:
        media_id = f"{name}{ids.tag}"
        await client.execute_raw(
            'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
            '"linkedRecordType", "uploadedById", "updatedAt") VALUES ($1, \'clip.m4a\', \'AUDIO\', \'audio/mp4\', 5, \'b\', $2, '
            "'questionnaire', $3, now())",
            media_id, f"media/{ids.lead.id}/{uuid.uuid4().hex}/clip.m4a", ids.lead.id,
        )
        return media_id

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        aside = await clip(client, ids, "aside")
        with pytest.raises(HTTPException) as stranger:
            await recordings.not_needed(aside, ids.other, client=client)
        assert stranger.value.status_code == 403
        first = await routes.media_not_needed(aside, ids.lead)
        again = await recordings.not_needed(aside, ids.admin, client=client)
        assert first["notNeeded"] is True and again["by"] == ids.lead.id and again["at"] == first["at"], "idempotent: the first mark stands"
        assert aside not in [m["mediaId"] for m in (await inbox.inbox(ids.lead, client=client))["toFile"]]
        live = await client.query_raw('SELECT "removedAt" FROM "MediaFile" WHERE "id" = $1', aside)
        assert live[0]["removedAt"] is None, "set aside, never deleted"
        named = await clip(client, ids, "named")
        rec = await recordings.file_media(named, FileMediaRequest.model_validate({
            "sessionId": s["id"], "targets": [{"sectionId": ids.sections["D"]}],
            "subjects": [{"artisanId": ids.artisans[1], "role": "ANSWERED"}],
        }), ids.lead, client=client)
        assert [x["artisanId"] for x in rec["subjects"]] == [ids.artisans[1]]
        assert ids.artisans[1] in await sessions.participant_ids(client, s["id"]), "a named subject joins the session"
        await client.disconnect()

    run(go())
