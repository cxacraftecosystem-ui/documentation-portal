"""This portal's wiring of the shared transcription service: pause, providers, dedup, budget, consent.

No database and no provider: ``db`` is replaced with small fakes, and ``requests.post`` with a
tripwire wherever a test claims that nothing is sent. The same cases exist in designer-portal's
``tests/test_transcription_service_portal.py`` against that portal's own consent rule.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
import requests

from app.services import ai, app_settings, media_queue, recording_consent, transcription_jobs


@pytest.fixture(autouse=True)
def _ledger_says_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    """These tests are about RecordingConsent, the pause and the queue; the questionnaire's consent
    ledger (the other store recording_consent asks) answers "nothing recorded" unless a test says
    otherwise, and the insights hold is lifted so only the pause decides what the drain selects."""

    async def _awaiting(*_a: Any, **_k: Any) -> str:
        return "AWAITING"

    monkeypatch.setattr(recording_consent.ledger, "verdict", _awaiting)
    monkeypatch.setattr(media_queue.transcript_insights, "enabled", lambda: True)


def _never(*_a: Any, **_k: Any) -> Any:
    raise AssertionError("a provider was called")


# --- The pause ---------------------------------------------------------------------------------


def test_a_missing_settings_row_reads_as_paused_and_a_row_can_unpause() -> None:
    assert app_settings.transcription_paused(None) is True
    assert app_settings.transcription_paused(SimpleNamespace(transcriptionPaused=True)) is True
    assert app_settings.transcription_paused(SimpleNamespace(transcriptionPaused=False)) is False


def test_while_paused_the_drain_never_selects_a_transcription_job(monkeypatch) -> None:
    seen: list[dict[str, Any]] = []

    class _Jobs:
        async def find_many(self, **kwargs: Any) -> list[Any]:
            seen.append(kwargs["where"])
            return []

    async def _row(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(transcriptionPaused=True, batchWindowEnabled=False)

    async def _recover() -> int:
        return 0

    monkeypatch.setattr(media_queue, "db", SimpleNamespace(mediaprocessingjob=_Jobs()))
    monkeypatch.setattr(media_queue, "load_app_settings", _row)
    monkeypatch.setattr(media_queue, "recover_stale_processing_jobs", _recover)
    monkeypatch.setattr(requests, "post", _never)
    asyncio.run(
        media_queue.process_next_media_jobs(
            limit=5, settings=SimpleNamespace(media_queue_batch_size=5)
        )
    )
    assert seen and seen[0]["jobType"] == {"not": media_queue.TRANSCRIPTION}


def test_unpaused_and_inside_the_window_transcription_is_selected(monkeypatch) -> None:
    seen: list[dict[str, Any]] = []

    class _Jobs:
        async def find_many(self, **kwargs: Any) -> list[Any]:
            seen.append(kwargs["where"])
            return []

    async def _row(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(transcriptionPaused=False, batchWindowEnabled=False)

    async def _recover() -> int:
        return 0

    monkeypatch.setattr(media_queue, "db", SimpleNamespace(mediaprocessingjob=_Jobs()))
    monkeypatch.setattr(media_queue, "load_app_settings", _row)
    monkeypatch.setattr(media_queue, "recover_stale_processing_jobs", _recover)
    monkeypatch.setattr(media_queue, "_rate_limit_cooldown_until", None)
    asyncio.run(
        media_queue.process_next_media_jobs(
            limit=5, settings=SimpleNamespace(media_queue_batch_size=5)
        )
    )
    assert "jobType" not in seen[0]


def test_transcribe_now_refuses_while_paused_and_sends_nothing(monkeypatch) -> None:
    async def _row(*_a: Any, **_k: Any) -> Any:
        return None  # no settings row at all: paused

    class _Gate:
        async def verdict(self, _media: Any) -> Any:
            return SimpleNamespace(may_send=True, refusal=None)

    monkeypatch.setattr(media_queue.recording_consent, "gate", _Gate())
    monkeypatch.setattr(media_queue, "load_app_settings", _row)
    monkeypatch.setattr(media_queue, "transcribe_audio_bytes", _never)
    monkeypatch.setattr(requests, "post", _never)
    with pytest.raises(transcription_jobs.TranscriptionPaused) as caught:
        asyncio.run(
            media_queue.transcribe_media_now(
                SimpleNamespace(id="m-1", objectKey="k"), SimpleNamespace()
            )
        )
    assert str(caught.value) == "Transcription is paused."


def test_the_paused_answer_is_a_calm_unavailable() -> None:
    answer = transcription_jobs.paused_result()
    assert answer["status"] == "UNAVAILABLE" and answer["paused"] is True
    assert answer["message"] == "Transcription is paused."


# --- Providers ---------------------------------------------------------------------------------


def test_muse_is_off_by_default_even_with_a_key(monkeypatch) -> None:
    monkeypatch.setattr(ai.managed_secrets, "peek_secret", lambda name: "k")
    order = ["muse", "elevenlabs", "deepgram", "whisper"]
    assert ai.transcription_provider_chain(order=order) == ["elevenlabs", "deepgram", "whisper"]
    assert ai.transcription_provider_chain(order=order, disabled=set())[0] == "muse"
    assert app_settings.stt_providers_disabled(None) == {"muse"}


def test_an_older_client_may_save_a_ranking_without_muse() -> None:
    assert app_settings.invalid_stt_provider_order(["deepgram", "elevenlabs", "whisper"]) is None
    assert (
        app_settings.stt_provider_order(SimpleNamespace(sttProviderOrder=["deepgram"]))[-1]
        == "muse"
    )
    assert "missing" in (app_settings.invalid_stt_provider_order(["deepgram", "whisper"]) or "")


def test_the_catalogue_names_muse_for_both_clients() -> None:
    assert ("muse", "Meta Muse", "META_MODEL_API_KEY") in ai.transcription_provider_catalog()


# --- One open job per clip ---------------------------------------------------------------------


def test_enqueue_reuses_the_open_job_instead_of_paying_twice(monkeypatch) -> None:
    existing = SimpleNamespace(id="job-open")
    created: list[Any] = []

    class _Jobs:
        async def find_first(self, **_kwargs: Any) -> Any:
            return existing

        async def create(self, **kwargs: Any) -> Any:
            created.append(kwargs)
            return SimpleNamespace(id="job-new")

    class _Media:
        async def update(self, **_kwargs: Any) -> Any:
            return None

    class _Gate:
        async def verdict(self, _media: Any) -> Any:
            return SimpleNamespace(may_send=True, refusal=None)

    monkeypatch.setattr(media_queue.recording_consent, "gate", _Gate())
    monkeypatch.setattr(
        media_queue, "db", SimpleNamespace(mediaprocessingjob=_Jobs(), mediafile=_Media())
    )
    jobs = asyncio.run(
        media_queue.enqueue_media_processing_jobs(
            {"id": "m-1", "mediaType": "AUDIO"},
            None,
            "u-1",
            SimpleNamespace(media_queue_job_max_attempts=3),
        )
    )
    assert jobs == [existing]
    assert created == []


# --- Budget: wait for the window, never fail ----------------------------------------------------


def test_a_budget_deferral_requeues_without_spending_an_attempt(monkeypatch) -> None:
    updates: list[dict[str, Any]] = []

    class _Rows:
        async def update(self, **kwargs: Any) -> Any:
            updates.append(kwargs)

    async def _row(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(transcriptionPaused=False, batchWindowEnabled=False)

    monkeypatch.setattr(
        media_queue, "db", SimpleNamespace(mediaprocessingjob=_Rows(), mediafile=_Rows())
    )
    monkeypatch.setattr(media_queue, "load_app_settings", _row)
    job = SimpleNamespace(id="job-1", mediaFileId="m-1", attempts=2)
    asyncio.run(
        media_queue._apply_transcription_result(
            job, {"status": "RATE_LIMITED", "reason": "budget", "message": "The budget is used up."}
        )
    )
    job_write = next(u for u in updates if u["where"] == {"id": "job-1"})
    assert job_write["data"]["status"] == media_queue.QUEUED
    assert job_write["data"]["attempts"] == 1
    assert job_write["data"]["runAfter"] > datetime.now(UTC)


# --- Dedup: same bytes, one provider call --------------------------------------------------------


def test_a_cache_hit_copies_the_transcript_and_calls_no_provider(monkeypatch, tmp_path) -> None:
    audio = tmp_path / "clip.m4a"
    audio.write_bytes(b"same bytes")
    hit = SimpleNamespace(
        provider="deepgram",
        model="nova-3",
        text="ringal ki tokri",
        segments=[],
        language="multi",
        speakers=1,
    )

    class _Store:
        async def cache_get(self, sha: str, options: str) -> Any:
            return hit

        async def record_usage(self, *_a: Any, **_k: Any) -> None:
            return None

    class _Media:
        async def update(self, **_kwargs: Any) -> Any:
            return None

    monkeypatch.setattr(transcription_jobs, "PrismaTranscriptionStore", _Store)
    monkeypatch.setattr(transcription_jobs, "db", SimpleNamespace(mediafile=_Media()))
    result = asyncio.run(
        transcription_jobs.transcribe_recording(
            SimpleNamespace(
                id="m-1", originalFilename="clip.m4a", mimeType="audio/mp4", checksum=None
            ),
            SimpleNamespace(),
            source_path=str(audio),
            transcribe=_never,
        )
    )
    assert result["status"] == "COMPLETED" and result["cacheHit"] is True
    assert result["text"] == "ringal ki tokri"
    assert transcription_jobs.transcript_extras(result)["transcriptProvider"] == "cache"


def test_a_job_throttled_three_times_by_one_provider_skips_it() -> None:
    job = SimpleNamespace(result={"throttles": {"elevenlabs": 3, "deepgram": 1}})
    assert transcription_jobs.skipped_providers(job) == {"elevenlabs"}


def test_the_language_hint_prefers_the_recording_then_the_state() -> None:
    from app.transcription import languages

    hinted = SimpleNamespace(extraMetadata={"languageHint": "Odia"}, locationId=None)
    assert asyncio.run(transcription_jobs.language_hint(hinted)) == ["Odia"]
    assert languages.bias_for_state("Orissa") == ["Odia", "English"]
    assert languages.bias_for_state("Nowhere") == []




# --- The consent rule: per recording, else per artisan, else not sent -------------------------


class _Consents:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_first(self, *, where: dict[str, Any], **_kwargs: Any) -> Any:
        for row in reversed(self.rows):
            if all(getattr(row, key, None) == value for key, value in where.items()):
                return row
        return None


def _consent(**fields: Any) -> Any:
    return SimpleNamespace(**{"mediaFileId": None, "artisanId": None, **fields})


def _verdict(monkeypatch, rows: list[Any], media: Any) -> Any:
    monkeypatch.setattr(recording_consent, "db", SimpleNamespace(recordingconsent=_Consents(rows)))
    return asyncio.run(recording_consent.gate.verdict(media))


def test_a_recordings_own_answer_wins_over_its_artisans(monkeypatch) -> None:
    media = SimpleNamespace(id="m-1", artisanId="a-1")
    rows = [_consent(artisanId="a-1", decision="GRANTED"), _consent(mediaFileId="m-1", decision="REFUSED")]
    verdict = _verdict(monkeypatch, rows, media)
    assert not verdict.may_send and verdict.decision == "REFUSED"


def test_an_artisans_answer_covers_every_recording_of_them(monkeypatch) -> None:
    media = SimpleNamespace(id="m-2", artisanId="a-1")
    assert _verdict(monkeypatch, [_consent(artisanId="a-1", decision="GRANTED")], media).may_send


def test_no_answer_is_not_recorded_and_is_not_sent(monkeypatch) -> None:
    verdict = _verdict(monkeypatch, [], SimpleNamespace(id="m-3", artisanId=None))
    assert not verdict.may_send and verdict.decision == "NOT_RECORDED"
    assert verdict.refusal.startswith("Consent not recorded.")


def test_a_failed_read_refuses_rather_than_sends(monkeypatch) -> None:
    class _Broken:
        async def find_first(self, **_kwargs: Any) -> Any:
            raise RuntimeError("database blinked")

    monkeypatch.setattr(recording_consent, "db", SimpleNamespace(recordingconsent=_Broken()))
    assert not asyncio.run(recording_consent.gate.verdict(SimpleNamespace(id="m-4", artisanId="a-1"))).may_send


def test_enqueue_keeps_the_recording_and_notes_why_it_was_not_sent(monkeypatch) -> None:
    created: list[Any] = []
    notes: list[Any] = []

    class _Jobs:
        async def find_first(self, **_kwargs: Any) -> Any:
            return None

        async def create(self, **kwargs: Any) -> Any:
            created.append(kwargs)
            return SimpleNamespace(id="job-new")

    class _Media:
        async def update(self, **kwargs: Any) -> Any:
            notes.append(kwargs)

    class _Gate:
        async def verdict(self, _media: Any) -> Any:
            return recording_consent.verdict_for(None)

    monkeypatch.setattr(media_queue.recording_consent, "gate", _Gate())
    monkeypatch.setattr(media_queue, "db", SimpleNamespace(mediaprocessingjob=_Jobs(), mediafile=_Media()))
    monkeypatch.setattr(requests, "post", _never)
    jobs = asyncio.run(
        media_queue.enqueue_media_processing_jobs(
            {"id": "m-1", "mediaType": "AUDIO"}, None, "u-1", SimpleNamespace(media_queue_job_max_attempts=3)
        )
    )
    assert jobs == [] and created == []
    assert notes[0]["data"]["transcriptStatus"] == media_queue.FAILED
    assert notes[0]["data"]["transcriptError"].startswith("Consent not recorded.")


# --- Consent backfill (owner attestation): idempotent, flags nothing, sends nothing ----------


def _load_backfill():
    import importlib.util
    from pathlib import Path

    path = Path(__file__).resolve().parents[1] / "scripts" / "backfill_recording_consent_owner_attestation.py"
    spec = importlib.util.spec_from_file_location("backfill_recording_consent_owner_attestation", path)
    module = importlib.util.module_from_spec(spec)
    import sys

    sys.modules[spec.name] = module  # dataclasses resolve their module through sys.modules
    spec.loader.exec_module(module)
    return module


def test_the_consent_backfill_covers_each_uncovered_recording_once(monkeypatch) -> None:
    backfill = _load_backfill()
    consents = [_consent(artisanId="a-9", decision="GRANTED"), _consent(mediaFileId="m-2", decision="REFUSED")]
    media = [
        SimpleNamespace(id="m-1", artisanId=None),
        SimpleNamespace(id="m-2", artisanId=None),
        SimpleNamespace(id="m-3", artisanId="a-9"),
    ]

    class _Consents:
        async def find_many(self, *, where: dict[str, Any]) -> list[Any]:
            if "mediaFileId" in where and where["mediaFileId"] == {"not": None}:
                return [c for c in consents if c.mediaFileId]
            return [c for c in consents if c.artisanId and not c.mediaFileId]

        async def create_many(self, *, data: list[dict[str, Any]]) -> int:
            for row in data:
                consents.append(_consent(**row))
            return len(data)

    class _Media:
        async def find_many(self, *, skip: int = 0, **_kwargs: Any) -> list[Any]:
            return media[skip:]

    monkeypatch.setattr(backfill, "db", SimpleNamespace(recordingconsent=_Consents(), mediafile=_Media()))
    monkeypatch.setattr(requests, "post", _never)
    assert asyncio.run(backfill.recordings_without_consent()) == ["m-1"]
