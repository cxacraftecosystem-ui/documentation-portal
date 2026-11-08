from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import media as media_routes


class _Files:
    def __init__(self) -> None:
        self.found: list[dict[str, Any]] = []
        self.counted: list[dict[str, Any]] = []

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.found.append(kwargs)
        return []

    async def count(self, **kwargs: Any) -> int:
        self.counted.append(kwargs)
        return 0


@pytest.fixture
def files(monkeypatch: pytest.MonkeyPatch) -> _Files:
    fake = _Files()

    async def no_scope(*_args: Any, **_kwargs: Any) -> None:
        return None

    async def no_chips(*_args: Any, **_kwargs: Any) -> list[str]:
        return []

    async def plain(rows: Any, *_args: Any, **_kwargs: Any) -> Any:
        return rows

    monkeypatch.setattr(media_routes, "db", SimpleNamespace(mediafile=fake))
    monkeypatch.setattr(media_routes, "viewable_where", no_scope)
    monkeypatch.setattr(media_routes.questionnaire_filters, "media_text_ids", no_chips)
    monkeypatch.setattr(media_routes, "_public", plain)
    return fake


def _list(**kwargs: Any) -> Any:
    params: dict[str, Any] = {
        "current_user": SimpleNamespace(id="u1", role="MASTER_ADMIN"),
        "request": None,
        "search": None,
        "q": None,
        "mediaType": None,
        "linkedRecordType": None,
        "linkedRecordId": None,
        "statusFilter": None,
        "dateFrom": None,
        "dateTo": None,
        "uploadedBy": None,
        "page": 1,
        "pageSize": 20,
    }
    if "designWorkshopId" in media_routes.list_media.__code__.co_varnames:
        params["designWorkshopId"] = None
    params.update(kwargs)
    return asyncio.run(media_routes.list_media(**params))


def _names(where: Any) -> dict[str, Any]:
    found: dict[str, Any] = {}
    if isinstance(where, dict):
        for key, value in where.items():
            if key in ("originalFilename", "caption"):
                found[key] = value
            else:
                found.update(_names(value))
    elif isinstance(where, list):
        for item in where:
            found.update(_names(item))
    return found


def test_q_searches_names_and_captions_without_case(files: _Files) -> None:
    _list(q="  Indigo Loom ")
    where = files.found[-1]["where"]
    named = _names(where)
    assert named["originalFilename"] == {"contains": "Indigo Loom", "mode": "insensitive"}
    assert named["caption"] == {"contains": "Indigo Loom", "mode": "insensitive"}
    assert files.counted[-1]["where"] == where


def test_q_is_paged_like_every_other_list(files: _Files) -> None:
    answer = _list(q="loom", page=3, pageSize=50)
    found = files.found[-1]
    assert found["take"] == 50 and found["skip"] == 100
    assert answer["page"] == 3 and answer["pageSize"] == 50


def test_without_q_nothing_is_narrowed_and_search_still_works(files: _Files) -> None:
    _list()
    assert _names(files.found[-1]["where"]) == {}
    _list(search="dye")
    assert _names(files.found[-1]["where"])["originalFilename"]["contains"] == "dye"
    _list(q="   ", search="dye")
    assert _names(files.found[-1]["where"])["originalFilename"]["contains"] == "dye"
