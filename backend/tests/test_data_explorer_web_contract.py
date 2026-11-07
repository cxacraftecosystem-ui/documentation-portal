from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from test_data_overview import WID, _browser, _fake_db

from app.services.data_explorer import browse, overview

SHARED = Path(__file__).resolve().parents[2] / "shared"
TYPES = {"string": str, "number": (int, float), "boolean": bool, "null": type(None)}


def _schema() -> dict[str, Any]:
    return json.loads((SHARED / "data-explorer-overview-schema.json").read_text(encoding="utf-8"))[
        "schema"
    ]


def _drift(value: Any, schema: Any, where: str = "$") -> list[str]:
    if isinstance(schema, dict):
        if not isinstance(value, dict):
            return [f"{where} is {type(value).__name__}, the schema has an object"]
        out: list[str] = []
        wanted = {key.rstrip("?"): key.endswith("?") for key in schema}
        for key, optional in wanted.items():
            if key not in value and not optional:
                out.append(f"{where}.{key} is missing")
        for key, item in value.items():
            if key not in wanted:
                out.append(f"{where}.{key} is not in the schema")
                continue
            spec = schema[key] if key in schema else schema[f"{key}?"]
            out.extend(_drift(item, spec, f"{where}.{key}"))
        return out
    if isinstance(schema, list):
        if not isinstance(value, list):
            return [f"{where} is {type(value).__name__}, the schema has an array"]
        return [
            line
            for index, item in enumerate(value)
            for line in _drift(item, schema[0], f"{where}[{index}]")
        ]
    allowed = tuple(TYPES[name] for name in str(schema).split("|"))
    if isinstance(value, bool) and bool not in allowed:
        return [f"{where} is boolean, the schema has {schema}"]
    return (
        []
        if isinstance(value, allowed)
        else [f"{where} is {type(value).__name__}, the schema has {schema}"]
    )


def test_the_overview_the_server_sends_matches_the_shared_schema(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(overview, "db", _fake_db([]))
    scope = SimpleNamespace(records={}, media={})
    body = asyncio.run(overview.workshop_overview(_browser(), f"by-workshop/{WID}", scope))
    assert body["artisans"], (
        "the fixture must carry artisans or the per-artisan shape goes unchecked"
    )
    assert _drift(body, _schema()) == []


def test_the_schema_check_catches_a_review_sent_as_a_word() -> None:
    schema = _schema()
    artisan = {
        "id": "a1",
        "name": "Kamla",
        "craft": None,
        "path": "x",
        "fields": {"filled": 1, "total": 2},
        "media": {"files": 0, "bytes": 0},
        "transcripts": {"total": 0, "ready": 0, "translating": 0, "failed": 0},
        "review": "APPROVED",
        "sections": [],
    }
    assert _drift(artisan, schema["artisans"][0]) == ["$.review is str, the schema has an object"]


def test_browse_types_are_the_contract_tokens() -> None:
    contract = json.loads((SHARED / "data-explorer-contract.json").read_text(encoding="utf-8"))
    route = next(r for r in contract["routes"] if r["id"] == "browse")
    tokens = [t.strip() for t in route["query"]["type"].split("|")]
    assert list(browse.KINDS) == tokens
