import re
from io import BytesIO
from math import ceil
from typing import Any

from openpyxl import Workbook
from openpyxl.cell.cell import Cell
from openpyxl.cell.rich_text import CellRichText, TextBlock
from openpyxl.cell.text import InlineFont
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.hyperlink import Hyperlink
from openpyxl.worksheet.worksheet import Worksheet

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

_MAX_CELL_CHARS = 32767
_MAX_COL_WIDTH = 60
_MIN_COL_WIDTH = 10
_PROSE_COL_WIDTH = 80
_TITLE_MAX = 31
_TITLE_BAD = set(r"[]:*?/\\")
_TITLE_RESERVED = {"history"}

_OVERVIEW_NAME = "Overview"
_OVERVIEW_COLOR = "5B21B6"

_WHITE = "FFFFFFFF"
_HYPERLINK_BLUE = "FF1D4ED8"

_THIN = Side(style="thin", color="FFBFBFBF")
_HEADER_BORDER = Border(left=_THIN, right=_THIN, top=_THIN, bottom=_THIN)
_HEADER_ALIGN = Alignment(horizontal="left", vertical="center", wrap_text=False)
_CELL_ALIGN = Alignment(horizontal="left", vertical="top", wrap_text=False)
_WRAP_ALIGN = Alignment(horizontal="left", vertical="top", wrap_text=True)
_LINE_HEIGHT = 15
_MAX_WRAP_LINES = 16
_MAX_ROW_HEIGHT = 409.0

_NONCHARACTERS = "".join(
    chr(plane * 0x10000 + offset) for plane in range(1, 17) for offset in (0xFFFE, 0xFFFF)
)
_XML_UNSAFE_RE = re.compile(
    "["
    "\x00-\x08\x0b\x0c\x0e-\x1f"
    "\ud800-\udfff"
    "\ufdd0-\ufdef\ufffe\uffff"
    + _NONCHARACTERS
    + "]"
)

_PLAIN_FONT = InlineFont()


def _rgb(color: str | None) -> str:
    hexpart = (color or "").lstrip("#").strip().upper()
    if len(hexpart) == 6:
        return f"FF{hexpart}"
    if len(hexpart) == 8:
        return hexpart
    return f"FF{_OVERVIEW_COLOR}"


def _tab_color(color: str | None) -> str:
    return _rgb(color)


def _sanitise(text: str) -> str:
    text = _XML_UNSAFE_RE.sub("", text)
    if len(text) > _MAX_CELL_CHARS:
        text = text[: _MAX_CELL_CHARS - 1] + "…"
    return text


def _clean(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, CellRichText):
        return _clean_runs(value) or ""
    if isinstance(value, (int, float)):
        return value
    return _sanitise(value if isinstance(value, str) else str(value))


def _clean_runs(value: CellRichText) -> CellRichText:
    cleaned = CellRichText()
    budget = _MAX_CELL_CHARS
    for run in value:
        block = isinstance(run, TextBlock)
        text = _XML_UNSAFE_RE.sub("", run.text if block else str(run))
        if not text:
            continue
        if len(text) >= budget:
            text = text[: budget - 1] + "…"
            budget = 0
        else:
            budget -= len(text)
        if block:
            font = run.font if isinstance(run.font, InlineFont) else _PLAIN_FONT
            cleaned.append(TextBlock(font, text))
        else:
            cleaned.append(text)
        if not budget:
            break
    return cleaned


def _put(ws: Worksheet, row: int, column: int, value: Any) -> Cell:
    cell = ws.cell(row=row, column=column, value=_clean(value))
    if isinstance(cell.value, str) and cell.data_type != "s":
        cell.data_type = "s"
    return cell


def _wraps(value: Any) -> bool:
    if isinstance(value, CellRichText):
        return True
    return isinstance(value, str) and "\n" in value


def _wrap_height(text: str) -> float:
    lines = sum(max(1, ceil(len(line) / _PROSE_COL_WIDTH)) for line in text.split("\n"))
    return min(min(lines, _MAX_WRAP_LINES) * _LINE_HEIGHT, _MAX_ROW_HEIGHT)


def _safe_title(name: str, used: set[str]) -> str:
    base = "".join(ch for ch in _sanitise(name or "").strip() if ch not in _TITLE_BAD)
    base = base[:_TITLE_MAX].strip().strip("'").strip()
    if not base or base.lower() in _TITLE_RESERVED:
        base = f"{base} sheet".strip() if base else "Sheet"
    candidate = base
    n = 2
    while candidate.lower() in used:
        suffix = f" ({n})"
        candidate = base[: _TITLE_MAX - len(suffix)] + suffix
        n += 1
    used.add(candidate.lower())
    return candidate


def _sheet_ref(title: str) -> str:
    return "'{}'!A1".format(title.replace("'", "''"))


def _autosize(ws: Worksheet, columns: list[str], rows: list[list[Any]], wrapped: set[int]) -> None:
    for idx, header in enumerate(columns):
        if idx in wrapped:
            ws.column_dimensions[get_column_letter(idx + 1)].width = _PROSE_COL_WIDTH
            continue
        longest = len(str(header))
        for row in rows:
            if idx < len(row) and row[idx] is not None:
                cell = str(row[idx])
                line = max((len(part) for part in cell.splitlines()), default=len(cell))
                longest = max(longest, line)
        width = max(_MIN_COL_WIDTH, min(longest + 2, _MAX_COL_WIDTH))
        ws.column_dimensions[get_column_letter(idx + 1)].width = width


def _write_sheet(ws: Worksheet, sheet: dict[str, Any]) -> None:
    columns: list[str] = sheet.get("columns") or []
    rows: list[list[Any]] = sheet.get("rows") or []
    fill = PatternFill(fill_type="solid", start_color=_rgb(sheet.get("color")))
    ws.sheet_properties.tabColor = _tab_color(sheet.get("color"))

    for idx, label in enumerate(columns, start=1):
        cell = _put(ws, 1, idx, label)
        cell.font = Font(bold=True, color=_WHITE)
        cell.fill = fill
        cell.border = _HEADER_BORDER
        cell.alignment = _HEADER_ALIGN

    wrapped: set[int] = set()
    for r, row in enumerate(rows, start=2):
        height = 0.0
        for idx, value in enumerate(row):
            cell = _put(ws, r, idx + 1, value)
            if _wraps(cell.value):
                cell.alignment = _WRAP_ALIGN
                wrapped.add(idx)
                height = max(height, _wrap_height(str(cell.value)))
            else:
                cell.alignment = _CELL_ALIGN
        if height:
            ws.row_dimensions[r].height = height

    if columns:
        ws.freeze_panes = "A2"
    _autosize(ws, columns, rows, wrapped)


def _write_overview(ws: Worksheet, title: str, sheets: list[dict[str, Any]]) -> None:
    ws.sheet_properties.tabColor = _rgb(_OVERVIEW_COLOR)

    heading = _put(ws, 1, 1, title or "Report")
    heading.font = Font(bold=True, size=14, color=f"FF{_OVERVIEW_COLOR}")

    headers = ["Sheet", "Rows"]
    fill = PatternFill(fill_type="solid", start_color=_rgb(_OVERVIEW_COLOR))
    for idx, label in enumerate(headers, start=1):
        cell = _put(ws, 3, idx, label)
        cell.font = Font(bold=True, color=_WHITE)
        cell.fill = fill
        cell.border = _HEADER_BORDER
        cell.alignment = _HEADER_ALIGN

    for offset, sheet in enumerate(sheets):
        r = 4 + offset
        safe = sheet["_title"]
        link = _put(ws, r, 1, sheet.get("name") or safe)
        link.hyperlink = Hyperlink(ref="", location=_sheet_ref(safe), display=safe)
        link.font = Font(color=_HYPERLINK_BLUE, underline="single")
        _put(ws, r, 2, len(sheet.get("rows") or []))

    ws.column_dimensions["A"].width = 40
    ws.column_dimensions["B"].width = 12
    ws.freeze_panes = "A4"


def build_report_workbook(sheets: list[dict[str, Any]], title: str) -> bytes:
    wb = Workbook()

    used_titles: set[str] = {_OVERVIEW_NAME.lower()}
    for sheet in sheets:
        sheet["_title"] = _safe_title(sheet.get("name") or "Sheet", used_titles)

    overview = wb.active
    overview.title = _OVERVIEW_NAME
    _write_overview(overview, title, sheets)

    for sheet in sheets:
        ws = wb.create_sheet(title=sheet["_title"])
        _write_sheet(ws, sheet)

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()
