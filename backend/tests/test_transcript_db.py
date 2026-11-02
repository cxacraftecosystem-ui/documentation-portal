"""The transcript pipeline against a real, migrated Postgres: turns, translation, edits, Ask, budget.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (``shared/transcription.sha256``).
It runs in each portal's database CI job (a throwaway Postgres on loopback) and skips anywhere else.
Gemini answers come from ``tests/fixtures/transcript/``; nothing calls Google or ElevenLabs.
"""

from __future__ import annotations

import asyncio
import json
import os
import socket
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import pytest

_DSN = os.environ.get("DATABASE_URL", "")
_PARTS = urlsplit(_DSN) if _DSN else None
_LOCAL = bool(_PARTS and _PARTS.hostname in {"localhost", "127.0.0.1", "::1"})


def _listening() -> bool:
    if not _LOCAL or _PARTS is None:
        return False
    try:
        with socket.create_connection((_PARTS.hostname, _PARTS.port or 5432), timeout=1.0):
            return True
    except OSError:
        return False


pytestmark = pytest.mark.skipif(
    not _listening(),
    reason="needs an exported DATABASE_URL naming a running Postgres on this machine (never backend/.env)",
)

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "transcript"
CONTRACT = json.loads(
    (Path(__file__).resolve().parents[2] / "shared" / "transcript-contract.json").read_text(encoding="utf-8")
)


def fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


class _Response:
    def __init__(self, payload: Any) -> None:
        self.status_code = 200
        self._payload = payload
        self.headers: dict[str, str] = {}

    def json(self) -> Any:
        return self._payload


class _Gemini:
    """Replaces ``requests.post`` for Gemini only; answers from a queue of recorded replies."""

    def __init__(self, *payloads: Any) -> None:
        self.payloads = list(payloads)
        self.calls = 0

    def __call__(self, url: str, **_kwargs: Any) -> _Response:
        assert "generativelanguage.googleapis.com" in url, "only Gemini may be called here"
        self.calls += 1
        return _Response(self.payloads.pop(0))


def _reply(obj: dict[str, Any]) -> dict[str, Any]:
    return {
        "candidates": [{"content": {"parts": [{"text": json.dumps(obj, ensure_ascii=False)}]}, "finishReason": "STOP"}],
        "usageMetadata": {"promptTokenCount": 100, "candidatesTokenCount": 20},
    }


@pytest.fixture
def wired(monkeypatch: pytest.MonkeyPatch) -> Any:
    import requests

    from app.services import transcript_insights, transcript_pipeline, transcript_portal
    from app.transcription.contract import ApiKey

    async def keys() -> list[Any]:
        return [ApiKey("GEMINI_API_KEY#1", "test-key")]

    async def allowed(_media: Any) -> tuple[bool, str | None]:
        return True, None

    async def readable(rows: list[Any], _viewer: Any) -> set[str]:
        return {str(r.id) for r in rows}

    monkeypatch.setattr(transcript_pipeline, "enabled", lambda: True)
    monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
    monkeypatch.setattr(transcript_pipeline, "gemini_keys", keys)
    monkeypatch.setattr(transcript_portal, "may_send", allowed)
    monkeypatch.setattr(transcript_portal, "readable_ids", readable)
    monkeypatch.setattr(transcript_portal, "audio_url", lambda _media: None)

    class Wiring:
        def gemini(self, *payloads: Any) -> _Gemini:
            fake = _Gemini(*payloads)
            monkeypatch.setattr(requests, "post", fake)
            return fake

        def refuse(self) -> None:
            async def refused(_media: Any) -> tuple[bool, str | None]:
                return False, "Consent was refused, so this recording stays on this server."

            monkeypatch.setattr(transcript_portal, "may_send", refused)

    return Wiring()


def _run(body: Any) -> Any:
    from app.core.db import db

    async def wrapper() -> Any:
        await db.connect()
        try:
            return await body()
        finally:
            await db.disconnect()

    return asyncio.run(wrapper())


async def _seed() -> dict[str, Any]:
    from app.core.db import db

    tag = uuid4().hex[:10]
    researcher = await db.user.create(data={"email": f"tr-{tag}@example.org", "name": "Nandini", "role": "RESEARCHER"})
    admin = await db.user.create(data={"email": f"tr-admin-{tag}@example.org", "name": "Admin", "role": "ADMIN"})
    media = await db.mediafile.create(
        data={
            "originalFilename": "interview.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 1024,
            "bucket": "ci",
            "objectKey": f"media/{researcher.id}/{tag}/interview.m4a",
            "uploadedBy": {"connect": {"id": researcher.id}},
        }
    )
    return {"researcher": researcher, "admin": admin, "media": media}


async def _cleanup(seed: dict[str, Any]) -> None:
    from app.core.db import db

    await db.transcriptspend.delete_many(where={"mediaFileId": seed["media"].id})
    await db.mediafile.delete(where={"id": seed["media"].id})
    await db.notification.delete_many(where={"userId": {"in": [seed["admin"].id, seed["researcher"].id]}})
    await db.user.delete_many(where={"id": {"in": [seed["admin"].id, seed["researcher"].id]}})


def _scribe_result() -> dict[str, Any]:
    from app.transcription.providers import elevenlabs

    payload = fixture("scribe_v2")
    return {
        "status": "COMPLETED",
        "text": payload["text"],
        "provider": "elevenlabs",
        "model": "scribe_v2",
        "languageCode": payload["language_code"],
        "audioMs": 25_000,
        "words": elevenlabs.parse_words(payload),
    }


def test_the_pipeline_end_to_end_on_a_real_database(wired: Any) -> None:
    from app.core.db import db
    from app.services import transcript_pipeline as pipeline, transcript_store
    from app.transcription.transcript import render

    async def scenario() -> None:
        seed = await _seed()
        media = seed["media"]
        try:
            version = await pipeline.store_transcription(media, _scribe_result(), keyterm_count=12)
            assert version is not None and version.version == 1 and version.turnCount == 4
            assert version.languages == ["hi", "en"] and version.speakerCount == 2 and version.keytermCount == 12

            view = await pipeline.build_view(media, seed["researcher"])
            assert set(view) == set(CONTRACT["types"]["TranscriptView"])
            assert all(set(t) == set(CONTRACT["types"]["Turn"]) for t in view["turns"])
            assert view["status"] == "READY" and view["cost"] is None, "cost is for admins"
            assert [t["translation"]["status"] for t in view["turns"]] == ["PENDING", "PENDING", "ALREADY_ENGLISH", "PENDING"]
            assert view["badges"] == [], "the model names are for administrators only"
            assert all(s["sample"] and s["sample"]["endMs"] - s["sample"]["startMs"] <= 8000 for s in view["speakers"])
            assert view["turns"][1]["lowConfidence"] == [[3, 7], [25, 29]]
            # The uploader may change it; another reader is told why not, and offered no edit control.
            assert view["readOnly"] is None and view["permissions"]["edit"] and view["permissions"]["mapSpeakers"]
            from types import SimpleNamespace

            stranger = SimpleNamespace(id="not-the-uploader", role="RESEARCHER", email="x@example.org")
            other = await pipeline.build_view(media, stranger)
            assert other["readOnly"]["code"] == "NOT_EDITOR" and other["readOnly"]["message"]
            assert not any(other["permissions"][k] for k in ("edit", "retry", "autodraft", "mapSpeakers"))

            # The insights job: one Gemini pass gives English per turn, summary, quotes and terms.
            fake = wired.gemini(fixture("gemini_translate"))
            outcome = await pipeline.generate(media, user_id=None)
            assert outcome["status"] == "COMPLETED" and outcome["failed"] == 0 and fake.calls == 1
            rows = await transcript_store.turns_of(version.id)
            assert [r.translationStatus for r in rows] == ["TRANSLATED", "TRANSLATED", "ALREADY_ENGLISH", "TRANSLATED"]
            assert rows[1].machineEnglish == "We mix clay and gum for dabu (mud-resist printing)."
            insight = await db.transcriptinsight.find_first(where={"mediaFileId": media.id, "supersededAt": None})
            assert insight is not None and insight.provider == "gemini" and insight.summaryLines
            assert [q["turnId"] for q in insight.quotes] == [rows[1].id]
            spend = await db.transcriptspend.find_many(where={"mediaFileId": media.id})
            assert len(spend) == 1 and spend[0].costUsd > 0 and spend[0].kind == "TRANSLATE"
            terms = await db.transcriptglossarysighting.find_many(where={"mediaFileId": media.id}, include={"term": True})
            assert [s.term.term for s in terms] == ["dabu"], "a term nobody said is not in the glossary"

            admin_view = await pipeline.build_view(media, seed["admin"])
            assert admin_view["cost"]["translationUsd"] > 0
            assert [b["label"] for b in admin_view["badges"]] == ["Scribe v2", "Gemini 3.8 Flash"]

            # Speakers, then an edit that makes the English stale, with history.
            current = await transcript_store.current_version(media.id)
            from app.transcription.transcript.schema import SpeakerMapping

            await pipeline.map_speakers(media, seed["researcher"], current.revision, [
                SpeakerMapping(speakerKey="speaker_0", name="Nandini", role="RESEARCHER", personId=seed["researcher"].id),
                SpeakerMapping(speakerKey="speaker_1", name="Giriraj", role="ARTISAN"),
            ])
            with pytest.raises(pipeline.PipelineRefused) as stale:
                await pipeline.map_speakers(media, seed["researcher"], current.revision, [])
            assert stale.value.code == "STALE_REVISION"
            edited = await pipeline.edit_turn(media, seed["researcher"], rows[0].id, base_revision=rows[0].revision,
                                              text="आप ये रंग कैसे बनाते हो?", english=None)
            assert edited["textSource"] == "EDITED" and edited["translation"]["status"] == "STALE"
            assert edited["words"] == [] and edited["speakerName"] == "Nandini (researcher)"
            history = await pipeline.turn_history(media, rows[0].id)
            assert [(h["field"], h["source"]) for h in history] == [("ORIGINAL", "MACHINE"), ("ORIGINAL", "EDITED")]
            with pytest.raises(pipeline.PipelineRefused):
                await pipeline.edit_turn(media, seed["researcher"], rows[0].id, base_revision=rows[0].revision, text="x", english=None)

            # Re-translate that one turn.
            wired.gemini(_reply({"translations": [{"id": "t1", "english": "How do you make these colours?", "alreadyEnglish": False}]}))
            again = await pipeline.retranslate_turn(media, seed["researcher"], rows[0].id)
            assert again["translation"]["status"] == "TRANSLATED"

            # Copies are generated from the stored turns.
            doc, _context = await pipeline.render_doc(media)
            copy = render.copy_all(doc, render.BOTH)
            assert "**Nandini (researcher)** · `00:00:04`\nआप ये रंग कैसे बनाते हो?\n> How do you make these colours?" in copy
            assert "**Giriraj** · `00:00:16`" not in copy and "**Nandini (researcher)** · `00:00:16`\nAnd how long does it dry?\n\n" in copy

            # Ask: every citation is a turn of this transcript and holds its quote.
            wired.gemini(fixture("gemini_ask"))
            answer = await pipeline.ask(media, "How does he make the paste?")
            assert answer["covered"] is True and answer["citations"]
            by_id = {r.id: r for r in await transcript_store.turns_of(version.id)}
            for citation in answer["citations"]:
                turn = by_id[citation["turnId"]]
                said = " ".join(filter(None, [turn.editedText or turn.machineText, turn.editedEnglish or turn.machineEnglish]))
                assert citation["quote"] is None or citation["quote"] in said
                assert citation["startMs"] == turn.startMs

            # Withdrawn consent stops every new send.
            wired.refuse()
            with pytest.raises(pipeline.PipelineRefused) as refused:
                await pipeline.ask(media, "Anything else?")
            assert refused.value.code == "CONSENT_REFUSED"
            with pytest.raises(pipeline.PipelineRefused) as quoted:
                await pipeline.quote_card(media, [rows[1].id])
            assert quoted.value.code == "CONSENT_REFUSED"
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_a_transcript_made_before_the_pipeline_gets_its_turns_on_first_read(wired: Any) -> None:
    from app.core.db import db
    from app.services import transcript_pipeline as pipeline

    async def scenario() -> None:
        seed = await _seed()
        try:
            media = await db.mediafile.update(
                where={"id": seed["media"].id},
                data={"transcriptText": "**Speaker 1:** आप कैसे हैं?\n\n**Speaker 2:** Fine, thank you.",
                      "transcriptStatus": "COMPLETED", "transcriptProvider": "deepgram"},
            )
            view = await pipeline.build_view(media, seed["researcher"])
            assert [t["speakerKey"] for t in view["turns"]] == ["Speaker 1", "Speaker 2"]
            assert [t["translation"]["status"] for t in view["turns"]] == ["PENDING", "ALREADY_ENGLISH"]
            # D38: making the turns queues their translation, so the view says it is on its way.
            assert view["status"] == "TRANSLATING" and view["version"] == 1
            again = await pipeline.build_view(media, seed["researcher"])
            assert again["version"] == 1, "made once, then read"
            jobs = await db.mediaprocessingjob.count(where={"mediaFileId": media.id, "jobType": "TRANSCRIPT_INSIGHTS"})
            assert jobs == 1, "queued once, however often it is opened"
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_the_budget_alerts_then_pauses_transcription_once(wired: Any) -> None:
    from app.core.db import db
    from app.services import transcript_pipeline as pipeline, transcript_store
    from app.transcription.transcript import insight

    async def scenario() -> None:
        seed = await _seed()
        before = await db.appsetting.find_unique(where={"id": "singleton"})
        try:
            await db.appsetting.upsert(
                where={"id": "singleton"},
                data={"create": {"id": "singleton", "transcriptionPaused": False},
                      "update": {"transcriptionPaused": False, "transcriptBudgetAlertedMonth": None,
                                 "transcriptBudgetPausedMonth": None}},
            )
            stt, gem = await transcript_store.month_spend()
            spent = stt + gem
            await pipeline.set_budget(spent + 10.0, seed["admin"].id)
            await db.sttusage.create(data={"provider": "elevenlabs", "model": "scribe_v2", "mediaFileId": seed["media"].id,
                                           "audioMs": 60_000, "latencyMs": 1, "outcome": "COMPLETED", "costUsd": 8.5})
            meter = await pipeline.check_budget()
            assert meter is not None and meter.state == insight.ALERT
            row = await db.appsetting.find_unique(where={"id": "singleton"})
            assert row.transcriptBudgetAlertedMonth == insight.month_key() and row.transcriptionPaused is False
            alerts = await db.notification.count(where={"userId": seed["admin"].id, "kind": pipeline.BUDGET_NOTICE_KIND})
            assert alerts == 1
            await pipeline.check_budget()
            assert await db.notification.count(where={"userId": seed["admin"].id, "kind": pipeline.BUDGET_NOTICE_KIND}) == 1, "once a month"

            await db.sttusage.create(data={"provider": "elevenlabs", "model": "scribe_v2", "mediaFileId": seed["media"].id,
                                           "audioMs": 60_000, "latencyMs": 1, "outcome": "COMPLETED", "costUsd": 5.0})
            meter = await pipeline.check_budget()
            assert meter.state == insight.PAUSED
            row = await db.appsetting.find_unique(where={"id": "singleton"})
            assert row.transcriptionPaused is True and pipeline.holds_translation(row)
            view = await pipeline.budget_view()
            assert view["state"] == "PAUSED" and view["pausedByBudget"] is True
            assert set(view) == set(CONTRACT["types"]["BudgetMeter"])
        finally:
            await db.sttusage.delete_many(where={"mediaFileId": seed["media"].id})
            restore = {
                "transcriptionPaused": True if before is None else before.transcriptionPaused,
                "transcriptMonthlyBudgetUsd": 100.0 if before is None else before.transcriptMonthlyBudgetUsd,
                "transcriptBudgetAlertedMonth": None if before is None else before.transcriptBudgetAlertedMonth,
                "transcriptBudgetPausedMonth": None if before is None else before.transcriptBudgetPausedMonth,
            }
            await db.appsetting.update(where={"id": "singleton"}, data=restore)
            await _cleanup(seed)

    _run(scenario)


def test_the_routes_answer_the_contract_shapes(wired: Any) -> None:
    import httpx
    from fastapi import FastAPI

    from app.api.router import api_router
    from app.core import deps
    from app.services import transcript_pipeline as pipeline

    async def scenario() -> None:
        seed = await _seed()
        media = seed["media"]
        app = FastAPI()
        app.include_router(api_router)
        app.dependency_overrides[deps.get_current_user] = lambda: seed["admin"]
        try:
            await pipeline.store_transcription(media, _scribe_result())
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
                view = (await client.get(f"/api/transcripts/{media.id}")).json()
                assert view["status"] == "READY" and len(view["turns"]) == 4
                copied = await client.post(f"/api/transcripts/{media.id}/copy", json={"track": "original", "turnIds": None})
                assert copied.status_code == 200 and copied.json()["text"].startswith("# ")
                single = await client.post(f"/api/transcripts/{media.id}/copy",
                                           json={"track": "original", "turnIds": [view["turns"][0]["id"]], "single": True})
                assert single.json()["text"] == view["turns"][0]["text"]
                for fmt, kind in (("md", "text/markdown"), ("srt", "application/x-subrip"), ("vtt", "text/vtt"), ("docx", "application/vnd")):
                    got = await client.get(f"/api/transcripts/{media.id}/download", params={"format": fmt, "track": "original"})
                    assert got.status_code == 200 and got.headers["content-type"].startswith(kind)
                    assert "_Transcript" in got.headers["content-disposition"] or "_Subtitles" in got.headers["content-disposition"]
                unknown = await client.post(f"/api/transcripts/{media.id}/copy", json={"track": "both", "turnIds": ["nope"]})
                assert unknown.status_code == 404 and unknown.json()["detail"]["code"] == "TURN_UNKNOWN"
                budget = await client.get("/api/transcripts/budget")
                assert budget.status_code == 200 and budget.json()["alertAt"] == 0.8
                batch = await client.get("/api/transcripts/batch", params={"ids": media.id})
                assert batch.json()["items"][0]["mediaId"] == media.id
                app.dependency_overrides[deps.get_current_user] = lambda: seed["researcher"]
                assert (await client.get("/api/transcripts/budget")).status_code == 403
        finally:
            await _cleanup(seed)

    _run(scenario)


# --- D38 (0.0.9): old transcripts get translated; a declined translation gets a person's English ---


async def _legacy(seed: dict[str, Any]) -> Any:
    from app.core.db import db

    return await db.mediafile.update(
        where={"id": seed["media"].id},
        data={"transcriptText": "**Speaker 1:** आप कैसे हैं?\n\n**Speaker 2:** Fine, thank you.",
              "transcriptStatus": "COMPLETED", "transcriptProvider": "deepgram"},
    )


async def _insight_jobs(media_id: str) -> list[Any]:
    from app.core.db import db

    return await db.mediaprocessingjob.find_many(where={"mediaFileId": media_id, "jobType": "TRANSCRIPT_INSIGHTS"})


def _stranger() -> Any:
    from types import SimpleNamespace

    return SimpleNamespace(id="not-the-uploader", role="RESEARCHER", email="x@example.org")


def test_opening_or_exporting_an_old_transcript_queues_its_translation_once(wired: Any) -> None:
    from app.services import transcript_pipeline as pipeline

    async def scenario() -> None:
        seed = await _seed()
        try:
            media = await _legacy(seed)
            doc, _context = await pipeline.render_doc(media)  # an export makes the version first
            assert len(doc.turns) == 2
            jobs = await _insight_jobs(media.id)
            assert [str(j.status).split(".")[-1] for j in jobs] == ["QUEUED"] and jobs[0].requestedById is None
            await pipeline.build_view(media, seed["researcher"])
            await pipeline.render_doc(media)
            assert len(await _insight_jobs(media.id)) == 1, "a version that exists queues nothing"
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_an_old_transcript_queues_nothing_without_consent_the_switch_or_over_an_edited_summary(
    wired: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    from datetime import UTC, datetime

    from app.core.db import db
    from app.services import (
        transcript_insights,
        transcript_pipeline as pipeline,
        transcript_portal,
        transcript_store,
    )
    from prisma import Json

    async def allowed(_media: Any) -> tuple[bool, str | None]:
        return True, None

    async def scenario() -> None:
        seed = await _seed()
        try:
            media = await _legacy(seed)
            wired.refuse()
            view = await pipeline.build_view(media, seed["researcher"])
            assert view["version"] == 1 and await _insight_jobs(media.id) == [], "consent refused: nothing queued"

            monkeypatch.setattr(transcript_portal, "may_send", allowed)
            current = await transcript_store.current_version(media.id)
            await db.transcriptversion.update(where={"id": current.id}, data={"supersededAt": datetime.now(UTC)})
            monkeypatch.setattr(transcript_insights, "enabled", lambda: False)
            await pipeline.build_view(media, seed["researcher"])
            assert await _insight_jobs(media.id) == [], "the switch is off: nothing queued"

            monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
            current = await transcript_store.current_version(media.id)
            await db.transcriptversion.update(where={"id": current.id}, data={"supersededAt": datetime.now(UTC)})
            await db.transcriptinsight.create(data={
                "mediaFileId": media.id, "version": 1, "sourceHash": "x", "summaryLines": ["Edited."],
                "quotes": Json([]), "translationStatus": "NOT_NEEDED", "provider": "gemini", "modelId": "m",
                "promptVersion": "p", "generated": Json({}), "generatedAt": datetime.now(UTC),
                "editedAt": datetime.now(UTC),
            })
            await pipeline.build_view(media, seed["researcher"])
            assert await _insight_jobs(media.id) == [], "a researcher's summary is never replaced automatically"
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_a_declined_translation_is_marked_and_the_running_job_queues_no_second_one(wired: Any) -> None:
    from app.core.db import db
    from app.services import transcript_insights, transcript_pipeline as pipeline, transcript_store

    async def scenario() -> None:
        seed = await _seed()
        try:
            media = await _legacy(seed)
            running = await db.mediaprocessingjob.create(data={
                "jobType": "TRANSCRIPT_INSIGHTS", "status": "PROCESSING", "mediaFileId": media.id, "priority": 80,
            })
            blocked = fixture("gemini_blocked")
            wired.gemini(blocked, blocked, blocked, blocked)
            with pytest.raises(transcript_insights.InsightsFailed) as failed:
                await pipeline.generate(media, user_id=None)  # makes the version inside its own job
            assert failed.value.retry is False
            assert str(failed.value) == pipeline.gemini.MESSAGES[pipeline.gemini.DECLINED]
            assert [j.id for j in await _insight_jobs(media.id)] == [running.id], "the running job is the only one"
            version = await transcript_store.current_version(media.id)
            assert version.translationStatus == pipeline.DECLINED
            rows = await transcript_store.turns_of(version.id)
            assert [r.translationStatus for r in rows] == ["FAILED", "ALREADY_ENGLISH"]
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_declined_english_shows_by_role_and_a_person_adds_it_whole_or_per_turn(wired: Any) -> None:
    from app.core.db import db
    from app.services import transcript_insights, transcript_pipeline as pipeline, transcript_store
    from app.transcription.transcript import render

    async def scenario() -> None:
        seed = await _seed()
        media = seed["media"]
        stranger = _stranger()
        try:
            version = await pipeline.store_transcription(media, _scribe_result())
            blocked = fixture("gemini_blocked")
            wired.gemini(blocked, blocked, blocked, blocked)
            with pytest.raises(transcript_insights.InsightsFailed):
                await pipeline.generate(media, user_id=None)

            mine = await pipeline.build_view(media, seed["researcher"])
            assert set(mine) == set(CONTRACT["types"]["TranscriptView"])
            assert mine["englishNotice"] == "DECLINED" and mine["permissions"]["addEnglish"] is True
            assert mine["manualEnglish"] is None
            assert [t["translation"]["status"] for t in mine["turns"]] == ["FAILED", "FAILED", "ALREADY_ENGLISH", "FAILED"]
            admin = await pipeline.build_view(media, seed["admin"])
            assert admin["englishNotice"] == "DECLINED" and admin["permissions"]["addEnglish"] is True
            other = await pipeline.build_view(media, stranger)
            assert other["englishNotice"] == "UNAVAILABLE" and other["permissions"]["addEnglish"] is False

            # Two paragraphs for three turns that need English: kept whole as the transcript's English.
            whole = "How do you make these colours?\n\nWe mix clay and gum."
            with pytest.raises(pipeline.PipelineRefused) as stale:
                await pipeline.add_english(media, seed["researcher"], base_revision=mine["revision"] - 1, text=whole)
            assert stale.value.code == "STALE_REVISION"
            assert await pipeline.add_english(media, seed["researcher"], base_revision=mine["revision"], text=whole) == "TEXT"
            insight = await transcript_insights.current_insight(media.id)
            assert insight.provider == pipeline.MANUAL and insight.editedAt is not None
            for reader in (seed["researcher"], stranger):
                view = await pipeline.build_view(media, reader)
                assert view["englishNotice"] is None and view["manualEnglish"] == whole
            doc, _context = await pipeline.render_doc(media)
            assert render.copy_all(doc, render.ENGLISH).endswith("\n\n" + whole)
            assert render.ENGLISH_HEADING + "\n\n" + whole in render.copy_all(doc, render.BOTH)
            assert await transcript_insights.enqueue_after_transcript(media.id) is None, "a person's English is never replaced"

            # Three paragraphs for the three turns that need English: laid on them, one each.
            view = await pipeline.build_view(media, seed["researcher"])
            lines = "Line one.\n\nLine two.\n\nLine three."
            assert await pipeline.add_english(media, seed["researcher"], base_revision=view["revision"], text=lines) == "TURNS"
            after = await pipeline.build_view(media, stranger)
            english = [t["translation"] for t in after["turns"]]
            assert [e["text"] for e in english] == ["Line one.", "Line two.", after["turns"][2]["text"], "Line three."]
            assert [e["status"] for e in english] == ["TRANSLATED", "TRANSLATED", "ALREADY_ENGLISH", "TRANSLATED"]
            assert all(e["source"] == "EDITED" for i, e in enumerate(english) if i != 2)
            assert after["englishNotice"] is None and after["manualEnglish"] is None, "English on every turn supersedes the paste"
            assert after["permissions"]["addEnglish"] is False
            current = await transcript_store.current_version(media.id)
            assert current.translationStatus == "COMPLETED" and current.translationProvider == pipeline.MANUAL
            first = (await transcript_store.turns_of(version.id))[0]
            assert [(h["field"], h["source"]) for h in await pipeline.turn_history(media, first.id)][-1] == ("ENGLISH", "EDITED")
            with pytest.raises(pipeline.PipelineRefused) as empty:
                await pipeline.add_english(media, seed["researcher"], base_revision=current.revision, text=" \n\n ")
            assert empty.value.code == "VALIDATION_FAILED"
        finally:
            await db.transcriptinsight.delete_many(where={"mediaFileId": media.id})
            await _cleanup(seed)

    _run(scenario)


def test_a_recording_declined_before_the_marker_reads_declined_from_its_job(wired: Any) -> None:
    from app.core.db import db
    from app.services import transcript_pipeline as pipeline

    async def scenario() -> None:
        seed = await _seed()
        media = seed["media"]
        try:
            await pipeline.store_transcription(media, _scribe_result())
            await db.mediaprocessingjob.create(data={
                "jobType": "TRANSCRIPT_INSIGHTS", "status": "FAILED", "mediaFileId": media.id,
                "error": pipeline.gemini.MESSAGES[pipeline.gemini.DECLINED],
            })
            view = await pipeline.build_view(media, seed["researcher"])
            assert view["englishNotice"] == "DECLINED" and view["status"] == "READY"
            assert "PENDING" not in [t["translation"]["status"] for t in view["turns"]], "never 'Translating' for ever"
            assert (await pipeline.build_view(media, _stranger()))["englishNotice"] == "UNAVAILABLE"
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_the_english_route_takes_whoever_may_edit_and_refuses_a_stale_revision(wired: Any) -> None:
    import httpx
    from fastapi import FastAPI

    from app.api.router import api_router
    from app.core import deps
    from app.services import transcript_pipeline as pipeline

    async def scenario() -> None:
        seed = await _seed()
        media = seed["media"]
        app = FastAPI()
        app.include_router(api_router)
        stranger = _stranger()
        app.dependency_overrides[deps.get_current_user] = lambda: stranger
        try:
            await pipeline.store_transcription(media, _scribe_result())
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
                view = (await client.get(f"/api/transcripts/{media.id}")).json()
                body = {"baseRevision": view["revision"], "text": "One.\n\nTwo.\n\nThree."}
                refused = await client.post(f"/api/transcripts/{media.id}/english", json=body)
                assert refused.status_code == 403
                app.dependency_overrides[deps.get_current_user] = lambda: seed["researcher"]
                stale = await client.post(f"/api/transcripts/{media.id}/english", json={**body, "baseRevision": view["revision"] + 5})
                assert stale.status_code == 409 and stale.json()["detail"]["code"] == "STALE_REVISION"
                bad = await client.post(f"/api/transcripts/{media.id}/english", json={"baseRevision": 0, "text": ""})
                assert bad.status_code == 422
                done = await client.post(f"/api/transcripts/{media.id}/english", json=body)
                assert done.status_code == 200
                got = done.json()
                assert set(got) == set(CONTRACT["types"]["TranscriptView"]) and got["revision"] > view["revision"]
                assert [t["translation"]["text"] for t in got["turns"] if t["translation"]["status"] != "ALREADY_ENGLISH"] == [
                    "One.", "Two.", "Three."
                ]
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_the_sweep_counts_then_queues_once_and_prints_no_words(wired: Any) -> None:
    from app.scripts import queue_pending_translations as sweep

    async def scenario() -> None:
        seed = await _seed()
        try:
            media = await _legacy(seed)  # never opened: no version yet
            dry = await sweep.sweep(apply=False, ids=[media.id])
            assert dry[sweep.CANDIDATES] == 1 and dry[sweep.WOULD_QUEUE] == 1 and dry[sweep.QUEUED] == 0
            assert await _insight_jobs(media.id) == [], "a dry run queues nothing"
            applied = await sweep.sweep(apply=True, ids=[media.id])
            assert applied[sweep.QUEUED] == 1 and len(await _insight_jobs(media.id)) == 1
            again = await sweep.sweep(apply=True, ids=[media.id])
            assert again[sweep.ALREADY_ACTIVE] == 1 and again[sweep.QUEUED] == 0
            assert len(await _insight_jobs(media.id)) == 1, "running it twice queues nothing new"
            printed = sweep.report(again, apply=True)
            assert media.id not in printed and "आप" not in printed and "Nandini" not in printed
            assert all(f"{bucket}: " in printed for bucket in sweep.BUCKETS)
        finally:
            await _cleanup(seed)

    _run(scenario)


def test_the_sweep_leaves_refused_consent_and_a_declined_recording_alone(wired: Any) -> None:
    from app.core.db import db
    from app.scripts import queue_pending_translations as sweep
    from app.services import transcript_pipeline as pipeline

    async def scenario() -> None:
        seed = await _seed()
        try:
            media = await _legacy(seed)
            wired.refuse()
            refused = await sweep.sweep(apply=True, ids=[media.id])
            assert refused[sweep.CONSENT_REFUSED] == 1 and await _insight_jobs(media.id) == []
            await db.mediaprocessingjob.create(data={
                "jobType": "TRANSCRIPT_INSIGHTS", "status": "FAILED", "mediaFileId": media.id,
                "error": pipeline.gemini.MESSAGES[pipeline.gemini.DECLINED],
            })
            declined = await sweep.sweep(apply=True, ids=[media.id])
            assert declined[sweep.DECLINED] == 1 and declined[sweep.QUEUED] == 0
        finally:
            await _cleanup(seed)

    _run(scenario)
