from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.core.odia_script import has_devanagari

DEVANAGARI_TURN = "अ, तो madam, आपणां केबे"
ODIA_TURN = "ଅ, ତୋ madam, ଆପଣାଂ କେବେ"


def _words(text: str) -> list[dict[str, Any]]:
    words: list[dict[str, Any]] = []
    for index, token in enumerate(text.split(" ")):
        if index:
            words.append({"text": " ", "type": "spacing", "start": index * 0.5, "end": index * 0.5})
        words.append({"text": token, "type": "word", "start": index * 0.5, "end": index * 0.5 + 0.4, "speaker_id": "speaker_0"})
    return words


def _result(language: str | None, **extra: Any) -> dict[str, Any]:
    return {
        "status": "COMPLETED",
        "text": DEVANAGARI_TURN,
        "formattedTranscript": "Transcript\n\n" + DEVANAGARI_TURN,
        "provider": "elevenlabs",
        "model": "scribe_v3",
        "languageCode": language,
        "audioMs": 4000,
        "words": _words(DEVANAGARI_TURN),
        **extra,
    }


@pytest.fixture
def captured(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    from app.services import transcript_store

    seen: dict[str, Any] = {}

    async def create_version(media_id: str, turns: list[Any], **kwargs: Any) -> Any:
        seen["turns"] = turns
        seen["detected"] = kwargs.get("detected")
        return SimpleNamespace(version=1, id="v1")

    async def stt_cost_for(_media_id: str) -> float:
        return 0.0

    monkeypatch.setattr(transcript_store, "create_version", create_version)
    monkeypatch.setattr(transcript_store, "stt_cost_for", stt_cost_for)
    return seen


def _store(result: dict[str, Any], metadata: Any = None) -> Any:
    from app.services import transcript_pipeline

    media = SimpleNamespace(id="m1", extraMetadata=metadata)
    return asyncio.run(transcript_pipeline.store_transcription(media, result))


def test_detected_odia_is_stored_in_odia_script(captured: dict[str, Any]) -> None:
    result = _result("or")
    assert _store(result) is not None
    turns = captured["turns"]
    assert [t.text for t in turns] == [ODIA_TURN]
    assert turns[0].language == "or"
    assert all(not has_devanagari(w.text) for w in turns[0].words)
    assert all(turns[0].text[w.offset : w.offset + len(w.text)] == w.text for w in turns[0].words)
    assert result["text"] == ODIA_TURN, "the full transcript text is saved from the same result"
    assert result["formattedTranscript"] == "Transcript\n\n" + ODIA_TURN


@pytest.mark.parametrize(
    ("language", "metadata", "extra"),
    [
        ("ori", None, {}),
        (None, {"languageHint": "or"}, {}),
        ("hi", {"spokenLanguage": "or"}, {}),
        ("hi", None, {"spokenLanguage": "or"}),
    ],
)
def test_the_odia_hint_or_detection_converts(captured: dict[str, Any], language: Any, metadata: Any, extra: Any) -> None:
    result = _result(language, **extra)
    _store(result, metadata)
    assert [t.text for t in captured["turns"]] == [ODIA_TURN]
    assert result["text"] == ODIA_TURN


@pytest.mark.parametrize(
    ("language", "metadata"),
    [("hi", None), (None, None), ("hi", {"languageHint": "hi"}), ("mr", {"spokenLanguage": "auto"}), ("ne", {"languageHint": "en"})],
)
def test_other_languages_are_never_touched(captured: dict[str, Any], language: Any, metadata: Any) -> None:
    result = _result(language)
    _store(result, metadata)
    assert [t.text for t in captured["turns"]] == [DEVANAGARI_TURN]
    assert result["text"] == DEVANAGARI_TURN
    assert result["words"][0]["text"] == "अ,"
