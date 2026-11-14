from __future__ import annotations

import asyncio
import logging
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import transcription_admin
from app.scripts import queue_pending_translations as sweep
from app.services import (
    app_settings,
    media_queue,
    transcript_backfill,
    transcript_insights,
    transcript_pipeline,
)


def _row(mode: str | None) -> SimpleNamespace:
    return SimpleNamespace(transcriptionMode=mode)


class _Reached(Exception):
    pass


@pytest.mark.parametrize(
    ("mode", "on"),
    [(None, True), ("REFINED_TRANSLATED", True), ("RAW", False), ("REFINED", False), ("bogus", True)],
)
def test_translation_is_on_only_for_transcribe_and_translate(mode: str | None, on: bool) -> None:
    row = None if mode is None else _row(mode)
    assert app_settings.translation_on(row) is on


def test_saving_the_choice_writes_the_existing_mode_column(monkeypatch: pytest.MonkeyPatch) -> None:
    writes: list[dict[str, Any]] = []

    async def update(*, where: dict[str, Any], data: dict[str, Any]) -> Any:
        writes.append(data)
        return _row(data["transcriptionMode"])

    async def get_or_create(_by: Any = None) -> Any:
        return _row("REFINED_TRANSLATED")

    monkeypatch.setattr(app_settings, "db", SimpleNamespace(appsetting=SimpleNamespace(update=update)))
    monkeypatch.setattr(app_settings, "get_or_create_app_settings", get_or_create)
    off = asyncio.run(app_settings.save_translation(False, "admin1"))
    on = asyncio.run(app_settings.save_translation(True, "admin1"))
    assert [w["transcriptionMode"] for w in writes] == ["RAW", "REFINED_TRANSLATED"]
    assert all(w["updatedById"] == "admin1" for w in writes)
    assert not app_settings.translation_on(off) and app_settings.translation_on(on)


def test_no_automatic_translation_is_queued_while_transcribe_only(monkeypatch: pytest.MonkeyPatch) -> None:
    async def load() -> Any:
        return _row("RAW")

    async def tripwire(*_a: Any, **_k: Any) -> Any:
        raise AssertionError("a translation was queued while transcribe only")

    monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
    monkeypatch.setattr(app_settings, "load_app_settings", load)
    monkeypatch.setattr(transcript_insights, "current_insight", tripwire)
    monkeypatch.setattr(transcript_insights, "enqueue", tripwire)
    assert asyncio.run(transcript_insights.enqueue_after_transcript("m1")) is None


def test_an_automatic_translation_still_queues_while_translate_is_on(monkeypatch: pytest.MonkeyPatch) -> None:
    queued: list[tuple[str, Any]] = []

    async def load() -> Any:
        return _row("REFINED_TRANSLATED")

    async def none(*_a: Any, **_k: Any) -> Any:
        return None

    async def enqueue(media_id: str, *, requested_by_id: Any = None, **_k: Any) -> Any:
        queued.append((media_id, requested_by_id))
        return SimpleNamespace(id="job")

    monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
    monkeypatch.setattr(app_settings, "load_app_settings", load)
    monkeypatch.setattr(transcript_insights, "current_insight", none)
    monkeypatch.setattr(transcript_insights, "enqueue", enqueue)
    assert asyncio.run(transcript_insights.enqueue_after_transcript("m1")) is not None
    assert queued == [("m1", None)]


def _insights_job_wiring(monkeypatch: pytest.MonkeyPatch, requester: str | None, mode: str) -> list[str]:
    stopped: list[str] = []

    async def requested(_job: Any) -> Any:
        return requester

    async def load() -> Any:
        return _row(mode)

    async def stop(*, job_id: str | None = None) -> int:
        stopped.append(str(job_id))
        return 1

    def reached(*_a: Any, **_k: Any) -> Any:
        raise _Reached

    async def reached_async(*_a: Any, **_k: Any) -> Any:
        raise _Reached

    monkeypatch.setattr(transcript_insights, "job_requester", requested)
    monkeypatch.setattr(media_queue, "load_app_settings", load)
    monkeypatch.setattr(transcript_pipeline, "stop_automatic_translation", stop)
    monkeypatch.setattr(transcript_insights, "current_insight", reached_async)
    monkeypatch.setattr(transcript_insights, "select_sources", reached)
    monkeypatch.setattr(transcript_insights, "consent_refusal", reached_async, raising=False)
    monkeypatch.setattr(transcript_pipeline, "generate", reached_async)
    return stopped


def test_a_queued_automatic_translation_stands_down_when_its_turn_comes(monkeypatch: pytest.MonkeyPatch) -> None:
    stopped = _insights_job_wiring(monkeypatch, None, "RAW")
    job = SimpleNamespace(id="job-auto", mediaFileId="m1")
    asyncio.run(media_queue._process_insights_job(job, SimpleNamespace(id="m1")))
    assert stopped == ["job-auto"]


def test_a_person_asked_summary_still_runs_while_transcribe_only(monkeypatch: pytest.MonkeyPatch) -> None:
    stopped = _insights_job_wiring(monkeypatch, "user-1", "RAW")
    job = SimpleNamespace(id="job-person", mediaFileId="m1")
    with pytest.raises(_Reached):
        asyncio.run(media_queue._process_insights_job(job, SimpleNamespace(id="m1")))
    assert stopped == []


def test_an_automatic_translation_runs_as_before_while_translate_is_on(monkeypatch: pytest.MonkeyPatch) -> None:
    stopped = _insights_job_wiring(monkeypatch, None, "REFINED_TRANSLATED")
    job = SimpleNamespace(id="job-auto", mediaFileId="m1")
    with pytest.raises(_Reached):
        asyncio.run(media_queue._process_insights_job(job, SimpleNamespace(id="m1")))
    assert stopped == []


def test_the_settings_route_sets_the_choice_stops_queued_translations_and_audits_it(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    saved: list[tuple[bool, Any]] = []
    stopped: list[Any] = []

    async def save(on: bool, by: Any = None) -> Any:
        saved.append((on, by))
        return _row("REFINED_TRANSLATED" if on else "RAW")

    async def stop(*, job_id: Any = None) -> int:
        stopped.append(job_id)
        return 3

    async def state() -> dict[str, Any]:
        return {"transcribeOnly": bool(saved and not saved[-1][0])}

    monkeypatch.setattr(app_settings, "save_translation", save)
    monkeypatch.setattr(transcript_pipeline, "stop_automatic_translation", stop)
    monkeypatch.setattr(transcription_admin, "_state", state)
    transcript_backfill._estimate_cache.update(value={"costUsd": 9.0}, at=0.0)
    user = SimpleNamespace(id="admin1", email="admin@example.org")
    with caplog.at_level(logging.WARNING):
        answer = asyncio.run(
            transcription_admin.update_transcription_settings(
                transcription_admin.TranscriptionSettingsUpdate(transcribeOnly=True), user
            )
        )
    assert answer == {"transcribeOnly": True}
    assert saved == [(False, "admin1")] and stopped == [None]
    assert transcript_backfill._estimate_cache == {}, "the estimate is worked out again without translation"
    assert any("transcribeOnly=True" in r.getMessage() and "stoppedTranslations=3" in r.getMessage() for r in caplog.records)

    back = asyncio.run(
        transcription_admin.update_transcription_settings(
            transcription_admin.TranscriptionSettingsUpdate(transcribeOnly=False), user
        )
    )
    assert back == {"transcribeOnly": False}
    assert saved[-1] == (True, "admin1") and stopped == [None], "turning it on stops nothing and queues nothing"


def test_an_unrelated_switch_leaves_the_choice_alone(monkeypatch: pytest.MonkeyPatch) -> None:
    async def tripwire(*_a: Any, **_k: Any) -> Any:
        raise AssertionError("the choice was written by another switch")

    async def nothing(*_a: Any, **_k: Any) -> Any:
        return None

    async def state() -> dict[str, Any]:
        return {}

    monkeypatch.setattr(app_settings, "save_translation", tripwire)
    monkeypatch.setattr(app_settings, "save_transcription_switches", nothing)
    monkeypatch.setattr(transcription_admin, "_state", state)
    user = SimpleNamespace(id="admin1", email="admin@example.org")
    asyncio.run(
        transcription_admin.update_transcription_settings(
            transcription_admin.TranscriptionSettingsUpdate(paused=True), user
        )
    )


def test_a_video_is_sized_at_a_video_rate_not_an_audio_one() -> None:
    each = int(3.5e9 / 58)
    old = sum(transcript_backfill.minutes_of(None, each) for _ in range(58)) / 60
    assert old > 100, "the reported bug: 3.5 GB of video read as audio is over a hundred hours"
    hours = sum(transcript_backfill.minutes_of(None, each, video=True) for _ in range(58)) / 60
    assert 0.5 < hours < 5
    assert transcript_backfill.minutes_of(None, 8_000 * 60) == 1.0, "audio keeps its own rate"


def test_a_stored_duration_beats_any_size_guess() -> None:
    assert transcript_backfill.minutes_of(90_000, 10**10, video=True) == 1.5
    assert transcript_backfill.minutes_of(90_000, 10**10) == 1.5


def test_the_candidates_query_reads_every_stored_duration_and_the_media_type() -> None:
    sql = transcript_backfill.CANDIDATES_SQL
    for source in ('"QuestionnaireRecording"', '"MediaWaveform"', '"TranscriptVersion"', 'm."mediaType"', 'm."mimeType"'):
        assert source in sql, source


def test_the_estimate_leaves_out_translation_while_transcribe_only(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(transcript_backfill, "stt_usd_per_minute", lambda: 0.25 / 60)
    c = transcript_backfill.Candidate
    on = transcript_backfill.summarise([c("a", transcript_backfill.FAILED, None, 120.0, True)], 0)
    off = transcript_backfill.summarise(
        [c("a", transcript_backfill.FAILED, None, 120.0, False)], 0, translate=False
    )
    assert on["costUsd"] == round(120 * (0.25 / 60 + transcript_backfill.GEMINI_USD_PER_MINUTE), 2)
    assert off["costUsd"] == round(120 * 0.25 / 60, 2)
    assert on["includesTranslation"] is True and off["includesTranslation"] is False
    assert transcript_backfill.usd_per_minute(False) == pytest.approx(0.25 / 60)


def test_collect_marks_candidates_by_the_switch(monkeypatch: pytest.MonkeyPatch) -> None:
    rows = [
        {"id": "v1", "recordedAt": None, "size": 6.0e7, "mediaType": "VIDEO", "mimeType": "video/mp4", "job": None, "duration": None},
        {"id": "a1", "recordedAt": None, "size": 4.8e5, "mediaType": "AUDIO", "mimeType": "audio/mp4", "job": "FAILED", "duration": 120_000},
    ]

    async def query_raw(_sql: str, *_a: Any) -> list[dict[str, Any]]:
        return rows

    async def find_unique(*, where: dict[str, Any]) -> Any:
        return SimpleNamespace(id=where["id"])

    async def load() -> Any:
        return _row("RAW")

    async def allowed(_media: Any) -> tuple[bool, None]:
        return True, None

    from app.services import transcript_portal

    monkeypatch.setattr(
        transcript_backfill,
        "db",
        SimpleNamespace(query_raw=query_raw, mediafile=SimpleNamespace(find_unique=find_unique)),
    )
    monkeypatch.setattr(app_settings, "load_app_settings", load)
    monkeypatch.setattr(transcript_portal, "may_send", allowed)
    candidates, refused = asyncio.run(transcript_backfill.collect())
    assert refused == 0 and [c.translate for c in candidates] == [False, False]
    video, audio = candidates
    assert video.minutes == pytest.approx(6.0e7 / transcript_backfill.ASSUMED_VIDEO_BYTES_PER_SECOND / 60)
    assert audio.minutes == 2.0


def test_the_retranscribe_estimate_follows_the_switch(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(transcript_backfill, "stt_usd_per_minute", lambda: 0.25 / 60)
    media = SimpleNamespace(sizeBytes=0, mediaType="VIDEO", mimeType="video/mp4")
    version = SimpleNamespace(durationMs=600_000)
    minutes, with_translation = transcript_pipeline.retranscribe_estimate(media, version, sees_spend=True)
    _, without = transcript_pipeline.retranscribe_estimate(media, version, sees_spend=True, translate=False)
    assert minutes == 10.0 and without == pytest.approx(10 * 0.25 / 60, abs=1e-4)
    assert with_translation > without


def test_the_sweep_says_transcribe_only_instead_of_queueing() -> None:
    row: dict[str, Any] = {"active": False, "versionStatus": None, "edited": False, "ranSince": False}
    assert sweep.classify(row, enabled=True, declined_message="x", translating=False) == sweep.TRANSLATION_OFF
    assert sweep.classify(row, enabled=True, declined_message="x") is None
    assert sweep.TRANSLATION_OFF in sweep.BUCKETS


def _turn(status: str, english: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        id="t1", position=0, speakerKey="speaker_0", startMs=0, endMs=1000, language="hi",
        editedText=None, machineText="नमस्ते", lowConfidence=[], words=[],
        editedEnglish=None, machineEnglish=english, translationStatus=status,
        translationModel=None, revision=1,
    )


def test_a_turn_without_english_reads_not_translated_never_translating() -> None:
    labels = {"speaker_0": "Speaker 1"}
    assert transcript_pipeline.turn_json(_turn("PENDING"), labels, manual=True)["translation"]["status"] == "NOT_REQUESTED"
    assert transcript_pipeline.turn_json(_turn("PENDING"), labels)["translation"]["status"] == "PENDING"
    translated = transcript_pipeline.turn_json(_turn("TRANSLATED", "Hello"), labels, manual=True)
    assert translated["translation"]["status"] == "TRANSLATED"
    assert transcript_pipeline.turn_json(_turn("ALREADY_ENGLISH"), labels, manual=True)["translation"]["status"] == "ALREADY_ENGLISH"


def test_the_neutral_notice_shows_only_while_english_is_missing() -> None:
    rows = [_turn("PENDING")]
    notice = transcript_pipeline.english_state(None, rows, None, declined=False, may_add=True, manual_only=True)
    assert notice["notice"] == transcript_pipeline.NOTICE_NOT_AUTOMATIC
    reader = transcript_pipeline.english_state(None, rows, None, declined=False, may_add=False, manual_only=True)
    assert reader["notice"] == transcript_pipeline.NOTICE_NOT_AUTOMATIC, "every reader sees the same calm note"
    done = transcript_pipeline.english_state(None, [_turn("TRANSLATED", "Hello")], None, declined=False, may_add=True, manual_only=True)
    assert done["notice"] is None
    on = transcript_pipeline.english_state(None, rows, None, declined=False, may_add=True)
    assert on["notice"] is None
    declined = transcript_pipeline.english_state(None, rows, None, declined=True, may_add=True, manual_only=True)
    assert declined["notice"] == transcript_pipeline.NOTICE_DECLINED
