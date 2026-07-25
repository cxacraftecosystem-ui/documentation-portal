"""Styled .xlsx workbook builder for the /data/report relational report.

The data_browser route assembles the report as a list of plain-dict *sheets* (one per record
type) and hands them here to be rendered into an Excel workbook:

    sheet = {"name": str, "color": "#RRGGBB", "columns": [label, ...], "rows": [[cell, ...], ...]}

``build_report_workbook`` turns those into a workbook with:

- a leading **Overview** sheet listing every data sheet, its row count, and an internal
  hyperlink that jumps to it,
- per-sheet styling: a bold white header row on the sheet's brand colour, a matching tab colour,
  thin header borders, a frozen header row (``freeze_panes = "A2"``) and column widths sized to
  content (capped so a transcript column can't blow the layout out),
- defensive cell handling: illegal XML control characters are stripped and over-long strings are
  clipped to Excel's hard 32767-char cell limit, so real transcript/notes text never corrupts the
  file.

openpyxl is synchronous; the caller runs this inside ``asyncio.to_thread``. The single public
entry point returns the finished workbook as ``bytes``.
"""

from io import BytesIO
from typing import Any

from openpyxl import Workbook
from openpyxl.cell.cell import ILLEGAL_CHARACTERS_RE
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.hyperlink import Hyperlink
from openpyxl.worksheet.worksheet import Worksheet

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

# Excel's hard per-cell character ceiling; longer values corrupt the file.
_MAX_CELL_CHARS = 32767
# Column width is measured in characters; keep even a long transcript column readable.
_MAX_COL_WIDTH = 60
_MIN_COL_WIDTH = 10
# Excel worksheet-title rules: <= 31 chars, none of these characters.
_TITLE_MAX = 31
_TITLE_BAD = set(r"[]:*?/\\")

_OVERVIEW_NAME = "Overview"
_OVERVIEW_COLOR = "5B21B6"  # purple, matching the Workshops brand tone

_WHITE = "FFFFFFFF"
_HYPERLINK_BLUE = "FF1D4ED8"

_THIN = Side(style="thin", color="FFBFBFBF")
_HEADER_BORDER = Border(left=_THIN, right=_THIN, top=_THIN, bottom=_THIN)
_HEADER_ALIGN = Alignment(horizontal="left", vertical="center", wrap_text=False)
_CELL_ALIGN = Alignment(horizontal="left", vertical="top", wrap_text=False)


def _rgb(color: str | None) -> str:
    """'#5B21B6' / '5b21b6' -> 'FF5B21B6' (openpyxl wants opaque 8-hex ARGB)."""
    hexpart = (color or "").lstrip("#").strip().upper()
    if len(hexpart) == 6:
        return f"FF{hexpart}"
    if len(hexpart) == 8:
        return hexpart
    return f"FF{_OVERVIEW_COLOR}"


def _tab_color(color: str | None) -> str:
    # Full opaque ARGB: a bare 6-hex value gets a 00 (transparent) alpha and the tab shows blank.
    return _rgb(color)


def _clean(value: Any) -> Any:
    """Make a value safe for an Excel cell: strip illegal control chars, clip over-long text."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return value
    text = value if isinstance(value, str) else str(value)
    text = ILLEGAL_CHARACTERS_RE.sub("", text)
    if len(text) > _MAX_CELL_CHARS:
        text = text[: _MAX_CELL_CHARS - 1] + "…"
    return text


def _safe_title(name: str, used: set[str]) -> str:
    """A legal, unique worksheet title for ``name`` (Excel: <= 31 chars, no []:*?/\\)."""
    base = "".join(ch for ch in (name or "").strip() if ch not in _TITLE_BAD) or "Sheet"
    base = base[:_TITLE_MAX]
    candidate = base
    n = 2
    while candidate.lower() in used:
        suffix = f" ({n})"
        candidate = base[: _TITLE_MAX - len(suffix)] + suffix
        n += 1
    used.add(candidate.lower())
    return candidate


def _autosize(ws: Worksheet, columns: list[str], rows: list[list[Any]]) -> None:
    for idx, header in enumerate(columns):
        longest = len(str(header))
        for row in rows:
            if idx < len(row) and row[idx] is not None:
                # Widen to the longest single line only — newlines shouldn't stretch the column.
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
        cell = ws.cell(row=1, column=idx, value=_clean(label))
        cell.font = Font(bold=True, color=_WHITE)
        cell.fill = fill
        cell.border = _HEADER_BORDER
        cell.alignment = _HEADER_ALIGN

    for r, row in enumerate(rows, start=2):
        for idx, value in enumerate(row, start=1):
            cell = ws.cell(row=r, column=idx, value=_clean(value))
            cell.alignment = _CELL_ALIGN

    if columns:
        ws.freeze_panes = "A2"
    _autosize(ws, columns, rows)


def _write_overview(ws: Worksheet, title: str, sheets: list[dict[str, Any]]) -> None:
    ws.sheet_properties.tabColor = _rgb(_OVERVIEW_COLOR)

    heading = ws.cell(row=1, column=1, value=_clean(title) or "Report")
    heading.font = Font(bold=True, size=14, color=f"FF{_OVERVIEW_COLOR}")

    headers = ["Sheet", "Rows"]
    fill = PatternFill(fill_type="solid", start_color=_rgb(_OVERVIEW_COLOR))
    for idx, label in enumerate(headers, start=1):
        cell = ws.cell(row=3, column=idx, value=label)
        cell.font = Font(bold=True, color=_WHITE)
        cell.fill = fill
        cell.border = _HEADER_BORDER
        cell.alignment = _HEADER_ALIGN

    for offset, sheet in enumerate(sheets):
        r = 4 + offset
        safe = sheet["_title"]
        link = ws.cell(row=r, column=1, value=sheet.get("name") or safe)
        link.hyperlink = Hyperlink(ref="", location=f"'{safe}'!A1", display=safe)
        link.font = Font(color=_HYPERLINK_BLUE, underline="single")
        ws.cell(row=r, column=2, value=len(sheet.get("rows") or []))

    ws.column_dimensions["A"].width = 28
    ws.column_dimensions["B"].width = 12
    ws.freeze_panes = "A4"


def build_report_workbook(sheets: list[dict[str, Any]], title: str) -> bytes:
    """Render the report ``sheets`` into a styled .xlsx workbook and return its bytes.

    ``sheets`` is the list produced by data_browser's ``_report_sheets`` — each a dict of
    ``{name, color (#hex), columns, rows}``. A leading Overview sheet is prepended.
    """
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
