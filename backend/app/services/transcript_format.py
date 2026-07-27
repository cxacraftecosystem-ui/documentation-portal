"""Stored Markdown transcripts -> Excel rich text.

Transcripts are persisted exactly as the refinement pass writes them (see
``ai.refine_transcript_text``): one speaker turn per line, each opening with a bold
``**Interviewer:**`` / ``**Interviewee 2:**`` label, with a ``---`` rule between topics. That is
the right form for the ``.md``/``.txt`` files the manifest hands out — those go to a Markdown
reader. A spreadsheet has no Markdown reader, so the same text lands in the cell with its
asterisks showing and, because Excel gives a cell a single line unless it is told otherwise, the
whole interview reads as one unbroken blob.

``transcript_cell`` re-expresses that Markdown in the only formatting Excel understands inside a
cell: a ``CellRichText`` of plain and bold runs. The speaker label becomes a real bold run and its
asterisks disappear, every turn is forced onto its own line even when the model ran several onto
one, and a horizontal rule becomes the blank line it was standing in for. The model also reaches
for the rest of its Markdown vocabulary unprompted, so a ``## Section`` heading becomes a bold
line after a gap, a ``- item`` becomes a real bullet and a ``code span`` loses its backticks —
each of which otherwise showed up in the cell as punctuation the reader has to look past.

Underscore emphasis (``_like this_``) is deliberately NOT recognised: transcripts carry file
names, object keys and identifiers full of underscores, and reading those as italics would eat
characters that are part of the data. Nothing here is reworded — this only changes how the same
characters are marked up.

Rich text needs openpyxl >= 3.1 (pyproject pins >= 3.1.2). The caller renders these cells with
``wrap_text`` on, without which the embedded newlines are written but never shown.
"""

import re
from typing import Any

from openpyxl.cell.rich_text import CellRichText, TextBlock
from openpyxl.cell.text import InlineFont

# A rule line stands in for a topic break; Markdown accepts all three fence characters.
_RULE_RE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
# "## Dyeing" — a section the model opened. Excel has no heading style inside a cell, so the
# heading becomes a bold line with a gap above it, which is what a heading is for anyway.
_HEADING_RE = re.compile(r"^\s*#{1,6}\s+(\S.*?)\s*#*\s*$")
# "- warp set at dawn" / "* warp set at dawn". Turned into a real bullet before the emphasis pass
# runs, so a "*" opening a list item is never mistaken for the start of an italic span.
_BULLET_RE = re.compile(r"^\s*[-*+]\s+(?=\S)")
# ``indigo vat`` — code spans mean nothing in a transcript; keep the words, drop the backticks.
_CODE_RE = re.compile(r"`([^`\n]+)`")
_BULLET = "•  "
# Models drift between "**Interviewer:**" and "**Interviewer**:"; fold the stray colon inside the
# emphasis so there is a single speaker shape left to recognise.
_LOOSE_LABEL_RE = re.compile(r"\*\*\s*([^*\n]{1,60}?)\s*\*\*\s*:")
# Only a bold span carrying a colon is a speaker label — plain **emphasis** mid-sentence is not,
# and must not start a new line.
_SPEAKER_RE = re.compile(r"\*\*[^*\n]{1,60}?:\*\*")
# Bold before italic, so "**x**" is never read as an empty italic wrapping "*x*".
_EMPHASIS_RE = re.compile(r"\*\*\s*([^*\n]+?)\s*\*\*|\*\s*([^*\n]+?)\s*\*")

_BOLD = InlineFont(b=True)
_ITALIC = InlineFont(i=True)


def _display_lines(markdown: str) -> list[str]:
    """The transcript as the lines a reader should see: one speaker turn each, rules blanked.

    A heading is rewritten as bold and given a blank line above rather than being carried as its
    own kind of line: the emphasis pass below already knows how to make a bold run, and a bold
    line standing after a gap is the whole of what a heading does inside a spreadsheet cell.
    """
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
        # A later label on the same line means the model ran two turns together; cut before it.
        cuts = [m.start() for m in _SPEAKER_RE.finditer(line) if m.start() > 0]
        bounds = [0, *cuts, len(line)]
        lines.extend(line[start:end].strip() for start, end in zip(bounds, bounds[1:]))
    return lines


def _tidy(lines: list[str]) -> list[str]:
    """Drop the leading/trailing blanks and collapse blank runs, so one rule reads as one gap."""
    kept: list[str] = []
    for line in lines:
        if line or (kept and kept[-1]):
            kept.append(line)
    while kept and not kept[-1]:
        kept.pop()
    return kept


def _runs(line: str) -> list[Any]:
    """One display line as plain and emphasised pieces, with the asterisks dropped."""
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
    """A stored Markdown transcript as a cell value that reads as formatted dialogue.

    Returns ``""`` for an empty transcript so the cell stays a plain blank rather than an empty
    rich-text object.
    """
    lines = _tidy(_display_lines(str(text or "")))
    if not lines:
        return ""
    rich = CellRichText()
    for index, line in enumerate(lines):
        if index:
            rich.append("\n")
        rich.extend(_runs(line))
    return rich
