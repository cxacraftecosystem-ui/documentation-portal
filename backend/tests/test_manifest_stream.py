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
