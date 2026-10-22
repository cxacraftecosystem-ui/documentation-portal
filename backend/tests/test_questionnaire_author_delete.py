"""A questionnaire's author edits and deletes from it, as a content admin does (owner, 22 Oct 2026).

"The author should be able to edit/delete the questionnaire apart from the admins", applied strictly.
Against a real, migrated Postgres (``QV2_TEST_DATABASE_URL``, the Backend database tests job): the
form's author (``Questionnaire.createdById``) and a content admin rename the form, add, edit and
reorder its sections and questions, delete and switch off sections and questions, and retire, delete
and restore the form; another questionnaire manager is refused 403 on every one of those paths; and
deleting a session stays a content admin's, the form's author included.
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
            # Nor may the other manager EDIT it: editing is the author's or a content admin's too.
            await _refused(lambda: questionnaire.update_section(ids.sections["F"], QuestionnaireSectionUpdate(title="Renamed by a manager"), other))

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
            # The instrument names its author, which is what the clients draw the controls from.
            assert (await questionnaire.get_questionnaire(ids.q, other))["createdById"] == ids.prof.id
            listed = {row["id"]: row for row in await questionnaire.list_questionnaires(other, activeOnly=False)}
            assert listed[ids.q]["createdById"] == ids.prof.id
            await _refused(lambda: questionnaire.delete_questionnaire(ids.q, other))
            await _refused(lambda: questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isActive=False), other))
            assert await _active(client, "Questionnaire", ids.q)

            # The author deletes it softly; restoring a DELETED form is a content admin's (UNIFY U8).
            await questionnaire.delete_questionnaire(ids.q, ids.prof)
            assert not await _active(client, "Questionnaire", ids.q)
            gone = await client.query_raw('SELECT "deletedAt", "deletedById" FROM "Questionnaire" WHERE "id" = $1', ids.q)
            assert gone[0]["deletedAt"] is not None and gone[0]["deletedById"] == ids.prof.id
            await _refused(lambda: questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isActive=True), ids.prof))
            await _refused(lambda: questionnaire.restore_questionnaire(ids.q, ids.prof))
            back = await questionnaire.restore_questionnaire(ids.q, ids.admin)
            assert back["deletedAt"] is None and back["isActive"] is True
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
def test_the_author_or_an_admin_edits_the_form_and_another_manager_is_refused(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import (
        QuestionnaireQuestionCreate,
        QuestionnaireQuestionReorder,
        QuestionnaireQuestionUpdate,
        QuestionnaireSectionCreate,
        QuestionnaireSectionReorder,
        QuestionnaireSectionUpdate,
        QuestionnaireUpdate,
    )

    async def go() -> None:
        client, ids, other = await _setup(monkeypatch)

        async def title(table: str, row_id: str, column: str) -> str:
            rows = await client.query_raw(f'SELECT "{column}" AS v FROM "{table}" WHERE "id" = $1', row_id)
            return str(rows[0]["v"])

        try:
            section_order = [r["id"] for r in await client.query_raw(
                'SELECT "id" FROM "QuestionnaireSection" WHERE "questionnaireId" = $1 ORDER BY "sortOrder"', ids.q
            )]
            d_questions = [r["id"] for r in await client.query_raw(
                'SELECT "id" FROM "QuestionnaireQuestion" WHERE "sectionId" = $1 AND "isActive" ORDER BY "sortOrder"',
                ids.sections["D"],
            )]

            # Another questionnaire manager: refused on every edit path, and nothing changes.
            await _refused(lambda: questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(title="Taken over"), other))
            await _refused(lambda: questionnaire.create_section(
                QuestionnaireSectionCreate(questionnaireId=ids.q, code="Z", title="Not theirs"), other))
            await _refused(lambda: questionnaire.update_section(ids.sections["B"], QuestionnaireSectionUpdate(title="Not theirs"), other))
            await _refused(lambda: questionnaire.reorder_sections(QuestionnaireSectionReorder(sectionIds=list(reversed(section_order))), other))
            await _refused(lambda: questionnaire.create_question(
                QuestionnaireQuestionCreate(sectionId=ids.sections["D"], prompt="Not theirs?"), other))
            await _refused(lambda: questionnaire.update_question(ids.questions["D3"], QuestionnaireQuestionUpdate(helpText="Not theirs"), other))
            await _refused(lambda: questionnaire.reorder_questions(
                QuestionnaireQuestionReorder(sectionId=ids.sections["D"], questionIds=list(reversed(d_questions))), other))
            assert (await title("Questionnaire", ids.q, "title")).lower() != "taken over"
            assert await client.query_raw(
                'SELECT 1 FROM "QuestionnaireSection" WHERE "questionnaireId" = $1 AND "code" = $2', ids.q, "Z"
            ) == []

            # The author: renames the form, adds a section and a question, edits and reorders them.
            await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(title=f"Renamed by its author {ids.tag}"), ids.prof)
            assert (await title("Questionnaire", ids.q, "title")).lower() == f"renamed by its author {ids.tag}".lower()
            made = await questionnaire.create_section(QuestionnaireSectionCreate(questionnaireId=ids.q, code="Z", title="Added"), ids.prof)
            await questionnaire.update_section(made["id"], QuestionnaireSectionUpdate(title="Added and renamed"), ids.prof)
            assert (await title("QuestionnaireSection", made["id"], "title")).lower() == "added and renamed"
            asked = await questionnaire.create_question(QuestionnaireQuestionCreate(sectionId=made["id"], prompt="Who taught you?"), ids.prof)
            await questionnaire.update_question(asked["id"], QuestionnaireQuestionUpdate(helpText="Name and village."), ids.prof)
            assert await title("QuestionnaireQuestion", asked["id"], "helpText") == "Name and village."
            await questionnaire.reorder_questions(
                QuestionnaireQuestionReorder(sectionId=ids.sections["D"], questionIds=list(reversed(d_questions))), ids.prof)
            await questionnaire.reorder_sections(QuestionnaireSectionReorder(sectionIds=list(reversed(section_order))), ids.prof)

            # A content admin, on somebody else's form.
            await questionnaire.update_section(made["id"], QuestionnaireSectionUpdate(title="Renamed by an admin"), ids.admin)
            assert (await title("QuestionnaireSection", made["id"], "title")).lower() == "renamed by an admin"
            await questionnaire.update_question(asked["id"], QuestionnaireQuestionUpdate(helpText="Edited by an admin."), ids.admin)
            await questionnaire.reorder_sections(QuestionnaireSectionReorder(sectionIds=section_order + [made["id"]]), ids.admin)
        finally:
            await client.disconnect()

    run(go())


@needs_db
def test_a_form_with_no_recorded_author_is_edited_by_a_content_admin_alone(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireSectionUpdate, QuestionnaireUpdate

    async def go() -> None:
        client, ids, _other = await _setup(monkeypatch)
        try:
            await client.execute_raw('UPDATE "Questionnaire" SET "createdById" = NULL WHERE "id" = $1', ids.q)
            await _refused(lambda: questionnaire.update_section(ids.sections["B"], QuestionnaireSectionUpdate(title="By the professor"), ids.prof))
            await _refused(lambda: questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(title="By the professor"), ids.prof))
            await questionnaire.update_section(ids.sections["B"], QuestionnaireSectionUpdate(title="By an admin"), ids.admin)
            rows = await client.query_raw('SELECT "title" FROM "QuestionnaireSection" WHERE "id" = $1', ids.sections["B"])
            assert rows[0]["title"].lower() == "by an admin"
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
    from app.questionnaire import sessions

    async def go() -> None:
        client, ids, _other = await _setup(monkeypatch)
        try:
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            await _refused(lambda: sessions.delete_session(s["id"], ids.prof))
            await sessions.delete_session(s["id"], ids.admin)
        finally:
            await client.disconnect()

    run(go())
