from __future__ import annotations

import json
import pathlib
from typing import Any

import pytest

from app.services import record_fields
from app.services.record_filters import (
    tool_artisan_clause,
    tool_craft_clause,
    tool_workshop_clause,
    tools_in_workshop_clause,
)
from app.services.tool_scope import (
    tool_artisan_names,
    tool_fits,
    tool_workshop_ids,
    tool_workshop_titles,
)

ROOT = pathlib.Path(__file__).resolve().parents[2]
VECTORS = json.loads((ROOT / "shared" / "tool-scope-vectors.json").read_text(encoding="utf-8"))


class _Row:
    def __init__(self, **columns: Any) -> None:
        self.__dict__.update(columns)

    def __getattr__(self, name: str) -> Any:
        return None


def _ns(value: Any) -> Any:
    if isinstance(value, dict):
        return _Row(**{key: _ns(item) for key, item in value.items()})
    if isinstance(value, list):
        return [_ns(item) for item in value]
    return value


@pytest.mark.parametrize("row", VECTORS["artisanNames"], ids=lambda r: r["case"])
def test_artisan_names_match_the_shared_vectors(row):
    assert tool_artisan_names(_ns(row["tool"])) == row["expected"]


@pytest.mark.parametrize("row", VECTORS["linkedWorkshops"], ids=lambda r: r["case"])
def test_linked_workshops_match_the_shared_vectors(row):
    assert tool_workshop_titles(_ns(row["tool"])) == row["expected"]


@pytest.mark.parametrize("row", VECTORS["fits"], ids=lambda r: r["case"])
def test_placement_matches_the_shared_vectors(row):
    fits = tool_fits(_ns(row["tool"]), row["workshopId"], row["artisanId"], set(row["members"]))
    assert fits is row["expected"]


def test_the_tool_sheet_names_every_artisan_and_workshop():
    columns = record_fields.sheet_columns("tool")
    assert columns.index("Linked workshops") == columns.index("Workshop") + 1
    tool = VECTORS["linkedWorkshops"][0]["tool"]
    record = _ns(
        {
            **tool,
            "toolkitName": "Block",
            "artisanId": "a1",
            "artisanName": "Kamla Devi",
            "artisanLinks": [
                {**link, "artisan": {**link["artisan"], "name": "Ramesh"}}
                for link in tool["artisanLinks"]
            ],
        }
    )
    row = dict(zip(columns, record_fields.sheet_row("tool", record), strict=False))
    assert row["Linked workshops"] == "Sanganer, Bagru, Akola"
    assert row["Artisan"] == "Kamla Devi, Ramesh"


def test_the_link_tables_are_matched_as_well_as_the_scalar_columns():
    assert tool_artisan_clause(["a1"]) == {
        "OR": [
            {"artisanId": {"in": ["a1"]}},
            {"artisanLinks": {"some": {"artisanId": {"in": ["a1"]}}}},
        ]
    }
    assert tool_craft_clause(["c1"]) == {
        "OR": [
            {"craftId": {"in": ["c1"]}},
            {"craftLinks": {"some": {"craftId": {"in": ["c1"]}}}},
        ]
    }


def test_the_workshop_clause_reaches_through_both_artisan_paths():
    clause = tool_workshop_clause(["w1"], False)
    branches = clause["OR"]
    assert {"workshopId": {"in": ["w1"]}} in branches
    assert any("artisan" in branch for branch in branches)
    assert any("artisanLinks" in branch for branch in branches)


def test_a_visibility_rule_is_carried_into_both_artisan_paths():
    seen = {"createdById": "u1"}
    text = json.dumps(tool_workshop_clause(["w1"], False, seen))
    assert text.count('"createdById": "u1"') == 6


def test_an_empty_selection_matches_nothing():
    assert tool_workshop_clause([], False) == {"id": {"in": []}}


def test_the_browser_clause_admits_a_members_tool_from_any_workshop():
    clause = tools_in_workshop_clause("w2", ["a2"])
    text = json.dumps(clause)
    assert '"workshopId": "w2"' in text
    assert '"workshopId": null' in text
    assert text.count('"artisanId": {"in": ["a2"]}') == 4


def test_the_search_index_takes_every_workshop_the_titles_name():
    ids = [tool_workshop_ids(_ns(row["tool"])) for row in VECTORS["linkedWorkshops"]]
    assert ids == [["w1", "w2", "w3"], ["w2"], []]


def test_the_search_index_reads_the_scalar_workshop_columns_too():
    tool = _ns(
        {
            "workshopId": "w9",
            "artisan": {"id": "a1", "workshopId": "w8", "workshops": [{"workshopId": "w7"}]},
            "artisanLinks": [],
        }
    )
    assert tool_workshop_ids(tool) == ["w9", "w7", "w8"]
