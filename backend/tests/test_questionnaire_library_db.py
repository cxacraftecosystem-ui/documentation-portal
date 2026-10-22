"""The forms library on Field Repository (UNIFY U3, section 5.4 tests 3, 4, 5, 8 and 9), against a real,
migrated Postgres (``QV2_TEST_DATABASE_URL``; skips without it).

* scopes: mine, shared, workshop, and deleted for content admins only;
* sharing with everyone is an admin act; the kind is the author's; deleted forms answer FORM_DELETED;
* the soft delete and the content admin's restore;
* reuse copies questions only;
* the handoff bundle round-trips, a repeat import is idempotent, a designer-portal v1 file imports,
  and a newer major version is BUNDLE_VERSION;
* hand-filled workbook answers import as UPLOAD sessions with origin WORKBOOK;
* the annexure groups forms by kind and reads live sessions only.
"""

from __future__ import annotations

import gzip
import json
from typing import Any

import pytest
from fastapi import HTTPException

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, run, seed, session_create

pytestmark = needs_db


async def _world(monkeypatch: pytest.MonkeyPatch) -> tuple[Any, Any]:
    client = await connect()
    patch_db(monkeypatch, client)
    no_outside_calls(monkeypatch)
    ids = await seed(client)
    await client.execute_raw('UPDATE "Questionnaire" SET "createdById" = $1, "ownerId" = $1 WHERE "id" = $2', ids.prof.id, ids.q)
    return client, ids


def _code(raised: Any) -> str:
    detail = raised.value.detail
    return detail.get("code") if isinstance(detail, dict) else str(detail)


def test_scopes_sharing_kind_and_the_deleted_list(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireUpdate
    from app.services import questionnaire_library as lib

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            mine = {f["id"] for f in await lib.list_forms(ids.prof, scope="mine")}
            assert ids.q in mine
            assert ids.q not in {f["id"] for f in await lib.list_forms(ids.lead, scope="mine")}
            assert ids.q not in {f["id"] for f in await lib.list_forms(ids.lead, scope="shared")}

            # Sharing is an admin act; the author is refused it, and so is anyone else.
            with pytest.raises(HTTPException) as refused:
                await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isShared=True), ids.prof)
            assert refused.value.status_code == 403
            shared = await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(isShared=True), ids.admin)
            assert shared["isShared"] is True
            assert ids.q in {f["id"] for f in await lib.list_forms(ids.lead, scope="shared")}

            # The kind is the author's, like the title, and filters the list.
            typed = await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(kind="market_survey"), ids.prof)
            assert typed["kind"] == "MARKET_SURVEY" and typed["kindLabel"] == "Market survey"
            assert ids.q in {f["id"] for f in await lib.list_forms(ids.lead, kind="MARKET_SURVEY")}
            assert ids.q not in {f["id"] for f in await lib.list_forms(ids.lead, kind="none")}
            with pytest.raises(HTTPException) as other:
                await questionnaire.update_questionnaire(ids.q, QuestionnaireUpdate(kind="WORKSHOP_INTERVIEW"), ids.lead)
            assert other.value.status_code == 403

            # The workshop scope reads the bindings.
            await client.execute_raw(
                'INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "workshopId", "isPrimary") VALUES ($1, $2, $3, true)',
                f"b{ids.tag}", ids.q, ids.workshop,
            )
            assert ids.q in {f["id"] for f in await lib.list_forms(ids.lead, scope="workshop", workshop_id=ids.workshop)}

            # The deleted list is a content admin's.
            with pytest.raises(HTTPException) as hidden:
                await lib.list_forms(ids.prof, scope="deleted")
            assert hidden.value.status_code == 403
            listed = await lib.list_forms(ids.admin, scope="workshop", workshop_id=ids.workshop)
            assert listed[0]["can"]["share"] is True and listed[0]["can"]["restore"] is False
        finally:
            await client.disconnect()

    run(go())


def test_the_soft_delete_hides_the_form_and_a_content_admin_restores_it(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.services import questionnaire_library as lib

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            with pytest.raises(HTTPException) as stranger:
                await questionnaire.delete_questionnaire(ids.q, ids.lead)
            assert stranger.value.status_code == 403
            gone = await questionnaire.delete_questionnaire(ids.q, ids.prof)
            assert gone["deletedAt"] and gone["isActive"] is False
            assert ids.q not in {f["id"] for f in await lib.list_forms(ids.prof, scope="mine")}
            assert ids.q in {f["id"] for f in await lib.list_forms(ids.admin, scope="deleted")}
            # The author still opens it (to see it is deleted); anyone else is told it was deleted.
            assert (await questionnaire.get_questionnaire(ids.q, ids.prof))["deletedAt"]
            with pytest.raises(HTTPException) as deleted:
                await questionnaire.get_questionnaire(ids.q, ids.lead)
            assert deleted.value.status_code == 410 and _code(deleted) == "FORM_DELETED"
            with pytest.raises(HTTPException) as reuse:
                await lib.reuse(ids.q, ids.prof)
            assert _code(reuse) == "FORM_DELETED"
            with pytest.raises(HTTPException) as refused:
                await lib.restore(ids.q, ids.prof)
            assert refused.value.status_code == 403
            back = await lib.restore(ids.q, ids.admin)
            assert back["deletedAt"] is None and back["isActive"] is True
            assert ids.q in {f["id"] for f in await lib.list_forms(ids.prof, scope="mine")}
        finally:
            await client.disconnect()

    run(go())


def test_reuse_copies_the_questions_and_nothing_else(monkeypatch) -> None:
    from app.questionnaire import sessions
    from app.services import questionnaire_library as lib

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            await client.execute_raw('UPDATE "Questionnaire" SET "isShared" = true, "kind" = $2 WHERE "id" = $1', ids.q, "WORKSHOP_INTERVIEW")
            with pytest.raises(HTTPException) as researcher:
                await lib.reuse(ids.q, ids.lead)
            assert researcher.value.status_code == 403
            copy = await lib.reuse(ids.q, ids.prof, title=None)
            assert copy["id"] != ids.q and copy["title"].endswith("(copy)")
            assert copy["sectionCount"] == 3 and copy["questionCount"] == 13
            assert copy["isShared"] is False and copy["isDefault"] is False and copy["kind"] == "WORKSHOP_INTERVIEW"
            assert copy["ownerId"] == ids.prof.id
            sittings = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireInterview" WHERE "questionnaireId" = $1', copy["id"])
            assert int(sittings[0]["n"]) == 0
            again = await lib.reuse(ids.q, ids.prof, kind=None)
            assert again["title"].endswith("(copy) (2)") and again["kind"] is None
        finally:
            await client.disconnect()

    run(go())


def test_the_bundle_round_trips_and_a_repeat_import_is_idempotent(monkeypatch) -> None:
    from app.services import questionnaire_library as lib

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            document = await lib.bundle_document(ids.q)
            assert document["v"] == 2 and len(document["bundleId"]) == 32
            assert "id" not in json.dumps(document["form"]) and len(document["questions"]) == 13
            raw = lib.bundle_bytes(document)
            assert raw[:2] == b"\x1f\x8b"
            assert lib.bundle_filename(document["form"]["title"]).endswith("_Handoff.frwq")
            first, created = await lib.import_bundle(lib.read_bundle_bytes(raw), ids.prof)
            assert created and first["questionCount"] == 13 and first["sectionCount"] == 3
            second, created_again = await lib.import_bundle(lib.read_bundle_bytes(raw), ids.prof)
            assert not created_again and second["id"] == first["id"]
            # Another researcher importing the same file gets a copy of their own.
            other, other_created = await lib.import_bundle(lib.read_bundle_bytes(raw), ids.admin)
            assert other_created and other["id"] != first["id"] and other["ownerId"] == ids.admin.id

            # designer-portal Android's v1 file.
            v1 = {"format": "designer-portal-questionnaire", "schemaVersion": 1, "title": f"Toolkit {ids.tag}", "description": None,
                  "sections": [{"code": "A", "title": "About", "sortOrder": 1,
                                "questions": [{"prompt": "How long?", "isRequired": True, "sortOrder": 1}]}]}
            dpwq, made = await lib.import_bundle(lib.read_bundle_bytes(gzip.compress(json.dumps(v1).encode())), ids.prof)
            assert made and dpwq["questionCount"] == 1

            newer = {**document, "v": 3}
            with pytest.raises(HTTPException) as refused:
                await lib.import_bundle(newer, ids.prof)
            assert refused.value.status_code == 422 and _code(refused) == "BUNDLE_VERSION"
            with pytest.raises(HTTPException) as junk:
                lib.read_bundle_bytes(b"not a bundle")
            assert _code(junk) == "VALIDATION_FAILED"
        finally:
            await client.disconnect()

    run(go())


def test_hand_filled_workbook_answers_import_as_upload_sessions(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.services import questionnaire_library as lib
    from app.services.questionnaire_xlsx import ParsedQuestion, ParsedQuestionnaire, ParsedSection

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            parsed = ParsedQuestionnaire(
                sections=[
                    ParsedSection("A", "About", [
                        ParsedQuestion("How long have you practised?", 2, answers={"Ramesh": "Forty years", "Sita": "Ten"},
                                       answerNotes={"Ramesh": "Said through his son"}),
                        ParsedQuestion("Who taught you?", 3, answers={"Ramesh": "My father"}),
                    ]),
                ],
                title=f"Market survey {ids.tag}",
                entryLabels=["Ramesh", "Sita"],
            )
            qid, report = await questionnaire.create_from_parsed(parsed, created_by_id=ids.prof.id, answers_imported=True)
            assert report["answersSkipped"] == 0 and report["provenance"] is None
            result = await lib.import_workbook_answers(qid, parsed, ids.prof, workshop_id=ids.workshop)
            assert result == {"sessions": 2, "answers": 3}
            rows = await client.query_raw(
                'SELECT i."source"::text AS source, r."name", rv."origin"::text AS origin, rv."note" '
                'FROM "QuestionnaireAnswerRevision" rv JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" '
                'JOIN "QuestionnaireAnswer" a ON a."id" = rv."answerId" JOIN "QuestionnaireRespondent" r ON r."id" = a."respondentId" '
                'WHERE i."questionnaireId" = $1 ORDER BY r."name", rv."text"',
                qid,
            )
            assert {r["source"] for r in rows} == {"UPLOAD"} and {r["origin"] for r in rows} == {"WORKBOOK"}
            assert sorted({r["name"] for r in rows}) == ["Ramesh", "Sita"]
            assert "Said through his son" in {r["note"] for r in rows}
            again = await lib.import_workbook_answers(qid, parsed, ids.prof, workshop_id=ids.workshop)
            assert again["sessions"] == 2 and again["answers"] == 0, "the same file twice lands once"
        finally:
            await client.disconnect()

    run(go())


def test_the_annexure_groups_by_kind_and_reads_live_sessions_only(monkeypatch) -> None:
    from app.questionnaire import answers, sessions
    from app.questionnaire.schemas import AnswersPut
    from app.services import questionnaire_library as lib

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            await client.execute_raw('UPDATE "Questionnaire" SET "kind" = $2 WHERE "id" = $1', ids.q, "WORKSHOP_INTERVIEW")
            survey = await lib.reuse(ids.q, ids.prof, title=f"Survey {ids.tag}", kind="MARKET_SURVEY")
            live, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            gone, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.lead)
            d1 = ids.questions["D1"]
            await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": ids.artisans[0], "questionLineageId": d1, "text": "Indigo from Bagru", "sessionId": live["id"]},
                {"artisanId": ids.artisans[1], "questionLineageId": d1, "text": "Madder", "sessionId": gone["id"]},
            ]}), ids.lead)
            await sessions.delete_session(gone["id"], ids.admin)
            # The survey is bound to the workshop but has no sessions there: it is printed, empty.
            await client.execute_raw(
                'INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "workshopId", "isPrimary") VALUES ($1, $2, $3, false)',
                f"bs{ids.tag}", survey["id"], ids.workshop,
            )
            doc = await lib.annexure(ids.workshop)
            assert [g["kind"] for g in doc["groups"]] == ["WORKSHOP_INTERVIEW", "MARKET_SURVEY"]
            interview = doc["groups"][0]["forms"][0]
            assert interview["id"] == ids.q and interview["sessionCount"] == 1
            d = next(s for s in interview["sections"] if s["code"] == "D")
            first = d["questions"][0]
            assert [a["text"] for a in first["answers"]] == ["Indigo from Bagru"], "a deleted session's answer is not printed"
            text = lib.annexure_text(doc)
            assert "== Workshop interview ==" in text and "== Market survey ==" in text and "Madder" not in text
            assert lib.annexure_filename("Bagru workshop").endswith("_Annexure.txt")
        finally:
            await client.disconnect()

    run(go())
