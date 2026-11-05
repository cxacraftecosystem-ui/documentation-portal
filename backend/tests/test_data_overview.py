from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.services.data_explorer import overview

WID = "w1"
CRAFTS = f"by-workshop/{WID}/crafts"


def _artisan(aid: str, name: str, craft: str | None, status: str) -> Any:
    return SimpleNamespace(
        id=aid, name=name, craftId=craft, craft=SimpleNamespace(name="Dabu") if craft else None, status=status
    )


def _file(mid: str, kind: str, size: int) -> dict[str, Any]:
    return {"kind": "file", "name": f"{mid}.bin", "path": f"x/{mid}", "mediaId": mid, "mediaType": kind, "sizeBytes": size}


ARTISANS = [_artisan("a1", "Kamla", "c1", "APPROVED"), _artisan("a2", "Asha", None, "PENDING")]
LEVELS: dict[str, list[dict[str, Any]]] = {
    f"by-workshop/{WID}": [
        {"kind": "folder", "name": "Dabu", "path": f"{CRAFTS}/c1"},
        {"kind": "folder", "name": "No craft", "path": f"{CRAFTS}/_none"},
        _file("ws-photo", "IMAGE", 10),
    ],
    f"{CRAFTS}/c1": [{"kind": "folder", "name": "Kamla", "path": f"{CRAFTS}/c1/artisans/a1"}],
    f"{CRAFTS}/_none": [{"kind": "folder", "name": "Asha", "path": f"{CRAFTS}/_none/artisans/a2"}],
    f"{CRAFTS}/c1/artisans/a1": [_file("m1", "AUDIO", 100), _file("m2", "IMAGE", 50)],
    f"{CRAFTS}/_none/artisans/a2": [_file("m3", "VIDEO", 400)],
}


def _browser() -> Any:
    async def require(_delegate: Any, wid: str, *_args: Any, **_kwargs: Any) -> Any:
        crafts = [SimpleNamespace(craft=SimpleNamespace(name="Dabu"))]
        return SimpleNamespace(id=wid, title="Bagru dabu", crafts=crafts)

    async def linked(_ws: Any, _scope: Any) -> list[Any]:
        return ARTISANS

    async def level(path: str, _scope: Any) -> Any:
        return [dict(e) for e in LEVELS.get(path, [])], None

    def both(where: dict[str, Any], extra: dict[str, Any]) -> dict[str, Any]:
        return {"AND": [where, extra]} if extra else where

    return SimpleNamespace(
        _require=require, _WS_CRAFTS_INCLUDE={}, _linked_artisans=linked, _list_level=level, _and=both
    )


class _Rows:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return self.rows


def _fake_db(statements: list[str]) -> Any:
    questions = [
        SimpleNamespace(id="q1", sectionCode="A", sectionTitle="About", prompt="Name?", lineageId=None),
        SimpleNamespace(id="q2", sectionCode="A", sectionTitle="About", prompt="Age?", lineageId=None),
        SimpleNamespace(id="q3", sectionCode="B", sectionTitle="Craft", prompt="Tools?", lineageId="L3"),
    ]
    interviews = [SimpleNamespace(id="i1", artisans=[SimpleNamespace(artisanId="a1")])]
    media = {
        "m1": {"id": "m1", "mediaType": "AUDIO", "status": "APPROVED", "transcriptStatus": "COMPLETED", "hasText": True, "translating": False},
        "m2": {"id": "m2", "mediaType": "IMAGE", "status": "PENDING", "transcriptStatus": None, "hasText": False, "translating": False},
        "m3": {"id": "m3", "mediaType": "VIDEO", "status": "PENDING", "transcriptStatus": "FAILED", "hasText": False, "translating": False},
        "ws-photo": {"id": "ws-photo", "mediaType": "IMAGE", "status": "APPROVED", "transcriptStatus": None, "hasText": False, "translating": False},
    }

    async def query_raw(sql: str, ids: list[str], *_args: Any) -> list[dict[str, Any]]:
        statements.append(sql)
        if "QuestionnaireResponse" in sql:
            return [{"interviewId": "i1", "key": "q1"}, {"interviewId": "i1", "key": "L3"}]
        return [media[i] for i in ids if i in media]

    return SimpleNamespace(
        query_raw=query_raw,
        questionnairequestion=_Rows(questions),
        questionnaireinterview=_Rows(interviews),
        workshop=object(),
    )


def test_the_overview_counts_every_artisan_and_section(monkeypatch: pytest.MonkeyPatch) -> None:
    statements: list[str] = []
    monkeypatch.setattr(overview, "db", _fake_db(statements))
    scope = SimpleNamespace(records={}, media={})
    body = asyncio.run(overview.workshop_overview(_browser(), f"by-workshop/{WID}", scope))

    assert body["title"] == "Bagru dabu" and body["subtitle"] == "Dabu"
    assert body["sections"] == [{"key": "A", "label": "About"}, {"key": "B", "label": "Craft"}]
    assert body["totals"]["files"] == 4 and body["totals"]["bytes"] == 560
    assert body["totals"]["fields"] == {"filled": 2, "total": 6}
    assert body["totals"]["transcripts"] == {"total": 2, "ready": 1, "translating": 0, "failed": 1}
    assert body["totals"]["review"] == {"approved": 3, "pending": 3, "total": 6}
    kamla, asha = body["artisans"]
    assert kamla["path"] == f"{CRAFTS}/c1/artisans/a1" and asha["path"] == f"{CRAFTS}/_none/artisans/a2"
    assert kamla["media"] == {"files": 2, "bytes": 150} and asha["media"] == {"files": 1, "bytes": 400}
    assert kamla["fields"] == {"filled": 2, "total": 3}
    assert kamla["sections"] == [
        {"key": "A", "state": "partial", "missing": ["Age?"]},
        {"key": "B", "state": "complete", "missing": []},
    ]
    assert [s["state"] for s in asha["sections"]] == ["missing", "missing"]
    assert asha["transcripts"]["failed"] == 1 and kamla["transcripts"]["ready"] == 1
    assert kamla["review"] == {"approved": 2, "pending": 1, "total": 3}
    assert body["truncated"] is False
    assert len(statements) == 2


def test_a_folder_below_the_workshop_has_no_overview(monkeypatch: pytest.MonkeyPatch) -> None:
    from fastapi import HTTPException

    monkeypatch.setattr(overview, "db", _fake_db([]))
    with pytest.raises(HTTPException) as refused:
        asyncio.run(overview.workshop_overview(_browser(), f"{CRAFTS}/c1", SimpleNamespace(records={}, media={})))
    assert refused.value.status_code == 404
