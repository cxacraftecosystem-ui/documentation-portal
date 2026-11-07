from __future__ import annotations

import zipfile
from io import BytesIO

import pytest
from openpyxl import Workbook, load_workbook
from openpyxl.utils import get_column_letter

from app.services import questionnaire_xlsx as qx

SHEET = "xl/worksheets/sheet1.xml"
HEAD = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
)


def _cell(ref: str, text: str) -> str:
    return f'<c r="{ref}" t="inlineStr"><is><t>{text}</t></is></c>'


def _workbook(sheet_xml: str, extra: tuple[str, int] | None = None) -> bytes:
    base = BytesIO()
    Workbook().save(base)
    out = BytesIO()
    with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            data = sheet_xml.encode() if item.filename == SHEET else src.read(item.filename)
            dst.writestr(item, data)
        if extra is not None:
            name, size = extra
            with dst.open(name, "w") as handle:
                block = b"\0" * (1024 * 1024)
                for _ in range(size // len(block)):
                    handle.write(block)
    return out.getvalue()


def test_a_declared_dimension_does_not_pad_every_row_to_its_width() -> None:
    xml = (
        HEAD
        + '<dimension ref="A1:XFD400"/><sheetData>'
        + '<row r="1">' + _cell("A1", "Question") + "</row>"
        + '<row r="400">' + _cell("A400", "x") + "</row>"
        + "</sheetData></worksheet>"
    )
    data = _workbook(xml)
    assert len(data) < 20_000
    wb = load_workbook(BytesIO(data), data_only=True, read_only=True)
    try:
        rows = qx._sheet_rows(wb.worksheets[0], {})
    finally:
        wb.close()
    assert sum(len(cells) for _n, cells in rows) < 1_000
    assert rows[0][1][0] == "Question"
    assert rows[399][1][0] == "x"


def test_a_workbook_past_the_cell_budget_is_refused() -> None:
    far = get_column_letter(500)
    body = "".join(f'<row r="{n}">{_cell(f"{far}{n}", "x")}</row>' for n in range(1, 4_200))
    xml = HEAD + "<sheetData>" + body + "</sheetData></worksheet>"
    with pytest.raises(qx.QuestionnaireXlsxError) as caught:
        qx.parse_questionnaire_workbook(_workbook(xml))
    assert "too large" in str(caught.value)


def test_an_archive_that_inflates_past_the_ceiling_is_refused_before_parsing() -> None:
    xml = HEAD + '<sheetData><row r="1">' + _cell("A1", "a") + "</row></sheetData></worksheet>"
    data = _workbook(xml, extra=("xl/media/padding.bin", 210 * 1024 * 1024))
    with pytest.raises(qx.QuestionnaireXlsxError) as caught:
        qx.parse_questionnaire_workbook(data)
    assert "too large" in str(caught.value)


def test_rows_are_cut_at_the_column_ceiling() -> None:
    far = get_column_letter(qx.MAX_COLUMNS + 200)
    xml = HEAD + '<sheetData><row r="1">' + _cell("A1", "a") + _cell(f"{far}1", "b") + "</row></sheetData></worksheet>"
    wb = load_workbook(BytesIO(_workbook(xml)), data_only=True, read_only=True)
    try:
        rows = qx._sheet_rows(wb.worksheets[0], {})
    finally:
        wb.close()
    assert len(rows[0][1]) <= qx.MAX_COLUMNS
