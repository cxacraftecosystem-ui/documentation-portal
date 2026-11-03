import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import ValidationError


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _MediaDelegate:
    def __init__(self, row):
        self.row = row
        self.updates: list[dict[str, Any]] = []

    async def update(self, **kwargs):
        self.updates.append(kwargs)
        return self.row


def test_posting_a_transcript_stamps_the_server_clock_and_the_caller(monkeypatch):
    from app.api.routes import media

    stored = _Row(id="med_1", uploadedById="usr_7")
    delegate = _MediaDelegate(stored)
    caller = _Row(id="usr_7", role="RESEARCHER")

    async def _require_record(_delegate, _record_id):
        return stored

    async def _public(row, _viewer=None, **_kwargs):
        return row

    monkeypatch.setattr(media, "db", SimpleNamespace(mediafile=delegate))
    monkeypatch.setattr(media, "require_record", _require_record)
    monkeypatch.setattr(media, "_public", _public)

    before = datetime.now(UTC)
    asyncio.run(
        media.set_media_transcript(
            "med_1", SimpleNamespace(text="The dye is prepared the night before."), caller
        )
    )

    assert len(delegate.updates) == 1
    call = delegate.updates[0]
    written = call["data"]
    assert written["transcriptText"] == "The dye is prepared the night before."
    assert written["transcriptStatus"] == "COMPLETED"
    assert written["transcriptError"] is None
    stamped = written["transcriptEditedAt"]
    assert isinstance(stamped, datetime)
    assert abs(stamped - before) < timedelta(seconds=5)
    assert written["transcriptEditedById"] == "usr_7"
    assert call["include"] is media.INCLUDE, (
        "the transcript update lost its `include=INCLUDE` — `_public` is handed an un-hydrated row"
    )


def test_neither_stamp_can_arrive_from_the_client():
    from app.schemas.media import MediaCompleteRequest, TranscriptUpdateRequest

    for schema in (MediaCompleteRequest, TranscriptUpdateRequest):
        assert "transcriptEditedAt" not in schema.model_fields
        assert "transcriptEditedById" not in schema.model_fields

    with pytest.raises(ValidationError):
        TranscriptUpdateRequest(text="x", transcriptEditedAt="2026-09-13T10:00:00Z")
    with pytest.raises(ValidationError):
        MediaCompleteRequest(transcriptEditedById="usr_9")


def test_the_queue_does_not_clear_the_flag():
    from pathlib import Path

    source = (
        Path(__file__).resolve().parents[1] / "app" / "services" / "media_queue.py"
    ).read_text(encoding="utf-8")

    assert "transcriptText" in source, "media_queue no longer writes transcripts — re-read this test"
    assert "transcriptEditedAt" not in source
    assert "transcriptEditedById" not in source


def test_the_route_is_the_only_writer_of_the_stamps():
    import re
    from pathlib import Path

    app_root = Path(__file__).resolve().parents[1] / "app"
    write = re.compile(r"""["']transcriptEditedAt["']\s*:""")
    writers = sorted(
        path.relative_to(app_root).as_posix()
        for path in app_root.rglob("*.py")
        if write.search(path.read_text(encoding="utf-8"))
    )

    assert writers == ["api/routes/media.py"], writers
