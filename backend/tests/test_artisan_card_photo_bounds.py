from __future__ import annotations

import io
import struct
import threading
import zlib
from types import SimpleNamespace

import pytest
from test_artisan_card_routes import USER, anyio_backend, world  # noqa: F401

from app.api.routes import artisan_cards as routes
from app.services import artisan_card_pdf, s3

pytestmark = pytest.mark.anyio

MIB = 1024 * 1024


class _Body:
    def __init__(self, total: int) -> None:
        self.total = total
        self.served = 0

    def read(self, size: int | None = None) -> bytes:
        left = self.total - self.served
        if left <= 0:
            return b""
        step = left if size is None or size < 0 else min(size, left)
        self.served += step
        return b"\0" * step

    def close(self) -> None:
        return None


class _Client:
    def __init__(self, declared: int, actual: int) -> None:
        self.declared = declared
        self.body = _Body(actual)

    def head_object(self, **_kw):
        return {"ContentLength": self.declared, "ContentType": "image/jpeg"}

    def get_object(self, **_kw):
        return {"Body": self.body}


def _png_header(width: int, height: int) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(b"\0"))
        + chunk(b"IEND", b"")
    )


@pytest.mark.parametrize("declared", [40 * MIB, 1024])
async def test_card_photo_read_is_bounded_by_the_stored_object_not_the_declared_size(
    monkeypatch, declared
):
    client = _Client(declared=declared, actual=40 * MIB)
    monkeypatch.setattr(s3, "_client", lambda: client)
    row = SimpleNamespace(id="m1", sizeBytes=1, objectKey="media/u1/a.jpg")

    photos = await routes._photo_bytes({"a1": row})

    assert "a1" not in photos
    assert client.body.served <= 16 * MIB


def test_an_oversized_pixel_count_is_refused_before_it_is_decoded(monkeypatch):
    from PIL import Image

    decoded: list[str] = []
    original = Image.Image.convert

    def spy(self, *args, **kwargs):
        decoded.append(self.mode)
        return original(self, *args, **kwargs)

    monkeypatch.setattr(Image.Image, "convert", spy)
    bomb = _png_header(9_000, 9_000)

    assert artisan_card_pdf.scale_photo(bomb) is None
    assert decoded == []


def test_a_normal_photo_is_still_scaled_for_the_card():
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (1200, 1600), (180, 120, 90)).save(buffer, format="JPEG")
    scaled = artisan_card_pdf.scale_photo(buffer.getvalue())
    assert scaled is not None
    with Image.open(io.BytesIO(scaled)) as image:
        assert max(image.size) <= artisan_card_pdf.PHOTO_MAX_PX


async def test_card_pdf_renders_off_the_event_loop(world, monkeypatch):  # noqa: F811
    seen: list[bool] = []
    original = artisan_card_pdf.render

    def render(*args, **kwargs):
        seen.append(threading.current_thread() is threading.main_thread())
        return original(*args, **kwargs)

    monkeypatch.setattr(artisan_card_pdf, "render", render)
    await routes.card_pdf("a1", size="id1", photo=0, current_user=USER)
    await routes.sheet_pdf(routes.SheetIn(artisanIds=["a1"], photo=False), current_user=USER)

    assert seen == [False, False]
