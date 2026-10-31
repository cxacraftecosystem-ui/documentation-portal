"""``GET /workshops/requestable`` — the wire contract, pinned on the server AND on the web client.

Identical in both portals (they will merge). The route answered a bare array once and answers
``{"items": [...], "truncated": bool}`` now; the web panel was not told, went on spreading the
envelope as if it were the array, and Settings → "Request workshop access" died in production with
"e is not iterable". A shape change that only one side of the wire knows about is the defect, so this
file holds both sides in one place:

* the server half: the envelope's keys, and each row's keys, exactly — adding a key to a projection
  that people with no access may read is a decision (see the route's docstring), not a drive-by;
* the client half: every web reader of the route decodes it through
  ``frontend/lib/requestableWorkshops.ts::readRequestableAnswer``, and nothing casts the answer to an
  array directly. The parser's own behaviour (envelope, bare array, junk) is pinned by
  ``frontend/e2e/requestable-workshops-unit.spec.ts``.

No database: the route is called against fake tables, as ``test_workshops.py`` does.
"""

from __future__ import annotations

import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import workshops as routes

# Both portals' convention that runs everywhere: anyio ships with starlette, pytest-asyncio may not.
pytestmark = pytest.mark.anyio

REPO = Path(__file__).resolve().parents[2]
FRONTEND = REPO / "frontend"

ENVELOPE_KEYS = {"items", "truncated"}
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
        take = kwargs.get("take")
        return self.rows[:take] if take else list(self.rows)


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
    }


def _web_sources() -> list[Path]:
    roots = [FRONTEND / "app", FRONTEND / "components", FRONTEND / "lib"]
    return [p for root in roots for p in root.rglob("*.ts*") if p.suffix in {".ts", ".tsx"}]


def test_every_web_reader_decodes_the_route_through_the_one_parser():
    readers = [p for p in _web_sources() if re.search(r"apiFetch[^(]*\(\s*[\"'`]/workshops/requestable", p.read_text(encoding="utf-8"))]
    assert readers, "the request panel reads /workshops/requestable; if it moved, follow it here"
    for path in readers:
        source = path.read_text(encoding="utf-8")
        assert "readRequestableAnswer" in source, f"{path.name} reads /workshops/requestable without the parser"
        assert not re.search(r"apiFetch<[^>]*\[\]>\(\s*[\"'`]/workshops/requestable", source), (
            f"{path.name} casts /workshops/requestable to an array; the route answers an envelope"
        )


def test_the_parser_names_the_same_two_keys_the_server_sends():
    parser = (FRONTEND / "lib" / "requestableWorkshops.ts").read_text(encoding="utf-8")
    assert ".items" in parser and ".truncated" in parser
