"""Changing takes, the sha worker and the corpus feed (DESIGN 3.3.4, 3.8, 5.3, 5.4; tests 30 and 35)."""

from __future__ import annotations

import hashlib
import json

import pytest
from fastapi import HTTPException
from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, record_take, run, seed, session_create, t

pytestmark = needs_db


def test_patch_decide_replace_remove_restore_are_logged_and_never_refuse_on_coverage(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import DecideRequest, RecordingPatch
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        moved = await recordings.patch(take["id"], RecordingPatch(
            version=take["version"], targets=[{"questionLineageId": ids.questions["D2"]}],
            subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED"}, {"artisanId": ids.artisans[1], "role": "PRESENT"}],
        ), ids.lead, client=client)
        assert [x["targetKey"] for x in moved["targets"]] == [f"Q:{ids.questions['D2']}"]
        assert {x["artisanId"]: x["role"] for x in moved["subjects"]} == {ids.artisans[0]: "ANSWERED", ids.artisans[1]: "PRESENT"}
        with pytest.raises(HTTPException) as stale:
            await recordings.patch(take["id"], RecordingPatch(version=take["version"]), ids.lead, client=client)
        assert stale.value.detail["code"] == "STALE_VERSION"
        with pytest.raises(HTTPException) as outside:
            await recordings.patch(take["id"], RecordingPatch(version=moved["version"], targets=[{"sectionId": "nope"}]), ids.lead, client=client)
        assert outside.value.detail["code"] == "TARGET_OUTSIDE_INSTRUMENT"
        two = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D2"]}], at=t(0.2),
                                replacesRecordingId=take["id"], ackedRecordingIds=[take["id"]])
        replaced = await recordings.recording_payload(client, take["id"])
        assert replaced["removedAt"] is not None and two["filing"] == "FILED"
        await recordings.restore(take["id"], ids.lead, client=client)
        three = await record_take(client, ids, s["id"], ids.other, targets=[{"questionLineageId": ids.questions["D2"]}], at=t(0.4))
        assert three["filing"] == "NEEDS_DECISION"
        decided = await recordings.decide(three["id"], DecideRequest(action="CONTINUATION", otherRecordingId=two["id"]), ids.lead, client=client)
        assert decided["filing"] == "FILED" and decided["continuationOfId"] == two["id"]
        events = await client.query_raw('SELECT "kind" FROM "QuestionnaireRecordingEvent" WHERE "recordingId" = ANY($1::text[]) ORDER BY "at"',
                                        [take["id"], three["id"]])
        assert {"REFILE", "SUBJECTS", "REMOVE", "RESTORE", "DECIDE"} <= {e["kind"] for e in events}
        with pytest.raises(HTTPException) as forbidden:
            await recordings.make_primary(two["id"], type("P", (), {"artisanId": ids.artisans[0], "targetKey": "x"})(), ids.lead, client=client)
        assert forbidden.value.detail["code"] == "FORBIDDEN"
        await client.disconnect()

    run(go())


def test_the_sha_worker_merges_a_same_session_copy_and_flags_one_on_another_session(monkeypatch) -> None:
    from app.services.questionnaire_v2 import recordings, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:2]), ids.lead)
        other, _ = await sessions.create_session(session_create(ids, ids.artisans[2:3], day="2026-06-22"), ids.other)
        same = lambda key: (hashlib.sha256(b"identical bytes").hexdigest(), 15)  # noqa: E731
        kept = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["B"]}], at=t(0),
                                 subjects=[{"artisanId": ids.artisans[0], "role": "ANSWERED"}])
        await client.execute_raw('UPDATE "MediaFile" SET "transcriptStatus" = \'COMPLETED\', "transcriptText" = \'words\' WHERE "id" = $1', kept["mediaFileId"])
        await recordings.verify_sha(kept["mediaFileId"], client=client, hasher=same)
        copy = await record_take(client, ids, s["id"], ids.other, targets=[{"sectionId": ids.sections["B"]}], at=t(0.1),
                                 subjects=[{"artisanId": ids.artisans[1], "role": "ANSWERED"}], ackedRecordingIds=[kept["id"]])
        out = await recordings.verify_sha(copy["mediaFileId"], client=client, hasher=same)
        assert out["status"] == "MERGED_INTO_TWIN"
        merged = await recordings.recording_payload(client, kept["id"])
        assert {x["artisanId"] for x in merged["subjects"] if x["role"] == "ANSWERED"} == set(ids.artisans[:2])
        gone = await recordings.recording_payload(client, copy["id"])
        assert gone["removedAt"] is not None and gone["transcript"]["status"] == "SKIPPED_DUPLICATE"
        elsewhere = await record_take(client, ids, other["id"], ids.other, targets=[{"sectionId": ids.sections["B"]}], at=t(50))
        out2 = await recordings.verify_sha(elsewhere["mediaFileId"], client=client, hasher=same)
        assert out2["status"] == "SAME_FILE_OTHER_SESSION"
        flagged = await recordings.recording_payload(client, elsewhere["id"])
        assert flagged["filing"] == "NEEDS_DECISION" and flagged["decisionKind"] == "SAME_FILE_OTHER_SESSION"
        jobs = await client.query_raw(
            "SELECT \"status\"::text AS s FROM \"MediaProcessingJob\" WHERE \"mediaFileId\" = $1 AND \"jobType\" = 'TRANSCRIPTION'", copy["mediaFileId"]
        )
        assert all(j["s"] == "CANCELLED" for j in jobs), "a verified copy is never transcribed"
        await client.disconnect()

    run(go())


def test_the_corpus_round_trips_stable_ids_and_carries_its_schema_version(monkeypatch) -> None:
    from app.schemas.questionnaire_v2 import AnswersPut
    from app.services.questionnaire_v2 import answers, corpus, sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D1"]}], at=t(0))
        await answers.put_answers(AnswersPut.model_validate({"items": [{"artisanId": ids.artisans[0], "questionLineageId": ids.questions["D1"],
                                                                         "text": "Teak", "sessionId": s["id"], "sourceRecordingId": take["id"]}]}), ids.lead)
        data = await corpus.corpus(ids.q, status=None, client=client)
        text = corpus.render(data["answers"], corpus.ANSWER_FIELDS, "jsonl")
        rows = [json.loads(line) for line in text.splitlines()]
        assert rows[0]["schemaVersion"] == 1 and rows[0]["questionLineageId"] == ids.questions["D1"]
        assert rows[0]["primaryRecordingId"] == take["id"] and rows[0]["takeCount"] == 1
        recs = data["recordings"]
        assert recs[0]["recordingId"] == take["id"] and recs[0]["consentVerdict"] in ("AWAITING", "ALLOW")
        approved_only = await corpus.corpus(ids.q, status="APPROVED", client=client)
        assert approved_only["answers"] == [] and approved_only["recordings"] == []
        names = corpus.file_names(data["title"], "jsonl")
        assert names["answers"].endswith("_Corpus-Answers.jsonl") and names["answers"].startswith("FieldRepository_Questionnaire_")
        await client.disconnect()

    run(go())
