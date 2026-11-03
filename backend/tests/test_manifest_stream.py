import asyncio
import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.routes import data_browser, export


class _Rec:
    def __init__(self, **kw: Any) -> None:
        self.__dict__.update(kw)

    def __getattr__(self, name: str) -> Any:
        return None


class _Rows:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_many(self, where: Any = None, take: int | None = None, **_: Any) -> list[Any]:
        return self.rows[:take] if take else list(self.rows)


def _collect(app: FastAPI, path: str) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://manifest.test") as client:
            return await client.get(path)

    return asyncio.run(run())


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    admin = SimpleNamespace(id="admin", email="admin@example.test", name="Admin", role="ADMIN")

    application = FastAPI()
    application.include_router(export.router, prefix="/api")

    tables: dict[str, _Rows] = {}

    class _Tables:
        def __getattr__(self, name: str) -> Any:
            return tables.setdefault(name, _Rows([]))

    monkeypatch.setattr(export, "db", _Tables())
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)

    async def _no_filter(*_args: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(export, "owned_or_granted_where", _no_filter)
    application.dependency_overrides[export.get_current_user] = lambda: admin

    class _Stack:
        def table(self, delegate: str, rows: list[Any]) -> None:
            tables[delegate] = _Rows(rows)

        def get(self, path: str) -> httpx.Response:
            return _collect(application, path)

    return _Stack()


def _workshop(**kw: Any) -> _Rec:
    fields: dict[str, Any] = {
        "id": "w1",
        "title": "Bagru Block Printers",
        "crafts": [],
        "artisans": [],
    }
    fields.update(kw)
    return _Rec(**fields)



def _ndjson_app(files: list[dict[str, Any]], truncated: bool = False) -> FastAPI:
    application = FastAPI()

    @application.get("/manifest")
    async def manifest() -> Any:  # pragma: no cover
        return data_browser.manifest_ndjson_response(files, 0, truncated, "test.ndjson")

    return application


def test_an_entry_whose_text_contains_newlines_still_occupies_one_line() -> None:
    body = "Title: Bagru\nPlace: Rajasthan\n\tCraft: Block printing\nNotes: हिन्दी"
    files = [
        {"path": "Workshops/Bagru/details.txt", "content": body},
        {"path": "Workshops/Bagru/photo.jpg", "url": "https://bucket/photo.jpg"},
    ]

    response = _collect(_ndjson_app(files), "/manifest")

    assert response.status_code == 200
    lines = response.text.splitlines()
    assert len(lines) == 2, f"one entry per line, got {len(lines)} lines for 2 entries"
    assert json.loads(lines[0])["content"] == body
    assert json.loads(lines[1])["url"] == "https://bucket/photo.jpg"


def test_non_ascii_is_sent_as_itself_not_as_escapes() -> None:
    files = [{"path": "a.txt", "content": "हिन्दी"}]

    response = _collect(_ndjson_app(files), "/manifest")

    assert "\\u0939" not in response.text
    assert json.loads(response.text.strip())["content"] == "हिन्दी"


def test_the_counts_and_the_flag_arrive_before_the_body() -> None:
    files = [{"path": f"f{i}.jpg", "url": f"https://bucket/{i}"} for i in range(7)]

    response = _collect(_ndjson_app(files, truncated=True), "/manifest")

    assert response.headers[data_browser.MANIFEST_TOTAL_HEADER] == "7"
    assert response.headers[data_browser.MANIFEST_TRUNCATED_HEADER] == "true"
    assert response.headers["content-type"].startswith(data_browser.MANIFEST_NDJSON_MEDIA_TYPE)


def test_a_manifest_larger_than_one_chunk_is_whole() -> None:
    files = [{"path": f"f{i}.jpg", "url": f"https://bucket/{i}"} for i in range(503)]

    response = _collect(_ndjson_app(files), "/manifest")

    paths = [json.loads(line)["path"] for line in response.text.splitlines()]
    assert paths == [f"f{i}.jpg" for i in range(503)]


def test_the_entries_are_released_as_they_are_encoded() -> None:
    files: list[Any] = [{"path": "a.txt", "content": "x" * 64}, {"path": "b.txt", "content": "y" * 64}]

    response = _collect(_ndjson_app(files), "/manifest")

    assert len(response.text.splitlines()) == 2
    assert files == [None, None]



def test_the_dataset_manifest_streams_the_same_entries_the_json_shape_carries(api) -> None:
    api.table("workshop", [_workshop()])

    plain = api.get("/api/export/dataset")
    streamed = api.get("/api/export/dataset?stream=1")

    assert plain.status_code == 200 and streamed.status_code == 200
    assert plain.headers["content-type"].startswith("application/json")
    assert streamed.headers["content-type"].startswith(data_browser.MANIFEST_NDJSON_MEDIA_TYPE)

    expected = plain.json()["files"]
    got = [json.loads(line) for line in streamed.text.splitlines()]
    assert got == expected
    assert got, "the fixture must produce at least one entry or this asserts nothing"
    assert streamed.headers[data_browser.MANIFEST_TOTAL_HEADER] == str(plain.json()["totalFiles"])


def test_the_default_response_is_still_the_json_object(api) -> None:
    api.table("workshop", [_workshop()])

    payload = api.get("/api/export/dataset").json()

    assert set(payload) == {"files", "totalFiles", "totalMedia", "truncated"}
    assert payload["totalFiles"] == len(payload["files"])


def test_an_unauthorised_caller_is_refused_before_anything_is_streamed(api, monkeypatch) -> None:
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: False)

    response = api.get("/api/export/dataset?stream=1")

    assert response.status_code == 403


def test_the_streamed_manifest_reports_truncation_in_its_header(api) -> None:
    api.table("workshop", [_workshop(id=f"w{i}", title=f"Workshop {i}") for i in range(export.EXPORT_TAKE)])

    response = api.get("/api/export/dataset?stream=1")

    assert response.headers[data_browser.MANIFEST_TRUNCATED_HEADER] == "true"



@pytest.fixture
def media_api(monkeypatch: pytest.MonkeyPatch):
    admin = SimpleNamespace(id="admin", email="admin@example.test", name="Admin", role="ADMIN")
    application = FastAPI()
    application.include_router(data_browser.router, prefix="/api")
    application.dependency_overrides[data_browser.require_dataset_downloader] = lambda: admin

    seen = {"fetched": False, "converted": False, "stored": b""}

    async def _no_filter(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(data_browser, "owned_or_granted_where", _no_filter)

    def _get_object_bytes(_key: str) -> bytes:
        seen["fetched"] = True
        return seen["stored"]

    def _convert(_raw: bytes) -> Any:  # pragma: no cover
        seen["converted"] = True
        raise AssertionError("the transcode ran on an object over the ceiling")

    monkeypatch.setattr(data_browser, "get_object_bytes", _get_object_bytes)
    monkeypatch.setattr(data_browser, "_convert_audio_to_mp4", _convert)

    class _Media:
        rows: list[Any] = []

        async def find_unique(self, where: dict, **_: Any) -> Any:
            return next((r for r in self.rows if r.id == where["id"]), None)

        async def find_first(self, **_: Any) -> Any:
            return self.rows[0] if self.rows else None

    media_table = _Media()

    class _Tables:
        def __getattr__(self, name: str) -> Any:
            assert name == "mediafile", f"unexpected database read: db.{name}"
            return media_table

    monkeypatch.setattr(data_browser, "db", _Tables())

    class _Stack:
        def __init__(self) -> None:
            self.seen = seen

        def row(self, *, declared: int, stored: bytes) -> None:
            media_table.rows = [
                _Rec(
                    id="m1",
                    mediaType="AUDIO",
                    objectKey="uploads/m1.wav",
                    sizeBytes=declared,
                    originalFilename="interview.wav",
                    mimeType="audio/wav",
                )
            ]
            seen["stored"] = stored

        def get(self, path: str) -> httpx.Response:
            return _collect(application, path)

    return _Stack()


def test_the_declared_size_refuses_before_a_byte_moves(media_api) -> None:
    media_api.row(declared=data_browser.MAX_CONVERT_BYTES + 1, stored=b"")

    response = media_api.get("/api/data/media/m1/download?format=mp4")

    assert response.status_code == 413
    assert media_api.seen["fetched"] is False, "the object was fetched despite a refusable claim"


def test_the_real_length_refuses_what_the_column_lied_about(media_api) -> None:
    oversized = b"\0" * (data_browser.MAX_CONVERT_BYTES + 1)
    media_api.row(declared=1024, stored=oversized)

    response = media_api.get("/api/data/media/m1/download?format=mp4")

    assert response.status_code == 413
    assert media_api.seen["fetched"] is True, "the fixture did not exercise the post-fetch path"
    assert media_api.seen["converted"] is False, "ffmpeg decoded an object over the ceiling"
