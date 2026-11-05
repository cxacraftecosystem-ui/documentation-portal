from __future__ import annotations

import asyncio
import sys
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import data_browser
from app.services.data_explorer import report_cache

ADMIN = SimpleNamespace(id="admin-1", role="MASTER_ADMIN", canDownloadDataset=True)


def _report(**kwargs: Any) -> Any:
    return data_browser.data_report(
        **{"path": "", "format": "json", "status_value": None, "current_user": ADMIN, **kwargs}
    )



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

    assert len(capped_p["rows"]) == data_browser.PREVIEW_ROWS + 1
    assert str(capped_p["rows"][-1][0]).startswith("Note:") and "300" in str(capped_p["rows"][-1][0])
    assert capped_p["capped"] is True and capped_p["preview"] is True and capped_p["totalRows"] == 300

    assert uncounted_p["truncated"] is True and uncounted_p["preview"] is True
    assert uncounted_p["totalRows"] is None and uncounted_p["capped"] is False
    assert all("noteRow" not in sheet for sheet in (long_p, short_p, capped_p, uncounted_p))



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



def _material_stub(monkeypatch, slow: Any) -> None:
    async def version(_db: Any) -> str:
        return "v1"

    async def scope(_user: Any, *_args: Any) -> Any:
        return data_browser.Scope(records={}, media={})

    monkeypatch.setattr(data_browser.report_cache, "data_version", version)
    monkeypatch.setattr(data_browser, "_scope_for", scope)
    monkeypatch.setattr(data_browser, "_report_material", slow)


def _built(rows: int = 3) -> dict[str, Any]:
    sheet = data_browser._sheet("Tools", "#000000", ["Tool"], [[f"tool {i}"] for i in range(rows)])
    return {"sheets": [sheet], "level": "Repository", "builtAt": "2026-11-05T10:00:00+05:30"}


def test_a_second_report_for_another_folder_waits_its_turn_instead_of_failing(monkeypatch):
    monkeypatch.setattr(data_browser, "REPORT_QUEUE", report_cache.ReportQueue(wait=5))
    calls: list[str] = []

    async def story() -> tuple[Any, Any]:
        started = asyncio.Event()
        release = asyncio.Event()

        async def slow(norm: str, *_args: Any) -> Any:
            calls.append(norm)
            if norm == "by-workshop/a":
                started.set()
                await release.wait()
            return _built()

        _material_stub(monkeypatch, slow)
        first = asyncio.create_task(_report(path="by-workshop/a"))
        await started.wait()
        second = asyncio.create_task(_report(path="by-workshop/b", format="xlsx"))
        await asyncio.sleep(0.05)
        assert not second.done()
        assert data_browser.REPORT_QUEUE.waiting == 1
        release.set()
        return await first, await second

    done, queued = asyncio.run(story())
    assert done.status_code == 200 and queued.status_code == 200
    assert queued.body[:2] == b"PK"
    assert calls == ["by-workshop/a", "by-workshop/b"]
    assert not data_browser.REPORT_QUEUE.locked()


def test_a_full_queue_says_when_to_come_back(monkeypatch):
    monkeypatch.setattr(data_browser, "REPORT_QUEUE", report_cache.ReportQueue(depth=0, wait=5))

    async def story() -> HTTPException:
        started = asyncio.Event()
        release = asyncio.Event()

        async def slow(*_args: Any) -> Any:
            started.set()
            await release.wait()
            return _built()

        _material_stub(monkeypatch, slow)
        first = asyncio.create_task(_report(path="by-workshop/a"))
        await started.wait()
        with pytest.raises(HTTPException) as refused:
            await _report(path="by-workshop/b")
        release.set()
        await first
        return refused.value

    refused = asyncio.run(story())
    assert refused.status_code == 503
    assert refused.detail == data_browser.REPORT_BUSY
    assert refused.headers == {"Retry-After": str(report_cache.RETRY_AFTER_SECONDS)}


def test_one_build_serves_the_preview_every_search_the_csv_and_the_xlsx(monkeypatch):
    builds: list[str] = []

    async def build(norm: str, *_args: Any) -> Any:
        builds.append(norm)
        return _built(250)

    _material_stub(monkeypatch, build)

    async def story() -> list[Any]:
        return [
            await _report(path="by-workshop/a"),
            await _report(path="by-workshop/a", q="tool 24"),
            await _report(path="by-workshop/a", q="tool 7", sheet="Tools"),
            await _report(path="by-workshop/a", format="csv", sheet="Tools"),
            await _report(path="by-workshop/a", format="xlsx"),
            await _report(path="by-workshop/a", format="xlsx"),
        ]

    preview, searched, picked, as_csv, xlsx, again = asyncio.run(story())
    assert builds == ["by-workshop/a"]
    assert preview.status_code == 200 and searched.status_code == 200 and picked.status_code == 200
    assert as_csv.status_code == 200
    assert xlsx.body == again.body and xlsx.body[:2] == b"PK"


def test_concurrent_asks_for_one_folder_share_one_build(monkeypatch):
    builds: list[str] = []

    async def build(norm: str, *_args: Any) -> Any:
        builds.append(norm)
        await asyncio.sleep(0.05)
        return _built()

    _material_stub(monkeypatch, build)

    async def story() -> list[Any]:
        return list(await asyncio.gather(*(_report(path="p", q=f"tool {i}") for i in range(4))))

    answers = asyncio.run(story())
    assert builds == ["p"]
    assert all(a.status_code == 200 for a in answers)


def test_new_data_rebuilds_the_report(monkeypatch):
    builds: list[str] = []
    versions = iter(["v1", "v1", "v2"])

    async def build(norm: str, *_args: Any) -> Any:
        builds.append(norm)
        return _built()

    _material_stub(monkeypatch, build)

    async def version(_db: Any) -> str:
        return next(versions)

    monkeypatch.setattr(data_browser.report_cache, "data_version", version)

    async def story() -> None:
        for _ in range(3):
            await _report(path="p")

    asyncio.run(story())
    assert builds == ["p", "p"]


def test_a_failed_build_releases_the_queue(monkeypatch):
    async def broken(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("boom")

    _material_stub(monkeypatch, broken)
    with pytest.raises(RuntimeError):
        asyncio.run(_report())
    assert not data_browser.REPORT_QUEUE.locked()
    assert data_browser.REPORT_QUEUE.waiting == 0


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
