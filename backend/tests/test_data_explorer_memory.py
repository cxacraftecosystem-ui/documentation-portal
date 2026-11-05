from __future__ import annotations

import asyncio
import shutil
import subprocess
from array import array
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import data_explorer
from app.services.data_explorer import overview, peaks, saved


def _pcm(values: list[int]) -> bytes:
    return array("h", values).tobytes()


def test_the_reducer_keeps_the_loudest_sample_of_every_block_across_chunk_edges():
    reducer = peaks._Reducer()
    samples = [0] * peaks.BLOCK * 3
    samples[10] = -30000
    samples[peaks.BLOCK + 5] = 1200
    raw = _pcm(samples)
    reducer.feed(raw[:33])
    reducer.feed(raw[33:])
    reducer.finish()
    assert list(reducer.blocks) == [30000, 1200, 0]
    assert reducer.samples == len(samples)


def test_bucket_always_gives_the_asked_number_of_values_between_zero_and_one():
    assert peaks.bucket([], 8) == [0.0] * 8
    few = peaks.bucket(array("H", [100, 400]), 8)
    assert len(few) == 8 and max(few) == 1.0 and min(few) == 0.25
    many = peaks.bucket(array("H", range(10000)), peaks.BUCKETS)
    assert len(many) == peaks.BUCKETS and many[-1] == 1.0
    assert all(0.0 <= v <= 1.0 for v in many)


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")
def test_decode_reads_a_real_recording(tmp_path):
    source = tmp_path / "tone.wav"
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=220:duration=3", str(source)],
        check=True,
    )
    values, duration = peaks.decode(str(source))
    assert len(values) == peaks.BUCKETS and max(values) == 1.0
    assert duration is not None and 2900 <= duration <= 3100


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")
def test_decode_refuses_what_is_not_sound(tmp_path):
    source = tmp_path / "notes.txt"
    source.write_text("not a recording", encoding="utf-8")
    with pytest.raises(peaks.DecodeFailed):
        peaks.decode(str(source))


def test_a_busy_decoder_says_so(monkeypatch):
    monkeypatch.setattr(peaks, "available", lambda: True)
    monkeypatch.setattr(peaks, "WAIT_SECONDS", 0.01)

    async def story() -> None:
        await peaks._slot.acquire()
        try:
            with pytest.raises(peaks.DecodeBusy):
                await peaks.peaks_of("x")
        finally:
            peaks._slot.release()

    asyncio.run(story())


def test_no_ffmpeg_is_a_decode_failure(monkeypatch):
    monkeypatch.setattr(peaks, "available", lambda: False)
    with pytest.raises(peaks.DecodeFailed):
        asyncio.run(peaks.peaks_of("x"))


def test_only_workshop_folders_have_an_overview():
    assert overview.workshop_id("by-workshop/w1") == "w1"
    for bad in ("", "by-workshop", "by-workshop/w1/crafts", "by-uploader/u1"):
        with pytest.raises(HTTPException) as refused:
            overview.workshop_id(bad)
        assert refused.value.status_code == 404


def test_artisan_of_reads_both_artisan_folder_shapes():
    assert overview.artisan_of("by-workshop/w/crafts/c/artisans/a1") == "a1"
    assert overview.artisan_of("by-workshop/w/crafts/c/artisans/a1/questionnaire/i") == "a1"
    assert overview.artisan_of("by-workshop/w/artisans/a2/products") == "a2"
    assert overview.artisan_of("by-workshop/w/crafts/c") is None
    assert overview.artisan_of("by-workshop/w/crafts/c/artisans") is None


def _q(qid: str, code: str, title: str, prompt: str, lineage: str | None = None) -> Any:
    return SimpleNamespace(id=qid, sectionCode=code, sectionTitle=title, prompt=prompt, lineageId=lineage)


def test_coverage_marks_each_section_complete_partial_or_missing():
    sections = overview.sections_of(
        [
            _q("q1", "A", "About", "Name?"),
            _q("q2", "A", "About", "Age?", lineage="L2"),
            _q("q3", "B", "Craft", "Tools?"),
            *[_q(f"m{i}", "C", "Market", f"Market {i}?") for i in range(7)],
        ]
    )
    assert [s["key"] for s in sections] == ["A", "B", "C"]
    fields, states = overview.coverage(sections, {"q1", "L2", "m0"})
    assert fields == {"filled": 3, "total": 10}
    by_key = {s["key"]: s for s in states}
    assert by_key["A"] == {"key": "A", "state": "complete", "missing": []}
    assert by_key["B"]["state"] == "missing" and by_key["B"]["missing"] == ["Tools?"]
    assert by_key["C"]["state"] == "partial" and len(by_key["C"]["missing"]) == overview.MISSING_MAX


def test_transcript_and_review_counts():
    rows = [
        {"mediaType": "AUDIO", "transcriptStatus": "COMPLETED", "hasText": True, "translating": False},
        {"mediaType": "VIDEO", "transcriptStatus": None, "hasText": True, "translating": True},
        {"mediaType": "AUDIO", "transcriptStatus": "FAILED", "hasText": False, "translating": False},
        {"mediaType": "AUDIO", "transcriptStatus": "QUEUED", "hasText": False, "translating": False},
        {"mediaType": "AUDIO", "transcriptStatus": None, "hasText": False, "translating": False},
        {"mediaType": "IMAGE", "transcriptStatus": None, "hasText": False, "translating": False},
    ]
    assert overview.transcript_counts(rows) == {"total": 5, "ready": 1, "translating": 2, "failed": 1}
    assert overview.review_counts(["APPROVED", "RecordStatus.PENDING", "NEEDS_REVISION", "REJECTED", "", None]) == {
        "approved": 1,
        "pending": 2,
        "total": 4,
    }


def test_view_state_is_checked_and_normalised():
    state = saved.state_of({"view": "tables", "sheet": " Tools ", "q": "", "filters": {"type": "audio", "n": 3, "x": None}})
    assert state == {"view": "tables", "sheet": "Tools", "q": None, "filters": {"type": "audio", "n": "3"}}
    assert saved.state_of(None) == {"view": "files", "sheet": None, "q": None, "filters": {}}
    for bad in ({"view": "map"}, {"filters": []}, {"filters": {"a": {"b": 1}}}, {"q": "x" * 500}, {"filters": {"a": True}}):
        with pytest.raises(saved.Invalid):
            saved.state_of(bad)
    assert saved.name_of("  Kamla   tables ") == "Kamla tables"
    for bad_name in ("", "   ", None, "x" * 200):
        with pytest.raises(saved.Invalid):
            saved.name_of(bad_name)
    with pytest.raises(saved.Invalid):
        saved.shared_of("yes")


class _Views:
    def __init__(self, row: Any) -> None:
        self.row = row

    async def find_unique(self, **_kwargs: Any) -> Any:
        return self.row


def _row(owner: str, shared: bool) -> Any:
    from datetime import UTC, datetime

    now = datetime(2026, 11, 5, tzinfo=UTC)
    return SimpleNamespace(
        id="v1", ownerId=owner, name="Tables", path="by-workshop/w1", state={"view": "tables"},
        shared=shared, owner=SimpleNamespace(name="Asha"), createdAt=now, updatedAt=now,
    )


def _open(monkeypatch: pytest.MonkeyPatch, row: Any, visible: bool, user: str) -> Any:
    monkeypatch.setattr(data_explorer, "db", SimpleNamespace(datasavedview=_Views(row)))

    async def scope(_user: Any) -> Any:
        return SimpleNamespace(records={}, media={})

    async def level(path: str, _scope: Any) -> Any:
        if not visible:
            raise HTTPException(status_code=404, detail="Unknown data path")
        return [], None

    browser = SimpleNamespace(_scope_for=scope, _list_level=level)
    monkeypatch.setattr(data_explorer, "_browser", lambda: browser)
    return asyncio.run(data_explorer.read_view("v1", current_user=SimpleNamespace(id=user)))


def test_a_view_opens_for_its_owner_or_for_anyone_who_sees_the_shared_folder(monkeypatch):
    assert _open(monkeypatch, _row("me", False), False, "me")["mine"] is True
    assert _open(monkeypatch, _row("me", True), True, "you")["mine"] is False
    for shared, visible in ((False, True), (True, False)):
        with pytest.raises(HTTPException) as refused:
            _open(monkeypatch, _row("me", shared), visible, "you")
        assert refused.value.status_code == 404
    with pytest.raises(HTTPException):
        _open(monkeypatch, None, True, "me")
