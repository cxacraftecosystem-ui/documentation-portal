from __future__ import annotations

import re
import xml.etree.ElementTree as ET

import segno

from app.services import artisan_card_store

SVG_NS = "http://www.w3.org/2000/svg"
TOKEN = "DPWC1-7K3QF9XM2T-ABCDEFGH234567"


def _parse(svg: str) -> ET.Element:
    return ET.fromstring(svg)


def test_card_qr_is_a_standalone_svg_document():
    svg = artisan_card_store.qr_svg(TOKEN)
    assert svg.startswith("<svg ")
    root = _parse(svg)
    assert root.tag == f"{{{SVG_NS}}}svg"
    assert root.get("viewBox")
    assert root.find(f"{{{SVG_NS}}}path") is not None


def test_card_qr_draws_the_segno_symbol_with_its_quiet_zone():
    svg = artisan_card_store.qr_svg(TOKEN)
    root = _parse(svg)
    _x, _y, width, height = (int(v) for v in root.get("viewBox").split())
    border = artisan_card_store.QR_BORDER_MODULES
    symbol = segno.make(TOKEN, error="q", mode="alphanumeric", micro=False, boost_error=False)
    expected = [[bool(cell) for cell in row] for row in symbol.matrix]
    assert width == height == len(expected) + 2 * border
    drawn = [[False] * width for _ in range(height)]
    path = root.find(f"{{{SVG_NS}}}path").get("d")
    for x, y, run in re.findall(r"M(\d+) (\d+)h(\d+)v1h-\d+z", path):
        for dx in range(int(run)):
            drawn[int(y)][int(x) + dx] = True
    inner = [row[border:-border] for row in drawn[border:-border]]
    assert inner == expected
    assert not any(drawn[0]) and not any(drawn[-1])

