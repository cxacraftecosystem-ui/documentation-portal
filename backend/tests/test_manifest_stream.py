"""The streamed download manifest: `GET /export/dataset?stream=1` and `GET /data/manifest?stream=1`.

WHAT THIS IS FOR. Both manifest endpoints answered with ONE JSON object holding every entry of a
download, and the entries carry inline text — a details.txt body per record and, unfiltered, the
full transcript of every audio row in the subtree. The caps are on the entry COUNT
(``EXPORT_TAKE``/``MEDIA_TAKE``/``MAX_MANIFEST_FILES``) and nothing caps the byte size, so
``docs/SCALABILITY.md:373-377`` measures 476 kB today and models ~48 MB at 100x the media.

On the handset that single body is the whole failure. Retrofit's kotlinx-serialization converter is
``Serializer.FromString`` — ``decodeFromString(body.string())`` — and ``ResponseBody.string()``
allocates one contiguous ``ByteArray`` the size of the entire body and copies it into one contiguous
``String``, so the app dies with ``OutOfMemoryError: Failed to allocate a N byte allocation``.
NDJSON lets the client decode one entry at a time and never hold more than the longest line.

THREE PROPERTIES ARE WORTH MORE THAN THE REST AND MOST OF THIS FILE IS ABOUT THEM.

**One entry per line, whatever the entry contains.** Every details.txt body is multi-line and every
transcript is many lines, so if an entry's text ever reached the wire with a real newline in it the
line protocol would break silently: the client would read one entry as several, most of them
unparseable, and the archive would come out short with no error anywhere. This is asserted with
content that contains newlines, tabs and non-ASCII.

**The default shape is unchanged.** ``?stream=1`` is opt-in because the two browser clients and
every installed Android build read the JSON object. A change that makes NDJSON the default is a
fleet-wide download outage, so the JSON response is asserted here beside the streamed one.

**The entries are released as they are encoded.** That is the server half of the same memory
argument — without it the ~48 MB of encoded bytes sit beside the list that produced them on a
single-worker t3.micro.

NOTHING HERE TOUCHES A DATABASE. ``db`` is replaced with stubs and the routes are driven over HTTP,
the same way ``test_dataset_api.py`` does it.
"""

import asyncio
import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.routes import data_browser, export

# =================================================================================================
# Harness
# =================================================================================================


class _Rec:
    """A record row that answers None for anything it was not given.

    The field registry reads far more attributes off a row than any one test cares to name, and a
    ``SimpleNamespace`` raises for the rest. Answering None is what a nullable column does anyway.
    """

    def __init__(self, **kw: Any) -> None:
        self.__dict__.update(kw)

    def __getattr__(self, name: str) -> Any:  # only reached when the attribute was not set
        return None


class _Rows:
    """Stands in for a Prisma delegate over a fixed list of rows."""

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
    """The real /export/dataset route over stubbed tables and a stubbed admin."""
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


# =================================================================================================
# The line protocol — the property that breaks silently
# =================================================================================================


def _ndjson_app(files: list[dict[str, Any]], truncated: bool = False) -> FastAPI:
    application = FastAPI()

    @application.get("/manifest")
    async def manifest() -> Any:  # pragma: no cover - exercised over HTTP
        return data_browser.manifest_ndjson_response(files, 0, truncated, "test.ndjson")

    return application


def test_an_entry_whose_text_contains_newlines_still_occupies_one_line() -> None:
    """The whole line protocol rests on this, and it fails silently if it is ever broken.

    Every ``details.txt`` body is multi-line. If one reached the wire with a real newline in it the
    client would read one entry as several — most of them unparseable — and hand back a short
    archive with no error raised anywhere in the system.
    """
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
    """``ensure_ascii=False`` — a Devanagari transcript must not triple in size on the wire."""
    files = [{"path": "a.txt", "content": "हिन्दी"}]

    response = _collect(_ndjson_app(files), "/manifest")

    assert "\\u0939" not in response.text
    assert json.loads(response.text.strip())["content"] == "हिन्दी"


def test_the_counts_and_the_flag_arrive_before_the_body() -> None:
    """Headers, because NDJSON has no wrapper object and progress must start at the first entry."""
    files = [{"path": f"f{i}.jpg", "url": f"https://bucket/{i}"} for i in range(7)]

    response = _collect(_ndjson_app(files, truncated=True), "/manifest")

    assert response.headers[data_browser.MANIFEST_TOTAL_HEADER] == "7"
    assert response.headers[data_browser.MANIFEST_TRUNCATED_HEADER] == "true"
    assert response.headers["content-type"].startswith(data_browser.MANIFEST_NDJSON_MEDIA_TYPE)


def test_a_manifest_larger_than_one_chunk_is_whole() -> None:
    """The chunked writer yields 200 lines at a time; the seam must not eat or duplicate an entry."""
    files = [{"path": f"f{i}.jpg", "url": f"https://bucket/{i}"} for i in range(503)]

    response = _collect(_ndjson_app(files), "/manifest")

    paths = [json.loads(line)["path"] for line in response.text.splitlines()]
    assert paths == [f"f{i}.jpg" for i in range(503)]


def test_the_entries_are_released_as_they_are_encoded() -> None:
    """The server half of the memory argument.

    Without the ``files[index] = None``, the encoded ~48 MB sits beside the list that produced it on
    a box with 1 GiB. This asserts the release actually happens rather than trusting the comment;
    if someone removes it as a tidy-up, this test says so.
    """
    files: list[Any] = [{"path": "a.txt", "content": "x" * 64}, {"path": "b.txt", "content": "y" * 64}]

    response = _collect(_ndjson_app(files), "/manifest")

    assert len(response.text.splitlines()) == 2
    assert files == [None, None]


# =================================================================================================
# The route: same entries either way, and the default is untouched
# =================================================================================================


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
    """Every deployed client reads this shape. Making NDJSON the default is a fleet-wide outage."""
    api.table("workshop", [_workshop()])

    payload = api.get("/api/export/dataset").json()

    assert set(payload) == {"files", "totalFiles", "totalMedia", "truncated"}
    assert payload["totalFiles"] == len(payload["files"])


def test_an_unauthorised_caller_is_refused_before_anything_is_streamed(api, monkeypatch) -> None:
    """``?stream=1`` must not be a way around the dataset-download permission."""
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: False)

    response = api.get("/api/export/dataset?stream=1")

    assert response.status_code == 403


def test_the_streamed_manifest_reports_truncation_in_its_header(api) -> None:
    """A capped export must say so on the streamed path too, or the client cannot warn."""
    api.table("workshop", [_workshop(id=f"w{i}", title=f"Workshop {i}") for i in range(export.EXPORT_TAKE)])

    response = api.get("/api/export/dataset?stream=1")

    assert response.headers[data_browser.MANIFEST_TRUNCATED_HEADER] == "true"


# =================================================================================================
# The audio-conversion ceiling: the column is a claim, the length is a fact
# =================================================================================================


@pytest.fixture
def media_api(monkeypatch: pytest.MonkeyPatch):
    """``GET /data/media/{id}/download`` over a stubbed media table and a stubbed object store.

    Records what the route asked the object store for and whether the transcode ran, because those
    two facts — not the status code alone — are what the ceiling is actually about.
    """
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

    def _convert(_raw: bytes) -> Any:  # pragma: no cover - reached only when the ceiling lets it
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
    """The cheap first check: an honest large recording costs nothing to reject."""
    media_api.row(declared=data_browser.MAX_CONVERT_BYTES + 1, stored=b"")

    response = media_api.get("/api/data/media/m1/download?format=mp4")

    assert response.status_code == 413
    assert media_api.seen["fetched"] is False, "the object was fetched despite a refusable claim"


def test_the_real_length_refuses_what_the_column_lied_about(media_api) -> None:
    """THE COLUMN IS A CLAIM AND THE LENGTH IS A FACT.

    ``MediaFile.sizeBytes`` is whatever the client declared at ``POST /media/complete``: bounded
    only from below, stored verbatim, never reconciled against the stored object — there is no
    ``head_object`` anywhere in ``backend/app``. Nor does the signature bound the body:
    ``presign_put_url`` signs Bucket/Key/ContentType and no ``content-length-range``, and its
    docstring records why that must stay so (a signed condition breaks every installed Android
    build). So a caller can declare 1 kB, PUT 1.5 GB, and click download on their own row.

    Without the post-fetch check the transcode then ran, and ffmpeg decoding compressed audio is
    where one copy becomes several — the box OOMs, taking every in-flight request with it. The
    fetch itself has already happened by then and cannot be undone from here; what this closes is
    the multiplier. Hence the assertion on ``converted``, not merely on the status code.
    """
    oversized = b"\0" * (data_browser.MAX_CONVERT_BYTES + 1)
    media_api.row(declared=1024, stored=oversized)

    response = media_api.get("/api/data/media/m1/download?format=mp4")

    assert response.status_code == 413
    assert media_api.seen["fetched"] is True, "the fixture did not exercise the post-fetch path"
    assert media_api.seen["converted"] is False, "ffmpeg decoded an object over the ceiling"
