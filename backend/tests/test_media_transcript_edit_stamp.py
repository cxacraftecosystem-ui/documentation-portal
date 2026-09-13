"""Whether the words on screen are still the machine's, and who changed them if not.

``POST /media/{id}/transcript`` is the one route by which a person replaces machine text. Until these
columns existed it wrote the text, marked the transcript COMPLETED, cleared the error — and recorded
neither that an edit had happened nor who made it. So a transcript a researcher had rewritten line by
line and one straight off the provider were byte-indistinguishable to every reader.

NULL MEANS "NOT STATED", NEVER "NEVER EDITED". The route has been able to replace a transcript all
along, so rows written before migration 20260913120200 genuinely do not say — which is why there is
no backfill and why both clients must render three states. The two tests at the bottom guard the two
ways that distinction gets destroyed: a client stamping itself, and the queue clearing the flag.
"""

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
    """AN EDIT STAMP A CLIENT COULD CHOOSE IS NOT AN AUDIT STAMP, so both halves come from the server:
    ``datetime.now(UTC)`` and the resolved caller, never anything off the payload.

    ``include=INCLUDE`` IS ASSERTED WITH THEM, not as tidiness. The response goes through ``_public``,
    which encodes relations the include loads; dropping it while editing the ``data`` dict beside it
    would hand the encoder an un-hydrated row — a plausible-looking edit that breaks the response
    shape for every caller of this route.
    """
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
    """AN UPLOAD THAT TRIED TO ARRIVE PRE-STAMPED AS HUMAN-EDITED IS REFUSED OUTRIGHT rather than
    believed. Both request models are ``extra="forbid"``, and that is the whole mechanism — which is
    why it is asserted rather than assumed: adding either column to a request model "so the client
    can send what it knows" would make the flag unfalsifiable."""
    from app.schemas.media import MediaCompleteRequest, TranscriptUpdateRequest

    for schema in (MediaCompleteRequest, TranscriptUpdateRequest):
        assert "transcriptEditedAt" not in schema.model_fields
        assert "transcriptEditedById" not in schema.model_fields

    with pytest.raises(ValidationError):
        TranscriptUpdateRequest(text="x", transcriptEditedAt="2026-09-13T10:00:00Z")
    with pytest.raises(ValidationError):
        MediaCompleteRequest(transcriptEditedById="usr_9")


def test_the_queue_does_not_clear_the_flag():
    """IF THE QUEUE BLANKED IT, A RESEARCHER'S CORRECTIONS WOULD BE RECORDED AS THE MACHINE'S OWN
    WORDS at the moment a later refinement pass overwrote them — the exact reading these columns
    exist to prevent, arriving through the one writer nobody would think to check.

    ``services/media_queue`` writes ``transcriptText`` on every provider result. It must never write
    either stamp: not ``None`` (which would erase a real edit) and not a value (which would attribute
    the machine's text to a person).
    """
    from pathlib import Path

    source = (
        Path(__file__).resolve().parents[1] / "app" / "services" / "media_queue.py"
    ).read_text(encoding="utf-8")

    assert "transcriptText" in source, "media_queue no longer writes transcripts — re-read this test"
    assert "transcriptEditedAt" not in source
    assert "transcriptEditedById" not in source


def test_the_route_is_the_only_writer_of_the_stamps():
    """ONE WRITER, ASSERTED ACROSS THE WHOLE BACKEND. A second one would make the flag mean two
    different things depending on which path set it, and the column carries no record of which."""
    from pathlib import Path

    app_root = Path(__file__).resolve().parents[1] / "app"
    writers = sorted(
        path.relative_to(app_root).as_posix()
        for path in app_root.rglob("*.py")
        if "transcriptEditedAt" in path.read_text(encoding="utf-8")
    )

    assert writers == ["api/routes/media.py"], writers
