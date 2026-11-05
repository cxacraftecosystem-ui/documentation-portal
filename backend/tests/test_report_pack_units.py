from __future__ import annotations

import asyncio
import io
import zipfile
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pypdf import PdfReader

from app.services import transcript_pipeline
from app.services.datareport import pack, pdf_summary
from app.transcription.transcript.render import RenderDoc, RenderTurn


def _summary(**overrides: Any) -> pdf_summary.Summary:
    base: dict[str, Any] = {
        "portal_title": "Design Workshop",
        "folder": "Bagru block printing",
        "path": "by-workshop/w1",
        "generated_by": "Explorer Admin",
        "generated_at": datetime(2026, 11, 5, 10, 30, tzinfo=UTC),
        "data_version": "12:3456",
        "options": [("Answers", "Original and English")],
        "kpis": [("Files", "128", "3.2 GB"), ("Recordings", "36", ""), ("Coverage", "64%", "")],
        "media_by_type": [("Images", 80, 1 << 30), ("Audio recordings", 36, 1 << 28), ("Documents", 0, 0)],
        "transcript_status": [("Ready", 30, pdf_summary.GREEN), ("Failed", 2, pdf_summary.RED)],
        "sections": [pdf_summary.SectionBar("Identity", 10, 2, 1)],
        "coverage": [
            pdf_summary.Coverage(
                "Bagru",
                [(f"S{i}", f"Section {i}") for i in range(17)],
                [
                    pdf_summary.CoverageRow(f"Kamla {n}", "Dabu", 12, 40, 7, "3 of 4 ready", ["complete", "partial", "missing"] * 6)
                    for n in range(25)
                ],
            )
        ],
        "quotes": [
            pdf_summary.Quote("हम लोग नील से रंगते हैं।", "We dye with indigo.", "Kamla", "talk.m4a"),
            pdf_summary.Quote("Plain words", "", None, "clip.mp4"),
        ],
        "pack_files": pack.pack_contents("Report.xlsx", "Summary.pdf"),
    }
    base.update(overrides)
    return pdf_summary.Summary(**base)


def test_the_summary_pdf_parses_and_carries_every_section():
    data = pdf_summary.build(_summary())
    reader = PdfReader(io.BytesIO(data))
    text = "\n".join(page.extract_text() or "" for page in reader.pages)
    assert data.startswith(b"%PDF")
    assert len(reader.pages) >= 4
    for heading in ("Report summary", "Media by type", "Transcript status", "Completion by section", "Coverage · Bagru", "Quotes", "In this pack"):
        assert heading in text, heading
    assert f"Page 1 of {len(reader.pages)}" in text
    assert "We dye with indigo." in text
    assert reader.metadata.title == "Bagru block printing · Report summary"


def test_an_empty_folder_still_makes_a_summary():
    data = pdf_summary.build(
        _summary(kpis=[], media_by_type=[], transcript_status=[], sections=[], coverage=[], quotes=[], pack_files=[])
    )
    text = "\n".join(page.extract_text() or "" for page in PdfReader(io.BytesIO(data)).pages)
    assert "No files in this folder." in text and "No recordings in this folder." in text


def test_indic_text_is_drawn_in_its_own_face():
    marked = pdf_summary.rich("Kamla: हम <b>")
    assert 'font name="PackDevanagari"' in marked
    assert "&lt;b&gt;" in marked
    assert pdf_summary.rich("plain") == "plain"


def test_human_sizes():
    assert pdf_summary.human_size(0) == "0 B"
    assert pdf_summary.human_size(1536) == "1.5 KB"
    assert pdf_summary.human_size(3 * 1024**3) == "3.0 GB"


def _doc(timed: bool, english: bool) -> RenderDoc:
    turns = [
        RenderTurn("t1", "Kamla", 0, 2500 if timed else 0, "नील से रंगते हैं", "We dye with indigo" if english else None),
        RenderTurn("t2", "Ravi", 2500 if timed else 0, 5200 if timed else 0, "हाँ", "Yes" if english else None),
    ]
    return RenderDoc(title="Talk", languages=["Hindi"], duration_ms=5200, speaker_count=2, turns=turns)


def _prepared(transcripts: list[tuple[str, str]]) -> pack.Prepared:
    return pack.Prepared(
        zip_name="Pack.zip",
        xlsx_name="Report.xlsx",
        xlsx=b"PK\x03\x04" + b"x" * (pack.XLSX_PIECE + 10),
        pdf_name="Summary.pdf",
        pdf=pdf_summary.build(_summary()),
        folder="Bagru",
        path="by-workshop/w1",
        generated_by="Explorer Admin",
        generated_at=datetime(2026, 11, 5, 10, 30, tzinfo=UTC),
        data_version="1:2",
        transcripts=transcripts,
        withheld=1,
        capped=False,
        files=3,
        bytes=4096,
        quotes=2,
        coverage=True,
    )


async def _collect(chunks: Any) -> bytes:
    return b"".join([chunk async for chunk in chunks])


def test_the_pack_zip_holds_the_workbook_summary_transcripts_and_a_readme(monkeypatch):
    monkeypatch.setattr(
        transcript_pipeline,
        "download_name",
        lambda media, context, fmt, track, what=None: f"{media.id}-{what}.{fmt}",
    )
    docs = {"m1": _doc(True, True), "m2": _doc(False, False)}

    async def load(media_id: str) -> Any:
        if media_id == "gone":
            return None
        if media_id == "boom":
            raise RuntimeError("render failed")
        return SimpleNamespace(id=media_id), docs[media_id], {}

    prepared = _prepared([("m1", "talk.m4a"), ("m2", "notes.m4a"), ("gone", "gone.m4a"), ("boom", "boom.m4a")])
    payload = asyncio.run(_collect(pack.stream(prepared, load)))
    archive = zipfile.ZipFile(io.BytesIO(payload))
    assert archive.testzip() is None
    names = archive.namelist()
    assert names[0] == "Report.xlsx" and names[-1] == "README.txt"
    assert archive.read("Report.xlsx").startswith(b"PK\x03\x04")
    assert archive.getinfo("Report.xlsx").compress_type == zipfile.ZIP_STORED
    PdfReader(io.BytesIO(archive.read("Summary.pdf")))
    assert "Transcripts/m1-Transcript-Bilingual.docx" in names
    assert "Transcripts/m1-Subtitles-With-Speakers.srt" in names
    assert "Transcripts/m1-Subtitles-With-Speakers-English.srt" in names
    assert "Transcripts/m2-Transcript-Bilingual.docx" in names
    assert not any(n.startswith("Transcripts/m2-") and n.endswith(".srt") for n in names)
    docx = zipfile.ZipFile(io.BytesIO(archive.read("Transcripts/m1-Transcript-Bilingual.docx")))
    assert "word/document.xml" in docx.namelist()
    srt = archive.read("Transcripts/m1-Subtitles-With-Speakers.srt").decode("utf-8")
    assert "00:00:00,000 --> 00:00:02,500" in srt
    readme = archive.read("README.txt").decode("utf-8")
    for name in names:
        assert name in readme, name
    assert "gone.m4a" in readme and "boom.m4a" in readme
    assert "consent" in readme
    assert prepared.xlsx == b"" and prepared.pdf == b""


def test_the_pack_streams_in_pieces_rather_than_one_blob(monkeypatch):
    monkeypatch.setattr(transcript_pipeline, "download_name", lambda media, context, fmt, track, what=None: f"{what}.{fmt}")

    async def load(media_id: str) -> Any:
        return SimpleNamespace(id=media_id), _doc(True, False), {}

    async def run() -> list[bytes]:
        return [chunk async for chunk in pack.stream(_prepared([("a", "a"), ("b", "b")]), load)]

    chunks = asyncio.run(run())
    assert len(chunks) > 3
    assert max(len(c) for c in chunks) <= pack.XLSX_PIECE + 64 * 1024
    names = zipfile.ZipFile(io.BytesIO(b"".join(chunks))).namelist()
    assert "Transcripts/Transcript-Bilingual.docx" in names and "Transcripts/Transcript-Bilingual (2).docx" in names


def test_a_failed_workbook_never_becomes_a_pack():
    async def broken(user: Any, params: pack.PackParams) -> tuple[bytes, str]:
        return b'{"detail": "boom"}', "Report.xlsx"

    with pytest.raises(HTTPException) as caught:
        asyncio.run(pack.prepare(SimpleNamespace(name="A"), pack.PackParams(), xlsx_builder=broken))
    assert caught.value.status_code == 500


def test_bad_options_are_refused_before_any_work():
    async def never(user: Any, params: pack.PackParams) -> tuple[bytes, str]:
        raise AssertionError("must not build")

    with pytest.raises(HTTPException) as caught:
        asyncio.run(pack.prepare(SimpleNamespace(), pack.PackParams(answers="klingon"), xlsx_builder=never))
    assert caught.value.status_code == 422


def test_disposition_names_and_portal_title():
    assert pack._disposition_name('attachment; filename="Report.xlsx"; filename*=UTF-8') == "Report.xlsx"
    assert pack._disposition_name("attachment") is None
    assert pack.portal_title() in ("Design Workshop", "Field Repository")


def test_transcript_candidates_prefer_the_recording_name():
    files = [
        {"mediaId": "m1", "mediaType": "TRANSCRIPT", "path": "x/transcripts/talk.md"},
        {"mediaId": "m1", "mediaType": "AUDIO", "originalFilename": "talk.m4a"},
        {"mediaId": "p1", "mediaType": "IMAGE", "originalFilename": "loom.jpg"},
        {"mediaId": "v1", "mediaType": "VIDEO", "path": "x/videos/clip.mp4"},
    ]
    assert pack._transcript_candidates(files) == [("m1", "talk.m4a"), ("v1", "clip.mp4")]


def test_section_bars_count_each_state():
    block = pdf_summary.Coverage(
        "W",
        [("a", "A"), ("b", "B")],
        [
            pdf_summary.CoverageRow("x", "", 1, 2, 0, "", ["complete", "missing"]),
            pdf_summary.CoverageRow("y", "", 1, 2, 0, "", ["partial", "missing"]),
        ],
    )
    bars = pack._section_bars(block)
    assert [(b.label, b.complete, b.partial, b.missing) for b in bars] == [("A", 1, 1, 0), ("B", 0, 0, 2)]


def test_delivery_hooks_run_only_after_the_whole_zip_streamed(monkeypatch):
    monkeypatch.setattr(transcript_pipeline, "download_name", lambda media, context, fmt, track, what=None: f"{what}.{fmt}")
    calls: list[str] = []

    async def hook() -> None:
        calls.append("delivered")

    async def broken() -> None:
        raise RuntimeError("hook failed")

    async def load(media_id: str) -> Any:
        return SimpleNamespace(id=media_id), _doc(True, False), {}

    prepared = _prepared([("a", "a")])
    prepared.delivered = [broken, hook]

    async def run() -> None:
        chunks = pack.stream(prepared, load)
        await chunks.__anext__()
        assert calls == []
        async for _chunk in chunks:
            pass

    asyncio.run(run())
    assert calls == ["delivered"]


def test_quotes_come_from_the_quotes_helper_when_the_builder_hands_back_a_context(monkeypatch):
    import sys

    seen: dict[str, Any] = {}

    async def top_quotes(ctx: Any, limit: int = 40) -> list[dict[str, Any]]:
        seen["ctx"], seen["limit"] = ctx, limit
        return [
            {"mediaId": "m1", "file": "talk.m4a", "artisan": "Kamla", "speaker": "K", "original": "नील", "english": "Indigo"},
            {"mediaId": "hidden", "file": "x", "artisan": "", "speaker": "", "original": "secret", "english": ""},
            {"mediaId": "m1", "file": "talk.m4a", "artisan": "", "speaker": "", "original": " ", "english": ""},
        ]

    from app.services.datareport import sheets as sheet_package

    helper = SimpleNamespace(top_quotes=top_quotes)
    monkeypatch.setitem(sys.modules, "app.services.datareport.sheets.ai_quotes", helper)
    monkeypatch.setattr(sheet_package, "ai_quotes", helper, raising=False)
    ctx = object()
    quotes = asyncio.run(pack._ctx_quotes(ctx, {"m1"}))
    assert seen["ctx"] is ctx
    assert [(q.original, q.english, q.speaker, q.source) for q in quotes] == [("नील", "Indigo", "K", "Kamla · talk.m4a")]
    assert asyncio.run(pack._ctx_quotes(None, {"m1"})) is None
