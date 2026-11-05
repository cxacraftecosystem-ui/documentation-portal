import re
import posixpath
import xml.etree.ElementTree as ET
import zipfile
from io import BytesIO

import pytest
from openpyxl import load_workbook
from openpyxl.cell.rich_text import CellRichText, TextBlock
from openpyxl.cell.text import InlineFont

from app.services.transcript_format import transcript_cell
from app.services.xlsx_report import build_report_workbook

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
CONTENT_TYPES = "{http://schemas.openxmlformats.org/package/2006/content-types}"
RELS = "{http://schemas.openxmlformats.org/package/2006/relationships}"

MAX_CELL_CHARS = 32767
TITLE_BAD = set(r"[]:*?/\\")

_ILLEGAL = re.compile(
    "[\x00-\x08\x0b\x0c\x0e-\x1f"
    f"{chr(0xD800)}-{chr(0xDFFF)}"
    f"{chr(0xFDD0)}-{chr(0xFDEF)}"
    + "".join(chr(p * 0x10000 + o) for p in range(17) for o in (0xFFFE, 0xFFFF))
    + "]"
)
_CHAR_REF = re.compile(r"&#(\d+);")


LONG_TRANSCRIPT = (
    "**Interviewer:** " + "तानाबाना बुनाई की प्रक्रिया बहुत पुरानी है। " * 900
    + "\n**Interviewee:** " + "The warp is set at dawn and the weft follows. " * 900
)

TRANSCRIPT = (
    "## Session 1: the loom\n"
    "**Interviewer:** How long have you worked the loom?\n"
    "---\n"
    "**Interviewee 2**: Forty years. My father taught me. *He never used a machine.*\n"
    "- warp set at dawn\n"
    "- weft after the meal\n"
    "### Dyeing\n"
    "**Interviewer:** And the dye? Ask for the `indigo vat`. 🧵\n"
)

DIRTY_NOTE = "Bell\x07 null\x00 vtab\x0b half-emoji \ud83d noncharacter ￾﷐\U0001FFFE end"


def _sheets():
    return [
        {
            "name": "All records",
            "color": "#5B21B6",
            "columns": ["Note", "Kind", "Count"],
            "rows": [
                ["=> comb the weft before the shed opens", "Loom notes", 3],
                ["==== DAY 2 ====", "Process", 4],
                ["#REF!", "Reference", 5],
                [DIRTY_NOTE, "Field notes", 6],
                ["चरखा / spinning wheel 🧵", "Devanagari", 7],
                [None, "", 0],
            ],
            "prose": set(),
        },
        {
            "name": "Media by type / hierarchy [live]: everything we hold",
            "color": "#0F766E",
            "columns": ["Type", "Transcript"],
            "rows": [
                ["audio/mp4", TRANSCRIPT],
                ["audio/mp4", LONG_TRANSCRIPT],
                ["audio/mp4", ""],
                ["audio/mp4", CellRichText([TextBlock(InlineFont(b=True), " "), ""])],
                ["audio/mp4", CellRichText(["\x00", TextBlock(InlineFont(), "\ud83d")])],
            ],
            "prose": {1},
        },
        {
            "name": "Media by type  hierarchy live: everything we hold too",
            "color": "#B45309",
            "columns": ["Type", "Notes"],
            "rows": [["image/jpeg", "plain"]],
            "prose": set(),
        },
        {
            "name": "Weaver's tools",
            "color": "#BE123C",
            "columns": ["Tool", "Note"],
            "rows": [["Charkha", "fine"]],
            "prose": set(),
        },
        {
            "name": "History",
            "color": "#1D4ED8",
            "columns": ["When", "What"],
            "rows": [["2026", "loom rebuilt"]],
            "prose": set(),
        },
    ]


def _rendered(sheets):
    out = []
    for sheet in sheets:
        prose = sheet.get("prose") or set()
        if not prose:
            out.append(sheet)
            continue
        out.append(
            {
                **sheet,
                "rows": [
                    [transcript_cell(v) if i in prose else v for i, v in enumerate(row)]
                    for row in sheet["rows"]
                ],
            }
        )
    return out


@pytest.fixture(scope="module")
def workbook_bytes() -> bytes:
    return build_report_workbook(_rendered(_sheets()), "Repository report")


@pytest.fixture(scope="module")
def archive(workbook_bytes: bytes) -> zipfile.ZipFile:
    return zipfile.ZipFile(BytesIO(workbook_bytes))


@pytest.fixture(scope="module")
def parts(archive: zipfile.ZipFile) -> dict[str, ET.Element]:
    trees = {}
    for name in archive.namelist():
        if name.endswith((".xml", ".rels")):
            trees[name] = ET.fromstring(archive.read(name))
    return trees



def test_every_xml_part_parses(archive: zipfile.ZipFile) -> None:
    for name in archive.namelist():
        if not name.endswith((".xml", ".rels")):
            continue
        try:
            ET.fromstring(archive.read(name))
        except ET.ParseError as exc:  # pragma: no cover
            pytest.fail(f"{name} is not well-formed XML, so Excel will offer to recover it: {exc}")


def test_no_illegal_codepoint_reaches_the_xml(archive: zipfile.ZipFile) -> None:
    for name in archive.namelist():
        if not name.endswith((".xml", ".rels")):
            continue
        text = archive.read(name).decode("utf-8")
        found = _ILLEGAL.search(text)
        assert found is None, f"{name} carries U+{ord(found.group()):04X} verbatim"
        for ref in _CHAR_REF.finditer(text):
            point = int(ref.group(1))
            assert not _ILLEGAL.match(chr(point)), f"{name} carries &#{point}; (U+{point:04X})"


def test_declared_parts_all_exist(archive: zipfile.ZipFile, parts) -> None:
    names = set(archive.namelist())
    for override in parts["[Content_Types].xml"].findall(f"{CONTENT_TYPES}Override"):
        part = override.get("PartName", "").lstrip("/")
        assert part in names, f"[Content_Types].xml declares {part}, which is not in the archive"


def test_every_relationship_resolves(archive: zipfile.ZipFile, parts) -> None:
    names = set(archive.namelist())
    for name, tree in parts.items():
        if not name.endswith(".rels"):
            continue
        base = posixpath.dirname(posixpath.dirname(name))
        for rel in tree.findall(f"{RELS}Relationship"):
            if rel.get("TargetMode") == "External":
                continue
            target = rel.get("Target", "")
            resolved = (
                target.lstrip("/")
                if target.startswith("/")
                else posixpath.normpath(posixpath.join(base, target))
            )
            assert resolved in names, f"{name} points at {target}, which is missing"


def test_openpyxl_can_read_it_back(workbook_bytes: bytes) -> None:
    wb = load_workbook(BytesIO(workbook_bytes), rich_text=True)
    assert wb.sheetnames[0] == "Overview"
    assert len(wb.sheetnames) == len(_sheets()) + 1



def _worksheets(parts):
    return {n: t for n, t in parts.items() if n.startswith("xl/worksheets/sheet")}


def test_no_text_cell_was_written_as_a_formula(parts) -> None:
    for name, tree in _worksheets(parts).items():
        for cell in tree.iter(f"{MAIN}c"):
            formula = cell.find(f"{MAIN}f")
            assert formula is None, (
                f"{name} {cell.get('r')} was written as a formula: <f>{formula.text}</f>"
            )


def test_the_equals_sign_the_researcher_typed_survives(workbook_bytes: bytes) -> None:
    wb = load_workbook(BytesIO(workbook_bytes))
    notes = [row[0] for row in wb["All records"].iter_rows(min_row=2, values_only=True)]
    assert "=> comb the weft before the shed opens" in notes
    assert "==== DAY 2 ====" in notes
    assert "#REF!" in notes


def test_no_cell_exceeds_the_excel_character_ceiling(parts) -> None:
    for name, tree in _worksheets(parts).items():
        for cell in tree.iter(f"{MAIN}c"):
            total = sum(len(t.text or "") for t in cell.iter(f"{MAIN}t"))
            assert total <= MAX_CELL_CHARS, f"{name} {cell.get('r')} holds {total} characters"


def test_no_rich_text_run_is_empty(parts) -> None:
    for name, tree in _worksheets(parts).items():
        for cell in tree.iter(f"{MAIN}c"):
            for run in cell.iter(f"{MAIN}r"):
                text = run.find(f"{MAIN}t")
                assert text is not None and text.text, f"{name} {cell.get('r')} has an empty run"



def test_sheet_titles_are_legal_and_unique(parts) -> None:
    titles = [s.get("name", "") for s in parts["xl/workbook.xml"].iter(f"{MAIN}sheet")]
    for title in titles:
        assert title, "a sheet was given an empty title"
        assert len(title) <= 31, f"{title!r} is longer than Excel's 31-character limit"
        assert not set(title) & TITLE_BAD, f"{title!r} carries a character Excel forbids"
        assert not title.startswith("'") and not title.endswith("'"), f"{title!r} is quoted"
        assert title.lower() != "history", "'History' is a title Excel reserves"
    lowered = [t.lower() for t in titles]
    assert len(set(lowered)) == len(lowered), f"duplicate sheet titles: {titles}"


def test_overview_links_point_at_sheets_that_exist(parts) -> None:
    titles = {s.get("name", "") for s in parts["xl/workbook.xml"].iter(f"{MAIN}sheet")}
    links = list(parts["xl/worksheets/sheet1.xml"].iter(f"{MAIN}hyperlink"))
    assert links, "the Overview lost its jump links"
    for link in links:
        assert link.get("ref"), "a hyperlink was written without a cell reference"
        location = link.get("location", "")
        quoted, _, remainder = location.partition("!")
        assert remainder == "A1", f"{location!r} does not target a cell"
        assert quoted.startswith("'") and quoted.endswith("'"), f"{location!r} is unquoted"
        inner = quoted[1:-1]
        assert re.fullmatch(r"(?:[^']|'')*", inner), f"{location!r} has a bare apostrophe"
        assert inner.replace("''", "'") in titles, f"{location!r} names no sheet"



def test_transcript_renders_as_formatting_not_as_markdown() -> None:
    rich = transcript_cell(TRANSCRIPT)
    plain = str(rich)
    assert "**" not in plain, "the asterisks are still in the cell"
    assert not plain.lstrip().startswith("#"), "the heading is still written with hashes"
    assert "## " not in plain and "### " not in plain
    assert "`" not in plain, "a code span kept its backticks"
    assert "•  warp set at dawn" in plain, "a list item did not become a bullet"
    assert "Session 1: the loom" in plain and "Dyeing" in plain
    assert "\n" in plain, "every turn is on one line, so the cell reads as a blob"

    bold = {b.text for b in rich if isinstance(b, TextBlock) and b.font.b}
    assert "Interviewer:" in bold, "the speaker label is not bold"
    assert "Session 1: the loom" in bold and "Dyeing" in bold, "headings are not bold"
    italic = {b.text for b in rich if isinstance(b, TextBlock) and b.font.i}
    assert "He never used a machine." in italic


def test_underscores_are_left_alone() -> None:
    assert "media_file_01_raw.wav" in str(transcript_cell("Saved as media_file_01_raw.wav"))


def test_prose_cells_are_wrapped_and_given_room(workbook_bytes: bytes) -> None:
    wb = load_workbook(BytesIO(workbook_bytes))
    ws = wb.worksheets[2]
    cell = ws["B2"]
    assert cell.alignment.wrap_text, "a transcript without wrap_text shows as one crushed line"
    assert ws.column_dimensions["B"].width >= 60, "the transcript column is too narrow to read"
    assert (ws.row_dimensions[2].height or 0) > 15, "the transcript row was left one line tall"
    assert (ws.row_dimensions[2].height or 0) <= 409, "Excel rejects a row taller than 409"


def _tricky_workbook() -> bytes:
    sheets = [
        {
            "name": "Numbers",
            "color": "#ZZZ999",
            "columns": ["Value", "Note"],
            "rows": [
                [float("nan"), "not a number"],
                [float("inf"), "infinite"],
                [True, "a flag"],
                [3.5, CellRichText([TextBlock(InlineFont(), "plain "), TextBlock(InlineFont(b=True), "bold")])],
            ],
            "prose": [],
        },
        {"name": "Tools", "color": "not a colour", "columns": ["Tool"], "rows": [["Charkha"]], "prose": []},
    ]
    return build_report_workbook(sheets, "Tricky report")


def test_a_tricky_workbook_round_trips_through_openpyxl() -> None:
    payload = _tricky_workbook()
    wb = load_workbook(BytesIO(payload), rich_text=True)
    assert wb.sheetnames == ["Overview", "Numbers", "Tools"]
    values = [row[0] for row in wb["Numbers"].iter_rows(min_row=2, values_only=True)]
    assert values[0] is None and values[1] is None
    assert values[2] is True and values[3] == 3.5
    note = wb["Numbers"]["B5"].value
    assert "".join(str(part.text if isinstance(part, TextBlock) else part) for part in note) == "plain bold"
    links = [c.hyperlink.location for c in wb["Overview"]["A"] if c.hyperlink]
    assert links == ["'Numbers'!A1", "'Tools'!A1"]


def test_no_run_carries_an_empty_font() -> None:
    archive = zipfile.ZipFile(BytesIO(_tricky_workbook()))
    for name in archive.namelist():
        if name.startswith("xl/worksheets/sheet"):
            tree = ET.fromstring(archive.read(name))
            for props in tree.iter(f"{MAIN}rPr"):
                assert len(props), f"{name} carries an empty <rPr/>, which Excel repairs"
            for value in tree.iter(f"{MAIN}v"):
                assert value.text not in {"nan", "inf", "-inf"}, f"{name} wrote {value.text}"
