from __future__ import annotations

import asyncio
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.transcription import dedup, languages
from app.transcription.contract import SttOptions
from app.transcription.providers import elevenlabs

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


def test_a_new_language_misses_the_cache_and_automatic_keeps_the_old_key() -> None:
    assert dedup.options_hash(SttOptions(spoken_language="or")) != dedup.options_hash(SttOptions())
    assert dedup.options_hash(SttOptions(spoken_language="auto")) == dedup.options_hash(SttOptions())


def test_scribes_language_probability_is_kept() -> None:
    assert elevenlabs.language_probability({"language_probability": 0.42}) == 0.42
    assert elevenlabs.language_probability({}) is None


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
