from __future__ import annotations

import asyncio
import io
import zipfile
from types import SimpleNamespace
from typing import Any
from xml.etree import ElementTree

import pytest
from fastapi import HTTPException

from app.api.routes import data_explorer
from app.services import transcript_export, transcript_pipeline
from app.services.data_explorer import report_cache
from app.transcription.transcript import render

USER = SimpleNamespace(id="u1", role="MASTER_ADMIN", canDownloadDataset=True)


def _doc(title: str, words: str) -> render.RenderDoc:
    return render.RenderDoc(
        title=title,
        languages=["Hindi"],
        duration_ms=4000,
        speaker_count=1,
        turns=[render.RenderTurn("t1", "Asha", 0, 4000, words, "Indigo & clay", "COMPLETED")],
    )


def _setup(monkeypatch: pytest.MonkeyPatch, media: list[str], refused: set[str], withheld: set[str]) -> None:
    async def scope(_user: Any) -> Any:
        return SimpleNamespace(records={}, media={})

    async def folder(_scope: Any, _norm: str) -> Any:
        seen = {mid: {"mediaId": mid} for mid in media}
        return seen, {mid: SimpleNamespace(id=mid) for mid in media}, False

    async def hidden(ids: list[str]) -> set[str]:
        return {i for i in ids if i in withheld}

    async def render_doc(row: Any) -> Any:
        if row.id in refused:
            raise transcript_pipeline.PipelineRefused("NOT_READY", "no transcript")
        return _doc(f"Talk {row.id}", f"नील {row.id}"), {}

    async def crumbs(_norm: str) -> list[dict[str, str]]:
        return [{"name": "Repository", "path": ""}, {"name": "Kamla", "path": "by-workshop/w"}]

    browser = SimpleNamespace(_norm=lambda p: p.strip("/"), _resolve_crumbs=crumbs)
    monkeypatch.setattr(data_explorer, "_browser", lambda: browser)
    monkeypatch.setattr(data_explorer, "download_scope", scope)
    monkeypatch.setattr(data_explorer, "_folder_transcripts", folder)
    monkeypatch.setattr(transcript_export, "withheld_ids", hidden)
    monkeypatch.setattr(transcript_pipeline, "render_doc", render_doc)


def _export(**kwargs: Any) -> Any:
    return asyncio.run(
        data_explorer.transcript_export_docx(
            **{"path": "by-workshop/w", "format": "docx", "track": "both", "current_user": USER, **kwargs}
        )
    )


def test_one_docx_holds_every_readable_transcript_in_order(monkeypatch):
    _setup(monkeypatch, ["m1", "m2", "m3", "m4"], refused={"m2"}, withheld={"m4"})
    answer = _export()
    assert answer.status_code == 200
    assert answer.body[:2] == b"PK"
    assert "Transcripts" in answer.headers["content-disposition"]
    assert answer.headers["content-disposition"].count(".docx") >= 1
    with zipfile.ZipFile(io.BytesIO(answer.body)) as archive:
        assert {"[Content_Types].xml", "_rels/.rels", "word/document.xml"} <= set(archive.namelist())
        xml = archive.read("word/document.xml").decode("utf-8")
    ElementTree.fromstring(xml)
    assert xml.index("Talk m1") < xml.index("Talk m3")
    assert "Talk m2" not in xml and "Talk m4" not in xml
    assert xml.count('w:type="page"') == 1
    assert "Indigo &amp; clay" in xml and xml.count("<w:sectPr") == 1


def test_a_folder_without_transcripts_is_a_404(monkeypatch):
    _setup(monkeypatch, ["m1"], refused={"m1"}, withheld=set())
    with pytest.raises(HTTPException) as missing:
        _export()
    assert missing.value.status_code == 404


def test_a_full_queue_says_when_to_come_back(monkeypatch):
    _setup(monkeypatch, ["m1"], refused=set(), withheld=set())
    monkeypatch.setattr(report_cache, "QUEUE", report_cache.ReportQueue(depth=0, wait=0.01))

    async def story() -> HTTPException:
        await report_cache.QUEUE._semaphore.acquire()
        report_cache.QUEUE.running += 1
        try:
            with pytest.raises(HTTPException) as busy:
                await data_explorer.transcript_export_docx(
                    path="by-workshop/w", format="docx", track="both", current_user=USER
                )
            return busy.value
        finally:
            report_cache.QUEUE.running -= 1
            report_cache.QUEUE._semaphore.release()

    busy = asyncio.run(story())
    assert busy.status_code == 503 and busy.headers["Retry-After"] == str(report_cache.RETRY_AFTER_SECONDS)
