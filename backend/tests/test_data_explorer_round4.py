from __future__ import annotations

import asyncio
import hashlib
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import data_explorer
from app.services.data_explorer import isolate, overview, report_tools, walk

USER = SimpleNamespace(id="u1", role="MASTER_ADMIN", canDownloadDataset=True)


def test_search_numbers_each_match_by_its_place_in_the_whole_sheet():
    sheet = {
        "name": "Tools",
        "columns": ["Tool"],
        "rows": [["loom"], ["needle"], ["loom frame"], ["Note: capped"]],
        "noteRow": True,
        "prose": [],
    }
    (found,) = report_tools.search([sheet], "loom")
    assert found["rows"] == [["loom"], ["loom frame"]]
    assert found["rowNumbers"] == [1, 3]
    (capped,) = report_tools.search([sheet], "loom", limit=1)
    assert capped["rowNumbers"] == [1] and len(capped["rows"]) == 1


def test_each_section_links_to_the_sitting_that_answered_it():
    sections = overview.sections_of(
        [
            SimpleNamespace(id="q1", sectionCode="A", sectionTitle="About", prompt="Name?", lineageId=None),
            SimpleNamespace(id="q2", sectionCode="B 2", sectionTitle="Craft", prompt="Tools?", lineageId=None),
        ]
    )
    _fields, states = overview.coverage(sections, {"q2"})
    overview.link_sections(states, sections, ["new", "old"], {"old": {"q2"}})
    assert states[0]["href"] == "/questionnaire/sessions/new/view?section=A"
    assert states[1]["href"] == "/questionnaire/sessions/old/view?section=B%202"
    assert states[0]["key"] == "A" and states[1]["key"] == "B 2"
    _fields, lonely = overview.coverage(sections, set())
    overview.link_sections(lonely, sections, [], {})
    assert [s["href"] for s in lonely] == [None, None]


def test_manifest_lines_carry_a_digest_the_client_can_check(monkeypatch):
    good = "a" * 64

    async def query_raw(_sql: str, ids: list[str]) -> list[dict[str, Any]]:
        rows = {
            "m1": {"id": "m1", "contentSha256": good.upper(), "checksum": None},
            "m2": {"id": "m2", "contentSha256": None, "checksum": "b" * 64},
            "m3": {"id": "m3", "contentSha256": "not-a-digest", "checksum": None},
        }
        return [rows[i] for i in ids if i in rows]

    import app.core.db as core_db

    monkeypatch.setattr(core_db, "db", SimpleNamespace(query_raw=query_raw))
    files = [
        {"mediaId": "m1", "mediaType": "IMAGE", "sizeBytes": 10, "path": "a.jpg"},
        {"mediaId": "m2", "mediaType": "AUDIO", "sizeBytes": 20, "path": "b.mp4", "convertToMp4": True},
        {"mediaId": "m3", "mediaType": "VIDEO", "sizeBytes": 30, "path": "c.mp4"},
        {"mediaId": "m1", "content": "नील", "path": "a.md"},
        None,
    ]
    asyncio.run(walk.fill_digests(files))
    assert files[0]["sha256"] == good and files[0]["sizeBytes"] == 10
    assert files[1]["sha256"] is None and files[1]["originalSha256"] == "b" * 64
    assert files[2]["sha256"] is None
    raw = "नील".encode()
    assert files[3]["sha256"] == hashlib.sha256(raw).hexdigest() and files[3]["sizeBytes"] == len(raw)


def _media(**extra: Any) -> Any:
    base = {
        "id": "m1", "mediaType": "AUDIO", "objectKey": "media/u1/x/talk.m4a", "mimeType": "audio/mp4",
        "uploadedById": "u1", "removedAt": None, "cleanedObjectKey": None, "cleanedMimeType": None,
        "originalFilename": "talk.m4a",
    }
    return SimpleNamespace(**{**base, **extra})


def _switches(monkeypatch: pytest.MonkeyPatch, *, on: bool, key: bool, consent: bool = True) -> list[str]:
    from app.services import audio_isolation, managed_secrets, transcript_portal

    queued: list[str] = []

    async def enabled() -> bool:
        return on

    async def send(_media: Any) -> tuple[bool, str | None]:
        return consent, None if consent else "Consent was refused."

    async def enqueue(media_id: str) -> Any:
        queued.append(media_id)

    monkeypatch.setattr(audio_isolation, "enabled", enabled)
    monkeypatch.setattr(audio_isolation, "enqueue_if_enabled", enqueue)
    monkeypatch.setattr(managed_secrets, "peek_secret", lambda _name: "k" if key else None)
    monkeypatch.setattr(transcript_portal, "may_send_audio", send)
    return queued


class _Jobs:
    def __init__(self, queued: list[str]) -> None:
        self.queued = queued

    async def find_first(self, **_kwargs: Any) -> Any:
        return SimpleNamespace(status="QUEUED") if self.queued else None


def test_clear_voices_is_offered_only_when_switched_on_keyed_and_consented(monkeypatch):
    owner = SimpleNamespace(id="u1", role="RESEARCHER")
    stranger = SimpleNamespace(id="u2", role="RESEARCHER")
    _switches(monkeypatch, on=True, key=True)
    assert asyncio.run(isolate.can_isolate(owner, _media())) is True
    assert asyncio.run(isolate.can_isolate(stranger, _media())) is False
    assert asyncio.run(isolate.can_isolate(owner, _media(cleanedObjectKey="media/u1/y/clean.mp3"))) is False
    assert asyncio.run(isolate.can_isolate(owner, _media(mediaType="VIDEO"))) is False
    _switches(monkeypatch, on=False, key=True)
    assert asyncio.run(isolate.can_isolate(owner, _media())) is False
    _switches(monkeypatch, on=True, key=False)
    assert asyncio.run(isolate.can_isolate(owner, _media())) is False
    _switches(monkeypatch, on=True, key=True, consent=False)
    assert asyncio.run(isolate.can_isolate(owner, _media())) is False


def test_asking_for_clear_voices_queues_one_job_or_says_no(monkeypatch):
    owner = SimpleNamespace(id="u1", role="RESEARCHER")
    queued = _switches(monkeypatch, on=True, key=True)
    monkeypatch.setattr(isolate, "db", SimpleNamespace(mediaprocessingjob=_Jobs(queued)))
    assert asyncio.run(isolate.request(owner, _media())) == {"status": "queued", "isolated": False}
    assert queued == ["m1"]
    ready = asyncio.run(isolate.request(owner, _media(cleanedObjectKey="media/u1/y/clean.mp3")))
    assert ready == {"status": "ready", "isolated": True}
    _switches(monkeypatch, on=False, key=True)
    with pytest.raises(HTTPException) as refused:
        asyncio.run(isolate.request(owner, _media()))
    assert refused.value.status_code == 403


def test_the_isolated_copy_is_signed_inline_or_is_a_404(monkeypatch):
    signed: list[tuple[str, str]] = []

    def sign(key: str, *, filename: str, mime_type: str, inline: bool = False) -> tuple[str, int]:
        signed.append((key, mime_type))
        assert inline
        return f"https://s3/{key}", 60

    monkeypatch.setattr(isolate.signing, "signed_get", sign)
    answer = isolate.isolated_redirect(_media(cleanedObjectKey="media/u1/y/clean.mp3", cleanedMimeType="audio/mpeg"))
    assert answer.status_code == 307 and answer.headers["location"] == "https://s3/media/u1/y/clean.mp3"
    assert signed == [("media/u1/y/clean.mp3", "audio/mpeg")]
    with pytest.raises(HTTPException) as missing:
        isolate.isolated_redirect(_media())
    assert missing.value.status_code == 404


def test_peaks_say_whether_an_isolated_copy_exists(monkeypatch):
    async def media_for(_user: Any, _media_id: str, **_kwargs: Any) -> Any:
        return _media(cleanedObjectKey="media/u1/y/clean.mp3")

    class _Waves:
        async def find_unique(self, **_kwargs: Any) -> Any:
            return SimpleNamespace(peaks=[0.1, 0.9], durationMs=1000)

    async def no(_user: Any, _media: Any) -> bool:
        return False

    monkeypatch.setattr(data_explorer, "media_for", media_for)
    monkeypatch.setattr(data_explorer, "db", SimpleNamespace(mediawaveform=_Waves()))
    monkeypatch.setattr(isolate, "can_isolate", no)
    body = asyncio.run(data_explorer.media_peaks("m1", current_user=USER))
    assert body == {"peaks": [0.1, 0.9], "durationMs": 1000, "isolated": True, "canIsolate": False}
