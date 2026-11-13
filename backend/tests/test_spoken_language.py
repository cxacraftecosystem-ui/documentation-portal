from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.transcription import dedup, languages
from app.transcription.contract import SttOptions
from app.transcription.providers import elevenlabs
from app.transcription.transcript.schema import RetranscribeBody

REPO = Path(__file__).resolve().parents[2]


def _fields(choice: str, *, conservative: bool = False) -> list[tuple[str, str]]:
    return elevenlabs.ElevenLabs().fields(SttOptions(spoken_language=choice), conservative=conservative)


def test_the_chosen_language_is_sent_to_scribe_as_language_code() -> None:
    assert ("language_code", "or") in _fields("or")
    assert ("language_code", "or") in _fields("or", conservative=True)
    assert all(name != "language_code" for name, _ in _fields("auto"))
    assert all(name != languages.MULTILINGUAL_FIELD for name, _ in _fields("auto"))


def test_mixed_and_automatic_send_no_language_to_scribe() -> None:
    plain = _fields("auto")
    assert _fields("mixed") == plain
    assert _fields("mixed", conservative=True) == _fields("auto", conservative=True)
    assert all(name not in ("language_code", languages.MULTILINGUAL_FIELD) for name, _ in plain)


def test_a_choice_is_read_from_names_and_codes_and_anything_else_is_automatic() -> None:
    assert languages.choice_of("Odia") == "or"
    assert languages.choice_of("or-IN") == "or"
    assert languages.choice_of("Mixed") == "mixed"
    assert languages.choice_of("Klingon") is None
    assert languages.normalise(None) == languages.AUTO
    assert languages.from_metadata({"spokenLanguage": "bn", "languageHint": "Hindi"}) == "bn"
    assert languages.uncertain(0.42) and not languages.uncertain(0.97) and not languages.uncertain(None)
    assert RetranscribeBody.model_validate({"spokenLanguage": "Odia"}).spokenLanguage == "or"
    with pytest.raises(ValueError):
        RetranscribeBody.model_validate({"spokenLanguage": "Klingon"})


def test_a_new_language_misses_the_cache_and_automatic_keeps_the_old_key() -> None:
    assert dedup.options_hash(SttOptions(spoken_language="or")) != dedup.options_hash(SttOptions())
    assert dedup.options_hash(SttOptions(spoken_language="auto")) == dedup.options_hash(SttOptions())


def test_scribes_language_probability_is_kept() -> None:
    assert elevenlabs.language_probability({"language_probability": 0.42}) == 0.42
    assert elevenlabs.language_probability({}) is None


def test_the_client_list_is_the_servers_list() -> None:
    shared = json.loads((REPO / "frontend" / "lib" / "transcript" / "spokenLanguages.json").read_text(encoding="utf-8"))
    assert shared["languages"] == languages.catalogue()
    assert shared["uncertainBelow"] == languages.UNCERTAIN_BELOW
    assert (shared["auto"], shared["mixed"]) == (languages.AUTO, languages.MIXED)


def test_a_recording_is_transcribed_in_the_language_chosen_for_it(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    from app.services import transcript_pipeline, transcription_jobs

    sent: dict[str, Any] = {}

    class _Store:
        async def cache_get(self, *_a: Any) -> Any:
            return None

    class _Media:
        async def update(self, **_k: Any) -> Any:
            return None

    def _options(_settings: Any, **kwargs: Any) -> SttOptions:
        sent["options"] = kwargs
        return SttOptions(spoken_language=kwargs.get("spoken_language") or "auto")

    async def _transcribe(_content: Any, _name: str, _mime: str, _settings: Any, **kwargs: Any) -> dict[str, Any]:
        sent["call"] = kwargs
        return {"status": "FAILED", "message": "offline"}

    monkeypatch.setattr(transcription_jobs, "PrismaTranscriptionStore", _Store)
    monkeypatch.setattr(transcription_jobs, "db", SimpleNamespace(mediafile=_Media()))
    monkeypatch.setattr(transcription_jobs.ai, "transcription_options", _options)
    monkeypatch.setattr(transcript_pipeline, "accepts_keyterms", lambda _fn: False)
    audio = tmp_path / "take.m4a"
    audio.write_bytes(b"\x00" * 64)
    media = SimpleNamespace(
        id="m-1", originalFilename="take.m4a", mimeType="audio/mp4", mediaType="AUDIO", checksum=None,
        extraMetadata={"spokenLanguage": "or"}, locationId="loc-bihar",
    )
    asyncio.run(transcription_jobs.transcribe_recording(media, SimpleNamespace(), source_path=str(audio), transcribe=_transcribe))
    assert sent["call"]["spoken_language"] == "or"
    assert sent["options"]["spoken_language"] == "or"
    assert sent["call"]["language_bias"] == ["Odia"]


class _Rows:
    def __init__(self, row: Any = None) -> None:
        self.row = row
        self.updates: list[dict[str, Any]] = []

    async def find_unique(self, **_k: Any) -> Any:
        return self.row

    async def update(self, **kwargs: Any) -> Any:
        self.updates.append(kwargs)
        data = kwargs["data"].get("extraMetadata")
        if data is not None:
            self.row.extraMetadata = getattr(data, "data", data)
        return self.row


def test_retranscribing_creates_a_new_version_and_keeps_the_old_one(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import transcript_store

    calls: dict[str, Any] = {}

    class _Versions:
        async def find_first(self, **_k: Any) -> Any:
            return SimpleNamespace(version=1)

        async def create(self, **kwargs: Any) -> Any:
            calls["create"] = kwargs["data"]
            return SimpleNamespace(id="v2", version=kwargs["data"]["version"])

        async def update_many(self, **kwargs: Any) -> Any:
            calls["supersede"] = kwargs
            return 1

        async def delete_many(self, **_k: Any) -> Any:
            raise AssertionError("an old version was deleted")

    class _Turns:
        async def create_many(self, **_k: Any) -> Any:
            return 1

    monkeypatch.setattr(transcript_store, "db", SimpleNamespace(transcriptversion=_Versions(), transcriptturn=_Turns()))
    from app.transcription.transcript.turns import turns_from_text

    row = asyncio.run(
        transcript_store.create_version(
            "m-1", turns_from_text("ନମସ୍କାର", detected="or", duration_ms=1_000),
            stt_provider="elevenlabs", stt_model="scribe_v3", detected="or", duration_ms=1_000,
        )
    )
    assert row.version == 2
    assert calls["create"]["detectedLanguage"] == "or"
    assert calls["supersede"]["where"] == {"mediaFileId": "m-1", "supersededAt": None, "version": {"lt": 2}}
    assert "supersededAt" in calls["supersede"]["data"]


def test_retranscribe_stores_the_choice_and_queues_a_new_run(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import transcript_pipeline as pipeline

    media = SimpleNamespace(id="m-1", extraMetadata={"spokenLanguage": "hi", "other": 1})
    rows = _Rows(media)
    queued: list[str] = []

    async def _allowed(*_a: Any) -> Any:
        return None

    async def _send(_media: Any) -> tuple[bool, None]:
        return True, None

    async def _requeue(m: Any) -> None:
        queued.append(m.id)

    monkeypatch.setattr(pipeline, "db", SimpleNamespace(mediafile=rows))
    monkeypatch.setattr(pipeline.transcript_portal, "guard_write", _allowed)
    monkeypatch.setattr(pipeline.transcript_portal, "may_send_audio", _send)
    monkeypatch.setattr(pipeline.transcript_portal, "requeue_transcription", _requeue)
    asyncio.run(pipeline.retranscribe(media, SimpleNamespace(id="u-1"), "or"))
    assert media.extraMetadata == {"spokenLanguage": "or", "other": 1}
    assert queued == ["m-1"]


def test_each_version_remembers_what_it_was_asked_for_and_how_sure_scribe_was(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import transcript_pipeline as pipeline

    media = SimpleNamespace(id="m-1", extraMetadata={"spokenLanguage": "or"}, sizeBytes=0)
    monkeypatch.setattr(pipeline, "db", SimpleNamespace(mediafile=_Rows(media)))
    asyncio.run(pipeline.remember_version_language("m-1", 2, {"spokenLanguage": "auto", "languageProbability": 0.42}))
    assert media.extraMetadata["transcriptLanguages"] == {"2": {"requested": "auto", "probability": 0.42}}

    async def _job(*_a: Any) -> Any:
        return SimpleNamespace(status="QUEUED")

    monkeypatch.setattr(pipeline, "_latest_job", _job)
    version = SimpleNamespace(version=2, detectedLanguage="hi", durationMs=69_000)
    view = asyncio.run(pipeline.spoken_language_view(media, version, sees_spend=False))
    assert view["requested"] == "auto"
    assert view["detected"] == "hi" and view["detectedLabel"] == "Hindi"
    assert view["uncertain"] is True and view["retranscribing"] is True
    assert view["chosen"] == "or"
    assert view["estimateMinutes"] == 1.15 and view["estimateUsd"] is None


def test_the_suggestion_prefers_the_artisan_then_the_researcher(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import transcript_pipeline as pipeline

    answers: dict[str, list[dict[str, Any]]] = {"artisan": [], "researcher": [{"choice": "or"}]}

    class _Db:
        async def query_raw(self, sql: str, *_args: Any) -> list[dict[str, Any]]:
            assert '"Location"' not in sql and '"state"' not in sql
            return answers["artisan" if "artisanId" in sql else "researcher"]

    monkeypatch.setattr(pipeline, "db", _Db())
    found = asyncio.run(pipeline.spoken_language_suggestion(["a-1"], "u-1"))
    assert found["suggested"] == {"code": "or", "label": "Odia", "source": "researcher"}
    answers["artisan"] = [{"choice": "bn"}]
    found = asyncio.run(pipeline.spoken_language_suggestion(["a-1"], "u-1"))
    assert found["suggested"] == {"code": "bn", "label": "Bengali", "source": "artisan"}
    answers["artisan"], answers["researcher"] = [], [{"choice": "auto"}]
    assert asyncio.run(pipeline.spoken_language_suggestion(["a-1"], "u-1"))["suggested"] is None
