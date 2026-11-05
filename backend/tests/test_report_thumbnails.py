from __future__ import annotations

import asyncio
import io
from types import SimpleNamespace
from typing import Any

import pytest
from PIL import Image

from app.services import s3
from app.services.data_explorer import downloads
from app.services.datareport import thumbnails


def _webp(size: tuple[int, int] = (256, 180)) -> bytes:
    out = io.BytesIO()
    Image.new("RGB", size, (120, 40, 200)).save(out, format="WEBP")
    return out.getvalue()


def _media(mid: str, mime: str = "image/jpeg") -> SimpleNamespace:
    return SimpleNamespace(id=mid, mediaType="IMAGE", mimeType=mime, objectKey=f"media/{mid}.jpg")


class FakeStore:
    def __init__(self, existing: dict[str, bytes]) -> None:
        self.objects = dict(existing)
        self.rendered: list[str] = []

    def install(self, monkeypatch: pytest.MonkeyPatch, sources: list[SimpleNamespace]) -> None:
        monkeypatch.setattr(s3, "object_exists", lambda key: key in self.objects)
        monkeypatch.setattr(s3, "get_object_bytes", lambda key, max_bytes=None: self.objects.get(key))

        def put(key: str, data: bytes, mime_type: str | None = None) -> None:
            self.objects[key] = data

        monkeypatch.setattr(s3, "put_object_bytes", put)

        async def render(object_key: str, edge: int) -> bytes:
            self.rendered.append(object_key)
            return _webp((edge, edge))

        monkeypatch.setattr(downloads, "_render_from_storage", render)
        monkeypatch.setattr(downloads, "_THUMB_SLOT", asyncio.Semaphore(1))
        by_id = {str(m.id): m for m in sources}

        async def lookup(ids: list[str]) -> dict[str, Any]:
            return {i: by_id[i] for i in ids if i in by_id}

        monkeypatch.setattr(thumbnails, "_sources", lookup)


def _edge(png: bytes) -> int:
    with Image.open(io.BytesIO(png)) as opened:
        return max(opened.size)


def test_a_portal_thumbnail_is_reused_without_rendering(monkeypatch: pytest.MonkeyPatch) -> None:
    store = FakeStore({downloads.thumbnail_key("a", 256): _webp()})
    store.install(monkeypatch, [_media("a")])
    got = asyncio.run(thumbnails.collect([{"id": "a", "mediaType": "IMAGE"}]))
    assert set(got) == {"a"} and _edge(got["a"]) <= thumbnails.EDGE
    assert store.rendered == []


def test_a_picture_without_a_thumbnail_gets_one_made_and_cached_for_the_app(monkeypatch: pytest.MonkeyPatch) -> None:
    store = FakeStore({})
    store.install(monkeypatch, [_media("b")])
    got = asyncio.run(thumbnails.collect([{"id": "b", "mediaType": "IMAGE"}]))
    assert set(got) == {"b"} and _edge(got["b"]) <= thumbnails.EDGE
    assert store.rendered == ["media/b.jpg"]
    assert downloads.thumbnail_key("b", 256) in store.objects
    again = asyncio.run(thumbnails.collect([{"id": "b", "mediaType": "IMAGE"}]))
    assert set(again) == {"b"} and store.rendered == ["media/b.jpg"]


def test_svg_and_non_pictures_are_never_rendered(monkeypatch: pytest.MonkeyPatch) -> None:
    store = FakeStore({})
    store.install(monkeypatch, [_media("s", "image/svg+xml"), _media("v")])
    got = asyncio.run(
        thumbnails.collect([{"id": "s", "mediaType": "IMAGE"}, {"id": "v", "mediaType": "VIDEO"}])
    )
    assert got == {} and store.rendered == []


def test_generation_keeps_to_the_count_and_byte_caps(monkeypatch: pytest.MonkeyPatch) -> None:
    sources = [_media(f"m{n}") for n in range(10)]
    store = FakeStore({})
    store.install(monkeypatch, sources)
    monkeypatch.setattr(thumbnails, "MAX_RENDERS", 3)
    got = asyncio.run(thumbnails.collect([{"id": m.id, "mediaType": "IMAGE"} for m in sources]))
    assert len(got) == 3 and len(store.rendered) == 3

    store = FakeStore({})
    store.install(monkeypatch, sources)
    monkeypatch.setattr(thumbnails, "MAX_RENDERS", 60)
    one = len(thumbnails.to_png(_webp((256, 256))) or b"")
    monkeypatch.setattr(thumbnails, "MAX_BYTES", one * 2 + 1)
    got = asyncio.run(thumbnails.collect([{"id": m.id, "mediaType": "IMAGE"} for m in sources]))
    assert len(got) == 2 and sum(len(v) for v in got.values()) <= thumbnails.MAX_BYTES

    store = FakeStore({})
    store.install(monkeypatch, sources)
    monkeypatch.setattr(thumbnails, "MAX_BYTES", 6 * 1024 * 1024)
    monkeypatch.setattr(thumbnails, "MAX_IMAGES", 4)
    got = asyncio.run(thumbnails.collect([{"id": m.id, "mediaType": "IMAGE"} for m in sources]))
    assert len(got) == 4


def test_a_busy_render_slot_skips_the_picture_instead_of_failing_the_report(monkeypatch: pytest.MonkeyPatch) -> None:
    store = FakeStore({})
    store.install(monkeypatch, [_media("c")])
    monkeypatch.setattr(thumbnails, "RENDER_WAIT", 0.02)

    async def run() -> dict[str, bytes]:
        await downloads._THUMB_SLOT.acquire()
        try:
            return await thumbnails.collect([{"id": "c", "mediaType": "IMAGE"}])
        finally:
            downloads._THUMB_SLOT.release()

    assert asyncio.run(run()) == {}
    assert not downloads._THUMB_SLOT.locked()
