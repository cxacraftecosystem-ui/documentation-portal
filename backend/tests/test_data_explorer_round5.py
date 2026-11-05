from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.services.data_explorer import counts, tree, walk

RECORDED = datetime(2026, 7, 1, 10, 30, tzinfo=UTC)
CREATED = datetime(2026, 7, 3, 9, 0, tzinfo=UTC)


class _Media:
    def __init__(self, rows: list[dict[str, Any]]) -> None:
        self.rows = rows
        self.calls: list[dict[str, Any]] = []

    async def group_by(self, **kwargs: Any) -> list[dict[str, Any]]:
        self.calls.append(kwargs)
        return self.rows


def _fake(monkeypatch: pytest.MonkeyPatch, rows: list[dict[str, Any]]) -> _Media:
    media = _Media(rows)
    monkeypatch.setattr(counts, "db", SimpleNamespace(mediafile=media))
    return media


def _folders(*leaves: str, parent: str) -> list[dict[str, Any]]:
    return [{"kind": "folder", "name": leaf, "path": f"{parent}/{leaf}"} for leaf in leaves]


def _row(column: str, key: str, n: int, size: int | None) -> dict[str, Any]:
    return {column: key, "_count": {"_all": n}, "_sum": {"sizeBytes": size}}


def test_type_folders_count_with_one_grouped_query(monkeypatch):
    media = _fake(
        monkeypatch,
        [_row("mediaType", "IMAGE", 3, 300), _row("mediaType", "PDF", 1, 10), _row("mediaType", "DOCUMENT", 2, None)],
    )
    entries = _folders("images", "videos", "audios", "transcripts", "documents", "other", parent="by-type")
    entries.append({"kind": "file", "name": "x"})
    asyncio.run(counts.fill("by-type", entries, SimpleNamespace(media={"uploadedById": "u1"})))
    got = {e["name"]: (e["count"], e["bytes"]) for e in entries if e["kind"] == "folder"}
    assert got == {
        "images": (3, 300),
        "videos": (0, 0),
        "audios": (0, 0),
        "transcripts": (None, None),
        "documents": (3, 10),
        "other": (0, 0),
    }
    assert "count" not in entries[-1]
    (call,) = media.calls
    assert call["by"] == ["mediaType"] and call["count"] is True and call["sum"] == {"sizeBytes": True}
    assert {"removedAt": None} in call["where"]["AND"] and {"uploadedById": "u1"} in call["where"]["AND"]


def test_uploader_levels_group_by_their_child_key(monkeypatch):
    media = _fake(monkeypatch, [_row("workshopId", "w1", 4, 400)])
    workshops = _folders("w1", "w2", parent="by-uploader")
    asyncio.run(counts.fill("by-uploader", workshops, SimpleNamespace(media={})))
    assert [(e["count"], e["bytes"]) for e in workshops] == [(4, 400), (0, 0)]
    assert media.calls[0]["by"] == ["workshopId"]

    media = _fake(monkeypatch, [_row("uploadedById", "u1", 2, 20)])
    people = _folders("u1", "u2", parent="by-uploader/w1")
    asyncio.run(counts.fill("by-uploader/w1", people, SimpleNamespace(media={})))
    assert [(e["count"], e["bytes"]) for e in people] == [(2, 20), (0, 0)]

    media = _fake(monkeypatch, [_row("uploadedById", "u1", 2, 20)])
    branches = _folders("artisans", "products", "tools", "questionnaire", "media", parent="by-uploader/w1/u1")
    asyncio.run(counts.fill("by-uploader/w1/u1", branches, SimpleNamespace(media={})))
    assert {e["name"]: e["count"] for e in branches} == {
        "artisans": 0, "products": 0, "tools": 0, "questionnaire": 0, "media": 2,
    }
    assert len(media.calls) == 1


@pytest.mark.parametrize(
    "norm",
    ["", "by-workshop", "by-workshop/w1", "by-workshop/w1/crafts/c1", "by-design-workshop", "users", "users/u1"],
)
def test_levels_a_grouped_query_cannot_express_say_null_without_querying(monkeypatch, norm):
    media = _fake(monkeypatch, [])
    entries = _folders("a", "b", parent=norm or "x")
    asyncio.run(counts.fill(norm, entries, SimpleNamespace(media={})))
    assert all(e["count"] is None and e["bytes"] is None for e in entries)
    assert media.calls == []


def test_a_file_carries_its_capture_time_and_text_carries_none():
    recorded = {"kind": "file", "name": "a.m4a", "mediaId": "m1", "mediaType": "AUDIO",
                "_media": SimpleNamespace(recordedAt=RECORDED, createdAt=CREATED, mimeType="audio/mp4")}
    fallback = {"kind": "file", "name": "b.jpg", "mediaId": "m2", "mediaType": "IMAGE",
                "_media": SimpleNamespace(recordedAt=None, createdAt=CREATED, mimeType="image/jpeg")}
    text = {"kind": "file", "name": "details.txt", "content": "Kamla"}
    transcript = {"kind": "file", "name": "a.md", "_txMedia": "m1", "mediaId": "m1", "content": "words"}
    page, _total, _next = asyncio.run(tree.shape([recorded, fallback, text, transcript], lean=True))
    by_name = {e["name"]: e for e in page}
    assert by_name["a.m4a"]["capturedAt"] == RECORDED.isoformat()
    assert by_name["b.jpg"]["capturedAt"] == CREATED.isoformat()
    assert by_name["details.txt"]["capturedAt"] is None
    assert by_name["a.md"]["capturedAt"] is None


def test_manifest_lines_carry_the_capture_time(monkeypatch):
    async def query_raw(_sql: str, _ids: list[str]) -> list[dict[str, Any]]:
        return []

    import app.core.db as core_db

    monkeypatch.setattr(core_db, "db", SimpleNamespace(query_raw=query_raw))
    walker = walk.Walk(lambda *_a: None, None, None)
    walker.emit_entries(
        [
            {"kind": "file", "name": "a.jpg", "mediaId": "m1", "mediaType": "IMAGE", "sizeBytes": 5,
             "_media": SimpleNamespace(recordedAt=RECORDED, createdAt=CREATED)},
            {"kind": "file", "name": "notes.txt", "content": "hello"},
        ],
        "",
        set(),
    )
    asyncio.run(walk.fill_digests(walker.files))
    photo, note = walker.files
    assert photo["capturedAt"] == RECORDED.isoformat()
    assert note["capturedAt"] is None
