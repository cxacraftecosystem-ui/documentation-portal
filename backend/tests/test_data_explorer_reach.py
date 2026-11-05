from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.services.data_explorer import counts, reach

W = "w1"


def _m(mid: str, size: int, **links: Any) -> dict[str, Any]:
    row = {
        "id": mid, "sizeBytes": size, "workshopId": None, "craftId": None, "artisanId": None, "productId": None,
        "toolId": None, "questionnaireInterviewId": None, "linkedRecordType": None, "linkedRecordId": None,
    }
    row.update(links)
    return row


MEDIA = [
    _m("misc", 1, workshopId=W),
    _m("craft", 2, craftId="c1"),
    _m("a1-own", 4, artisanId="a1"),
    _m("a1-tagged", 8, linkedRecordType="artisan", linkedRecordId="a1"),
    _m("a1-typed-elsewhere", 16, artisanId="a1", linkedRecordType="product", linkedRecordId="p1"),
    _m("product", 32, productId="p1"),
    _m("process", 64, linkedRecordType="process", linkedRecordId="pr1"),
    _m("step", 128, linkedRecordType="processstep", linkedRecordId="s1"),
    _m("tool", 256, toolId="t1"),
    _m("orphan-tool", 512, toolId="t9", workshopId=W),
    _m("talk", 1024, questionnaireInterviewId="i1"),
    _m("a2-own", 2048, artisanId="a2"),
    _m("other-workshop-product", 4096, productId="p2"),
]


def _fake_db(monkeypatch: pytest.MonkeyPatch, hidden: dict[str, set[str]] | None = None) -> list[str]:
    hidden = hidden or {}
    statements: list[str] = []

    async def query_raw(sql: str, *_args: Any) -> list[dict[str, Any]]:
        statements.append(sql)
        if sql == reach._WORKSHOP_ARTISANS_SQL:
            return [{"workshopId": W, "artisanId": "a2"}]
        if sql == reach._WORKSHOP_CRAFTS_SQL:
            return [{"workshopId": W, "craftId": "c1"}]
        if sql == reach._FILED_TOOLS_SQL:
            return [{"id": "t9", "workshopId": W, "artisanId": "a9", "links": []}]
        if sql == reach._ARTISANS_SQL:
            return [
                {"id": "a1", "workshopId": W, "craftId": "c1", "craftExists": True},
                {"id": "a2", "workshopId": None, "craftId": None, "craftExists": False},
                {"id": "a9", "workshopId": None, "craftId": None, "craftExists": False},
            ]
        if sql == reach._PRODUCTS_SQL:
            return [
                {"id": "p1", "artisanId": "a1", "workshopId": None},
                {"id": "p2", "artisanId": "a1", "workshopId": "elsewhere"},
            ]
        if sql == reach._PROCESSES_SQL:
            return [{"id": "pr1", "productId": "p1"}]
        if sql == reach._STEPS_SQL:
            return [{"id": "s1", "processId": "pr1"}]
        if sql == reach._TOOLS_SQL:
            return [{"id": "t1", "workshopId": "elsewhere", "linkedArtisan": "a1"},
                    {"id": "t9", "workshopId": W, "linkedArtisan": "a9"}]
        if sql == reach._INTERVIEWS_SQL:
            return [{"interviewId": "i1", "artisanId": "a2"}]
        if sql == reach._MEDIA_SQL:
            return MEDIA
        raise AssertionError(sql)

    class _Delegate:
        def __init__(self, kind: str) -> None:
            self.kind = kind

        async def group_by(self, **kwargs: Any) -> list[dict[str, Any]]:
            statements.append(self.kind)
            ids = kwargs["where"]["AND"][0]["id"]["in"]
            return [{"id": i} for i in ids if i not in hidden.get(self.kind, set())]

    fake = SimpleNamespace(
        query_raw=query_raw,
        artisan=_Delegate("artisan"),
        productdocumentation=_Delegate("product"),
        process=_Delegate("process"),
        tooldocumentation=_Delegate("tool"),
        questionnaireinterview=_Delegate("interview"),
        mediafile=_Delegate("media"),
    )
    monkeypatch.setattr(reach, "db", fake)
    return statements


BROWSER = SimpleNamespace()
OPEN = SimpleNamespace(records={}, media={})


def _totals(segs: list[str], keys: list[str], scope: Any = OPEN) -> Any:
    return asyncio.run(reach.workshop_totals(segs, keys, scope, BROWSER))


def test_every_by_workshop_level_counts_what_its_walk_would_hold(monkeypatch):
    _fake_db(monkeypatch)
    products = 32 + 16 + 64 + 128
    a1 = 4 + 8 + products + 256
    a2 = 2048 + 1024
    a9 = 512
    assert _totals(["by-workshop"], [W]) == {W: (12, 1 + 2 + a1 + a2 + a9)}
    assert _totals(["by-workshop", W], ["c1", reach.NO_CRAFT]) == {"c1": (8, 2 + a1), reach.NO_CRAFT: (3, a2 + a9)}
    assert _totals(["by-workshop", W, "crafts", "c1"], ["a1"]) == {"a1": (7, a1)}
    assert _totals(["by-workshop", W, "crafts", reach.NO_CRAFT], ["a2", "a9"]) == {"a2": (2, a2), "a9": (1, a9)}
    artisan = ["by-workshop", W, "crafts", "c1", "artisans", "a1"]
    assert _totals(artisan, ["products", "tools", "questionnaire"]) == {
        "products": (4, products), "tools": (1, 256), "questionnaire": (0, 0),
    }
    assert _totals([*artisan, "products"], ["p1"]) == {"p1": (4, products)}
    assert _totals([*artisan, "products", "p1"], ["processes"]) == {"processes": (2, 192)}
    assert _totals([*artisan, "products", "p1", "processes"], ["pr1"]) == {"pr1": (2, 192)}
    assert _totals([*artisan, "products", "p1", "processes", "pr1"], ["s1"]) == {"s1": (1, 128)}
    assert _totals([*artisan, "tools"], ["t1"]) == {"t1": (1, 256)}
    assert _totals(["by-workshop", W, "artisans", "a2", "questionnaire"], ["i1"]) == {"i1": (1, 1024)}


def test_the_statement_count_does_not_depend_on_the_number_of_children(monkeypatch):
    statements = _fake_db(monkeypatch)
    _totals(["by-workshop", W], ["c1"])
    few = len(statements)
    statements.clear()
    _totals(["by-workshop", W], ["c1", reach.NO_CRAFT, "x", "y", "z", "q", "r", "s"])
    assert len(statements) == few


def test_hidden_records_and_files_drop_out(monkeypatch):
    _fake_db(monkeypatch, {"product": {"p1"}, "media": {"tool"}, "tool": {"t9"}})
    restricted = SimpleNamespace(records={"createdById": "u1"}, media={"uploadedById": "u1"})
    artisan = ["by-workshop", W, "crafts", "c1", "artisans", "a1"]
    assert _totals(artisan, ["products", "tools"], restricted) == {"products": (0, 0), "tools": (0, 0)}
    assert reach.NO_CRAFT in _totals(["by-workshop", W], [reach.NO_CRAFT], restricted)
    assert _totals(["by-workshop", W, "crafts", reach.NO_CRAFT], ["a9"], restricted) == {"a9": (0, 0)}


def test_deleted_sittings_are_hidden_where_the_portal_hides_them(monkeypatch):
    statements = _fake_db(monkeypatch, {"interview": {"i1"}})
    live = SimpleNamespace(_live=lambda where: {"AND": [where, {"deletedAt": None}]} if where else {"deletedAt": None})
    got = asyncio.run(
        reach.workshop_totals(["by-workshop", W, "artisans", "a2"], ["questionnaire"], OPEN, live)
    )
    assert got == {"questionnaire": (0, 0)}
    assert "interview" in statements


def test_by_workshop_counts_are_wired_into_the_tree(monkeypatch):
    _fake_db(monkeypatch)
    entries = [{"kind": "folder", "name": "Dabu", "path": f"by-workshop/{W}/crafts/c1"}]
    asyncio.run(counts.fill(f"by-workshop/{W}", entries, OPEN))
    assert entries[0]["count"] == 8
