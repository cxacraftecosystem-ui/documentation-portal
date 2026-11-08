from __future__ import annotations

import asyncio
import io
import sys
import warnings
import zipfile
from typing import Any

import pytest

from app.services.datareport import build, contract, jobs, writer
from app.services.datareport.contract import ReportOptions
from tests import report_fixtures as rf

openpyxl = pytest.importorskip("openpyxl")

PORTALS = ("designer", "fieldRepository")
HEADER_ROW = writer.TOP + 1
FRAME = frozenset({"Overview", "Provenance", "Data dictionary", "Chart data"})
CORE_SHEET = "workshops"
EMPTY_ON_FIXTURE = frozenset({"peer", "quotes"})
DB_MODULES = ("extras_audit", "extras_changes", "extras_common", "extras_coverage", "extras_map", "extras_peer")


@pytest.fixture
def make(monkeypatch: pytest.MonkeyPatch) -> Any:
    import importlib

    from app.core import db as core_db
    from prisma import Prisma

    for name in DB_MODULES:
        importlib.import_module(f"app.services.datareport.sheets.{name}")
    real = core_db.db

    def factory(portal: str, options: ReportOptions | None = None) -> contract.ReportContext:
        ctx = rf.rich_context(options or ReportOptions(), portal_key=portal)
        fake = ctx.shared["db"]
        monkeypatch.setattr(core_db, "db", fake)
        for module in list(sys.modules.values()):
            current = getattr(module, "db", None)
            if (getattr(module, "__name__", "") or "").startswith("app.") and (current is real or isinstance(current, Prisma)):
                monkeypatch.setattr(module, "db", fake)
        return ctx

    return factory


def _render(ctx: contract.ReportContext) -> tuple[Any, bytes, list[str]]:
    failed: list[str] = []
    payload = asyncio.run(build.render_parts(ctx, failed)).main
    assert rf.string_problems(payload) == []
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        book = openpyxl.load_workbook(io.BytesIO(payload))
    return book, payload, failed


def _data_sheets(book: Any) -> set[str]:
    return {name for name in book.sheetnames if name not in FRAME}


def _data_rows(ws: Any) -> int:
    count = 0
    for row in ws.iter_rows(min_row=HEADER_ROW + 1, values_only=True):
        if not any(v is not None for v in row):
            continue
        if row[0] in ("Total", writer.TEXT["banner.empty"]):
            continue
        count += 1
    return count


def _header(ws: Any) -> list[str]:
    return [c.value for c in ws[HEADER_ROW] if c.value is not None]


def _described(book: Any) -> set[tuple[str, str]]:
    ws = book["Data dictionary"]
    return {(r[0], r[1]) for r in ws.iter_rows(min_row=5, values_only=True) if r[0]}


async def _expected(plugin: Any, ctx: contract.ReportContext) -> list[tuple[str, int, list[contract.Column], contract.Sheet]]:
    used: set[str] = set()
    out = []
    for sheet in await plugin.build(ctx) or []:
        shaped = build.shape(sheet, ctx.options)
        rows = [row async for row in build._iterate(shaped.rows)]
        out.append((writer.sheet_title(ctx.t(f"sheet.{sheet.key}", sheet.name), used), len(rows), list(shaped.columns), sheet))
    return out


def _plugin_of(portal: str, key: str) -> Any:
    for plugin in contract.plugins(portal):
        if plugin.key == key or plugin.option == key:
            return plugin
    raise LookupError(key)


def _assert_columns(book: Any, title: str, columns: list[contract.Column], sheet_key: str, t: Any) -> None:
    ws = book[title]
    header = set(_header(ws))
    described = _described(book)
    for column in columns:
        label = t(f"col.{sheet_key}.{column.key}", column.label).replace("\n", " ").strip()
        in_sheet = label in header or any(h.startswith(label) for h in header)
        in_provenance = ("Provenance", f"{label} ({title})") in described
        assert in_sheet or in_provenance, (title, label)


@pytest.mark.parametrize("portal", PORTALS)
def test_every_sheet_in_the_dialog_round_trips_alone_and_unticked(make: Any, portal: str) -> None:
    catalog = asyncio.run(build.catalog_sheets(make(portal)))
    assert len(catalog) >= 10
    keys = [entry["key"] for entry in catalog]
    results = []
    for entry in catalog:
        plugin = _plugin_of(portal, entry["plugin"])
        expected = [e for e in asyncio.run(_expected(plugin, make(portal, ReportOptions(sheets=[entry["key"]])))) if e[3].key == entry["key"]]
        assert len(expected) == 1, entry
        title, rows, columns, _sheet = expected[0]
        for ident in (entry["key"], entry["name"]):
            book, _payload, failed = _render(make(portal, ReportOptions(sheets=[ident])))
            assert failed == [], (entry, failed)
            assert _data_sheets(book) == {title}, (ident, _data_sheets(book))
            assert _data_rows(book[title]) == rows, (ident, rows)
            if entry["count"]:
                assert rows > 0, entry
        _assert_columns(book, title, columns, entry["key"], lambda k, d: d)
        others = [k for k in keys if k != entry["key"]]
        if others:
            book, _payload, _failed = _render(make(portal, ReportOptions(sheets=others)))
            assert title not in book.sheetnames, (entry["key"], "still present when unticked")
        results.append((entry["name"], rows))
    print(f"{portal}: {len(results)} dialog sheets round-trip: {results}")


@pytest.mark.parametrize("portal", PORTALS)
def test_every_extra_round_trips_alone_and_unticked(make: Any, portal: str) -> None:
    offered = contract.optional_keys(portal)
    assert offered
    results = []
    for option, label in offered:
        plugin = _plugin_of(portal, option)
        ticked = ReportOptions(sheets=[CORE_SHEET], extras={option: True})
        expected = asyncio.run(_expected(plugin, make(portal, ticked)))
        book, payload, failed = _render(make(portal, ticked))
        assert failed == [], (option, failed)
        for title, rows, columns, sheet in expected:
            assert title in book.sheetnames, (option, title)
            assert _data_rows(book[title]) == rows, (option, title, rows)
            if option not in EMPTY_ON_FIXTURE:
                assert rows > 0, (option, title)
            _assert_columns(book, title, columns, sheet.key, lambda k, d: d)
        if not expected:
            with zipfile.ZipFile(io.BytesIO(payload)) as z:
                assert any(n.startswith("xl/charts/") for n in z.namelist()), option
        plain, plain_payload, _failed = _render(make(portal, ReportOptions(sheets=[CORE_SHEET])))
        for title, _rows, _columns, _sheet in expected:
            assert title not in plain.sheetnames, (option, title, "present when unticked")
        if not expected:
            with zipfile.ZipFile(io.BytesIO(plain_payload)) as z:
                assert not any(n.startswith("xl/charts/") for n in z.namelist()), option
        results.append((label, [(t, r) for t, r, _c, _s in expected] or "cover charts"))
    print(f"{portal}: extras round-trip: {results}")


@pytest.mark.parametrize("portal", PORTALS)
def test_ticking_everything_yields_every_sheet_and_extra(make: Any, portal: str) -> None:
    catalog = asyncio.run(build.catalog_sheets(make(portal)))
    everything = ReportOptions(extras={option: True for option, _label in contract.optional_keys(portal)})
    wanted: set[str] = set()
    used: set[str] = set()
    for entry in catalog:
        wanted.add(writer.sheet_title(entry["name"], used))
    for option, _label in contract.optional_keys(portal):
        for title, _rows, _columns, _sheet in asyncio.run(_expected(_plugin_of(portal, option), make(portal, everything))):
            wanted.add(title)
    book, payload, failed = _render(make(portal, everything))
    assert failed == []
    assert wanted <= set(book.sheetnames), sorted(wanted - set(book.sheetnames))
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        assert any(n.startswith("xl/charts/") for n in z.namelist())


@pytest.mark.parametrize("portal", PORTALS)
def test_the_default_report_ticks_every_sheet_and_every_extra(make: Any, portal: str, monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import naming_portal
    from app.services.data_explorer import report_options

    monkeypatch.setattr(naming_portal, "PORTAL", "FieldRepository" if portal == "fieldRepository" else "DesignWorkshop")
    everything = {option: True for option, _label in contract.optional_keys(portal)}
    default = build.from_parsed(report_options.parse())
    assert default.sheets is None and default.extras == everything
    assert build.options_from({}).extras == everything
    assert build.options_from({"extras": "none"}).extras == {}
    kept = report_options.stored_options(report_options.parse(extras="none").stored())
    assert kept["extras"] == dict.fromkeys(everything, False)
    assert build.from_parsed(report_options.parse(extras=kept["extras"])).extras == {}
    assert report_options.stored_options(report_options.parse().stored())["extras"] is None
    book, _payload, failed = _render(make(portal, default))
    full, _full_payload, _full_failed = _render(make(portal, ReportOptions(extras=everything)))
    assert failed == []
    assert book.sheetnames == full.sheetnames
    bare, _bare_payload, _bare_failed = _render(make(portal, build.options_from({"extras": "none"})))
    assert len(bare.sheetnames) < len(book.sheetnames)


@pytest.mark.parametrize("portal", PORTALS)
def test_dialog_labels_are_the_names_in_the_workbook(make: Any, portal: str) -> None:
    catalog = asyncio.run(build.catalog_sheets(make(portal)))
    for entry in catalog:
        assert writer.sheet_title(entry["name"], set()) == entry["name"], entry["name"]
    ctx = make(portal, ReportOptions(extras={option: True for option, _label in contract.optional_keys(portal)}))
    for option, label in contract.optional_keys(portal):
        names = [title for title, _r, _c, _s in asyncio.run(_expected(_plugin_of(portal, option), ctx))]
        if option == "charts":
            assert names == []
            continue
        if option == "profiles":
            assert names and all(name.startswith(label[:-1]) for name in names), (label, names)
            continue
        assert label in names, (option, label, names)


@pytest.mark.parametrize("portal", PORTALS)
def test_the_switches_round_trip(make: Any, portal: str) -> None:
    book, _payload, _failed = _render(make(portal, ReportOptions(sheets=["media", "transcripts"], links=False)))
    assert "Download" not in _header(book["Media"]) and "Open" not in _header(book["Transcripts"])
    assert not any(c.hyperlink for ws in (book["Media"], book["Transcripts"]) for r in ws.iter_rows(min_row=HEADER_ROW + 1) for c in r)
    book, _payload, _failed = _render(make(portal, ReportOptions(sheets=["media", "transcripts"])))
    assert "Download" in _header(book["Media"]) and "Open" in _header(book["Transcripts"])
    for answers, gone, kept in (("original", "English translation", "Original"), ("english", "Original", "English translation")):
        book, _payload, _failed = _render(make(portal, ReportOptions(sheets=["transcripts"], answers=answers)))
        ws = book["Transcripts"]
        column = _header(ws).index("Row")
        kinds = {r[column] for r in ws.iter_rows(min_row=HEADER_ROW + 1, values_only=True) if r[0]}
        assert gone not in kinds and kept in kinds, (answers, kinds)
    book, _payload, _failed = _render(make(portal, ReportOptions(sheets=["artisans"], rows="filtered", q="sita verma")))
    ws = book["Artisans"]
    assert [r[_header(ws).index("Name")] for r in ws.iter_rows(min_row=HEADER_ROW + 1, values_only=True) if r[0] and r[0] != "Total"] == ["Sita Verma"]
    ctx = make(portal, ReportOptions(sheets=["media"], thumbnails=True))
    ctx.thumbnails["m1"] = _png()
    book, payload, _failed = _render(ctx)
    assert "Preview" in _header(book["Media"])
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        assert any(n.startswith("xl/media/") for n in z.namelist())
    book, _payload, _failed = _render(make(portal, ReportOptions(sheets=["media"])))
    assert "Preview" not in _header(book["Media"])
    book, _payload, _failed = _render(make(portal, ReportOptions(sheets=["media"], locale="hi")))
    assert any(ch >= "ऀ" for name in book.sheetnames for ch in name)


def _png() -> bytes:
    from PIL import Image

    out = io.BytesIO()
    Image.new("RGB", (40, 30), (118, 44, 177)).save(out, format="PNG")
    return out.getvalue()


@pytest.mark.parametrize("portal", PORTALS)
def test_the_worker_path_keeps_every_ticked_option(portal: str) -> None:
    from app.services.data_explorer import report_options

    extras = ",".join(option for option, _label in contract.optional_keys(portal))
    picked = report_options.parse(sheets="Workshops,artisans,Transcripts", answers="english", rows="filtered", links="0", q="indigo", thumbnails="1", locale="hi", extras=extras)
    params = jobs.report_params("by-workshop", picked, name="My report", sheet=None)
    back = build.from_parsed(jobs._picked(params["options"]), name=params.get("name"))
    direct = build.from_parsed(picked, name="My report")
    assert back == direct
    assert back.sheets == ["Workshops", "artisans", "Transcripts"]
    assert set(back.extras) == {option for option, _label in contract.optional_keys(portal)}
    assert (back.answers, back.rows, back.links, back.thumbnails, back.locale, back.q) == ("english", "filtered", False, True, "hi", "indigo")


@pytest.mark.parametrize("portal", PORTALS)
def test_a_filtered_report_keeps_every_ticked_option_and_its_filters(portal: str) -> None:
    from app.services.data_explorer import filters as explorer_filters, report_options

    extras = ",".join(option for option, _label in contract.optional_keys(portal))
    picked = report_options.parse(sheets="Workshops,artisans,Transcripts", answers="english", rows="filtered", links="1", q="", thumbnails="0", locale="en", extras=extras)
    flt = explorer_filters.parse({"craftIds": "craft-a,craft-b", "mediaTypes": "AUDIO,VIDEO", "statuses": "APPROVED", "dateFrom": "2026-01-01", "dateTo": "2026-11-08"})
    assert explorer_filters.real(flt) is not None
    params = jobs.report_params("by-workshop", picked, name="Filtered", sheet=None, filters=explorer_filters.as_query(flt))
    assert build.from_parsed(jobs._picked(params["options"]), name=params.get("name")) == build.from_parsed(picked, name="Filtered")
    assert jobs._explorer_filter(params) == flt
    unfiltered = jobs.report_params("by-workshop", picked, name="Filtered", sheet=None)
    assert "filters" not in unfiltered
    assert jobs._explorer_filter(unfiltered) is None
