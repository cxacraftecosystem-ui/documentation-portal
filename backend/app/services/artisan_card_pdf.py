"""The artisan card as a PDF: one ID-1 card, one A6 card, or a sheet of them for a workshop.

Pages are drawn at physical size, so a print at 100% gives a card that is 85.6 x 54 mm (ID-1, the
bank-card size) or an A6 page. A sheet of ID-1 cards is A4 portrait, two columns of five, with crop
marks; a sheet of A6 cards is one card per page.

What a card face carries, and nothing more: the photo (optional, per print), the name and local
name, the craft and cluster, ``Aadhaar XXXX XXXX 9012`` (left out when the last four are not
known), the portal's name, the QR and the short code under it. Never the fingerprint, phone,
address, date of birth or Pehchan number; the QR carries only the opaque token.

The QR is segno's matrix drawn as vector squares at 24 mm or more (29 modules, so each module is
at least 0.8 mm), with a two-module quiet zone kept white.
"""

from __future__ import annotations

import io
from dataclasses import dataclass
from typing import Any

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas as pdf_canvas

ID1 = (85.6 * mm, 54 * mm)
A6 = (105 * mm, 148 * mm)
SIZES = ("id1", "a6")

#: Cards per A4 sheet of ID-1 cards: two columns of five.
SHEET_COLUMNS = 2
SHEET_ROWS = 5
PER_SHEET = SHEET_COLUMNS * SHEET_ROWS

#: The largest side a photo is placed at, in pixels. Bigger originals are scaled down first.
PHOTO_MAX_PX = 600

INK = (0.11, 0.11, 0.12)
MUTED = (0.38, 0.38, 0.42)
RULE = (0.78, 0.78, 0.8)


@dataclass
class CardFace:
    name: str
    local_name: str | None
    craft: str | None
    cluster: str | None
    aadhaar_last4: str | None
    token: str | None
    short_code: str | None
    photo: bytes | None = None


def _printable(text: str | None) -> str | None:
    """Helvetica draws Latin-1 only; a line in another script is left out rather than drawn as boxes."""
    if not text:
        return None
    try:
        text.encode("latin-1")
    except UnicodeEncodeError:
        return None
    return text


def _fit(c: Any, text: str, font: str, size: float, width: float) -> str:
    if c.stringWidth(text, font, size) <= width:
        return text
    while text and c.stringWidth(text + "...", font, size) > width:
        text = text[:-1]
    return text.rstrip() + "..."


def _qr_matrix(token: str) -> list[list[bool]]:
    import segno

    symbol = segno.make(token, error="q", mode="alphanumeric", micro=False, boost_error=False)
    return [[bool(cell) for cell in row] for row in symbol.matrix]


def _draw_qr(c: Any, token: str, x: float, y: float, side: float) -> None:
    """The QR with its bottom-left corner at (x, y), ``side`` wide, quiet zone outside it."""
    matrix = _qr_matrix(token)
    n = len(matrix)
    module = side / n
    c.setFillColorRGB(1, 1, 1)
    c.rect(x - 2 * module, y - 2 * module, side + 4 * module, side + 4 * module, stroke=0, fill=1)
    c.setFillColorRGB(0, 0, 0)
    for r, row in enumerate(matrix):
        top = y + side - (r + 1) * module
        start = None
        for col, dark in enumerate(row + [False]):
            if dark and start is None:
                start = col
            elif not dark and start is not None:
                # One rectangle per dark run, slightly overdrawn so no hairline shows between runs.
                c.rect(
                    x + start * module, top, (col - start) * module, module + 0.01, stroke=0, fill=1
                )
                start = None


def _photo_reader(photo: bytes | None) -> Any | None:
    if not photo:
        return None
    try:
        from PIL import Image
        from reportlab.lib.utils import ImageReader

        image = Image.open(io.BytesIO(photo))
        image.load()
        image = image.convert("RGB")
        image.thumbnail((PHOTO_MAX_PX, PHOTO_MAX_PX))
        return ImageReader(image)
    except Exception:
        return None


def _draw_photo(c: Any, reader: Any, x: float, y: float, w: float, h: float) -> None:
    """Centre-crop the photo into the w x h box."""
    iw, ih = reader.getSize()
    scale = max(w / iw, h / ih)
    dw, dh = iw * scale, ih * scale
    c.saveState()
    path = c.beginPath()
    path.rect(x, y, w, h)
    c.clipPath(path, stroke=0, fill=0)
    c.drawImage(reader, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh)
    c.restoreState()
    c.setStrokeColorRGB(*RULE)
    c.setLineWidth(0.3)
    c.rect(x, y, w, h, stroke=1, fill=0)


def _text_lines(face: CardFace) -> list[tuple[str, str, float, tuple[float, float, float]]]:
    lines: list[tuple[str, str, float, tuple[float, float, float]]] = []
    lines.append((_printable(face.name) or "Artisan", "Helvetica-Bold", 9.5, INK))
    local = _printable(face.local_name)
    if local and local != face.name:
        lines.append((local, "Helvetica", 8, MUTED))
    for value in (face.craft, face.cluster):
        printable = _printable(value)
        if printable:
            lines.append((printable, "Helvetica", 7.5, INK))
    return lines


def _aadhaar_line(face: CardFace) -> str | None:
    digits = face.aadhaar_last4
    if not digits or len(digits) != 4 or not digits.isdigit():
        return None
    return f"Aadhaar XXXX XXXX {digits}"


def _draw_refusal(c: Any, ox: float, oy: float, w: float, h: float) -> None:
    c.setFillColorRGB(*MUTED)
    c.setFont("Helvetica", 8)
    c.drawCentredString(ox + w / 2, oy + h / 2, "Sync this artisan first.")


def draw_id1(c: Any, face: CardFace, ox: float, oy: float, portal: str) -> None:
    """One ID-1 face with its bottom-left corner at (ox, oy)."""
    w, h = ID1
    pad = 4 * mm
    c.setFillColorRGB(*MUTED)
    c.setFont("Helvetica-Bold", 6)
    c.drawString(ox + pad, oy + h - pad - 2.2 * mm, portal.upper())
    if not face.token:
        _draw_refusal(c, ox, oy, w, h)
        return
    qr_side = 24 * mm
    qr_x = ox + w - pad - qr_side
    qr_y = oy + 13 * mm
    _draw_qr(c, face.token, qr_x, qr_y, qr_side)
    c.setFillColorRGB(*INK)
    c.setFont("Courier-Bold", 6.6)
    c.drawCentredString(qr_x + qr_side / 2, oy + 8.5 * mm, face.short_code or "")
    top = oy + h - pad - 5 * mm
    text_x = ox + pad
    reader = _photo_reader(face.photo)
    if reader is not None:
        pw, ph = 22 * mm, 28 * mm
        _draw_photo(c, reader, text_x, top - ph, pw, ph)
        text_x += pw + 2.5 * mm
    width = qr_x - 2.5 * mm - text_x
    y = top - 3.2 * mm
    for text, font, size, colour in _text_lines(face):
        c.setFillColorRGB(*colour)
        c.setFont(font, size)
        c.drawString(text_x, y, _fit(c, text, font, size, width))
        y -= size * 0.42 * mm + 1.6 * mm
    aadhaar = _aadhaar_line(face)
    if aadhaar:
        c.setFillColorRGB(*INK)
        c.setFont("Helvetica", 7)
        c.drawString(ox + pad, oy + pad, aadhaar)


def draw_a6(c: Any, face: CardFace, portal: str) -> None:
    w, h = A6
    pad = 8 * mm
    c.setFillColorRGB(*MUTED)
    c.setFont("Helvetica-Bold", 8)
    c.drawString(pad, h - pad - 3 * mm, portal.upper())
    if not face.token:
        _draw_refusal(c, 0, 0, w, h)
        return
    top = h - pad - 8 * mm
    text_x = pad
    reader = _photo_reader(face.photo)
    if reader is not None:
        pw, ph = 22 * mm, 28 * mm
        _draw_photo(c, reader, pad, top - ph, pw, ph)
        text_x += pw + 4 * mm
    width = w - pad - text_x
    y = top - 4 * mm
    for text, font, size, colour in _text_lines(face):
        size = size * 1.35
        c.setFillColorRGB(*colour)
        c.setFont(font, size)
        c.drawString(text_x, y, _fit(c, text, font, size, width))
        y -= size * 0.42 * mm + 2.4 * mm
    qr_side = 52 * mm
    qr_x = (w - qr_side) / 2
    qr_y = 30 * mm
    _draw_qr(c, face.token, qr_x, qr_y, qr_side)
    c.setFillColorRGB(*INK)
    c.setFont("Courier-Bold", 11)
    c.drawCentredString(w / 2, qr_y - 8 * mm, face.short_code or "")
    aadhaar = _aadhaar_line(face)
    if aadhaar:
        c.setFont("Helvetica", 9)
        c.drawCentredString(w / 2, pad, aadhaar)


def _crop_marks(c: Any, x: float, y: float, w: float, h: float) -> None:
    c.setStrokeColorRGB(*RULE)
    c.setLineWidth(0.25)
    gap, length = 1 * mm, 3 * mm
    for cx, cy, dx, dy in (
        (x, y, -1, -1),
        (x + w, y, 1, -1),
        (x, y + h, -1, 1),
        (x + w, y + h, 1, 1),
    ):
        c.line(cx + dx * gap, cy, cx + dx * (gap + length), cy)
        c.line(cx, cy + dy * gap, cx, cy + dy * (gap + length))


def render(
    faces: list[CardFace],
    size: str,
    *,
    portal: str,
    sheet: bool = False,
    title: str = "Artisan card",
) -> bytes:
    """The PDF for ``faces``. A6: one card per page. ID-1: one card-sized page, or with ``sheet``
    A4 pages of ten with crop marks."""
    buffer = io.BytesIO()
    if size == "a6":
        c = pdf_canvas.Canvas(buffer, pagesize=A6)
        c.setTitle(title)
        for face in faces:
            draw_a6(c, face, portal)
            c.showPage()
    elif not sheet:
        c = pdf_canvas.Canvas(buffer, pagesize=ID1)
        c.setTitle(title)
        draw_id1(c, faces[0], 0, 0, portal)
        c.showPage()
    else:
        c = pdf_canvas.Canvas(buffer, pagesize=A4)
        c.setTitle(title)
        pw, ph = A4
        cw, ch = ID1
        gutter = 6 * mm
        left = (pw - SHEET_COLUMNS * cw - (SHEET_COLUMNS - 1) * gutter) / 2
        bottom = (ph - SHEET_ROWS * ch - (SHEET_ROWS - 1) * gutter) / 2
        for start in range(0, len(faces), PER_SHEET):
            for i, face in enumerate(faces[start : start + PER_SHEET]):
                col, row = i % SHEET_COLUMNS, i // SHEET_COLUMNS
                x = left + col * (cw + gutter)
                y = bottom + (SHEET_ROWS - 1 - row) * (ch + gutter)
                _crop_marks(c, x, y, cw, ch)
                draw_id1(c, face, x, y, portal)
            c.showPage()
    c.save()
    return buffer.getvalue()
