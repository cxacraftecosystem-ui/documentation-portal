from __future__ import annotations

import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import workshops as routes

pytestmark = pytest.mark.anyio

REPO = Path(__file__).resolve().parents[2]
FRONTEND = REPO / "frontend"

ENVELOPE_KEYS = {"items", "truncated", "nextOffset"}
ROW_KEYS = {
    "id",
    "title",
    "place",
    "date",
    "startDate",
    "endDate",
    "accessStatus",
    "accessLevel",
    "restricted",
}


class _Table:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_many(self, **kwargs: Any) -> list[Any]:
        skip = kwargs.get("skip") or 0
        take = kwargs.get("take")
        return self.rows[skip : skip + take] if take else list(self.rows[skip:])


def _workshop(wid: str) -> Any:
    return SimpleNamespace(id=wid, title=f"Workshop {wid}", place="Kutch", date=None, startDate=None, endDate=None)


def _assignment(wid: str, uid: str) -> Any:
    return SimpleNamespace(workshopId=wid, userId=uid, status="PENDING", accessLevel="EDIT", assignedById=None)


async def test_the_server_answers_the_envelope_with_exactly_these_row_keys(monkeypatch):
    monkeypatch.setattr(
        routes,
        "db",
        SimpleNamespace(
            workshop=_Table([_workshop("w1"), _workshop("w2"), _workshop("w3")]),
            workshopassignment=_Table([_assignment("w1", "u-1")]),
        ),
    )
    payload = await routes.list_requestable_workshops(current_user=SimpleNamespace(id="u-1"), limit=2)
    assert isinstance(payload, dict) and set(payload) == ENVELOPE_KEYS
    assert payload["truncated"] is True
    assert [row["id"] for row in payload["items"]] == ["w1", "w2"]
    for row in payload["items"]:
        assert set(row) == ROW_KEYS
    assert payload["items"][0]["accessStatus"] == "PENDING"
    assert payload["items"][1]["accessStatus"] is None


async def test_exactly_the_cap_is_not_a_cut_and_the_extra_row_never_leaks(monkeypatch):
    def install(n: int) -> None:
        monkeypatch.setattr(
            routes,
            "db",
            SimpleNamespace(workshop=_Table([_workshop(f"w{i}") for i in range(n)]), workshopassignment=_Table([])),
        )

    install(2)
    exact = await routes.list_requestable_workshops(current_user=SimpleNamespace(id="u-1"), limit=2)
    assert exact["truncated"] is False and len(exact["items"]) == 2
    install(0)
    assert await routes.list_requestable_workshops(current_user=SimpleNamespace(id="u-1"), limit=2) == {
        "items": [],
        "truncated": False,
        "nextOffset": None,
    }


async def test_offset_pages_to_the_last_workshop(monkeypatch):
    monkeypatch.setattr(
        routes,
        "db",
        SimpleNamespace(workshop=_Table([_workshop(f"w{i}") for i in range(31)]), workshopassignment=_Table([])),
    )
    seen: list[str] = []
    offset: int | None = 0
    while offset is not None:
        page = await routes.list_requestable_workshops(current_user=SimpleNamespace(id="u-1"), limit=10, offset=offset)
        seen.extend(row["id"] for row in page["items"])
        offset = page["nextOffset"]
    assert seen == [f"w{i}" for i in range(31)]


def _web_sources() -> list[Path]:
    roots = [FRONTEND / "app", FRONTEND / "components", FRONTEND / "lib"]
    return [p for root in roots for p in root.rglob("*.ts*") if p.suffix in {".ts", ".tsx"}]


def test_every_web_reader_decodes_the_route_through_the_one_parser():
    readers = [p for p in _web_sources() if re.search(r"apiFetch[^(]*\(\s*[\"'`]/workshops/requestable", p.read_text(encoding="utf-8"))]
    assert readers, "the request panel reads /workshops/requestable; if it moved, follow it here"
    for path in readers:
        source = path.read_text(encoding="utf-8")
        assert "readRequestableAnswer" in source or "readEveryRequestable" in source, f"{path.name} reads /workshops/requestable without the parser"
        assert not re.search(r"apiFetch<[^>]*\[\]>\(\s*[\"'`]/workshops/requestable", source), (
            f"{path.name} casts /workshops/requestable to an array; the route answers an envelope"
        )


def test_the_parser_names_the_same_two_keys_the_server_sends():
    parser = (FRONTEND / "lib" / "requestableWorkshops.ts").read_text(encoding="utf-8")
    assert ".items" in parser and ".truncated" in parser
