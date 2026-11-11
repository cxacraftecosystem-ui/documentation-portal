from __future__ import annotations

import asyncio
import os
import shutil
import subprocess
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import transcription_admin
from app.services import (
    app_settings,
    media_queue,
    transcribe_choice,
    transcript_backfill,
    transcript_pipeline,
    transcription_jobs,
)
from app.transcription import audio as transcription_audio, workdir


@pytest.fixture
def video_clip(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv(workdir.ENV_DIR, str(tmp_path / "work"))
    clip = tmp_path / "Workshop_Interview.mp4"
    clip.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 64)
    return clip


class _Runner:
    def __init__(self, returncode: int = 0, sound: bytes = b"sound") -> None:
        self.calls: list[list[str]] = []
        self.returncode = returncode
        self.sound = sound

    def __call__(self, args: list[str]) -> subprocess.CompletedProcess[str]:
        self.calls.append(args)
        if self.returncode == 0:
            Path(args[-1]).write_bytes(self.sound)
        return subprocess.CompletedProcess(args, self.returncode, "", "")


def test_the_sound_is_taken_out_of_a_video_with_the_picture_dropped(video_clip: Path) -> None:
    runner = _Runner()
    target = transcription_audio.extract_to_workdir(str(video_clip), runner)
    assert target is not None and target.endswith(".m4a")
    (args,) = runner.calls
    assert args[0] == "ffmpeg" and str(video_clip) in args
    assert "-vn" in args and args[args.index("-map") + 1] == "0:a:0"
    assert Path(target).read_bytes() == b"sound"
    os.remove(target)


def test_a_video_without_sound_leaves_no_file_behind(video_clip: Path) -> None:
    runner = _Runner(returncode=1)
    assert transcription_audio.extract_to_workdir(str(video_clip), runner) is None
    leftovers = list((video_clip.parent / "work").glob("transcription_source_*"))
    assert leftovers == []


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed here")
def test_real_ffmpeg_extracts_the_sound_of_a_real_video(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(workdir.ENV_DIR, str(tmp_path / "work"))
    clip = tmp_path / "clip.mp4"
    made = subprocess.run(
        [
            "ffmpeg", "-nostdin", "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=size=64x64:rate=5:duration=1",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-shortest", "-c:v", "mpeg4", "-c:a", "aac", str(clip),
        ],
        capture_output=True,
        check=False,
    )
    assert made.returncode == 0, made.stderr
    target = transcription_audio.extract_to_workdir(str(clip))
    assert target is not None and os.path.getsize(target) > 0
    os.remove(target)


@pytest.fixture
def offline_chain(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    seen = SimpleNamespace(calls=[])

    class _Store:
        async def cache_get(self, *_a: Any) -> Any:
            return None

        async def cache_put(self, *_a: Any, **_k: Any) -> None:
            return None

        async def record_usage(self, *_a: Any, **_k: Any) -> None:
            return None

    async def no_hint(_media: Any) -> list[str]:
        return []

    monkeypatch.setattr(transcription_jobs, "PrismaTranscriptionStore", _Store)
    monkeypatch.setattr(transcription_jobs, "language_hint", no_hint)
    monkeypatch.setattr(transcription_jobs.ai, "transcription_options", lambda *_a, **_k: None)
    monkeypatch.setattr(transcription_jobs.dedup, "options_hash", lambda _opts: "options")
    monkeypatch.setattr(transcript_pipeline, "accepts_keyterms", lambda _fn: False)

    async def transcribe(content: Any, filename: str, mime_type: str, settings: Any, **kwargs: Any) -> dict[str, Any]:
        path = kwargs.get("source_path")
        seen.calls.append(
            {
                "content": content,
                "filename": filename,
                "mime": mime_type,
                "path": path,
                "bytes": Path(path).read_bytes() if path else None,
            }
        )
        return {"status": "FAILED", "message": "offline"}

    seen.transcribe = transcribe
    return seen


def _video(**extra: Any) -> SimpleNamespace:
    return SimpleNamespace(
        id="v1",
        mediaType="VIDEO",
        mimeType="video/mp4",
        originalFilename="Workshop_Interview.mp4",
        checksum="a" * 64,
        **extra,
    )


def test_a_video_reaches_the_provider_as_its_sound_only(
    video_clip: Path, offline_chain: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    runner = _Runner(sound=b"only the sound")
    monkeypatch.setattr(transcription_audio, "_run", runner)
    result = asyncio.run(
        transcription_jobs.transcribe_recording(
            _video(), None, source_path=str(video_clip), transcribe=offline_chain.transcribe
        )
    )
    assert result["status"] == "FAILED"
    (call,) = offline_chain.calls
    assert call["path"] != str(video_clip) and call["path"].endswith(".m4a")
    assert call["bytes"] == b"only the sound"
    assert call["filename"] == "Workshop_Interview.m4a"
    assert call["mime"] == "audio/mp4"
    assert not os.path.exists(call["path"]), "the extracted sound is removed after the send"
    assert video_clip.exists(), "the stored video itself is never touched"


def test_a_video_with_no_readable_sound_is_never_sent(
    video_clip: Path, offline_chain: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(transcription_audio, "_run", _Runner(returncode=1))
    result = asyncio.run(
        transcription_jobs.transcribe_recording(
            _video(), None, source_path=str(video_clip), transcribe=offline_chain.transcribe
        )
    )
    assert result["status"] == "UNAVAILABLE"
    assert result["message"] == transcription_jobs.NO_SOUND_MESSAGE
    assert offline_chain.calls == []


def test_audio_is_sent_as_it_was_recorded(
    video_clip: Path, offline_chain: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    runner = _Runner()
    monkeypatch.setattr(transcription_audio, "_run", runner)
    clip = video_clip.with_name("Recording.m4a")
    clip.write_bytes(b"voice")
    media = SimpleNamespace(
        id="a1", mediaType="AUDIO", mimeType="audio/mp4", originalFilename="Recording.m4a", checksum="b" * 64
    )
    asyncio.run(
        transcription_jobs.transcribe_recording(media, None, source_path=str(clip), transcribe=offline_chain.transcribe)
    )
    assert runner.calls == []
    assert offline_chain.calls[0]["path"] == str(clip)


@pytest.mark.parametrize(
    ("media_type", "requested", "expected"),
    [
        ("VIDEO", ["TRANSCRIPTION"], {"TRANSCRIPTION"}),
        ("AUDIO", ["TRANSCRIPTION"], {"TRANSCRIPTION"}),
        ("AUDIO", None, set()),
        ("VIDEO", None, set()),
        ("IMAGE", ["TRANSCRIPTION"], set()),
        ("PDF", ["TRANSCRIPTION"], set()),
    ],
)
def test_the_queue_gate_lets_video_through_and_never_adds_a_job_nobody_asked_for(
    media_type: str, requested: list[str] | None, expected: set[str]
) -> None:
    assert media_queue._job_requests(SimpleNamespace(mediaType=media_type), requested) == expected


@pytest.mark.parametrize(
    ("kind", "asked", "policy", "expected"),
    [
        ("VIDEO", False, {}, (False, None)),
        ("VIDEO", True, {}, (True, transcribe_choice.UPLOAD)),
        ("AUDIO", True, {}, (True, transcribe_choice.UPLOAD)),
        ("VIDEO", False, {"autoTranscribeVideo": True}, (True, transcribe_choice.AUTO_VIDEO)),
        ("AUDIO", False, {"autoTranscribeVideo": True}, (False, None)),
        ("AUDIO", False, {"autoTranscribeAudio": True}, (True, transcribe_choice.AUTO_AUDIO)),
        ("IMAGE", True, {"autoTranscribeAudio": True, "autoTranscribeVideo": True}, (False, None)),
    ],
)
def test_the_box_and_the_admin_switches_decide_together(
    kind: str, asked: bool, policy: dict[str, bool], expected: tuple[bool, str | None]
) -> None:
    assert transcribe_choice.decide(kind, asked, policy) == expected


@pytest.fixture
def fake_db(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    state = SimpleNamespace(media_updates=[], setting_updates=[], row=SimpleNamespace())

    async def media_update(*, where: dict[str, Any], data: dict[str, Any]) -> Any:
        state.media_updates.append((where["id"], data))
        return SimpleNamespace(**data)

    async def setting_update(*, where: dict[str, Any], data: dict[str, Any]) -> Any:
        state.setting_updates.append(data)
        for key, value in data.items():
            setattr(state.row, key, value)
        return state.row

    async def load() -> Any:
        return state.row

    async def get_or_create(_by: Any = None) -> Any:
        return state.row

    db = SimpleNamespace(
        mediafile=SimpleNamespace(update=media_update),
        appsetting=SimpleNamespace(update=setting_update),
    )
    monkeypatch.setattr(transcribe_choice, "db", db)
    monkeypatch.setattr(transcript_backfill, "db", db)
    monkeypatch.setattr(app_settings, "load_app_settings", load)
    monkeypatch.setattr(app_settings, "get_or_create_app_settings", get_or_create)
    return state


def test_the_choice_is_stored_on_the_row(fake_db: SimpleNamespace) -> None:
    video = SimpleNamespace(id="v1", mediaType="VIDEO")
    assert asyncio.run(transcribe_choice.resolve_upload(video, ["TRANSCRIPTION"])) == ["TRANSCRIPTION"]
    assert asyncio.run(transcribe_choice.resolve_upload(SimpleNamespace(id="v2", mediaType="VIDEO"), [])) == []
    (first, second) = fake_db.media_updates
    assert first[0] == "v1" and first[1]["transcribeRequested"] is True
    assert first[1]["transcribeRequestSource"] == transcribe_choice.UPLOAD
    assert first[1]["transcribeRequestedAt"] is not None
    assert second == ("v2", {"transcribeRequested": False, "transcribeRequestSource": None, "transcribeRequestedAt": None})


def test_an_auto_switch_queues_a_file_whose_box_was_left_off(fake_db: SimpleNamespace) -> None:
    fake_db.row.autoTranscribeAudio = True
    audio = SimpleNamespace(id="a1", mediaType="AUDIO")
    video = SimpleNamespace(id="v1", mediaType="VIDEO")
    assert asyncio.run(transcribe_choice.resolve_upload(audio, None)) == ["TRANSCRIPTION"]
    assert asyncio.run(transcribe_choice.resolve_upload(video, None)) == []
    assert fake_db.media_updates[0][1]["transcribeRequestSource"] == transcribe_choice.AUTO_AUDIO


def test_an_image_upload_is_left_alone(fake_db: SimpleNamespace) -> None:
    image = SimpleNamespace(id="i1", mediaType="IMAGE")
    assert asyncio.run(transcribe_choice.resolve_upload(image, ["MEASUREMENT"])) == ["MEASUREMENT"]
    assert fake_db.media_updates == []


def test_the_admin_switches_ship_off_and_save_what_was_asked(fake_db: SimpleNamespace) -> None:
    assert asyncio.run(transcribe_choice.policy()) == {"autoTranscribeVideo": False, "autoTranscribeAudio": False}
    saved = asyncio.run(transcribe_choice.save_switches(video=True, audio=None, updated_by_id="admin1"))
    assert saved == {"autoTranscribeVideo": True, "autoTranscribeAudio": False}
    assert fake_db.setting_updates == [{"autoTranscribeVideo": True, "updatedById": "admin1"}]


def test_the_backfill_switch_needs_the_estimate_confirmed(fake_db: SimpleNamespace) -> None:
    with pytest.raises(transcript_backfill.ConfirmationNeeded):
        asyncio.run(transcript_backfill.set_enabled(True, confirmed=False, updated_by_id="admin1"))
    assert fake_db.setting_updates == []
    assert asyncio.run(transcript_backfill.set_enabled(True, confirmed=True, updated_by_id="admin1")) is True
    assert fake_db.setting_updates[-1]["transcriptBackfillById"] == "admin1"


def test_the_settings_route_refuses_an_unconfirmed_backfill(
    fake_db: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def state() -> dict[str, Any]:
        return {}

    monkeypatch.setattr(transcription_admin, "_state", state)
    user = SimpleNamespace(id="admin1", email="admin@example.org")
    body = transcription_admin.TranscriptionSettingsUpdate(transcriptBackfill=True)
    with pytest.raises(HTTPException) as refused:
        asyncio.run(transcription_admin.update_transcription_settings(body, user))
    assert refused.value.status_code == 409
    assert not getattr(fake_db.row, "transcriptBackfillEnabled", False)


def test_the_backfill_refuses_to_run_while_its_switch_is_off(
    fake_db: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def tripwire(*_a: Any, **_k: Any) -> Any:
        raise AssertionError("the backfill queued a recording while switched off")

    monkeypatch.setattr(media_queue, "enqueue_media_processing_jobs", tripwire)
    monkeypatch.setattr(transcript_backfill, "collect", tripwire)
    fake_db.row.transcriptBackfillEnabled = False
    with pytest.raises(transcript_backfill.BackfillOff):
        asyncio.run(transcript_backfill.run_batch())
    assert asyncio.run(transcript_backfill.maybe_run(now=10**9)) is None


def test_the_backfill_waits_while_transcription_is_paused(
    fake_db: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def tripwire(*_a: Any, **_k: Any) -> Any:
        raise AssertionError("the backfill queued a recording while paused")

    monkeypatch.setattr(transcript_backfill, "collect", tripwire)
    fake_db.row.transcriptBackfillEnabled = True
    fake_db.row.transcriptionPaused = True
    assert asyncio.run(transcript_backfill.run_batch()) == {"queued": 0, "reason": "paused"}


def test_the_estimate_counts_hours_and_cost() -> None:
    c = transcript_backfill.Candidate
    summary = transcript_backfill.summarise(
        [c("a", transcript_backfill.FAILED, "2026-01-01", 90.0), c("b", transcript_backfill.UNTRANSCRIBED, "2026-02-01", 30.0)],
        refused=3,
    )
    assert summary["recordings"] == 2 and summary["hours"] == 2.0 and summary["leftOutForConsent"] == 3
    assert summary["costUsd"] == round(120 * (0.25 / 60 + 0.002), 2)


class _InTmpRunner(_Runner):
    def __init__(self, root: Path) -> None:
        super().__init__()
        self.root = str(root)

    def __call__(self, args: list[str]) -> subprocess.CompletedProcess[str]:
        self.calls.append(args)
        if args[-1].startswith(self.root):
            Path(args[-1]).write_bytes(self.sound)
        return subprocess.CompletedProcess(args, 0, "", "")


def test_every_ffmpeg_and_ffprobe_call_reads_one_local_file_within_caps(tmp_path: Path) -> None:
    runner = _InTmpRunner(tmp_path)
    hostile = "concat:/etc/passwd|/etc/hosts"
    assert transcription_audio.normalise_to_wav(hostile, str(tmp_path / "out.wav"), runner)
    transcription_audio.extract_audio_track("-filter_complex", str(tmp_path / "out.m4a"), runner)
    transcription_audio.probe_duration_ms(str(tmp_path / "x.wav"), runner)
    transcription_audio.detect_silences(str(tmp_path / "x.wav"), runner)
    plan = transcription_audio.PartPlan(0, 1, 0, 1000, 0)
    transcription_audio.cut_part(str(tmp_path / "x.wav"), plan, str(tmp_path), runner)
    assert len(runner.calls) == 5
    for args in runner.calls:
        assert args.count("-i") == 1
        source = args[args.index("-i") + 1]
        assert os.path.isabs(source) and not source.startswith("-")
        assert args.index("-protocol_whitelist") < args.index("-i")
        assert args[args.index("-protocol_whitelist") + 1] == "file"
        formats = args[args.index("-format_whitelist") + 1].split(",")
        assert "hls" not in formats and "concat" not in formats
    wav, track = runner.calls[0], runner.calls[1]
    assert wav[wav.index("-t") + 1] == str(transcription_audio.MAX_SOURCE_SECONDS)
    assert int(wav[wav.index("-fs") + 1]) == transcription_audio.MAX_WAV_BYTES
    assert track[track.index("-t") + 1] == str(transcription_audio.MAX_SOURCE_SECONDS)
    assert int(track[track.index("-fs") + 1]) == transcription_audio.MAX_TRACK_BYTES


def test_the_media_tools_refuse_a_remote_address_that_is_not_ours() -> None:
    with pytest.raises(transcription_audio.UnsafeInput):
        transcription_audio.remote_input("https://evil.example/a.m3u8", {"media-bucket.s3.amazonaws.com"})
    with pytest.raises(transcription_audio.UnsafeInput):
        transcription_audio.local_input("")
