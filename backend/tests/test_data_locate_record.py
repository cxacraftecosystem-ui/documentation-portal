from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import data_browser
from app.api.routes.data_browser import Scope


class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


def _match(row: Any, where: dict[str, Any]) -> bool:
    for key, wanted in (where or {}).items():
        if key == "AND":
            if not all(_match(row, part) for part in wanted):
                return False
            continue
        value = getattr(row, key, None)
        if isinstance(wanted, dict) and "in" in wanted:
            if value not in wanted["in"]:
                return False
        elif value != wanted:
            return False
    return True


class Table:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_first(self, where: dict[str, Any] | None = None, **_: Any) -> Any:
        return next((r for r in self.rows if _match(r, where or {})), None)

    async def find_unique(self, where: dict[str, Any], **_: Any) -> Any:
        return await self.find_first(where=where)

    async def find_many(self, where: dict[str, Any] | None = None, **_: Any) -> list[Any]:
        return [r for r in self.rows if _match(r, where or {})]


def _db(monkeypatch: pytest.MonkeyPatch, **tables: list[Any]) -> None:
    names = ("workshop", "workshopcraft", "workshopartisan", "artisan", "craft", "productdocumentation")
    monkeypatch.setattr(data_browser, "db", SimpleNamespace(**{n: Table(tables.get(n, [])) for n in names}))


EVERYTHING = Scope(records={}, media={})


def _scope(scope: Scope):
    async def scoped(_user: Any, _status: Any = None) -> Scope:
        return scope

    return scoped


def _locate(kind: str, record_id: str) -> dict[str, Any]:
    return asyncio.run(
        data_browser.data_locate(type=kind, id=record_id, status_value=None, current_user=Row(id="u"))
    )


def test_a_craft_outside_every_workshop_offers_its_record_page(monkeypatch):
    _db(monkeypatch, craft=[Row(id="c9")])
    monkeypatch.setattr(data_browser, "_scope_for", _scope(EVERYTHING))
    assert _locate("craft", "c9") == {"path": None, "locatable": False, "record": {"kind": "craft", "id": "c9"}}
    assert _locate("craft", "nope") == {"path": None, "locatable": False, "record": None}


def test_an_artisan_with_no_workshop_offers_its_record_page_within_scope(monkeypatch):
    _db(monkeypatch, artisan=[Row(id="a1", ownerId="u")])
    monkeypatch.setattr(data_browser, "_scope_for", _scope(EVERYTHING))
    assert _locate("artisan", "a1") == {"path": None, "locatable": False, "record": {"kind": "artisan", "id": "a1"}}
    hidden = Scope(records={"ownerId": "someone-else"}, media={})
    monkeypatch.setattr(data_browser, "_scope_for", _scope(hidden))
    assert _locate("artisan", "a1") == {"path": None, "locatable": False, "record": None}


def test_a_located_record_keeps_its_folder_and_sends_no_fallback(monkeypatch):
    _db(monkeypatch, workshopcraft=[Row(craftId="c1", workshopId="w1")], workshop=[Row(id="w1")], craft=[Row(id="c1")])
    monkeypatch.setattr(data_browser, "_scope_for", _scope(EVERYTHING))
    assert _locate("craft", "c1") == {"path": "by-workshop/w1/crafts/c1", "locatable": True, "record": None}


def test_kinds_without_a_record_page_never_offer_one(monkeypatch):
    _db(monkeypatch)
    monkeypatch.setattr(data_browser, "_scope_for", _scope(EVERYTHING))
    assert _locate("product", "p1") == {"path": None, "locatable": False, "record": None}
