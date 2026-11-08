from __future__ import annotations

import asyncio
import dataclasses
import io
import os
import re
import shutil
import subprocess
import tempfile
import time
import warnings
import zipfile
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from app.services.datareport import build, contract, writer
from app.services.datareport.contract import ROW_FULL, ROW_KIND, Column, Link, Sheet
from app.services.datareport.sheets import core_transcripts
from tests import report_fixtures as rf

openpyxl = pytest.importorskip("openpyxl")

MAIN_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
HEADER_ROW = writer.TOP + 1
RECORD_SHEETS = ("Workshops", "Artisans", "Products", "Processes", "Tools", "Questionnaire sittings", "Media", "Transcripts", "Answers", "Design workshops")
PROVENANCE_LABELS = ("Created on", "Updated on", "Created by", "Updated by", "Deleted on", "Deleted by", "More details", "Field provenance", "Checksum", "SHA-256 checksum", "Uploaded by", "Reviewed by", "Edited by", "Edited on", "Translation model", "Revision", "Version ID")
V014_TURN_ROWS = 19716
V014_EVERYTHING_ROWS = 33772


def parts_of(payload: bytes) -> dict[str, str]:
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        return {n: z.read(n).decode("utf-8") for n in z.namelist() if n.endswith((".xml", ".rels"))}


def sheet_parts(payload: bytes) -> dict[str, str]:
    parts = parts_of(payload)
    book = parts["xl/workbook.xml"]
    rels = parts["xl/_rels/workbook.xml.rels"]
    targets = {m.group(1): m.group(2) for m in re.finditer(r'Id="([^"]+)" [^>]*?Target="([^"]+)"', rels)}
    targets.update({m.group(2): m.group(1) for m in re.finditer(r'Target="([^"]+)" [^>]*?Id="([^"]+)"', rels)})
    out = {}
    for m in re.finditer(r'<sheet name="([^"]+)" sheetId="\d+"(?: state="hidden")? r:id="([^"]+)"', book):
        target = targets[m.group(2)].lstrip("/").removeprefix("xl/")
        out[m.group(1).replace("&amp;", "&").replace("&apos;", "'")] = parts[f"xl/{target}"]
    return out


def xml_bytes(payload: bytes) -> int:
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        return sum(i.file_size for i in z.infolist())


def budget_report(payload: bytes) -> dict[str, Any]:
    sheets = sheet_parts(payload)
    links = {name: xml.count("<hyperlink ") for name, xml in sheets.items()}
    formulas: list[tuple[str, str]] = []
    for name, xml in sheets.items():
        for m in re.finditer(r'<c r="([A-Z]+\d+)"[^>]*><f>(.*?)</f>', xml):
            formulas.append((name, m.group(2)))
    return {"links": links, "total": sum(links.values()), "formulas": formulas, "xml": xml_bytes(payload)}


def assert_budgets(payload: bytes) -> dict[str, Any]:
    found = budget_report(payload)
    for name, count in found["links"].items():
        assert count <= writer.LINKS_PER_SHEET, (name, count)
    assert found["total"] <= writer.LINKS_PER_BOOK, found["total"]
    for name, formula in found["formulas"]:
        assert formula.startswith("SUBTOTAL("), (name, formula)
    assert "HYPERLINK(" not in "".join(sheet_parts(payload).values())
    return found


def wrapped_styles(payload: bytes) -> set[int]:
    styles = parts_of(payload)["xl/styles.xml"]
    xfs = re.search(r"<cellXfs[^>]*>(.*?)</cellXfs>", styles, re.DOTALL)
    assert xfs
    out = set()
    for index, xf in enumerate(re.findall(r"<xf [^>]*?(?:/>|>.*?</xf>)", xfs.group(1), re.DOTALL)):
        if 'wrapText="1"' in xf:
            out.add(index)
    return out


def _row_number(attrs: str) -> int:
    found = re.search(r'\br="(\d+)"', attrs)
    assert found
    return int(found.group(1))


def assert_contract(payload: bytes) -> dict[str, Any]:
    assert payload[:4] == b"PK\x03\x04"
    assert rf.string_problems(payload) == []
    sheets = sheet_parts(payload)
    wrapped = wrapped_styles(payload)
    report: dict[str, Any] = {"sheets": {}, "tables": 0}
    sst = parts_of(payload).get("xl/sharedStrings.xml", "")
    for si in re.findall(r"<si>(.*?)</si>", sst, re.DOTALL):
        text = "".join(re.findall(r"<t[^>]*>(.*?)</t>", si, re.DOTALL))
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        assert len(text) <= writer.CELL_LIMIT, (len(text), text[:80])
    for name, xml in sheets.items():
        tables = xml.count("<tablePart ")
        assert tables >= 1, f"{name} is not an Excel Table"
        report["tables"] += tables
        if name == "Overview":
            continue
        header = 4 if name in ("Data dictionary", "Chart data") else HEADER_ROW
        rows = re.findall(r"<row ([^>]*?)/?>", xml)
        for attrs in rows:
            r = _row_number(attrs)
            if r > header:
                assert 'customHeight="1"' in attrs and 'ht="' in attrs, f"{name} row {r} has no fixed height"
        sheet_format = re.search(r"<sheetFormatPr ([^>]*)/>", xml)
        assert sheet_format and 'customHeight="1"' in sheet_format.group(1), name
        for row in re.finditer(r'<row r="(\d+)"[^>]*>(.*?)</row>', xml, re.DOTALL):
            if int(row.group(1)) <= header:
                continue
            for style in re.findall(r'<c r="[A-Z]+\d+" s="(\d+)"', row.group(2)):
                assert int(style) not in wrapped, f"{name} row {row.group(1)} wraps"
        assert "<f>HYPERLINK" not in xml
        report["sheets"][name] = len(rows)
    assert report["tables"] >= len(sheets)
    assert "Provenance" in sheets
    return report


def assert_tables_cover_every_row(book: Any) -> None:
    for ws in book.worksheets:
        assert ws.tables, f"{ws.title} has no table"
        if ws.title == "Overview":
            continue
        last = max(int(re.sub(r"[A-Z]", "", t.ref.split(":")[1])) for t in ws.tables.values())
        assert last >= ws.max_row, (ws.title, last, ws.max_row)
        if ws.title not in ("Data dictionary", "Chart data"):
            first = min(int(re.sub(r"[A-Z]", "", t.ref.split(":")[0])) for t in ws.tables.values())
            assert first == HEADER_ROW, (ws.title, first)
        for table in ws.tables.values():
            assert table.tableStyleInfo is not None and table.tableStyleInfo.showRowStripes, (ws.title, table.name)
        if ws.title != "Chart data":
            assert ws.freeze_panes, ws.title


def assert_record_sheets_hold_no_provenance(book: Any) -> None:
    for name in RECORD_SHEETS:
        if name not in book.sheetnames:
            continue
        ws = book[name]
        header = _header(ws)
        bad = [h for h in header if h in PROVENANCE_LABELS]
        assert bad == [], (name, bad)
        ids = [h for h in header if h and (h.endswith(" ID") or h == "Record ID")]
        assert len(ids) <= 1, (name, ids)
        for dim in ws.column_dimensions.values():
            assert not dim.hidden, (name, dim.min, dim.max)
            assert not dim.outline_level, (name, dim.min, dim.max)
            if dim.width:
                assert dim.width <= writer.WIDTH_MAX + 1, (name, dim.min, dim.width)
    provenance = book["Provenance"]
    assert _header(provenance) == ["Sheet", "Record ID", "Field", "Value"]
    assert provenance.tables


def _book(payload: bytes, **kw: Any) -> Any:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        return openpyxl.load_workbook(io.BytesIO(payload), **kw)


def _render(options: contract.ReportOptions | None = None, **kw: Any) -> build.Parts:
    ctx = rf.context(options, **kw)
    return asyncio.run(build.render_parts(ctx))


def _rows(ws: Any) -> list[list[Any]]:
    return [list(r) for r in ws.iter_rows(values_only=True)]


def _header(ws: Any) -> list[str]:
    return list(_rows(ws)[writer.TOP])


def test_the_report_is_one_workbook_with_no_companion_and_no_zip() -> None:
    fields = {f.name for f in dataclasses.fields(build.Parts)} | {f.name for f in dataclasses.fields(build.BuiltReport)}
    assert not {"companion", "companion_name"} & fields
    assert not hasattr(build, "ZIP_MIME") and not hasattr(build, "split_needed")
    built = build.BuiltReport(b"PK\x03\x04main", "a.xlsx", rf.context())
    assert build.deliverable(built) == (b"PK\x03\x04main", "a.xlsx", build.XLSX_MIME)


def test_the_default_report_keeps_every_budget_and_the_workbook_contract() -> None:
    parts = _render(contract.ReportOptions(extras={"everything": True}))
    found = assert_budgets(parts.main)
    assert found["xml"] < writer.XML_BUDGET
    assert_contract(parts.main)
    book = _book(parts.main)
    assert_tables_cover_every_row(book)
    assert_record_sheets_hold_no_provenance(book)


def test_transcripts_hold_every_turn_with_its_translation_directly_beneath() -> None:
    parts = _render()
    ws = _book(parts.main)["Transcripts"]
    header = _header(ws)
    rows = [dict(zip(header, r, strict=False)) for r in _rows(ws)[HEADER_ROW:] if r[0]]
    turns = [r for r in rows if r["Row"] in ("Original", "English translation")]
    assert len(turns) == 2 * 2 * 2
    for index, row in enumerate(rows):
        if row["Row"] == "Original":
            assert rows[index + 1]["Row"] == "English translation"
            assert rows[index + 1]["Record ID"] == row["Record ID"]
            assert rows[index + 1]["Turn"] == row["Turn"]
        if row["Row"] == "Whole transcript":
            assert rows[index + 1]["Row"] == "Whole transcript · English translation"
    assert not any("part 1 of" in str(r["Row"]) for r in rows)
    assert {r["Record ID"] for r in turns} == {"t1-m0", "t2-m0", "t1-m2", "t2-m2"}


def test_speaker_names_are_bold_in_both_transcription_and_translation() -> None:
    from openpyxl.cell.rich_text import CellRichText, TextBlock

    parts = _render()
    plain = _book(parts.main)["Transcripts"]
    header = _header(plain)
    ws = _book(parts.main, rich_text=True)["Transcripts"]
    row_col, text_col, speaker_col = header.index("Row") + 1, header.index("Text") + 1, header.index("Speaker") + 1
    seen = {"Original": 0, "English translation": 0}
    for r in range(HEADER_ROW + 1, ws.max_row + 1):
        kind = ws.cell(row=r, column=row_col).value
        value = ws.cell(row=r, column=text_col).value
        if kind in seen and value:
            assert isinstance(value, CellRichText), (r, value)
            first = value[0]
            assert isinstance(first, TextBlock) and first.font.b and first.text == ws.cell(row=r, column=speaker_col).value, (r, value)
            seen[kind] += 1
    assert seen["Original"] >= 2 and seen["English translation"] >= 2


def test_transcript_links_are_one_set_per_recording() -> None:
    parts = _render()
    ws = _book(parts.main)["Transcripts"]
    header = _header(ws)
    links = [(c.row, c.hyperlink.target) for r in ws.iter_rows() for c in r if c.hyperlink is not None and c.row > writer.TOP]
    kinds = {r: ws.cell(row=r, column=header.index("Row") + 1).value for r, _url in links}
    assert all(str(kind).startswith("Whole transcript") for kind in kinds.values())
    assert sum(1 for _r, url in links if "t=" in url) == 2
    starts = [ws.cell(row=r, column=header.index("Start") + 1).value for r in range(HEADER_ROW + 1, ws.max_row + 1) if ws.cell(row=r, column=header.index("Row") + 1).value == "Original"]
    assert starts and all(isinstance(v, str) and re.fullmatch(r"\d+:\d{2}", v) for v in starts)


def test_rich_strings_never_carry_an_empty_or_lone_run() -> None:
    rows = [
        {"name": "a", "text": contract.Rich((("Speaker 1", True), ("  ", False))), ROW_FULL: None},
        {"name": "b", "text": contract.Rich((("Speaker 1", True), ("", False), ("words", False))), ROW_FULL: None},
        {"name": "c", "text": contract.Rich((("Speaker 1", True), (" " * 1500, False))), ROW_FULL: "https://portal.example.org/data/go/transcript/m0/docx"},
        {"name": "d", "text": contract.Rich((("", True), ("x" * 1500, False))), ROW_FULL: "https://portal.example.org/data/go/transcript/m0/docx"},
        {"name": "e", "text": contract.Rich((("Speaker 1", True),)), ROW_FULL: None},
    ]
    sheet = Sheet("edge", "Edge", [Column("name", "Name", "text"), Column("text", "Text", "longtext")], rows, count=len(rows))
    payload = _write([sheet])
    assert rf.string_problems(payload) == []
    assert "<t></t>" not in parts_of(payload)["xl/sharedStrings.xml"]
    assert core_transcripts.turn_runs("Speaker 1", "  ") == "Speaker 1"


def _long_sheet(text: str, *, full: str | None, rich: bool = False) -> Sheet:
    value: Any = contract.Rich((("Asha", True), (f"  {text}", False))) if rich else text
    return Sheet("long", "Long", [Column("name", "Name", "text"), Column("text", "Text", "longtext")], [{"name": "a", "text": value, ROW_FULL: full}], count=1)


def _write(sheets: list[Sheet]) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "links.xlsx")
        book = writer.ReportWriter(path, rf.context())
        for sheet in sheets:
            state = book.begin(sheet, bundle_url=None)
            state.write_rows(list(sheet.rows))
            book.end(state)
        book.write_provenance()
        book.write_cover(everything_url=None, everything_files=0, everything_bytes=0, kpis=[], charts=[])
        book.write_dictionary()
        writer.close(book)
        return Path(path).read_bytes()


def test_a_long_cell_stops_at_a_thousand_characters_with_a_full_text_link() -> None:
    url = "https://portal.example.org/data/go/transcript/m0/docx"
    payload = _write([_long_sheet("नील का रंग " * 400, full=url, rich=True)])
    assert_contract(payload)
    ws = _book(payload, rich_text=True)["Long"]
    cell = ws.cell(row=HEADER_ROW + 1, column=2)
    text = str(cell.value)
    assert len(text) <= writer.CELL_LIMIT
    assert text.endswith(writer.TEXT["cell.fullText"])
    assert cell.hyperlink is not None and cell.hyperlink.target == url
    assert cell.value[0].font.b and cell.value[0].text == "Asha"


def test_a_long_cell_without_a_link_still_stops_at_a_thousand_characters() -> None:
    payload = _write([_long_sheet("x" * 5000, full=None)])
    ws = _book(payload)["Long"]
    cell = ws.cell(row=HEADER_ROW + 1, column=2)
    assert len(str(cell.value)) <= writer.CELL_LIMIT and str(cell.value).endswith(writer.TEXT["cell.truncated"])
    assert cell.hyperlink is None


def test_everything_has_every_field_row_and_no_per_row_links() -> None:
    parts = _render(contract.ReportOptions(extras={"everything": True}))
    sheets = sheet_parts(parts.main)
    assert sheets["Everything"].count("<hyperlink ") == 1
    ws = _book(parts.main)["Everything"]
    header = _header(ws)
    assert "Open" not in header and "Record ID" in header and "Source" in header
    fields = {r[header.index("Field")] for r in _rows(ws)[HEADER_ROW:] if r[0]}
    assert {"createdAt", "createdById"} <= fields


def test_data_rows_carry_no_formulas() -> None:
    parts = _render(contract.ReportOptions(extras={"everything": True}))
    found = budget_report(parts.main)
    book = _book(parts.main)
    for name, ws in [(n, book[n]) for n in book.sheetnames]:
        for row in ws.iter_rows(min_row=HEADER_ROW + 1):
            for cell in row:
                if isinstance(cell.value, str) and cell.value.startswith("="):
                    assert ws.cell(row=cell.row, column=1).value == "Total", (name, cell.coordinate, cell.value)
    assert all(f.startswith("SUBTOTAL(") for _n, f in found["formulas"])


def _link_sheet(rows: int) -> Sheet:
    columns = [Column("name", "Name", "text"), Column("open", "Open", "link"), Column("id", "ID", "id")]
    data = [{"name": f"Row {i}", "open": Link("Open", f"https://portal.example.org/data/go/open/artisan/a{i}"), "id": f"a{i}"} for i in range(rows)]
    return Sheet("many", "Many links", columns, data, count=rows)


def test_a_sheet_over_the_link_cap_writes_web_addresses_as_text_with_a_banner() -> None:
    payload = _write([_link_sheet(writer.LINKS_PER_SHEET + 10)])
    assert_budgets(payload)
    ws = _book(payload)["Many links"]
    assert ws.cell(row=4, column=1).value == writer.TEXT["banner.urlText"]
    header = _header(ws)
    assert header[:3] == ["Name", "Open", "Open (web address)"]
    assert ws.cell(row=HEADER_ROW + 1, column=3).value == "https://portal.example.org/data/go/open/artisan/a0"
    assert sum(1 for r in ws.iter_rows() for c in r if c.hyperlink is not None) == 1
    dictionary = {(r[0], r[1]) for r in _rows(_book(payload)["Data dictionary"]) if r[0]}
    assert ("Many links", "Open (web address)") in dictionary


def test_the_workbook_link_cap_holds_across_sheets() -> None:
    sheets = [replace(_link_sheet(4000), key=f"many{i}", name=f"Many {i}") for i in range(6)]
    payload = _write(sheets)
    found = assert_budgets(payload)
    assert found["total"] <= writer.LINKS_PER_BOOK
    book = _book(payload)
    texted = [name for name in book.sheetnames if name.startswith("Many") and "Open (web address)" in _header(book[name])]
    assert texted and "Many 0" not in texted


def test_a_small_sheet_under_the_cap_keeps_live_links() -> None:
    payload = _write([_link_sheet(50)])
    ws = _book(payload)["Many links"]
    assert sum(1 for r in ws.iter_rows() for c in r if c.hyperlink is not None and c.row > writer.TOP) == 50
    assert "Open (web address)" not in _header(ws)


def test_every_sheet_is_a_table_however_large_and_empty_sheets_too() -> None:
    small = Sheet("small", "Small", [Column("name", "Name", "text")], [{"name": "a"}], count=1)
    big_rows = 25_000
    big = Sheet("big", "Big", [Column("name", "Name", "text"), Column("n", "N", "int", total="sum")], [{"name": f"r{i}", "n": i} for i in range(big_rows)], count=big_rows)
    empty = Sheet("empty", "Empty", [Column("name", "Name", "text")], [], count=0)
    payload = _write([small, big, empty])
    sheets = sheet_parts(payload)
    for name in ("Small", "Big", "Empty", "Provenance", "Data dictionary", "Overview"):
        assert "<tablePart " in sheets[name], name
    assert "<autoFilter " not in sheets["Big"]
    book = _book(payload)
    ws = book["Big"]
    ref = next(iter(ws.tables.values())).ref
    assert ref == f"A{HEADER_ROW}:B{HEADER_ROW + big_rows + 1}"
    assert ws.cell(row=HEADER_ROW + big_rows + 1, column=1).value == "Total"
    assert_tables_cover_every_row(book)


def test_technical_columns_go_to_one_provenance_sheet_keyed_by_sheet_and_record_id() -> None:
    parts = _render()
    book = _book(parts.main)
    ws = book["Artisans"]
    header = _header(ws)
    assert "Artisan ID" in header
    assert not any(h in PROVENANCE_LABELS for h in header)
    assert ws.freeze_panes == f"B{HEADER_ROW + 1}"
    provenance = book["Provenance"]
    rows = [r for r in _rows(provenance)[HEADER_ROW:] if r[0]]
    artisans = [r for r in rows if r[0] == "Artisans"]
    assert {r[1] for r in artisans} == {"a0", "a1", "a2"}
    assert any(r[2] == "Created by ID" and r[3] == "u1" for r in artisans)
    transcripts = [r for r in rows if r[0] == "Transcripts"]
    assert {r[1] for r in transcripts} >= {"m0", "m2"}
    assert ("m0", "Version ID", "v-m0") in {(r[1], r[2], r[3]) for r in transcripts}
    assert ("m0", "Translation model", "m") in {(r[1], r[2], r[3]) for r in transcripts}
    assert len({(r[1], r[2]) for r in transcripts}) == len(transcripts)
    described = {(r[0], r[1]) for r in _rows(book["Data dictionary"]) if r[0]}
    for label in header:
        assert ("Artisans", label) in described
    assert ("Provenance", "Created by ID (Artisans)") in described


def test_rich_runs_never_carry_an_empty_font() -> None:
    parts = _render()
    for name, xml in parts_of(parts.main).items():
        assert "<rPr/>" not in xml and "<rPr></rPr>" not in xml, name
    sst = parts_of(parts.main).get("xl/sharedStrings.xml", "")
    assert "<b/>" in sst and 'rFont val="Aptos"' in sst


def _speaker_rows(n: int) -> list[dict[str, Any]]:
    rows = []
    for p in range(n):
        rows.append({"id": f"t{p}", "versionId": "v", "mediaFileId": "m", "position": p, "speakerKey": "s0" if p % 4 < 2 else "s1", "startMs": p * 1000, "endMs": p * 1000 + 900, "language": "hi", "machineText": f"line {p}", "editedText": None, "machineEnglish": f"english {p}", "editedEnglish": None, "translationStatus": "TRANSLATED", "translationModel": "m", "editedAt": None, "editedById": None, "revision": 1, "createdAt": None, "updatedAt": None})
    return rows


def test_every_segment_is_its_own_turn_row() -> None:
    turns = core_transcripts.turns_of(_speaker_rows(8), {"s0": "Asha", "s1": "Mohan"}, "Not requested")
    assert [t.speaker for t in turns] == ["Asha", "Asha", "Mohan", "Mohan", "Asha", "Asha", "Mohan", "Mohan"]
    assert [t.turn_id for t in turns] == [f"t{p}" for p in range(8)]
    assert turns[1].text == "line 1" and turns[1].english_text == "english 1"


def test_the_whole_transcript_row_previews_the_dialogue_and_links_the_full_text() -> None:
    raw = rf.production_raw(recordings=1, segments_each=400)
    ctx = rf.context(db=rf.FakeDB(raw=lambda sql, *args: raw(sql, *[["m0"] if a and isinstance(a, list) and "m0" in a else a for a in args])))
    payload = asyncio.run(build.render(ctx))
    assert_contract(payload)
    ws = _book(payload)["Transcripts"]
    header = _header(ws)
    whole = [r for r in range(HEADER_ROW + 1, ws.max_row + 1) if str(ws.cell(row=r, column=header.index("Row") + 1).value).startswith("Whole transcript") and ws.cell(row=r, column=1).value == "talk-0.m4a"]
    assert len(whole) == 2
    for r in whole:
        cell = ws.cell(row=r, column=header.index("Text") + 1)
        assert len(str(cell.value)) <= writer.CELL_LIMIT and str(cell.value).endswith(writer.TEXT["cell.fullText"])
        assert cell.hyperlink is not None and cell.hyperlink.target.endswith("/data/go/transcript/m0/docx")
    turns = sum(1 for r in range(HEADER_ROW + 1, ws.max_row + 1) if ws.cell(row=r, column=header.index("Row") + 1).value == "Original")
    assert turns == 400


def _soffice() -> str | None:
    return shutil.which("soffice") or shutil.which("libreoffice")


def _convert(binary: str, source: str, tmp: str) -> float:
    started = time.monotonic()
    result = subprocess.run(
        [binary, "--headless", "--norestore", f"-env:UserInstallation=file://{tmp}/profile", "--convert-to", "csv", "--outdir", tmp, source],
        capture_output=True,
        text=True,
        timeout=600,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    assert Path(source).with_suffix(".csv").is_file(), result.stdout + result.stderr
    return time.monotonic() - started


def test_libreoffice_and_openpyxl_open_the_workbook_without_repair() -> None:
    parts = _render(contract.ReportOptions(extras={"everything": True}))
    rf.validate(parts.main)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        openpyxl.load_workbook(io.BytesIO(parts.main), rich_text=True)
    assert [str(w.message) for w in caught if "extension" not in str(w.message).lower()] == []
    binary = _soffice()
    if binary is None:
        if os.environ.get("REQUIRE_LIBREOFFICE") == "1":
            pytest.fail("LibreOffice is required on CI but soffice is not on PATH")
        pytest.skip("LibreOffice is not installed here")
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "book.xlsx")
        Path(source).write_bytes(parts.main)
        _convert(binary, source, tmp)


PRODUCTION = os.environ.get("REPORT_PRODUCTION") == "1"
LOAD_SECONDS = float(os.environ.get("REPORT_LOAD_SECONDS") or 45)
SCROLL_SECONDS = float(os.environ.get("REPORT_SCROLL_SECONDS") or 20)
CONVERT_SECONDS = float(os.environ.get("REPORT_CONVERT_SECONDS") or 90)
BUILD_SECONDS = float(os.environ.get("REPORT_BUILD_SECONDS") or 240)


@pytest.mark.skipif(not PRODUCTION, reason="the production-sized budget runs in its own CI job (REPORT_PRODUCTION=1)")
def test_a_production_sized_report_keeps_every_budget_and_opens_fast() -> None:
    from openpyxl.cell.rich_text import CellRichText, TextBlock

    ctx = rf.production_context()
    started = time.monotonic()
    parts = asyncio.run(build.render_parts(ctx))
    built_in = time.monotonic() - started
    out = Path(os.environ.get("REPORT_OUT") or tempfile.mkdtemp())
    out.mkdir(parents=True, exist_ok=True)
    (out / "after-main.xlsx").write_bytes(parts.main)
    assert not hasattr(parts, "companion")
    found = assert_budgets(parts.main)
    assert found["xml"] < writer.XML_BUDGET, found["xml"]
    contract_report = assert_contract(parts.main)
    started = time.monotonic()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        book = openpyxl.load_workbook(io.BytesIO(parts.main))
    loaded = time.monotonic() - started
    assert_tables_cover_every_row(book)
    assert_record_sheets_hold_no_provenance(book)
    rows = {name: book[name].max_row for name in book.sheetnames}
    transcripts = book["Transcripts"]
    header = _header(transcripts)
    kinds = [r[header.index("Row")] for r in transcripts.iter_rows(min_row=HEADER_ROW + 1, values_only=True)]
    segments = sum(len(rf.production_turns(f"v-m{i}", 49)) for i in range(420))
    assert segments >= 20000
    assert kinds.count("Original") == segments and kinds.count("English translation") == segments
    assert kinds.count("Original") + kinds.count("English translation") >= V014_TURN_ROWS
    everything = rows["Everything"] - HEADER_ROW
    assert everything >= 34000 and everything >= V014_EVERYTHING_ROWS, everything
    started = time.monotonic()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        lazy = openpyxl.load_workbook(io.BytesIO(parts.main), read_only=True)
        last = None
        for row in lazy["Transcripts"].iter_rows(values_only=True):
            last = row
        lazy.close()
    scrolled = time.monotonic() - started
    assert last is not None and last[0]
    rich = _book(parts.main, rich_text=True)["Transcripts"]
    text_col, row_col, speaker_col = header.index("Text") + 1, header.index("Row") + 1, header.index("Speaker") + 1
    checked = 0
    for r in range(HEADER_ROW + 1, min(rich.max_row, HEADER_ROW + 3000) + 1):
        if rich.cell(row=r, column=row_col).value in ("Original", "English translation"):
            value = rich.cell(row=r, column=text_col).value
            assert isinstance(value, CellRichText), r
            assert isinstance(value[0], TextBlock) and value[0].font.b and value[0].text == rich.cell(row=r, column=speaker_col).value, r
            checked += 1
    assert checked > 1000
    long_links = sum(1 for r in rich.iter_rows(min_row=HEADER_ROW + 1, min_col=text_col, max_col=text_col) for c in r if c.hyperlink is not None)
    assert long_links >= 420
    print(f"main: xml {found['xml'] / 1e6:.1f} MB, links {found['total']}, tables {contract_report['tables']} for {len(book.sheetnames)} sheets, formulas {len(found['formulas'])}")
    print(f"rows: Transcripts {rows['Transcripts']}, Everything {rows['Everything']}, Provenance {rows['Provenance']}")
    print(f"build {built_in:.1f}s, openpyxl load {loaded:.1f}s, Transcripts read to the last row {scrolled:.1f}s")
    assert built_in < BUILD_SECONDS, built_in
    assert loaded < LOAD_SECONDS, loaded
    assert scrolled < SCROLL_SECONDS, scrolled
    binary = _soffice()
    if binary is None:
        if os.environ.get("REQUIRE_LIBREOFFICE") == "1":
            pytest.fail("LibreOffice is required on CI but soffice is not on PATH")
        pytest.skip("LibreOffice is not installed here")
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "main.xlsx")
        shutil.copy(out / "after-main.xlsx", source)
        seconds = _convert(binary, source, tmp)
        print(f"main: soffice --convert-to csv {seconds:.1f}s")
        assert seconds < CONVERT_SECONDS, seconds


def test_row_kinds_survive_in_the_writer() -> None:
    sheet = Sheet("pairs", "Pairs", [Column("text", "Text", "longtext")], [{ROW_KIND: "summary", "text": contract.Rich((("Asha", True), ("  hello", False)))}], count=1)
    payload = _write([sheet])
    assert "<b/>" in parts_of(payload)["xl/sharedStrings.xml"]


def test_a_streamed_sheet_with_links_and_no_count_is_counted_then_written(monkeypatch: pytest.MonkeyPatch) -> None:
    def rows() -> Any:
        for i in range(30):
            yield {"name": f"Row {i}", "open": Link("Open", f"https://portal.example.org/data/go/open/artisan/a{i}")}

    sheet = Sheet("streamed", "Streamed", [Column("name", "Name", "text"), Column("open", "Open", "link")], rows())

    async def chosen(_ctx: Any, _failed: list[str]) -> list[tuple[Any, Sheet]]:
        return [(None, sheet)]

    monkeypatch.setattr(build, "collect_sheets", chosen)
    parts = asyncio.run(build.render_parts(rf.context(contract.ReportOptions(sheets=["Streamed"]))))
    ws = _book(parts.main)["Streamed"]
    assert sum(1 for r in ws.iter_rows() for c in r if c.hyperlink is not None and c.row > writer.TOP) == 30
