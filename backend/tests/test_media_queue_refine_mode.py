import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import media_queue

RAW = "**Interviewer:** what dye is that?\n**Interviewee:** Indigo.\n"


def _run(monkeypatch, mode: str) -> tuple[dict[str, Any], list[Any]]:
    applied: dict[str, Any] = {}
    calls: list[Any] = []

    async def _not_paused() -> bool:
        return False

    async def _verdict(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(may_send=True, refusal=None)

    async def _transcribe(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {"status": "COMPLETED", "text": RAW}

    async def _refine(*a: Any, **_k: Any) -> dict[str, Any]:
        calls.append(a)
        return {"status": "COMPLETED", "refined": "**Interviewer:** ...refined..."}

    async def _apply(_job: Any, result: dict[str, Any]) -> None:
        applied.update(result)

    async def _settings(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(media_queue.transcription, "is_paused", _not_paused)
    monkeypatch.setattr(media_queue.recording_consent.gate, "verdict", _verdict)
    monkeypatch.setattr(media_queue, "download_to_temp", lambda _key, **_kw: "/tmp/fake-audio")
    monkeypatch.setattr(media_queue, "discard_temp", lambda _path: None)
    monkeypatch.setattr(media_queue.transcription_jobs, "transcribe_recording", _transcribe)
    monkeypatch.setattr(media_queue, "load_app_settings", _settings)
    monkeypatch.setattr(media_queue, "transcription_mode", lambda _s: mode)
    monkeypatch.setattr(media_queue, "refine_transcript_text", _refine)
    monkeypatch.setattr(media_queue, "_apply_transcription_result", _apply)

    job = SimpleNamespace(
        id="job-1",
        jobType=media_queue.TRANSCRIPTION,
        mediaFileId="m-1",
        attempts=0,
        requestedById=None,
        mediaFile=SimpleNamespace(
            objectKey="k", originalFilename="i.m4a", mimeType="audio/mp4", id="m-1", uploadedById=None
        ),
    )
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    return applied, calls


@pytest.mark.parametrize("mode", ["RAW", "REFINED"])
def test_transcribe_only_modes_never_pay_for_a_refinement(monkeypatch, mode):
    applied, calls = _run(monkeypatch, mode)
    assert calls == []
    assert applied["text"] == RAW
    assert "formattedTranscript" not in applied


def test_transcribe_and_translate_still_refines(monkeypatch):
    applied, calls = _run(monkeypatch, "REFINED_TRANSLATED")
    assert len(calls) == 1 and calls[0][1] is True
    assert applied["formattedTranscript"] == "**Interviewer:** ...refined..."
