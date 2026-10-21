"""Canonical answers, per item (DESIGN 3.6; tests 18-20). Real Postgres."""

from __future__ import annotations

from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

pytestmark = needs_db


def _put(*items):
    from app.questionnaire.schemas import AnswersPut

    return AnswersPut.model_validate({"items": list(items)})


def test_put_answers_is_per_item_and_never_fails_as_a_whole(monkeypatch) -> None:
    from app.questionnaire import answers, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        a, b = ids.artisans[0], ids.artisans[1]
        d1, d2 = ids.questions["D1"], ids.questions["D2"]
        item = lambda artisan, lineage, text, **kw: {"artisanId": artisan, "questionLineageId": lineage, "text": text, "sessionId": s["id"], **kw}  # noqa: E731
        first = await answers.put_answers(_put(item(a, d1, "Chisels"), item(b, d1, "Hammers")), ids.lead)
        assert [r["status"] for r in first["results"]] == ["SAVED", "SAVED"]
        mixed = await answers.put_answers(_put(item(a, d1, "Files"), item(b, d1, "Hammers", baseVersion=1), item(a, d2, "Teak")), ids.lead)
        assert [r["status"] for r in mixed["results"]] == ["CONFLICT", "UNCHANGED", "SAVED"]
        assert mixed["results"][0]["current"]["text"] == "Chisels"
        stale = await answers.put_answers(_put(item(a, d1, "Files", baseVersion=7)), ids.lead)
        assert stale["results"][0]["status"] == "CONFLICT"
        await client.execute_raw('UPDATE "QuestionnaireAnswer" SET "pinned" = true WHERE "artisanId" = $1 AND "questionLineageId" = $2', a, d1)
        pinned = await answers.put_answers(_put(item(a, d1, "Files", baseVersion=1)), ids.lead)
        assert pinned["results"][0]["status"] == "FORBIDDEN"
        prof = await answers.put_answers(_put(item(a, d1, "Files", baseVersion=1)), ids.prof)
        assert prof["results"][0]["status"] == "SAVED" and prof["results"][0]["answer"]["version"] == 2
        revisions = await client.query_raw(
            'SELECT count(*) AS n FROM "QuestionnaireAnswerRevision" rv JOIN "QuestionnaireAnswer" x ON x."id" = rv."answerId" WHERE x."artisanId" = $1 AND x."questionLineageId" = $2',
            a, d1,
        )
        assert int(revisions[0]["n"]) == 2
        await client.disconnect()

    run(go())


def test_prefill_never_overwrites_a_person(monkeypatch) -> None:
    from app.questionnaire import answers, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        a, q = ids.artisans[0], ids.questions["B1"]
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "artisanField" = \'phone\' WHERE "id" = $1', q)
        base = {"artisanId": a, "questionLineageId": q, "sessionId": s["id"]}
        pre = await answers.put_answers(_put({**base, "text": "+91 98765 43210", "origin": "PREFILL"}), ids.lead)
        assert pre["results"][0]["status"] == "SAVED"
        field = await client.query_raw('SELECT "sourceFieldValue" FROM "QuestionnaireAnswerRevision" ORDER BY "createdAt" DESC LIMIT 1')
        assert field[0]["sourceFieldValue"] == "+91 98765 43210"
        typed = await answers.put_answers(_put({**base, "text": "Two numbers", "origin": "TYPED", "baseVersion": 1}), ids.lead)
        assert typed["results"][0]["status"] == "SAVED"
        again = await answers.put_answers(_put({**base, "text": "+91 98765 43210", "origin": "PREFILL", "baseVersion": 2}), ids.lead)
        assert again["results"][0]["status"] == "CONFLICT" and again["results"][0]["current"]["text"] == "Two numbers"
        await client.disconnect()

    run(go())


def test_proposals_for_an_unnamed_group_take_write_drafts_never_answers(monkeypatch) -> None:
    from app.questionnaire import answers, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:3]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D3"]}], at=t(0))
        await client.execute_raw('UPDATE "MediaFile" SET "transcriptStatus" = \'COMPLETED\', "transcriptText" = \'We use teak and chisels.\' WHERE "id" = $1',
                                 take["mediaFileId"])
        out = await answers.proposals(take["id"], ids.lead)
        assert out["proposals"] and out["proposals"][0]["artisanIds"] is None
        assert out["proposals"][0]["origin"] == "TRANSCRIPT"
        count = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireAnswer" WHERE "artisanId" = ANY($1::text[])', ids.artisans[:3])
        assert int(count[0]["n"]) == 0
        drafts = await client.query_raw('SELECT "artisanId" FROM "QuestionnaireTakeDraft" WHERE "recordingId" = $1', take["id"])
        assert [d["artisanId"] for d in drafts] == [""]
        await client.disconnect()

    run(go())
