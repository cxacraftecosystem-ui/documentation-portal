"""``/data/report`` stays bounded (D39): the JSON preview, the named media columns, one build at a time.

The field API's resident memory ratcheted 179 -> 288 MB, and its query engine 22 -> 104 MB, in steps
on root ``/data/report`` calls: the Data page built the whole-repository report on open, twice at
once, and every media row carried its ``transcriptText`` and ``transcriptSegments``. These tests need
no database; ``test_data_report_db.py`` repeats the root report against Postgres and counts.
"""

from __future__ import annotations

import asyncio
import sys
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from fastapi.responses import JSONResponse

from app.api.routes import data_browser

ADMIN = SimpleNamespace(id="admin-1", role="MASTER_ADMIN", canDownloadDataset=True)


def _report(**kwargs: Any) -> Any:
    return data_browser.data_report(
        **{"path": "", "format": "json", "status_value": None, "current_user": ADMIN, **kwargs}
    )


# --- the JSON preview ---------------------------------------------------------------------------


def test_the_json_preview_cuts_each_sheet_and_says_how_many_rows_the_download_has(monkeypatch):
    monkeypatch.setattr(data_browser, "REPORT_TAKE", 300)
    long = data_browser._sheet("Long", "#000000", ["n"], [[i] for i in range(250)])
    short = data_browser._sheet("Short", "#000000", ["n"], [[i] for i in range(12)])
    capped = data_browser._sheet("Capped", "#000000", ["n"], [[i] for i in range(400)])
    uncounted = data_browser._sheet("Turns", "#000000", ["n"], [[i] for i in range(200)], True)

    long_p, short_p, capped_p, uncounted_p = data_browser._preview([long, short, capped, uncounted], {3})

    assert len(long_p["rows"]) == data_browser.PREVIEW_ROWS
    assert long_p["truncated"] is True and long_p["preview"] is True and long_p["capped"] is False
    assert long_p["totalRows"] == 250

    assert short_p["rows"] == [[i] for i in range(12)]
    assert short_p["truncated"] is False and short_p["preview"] is False and short_p["totalRows"] == 12

    # The download itself stops at REPORT_TAKE: 200 shown, then the note row, and the total is the cap.
    assert len(capped_p["rows"]) == data_browser.PREVIEW_ROWS + 1
    assert str(capped_p["rows"][-1][0]).startswith("Note: capped at 300")
    assert capped_p["capped"] is True and capped_p["preview"] is True and capped_p["totalRows"] == 300

    # A transcript-pipeline sheet built only as far as the preview: cut, and not counted.
    assert uncounted_p["truncated"] is True and uncounted_p["preview"] is True
    assert uncounted_p["totalRows"] is None and uncounted_p["capped"] is False
    assert all("noteRow" not in sheet for sheet in (long_p, short_p, capped_p, uncounted_p))


# --- the media rows: named columns, never the transcript ----------------------------------------


class _Delegate:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows or []
        self.calls: list[dict[str, Any]] = []

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.calls.append(kwargs)
        return self.rows

    async def group_by(self, **kwargs: Any) -> list[Any]:
        self.calls.append(kwargs)
        return [{"id": "m1", "createdAt": datetime(2026, 11, 1, tzinfo=UTC)}]


def _fake_db(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    sql: list[str] = []

    async def query_raw(query: str, *args: Any) -> list[dict[str, Any]]:
        sql.append(query)
        return [
            {
                "id": "m1", "originalFilename": "talk.m4a", "mediaType": "AUDIO", "sizeBytes": "2048",
                "url": None, "linkedRecordType": None, "linkedRecordId": None,
                "recordedAt": "2026-11-01T10:00:00Z", "createdAt": "2026-11-01T10:00:00+00:00",
                "uploadedById": "u1", "artisanId": None, "craftId": None, "workshopId": None,
                "productId": None, "toolId": None, "questionnaireInterviewId": None,
                "designWorkshopId": None, "hasTranscript": True,
            }
        ]

    fake = SimpleNamespace(
        mediafile=_Delegate(),
        user=_Delegate([SimpleNamespace(id="u1", name="Asha")]),
        productdocumentation=_Delegate(),
        tooldocumentation=_Delegate(),
        artisan=_Delegate(),
        workshop=_Delegate(),
        craft=_Delegate(),
        questionnaireinterview=_Delegate(),
        query_raw=query_raw,
        sql=sql,
    )
    monkeypatch.setattr(data_browser, "db", fake)
    return fake


def _projection(query: str) -> str:
    """The select list of the media read, less the one cheap ``hasTranscript`` expression."""
    head = query.split(' FROM "MediaFile"', 1)[0]
    return head.replace(data_browser._HAS_TRANSCRIPT, "")


def test_the_media_query_names_its_columns_and_never_the_transcript(monkeypatch):
    fake = _fake_db(monkeypatch)
    scope = data_browser.Scope(records={}, media={})

    rows = asyncio.run(data_browser._report_media(None, scope))

    picked = fake.mediafile.calls[0]
    assert picked["by"] == ["id", "createdAt"] and "include" not in picked
    assert len(fake.sql) == 1
    for heavy in ("transcriptText", "transcriptSegments", "transcriptSummary", "extraMetadata", "*"):
        assert heavy not in _projection(fake.sql[0]), heavy
    (m,) = rows
    assert not hasattr(m, "transcriptText")
    assert m.hasTranscript is True and m.sizeBytes == 2048
    assert isinstance(m.createdAt, datetime) and m.uploadedBy.name == "Asha"


def test_the_transcripts_sheet_prints_the_words_it_was_handed_not_a_column(monkeypatch):
    _fake_db(monkeypatch)
    rows = asyncio.run(data_browser._report_media(None, data_browser.Scope(records={}, media={})))

    sheet = data_browser._transcript_sheet(rows, {}, {}, texts={"m1": "**Asha:** clay and gum"})

    assert sheet["rows"][0][-1] == "**Asha:** clay and gum"
    assert sheet["rows"][0][0] == "talk.m4a"


# --- one build at a time ------------------------------------------------------------------------


def test_a_second_report_while_one_is_building_is_answered_at_once(monkeypatch):
    async def story() -> tuple[HTTPException, Any, Any]:
        started = asyncio.Event()
        release = asyncio.Event()

        async def slow(*_args: Any, **_kwargs: Any) -> Any:
            started.set()
            await release.wait()
            return JSONResponse({"sheets": []})

        monkeypatch.setattr(data_browser, "_build_report", slow)
        first = asyncio.create_task(_report())
        await started.wait()
        with pytest.raises(HTTPException) as refused:
            await _report(format="xlsx")
        release.set()
        done = await first
        # Free again once the first has finished.
        again = await _report()
        return refused.value, done, again

    refused, done, again = asyncio.run(story())
    assert refused.status_code == 503
    assert refused.detail == data_browser.REPORT_BUSY == "A report is already being prepared. Try again in a minute."
    assert refused.headers == {"Retry-After": "60"}
    assert done.status_code == 200 and again.status_code == 200
    assert not data_browser._REPORT_BUILD.locked()


def test_a_failed_build_releases_the_lock(monkeypatch):
    async def broken(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("boom")

    monkeypatch.setattr(data_browser, "_build_report", broken)
    with pytest.raises(RuntimeError):
        asyncio.run(_report())
    assert not data_browser._REPORT_BUILD.locked()


# --- malloc_trim --------------------------------------------------------------------------------


def test_trimming_the_heap_never_fails_a_request(monkeypatch):
    def refuses(_pad: int) -> int:
        raise OSError("no")

    monkeypatch.setattr(data_browser, "_malloc_trim", lambda: refuses)
    data_browser._trim_heap()
    monkeypatch.setattr(data_browser, "_malloc_trim", lambda: None)
    data_browser._trim_heap()


def test_malloc_trim_is_only_looked_for_on_linux(monkeypatch):
    monkeypatch.setattr(sys, "platform", "win32")
    assert data_browser._malloc_trim.__wrapped__() is None
