from __future__ import annotations

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

pytestmark = needs_db


def _presign(session_id: str, clip: str, sha: str | None = None):
    from app.questionnaire.schemas import PresignRequest

    return PresignRequest.model_validate({
        "linkedRecordType": "questionnaire_session", "sessionId": session_id, "clientClipKey": clip, "sizeBytes": 3400000,
        "contentType": "audio/mp4", "extension": "m4a", "contentSha256": sha,
    })


def test_the_review_counts_questions_lists_what_is_left_and_names_takes_waiting_for_a_decision(monkeypatch) -> None:
    from app.questionnaire import answers, recordings, sessions
    from app.questionnaire.schemas import AnswersPut, SessionReview

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        try:
            ids = await seed(client)
            a, b = ids.artisans[0], ids.artisans[1]
            s, _ = await sessions.create_session(session_create(ids, [a, b]), ids.lead)
            empty = await sessions.review(s["id"], ids.lead)
            assert (empty["answered"], empty["total"], empty["sectionsDone"]) == (0, 13, 0)
            assert [x["code"] for x in empty["stillToAsk"]] == ["B", "D", "F"]
            assert empty["stillToAsk"][0]["lineages"] == [ids.questions["B1"], ids.questions["B2"]]
            await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0),
                              subjects=[{"artisanId": a, "role": "ANSWERED"}])
            await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D1"]}], at=t(1),
                              subjects=[{"artisanId": b, "role": "ANSWERED"}])
            waiting = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["F2"]}], at=t(2),
                                        subjects=[{"artisanId": a, "role": "ANSWERED"}])
            await client.execute_raw('UPDATE "QuestionnaireRecording" SET "filing" = \'NEEDS_DECISION\' WHERE "id" = $1', waiting["id"])
            item = {"artisanId": a, "questionLineageId": ids.questions["D2"], "text": "Indigo", "sessionId": s["id"]}
            await answers.put_answers(AnswersPut.model_validate({"items": [item, {**item, "artisanId": b, "questionLineageId": ids.questions["D1"]}]}), ids.lead)
            await recordings.presign(_presign(s["id"], f"k-{ids.tag}"), ids.lead, client=client)
            review = await sessions.review(s["id"], ids.lead)
            SessionReview.model_validate(review)
            assert (review["answered"], review["total"], review["sectionsDone"], review["pendingUploads"]) == (4, 13, 1, 1)
            assert [(x["code"], x["answered"], x["total"]) for x in review["stillToAsk"]] == [("D", 2, 4), ("F", 0, 7)]
            assert review["stillToAsk"][0]["lineages"] == [ids.questions["D3"], ids.questions["D4"]]
            assert ids.questions["F2"] in review["stillToAsk"][1]["lineages"], "a take waiting for a decision answers nothing yet"
            assert review["needsDecision"] == [{"recordingId": waiting["id"], "capturedAt": review["needsDecision"][0]["capturedAt"], "codes": ["F2"]}]
            read = await sessions.session_payload(client, s["id"], ids.lead)
            assert read["progress"]["answeredQuestions"] == review["answered"]
        finally:
            await client.disconnect()

    run(go())


def test_a_whole_session_take_marks_no_section(monkeypatch) -> None:
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        try:
            ids = await seed(client)
            s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
            await record_take(client, ids, s["id"], ids.lead, targets=[], at=t(0))
            review = await sessions.review(s["id"], ids.lead)
            assert (review["answered"], review["sectionsDone"], len(review["stillToAsk"])) == (0, 0, 3)
        finally:
            await client.disconnect()

    run(go())
