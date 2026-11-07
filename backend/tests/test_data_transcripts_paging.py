from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from app.api.routes import data_explorer

USER = SimpleNamespace(id="u1", role="MASTER_ADMIN", canDownloadDataset=True)
COUNT = 1234


class _Media:
    def __init__(self) -> None:
        self.asked: list[int] = []

    async def find_many(self, where: dict[str, Any], **_: Any) -> list[Any]:
        ids = where["AND"][0]["id"]["in"]
        self.asked.append(len(ids))
        return [
            SimpleNamespace(
                id=i,
                mediaType="AUDIO",
                originalFilename=f"{i}.m4a",
                transcriptText=f"indigo talk {i}",
                createdAt=datetime(2026, 11, 1, tzinfo=UTC),
            )
            for i in ids
        ]


def _world(monkeypatch) -> _Media:
    media = _Media()

    async def scope(_user: Any) -> Any:
        return SimpleNamespace(media=None)

    async def manifest_files(*_args: Any, **_kwargs: Any) -> tuple[list[dict[str, Any]], bool]:
        return [
            {"mediaId": f"m{n:04d}", "mediaType": "AUDIO", "path": f"w/m{n:04d}.m4a", "originalFilename": f"m{n:04d}.m4a"}
            for n in range(1, COUNT + 1)
        ], False

    monkeypatch.setattr(data_explorer, "download_scope", scope)
    monkeypatch.setattr(data_explorer, "_browser", lambda: SimpleNamespace(_norm=lambda p: p.strip("/"), _list_level=None))
    monkeypatch.setattr(data_explorer.walk, "manifest_files", manifest_files)
    monkeypatch.setattr(data_explorer, "db", SimpleNamespace(mediafile=media))

    async def all_consented(*_args: Any, **_kwargs: Any) -> set[str]:
        return set()

    monkeypatch.setattr(data_explorer.transcript_export, "withheld_ids", all_consented)
    return media


def _list(offset: int = 0, q: str | None = None) -> dict[str, Any]:
    return asyncio.run(data_explorer.transcript_list(path="w", q=q, offset=offset, current_user=USER))


def test_the_transcripts_tab_pages_to_the_last_recording(monkeypatch):
    media = _world(monkeypatch)
    seen: list[str] = []
    offset: int | None = 0
    pages = 0
    while offset is not None:
        page = _list(offset)
        assert page["offset"] == offset
        seen += [item["mediaId"] for item in page["items"]]
        offset = page["nextOffset"]
        pages += 1
    assert pages == 3
    assert seen == [f"m{n:04d}" for n in range(1, COUNT + 1)]
    assert max(media.asked) == data_explorer.TRANSCRIPT_LIST_MAX


def test_the_first_page_keeps_its_old_shape(monkeypatch):
    _world(monkeypatch)
    first = _list()
    assert len(first["items"]) == data_explorer.TRANSCRIPT_LIST_MAX
    assert first["truncated"] is True and first["total"] == data_explorer.TRANSCRIPT_LIST_MAX
    assert first["nextOffset"] == data_explorer.TRANSCRIPT_LIST_MAX
    last = _list(1000)
    assert last["truncated"] is False and last["nextOffset"] is None
    assert last["items"][-1]["mediaId"] == f"m{COUNT:04d}"


def test_a_search_walks_every_page(monkeypatch):
    _world(monkeypatch)
    hits: list[str] = []
    offset: int | None = 0
    while offset is not None:
        page = _list(offset, q="1234")
        hits += [item["mediaId"] for item in page["items"]]
        offset = page["nextOffset"]
    assert hits == ["m1234"]
