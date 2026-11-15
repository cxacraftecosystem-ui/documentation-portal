from __future__ import annotations

import uuid
from typing import Any

import pytest
from fastapi import HTTPException
from qv2_fixtures import (
    connect,
    needs_db,
    no_outside_calls,
    patch_db,
    run,
    seed,
    session_create,
    settings_v2,
)

pytestmark = needs_db


async def _grant(client: Any, ids: Any, who: Any, level: str) -> None:
    await client.execute_raw(
        'INSERT INTO "WorkshopAssignment" ("id", "workshopId", "userId", "accessLevel", "status", "updatedAt") '
        "VALUES ($1, $2, $3, $4, 'GRANTED', now())",
        f"wa{uuid.uuid4().hex[:12]}", ids.workshop, who.id, level,
    )


async def _admins_interview(client: Any, monkeypatch: Any) -> tuple[Any, dict[str, Any]]:
    from app.questionnaire import sessions

    patch_db(monkeypatch, client)
    no_outside_calls(monkeypatch)
    ids = await seed(client)
    session, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.admin)
    return ids, session


def _update(body: dict[str, Any]) -> Any:
    from app.schemas.questionnaire import QuestionnaireInterviewUpdate

    return QuestionnaireInterviewUpdate.model_validate(body)


async def _linked(client: Any, session_id: str) -> set[str]:
    rows = await client.query_raw(
        'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', session_id
    )
    return {str(r["artisanId"]) for r in rows}


@pytest.mark.parametrize("v2", [True, False])
def test_a_contributor_adds_an_answer_and_an_artisan_to_an_admins_interview(monkeypatch, v2) -> None:
    from app.api.routes import questionnaire
    from app.questionnaire import answers

    settings_v2(monkeypatch, v2)

    async def go() -> None:
        client = await connect()
        try:
            ids, session = await _admins_interview(client, monkeypatch)
            await _grant(client, ids, ids.other, "CONTRIBUTE")
            a0, a1 = ids.artisans[0], ids.artisans[1]
            await questionnaire.update_interview(
                session["id"],
                _update({"artisanIds": [a0, a1], "responses": [{"questionId": ids.questions["D2"], "answerText": "Sheesham"}]}),
                ids.other,
            )
            assert await _linked(client, session["id"]) == {a0, a1}
            if v2:
                written = await answers.current_answer(client, a0, ids.questions["D2"])
                assert written and written["text"] == "Sheesham"
            else:
                rows = await client.query_raw(
                    'SELECT "answerText" FROM "QuestionnaireResponse" WHERE "interviewId" = $1 AND "questionId" = $2',
                    session["id"], ids.questions["D2"],
                )
                assert rows and rows[0]["answerText"] == "Sheesham"
        finally:
            await client.disconnect()

    run(go())


def test_a_refused_v2_answer_is_an_error_and_not_a_silent_loss(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.questionnaire import answers
    from app.questionnaire.schemas import AnswersPut

    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        try:
            ids, session = await _admins_interview(client, monkeypatch)
            await _grant(client, ids, ids.other, "CONTRIBUTE")
            a0 = ids.artisans[0]
            await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": a0, "questionLineageId": ids.questions["D1"], "text": "Teak", "sessionId": session["id"]}]}), ids.admin)
            with pytest.raises(HTTPException) as refused:
                await questionnaire.update_interview(
                    session["id"],
                    _update({"responses": [{"questionId": ids.questions["D1"], "answerText": "Rosewood"}]}),
                    ids.other,
                )
            assert refused.value.status_code == 403
            assert refused.value.detail["refused"] == 1
            assert "1 answer was not saved" in refused.value.detail["message"]
            kept = await answers.current_answer(client, a0, ids.questions["D1"])
            assert kept["text"] == "Teak"
            await questionnaire.update_interview(
                session["id"], _update({"responses": [{"questionId": ids.questions["D1"], "answerText": "Teak"}]}), ids.other
            )
        finally:
            await client.disconnect()

    run(go())


@pytest.mark.parametrize("v2", [True, False])
def test_view_access_removes_nothing_even_when_the_request_is_refused(monkeypatch, v2) -> None:
    from app.api.routes import questionnaire

    settings_v2(monkeypatch, v2)

    async def go() -> None:
        client = await connect()
        try:
            ids, session = await _admins_interview(client, monkeypatch)
            await _grant(client, ids, ids.other, "VIEW")
            before = await _linked(client, session["id"])
            with pytest.raises(HTTPException) as refused:
                await questionnaire.update_interview(session["id"], _update({"artisanIds": [], "title": "x"}), ids.other)
            assert refused.value.status_code == 403
            assert await _linked(client, session["id"]) == before
        finally:
            await client.disconnect()

    run(go())


def test_workshop_edit_access_works_in_a_v2_session_and_contribute_only_adds(monkeypatch) -> None:
    from app.questionnaire import answers, sessions
    from app.questionnaire.schemas import AnswersPut

    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        try:
            ids, session = await _admins_interview(client, monkeypatch)
            row = await sessions.load_session_row(client, session["id"])
            assert await sessions.can_work_in(client, ids.other, row) is False
            await _grant(client, ids, ids.other, "EDIT")
            assert await sessions.can_work_in(client, ids.other, row) is True
            await client.execute_raw('UPDATE "WorkshopAssignment" SET "accessLevel" = \'CONTRIBUTE\' WHERE "userId" = $1', ids.other.id)
            assert await sessions.can_work_in(client, ids.other, row) is False
            assert await sessions.can_add_answers_in(client, ids.other, row) is True
            a0 = ids.artisans[0]
            first = await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": a0, "questionLineageId": ids.questions["D3"], "text": "Brass", "sessionId": session["id"]}]}), ids.other)
            assert first["results"][0]["status"] not in ("FORBIDDEN", "CONFLICT")
            await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": a0, "questionLineageId": ids.questions["D4"], "text": "Iron", "sessionId": session["id"]}]}), ids.admin)
            current = await answers.current_answer(client, a0, ids.questions["D4"])
            over = await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": a0, "questionLineageId": ids.questions["D4"], "text": "Copper", "sessionId": session["id"],
                 "baseVersion": int(current["version"])}]}), ids.other)
            assert over["results"][0]["status"] == "FORBIDDEN"
        finally:
            await client.disconnect()

    run(go())


@pytest.mark.parametrize("level", ["CONTRIBUTE", "EDIT"])
def test_legacy_answers_resend_unchanged_and_workshop_edit_may_change_them(monkeypatch, level) -> None:
    from app.api.routes import questionnaire

    settings_v2(monkeypatch, False)

    async def go() -> None:
        client = await connect()
        try:
            ids, session = await _admins_interview(client, monkeypatch)
            await _grant(client, ids, ids.other, level)
            qid = ids.questions["D1"]
            await questionnaire.update_interview(
                session["id"], _update({"responses": [{"questionId": qid, "answerText": "Teak"}]}), ids.admin
            )
            await questionnaire.update_interview(
                session["id"], _update({"responses": [{"questionId": qid, "answerText": "Teak"}]}), ids.other
            )
            change = _update({"responses": [{"questionId": qid, "answerText": "Rosewood"}]})
            if level == "EDIT":
                await questionnaire.update_interview(session["id"], change, ids.other)
            else:
                with pytest.raises(HTTPException) as refused:
                    await questionnaire.update_interview(session["id"], change, ids.other)
                assert refused.value.status_code == 403
            rows = await client.query_raw(
                'SELECT "answerText" FROM "QuestionnaireResponse" WHERE "interviewId" = $1 AND "questionId" = $2', session["id"], qid
            )
            assert rows[0]["answerText"] == ("Rosewood" if level == "EDIT" else "Teak")
        finally:
            await client.disconnect()

    run(go())
