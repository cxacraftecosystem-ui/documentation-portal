import re
from typing import Any

from openpyxl.cell.rich_text import CellRichText, TextBlock
from openpyxl.cell.text import InlineFont

_RULE_RE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
_HEADING_RE = re.compile(r"^\s*#{1,6}\s+(\S.*?)\s*#*\s*$")
_BULLET_RE = re.compile(r"^\s*[-*+]\s+(?=\S)")
_CODE_RE = re.compile(r"`([^`\n]+)`")
_BULLET = "•  "
_LOOSE_LABEL_RE = re.compile(r"\*\*\s*([^*\n]{1,60}?)\s*\*\*\s*:")
_SPEAKER_RE = re.compile(r"\*\*[^*\n]{1,60}?:\*\*")
_EMPHASIS_RE = re.compile(r"\*\*\s*([^*\n]+?)\s*\*\*|\*\s*([^*\n]+?)\s*\*")

_BOLD = InlineFont(b=True)
_ITALIC = InlineFont(i=True)


def _display_lines(markdown: str) -> list[str]:
    lines: list[str] = []
    for raw in markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if _RULE_RE.match(raw):
            lines.append("")
            continue
        heading = _HEADING_RE.match(raw)
        if heading:
            text = _CODE_RE.sub(r"\1", heading.group(1))
            lines.extend(("", text if "**" in text else f"**{text}**"))
            continue
        line = _CODE_RE.sub(r"\1", _BULLET_RE.sub(_BULLET, raw.strip()))
        line = _LOOSE_LABEL_RE.sub(r"**\1:**", line)
        cuts = [m.start() for m in _SPEAKER_RE.finditer(line) if m.start() > 0]
        bounds = [0, *cuts, len(line)]
        lines.extend(line[start:end].strip() for start, end in zip(bounds, bounds[1:]))
    return lines


def _tidy(lines: list[str]) -> list[str]:
    kept: list[str] = []
    for line in lines:
        if line or (kept and kept[-1]):
            kept.append(line)
    while kept and not kept[-1]:
        kept.pop()
    return kept


def _runs(line: str) -> list[Any]:
    pieces: list[Any] = []
    pos = 0
    for match in _EMPHASIS_RE.finditer(line):
        if match.start() > pos:
            pieces.append(line[pos : match.start()])
        bold, italic = match.groups()
        pieces.append(TextBlock(_BOLD, bold) if bold else TextBlock(_ITALIC, italic))
        pos = match.end()
    if pos < len(line):
        pieces.append(line[pos:])
    return pieces


def transcript_cell(text: Any) -> Any:
    lines = _tidy(_display_lines(str(text or "")))
    if not lines:
        return ""
    rich = CellRichText()
    for index, line in enumerate(lines):
        if index:
            rich.append("\n")
        rich.extend(_runs(line))
    return rich
