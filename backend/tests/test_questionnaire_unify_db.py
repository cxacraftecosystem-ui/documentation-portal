"""The unification's server rules on Field Repository's database (UNIFY U0, owner decisions of 21 Oct
and amendment 2 of 22 Oct 2026). Real Postgres, migrated through 20261107090000; skips without
``QV2_TEST_DATABASE_URL``.

* guest respondents: a session with only a guest saves; their answers are unique per lineage;
  registering the guest moves answers and their place in one transaction, or nothing;
* the one answer-lock rule, guests' answers included;
* restorable soft delete of sessions, content admins only, hidden from every reader;
* typed or recorded coverage, recording preferred, and the "Already recorded" prompt ignoring typed;
* bindings, type defaults and the four-step form resolution, with the Workshop mirror;
* required questions counted on Finish; answer history; recording history.
"""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

pytestmark = needs_db


def _put(*items):
    from app.questionnaire.schemas import AnswersPut

    return AnswersPut.model_validate({"items": list(items)})


async def _world(monkeypatch, **kw):
    client = await connect()
    patch_db(monkeypatch, client)
    no_outside_calls(monkeypatch)
    return client, await seed(client, **kw)


def _code(raised) -> str:
    return raised.value.detail["code"]


# ── guest respondents ─────────────────────────────────────────────────────────────────────────
def test_a_session_with_only_a_guest_saves_and_the_guest_answers(monkeypatch) -> None:
    from app.questionnaire import answers, respondents, sessions

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            s, created = await sessions.create_session(
                session_create(ids, [], respondents=[{"name": "Ravi, a shopper", "kind": "GUEST"}], source="APP"), ids.lead
            )
            assert created and s["participantCount"] == 0 and s["source"] == "APP"
            guest = s["respondents"][0]
            assert guest["name"] == "Ravi, a shopper" and guest["subjectKey"] == f"R:{guest['id']}"
            d1 = ids.questions["D1"]
            item = {"respondentId": guest["id"], "questionLineageId": d1, "text": "Cotton, from Sanganer", "sessionId": s["id"], "note": "asked twice"}
            first = await answers.put_answers(_put(item), ids.lead)
            assert first["results"][0]["status"] == "SAVED"
            again = await answers.put_answers(_put({**item, "text": "Silk"}), ids.lead)
            assert again["results"][0]["status"] == "CONFLICT", "create-only without baseVersion, one answer per lineage"
            rows = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireAnswer" WHERE "respondentId" = $1', guest["id"])
            assert int(rows[0]["n"]) == 1
            note = await client.query_raw(
                'SELECT rv."note" FROM "QuestionnaireAnswerRevision" rv JOIN "QuestionnaireAnswer" a ON a."id" = rv."answerId" WHERE a."respondentId" = $1',
                guest["id"],
            )
            assert note[0]["note"] == "asked twice"
            # A17: retract the name; a guest with an answer is never deleted.
            retracted = await respondents.patch(s["id"], guest["id"], {"name": "", "notes": None}, ids.lead)
            assert retracted["name"] == "Respondent"
            with pytest.raises(HTTPException) as held:
                await respondents.remove(s["id"], guest["id"], ids.lead)
            assert _code(held) == "RESPONDENT_HAS_ROWS"
            with pytest.raises(HTTPException) as stranger:
                await respondents.add(s["id"], [{"name": "X"}], ids.vol)
            assert stranger.value.status_code == 403
        finally:
            await client.disconnect()

    run(go())


def test_registering_a_guest_moves_their_rows_in_one_transaction(monkeypatch) -> None:
    from app.questionnaire import answers, respondents, sessions

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], respondents=[{"name": "Guest"}]), ids.lead)
            guest = s["respondents"][0]["id"]
            d1, d2 = ids.questions["D1"], ids.questions["D2"]
            await answers.put_answers(_put(
                {"respondentId": guest, "questionLineageId": d1, "text": "Indigo", "sessionId": s["id"]},
                {"artisanId": ids.artisans[1], "questionLineageId": d1, "text": "Madder", "sessionId": s["id"]},
            ), ids.lead)
            # The artisan already answered D1 elsewhere: nothing moves.
            other, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.lead)
            await answers.put_answers(_put({"artisanId": ids.artisans[1], "questionLineageId": d1, "text": "Madder", "sessionId": other["id"]}), ids.lead)
            with pytest.raises(HTTPException) as clash:
                await respondents.register(guest, ids.artisans[1], ids.lead)
            assert _code(clash) == "RESPONDENT_HAS_ROWS"
            still = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireAnswer" WHERE "respondentId" = $1', guest)
            assert int(still[0]["n"]) == 1
            # A fresh artisan takes them all.
            await answers.put_answers(_put({"respondentId": guest, "questionLineageId": d2, "text": "Teak", "sessionId": s["id"]}), ids.lead)
            done = await respondents.register(guest, ids.artisans[2], ids.lead)
            assert done["artisanId"] == ids.artisans[2]
            moved = await client.query_raw(
                'SELECT count(*) AS n FROM "QuestionnaireAnswer" WHERE "artisanId" = $1 AND "respondentId" IS NULL', ids.artisans[2]
            )
            assert int(moved[0]["n"]) == 2
            place = await client.query_raw(
                'SELECT count(*) AS n FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1 AND "artisanId" = $2', s["id"], ids.artisans[2]
            )
            assert int(place[0]["n"]) == 1
            assert (await respondents.register(guest, ids.artisans[2], ids.lead))["artisanId"] == ids.artisans[2], "idempotent"
        finally:
            await client.disconnect()

    run(go())


# ── the one answer-lock rule ──────────────────────────────────────────────────────────────────
def test_the_lock_refuses_others_per_item_and_the_guests_answers_too(monkeypatch) -> None:
    from app.questionnaire import answers, sessions

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2], respondents=[{"name": "Guest"}]), ids.lead)
            a, b = ids.artisans[0], ids.artisans[1]
            guest = s["respondents"][0]["id"]
            d1, d2 = ids.questions["D1"], ids.questions["D2"]
            await sessions.add_researcher_route(s["id"], ids.other.id, "CONTRIBUTOR", ids.lead)
            await answers.put_answers(_put(
                {"artisanId": a, "questionLineageId": d1, "text": "Chisels", "sessionId": s["id"]},
                {"respondentId": guest, "questionLineageId": d1, "text": "Looms", "sessionId": s["id"]},
            ), ids.other)
            change = {"artisanId": a, "questionLineageId": d1, "text": "Files", "sessionId": s["id"], "baseVersion": 1}
            out = await answers.put_answers(_put(
                change, {"artisanId": b, "questionLineageId": d2, "text": "Teak", "sessionId": s["id"]},
                {**change, "text": "Chisels"},
                {"respondentId": guest, "questionLineageId": d1, "text": "Spindles", "sessionId": s["id"], "baseVersion": 1},
            ), ids.prof)
            statuses = [(r["status"], r.get("code")) for r in out["results"]]
            assert statuses == [("FORBIDDEN", "ANSWER_LOCKED"), ("SAVED", None), ("UNCHANGED", None), ("FORBIDDEN", "ANSWER_LOCKED")], statuses
            assert out["results"][0]["current"]["text"] == "Chisels"
            lead = await answers.put_answers(_put(change), ids.lead)
            assert lead["results"][0]["status"] == "SAVED", "the session lead may change it"
            admin = await answers.put_answers(_put({**change, "text": "Saws", "baseVersion": 2}), ids.admin)
            assert admin["results"][0]["status"] == "SAVED", "an admin may change it"
            history = await answers.answer_revisions(admin["results"][0]["answer"]["id"])
            assert [r["text"] for r in history["revisions"]] == ["Saws", "Files", "Chisels"]
            assert history["revisions"][0]["current"] and not history["revisions"][1]["current"]
        finally:
            await client.disconnect()

    run(go())


# ── restorable soft delete of sessions ────────────────────────────────────────────────────────
def test_a_session_with_recordings_is_soft_deleted_and_restored_by_a_content_admin(monkeypatch) -> None:
    from app.questionnaire import answers, coverage, sessions

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
            await answers.put_answers(_put({"artisanId": ids.artisans[0], "questionLineageId": ids.questions["B1"], "text": "Yes", "sessionId": s["id"]}), ids.lead)
            before = await coverage.grid(ids.q, artisan_ids=ids.artisans[:1], client=client)
            for who in (ids.lead, ids.prof):
                with pytest.raises(HTTPException) as refused:
                    await sessions.delete_session(s["id"], who)
                assert refused.value.status_code == 403
            await sessions.delete_session(s["id"], ids.admin)
            listed = await sessions.list_sessions(client=client, questionnaireId=ids.q)
            assert s["id"] not in {x["id"] for x in listed["items"]}
            deleted = await sessions.list_sessions(client=client, questionnaireId=ids.q, deleted=True)
            assert s["id"] in {x["id"] for x in deleted["items"]}
            with pytest.raises(HTTPException) as gone:
                await sessions.session_payload(client, s["id"])
            assert _code(gone) == "SESSION_UNKNOWN"
            hidden = await coverage.grid(ids.q, artisan_ids=ids.artisans[:1], client=client)
            assert all(c["state"] == "NOT_RECORDED" for sec in hidden["sections"] for c in sec["cells"]), "its takes and answers are hidden"
            assert await answers.answers_for(client, session_id=s["id"]) == []
            with pytest.raises(HTTPException):
                await sessions.restore_session(s["id"], ids.lead)
            restored = await sessions.restore_session(s["id"], ids.admin)
            assert restored["id"] == s["id"] and restored["deletedAt"] is None
            after = await coverage.grid(ids.q, artisan_ids=ids.artisans[:1], client=client)
            assert after == before, "restore brings everything back"
        finally:
            await client.disconnect()

    run(go())


# ── typed or recorded, either counts; recording preferred (amendment 2, point 4) ──────────────
def test_typed_answers_cover_a_question_and_recordings_are_preferred(monkeypatch) -> None:
    from app.questionnaire import answers, coverage, record, sessions
    from app.questionnaire.schemas import CoverageCheckRequest

    async def go() -> None:
        client, ids = await _world(monkeypatch, sections={"B": 2, "D": 2})
        try:
            a = ids.artisans[0]
            s, _ = await sessions.create_session(session_create(ids, [a]), ids.lead)
            b1, b2, d1 = ids.questions["B1"], ids.questions["B2"], ids.questions["D1"]
            await answers.put_answers(_put(
                {"artisanId": a, "questionLineageId": b1, "text": "Typed one", "sessionId": s["id"]},
                {"artisanId": a, "questionLineageId": b2, "text": "Typed two", "sessionId": s["id"]},
                {"artisanId": a, "questionLineageId": d1, "text": "   ", "sessionId": s["id"]},
            ), ids.lead)
            grid = await coverage.grid(ids.q, artisan_ids=[a], client=client)
            cell = {sec["code"]: sec["cells"][0] for sec in grid["sections"]}
            assert cell["B"]["covered"] == 2 and coverage.section_covered(cell["B"]), "typed answers count"
            assert cell["B"]["source"] == "TYPED" and cell["B"]["addRecording"] and cell["B"]["typedOnlyCount"] == 2
            assert cell["D"]["covered"] == 0 and cell["D"]["source"] is None, "an empty answer covers nothing"
            ask = CoverageCheckRequest.model_validate({"questionnaireId": ids.q, "sessionId": s["id"], "artisanIds": [a],
                                                       "targets": [{"questionLineageId": b1}]})
            check = await coverage.check(ask, ids.lead, client=client)
            per = check["targets"][0]["perArtisan"][0]
            assert per["state"] == "NOT_RECORDED" and per["typed"] is True, "recording over a typed answer asks nothing"
            await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": b1}], at=t(1))
            grid = await coverage.grid(ids.q, artisan_ids=[a], client=client)
            b = next(sec for sec in grid["sections"] if sec["code"] == "B")["cells"][0]
            assert b["source"] == "RECORDED" and not b["addRecording"] and b["recordedCount"] == 1 and b["typedOnlyCount"] == 1
            assert (await coverage.check(ask, ids.lead, client=client))["targets"][0]["perArtisan"][0]["state"] == "RECORDED"
            mine = await record.artisan_record(a, ids.lead, client=client)
            sources = {q["displayCode"]: q["coverageSource"] for sec in mine["instruments"][0]["document"] for q in sec["questions"]}
            assert sources[ids.codes[b1]] == "RECORDED" and sources[ids.codes[b2]] == "TYPED" and sources[ids.codes[d1]] is None
            _name, csv_text = await record.artisan_record_csv(a, ids.lead, client=client)
            assert csv_text.splitlines()[0].endswith(",Covered by") and ",Typed" in csv_text and ",Recorded" in csv_text
        finally:
            await client.disconnect()

    run(go())


def test_finish_counts_required_questions_and_never_blocks(monkeypatch) -> None:
    from app.questionnaire import answers, sessions

    async def go() -> None:
        client, ids = await _world(monkeypatch, sections={"B": 3})
        try:
            await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "isRequired" = true WHERE "questionnaireId" = $1', ids.q)
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1], respondents=[{"name": "Guest"}]), ids.lead)
            guest = s["respondents"][0]["id"]
            await answers.put_answers(_put(
                {"artisanId": ids.artisans[0], "questionLineageId": ids.questions["B1"], "text": "x", "sessionId": s["id"]},
                {"respondentId": guest, "questionLineageId": ids.questions["B2"], "text": "y", "sessionId": s["id"]},
            ), ids.lead)
            await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["B3"]}], at=t(0))
            done = await sessions.finish(s["id"], ids.lead)
            assert done["state"] == "CLOSED", "never blocks"
            assert done["requiredUnanswered"]["bySubject"] == {f"A:{ids.artisans[0]}": 1, f"R:{guest}": 2}
            assert done["requiredUnanswered"]["count"] == 3
        finally:
            await client.disconnect()

    run(go())


# ── bindings, type defaults and the form resolution (amendment 2, point 2) ────────────────────
def test_the_form_resolution_and_the_workshop_mirror(monkeypatch) -> None:
    from app.questionnaire import bindings

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            second = f"q2{ids.tag}"
            await client.execute_raw('INSERT INTO "Questionnaire" ("id", "title", "sortOrder", "kind", "updatedAt") VALUES ($1, $2, 2, \'MARKET_SURVEY\', now())',
                                     second, f"Market {ids.tag}")
            first = await bindings.resolve_for_workshop(workshop_id=ids.workshop, client=client)
            assert first["reason"] in ("GLOBAL_DEFAULT", "PICKER")
            # The old admin route's column binds as primary (the mirror trigger).
            await client.execute_raw('UPDATE "Workshop" SET "questionnaireId" = $1 WHERE "id" = $2', ids.q, ids.workshop)
            out = await bindings.resolve_for_workshop(workshop_id=ids.workshop, client=client)
            assert (out["questionnaireId"], out["reason"]) == (ids.q, "PRIMARY_BINDING")
            with pytest.raises(HTTPException) as refused:
                await bindings.set_bindings(second, [{"workshopId": ids.workshop, "isPrimary": True}], ids.prof)
            assert refused.value.status_code == 403
            await bindings.set_bindings(second, [{"workshopId": ids.workshop, "isPrimary": True}], ids.admin)
            out = await bindings.resolve_for_workshop(workshop_id=ids.workshop, client=client)
            assert (out["questionnaireId"], out["reason"]) == (second, "PRIMARY_BINDING")
            mirror = await client.query_raw('SELECT "questionnaireId" FROM "Workshop" WHERE "id" = $1', ids.workshop)
            assert mirror[0]["questionnaireId"] == second, "the binding keeps the old column in step"
            prim = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireBinding" WHERE "workshopId" = $1 AND "isPrimary"', ids.workshop)
            assert int(prim[0]["n"]) == 1, "one primary per workshop"
            assert (await bindings.resolve_for_workshop(workshop_id=ids.workshop, explicit=ids.q, client=client))["reason"] == "EXPLICIT"
            # Unbound: the workshop type's default comes before the global default.
            # The first form stayed bound, no longer primary: it would be the workshop's only binding.
            assert (await bindings.resolve_for_workshop(workshop_id=ids.workshop, client=client))["reason"] == "PRIMARY_BINDING"
            await bindings.set_bindings(second, [], ids.admin)
            assert (await bindings.resolve_for_workshop(workshop_id=ids.workshop, client=client))["reason"] == "ONLY_BINDING"
            await bindings.set_bindings(ids.q, [], ids.admin)
            await client.execute_raw('UPDATE "Workshop" SET "questionnaireId" = NULL WHERE "id" = $1', ids.workshop)
            await bindings.set_type_default(second, ids.admin, workshop_type="OTHER", client=client)
            out = await bindings.resolve_for_workshop(workshop_id=ids.workshop, client=client)
            assert (out["questionnaireId"], out["reason"]) == (second, "TYPE_DEFAULT")
            with pytest.raises(HTTPException):
                await bindings.set_type_default(ids.q, ids.lead, workshop_type="OTHER", client=client)
            await client.execute_raw('UPDATE "Questionnaire" SET "deletedAt" = now() WHERE "id" = $1', second)
            out = await bindings.resolve_for_workshop(workshop_id=ids.workshop, client=client)
            assert out["questionnaireId"] != second, "a deleted form is never offered"
            await bindings.set_type_default(None, ids.admin, workshop_type="OTHER", client=client)
        finally:
            await client.disconnect()

    run(go())


def test_recording_history_lists_every_event(monkeypatch) -> None:
    from app.questionnaire import recordings, sessions

    async def go() -> None:
        client, ids = await _world(monkeypatch)
        try:
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
            await recordings.remove(take["id"], ids.admin, client=client)
            await recordings.restore(take["id"], ids.admin, client=client)
            history = await recordings.recording_events(take["id"], ids.lead, client=client)
            kinds = [e["kind"] for e in history["events"]]
            assert {"REMOVE", "RESTORE"} <= set(kinds), kinds
            assert all(e["actor"]["id"] == ids.admin.id for e in history["events"] if e["kind"] in ("REMOVE", "RESTORE"))
        finally:
            await client.disconnect()

    run(go())
