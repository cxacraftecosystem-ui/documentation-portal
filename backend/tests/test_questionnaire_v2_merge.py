"""Session merge and artisan merge carry every v2 row (DESIGN 3.7, 3.8; tests 24 and 25)."""

from __future__ import annotations

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

pytestmark = needs_db


def test_a_session_merge_moves_everything_and_leaves_each_artisans_takes_unchanged(monkeypatch) -> None:
    from app.questionnaire.schemas import AnswersPut
    from app.questionnaire import answers, coverage, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        a, b = ids.artisans[0], ids.artisans[1]
        one, _ = await sessions.create_session(session_create(ids, [a]), ids.lead)
        two, _ = await sessions.create_session(session_create(ids, [b]), ids.other)
        await record_take(client, ids, one["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0))
        await record_take(client, ids, two["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(1))
        await answers.put_answers(AnswersPut.model_validate({"items": [
            {"artisanId": b, "questionLineageId": ids.questions["D1"], "text": "Clay", "sessionId": two["id"]}]}), ids.other)
        before = {x: sorted((r["recordingId"], r["targetKey"], r["position"]) for r in await coverage.take_rows(client, ids.q, artisan_ids=[x])) for x in (a, b)}
        merged = await sessions.merge_into(two["id"], one["id"], ids.admin)
        assert merged["participantCount"] == 2 and merged["counts"]["takes"] == 2
        assert {r["userId"] for r in merged["researchers"]} == {ids.lead.id, ids.other.id}
        after = {x: sorted((r["recordingId"], r["targetKey"], r["position"]) for r in await coverage.take_rows(client, ids.q, artisan_ids=[x])) for x in (a, b)}
        assert after == before
        moved = await client.query_raw('SELECT DISTINCT "sessionId" FROM "QuestionnaireAnswerRevision" rv JOIN "QuestionnaireAnswer" x ON x."id" = rv."answerId" WHERE x."artisanId" = $1', b)
        assert [r["sessionId"] for r in moved] == [one["id"]]
        gone = await client.query_raw('SELECT "mergedIntoId" FROM "QuestionnaireInterview" WHERE "id" = $1', two["id"])
        assert gone[0]["mergedIntoId"] == one["id"]
        await client.disconnect()

    run(go())


def test_an_artisan_merge_moves_subjects_answers_and_consent_with_the_clash_rule(monkeypatch) -> None:
    from app.questionnaire.schemas import AnswersPut
    from app.services import consent_gate
    from app.questionnaire import answers, sessions
    from app.questionnaire.merge import merge_artisan_rows

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        keep, dupe = ids.artisans[0], ids.artisans[1]
        s, _ = await sessions.create_session(session_create(ids, [keep, dupe]), ids.lead)
        both = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0),
                                 subjects=[{"artisanId": keep, "role": "PRESENT"}, {"artisanId": dupe, "role": "ANSWERED"}])
        only = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(1),
                                 subjects=[{"artisanId": dupe, "role": "ANSWERED"}])
        put = lambda a, q, text: {"artisanId": a, "questionLineageId": q, "text": text, "sessionId": s["id"]}  # noqa: E731
        await answers.put_answers(AnswersPut.model_validate({"items": [put(keep, ids.questions["D1"], "older"), put(dupe, ids.questions["D2"], "only dupe")]}), ids.lead)
        await answers.put_answers(AnswersPut.model_validate({"items": [put(dupe, ids.questions["D1"], "newer")]}), ids.lead)
        await consent_gate.record_artisan_consent(dupe, state="GRANTED", basis="VERBAL_RECORDED", actor_id=ids.lead.id, client=client)
        counts = await merge_artisan_rows(keep, dupe, client=client)
        assert counts["answerClashes"] == 1
        subjects = await client.query_raw(
            'SELECT "recordingId", "role"::text AS role FROM "QuestionnaireRecordingSubject" WHERE "artisanId" = $1 ORDER BY 1', keep)
        assert {(r["recordingId"], r["role"]) for r in subjects} == {(both["id"], "ANSWERED"), (only["id"], "ANSWERED")}
        left = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireRecordingSubject" WHERE "artisanId" = $1', dupe)
        assert int(left[0]["n"]) == 0
        d1 = await answers.current_answer(client, keep, ids.questions["D1"])
        assert d1["text"] == "newer", "no pin: the most recent wins"
        history = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireAnswerRevision" WHERE "answerId" = $1', d1["id"])
        assert int(history[0]["n"]) == 2, "the losing answer became a revision"
        assert (await answers.current_answer(client, keep, ids.questions["D2"]))["text"] == "only dupe"
        consent = await consent_gate.current_states("ARTISAN", [keep], client=client)
        assert consent[keep]["state"] == "GRANTED"
        await client.disconnect()

    run(go())
