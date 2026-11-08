from __future__ import annotations

import asyncio
import io
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import data_browser
from app.services.data_explorer import report_options
from tests import report_fixtures
from tests.report_worker import body, running_worker

ADMIN = SimpleNamespace(id="admin-1", role="MASTER_ADMIN", canDownloadDataset=True)


def _turns() -> dict[str, Any]:
    return data_browser._sheet(
        "Transcript turns",
        "#0F766E",
        ["Media ID", "File", "Language", "Original", "English", "Translation status", "URL"],
        [
            ["m1", "talk.m4a", "hi", "नील", "Indigo", "COMPLETED", "https://x/1"],
            ["m2", "song.m4a", "hi", "मिट्टी", "Clay", "COMPLETED", "https://x/2"],
        ],
        prose=[3, 4],
    )


def _tools(rows: int = 3, truncated: bool = False) -> dict[str, Any]:
    return data_browser._sheet(
        "Tools", "#000000", ["Tool", "Media URLs"], [[f"tool {i}", f"https://t/{i}"] for i in range(rows)], truncated
    )


def test_parse_reads_every_option_and_refuses_nonsense():
    picked = report_options.parse(sheets="Tools, tools ,Transcript turns", answers="English", rows="filtered", links="0", q="clay")
    assert picked.sheets == ("Tools", "Transcript turns")
    assert picked.answers == "english" and picked.rows == "filtered" and picked.links is False
    assert picked.filters_rows and not picked.default
    assert report_options.parse().default
    assert report_options.parse(rows="filtered").default
    for bad in ({"answers": "both-ish"}, {"rows": "some"}, {"links": "maybe"}):
        with pytest.raises(report_options.OptionsInvalid):
            report_options.parse(**bad)


def test_stored_options_keep_only_the_known_choices():
    stored = report_options.stored_options({"sheets": ["Tools"], "answers": "original", "rows": "all", "links": True, "x": 1})
    assert stored == {"sheets": ["Tools"], "answers": "original", "rows": "all", "links": True, "thumbnails": False, "locale": "en", "extras": None}
    richer = report_options.stored_options({"thumbnails": True, "locale": "hi", "extras": {"quotes": True, "map": False}})
    assert richer["thumbnails"] is True and richer["locale"] == "hi" and richer["extras"]["quotes"] is True
    assert not any(on for key, on in richer["extras"].items() if key != "quotes")
    assert report_options.stored_options(richer)["extras"] == richer["extras"]
    assert report_options.parse(extras="map,quotes,map").extras == ("map", "quotes")
    assert report_options.parse().extras is None and report_options.parse(extras={}).extras is None
    assert report_options.parse(extras="none").extras == () and report_options.parse(extras="").extras == ()
    assert report_options.parse(extras={"quotes": False}).extras == ()
    assert not report_options.parse(extras="none").default
    assert not report_options.parse(locale="hi").default
    with pytest.raises(report_options.OptionsInvalid):
        report_options.parse(locale="fr")
    with pytest.raises(report_options.OptionsInvalid):
        report_options.parse(extras="bad key!")
    assert report_options.stored_options({})["sheets"] is None
    with pytest.raises(report_options.OptionsInvalid):
        report_options.stored_options([])
    with pytest.raises(report_options.OptionsInvalid):
        report_options.stored_options({"sheets": [1]})


def test_file_name_is_a_plain_leaf_ending_in_xlsx():
    assert report_options.file_name("../../etc/Bagru report.xlsx") == "Bagru-report.xlsx"
    assert report_options.file_name("C:\\Users\\a\\Kamla: dyes?") == "Kamla-dyes.xlsx"
    assert report_options.file_name("नील रिपोर्ट").endswith(".xlsx")
    assert report_options.file_name("   ") is None
    assert report_options.file_name(".xlsx") is None
    assert report_options.file_name(None) is None
    assert len(report_options.file_name("a" * 500) or "") <= 150


def test_original_answers_drop_the_english_columns():
    (sheet,) = report_options.apply([_turns()], report_options.parse(answers="original"))
    assert sheet["columns"] == ["Media ID", "File", "Language", "Original", "URL"]
    assert sheet["rows"][0] == ["m1", "talk.m4a", "hi", "नील", "https://x/1"]
    assert sheet["prose"] == [3]


def test_english_answers_drop_the_original_where_an_english_twin_exists():
    (sheet,) = report_options.apply([_turns()], report_options.parse(answers="english"))
    assert "Original" not in sheet["columns"] and "English" in sheet["columns"]
    assert sheet["prose"] == [sheet["columns"].index("English")]
    alone = data_browser._sheet("Alone", "#000000", ["Original"], [["x"]])
    (kept,) = report_options.apply([alone], report_options.parse(answers="english"))
    assert kept["columns"] == ["Original"]


def test_links_off_drops_url_columns_and_keeps_the_cap_note():
    capped = _tools(rows=4, truncated=True)
    (sheet,) = report_options.apply([capped], report_options.parse(links="0"))
    assert sheet["columns"] == ["Tool"]
    assert all(len(row) == 1 for row in sheet["rows"])
    assert str(sheet["rows"][-1][0]).startswith("Note:")


def test_filtered_rows_keep_only_matches_without_the_preview_cap():
    many = _tools(rows=450)
    (sheet,) = report_options.apply([many], report_options.parse(rows="filtered", q="4"))
    assert len(sheet["rows"]) == len([i for i in range(450) if "4" in str(i)])
    assert len(sheet["rows"]) > 100


def test_sheets_pick_by_name_in_any_case():
    picked = report_options.apply([_tools(), _turns()], report_options.parse(sheets="transcript TURNS"))
    assert [s["name"] for s in picked] == ["Transcript turns"]


def _stub(monkeypatch: pytest.MonkeyPatch, builds: list[str]) -> None:
    async def version(_db: Any) -> str:
        return "v1"

    async def scope(_user: Any, *_args: Any) -> Any:
        return data_browser.Scope(records={}, media={})

    async def material(norm: str, *_args: Any) -> Any:
        builds.append(norm)
        return {"sheets": [_tools(), _turns()], "level": "Repository", "builtAt": "2026-11-05T10:00:00+05:30"}

    async def records(_segs: Any, _scope: Any) -> dict[str, list[Any]]:
        found: dict[str, list[Any]] = {key: [] for key in data_browser._REPORT_KEYS}
        found["tools"] = [SimpleNamespace(id=f"t{i}", toolkitName=f"tool {i}", status="APPROVED") for i in range(3)]
        return found

    monkeypatch.setattr(data_browser.report_cache, "data_version", version)
    monkeypatch.setattr(data_browser, "_scope_for", scope)
    monkeypatch.setattr(data_browser, "_report_material", material)
    monkeypatch.setattr(data_browser, "_report_records", records)
    monkeypatch.setattr(data_browser, "db", report_fixtures.FakeDB())


def _report(**kwargs: Any) -> Any:
    return data_browser.data_report(
        **{"path": "p", "format": "xlsx", "status_value": None, "current_user": ADMIN, **kwargs}
    )


def _workbook(body: bytes) -> Any:
    from openpyxl import load_workbook

    assert body[:2] == b"PK"
    return load_workbook(io.BytesIO(body))


def _data_rows(ws: Any) -> list[tuple[Any, ...]]:
    from app.services.datareport import writer

    return [r for r in ws.iter_rows(min_row=writer.TOP + 2, values_only=True) if r[0] not in (None, "Total")]


def test_the_xlsx_follows_the_options_and_reuses_one_build(monkeypatch):
    from app.services.datareport import writer

    builds: list[str] = []
    _stub(monkeypatch, builds)

    async def story() -> list[Any]:
        async with running_worker({ADMIN.id: ADMIN}):
            return [
                await _report(format="json"),
                await _report(format="json"),
                await _report(),
                await _report(sheets="Tools", answers="original", links="0", name="Kamla dyes", extras="none"),
                await _report(sheets="Tools", rows="filtered", q="tool 1", extras="none"),
                await _report(),
            ]

    first, second, full, slim_book, filtered, again = asyncio.run(story())
    assert builds == ["p"]
    assert first.status_code == 200
    import json

    assert json.loads(first.body)["cached"] is False
    assert json.loads(second.body)["cached"] is True
    names = _workbook(body(full)).sheetnames
    assert names[0] == "Overview" and names[-1] == "Data dictionary"
    assert "Tools" in names and "Transcripts" in names
    book = _workbook(body(slim_book))
    assert book.sheetnames == ["Overview", "Tools", "Provenance", "Data dictionary"]
    header = [c.value for c in book["Tools"][writer.TOP + 1] if c.value is not None]
    assert "Open" not in header and "Toolkit name" in header
    assert 'filename="Kamla-dyes.xlsx"' in slim_book.headers["content-disposition"]
    tools = _workbook(body(filtered))["Tools"]
    header = [c.value for c in tools[writer.TOP + 1]]
    picked = [row[header.index("Toolkit name")] for row in _data_rows(tools)]
    assert picked == ["tool 1"]
    assert body(again) == body(full)
    cache = data_browser.REPORT_CACHE
    key = next(iter(cache._entries))
    assert all(name.startswith("xlsx4:") for name in cache._entries[key].files)


def test_unknown_options_are_a_422_and_an_empty_pick_is_a_404(monkeypatch):
    _stub(monkeypatch, [])
    with pytest.raises(HTTPException) as bad:
        asyncio.run(_report(answers="klingon"))
    assert bad.value.status_code == 422

    async def missing_sheet() -> Any:
        async with running_worker({ADMIN.id: ADMIN}):
            return await _report(sheets="Nope", extras="none")

    with pytest.raises(HTTPException) as missing:
        asyncio.run(missing_sheet())
    assert missing.value.status_code == 404
