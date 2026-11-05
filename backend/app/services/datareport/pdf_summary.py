from __future__ import annotations

import contextvars
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime
from io import BytesIO
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

from app.services.datareport import i18n

logger = logging.getLogger(__name__)

PDF_MIME = "application/pdf"
BRAND = "#762CB1"
BRAND_DEEP = "#4C1D73"
BRAND_SOFT = "#F3EAFA"
BRAND_LINE = "#D9C3EC"
INK = "#1F1530"
MUTED = "#5B5168"
PAPER = "#FFFFFF"
GREEN = "#15803D"
GREEN_SOFT = "#DCFCE7"
GREEN_INK = "#14532D"
AMBER = "#D97706"
AMBER_SOFT = "#FEF3C7"
AMBER_INK = "#78350F"
RED = "#DC2626"
RED_SOFT = "#FEE2E2"
RED_INK = "#7F1D1D"
GREY = "#94A3B8"

FONT_DIR = Path(__file__).resolve().parent / "fonts"
SYSTEM_NOTO = Path("/usr/share/fonts/truetype/noto")

SCRIPTS: tuple[tuple[str, int, int], ...] = (
    ("Devanagari", 0x0900, 0x097F),
    ("Bengali", 0x0980, 0x09FF),
    ("Gurmukhi", 0x0A00, 0x0A7F),
    ("Gujarati", 0x0A80, 0x0AFF),
    ("Oriya", 0x0B00, 0x0B7F),
    ("Tamil", 0x0B80, 0x0BFF),
    ("Telugu", 0x0C00, 0x0C7F),
    ("Kannada", 0x0C80, 0x0CFF),
    ("Malayalam", 0x0D00, 0x0D7F),
)

COVERAGE_ROWS_MAX = 400
SECTIONS_PER_GRID = 14
STATE_LABEL = {"complete": "Done", "partial": "Part", "missing": "None"}
STATE_KEYS = {"complete": "pdf.state.complete", "partial": "pdf.state.partial", "missing": "pdf.state.missing"}
MONTHS_HI = ("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")


def stamp(when: datetime, locale: str, *, zone: bool = False) -> str:
    tail = f" {when.strftime('%Z')}".rstrip() if zone else ""
    if locale == "hi":
        return f"{when.day} {MONTHS_HI[when.month - 1]} {when.year}, {when.strftime('%H:%M')}{tail}"
    return f"{when.strftime('%d %b %Y, %H:%M')}{tail}"
STATE_FILL = {"complete": GREEN_SOFT, "partial": AMBER_SOFT, "missing": RED_SOFT}
STATE_INK = {"complete": GREEN_INK, "partial": AMBER_INK, "missing": RED_INK}


@dataclass
class SectionBar:
    label: str
    complete: int
    partial: int
    missing: int


@dataclass
class CoverageRow:
    name: str
    craft: str
    filled: int
    total: int
    files: int
    transcripts: str
    states: list[str]


@dataclass
class Coverage:
    title: str
    sections: list[tuple[str, str]]
    rows: list[CoverageRow]


@dataclass
class Quote:
    original: str
    english: str
    speaker: str | None
    source: str


@dataclass
class Summary:
    portal_title: str
    folder: str
    path: str
    generated_by: str
    generated_at: datetime
    data_version: str
    options: list[tuple[str, str]] = field(default_factory=list)
    kpis: list[tuple[str, str, str]] = field(default_factory=list)
    media_by_type: list[tuple[str, int, int]] = field(default_factory=list)
    transcript_status: list[tuple[str, int, str]] = field(default_factory=list)
    sections: list[SectionBar] = field(default_factory=list)
    coverage: list[Coverage] = field(default_factory=list)
    quotes: list[Quote] = field(default_factory=list)
    pack_files: list[tuple[str, str]] = field(default_factory=list)
    locale: str = "en"


@dataclass(frozen=True)
class Fonts:
    regular: str
    bold: str
    scripts: dict[str, tuple[str, str]]


_FONTS: Fonts | None = None


def _font_file(stem: str, weight: str) -> Path | None:
    for base in (FONT_DIR, SYSTEM_NOTO):
        candidate = base / f"{stem}-{weight}.ttf"
        if candidate.is_file():
            return candidate
    return None


def _register(name: str, path: Path | None) -> str | None:
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont

    if path is None:
        return None
    if name in pdfmetrics.getRegisteredFontNames():
        return name
    try:
        pdfmetrics.registerFont(TTFont(name, str(path)))
    except Exception:
        logger.warning("pdf_summary: cannot register %s from %s", name, path, exc_info=True)
        return None
    return name


_UNSHAPED: contextvars.ContextVar[bool] = contextvars.ContextVar("pdf_summary_unshaped", default=False)
_SPACES = re.compile(r"(\s+)")


def shaping() -> bool:
    if _UNSHAPED.get():
        return False
    from reportlab.pdfbase import ttfonts

    return getattr(ttfonts, "uharfbuzz", None) is not None


def _covers(script: str, token: str) -> bool:
    from reportlab.pdfbase import pdfmetrics

    face = fonts().scripts.get(script)
    if face is None:
        return False
    known = pdfmetrics.getFont(face[0]).face.charToGlyph
    return all(ord(ch) in known for ch in token)


def fonts() -> Fonts:
    global _FONTS
    if _FONTS is not None:
        return _FONTS
    import reportlab
    from reportlab.lib.fonts import addMapping

    regular = _register("PackSans", _font_file("NotoSans", "Regular"))
    bold = _register("PackSans-Bold", _font_file("NotoSans", "Bold"))
    if regular is None:
        vera = Path(reportlab.__file__).parent / "fonts"
        regular = _register("PackSans", vera / "Vera.ttf") or "Helvetica"
        bold = _register("PackSans-Bold", vera / "VeraBd.ttf") or "Helvetica-Bold"
    bold = bold or regular
    addMapping(regular, 0, 0, regular)
    addMapping(regular, 1, 0, bold)
    addMapping(regular, 0, 1, regular)
    addMapping(regular, 1, 1, bold)
    scripts: dict[str, tuple[str, str]] = {}
    for script, _low, _high in SCRIPTS:
        stem = f"NotoSans{script}"
        face = _register(f"Pack{script}", _font_file(stem, "Regular"))
        if face is None:
            continue
        heavy = _register(f"Pack{script}-Bold", _font_file(stem, "Bold")) or face
        scripts[script] = (face, heavy)
    _FONTS = Fonts(regular, bold, scripts)
    return _FONTS


def _script_of(ch: str) -> str | None:
    point = ord(ch)
    for script, low, high in SCRIPTS:
        if low <= point <= high:
            return script
    return None


def runs(text: Any) -> list[tuple[str | None, str]]:
    value = "" if text is None else str(text)
    out: list[tuple[str | None, str]] = []
    for token in _SPACES.split(value):
        if not token:
            continue
        if token.isspace():
            pieces: list[tuple[str | None, str]] = [(out[-1][0] if out else None, token)]
        else:
            script = next((found for found in map(_script_of, token) if found), None)
            whole = script is not None and _covers(script, token)
            pieces = [(script, token)] if whole or script is None else _char_runs(token)
        for script, piece in pieces:
            if out and out[-1][0] == script:
                out[-1] = (script, out[-1][1] + piece)
            else:
                out.append((script, piece))
    return out


def _char_runs(value: str) -> list[tuple[str | None, str]]:
    out: list[tuple[str | None, str]] = []
    run: list[str] = []
    current: str | None = None
    for ch in value:
        script = _script_of(ch)
        if script is None and ch in " ,.;:!?'\"()-–—" and current is not None:
            run.append(ch)
            continue
        if script != current:
            if run:
                out.append((current, "".join(run)))
                run = []
            current = script
        run.append(ch)
    if run:
        out.append((current, "".join(run)))
    return out


def rich(text: Any, *, bold: bool = False) -> str:
    faces = fonts()
    out: list[str] = []
    for script, chunk in runs(text):
        piece = escape(chunk).replace("\n", "<br/>")
        face = faces.scripts.get(script) if script else None
        if face is not None:
            out.append(f'<font name="{face[1] if bold else face[0]}">{piece}</font>')
        else:
            out.append(f"<b>{piece}</b>" if bold else piece)
    return "".join(out)


def _faced(text: str, base: str) -> list[tuple[str, str]]:
    faces = fonts()
    bold = base == faces.bold
    out = []
    for script, chunk in runs(text):
        face = faces.scripts.get(script) if script else None
        out.append(((face[1] if bold else face[0]) if face else base, chunk))
    return out


def text_width(text: str, font: str, size: float) -> float:
    from reportlab.pdfbase.pdfmetrics import stringWidth

    return sum(stringWidth(chunk, face, size) for face, chunk in _faced(text, font))


def draw(c: Any, x: float, y: float, text: str, font: str, size: float, *, right: bool = False) -> None:
    from reportlab.pdfbase.pdfmetrics import stringWidth

    if right:
        x -= text_width(text, font, size)
    shaped = shaping()
    for face, chunk in _faced(text, font):
        c.setFont(face, size)
        drawn = False
        if shaped and face != font:
            try:
                c.drawString(x, y, chunk, direction="LTR", shaping=True)
                drawn = True
            except Exception:
                logger.warning("pdf_summary: shaping failed for a %s run; drawing it unshaped", face, exc_info=True)
        if not drawn:
            c.drawString(x, y, chunk)
        x += stringWidth(chunk, face, size)


def human_size(value: int | None) -> str:
    size = float(max(0, int(value or 0)))
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{int(size)} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"


def _styles() -> dict[str, Any]:
    from reportlab.lib import colors
    from reportlab.lib.styles import ParagraphStyle

    faces = fonts()
    base = {"fontName": faces.regular, "textColor": colors.HexColor(INK)}
    return {
        "body": ParagraphStyle("body", fontSize=9.5, leading=13.5, **base),
        "small": ParagraphStyle("small", fontSize=8, leading=11, **{**base, "textColor": colors.HexColor(MUTED)}),
        "h1": ParagraphStyle(
            "h1", fontSize=17, leading=22, spaceBefore=4, spaceAfter=8,
            **{**base, "fontName": faces.bold, "textColor": colors.HexColor(BRAND_DEEP)},
        ),
        "h2": ParagraphStyle(
            "h2", fontSize=11.5, leading=15, spaceBefore=10, spaceAfter=5,
            **{**base, "fontName": faces.bold},
        ),
        "quote": ParagraphStyle("quote", fontSize=11, leading=16, **base),
        "english": ParagraphStyle(
            "english", fontSize=9.5, leading=13.5, spaceBefore=3, **{**base, "textColor": colors.HexColor(MUTED)}
        ),
        "cell": ParagraphStyle("cell", fontSize=8, leading=10.5, **base),
        "cellHead": ParagraphStyle(
            "cellHead", fontSize=7.5, leading=9.5,
            **{**base, "fontName": faces.bold, "textColor": colors.HexColor(PAPER)},
        ),
        "tiny": ParagraphStyle(
            "tiny", fontSize=6.3, leading=8, alignment=1, **{**base, "fontName": faces.bold}
        ),
    }


def _flowables() -> Any:
    from reportlab.platypus import Flowable

    faces = fonts()

    class Tiles(Flowable):
        def __init__(self, items: list[tuple[str, str, str]], width: float) -> None:
            super().__init__()
            self.items = items
            self.columns = 3
            self.gap = 8.0
            self.tile_h = 58.0
            self.box_w = width
            rows = (len(items) + self.columns - 1) // self.columns
            self.box_h = rows * self.tile_h + max(0, rows - 1) * self.gap

        def wrap(self, avail_w: float, avail_h: float) -> tuple[float, float]:
            return self.box_w, self.box_h

        def draw(self) -> None:
            from reportlab.lib import colors

            c = self.canv
            tile_w = (self.box_w - self.gap * (self.columns - 1)) / self.columns
            for index, (label, value, sub) in enumerate(self.items):
                row, col = divmod(index, self.columns)
                x = col * (tile_w + self.gap)
                y = self.box_h - (row + 1) * self.tile_h - row * self.gap
                c.setFillColor(colors.HexColor(BRAND_SOFT))
                c.setStrokeColor(colors.HexColor(BRAND_LINE))
                c.roundRect(x, y, tile_w, self.tile_h, 7, stroke=1, fill=1)
                c.setFillColor(colors.HexColor(BRAND))
                c.rect(x, y + 10, 3, self.tile_h - 20, stroke=0, fill=1)
                c.setFillColor(colors.HexColor(MUTED))
                draw(c, x + 12, y + self.tile_h - 16, _clip(c, label.upper(), faces.regular, 7.5, tile_w - 18), faces.regular, 7.5)
                c.setFillColor(colors.HexColor(BRAND_DEEP))
                draw(c, x + 12, y + 20, value[:16], faces.bold, 17)
                if sub:
                    c.setFillColor(colors.HexColor(MUTED))
                    draw(c, x + 12, y + 9, _clip(c, sub, faces.regular, 7, tile_w - 18), faces.regular, 7)

    class Bars(Flowable):
        def __init__(
            self,
            rows: list[tuple[str, list[tuple[float, str]], str]],
            width: float,
            legend: list[tuple[str, str]] | None = None,
        ) -> None:
            super().__init__()
            self.rows = rows
            self.box_w = width
            self.bar_h = 13.0
            self.row_gap = 7.0
            self.label_w = min(150.0, width * 0.34)
            self.value_w = 92.0
            self.legend = legend or []
            self.legend_h = 18.0 if self.legend else 0.0
            self.box_h = len(rows) * (self.bar_h + self.row_gap) + self.legend_h

        def wrap(self, avail_w: float, avail_h: float) -> tuple[float, float]:
            return self.box_w, self.box_h

        def draw(self) -> None:
            from reportlab.lib import colors

            c = self.canv
            track = self.box_w - self.label_w - self.value_w - 12
            peak = max((sum(v for v, _ in parts) for _, parts, _ in self.rows), default=0.0) or 1.0
            y = self.box_h - self.legend_h
            for label, parts, note in self.rows:
                y -= self.bar_h + self.row_gap
                c.setFillColor(colors.HexColor(INK))
                draw(c, 0, y + 3.5, _clip(c, label, faces.regular, 8.5, self.label_w - 6), faces.regular, 8.5)
                c.setFillColor(colors.HexColor("#F1EDF5"))
                c.roundRect(self.label_w, y, track, self.bar_h, 3, stroke=0, fill=1)
                x = self.label_w
                for value, colour in parts:
                    w = track * (value / peak)
                    if w <= 0:
                        continue
                    c.setFillColor(colors.HexColor(colour))
                    c.rect(x, y, w, self.bar_h, stroke=0, fill=1)
                    x += w
                c.setFillColor(colors.HexColor(MUTED))
                draw(c, self.box_w, y + 3.5, note, faces.regular, 8, right=True)
            if self.legend:
                x = self.label_w
                for name, colour in self.legend:
                    c.setFillColor(colors.HexColor(colour))
                    c.rect(x, self.box_h - 9, 8, 8, stroke=0, fill=1)
                    c.setFillColor(colors.HexColor(MUTED))
                    draw(c, x + 11, self.box_h - 8, name, faces.regular, 7.5)
                    x += 14 + text_width(name, faces.regular, 7.5) + 12

    return Tiles, Bars


def _clip(c: Any, text: str, font: str, size: float, width: float) -> str:
    if text_width(text, font, size) <= width:
        return text
    while text and text_width(text + "…", font, size) > width:
        text = text[:-1]
    return text + "…"


def _numbered_canvas(summary: Summary, t: Any) -> Any:
    from reportlab.lib import colors
    from reportlab.pdfgen import canvas

    faces = fonts()

    class Numbered(canvas.Canvas):
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            super().__init__(*args, **kwargs)
            self._pages: list[dict[str, Any]] = []

        def showPage(self) -> None:
            self._pages.append(dict(self.__dict__))
            self._startPage()

        def save(self) -> None:
            total = len(self._pages)
            for state in self._pages:
                self.__dict__.update(state)
                self._furniture(total)
                super().showPage()
            super().save()

        def _furniture(self, total: int) -> None:
            width, height = self._pagesize
            number = self._pageNumber
            if number > 1:
                self.setStrokeColor(colors.HexColor(BRAND))
                self.setLineWidth(1.2)
                self.line(42, height - 34, width - 42, height - 34)
                self.setFillColor(colors.HexColor(BRAND))
                draw(self, 42, height - 28, summary.portal_title, faces.bold, 8)
                self.setFillColor(colors.HexColor(MUTED))
                draw(self, width - 42, height - 28, _clip(self, summary.folder, faces.regular, 8, 300), faces.regular, 8, right=True)
            self.setFillColor(colors.HexColor(MUTED))
            draw(self, 42, 26, t("pdf.footer", "{portal} · Report summary", portal=summary.portal_title), faces.regular, 7.5)
            draw(self, width - 42, 26, t("pdf.page", "Page {n} of {total}", n=number, total=total), faces.regular, 7.5, right=True)

    return Numbered


def _cover(summary: Summary, t: Any) -> Any:
    from reportlab.lib import colors

    faces = fonts()

    def paint(c: Any, doc: Any) -> None:
        width, height = doc.pagesize
        band = 210.0
        c.saveState()
        c.setFillColor(colors.HexColor(BRAND))
        c.rect(0, height - band, width, band, stroke=0, fill=1)
        c.setFillColor(colors.HexColor(BRAND_DEEP))
        c.rect(0, height - band, width, 6, stroke=0, fill=1)
        c.setFillColor(colors.HexColor("#E9D8F7"))
        draw(c, 42, height - 58, summary.portal_title.upper(), faces.bold, 10)
        c.setFillColor(colors.HexColor(PAPER))
        draw(c, 42, height - 102, t("pdf.title", "Report summary"), faces.bold, 30)
        draw(c, 42, height - 130, _clip(c, summary.folder, faces.regular, 15, width - 84), faces.regular, 15)
        c.setFillColor(colors.HexColor("#E9D8F7"))
        when = stamp(summary.generated_at, summary.locale)
        line = t("pdf.generated", "Generated {date} by {name}", date=when, name=summary.generated_by)
        draw(c, 42, height - 158, line, faces.regular, 9)
        draw(c, 42, height - 172, t("pdf.dataVersion", "Data version {version}", version=summary.data_version), faces.regular, 9)
        c.restoreState()

    return paint


def build(summary: Summary) -> bytes:
    if shaping():
        try:
            return _build(summary)
        except Exception:
            logger.warning("pdf_summary: the shaped summary failed; building it unshaped", exc_info=True)
    token = _UNSHAPED.set(True)
    try:
        return _build(summary)
    finally:
        _UNSHAPED.reset(token)


def _build(summary: Summary) -> bytes:
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.platypus import (
        BaseDocTemplate,
        Frame,
        KeepTogether,
        NextPageTemplate,
        PageBreak,
        PageTemplate,
        Paragraph,
        Spacer,
        Table,
        TableStyle,
    )

    t = i18n.translator(summary.locale)
    styles = _styles()
    Tiles, Bars = _flowables()
    buffer = BytesIO()
    width, height = A4
    margin = 42.0
    text_w = width - 2 * margin
    doc = BaseDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=margin,
        rightMargin=margin,
        topMargin=52,
        bottomMargin=48,
        title=f"{summary.folder} · {t('pdf.title', 'Report summary')}",
        author=summary.generated_by,
        subject=f"{summary.portal_title} · {t('pdf.title', 'Report summary')}",
        creator=summary.portal_title,
    )
    cover_frame = Frame(margin, 48, text_w, height - 48 - 236, id="cover")
    body_frame = Frame(margin, 48, text_w, height - 48 - 52, id="body")
    doc.addPageTemplates(
        [
            PageTemplate(id="cover", frames=[cover_frame], onPage=_cover(summary, t)),
            PageTemplate(id="body", frames=[body_frame]),
        ]
    )

    shaped = shaping()
    shaped_styles: dict[str, Any] = {}

    def p(text: Any, style: str = "body", *, bold: bool = False) -> Any:
        chosen = styles[style]
        marked = rich(text, bold=bold)
        if shaped and any(script for script, _ in runs(text)):
            if style not in shaped_styles:
                shaped_styles[style] = ParagraphStyle(f"{style}-shaped", parent=chosen, shaping=1)
            trial = Paragraph(marked, shaped_styles[style])
            try:
                trial.wrap(text_w, height)
            except Exception:
                logger.warning("pdf_summary: shaping failed for a paragraph; setting it unshaped", exc_info=True)
            else:
                return Paragraph(marked, shaped_styles[style])
        return Paragraph(marked, chosen)

    def table(rows: list[list[Any]], widths: list[float], *, head: bool = True, extra: list[Any] | None = None) -> Any:
        grid = Table(rows, colWidths=widths, repeatRows=1 if head else 0, hAlign="LEFT")
        commands: list[Any] = [
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ("LEFTPADDING", (0, 0), (-1, -1), 5),
            ("RIGHTPADDING", (0, 0), (-1, -1), 5),
            ("LINEBELOW", (0, 0), (-1, -1), 0.4, colors.HexColor(BRAND_LINE)),
        ]
        if head:
            commands += [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor(BRAND))]
            commands += [
                ("BACKGROUND", (0, r), (-1, r), colors.HexColor("#FAF6FD"))
                for r in range(2, len(rows), 2)
            ]
        grid.setStyle(TableStyle(commands + (extra or [])))
        return grid

    states = {key: rich(t(STATE_KEYS[key], label), bold=True) for key, label in STATE_LABEL.items()}
    tiny = styles["tiny"]
    if shaped and any(script for value in states.values() for script, _ in runs(value)):
        tiny = ParagraphStyle("tiny-shaped", parent=tiny, shaping=1)
    story: list[Any] = [NextPageTemplate("body")]
    if summary.kpis:
        story.append(Tiles(summary.kpis, text_w))
        story.append(Spacer(1, 16))
    info = [
        (t("pdf.portal", "Portal"), summary.portal_title),
        (t("pdf.folder", "Folder"), summary.folder),
        (t("pdf.path", "Path"), summary.path or t("pdf.repository", "Repository")),
        *summary.options,
        (t("pdf.generatedBy", "Generated by"), summary.generated_by),
        (t("pdf.generatedAt", "Generated at"), stamp(summary.generated_at, summary.locale, zone=True)),
        (t("pdf.dataVersionLabel", "Data version"), summary.data_version),
    ]
    story.append(
        table(
            [[p(label, "small"), p(value)] for label, value in info],
            [text_w * 0.26, text_w * 0.74],
            head=False,
        )
    )
    contents = [t("pdf.charts", "Charts")]
    if summary.coverage:
        contents.append(t("pdf.coverage", "Coverage"))
    if summary.quotes:
        contents.append(t("pdf.quotes", "Quotes"))
    if summary.pack_files:
        contents.append(t("pdf.inPack", "In this pack"))
    story += [Spacer(1, 14), p(t("pdf.contents", "Contents"), "h2")]
    story += [p(f"{n}. {name}") for n, name in enumerate(contents, 1)]

    story += [PageBreak(), p(t("pdf.charts", "Charts"), "h1")]
    story.append(p(t("pdf.mediaByType", "Media by type"), "h2"))
    media_rows = [
        (label, [(float(files), BRAND)], f"{files} · {human_size(size)}")
        for label, files, size in summary.media_by_type
    ]
    story.append(Bars(media_rows, text_w) if media_rows else p(t("pdf.noFiles", "No files in this folder."), "small"))
    story.append(p(t("pdf.transcriptStatus", "Transcript status"), "h2"))
    status_rows = [(label, [(float(count), colour)], str(count)) for label, count, colour in summary.transcript_status]
    story.append(Bars(status_rows, text_w) if any(c for _, c, _ in summary.transcript_status) else p(t("pdf.noRecordings", "No recordings in this folder."), "small"))
    if summary.sections:
        story.append(p(t("pdf.completionBySection", "Completion by section"), "h2"))
        story.append(p(t("pdf.completionNote", "Artisans whose answers cover each questionnaire section fully, partly or not at all."), "small"))
        story.append(Spacer(1, 4))
        section_rows = [
            (
                bar.label,
                [(float(bar.complete), GREEN), (float(bar.partial), AMBER), (float(bar.missing), RED)],
                t("pdf.sectionCounts", "{complete} done · {partial} part · {missing} none", complete=bar.complete, partial=bar.partial, missing=bar.missing),
            )
            for bar in summary.sections
        ]
        story.append(Bars(section_rows, text_w, legend=[(t("pdf.legend.complete", "Complete"), GREEN), (t("pdf.legend.partial", "Partial"), AMBER), (t("pdf.legend.missing", "Missing"), RED)]))

    for block in summary.coverage:
        story += [PageBreak(), p(t("pdf.coverageTitle", "Coverage · {title}", title=block.title), "h1")]
        head = [
            p(h, "cellHead")
            for h in (
                t("pdf.head.artisan", "Artisan"),
                t("pdf.head.craft", "Craft"),
                t("pdf.head.fieldsFilled", "Fields filled"),
                t("pdf.head.files", "Files"),
                t("pdf.head.transcripts", "Transcripts"),
            )
        ]
        rows: list[list[Any]] = [head]
        for row in block.rows[:COVERAGE_ROWS_MAX]:
            pct = f"{round(100 * row.filled / row.total)}%" if row.total else "–"
            rows.append(
                [
                    p(row.name, "cell"),
                    p(row.craft or "–", "cell"),
                    p(f"{row.filled}/{row.total} · {pct}", "cell"),
                    p(str(row.files), "cell"),
                    p(row.transcripts, "cell"),
                ]
            )
        story.append(table(rows, [text_w * w for w in (0.3, 0.22, 0.2, 0.1, 0.18)]))
        if len(block.rows) > COVERAGE_ROWS_MAX:
            story.append(p(t("pdf.coverageCapped", "The workbook's coverage sheet lists all {n} artisans.", n=len(block.rows)), "small"))
        for start in range(0, len(block.sections), SECTIONS_PER_GRID):
            chunk = block.sections[start : start + SECTIONS_PER_GRID]
            story.append(p(t("pdf.sectionsAnswered", "Sections answered") if start == 0 else t("pdf.sectionsAnsweredMore", "Sections answered (continued)"), "h2"))
            legend = [
                [p(f"{start + n}", "small", bold=True), p(label, "small")]
                for n, (_key, label) in enumerate(chunk, 1)
            ]
            story.append(table(legend, [24, text_w - 24], head=False))
            story.append(Spacer(1, 6))
            name_w = min(text_w - 26.0 * len(chunk), 190.0)
            grid_rows: list[list[Any]] = [[p(t("pdf.head.artisan", "Artisan"), "cellHead"), *[p(str(start + n), "cellHead") for n in range(1, len(chunk) + 1)]]]
            fills: list[Any] = []
            for r, row in enumerate(block.rows[:COVERAGE_ROWS_MAX], 1):
                cells: list[Any] = [p(row.name, "cell")]
                for c_index in range(len(chunk)):
                    state = row.states[start + c_index] if start + c_index < len(row.states) else "missing"
                    cells.append(Paragraph(f'<font color="{STATE_INK.get(state, MUTED)}">{states.get(state, "")}</font>', tiny))
                    fills.append(("BACKGROUND", (c_index + 1, r), (c_index + 1, r), colors.HexColor(STATE_FILL.get(state, PAPER))))
                grid_rows.append(cells)
            fills += [
                ("GRID", (1, 1), (-1, -1), 0.6, colors.HexColor(PAPER)),
                ("LEFTPADDING", (1, 0), (-1, -1), 1),
                ("RIGHTPADDING", (1, 0), (-1, -1), 1),
                ("ALIGN", (1, 0), (-1, 0), "CENTER"),
            ]
            story.append(table(grid_rows, [name_w, *[26.0] * len(chunk)], extra=fills))

    if summary.quotes:
        story += [PageBreak(), p(t("pdf.quotes", "Quotes"), "h1")]
        story.append(p(t("pdf.quotesNote", "Key passages from the transcripts, in the speakers' own words with an English rendering."), "small"))
        story.append(Spacer(1, 8))
        for quote in summary.quotes:
            parts = [p(f"“{quote.original}”", "quote")]
            if quote.english and quote.english.strip() != quote.original.strip():
                parts.append(p(quote.english, "english"))
            who = " · ".join(x for x in (quote.speaker, quote.source) if x)
            if who:
                parts.append(p(who, "small"))
            card = Table([[parts]], colWidths=[text_w])
            card.setStyle(
                TableStyle(
                    [
                        ("LINEBEFORE", (0, 0), (0, 0), 2.5, colors.HexColor(BRAND)),
                        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#FBF8FD")),
                        ("LEFTPADDING", (0, 0), (-1, -1), 12),
                        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
                        ("TOPPADDING", (0, 0), (-1, -1), 8),
                        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
                    ]
                )
            )
            story += [KeepTogether([card]), Spacer(1, 8)]

    if summary.pack_files:
        story += [PageBreak(), p(t("pdf.inPack", "In this pack"), "h1")]
        rows = [[p(t("pdf.head.file", "File"), "cellHead"), p(t("pdf.head.holds", "What it holds"), "cellHead")]]
        rows += [[p(name, "cell"), p(what, "cell")] for name, what in summary.pack_files]
        story.append(table(rows, [text_w * 0.5, text_w * 0.5]))

    doc.build(story, canvasmaker=_numbered_canvas(summary, t))
    return buffer.getvalue()
