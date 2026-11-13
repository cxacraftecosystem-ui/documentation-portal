from __future__ import annotations

import pytest
from fastapi import HTTPException
from qv2_fixtures import (
    connect,
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


def _world(monkeypatch: pytest.MonkeyPatch, body) -> None:
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        try:
            ids = await seed(client)
            session, _ = await sessions.create_session(
                session_create(ids, ids.artisans[:1], place="Bagru, Jaipur", note="First visit"), ids.lead
            )
            await body(client, ids, session)
        finally:
            await client.disconnect()

    run(go())


async def _version(client, session_id: str) -> int:
    rows = await client.query_raw('SELECT "version" FROM "QuestionnaireInterview" WHERE "id" = $1', session_id)
    return int(rows[0]["version"])


def test_a_recording_and_a_participant_leave_the_version_so_an_edit_loaded_before_them_lands(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import sessions
    from app.questionnaire.schemas import ParticipantPatch, SessionPatch

    async def body(client, ids, session) -> None:
        loaded = session["version"]
        await record_take(client, ids, session["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        await sessions.add_participant(session["id"], ids.artisans[1], None, ids.lead)
        await sessions.patch_participant(session["id"], ids.artisans[1], ParticipantPatch(leftAt="2026-06-20T07:00:00Z"), ids.lead)
        assert await _version(client, session["id"]) == loaded
        edited = await sessions.patch_session(session["id"], SessionPatch(version=loaded, place="Sanganer, Jaipur"), ids.lead)
        assert edited["place"] == "Sanganer, Jaipur"
        assert edited["version"] == loaded + 1

    _world(monkeypatch, body)


def test_two_edits_of_the_same_field_from_one_version_refuse_the_second_with_the_field_diff(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import sessions
    from app.questionnaire.schemas import SessionPatch

    async def body(client, ids, session) -> None:
        loaded = session["version"]
        await sessions.patch_session(session["id"], SessionPatch(version=loaded, place="Sanganer, Jaipur"), ids.lead)
        with pytest.raises(HTTPException) as stale:
            await sessions.patch_session(session["id"], SessionPatch(version=loaded, place="Kaladera, Jaipur"), ids.lead)
        assert stale.value.detail["code"] == "STALE_VERSION"
        assert stale.value.detail["diff"] == {"place": {"current": "Sanganer, Jaipur", "yours": "Kaladera, Jaipur"}}
        assert stale.value.detail["current"]["place"] == "Sanganer, Jaipur"

    _world(monkeypatch, body)


def test_a_stale_edit_of_another_field_is_refused_only_on_the_field_it_changes(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import sessions
    from app.questionnaire.schemas import SessionPatch

    async def body(client, ids, session) -> None:
        loaded = session["version"]
        await sessions.patch_session(session["id"], SessionPatch(version=loaded, note="Second visit"), ids.lead)
        with pytest.raises(HTTPException) as stale:
            await sessions.patch_session(session["id"], SessionPatch(version=loaded, place="Kaladera, Jaipur"), ids.lead)
        assert stale.value.detail["code"] == "STALE_VERSION"
        assert stale.value.detail["diff"] == {"place": {"current": "Bagru, Jaipur", "yours": "Kaladera, Jaipur"}}

    _world(monkeypatch, body)


def test_a_stale_edit_whose_every_field_already_matches_is_applied(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.questionnaire import sessions
    from app.questionnaire.schemas import SessionPatch

    async def body(client, ids, session) -> None:
        loaded = session["version"]
        first = await sessions.patch_session(session["id"], SessionPatch(version=loaded, place="Sanganer, Jaipur"), ids.lead)
        again = await sessions.patch_session(
            session["id"], SessionPatch(version=loaded, place="Sanganer, Jaipur", note="First visit"), ids.lead
        )
        assert again["place"] == "Sanganer, Jaipur" and again["note"] == "First visit"
        assert again["version"] == first["version"] + 1

    _world(monkeypatch, body)
