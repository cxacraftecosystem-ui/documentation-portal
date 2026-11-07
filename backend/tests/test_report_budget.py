from __future__ import annotations

import asyncio
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
from app.services.datareport.contract import ROW_KIND, Column, Link, Sheet
from app.services.datareport.sheets import core_transcripts
from tests import report_fixtures as rf

openpyxl = pytest.importorskip("openpyxl")

MAIN_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
CELL_LIMIT = 32767


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
        out[m.group(1)] = parts[f"xl/{target}"]
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


def test_the_default_report_keeps_every_budget() -> None:
    parts = _render(contract.ReportOptions(extras={"everything": True}))
    found = assert_budgets(parts.main)
    assert parts.companion is None
    assert found["xml"] < writer.XML_BUDGET


def test_transcript_links_are_one_set_per_recording_never_per_segment() -> None:
    parts = _render()
    ws = _book(parts.main)["Transcripts"]
    links = [(c.row, c.hyperlink.target) for r in ws.iter_rows() for c in r if c.hyperlink is not None and c.row > writer.TOP]
    header = _header(ws)
    kinds = {r: ws.cell(row=r, column=header.index("Row") + 1).value for r, _url in links}
    assert all(str(kind).startswith("Whole transcript") for kind in kinds.values())
    assert sum(1 for _r, url in links if "t=" in url) == 2
    starts = [ws.cell(row=r, column=header.index("Start") + 1).value for r in range(writer.TOP + 2, ws.max_row + 1) if ws.cell(row=r, column=header.index("Row") + 1).value == "Original"]
    assert starts and all(isinstance(v, str) and re.fullmatch(r"\d+:\d{2}", v) for v in starts)


def test_everything_has_no_per_row_links_and_names_the_record_id() -> None:
    parts = _render(contract.ReportOptions(extras={"everything": True}))
    sheets = sheet_parts(parts.main)
    assert sheets["Everything"].count("<hyperlink ") == 1
    ws = _book(parts.main)["Everything"]
    header = _header(ws)
    assert "Open" not in header and "Record ID" in header
    assert header.index("Record ID") not in hidden_columns(ws)


def test_data_rows_carry_no_formulas() -> None:
    parts = _render(contract.ReportOptions(extras={"everything": True}))
    found = budget_report(parts.main)
    book = _book(parts.main)
    for name, ws in [(n, book[n]) for n in book.sheetnames]:
        for row in ws.iter_rows(min_row=writer.TOP + 2):
            for cell in row:
                if isinstance(cell.value, str) and cell.value.startswith("="):
                    assert ws.cell(row=cell.row, column=1).value == "Total", (name, cell.coordinate, cell.value)
    assert all(f.startswith("SUBTOTAL(") for _n, f in found["formulas"])


def _link_sheet(rows: int) -> Sheet:
    columns = [Column("name", "Name", "text"), Column("open", "Open", "link"), Column("id", "ID", "id")]
    data = [{"name": f"Row {i}", "open": Link("Open", f"https://portal.example.org/data/go/open/artisan/a{i}"), "id": f"a{i}"} for i in range(rows)]
    return Sheet("many", "Many links", columns, data, count=rows)


def _write(sheets: list[Sheet]) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "links.xlsx")
        book = writer.ReportWriter(path, rf.context())
        for sheet in sheets:
            state = book.begin(sheet, bundle_url=None)
            state.write_rows(list(sheet.rows))
            book.end(state)
        book.write_cover(everything_url=None, everything_files=0, everything_bytes=0, kpis=[], charts=[])
        book.write_dictionary()
        writer.close(book)
        return Path(path).read_bytes()


def test_a_sheet_over_the_link_cap_writes_web_addresses_as_text_with_a_banner() -> None:
    payload = _write([_link_sheet(writer.LINKS_PER_SHEET + 10)])
    assert_budgets(payload)
    ws = _book(payload)["Many links"]
    assert ws.cell(row=4, column=1).value == writer.TEXT["banner.urlText"]
    header = _header(ws)
    assert header[:3] == ["Name", "Open", "Open (web address)"]
    assert ws.cell(row=writer.TOP + 2, column=3).value == "https://portal.example.org/data/go/open/artisan/a0"
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


def test_tables_only_on_sheets_under_the_size_budget() -> None:
    small = Sheet("small", "Small", [Column("name", "Name", "text")], [{"name": "a"}], count=1)
    big_rows = writer.TABLE_ROWS_MAX + 5
    big = Sheet("big", "Big", [Column("name", "Name", "text"), Column("n", "N", "int", total="sum")], [{"name": f"r{i}", "n": i} for i in range(big_rows)], count=big_rows)
    payload = _write([small, big])
    sheets = sheet_parts(payload)
    assert "<tablePart " in sheets["Small"]
    assert "<tablePart " not in sheets["Big"] and "<autoFilter " in sheets["Big"]
    assert re.search(r"<f>SUBTOTAL\(109,B6:B\d+\)</f>", sheets["Big"])


def test_technical_columns_sit_in_a_collapsed_group_at_the_right() -> None:
    parts = _render()
    book = _book(parts.main)
    ws = book["Artisans"]
    header = _header(ws)
    tech = [i for i, h in enumerate(header) if h and (h.endswith(" ID") or h in ("Created by", "Updated by", "Created at", "Updated at"))]
    assert tech
    hidden = hidden_columns(ws)
    visible = [i for i, h in enumerate(header) if h and i not in hidden]
    assert max(visible) < min(tech)
    for i in tech:
        assert hidden.get(i) == 1, header[i]
    assert ws.freeze_panes == f"B{writer.TOP + 2}"
    described = {(r[0], r[1]) for r in _rows(book["Data dictionary"]) if r[0]}
    for label in header:
        assert ("Artisans", label) in described


def test_the_sheet_xml_marks_the_group_collapsed() -> None:
    parts = _render()
    xml = sheet_parts(parts.main)["Artisans"]
    assert re.search(r'<col [^>]*hidden="1"[^>]*outlineLevel="1"', xml)
    assert 'collapsed="1"' in xml


def hidden_columns(ws: Any) -> dict[int, int]:
    out: dict[int, int] = {}
    for dim in ws.column_dimensions.values():
        if dim.hidden and dim.min and dim.max:
            for index in range(dim.min, dim.max + 1):
                out[index - 1] = dim.outline_level
    return out


def _whole_rows(ws: Any) -> list[dict[str, Any]]:
    header = _header(ws)
    out = []
    for row in ws.iter_rows(min_row=writer.TOP + 2):
        values = {header[i]: c.value for i, c in enumerate(row) if i < len(header) and header[i]}
        if str(values.get("Row") or "").startswith("Whole transcript"):
            out.append(values)
    return out


def test_whole_transcripts_read_as_dialogue_with_bold_speakers() -> None:
    from openpyxl.cell.rich_text import CellRichText, TextBlock

    parts = _render()
    ws = _book(parts.main, rich_text=True)["Transcripts"]
    header = _header(_book(parts.main)["Transcripts"])
    text_col = header.index("Text") + 1
    row_col = header.index("Row") + 1
    found = []
    for r in range(writer.TOP + 2, ws.max_row + 1):
        kind = ws.cell(row=r, column=row_col).value
        if kind in ("Whole transcript", "Whole transcript · English translation"):
            found.append((kind, ws.cell(row=r, column=text_col).value))
    rich = [v for _k, v in found if isinstance(v, CellRichText)]
    assert rich
    for value in rich:
        bold = [b.text for b in value if isinstance(b, TextBlock) and b.font is not None and b.font.b]
        assert "Kamla" in bold
        for block in value:
            if isinstance(block, TextBlock):
                assert block.font is not None and block.font.rFont is not None and block.font.sz is not None
    original = next(v for k, v in found if k == "Whole transcript" and isinstance(v, CellRichText))
    english = next(v for k, v in found if k != "Whole transcript" and isinstance(v, CellRichText))
    o_lines, e_lines = str(original).split("\n"), str(english).split("\n")
    assert len(o_lines) == len(e_lines) == 2
    assert [line.split("  ")[0] for line in o_lines] == [line.split("  ")[0] for line in e_lines]
    assert o_lines[0].startswith("Kamla  0:00  ")
    assert e_lines[0] == "Kamla  0:00  The indigo colour"


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


def test_consecutive_segments_by_one_speaker_make_one_turn() -> None:
    turns = core_transcripts.group_turns(_speaker_rows(8), {"s0": "Asha", "s1": "Mohan"}, "Not requested")
    assert [t.speaker for t in turns] == ["Asha", "Mohan", "Asha", "Mohan"]
    assert turns[0].text == "line 0 line 1" and turns[0].english_text == "english 0 english 1"
    assert turns[1].start == 2000 and turns[1].end == 3900
    assert turns[0].ids == ["t0", "t1"]


def test_long_transcripts_split_into_labelled_continuation_rows_under_the_cell_limit() -> None:
    sentence = "नील का रंग पक्का करने के लिए हम पहले कपड़े को धोते हैं " * 40
    english = "To fix the indigo we first wash the cloth " * 40
    lines = [(core_transcripts.line_runs(f"Speaker {i % 2}", i * 1000, sentence), core_transcripts.line_runs(f"Speaker {i % 2}", i * 1000, english)) for i in range(60)]
    parts = core_transcripts.dialogue_parts(lines)
    assert len(parts) > 1
    total_lines = 0
    for original, translated in parts:
        assert len(original.text) <= CELL_LIMIT and len(translated.text) <= CELL_LIMIT
        assert original.text.count("\n") == translated.text.count("\n")
        total_lines += original.text.count("\n") + 1
    assert total_lines == 60


def test_a_long_recording_gets_part_rows_paired_with_their_translation() -> None:
    raw = rf.production_raw(recordings=1, segments_each=400)

    def long_raw(sql: str, *args: Any) -> list[dict[str, Any]]:
        out = raw(sql, *[["m0"] if a and isinstance(a, list) and "m0" in a else a for a in args])
        if 'FROM "TranscriptTurn"' in sql and "GROUP BY" not in sql and "octet_length" not in sql:
            for row in out:
                row["machineText"] = row["machineText"] * 3
        return out

    ctx = rf.context(db=rf.FakeDB(raw=long_raw))
    payload = asyncio.run(build.render(ctx))
    ws = _book(payload)["Transcripts"]
    kinds = [r["Row"] for r in _whole_rows(ws) if r.get("File") == "talk-0.m4a"]
    assert len(kinds) >= 4
    assert kinds[0].startswith("Whole transcript (part 1 of ")
    assert kinds[1].startswith("Whole transcript · English translation (part 1 of ")
    for row in _whole_rows(ws):
        assert len(str(row.get("Text") or "")) <= CELL_LIMIT


def test_a_report_over_the_size_budget_moves_turns_to_a_companion_workbook() -> None:
    ctx = rf.context()
    ctx.shared["transcriptDetailBytes"] = build.DETAIL_INLINE_MAX + 1
    parts = asyncio.run(build.render_parts(ctx))
    assert parts.companion is not None
    main = _book(parts.main)["Transcripts"]
    kinds = {r[_header(main).index("Row")] for r in _rows(main)[writer.TOP + 1 :] if r[0]}
    assert kinds == {"Whole transcript", "Whole transcript · English translation"}
    banner = " ".join(str(c) for r in _rows(main)[:4] for c in r if c)
    assert "companion workbook" in banner and "Report-Transcripts.xlsx" in banner
    companion = _book(parts.companion)
    assert companion.sheetnames == ["Overview", "Transcripts", "Data dictionary"]
    turns = companion["Transcripts"]
    header = _header(turns)
    kinds = [r[header.index("Row")] for r in _rows(turns)[writer.TOP + 1 :] if r[0]]
    assert kinds and set(kinds) == {"Original", "English translation"}
    for index, kind in enumerate(kinds):
        if kind == "Original":
            assert kinds[index + 1] == "English translation"
    assert_budgets(parts.companion)


def test_a_split_report_downloads_as_one_zip_when_asked() -> None:
    ctx = rf.context()
    built = build.BuiltReport(b"PK\x03\x04main", "DesignWorkshop_Dataset_2026-11-08_Report.xlsx", ctx, companion=b"PK\x03\x04turns", companion_name="DesignWorkshop_Dataset_2026-11-08_Report-Transcripts.xlsx")
    payload, name, mime = build.deliverable(built, parts=True)
    assert name.endswith("_Report.zip") and mime == build.ZIP_MIME
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        assert z.namelist() == ["DesignWorkshop_Dataset_2026-11-08_Report.xlsx", "DesignWorkshop_Dataset_2026-11-08_Report-Transcripts.xlsx"]
    assert build.deliverable(built, parts=False) == (b"PK\x03\x04main", built.file_name, build.XLSX_MIME)
    single = build.BuiltReport(b"PK\x03\x04main", "a.xlsx", ctx)
    assert build.deliverable(single, parts=True)[1] == "a.xlsx"


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


def test_libreoffice_and_openpyxl_open_the_dialogue_workbook_without_repair() -> None:
    ctx = rf.context()
    ctx.shared["transcriptDetailBytes"] = build.DETAIL_INLINE_MAX + 1
    parts = asyncio.run(build.render_parts(ctx))
    for payload in (parts.main, parts.companion):
        rf.validate(payload)
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            openpyxl.load_workbook(io.BytesIO(payload), rich_text=True)
        assert [str(w.message) for w in caught if "extension" not in str(w.message).lower()] == []
    binary = _soffice()
    if binary is None:
        if os.environ.get("REQUIRE_LIBREOFFICE") == "1":
            pytest.fail("LibreOffice is required on CI but soffice is not on PATH")
        pytest.skip("LibreOffice is not installed here")
    with tempfile.TemporaryDirectory() as tmp:
        for index, payload in enumerate((parts.main, parts.companion)):
            source = os.path.join(tmp, f"book{index}.xlsx")
            Path(source).write_bytes(payload)
            _convert(binary, source, tmp)


PRODUCTION = os.environ.get("REPORT_PRODUCTION") == "1"
LOAD_SECONDS = float(os.environ.get("REPORT_LOAD_SECONDS") or 45)
CONVERT_SECONDS = float(os.environ.get("REPORT_CONVERT_SECONDS") or 90)


@pytest.mark.skipif(not PRODUCTION, reason="the production-sized budget runs in its own CI job (REPORT_PRODUCTION=1)")
def test_a_production_sized_report_keeps_every_budget_and_opens_fast() -> None:
    ctx = rf.production_context()
    started = time.monotonic()
    parts = asyncio.run(build.render_parts(ctx))
    built_in = time.monotonic() - started
    out = Path(os.environ.get("REPORT_OUT") or tempfile.mkdtemp())
    out.mkdir(parents=True, exist_ok=True)
    (out / "after-main.xlsx").write_bytes(parts.main)
    rows = {}
    for label, payload in (("main", parts.main), ("companion", parts.companion)):
        assert payload is not None, f"the production fixture must split; {label} is missing"
        found = assert_budgets(payload)
        assert found["xml"] < writer.XML_BUDGET, (label, found["xml"])
        (out / f"after-{label}.xlsx").write_bytes(payload)
        started = time.monotonic()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            book = openpyxl.load_workbook(io.BytesIO(payload))
        loaded = time.monotonic() - started
        rows[label] = {name: book[name].max_row for name in book.sheetnames}
        print(f"{label}: xml {found['xml'] / 1e6:.1f} MB, links {found['total']}, formulas {len(found['formulas'])}, openpyxl load {loaded:.1f}s")
        assert loaded < LOAD_SECONDS, (label, loaded)
    print(f"build {built_in:.1f}s")
    assert rows["main"]["Everything"] - writer.TOP - 1 >= 34000
    segments = sum(len(rf.production_turns(f"v-m{i}", 49)) for i in range(420))
    assert segments >= 20000
    assert rows["companion"]["Transcripts"] > 2 * 420
    binary = _soffice()
    if binary is None:
        if os.environ.get("REQUIRE_LIBREOFFICE") == "1":
            pytest.fail("LibreOffice is required on CI but soffice is not on PATH")
        pytest.skip("LibreOffice is not installed here")
    with tempfile.TemporaryDirectory() as tmp:
        for label in ("main", "companion"):
            source = os.path.join(tmp, f"{label}.xlsx")
            shutil.copy(out / f"after-{label}.xlsx", source)
            seconds = _convert(binary, source, tmp)
            print(f"{label}: soffice --convert-to csv {seconds:.1f}s")
            assert seconds < CONVERT_SECONDS, (label, seconds)


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
