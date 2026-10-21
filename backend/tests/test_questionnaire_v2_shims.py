"""Old builds keep working (DESIGN 5.6, 7.2; tests 3, 26, 27 and the shim half of 35a)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from qv2_fixtures import connect, needs_db, no_outside_calls, patch_db, run, seed, settings_v2

from app.services.questionnaire_v2 import legacy_resolver


def _questions():
    base = datetime(2026, 6, 1, tzinfo=UTC)
    rows = []
    for i, prompt in enumerate(["Your name", "Where you learnt the craft", "Who taught you", "Tools you use most",
                                "Raw material source", "Market you sell in", "How long a piece takes"], start=1):
        rows.append({"id": f"f{i}", "lineageId": f"f{i}", "prompt": prompt, "sectionId": "secF", "sectionCode": "F",
                     "sortOrder": i, "isActive": True, "updatedAt": base})
    return rows, [{"id": "secF", "code": "F"}, {"id": "secD", "code": "D"}]


def test_the_resolver_ranks_prompt_text_before_position_even_after_a_reorder() -> None:
    questions, sections = _questions()
    for q in questions:  # reordered after the upload: position would now be wrong
        q["sortOrder"] = 8 - q["sortOrder"]
        q["updatedAt"] = datetime(2026, 7, 1, tzinfo=UTC)
    media = SimpleNamespace(caption="Question audio: F7 How long a piece takes", extraMetadata=None, originalFilename="F_7_X.m4a",
                            transcriptText=None, transcriptStatus=None, createdAt=datetime(2026, 6, 20, tzinfo=UTC))
    res = legacy_resolver.resolve(media, questions, sections)
    assert res.targets == [{"questionLineageId": "f7"}] and res.provenance == "LEGACY_CAPTION_TEXT" and res.step == "caption.prompt"


def test_the_resolver_uses_metadata_then_transcript_then_filename_then_section_and_else_asks() -> None:
    questions, sections = _questions()
    meta = SimpleNamespace(caption=None, extraMetadata={"questionId": "f3"}, originalFilename="x.m4a", transcriptText=None,
                           transcriptStatus=None, createdAt=None)
    assert legacy_resolver.resolve(meta, questions, sections).provenance == "LEGACY_META"
    spoken = SimpleNamespace(caption="Question audio: F4", extraMetadata=None, originalFilename="x.m4a",
                             transcriptText="The tools you use most are chisels; most tools I use are mine", transcriptStatus="COMPLETED",
                             createdAt=datetime(2026, 6, 20, tzinfo=UTC))
    res = legacy_resolver.resolve(spoken, questions, sections)
    assert res.targets == [{"questionLineageId": "f4"}] and res.step in ("transcript+position", "caption.position")
    named = SimpleNamespace(caption=None, extraMetadata=None, originalFilename="F_2_GIRIRAJ_200620261824.m4a", transcriptText=None,
                            transcriptStatus=None, createdAt=datetime(2026, 6, 20, tzinfo=UTC))
    assert legacy_resolver.resolve(named, questions, sections).provenance == "LEGACY_FILENAME"
    section = SimpleNamespace(caption="Section audio: D", extraMetadata=None, originalFilename="clip.m4a", transcriptText=None,
                              transcriptStatus=None, createdAt=None)
    assert legacy_resolver.resolve(section, questions, sections).targets == [{"sectionId": "secD"}]
    lost = SimpleNamespace(caption=None, extraMetadata=None, originalFilename="clip.m4a", transcriptText="material market",
                           transcriptStatus="COMPLETED", createdAt=None)
    res = legacy_resolver.resolve(lost, questions, sections)
    assert res.needs_decision and len(res.candidates) <= 3


def test_the_title_is_never_used_to_place_a_clip() -> None:
    import inspect

    assert "title" not in inspect.getsource(legacy_resolver.resolve)


def test_an_old_build_after_the_cutoff_gets_a_transient_503_never_a_new_code(monkeypatch) -> None:
    """Audit C7: the cutoff compares the build NUMBER (X-App-Version-Code), per platform. The version
    name was reset to 0.0.1, so a name compare would refuse the new build or let every old one in."""
    from app.core.config import get_settings
    from app.services.questionnaire_v2 import shims

    monkeypatch.setattr(get_settings(), "questionnaire_min_android_version_code_for_create", 30, raising=False)
    monkeypatch.setattr(get_settings(), "questionnaire_min_web_version_code_for_create", None, raising=False)
    android = {"x-app-platform": "android", "x-app-version": "0.0.1"}
    with pytest.raises(HTTPException) as old:
        shims.assert_old_build_may_create({**android, "x-app-version-code": "29"})
    assert old.value.status_code == 503 and old.value.headers["Retry-After"] == "86400"
    # The reset name "0.0.1" with a newer number is the NEW build: never refused, whatever its name.
    shims.assert_old_build_may_create({**android, "x-app-version-code": "30"})
    shims.assert_old_build_may_create({**android, "x-app-version-code": "31"})
    # A build from before the header contract sends no code: below any Android minimum.
    with pytest.raises(HTTPException) as headerless:
        shims.assert_old_build_may_create({"x-app-version": "0.0.14"})
    assert headerless.value.status_code == 503
    with pytest.raises(HTTPException):
        shims.assert_old_build_may_create({})
    # The web is its own platform: the Android minimum never refuses it, with or without a code.
    shims.assert_old_build_may_create({"x-app-version": "web-0.1.0"})
    shims.assert_old_build_may_create({"x-app-platform": "web", "x-app-version-code": "3"})
    monkeypatch.setattr(get_settings(), "questionnaire_min_web_version_code_for_create", 5, raising=False)
    with pytest.raises(HTTPException):
        shims.assert_old_build_may_create({"x-app-platform": "web", "x-app-version-code": "3"})
    shims.assert_old_build_may_create({"x-app-platform": "web"})  # the page ships with the server
    assert shims.client_build({"x-app-version": "web-0.1.0"}) == ("web", None, "web-0.1.0")
    assert shims.client_build({"x-app-platform": "ANDROID", "x-app-version-code": "12"}) == ("android", 12, None)
    assert shims.client_build({"x-app-version-code": "-1"}) == ("android", None, None)


@needs_db
def test_an_old_create_makes_a_session_with_no_fold_and_keeps_its_typed_title(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.schemas.questionnaire import QuestionnaireInterviewCreate

    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        q1 = ids.questions["D1"]
        body = {
            "title": "section F", "workshopId": ids.workshop, "questionnaireId": ids.q, "language": "Bangla",
            "artisanIds": ids.artisans[:2], "responses": [{"questionId": q1, "answerText": "Chisels"}],
            "location": {"latitude": 26.8, "longitude": 75.5, "state": "Rajasthan", "district": "Jaipur"},
        }
        request = SimpleNamespace(headers={"x-app-version": "0.0.13"})
        first = await questionnaire.create_interview(QuestionnaireInterviewCreate.model_validate(body), request, ids.lead)
        second = await questionnaire.create_interview(QuestionnaireInterviewCreate.model_validate(body), request, ids.lead)
        assert first["id"] != second["id"], "no unique violation and no fold: two sessions"
        row = await client.query_raw('SELECT "title", "legacyTitle", "languageCode", "sessionDateEstimated" FROM "QuestionnaireInterview" WHERE "id" = $1', first["id"])
        assert row[0]["legacyTitle"] == "section F" and row[0]["title"].startswith("Group-Interview_")
        assert row[0]["languageCode"] == "bn" and row[0]["sessionDateEstimated"] is True
        assert first["title"] == row[0]["title"]
        answer = await client.query_raw('SELECT count(*) AS n FROM "QuestionnaireAnswer" WHERE "artisanId" = ANY($1::text[])', ids.artisans[:2])
        assert int(answer[0]["n"]) == 2
        await client.disconnect()

    run(go())


@needs_db
def test_an_old_clip_complete_becomes_a_recording_through_the_resolver(monkeypatch) -> None:
    from app.services.questionnaire_v2 import sessions, shims
    from qv2_fixtures import session_create

    settings_v2(monkeypatch, True)

    async def go() -> None:
        client = await connect()
        patch_db(monkeypatch, client)
        no_outside_calls(monkeypatch)
        ids = await seed(client)
        s, _ = await sessions.create_session(session_create(ids, ids.artisans[:1]), ids.lead)
        prompt = (await client.query_raw('SELECT "prompt" FROM "QuestionnaireQuestion" WHERE "id" = $1', ids.questions["F7"]))[0]["prompt"]
        await client.execute_raw('UPDATE "QuestionnaireQuestion" SET "sortOrder" = 20 - "sortOrder" WHERE "sectionId" = $1', ids.sections["F"])
        media_id = f"m{ids.tag}"
        await client.execute_raw(
            'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", "caption", '
            '"linkedRecordType", "linkedRecordId", "questionnaireInterviewId", "uploadedById", "updatedAt", "createdAt") VALUES ($1, '
            "'F_7_GIRIRAJ_200620261824.m4a', 'AUDIO', 'audio/mp4', 10, 'b', $2, $3, 'questionnaire', $4, $4, $5, now(), $6::timestamp)",
            media_id, f"media/{ids.lead.id}/x{ids.tag}/F_7.m4a", f"Question audio: F7 {prompt}", s["id"], ids.lead.id,
            (datetime.now(UTC) - timedelta(days=1)).replace(tzinfo=None).isoformat(),
        )
        media = await client.mediafile.find_unique(where={"id": media_id})
        await shims.after_legacy_complete({"id": media_id}, SimpleNamespace(linkedRecordType="questionnaire"), ids.lead)
        rec = await client.query_raw(
            'SELECT r."provenance"::text AS p, t."questionLineageId" AS l FROM "QuestionnaireRecording" r '
            'JOIN "QuestionnaireRecordingTarget" t ON t."recordingId" = r."id" WHERE r."mediaFileId" = $1', media.id,
        )
        assert rec and rec[0]["l"] == ids.questions["F7"] and rec[0]["p"] == "SHIM"
        await client.disconnect()

    run(go())
