"""ElevenLabs Voice Isolator (0.0.5): off by default, the original never touched, holds that clear.

IDENTICAL IN BOTH PORTALS (``shared/transcription.sha256``). No network and no database: the
isolator is answered by a fake ``send``, and the service by fakes for the job table, S3 and consent.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import audio_isolation
from app.transcription import isolation

REPO = Path(__file__).resolve().parents[2]


class _Response:
    def __init__(self, status: int, content: bytes = b"", body: Any = None, ctype: str = "audio/mpeg") -> None:
        self.status_code = status
        self.content = content
        self._body = body
        self.headers = {"content-type": ctype}

    def json(self) -> Any:
        if self._body is None:
            raise ValueError("no json")
        return self._body


def _send(response: _Response, seen: list[dict[str, Any]]):
    def send(url: str, **kwargs: Any) -> _Response:
        seen.append({"url": url, **kwargs})
        return response

    return send


def test_the_isolator_is_asked_with_the_key_in_a_header_and_answers_audio(tmp_path: Path) -> None:
    clip = tmp_path / "clip.m4a"
    clip.write_bytes(b"\x00" * 32)
    seen: list[dict[str, Any]] = []
    out = isolation.isolate(str(clip), "clip.m4a", "audio/mp4", key="k-123", send=_send(_Response(200, b"clean"), seen))
    assert out.content == b"clean" and out.mime_type == "audio/mpeg"
    (call,) = seen
    assert call["url"] == isolation.URL and call["headers"] == {"xi-api-key": "k-123"}
    assert "audio" in call["files"]


@pytest.mark.parametrize(
    ("status", "body", "kind", "holds"),
    [
        (401, {"detail": {"status": "missing_permissions"}}, isolation.PERMISSION, False),
        (401, {"detail": {"status": "invalid_api_key"}}, isolation.KEY, False),
        (402, None, isolation.BILLING, True),
        (429, None, isolation.THROTTLED, True),
        (503, None, isolation.THROTTLED, True),
        (413, None, isolation.REJECTED, False),
        (500, None, isolation.FAILED, False),
    ],
)
def test_each_refusal_says_what_it_is_and_only_self_clearing_ones_hold(
    tmp_path: Path, status: int, body: Any, kind: str, holds: bool
) -> None:
    clip = tmp_path / "clip.wav"
    clip.write_bytes(b"\x00")
    with pytest.raises(isolation.IsolationError) as caught:
        isolation.isolate(str(clip), "clip.wav", "audio/wav", key="k", send=_send(_Response(status, body=body), []))
    assert caught.value.kind == kind
    assert bool(caught.value.hold_seconds) is holds


def test_the_cleaned_copy_keeps_the_file_name() -> None:
    assert (
        isolation.cleaned_leaf("media/u1/abc/FieldRepository_Interview_2026-07-01_Recording.m4a", "audio/mpeg")
        == "FieldRepository_Interview_2026-07-01_Recording.mp3"
    )
    assert isolation.cleaned_leaf("media/u1/abc/take", "audio/wav") == "take.wav"


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> SimpleNamespace:
    media = SimpleNamespace(
        id="m1", mediaType="AUDIO", objectKey="media/u1/abc/Recording.m4a", originalFilename="Recording.m4a",
        mimeType="audio/mp4", uploadedById="u1", cleanedObjectKey=None, removedAt=None,
    )
    state = SimpleNamespace(on=False, media=media, updates=[], created=[], puts=[], consent=(True, None), key="k")

    async def find_unique(*, where: dict[str, Any], **_kw: Any) -> Any:
        return state.media

    async def find_first(**_kw: Any) -> Any:
        return None

    async def create(*, data: dict[str, Any]) -> Any:
        state.created.append(data)
        return SimpleNamespace(id="job1", **data)

    async def update(*, where: dict[str, Any], data: dict[str, Any]) -> Any:
        state.updates.append(data)
        return SimpleNamespace(**data)

    db = SimpleNamespace(
        mediafile=SimpleNamespace(find_unique=find_unique, update=update),
        mediaprocessingjob=SimpleNamespace(find_first=find_first, create=create),
    )

    async def enabled() -> bool:
        return state.on

    async def may_send_audio(_media: Any) -> tuple[bool, str | None]:
        return state.consent

    async def get_secret(_name: str) -> str | None:
        return state.key

    clip = tmp_path / "dl.m4a"

    def download(key: str, *, suffix: str = "", max_bytes: Any = None) -> str:
        clip.write_bytes(b"\x01" * 16)
        return str(clip)

    def put(key: str, data: bytes, *, mime_type: str) -> None:
        state.puts.append((key, data, mime_type))

    from app.services import s3

    monkeypatch.setattr(audio_isolation, "db", db)
    monkeypatch.setattr(audio_isolation, "enabled", enabled)
    monkeypatch.setattr(audio_isolation.transcript_portal, "may_send_audio", may_send_audio)
    monkeypatch.setattr(audio_isolation.managed_secrets, "get_secret", get_secret)
    monkeypatch.setattr(s3, "download_to_temp", download)
    monkeypatch.setattr(s3, "put_object_bytes", put, raising=False)
    monkeypatch.setattr(
        isolation, "isolate", lambda path, name, mime, *, key: isolation.Isolated(b"clean-audio", "audio/mpeg")
    )
    return state


def test_nothing_is_queued_or_sent_while_it_is_switched_off(world: SimpleNamespace) -> None:
    assert asyncio.run(audio_isolation.enqueue_if_enabled("m1")) is None
    assert asyncio.run(audio_isolation.process(world.media)) == {"status": "SKIPPED", "reason": "off"}
    assert world.created == [] and world.puts == []


def test_when_on_one_job_is_queued_and_the_copy_is_stored_beside_the_original(world: SimpleNamespace) -> None:
    world.on = True
    job = asyncio.run(audio_isolation.enqueue_if_enabled("m1"))
    assert job is not None and world.created[0]["jobType"] == "AUDIO_ISOLATION"
    out = asyncio.run(audio_isolation.process(world.media))
    assert out["status"] == "COMPLETED"
    ((key, data, mime),) = world.puts
    assert key.startswith("media/u1/") and key.endswith("/Recording.mp3") and key != world.media.objectKey
    assert data == b"clean-audio" and mime == "audio/mpeg"
    (update,) = world.updates
    assert update["cleanedObjectKey"] == key and "objectKey" not in update, "the original is never replaced"


def test_a_refused_consent_sends_nothing(world: SimpleNamespace) -> None:
    world.on = True
    world.consent = (False, "refused")
    assert asyncio.run(audio_isolation.process(world.media))["status"] == "REFUSED"
    assert world.puts == []


def test_a_video_or_an_already_cleaned_recording_is_left_alone(world: SimpleNamespace) -> None:
    world.on = True
    world.media.mediaType = "VIDEO"
    assert asyncio.run(audio_isolation.enqueue_if_enabled("m1")) is None
    world.media.mediaType = "AUDIO"
    world.media.cleanedObjectKey = "media/u1/x/Recording.mp3"
    assert asyncio.run(audio_isolation.enqueue_if_enabled("m1")) is None


def test_the_switch_ships_off() -> None:
    sql = (REPO / "backend/prisma/migrations/20261115090000_audio_isolation/migration.sql").read_text(encoding="utf-8")
    assert '"audioIsolationEnabled" BOOLEAN NOT NULL DEFAULT false' in sql
    schema = (REPO / "backend/prisma/schema.prisma").read_text(encoding="utf-8")
    assert "audioIsolationEnabled        Boolean @default(false)" in schema
