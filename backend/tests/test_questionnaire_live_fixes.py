"""Phase 1 of the 21 Oct 2026 questionnaire audit: the bugs users of the LEGACY flow hit today.

One test (or more) per audit item, named after it: H3, H6, H7, H12, M8, M19, M20, M21. M20 and H7's
fake-backed halves live beside the code they pin (``test_web_capture.py``,
``test_questionnaire_workbook_import.py``); this file holds the rest, and every database-backed test
runs against the throwaway Postgres of ``qv2_fixtures`` (CI's "Backend database tests" job).
Every one of them runs with ``QUESTIONNAIRE_V2`` OFF unless it says otherwise, because that is what
production runs.
"""

from __future__ import annotations

import asyncio
import inspect
import uuid
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, HTTPException
from qv2_fixtures import (
    connect,
    needs_db,
    no_outside_calls,
    patch_db,
    record_take,
    run,
    seed,
    session_create,
    settings_v2,
    t,
)

LOCATION = {"latitude": 26.8, "longitude": 75.5, "state": "Rajasthan", "district": "Jaipur"}


def _code(exc: pytest.ExceptionInfo) -> str:
    return exc.value.detail["code"]


class _Body:
    """A request whose JSON body is ``payload`` (the v2 routes parse their own bodies)."""

    def __init__(self, payload: dict[str, Any]) -> None:
        self._payload = payload
        self.headers: dict[str, str] = {}

    async def json(self) -> dict[str, Any]:
        return self._payload


async def _media(client: Any, ids: SimpleNamespace, interview_id: str, *, removed: bool = False,
                 caption: str = "Question audio: D1 x", transcript: str | None = None) -> str:
    media_id = f"m{uuid.uuid4().hex[:12]}"
    await client.execute_raw(
        'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", "caption", '
        '"linkedRecordType", "linkedRecordId", "questionnaireInterviewId", "uploadedById", "updatedAt", "status", "removedAt", '
        '"transcriptText") VALUES ($1, $1 || \'.m4a\', \'AUDIO\', \'audio/mp4\', 10, \'b\', $2, $3, \'questionnaire\', $4, $4, $5, now(), '
        "'PENDING', CASE WHEN $6 THEN now() ELSE NULL END, $7)",
        media_id, f"media/{ids.lead.id}/{media_id}/clip.m4a", caption, interview_id, ids.lead.id, removed, transcript,
    )
    return media_id


async def _legacy_create(ids: SimpleNamespace, artisans: list[str], user: Any, **extra: Any) -> dict[str, Any]:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireInterviewCreate

    body = {"title": "Sitting", "questionnaireId": ids.q, "artisanIds": artisans, "location": LOCATION, **extra}
    return await questionnaire.create_interview(
        QuestionnaireInterviewCreate.model_validate(body), SimpleNamespace(headers={}), user
    )


# ── M19: the live-media filter applies whatever the flag says ──────────────────────────────────


def test_m19_the_live_filter_applies_with_the_flag_off(monkeypatch) -> None:
    from app.questionnaire.live import live_media_where

    settings_v2(monkeypatch, False)
    assert live_media_where() == {"removedAt": None}
    assert live_media_where({"status": "PENDING"}) == {"AND": [{"status": "PENDING"}, {"removedAt": None}]}


def test_m19_an_interviews_media_relation_reads_live_files_only() -> None:
    from app.api.routes import questionnaire
    from app.services.records import include_of

    media = next(rel for rel in questionnaire.RELATIONS if rel.field == "media")
    assert media.where == {"removedAt": None}
    assert include_of([media]) == {"media": {"where": {"removedAt": None}}}


@needs_db
def test_m19_approving_an_interview_never_approves_a_removed_copy(monkeypatch) -> None:
    from app.api.routes import review

    settings_v2(monkeypatch, False)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        interview = await _legacy_create(ids, ids.artisans[:1], ids.lead)
        live = await _media(client, ids, interview["id"])
        removed = await _media(client, ids, interview["id"], removed=True)
        approved = await review.approve_record_media("questionnaire", interview["id"], ids.admin, client=client)
        assert approved == 1
        rows = await client.query_raw('SELECT "id", "status"::text AS s FROM "MediaFile" WHERE "id" = ANY($1::text[])', [live, removed])
        assert {r["id"]: r["s"] for r in rows} == {live: "APPROVED", removed: "PENDING"}
        await client.disconnect()

    run(go())


# ── M21: the legacy create is atomic ───────────────────────────────────────────────────────────


@needs_db
def test_m21_two_racing_creates_for_one_set_make_one_interview_with_its_links(monkeypatch) -> None:
    settings_v2(monkeypatch, False)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        pair = ids.artisans[:2]
        results = await asyncio.gather(*(_legacy_create(ids, pair, ids.lead) for _ in range(4)))
        assert len({r["id"] for r in results}) == 1, "every racer lands on one interview"
        key = ",".join(sorted(pair))
        rows = await client.query_raw(
            'SELECT "id" FROM "QuestionnaireInterview" WHERE "questionnaireId" = $1 AND "artisanSetKey" = $2', ids.q, key
        )
        assert len(rows) == 1
        links = await client.query_raw(
            'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', rows[0]["id"]
        )
        assert sorted(r["artisanId"] for r in links) == sorted(pair)
        await client.disconnect()

    run(go())


# ── H6: legacy delete and merge ────────────────────────────────────────────────────────────────


@needs_db
def test_h6_a_legacy_delete_of_an_interview_with_recordings_is_409_whatever_the_flag(monkeypatch) -> None:
    from app.api.routes import questionnaire

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        for flag in (False, True):
            settings_v2(monkeypatch, flag)
            interview = await _legacy_create(ids, [ids.artisans[0 if flag else 1]], ids.lead)
            await _media(client, ids, interview["id"])
            with pytest.raises(HTTPException) as refused:
                await questionnaire.delete_interview(interview["id"], ids.lead)
            assert refused.value.status_code == 403, "deletes are content admins' only"
            with pytest.raises(HTTPException) as held:
                await questionnaire.delete_interview(interview["id"], ids.admin)
            assert held.value.status_code == 409 and _code(held) == "HAS_RECORDINGS"
            assert held.value.detail["count"] == 1
            still = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireInterview" WHERE "id" = $1', interview["id"])
            assert int(still[0]["n"]) == 1
        settings_v2(monkeypatch, False)
        empty = await _legacy_create(ids, ids.artisans[2:3], ids.lead)
        await _media(client, ids, empty["id"], removed=True)  # a removed copy is not a recording it holds
        await questionnaire.delete_interview(empty["id"], ids.admin)
        gone = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireInterview" WHERE "id" = $1', empty["id"])
        assert int(gone[0]["n"]) == 0
        await client.disconnect()

    run(go())


@needs_db
def test_h6_a_legacy_merge_with_v2_data_goes_through_the_v2_merge(monkeypatch) -> None:
    from app.api import router as api_router_module
    from app.api.routes import questionnaire
    from app.core import deps
    from app.questionnaire import sessions

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        target, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        source, _ = await sessions.create_session(session_create(ids, ids.artisans[1:2]), ids.other)
        take = await record_take(client, ids, source["id"], ids.other, targets=[{"sectionId": ids.sections["D"]}], at=t(1))
        settings_v2(monkeypatch, False)
        with pytest.raises(HTTPException) as refused:
            await questionnaire.merge_interview_into(source["id"], target["id"], ids.other)
        assert refused.value.status_code == 403
        survivor = await questionnaire.merge_interview_into(source["id"], target["id"], ids.admin)
        assert survivor["id"] == target["id"]
        row = await client.query_raw('SELECT "mergedIntoId" FROM "QuestionnaireInterview" WHERE "id" = $1', source["id"])
        assert row[0]["mergedIntoId"] == target["id"], "kept, pointing at the survivor, as the v2 merge does"
        moved = await client.query_raw('SELECT "questionnaireInterviewId" AS s FROM "MediaFile" WHERE "id" = $1', take["mediaFileId"])
        assert moved[0]["s"] == target["id"]
        people = await client.query_raw('SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', target["id"])
        assert set(ids.artisans[:2]) <= {r["artisanId"] for r in people}
        researchers = await client.query_raw('SELECT "userId" FROM "QuestionnaireInterviewResearcher" WHERE "interviewId" = $1', target["id"])
        assert ids.other.id in {r["userId"] for r in researchers}
        events = await client.query_raw(
            'SELECT count(*) AS n FROM "QuestionnaireRecordingEvent" WHERE "recordingId" = $1 AND "kind" = \'MOVE\'', take["id"]
        )
        assert int(events[0]["n"]) == 1

        # The merged-away row is not listed by the legacy list.
        app = FastAPI()
        app.include_router(api_router_module.api_router)
        app.dependency_overrides[deps.get_current_user] = lambda: ids.admin
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://h6.test") as http:
            listed = await http.get("/api/questionnaire/interviews", params={"questionnaireId": ids.q, "pageSize": 100})
        assert listed.status_code == 200, listed.text
        assert source["id"] not in listed.text and target["id"] in listed.text
        await client.disconnect()

    run(go())


# ── H7: a recording is evidence on a workbook re-upload ────────────────────────────────────────


@needs_db
def test_h7_a_question_with_only_a_take_survives_a_re_upload_that_leaves_it_out(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.questionnaire import sessions
    from app.services.questionnaire_xlsx import ParsedQuestion, ParsedQuestionnaire, ParsedSection

    settings_v2(monkeypatch, False)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client, sections={"D": 2})
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D2"]}], at=t(0))
        parsed = ParsedQuestionnaire(
            sections=[ParsedSection(code="D", title="Section D", questions=[
                ParsedQuestion(prompt="Question D1 about tools and materials number 1", row=2, questionId=ids.questions["D1"]),
            ])],
            sheet="Questionnaire",
            questionnaireId=ids.q,
        )
        report = await questionnaire.apply_parsed_edit(ids.q, parsed)
        assert report["retired"] == 1 and report["removed"] == 0
        row = await client.query_raw('SELECT "isActive", "retiredAt" FROM "QuestionnaireQuestion" WHERE "id" = $1', ids.questions["D2"])
        assert row and row[0]["isActive"] is False and row[0]["retiredAt"] is not None
        await client.disconnect()

    run(go())


# ── H12: consent POST needs edit privilege ─────────────────────────────────────────────────────


@needs_db
def test_h12_only_someone_who_may_edit_the_artisan_records_their_consent(monkeypatch) -> None:
    from app.api.routes import questionnaire_v2

    settings_v2(monkeypatch, False)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        artisan = ids.artisans[0]  # created by the admin
        body = {"state": "REFUSED", "purpose": "EXTERNAL_PROCESSING"}
        for stranger in (ids.vol, ids.other):
            with pytest.raises(HTTPException) as refused:
                await questionnaire_v2.artisan_consent_post(artisan, _Body(body), stranger)
            assert refused.value.status_code == 403
        rows = await client.query_raw('SELECT count(*) AS n FROM "Consent" WHERE "subjectId" = $1', artisan)
        assert int(rows[0]["n"]) == 0
        saved = await questionnaire_v2.artisan_consent_post(artisan, _Body(body), ids.admin)
        assert saved["state"] == "REFUSED"
        await client.disconnect()

    run(go())


# ── H3: downloads, the corpus and the record CSV are gated ─────────────────────────────────────


def test_h3_the_corpus_routes_need_dataset_download_access() -> None:
    from app.api.routes import questionnaire_v2
    from app.core.deps import require_dataset_downloader

    for route in (questionnaire_v2.corpus_jsonl, questionnaire_v2.corpus_csv):
        defaults = [p.default for p in inspect.signature(route).parameters.values()]
        assert any(getattr(d, "dependency", None) is require_dataset_downloader for d in defaults), route.__name__


@needs_db
def test_h3_a_volunteer_cannot_download_a_take_or_an_artisan_record_csv(monkeypatch) -> None:
    from app.api.routes import questionnaire_v2
    from app.questionnaire import sessions

    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"sectionId": ids.sections["D"]}], at=t(0))
        for stranger in (ids.vol, ids.other):
            with pytest.raises(HTTPException) as refused:
                await questionnaire_v2.recordings_download(take["id"], stranger)
            assert refused.value.status_code == 403
            with pytest.raises(HTTPException) as csv_refused:
                await questionnaire_v2.artisan_record_csv(ids.artisans[0], None, stranger)
            assert csv_refused.value.status_code == 403
        allowed = await questionnaire_v2.recordings_download(take["id"], ids.lead)
        assert allowed.status_code == 302
        await client.disconnect()

    run(go())


# ── M8: the old RecordingConsent table is not read ─────────────────────────────────────────────


def test_m8_the_consent_gate_reads_the_ledger_only(monkeypatch) -> None:
    from app.services import consent_gate
    from app.transcription import ledger

    assert '"RecordingConsent"' not in inspect.getsource(consent_gate)
    calls: list[tuple[str, list[str]]] = []

    async def ledger_states(client: Any, subject_type: str, ids: list[str], *, purpose: str) -> dict[str, Any]:
        calls.append((subject_type, ids))
        return {}

    class _NoRaw:
        async def query_raw(self, *_a: Any, **_k: Any) -> Any:
            raise AssertionError("the gate issued its own SQL beside the ledger's")

    monkeypatch.setattr(ledger, "current_states", ledger_states)
    run(consent_gate.current_states(ledger.ARTISAN, ["a1", "a1", "a2"], client=_NoRaw()))
    assert calls == [(ledger.ARTISAN, ["a1", "a2"])]


# ── M20 with the database: the recording target places a clip ─────────────────────────────────


@needs_db
def test_m20_use_transcripts_follows_the_recording_target_not_the_caption_number(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.questionnaire import sessions

    settings_v2(monkeypatch, False)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client, sections={"D": 3})
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        take = await record_take(client, ids, s["id"], ids.lead, targets=[{"questionLineageId": ids.questions["D3"]}], at=t(0))
        # The caption still says D1 (a reorder since), and carries no prompt; the target says D3.
        await client.execute_raw(
            'UPDATE "MediaFile" SET "caption" = \'Question audio: D1\', "transcriptText" = \'Chisels and a mallet.\' WHERE "id" = $1',
            take["mediaFileId"],
        )
        result = await questionnaire.apply_interview_transcripts(s["id"], ids.admin)
        assert result["applied"] == 1
        answers = await client.query_raw(
            'SELECT "questionId", "answerText" FROM "QuestionnaireResponse" WHERE "interviewId" = $1', s["id"]
        )
        assert [(r["questionId"], r["answerText"]) for r in answers] == [(ids.questions["D3"], "Chisels and a mallet.")]
        await client.disconnect()

    run(go())
