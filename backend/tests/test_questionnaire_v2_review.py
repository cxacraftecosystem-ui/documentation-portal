"""Approving a session (DESIGN 3.10; test 21). Real Postgres."""

from __future__ import annotations

import pytest
from fastapi import HTTPException
from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, settings_v2, t

pytestmark = needs_db


def test_approval_is_refused_for_no_people_no_content_or_an_undecided_take_and_cascades_otherwise(monkeypatch) -> None:
    from app.api.routes import review
    from app.schemas.common import ReviewAction
    from app.questionnaire.schemas import AnswersPut, DecideRequest
    from app.services import email_outbox
    from app.questionnaire import answers, recordings, sessions

    async def no_mail(**kwargs):
        return 0

    monkeypatch.setattr(email_outbox, "notify_review_decision", no_mail)
    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        with pytest.raises(HTTPException) as empty:
            await review.set_review_status("questionnaire", s["id"], "APPROVED", ReviewAction(notes=None), ids.admin, notify=False)
        assert empty.value.detail["code"] == "APPROVE_BLOCKED"
        assert [r["code"] for r in empty.value.detail["reasons"]] == ["NO_CONTENT"]
        one = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        two = await record_take(client, ids, s["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(0.5))
        assert two["filing"] == "NEEDS_DECISION"
        with pytest.raises(HTTPException) as undecided:
            await review.set_review_status("questionnaire", s["id"], "APPROVED", ReviewAction(notes=None), ids.admin, notify=False)
        assert undecided.value.detail["message"] == "Decide on 1 take first."
        await recordings.decide(two["id"], DecideRequest(action="KEEP_BOTH", otherRecordingId=one["id"]), ids.admin, client=client)
        removed = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(1))
        await recordings.remove(removed["id"], ids.admin, client=client)
        await answers.put_answers(AnswersPut.model_validate({"items": [
            {"artisanId": ids.artisans[0], "questionLineageId": ids.questions["B1"], "text": "Teak", "sessionId": s["id"]}]}), ids.lead)
        await review.set_review_status("questionnaire", s["id"], "APPROVED", ReviewAction(notes=None), ids.admin, notify=False)
        statuses = await client.query_raw(
            'SELECT "id", "status"::text AS s FROM "MediaFile" WHERE "id" = ANY($1::text[])',
            [one["mediaFileId"], two["mediaFileId"], removed["mediaFileId"]],
        )
        by = {r["id"]: r["s"] for r in statuses}
        assert by[one["mediaFileId"]] == "APPROVED" and by[two["mediaFileId"]] == "APPROVED"
        assert by[removed["mediaFileId"]] == "PENDING", "a removed take never rides the approval"
        rev = await client.query_raw('SELECT DISTINCT "status"::text AS s FROM "QuestionnaireAnswerRevision" WHERE "sessionId" = $1', s["id"])
        assert [r["s"] for r in rev] == ["APPROVED"]
        late = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["F"]}], at=t(2))
        late_status = await client.query_raw('SELECT "status"::text AS s, "extraMetadata" FROM "MediaFile" WHERE "id" = $1', late["mediaFileId"])
        assert late_status[0]["s"] == "PENDING"
        session_status = await client.query_raw('SELECT "status"::text AS s FROM "QuestionnaireInterview" WHERE "id" = $1', s["id"])
        assert session_status[0]["s"] == "APPROVED"
        nobody, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.lead)
        await client.execute_raw('DELETE FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', nobody["id"])
        with pytest.raises(HTTPException) as people:
            await review.set_review_status("questionnaire", nobody["id"], "APPROVED", ReviewAction(notes=None), ids.admin, notify=False)
        assert "NO_PARTICIPANTS" in [r["code"] for r in people.value.detail["reasons"]]
        await client.disconnect()

    run(go())
