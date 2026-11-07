from __future__ import annotations

import asyncio
import threading
import tracemalloc
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services import s3
from app.services.data_explorer import downloads, report_cache, signing


class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


def _image() -> Row:
    return Row(id="img1", mediaType="IMAGE", mimeType="image/jpeg", objectKey="media/u/img1.jpg", originalFilename="a.jpg")


@pytest.fixture
def storage(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    seen: dict[str, Any] = {"renders": 0, "puts": 0, "signed": []}
    monkeypatch.setattr(downloads, "_UNSTORED", type(downloads._UNSTORED)())
    monkeypatch.setattr(downloads, "_THUMB_SLOT", asyncio.Semaphore(1))
    monkeypatch.setattr(s3, "object_exists", lambda _key: None)

    async def render(_key: str, _edge: int) -> bytes:
        seen["renders"] += 1
        return b"webp"

    def denied(_key: str, _data: bytes, *, mime_type: str) -> None:
        seen["puts"] += 1
        raise RuntimeError("AccessDenied")

    monkeypatch.setattr(downloads, "_render_from_storage", render)
    monkeypatch.setattr(s3, "put_object_bytes", denied)
    monkeypatch.setattr(
        signing,
        "signed_get",
        lambda key, **kw: (seen["signed"].append((key, kw.get("inline"))) or f"https://bucket.example/{key}", 60),
    )
    return seen


def test_a_thumbnail_that_cannot_be_stored_falls_back_to_the_original(storage):
    response = asyncio.run(downloads.thumbnail_redirect(_image(), 256))
    assert response.status_code == 307
    assert response.headers["X-Thumbnail"] == "original"
    assert response.headers["location"] == "https://bucket.example/media/u/img1.jpg"
    assert storage["signed"] == [("media/u/img1.jpg", True)]


def test_a_refused_thumbnail_is_not_rendered_again_while_storage_refuses(storage):
    for _ in range(5):
        assert asyncio.run(downloads.thumbnail_redirect(_image(), 256)).status_code == 307
    assert storage["renders"] == 1
    assert storage["puts"] == 1


def test_ensure_thumbnail_still_hands_back_the_rendered_bytes_when_storage_refuses(storage):
    assert asyncio.run(downloads.ensure_thumbnail(_image(), 512)) == b"webp"
    assert downloads.thumbnail_unstored(downloads.thumbnail_key("img1", 512))


def test_a_stored_thumbnail_redirects_to_itself(storage, monkeypatch):
    monkeypatch.setattr(s3, "put_object_bytes", lambda *_a, **_k: None)
    response = asyncio.run(downloads.thumbnail_redirect(_image(), 256))
    assert "X-Thumbnail" not in response.headers
    assert storage["signed"][-1][0] == downloads.thumbnail_key("img1", 256)


def test_the_refused_thumbnail_memory_is_capped_and_expires(monkeypatch):
    monkeypatch.setattr(downloads, "_UNSTORED", type(downloads._UNSTORED)())
    monkeypatch.setattr(downloads, "THUMBNAIL_UNSTORED_MAX", 8)
    for n in range(50):
        downloads._mark_unstored(f"thumbnails/256/m{n}.webp")
    assert len(downloads._UNSTORED) == 8
    assert downloads.thumbnail_unstored("thumbnails/256/m49.webp")
    assert not downloads.thumbnail_unstored("thumbnails/256/m0.webp")
    monkeypatch.setattr(downloads, "THUMBNAIL_UNSTORED_TTL_SECONDS", -1.0)
    downloads._mark_unstored("thumbnails/256/old.webp")
    assert not downloads.thumbnail_unstored("thumbnails/256/old.webp")


def test_busy_report_sheets_answer_503_with_retry_after(monkeypatch):
    from app.api.routes import data_report
    from app.services.datareport import build

    async def busy(*_a: Any, **_k: Any) -> Any:
        raise report_cache.ReportBusy(retry_after=9)

    monkeypatch.setattr(build, "catalog", busy)
    with pytest.raises(HTTPException) as refused:
        asyncio.run(data_report.report_sheets(path="", status_value=None, q=None, current_user=Row(id="u")))
    assert refused.value.status_code == 503
    assert refused.value.headers["Retry-After"] == "9"


def test_one_storage_client_serves_every_call_and_thread():
    s3._build_client.cache_clear()
    first = s3._client()
    seen: list[Any] = []
    threads = [threading.Thread(target=lambda: seen.append(s3._client())) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert s3._client() is first
    assert len(seen) == 8 and all(client is first for client in seen)
    assert s3._build_client.cache_info().currsize == 1


def test_storage_calls_do_not_grow_memory():
    s3._client()
    tracemalloc.start()
    try:
        before, _ = tracemalloc.get_traced_memory()
        for _ in range(300):
            s3._client()
        after, _ = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert after - before < 64 * 1024
