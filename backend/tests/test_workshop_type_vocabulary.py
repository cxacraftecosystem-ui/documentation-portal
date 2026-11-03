import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.schemas.records import WORKSHOP_TYPES, WorkshopCreate, WorkshopUpdate

_BODY = {
    "title": "Bhuj toolkit workshop",
    "place": "Bhuj",
    "date": "2026-09-03T00:00:00Z",
    "location": {"latitude": 23.24, "longitude": 69.66, "state": "Gujarat", "district": "Kachchh"},
}


def test_the_vocabulary_is_the_two_tokens_the_enum_declares():
    from pathlib import Path

    schema = (
        Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"
    ).read_text(encoding="utf-8")
    start = schema.index("enum WorkshopType {")
    block = schema[start : schema.index("\n}", start)]
    declared = {line.strip() for line in block.splitlines()[1:] if line.strip()}

    assert declared == set(WORKSHOP_TYPES) == {"DESIGN_PROTOTYPE", "OTHER"}


def test_a_workshop_created_without_a_kind_is_an_ordinary_one():
    assert WorkshopCreate(**_BODY).workshopType == "OTHER"


def test_an_unknown_kind_is_refused_by_both_bodies():
    with pytest.raises(ValidationError):
        WorkshopCreate(**{**_BODY, "workshopType": "DESIGN_WORKSHOP"})
    with pytest.raises(ValidationError):
        WorkshopUpdate(workshopType="design_prototype")


def test_omitting_the_kind_on_a_correction_leaves_the_stored_one_alone():
    update = WorkshopUpdate(title="Bhuj toolkit workshop")

    assert update.workshopType is None
    assert "workshopType" not in update.model_dump(exclude_unset=True)


def test_the_list_filter_refuses_an_unknown_kind_before_it_reaches_prisma(monkeypatch):
    from app.api.routes import workshops

    async def _viewable_where(_user, *_args, **_kwargs):
        return {}

    async def _unreachable(*_args, **_kwargs):
        raise AssertionError("the list ran a query with an invalid enum filter")

    monkeypatch.setattr(workshops, "viewable_where", _viewable_where)
    monkeypatch.setattr(workshops, "count_and_page", _unreachable)

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            workshops.list_workshops(
                current_user=SimpleNamespace(id="usr_7", role="RESEARCHER"),
                workshopType="DESIGN_WORKSHOP",
                page=1,
                pageSize=20,
            )
        )

    assert refusal.value.status_code == 422
    assert "DESIGN_PROTOTYPE" in refusal.value.detail


def test_a_known_kind_reaches_the_query_as_an_equality_filter(monkeypatch):
    from app.api.routes import workshops

    captured: dict[str, Any] = {}

    async def _viewable_where(_user, *_args, **_kwargs):
        return {}

    async def _count_and_page(_delegate, *, where, **_kwargs):
        captured["where"] = where
        return 0, []

    monkeypatch.setattr(workshops, "viewable_where", _viewable_where)
    monkeypatch.setattr(workshops, "count_and_page", _count_and_page)

    asyncio.run(
        workshops.list_workshops(
            current_user=SimpleNamespace(id="usr_7", role="RESEARCHER"),
            workshopType="DESIGN_PROTOTYPE",
            page=1,
            pageSize=20,
        )
    )

    assert captured["where"]["workshopType"] == "DESIGN_PROTOTYPE"
