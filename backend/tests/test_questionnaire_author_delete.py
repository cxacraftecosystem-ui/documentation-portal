"""A questionnaire's author deletes from it, as a content admin does (owner, 22 Oct 2026).

"The author should be able to edit/delete the questionnaire apart from the admins." Against a real,
migrated Postgres (``QV2_TEST_DATABASE_URL``, the Backend database tests job): the form's author
(``Questionnaire.createdById``) and a content admin delete and switch off sections and questions and
retire, delete and restore the form; another questionnaire manager is refused 403 on every one of
those paths; and deleting a session stays a content admin's, the form's author included.
"""

from __future__ import annotations

from typing import Any

import pytest
from fastapi import HTTPException
from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, run, seed, session_create, user


async def _setup(monkeypatch: pytest.MonkeyPatch) -> tuple[Any, Any, Any]:
    client = await connect()
    patch_db(monkeypatch, client)
    no_outside_calls(monkeypatch)
    ids = await seed(client)
    # The professor is the AUTHOR; a second professor, with the same questionnaire powers, is not.
    await client.execute_raw('UPDATE "Questionnaire" SET "createdById" = $1 WHERE "id" = $2', ids.prof.id, ids.q)
    other = f"uprof2{ids.tag}"
    await client.execute_raw(
        'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, \'PROFESSOR\'::"UserRole", now())',
        other, f"{other}@example.test", "Other Professor",
    )
    return client, ids, user("PROFESSOR", other, "Other Professor")


async def _active(client: Any, table: str, row_id: str) -> bool:
    rows = await client.query_raw(f'SELECT "isActive" FROM "{table}" WHERE "id" = $1', row_id)
    return bool(rows[0]["isActive"])


def _refused(work: Any) -> None:
    async def go() -> None:
        with pytest.raises(HTTPException) as refusal:
            await work()
        assert refusal.value.status_code == 403, refusal.value.detail

    return go()


@needs_db
def test_the_author_or_an_admin_deletes_questions_and_sections_and_nobody_else(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireQuestionUpdate, QuestionnaireSectionUpdate

    async def go() -> None:
        client, ids, other = await _setup(monkeypatch)
        try:
            # Another questionnaire manager: refused on every removal path, and nothing changes.
            await _refused(lambda: questionnaire.delete_question(ids.questions["D1"], other))
            await _refused(lambda: questionnaire.delete_section(ids.sections["B"], other))
            await _refused(lambda: questionnaire.update_section(ids.sections["B"], QuestionnaireSectionUpdate(isActive=False), other))
            await _refused(lambda: questionnaire.update_question(ids.questions["D2"], QuestionnaireQuestionUpdate(isActive=False), other))
            assert await _active(client, "QuestionnaireQuestion", ids.questions["D1"])
            assert await _active(client, "QuestionnaireSection", ids.sections["B"])
            # The other manager may still EDIT the form: the amendment widens deletes, it narrows no edit.
            await questionnaire.update_section(ids.sections["F"], QuestionnaireSectionUpdate(title="Renamed by a manager"), other)

            # The author: a question deleted, one switched off through its PATCH, a section taken out.
            await questionnaire.delete_question(ids.questions["D1"], ids.prof)
            assert not await _active(client, "QuestionnaireQuestion", ids.questions["D1"])
            await questionnaire.update_question(ids.questions["D2"], QuestionnaireQuestionUpdate(isActive=False), ids.prof)
            assert not await _active(client, "QuestionnaireQuestion", ids.questions["D2"])
            await questionnaire.update_section(ids.sections["B"], QuestionnaireSectionUpdate(isActive=False), ids.prof)
            assert not await _active(client, "QuestionnaireSection", ids.sections["B"])

            # A content admin, on the same form.
            await questionnaire.delete_question(ids.questions["D3"], ids.admin)
            assert not await _active(client, "QuestionnaireQuestion", ids.questions["D3"])
            await questionnaire.delete_section(ids.sections["F"], ids.admin)
            assert not await _active(client, "QuestionnaireSection", ids.sections["F"])
        finally:
            await client.disconnect()

    run(go())


@needs_db
def test_the_author_or_an_admin_deletes_and_restores_the_form_and_nobody_else(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireUpdate

    async def go() -> None:
        client, ids, other = await _setup(monkeypatch)
        try:
            await _refused(lambda: questionnaire.delete_questionnaire(ids.q, other))
            await _refused(lambda: questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isActive=False), other))
            assert await _active(client, "Questionnaire", ids.q)

            # The author deletes it softly and restores it.
            await questionnaire.delete_questionnaire(ids.q, ids.prof)
            assert not await _active(client, "Questionnaire", ids.q)
            await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isActive=True), ids.prof)
            assert await _active(client, "Questionnaire", ids.q)

            # A content admin retires it through the PATCH and restores it.
            await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isActive=False), ids.admin)
            assert not await _active(client, "Questionnaire", ids.q)
            await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isActive=True), ids.admin)
            assert await _active(client, "Questionnaire", ids.q)
        finally:
            await client.disconnect()

    run(go())


@needs_db
def test_a_form_with_no_recorded_author_is_deleted_from_by_a_content_admin_alone(monkeypatch) -> None:
    from app.api.routes import questionnaire

    async def go() -> None:
        client, ids, _other = await _setup(monkeypatch)
        try:
            await client.execute_raw('UPDATE "Questionnaire" SET "createdById" = NULL WHERE "id" = $1', ids.q)
            await _refused(lambda: questionnaire.delete_question(ids.questions["D1"], ids.prof))
            await questionnaire.delete_question(ids.questions["D1"], ids.admin)
            assert not await _active(client, "QuestionnaireQuestion", ids.questions["D1"])
        finally:
            await client.disconnect()

    run(go())


@needs_db
def test_a_session_on_the_authors_form_is_still_deleted_by_a_content_admin_alone(monkeypatch) -> None:
    from app.services.questionnaire_v2 import sessions

    async def go() -> None:
        client, ids, _other = await _setup(monkeypatch)
        try:
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            await _refused(lambda: sessions.delete_session(s["id"], ids.prof))
            await sessions.delete_session(s["id"], ids.admin)
        finally:
            await client.disconnect()

    run(go())
