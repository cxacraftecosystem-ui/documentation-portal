from __future__ import annotations

import uuid
from typing import Any

import httpx
import pytest
from fastapi import HTTPException
from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, settings_v2, t

pytestmark = needs_db

_CURRENT: dict[str, Any] = {}


def _app() -> Any:
    from fastapi import FastAPI

    from app.api import router as api_router_module
    from app.core import deps

    app = FastAPI()
    app.include_router(api_router_module.api_router)
    app.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return app


async def _get(http: httpx.AsyncClient, who: Any, path: str) -> Any:
    _CURRENT["user"] = who
    response = await http.get(path)
    assert response.status_code == 200, (path, response.status_code, response.text)
    return response.json()


def _code(exc: pytest.ExceptionInfo) -> str:
    return exc.value.detail["code"]


async def _clip(client: Any, ids: Any, owner: Any, session_id: str | None) -> str:
    media_id = f"clip{uuid.uuid4().hex[:10]}"
    await client.execute_raw(
        'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", '
        '"linkedRecordType", "linkedRecordId", "questionnaireInterviewId", "uploadedById", "updatedAt") '
        "VALUES ($1, 'clip.m4a', 'AUDIO', 'audio/mp4', 5, 'b', $2, 'questionnaire', $3, $3, $4, now())",
        media_id, f"media/{owner.id}/{uuid.uuid4().hex}/clip.m4a", session_id, owner.id,
    )
    return media_id


def test_a_researcher_cannot_file_another_researchers_clip_into_their_own_session(monkeypatch) -> None:
    from app.questionnaire import recordings, sessions
    from app.questionnaire.schemas import FileMediaRequest

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        theirs, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        mine, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.other)
        clip = await _clip(client, ids, ids.lead, theirs["id"])
        with pytest.raises(HTTPException) as refused:
            await recordings.file_media(clip, FileMediaRequest.model_validate(
                {"sessionId": mine["id"], "targets": [{"sectionId": ids.sections["B"]}]}), ids.other, client=client)
        assert _code(refused) == "FORBIDDEN"
        row = await client.query_raw('SELECT "questionnaireInterviewId" AS sid FROM "MediaFile" WHERE "id" = $1', clip)
        assert row[0]["sid"] == theirs["id"], "the clip was pulled into a session its uploader never chose"
        filed = await recordings.file_media(clip, FileMediaRequest.model_validate(
            {"sessionId": theirs["id"], "targets": [{"sectionId": ids.sections["B"]}]}), ids.lead, client=client)
        assert filed["sessionId"] == theirs["id"]
        await client.disconnect()

    run(go())


def test_filing_a_take_into_another_session_is_for_its_uploader_or_an_admin(monkeypatch) -> None:
    from app.questionnaire import recordings, sessions
    from app.questionnaire.schemas import FileRecordingRequest

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        home, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        elsewhere, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2], day="2026-06-22"), ids.other)
        take = await record_take(client, ids, home["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        with pytest.raises(HTTPException) as refused:
            await recordings.file_recording(take["id"], FileRecordingRequest.model_validate(
                {"sessionId": elsewhere["id"], "targets": [{"sectionId": ids.sections["D"]}]}), ids.prof, client=client)
        assert _code(refused) == "FORBIDDEN"
        stayed = await recordings.recording_payload(client, take["id"])
        assert stayed["sessionId"] == home["id"], "a reviewer moved somebody else's take to another session"
        moved = await recordings.file_recording(take["id"], FileRecordingRequest.model_validate(
            {"sessionId": elsewhere["id"], "targets": [{"sectionId": ids.sections["D"]}]}), ids.lead, client=client)
        assert moved["sessionId"] == elsewhere["id"]
        await client.disconnect()

    run(go())


def test_a_take_an_admin_removed_is_restored_only_by_an_admin(monkeypatch) -> None:
    from app.questionnaire import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        await recordings.remove(take["id"], ids.admin, client=client)
        for who in (ids.lead, ids.prof):
            with pytest.raises(HTTPException) as refused:
                await recordings.restore(take["id"], who, client=client)
            assert _code(refused) == "FORBIDDEN"
        still = await recordings.recording_payload(client, take["id"])
        assert still["removedAt"] is not None, "the uploader undid an admin's removal"
        back = await recordings.restore(take["id"], ids.admin, client=client)
        assert back["removedAt"] is None
        await client.disconnect()

    run(go())


def test_uploading_a_take_does_not_make_the_uploader_one_of_the_sessions_researchers(monkeypatch) -> None:
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await record_take(client, ids, s["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        row = await sessions.load_session_row(client, s["id"])
        assert not await sessions.can_work_in(client, ids.other, row), "an upload granted write authority over the session"
        assert ids.other.id not in await sessions.researcher_ids(client, s["id"])
        await client.disconnect()

    run(go())


def test_join_codes_and_client_keys_are_shown_only_to_the_sessions_researchers(monkeypatch) -> None:
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        try:
            patch_db(monkeypatch, client)
            no_outside_calls(monkeypatch)
            settings_v2(monkeypatch, True)
            ids = await seed(client)
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            assert s["joinCode"] and s["clientKey"]
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=_app()), base_url="http://take.test") as http:
                listed = await _get(http, ids.other, f"/api/questionnaire/sessions?questionnaireId={ids.q}")
                row = next(item for item in listed["items"] if item["id"] == s["id"])
                assert (row["joinCode"], row["joinQr"], row["clientKey"]) == (None, None, None), row
                one = await _get(http, ids.other, f"/api/questionnaire/sessions/{s['id']}")
                session = one.get("session", one)
                assert (session["joinCode"], session["joinQr"], session["clientKey"]) == (None, None, None)
                legacy = await _get(http, ids.other, f"/api/questionnaire/interviews/{s['id']}")
                assert legacy.get("joinCode") is None and legacy.get("clientKey") is None
                own = await _get(http, ids.lead, f"/api/questionnaire/sessions/{s['id']}")
                assert own.get("session", own)["joinCode"] == s["joinCode"]
        finally:
            await client.disconnect()

    run(go())
