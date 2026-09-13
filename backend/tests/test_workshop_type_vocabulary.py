"""A workshop kind the database does not have is a 422 that names the kinds, not a 500.

``Workshop.workshopType`` reaches a POSTGRES ENUM column. An unknown value is therefore not merely
stored wrong — Prisma refuses it and the route answers a bare 500, which reads to a client as "the
server is broken" rather than "that is not a kind of workshop". So the vocabulary is declared once in
``schemas/records.WORKSHOP_TYPES`` and checked in three places that would otherwise drift: the create
body, the update body and the list's query parameter, which does not pass through a pydantic model at
all.

WHY THIS PRODUCT CARRIES A TOKEN IT HAS NO SCREEN FOR. ``DESIGN_PROTOTYPE`` names a thing this
repository does not model — there is no DesignWorkshop table here. It is carried because the two
products coordinate their record vocabularies on purpose, exactly as ``experienceYears``' 0..90 bound
is: a workshop recorded here and later adopted by the sibling must be able to carry the same mark.
"""

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
    "location": {"latitude": 23.24, "longitude": 69.66, "state": "Gujarat", "district": "Kachchh"},
}


def test_the_vocabulary_is_the_two_tokens_the_enum_declares():
    """Read against the schema file rather than retyped here, because the three checkers and the
    Postgres type have to agree and a hand-copied list in a test is a fourth place to forget."""
    from pathlib import Path

    schema = (
        Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"
    ).read_text(encoding="utf-8")
    start = schema.index("enum WorkshopType {")
    block = schema[start : schema.index("\n}", start)]
    declared = {line.strip() for line in block.splitlines()[1:] if line.strip()}

    assert declared == set(WORKSHOP_TYPES) == {"DESIGN_PROTOTYPE", "OTHER"}


def test_a_workshop_created_without_a_kind_is_an_ordinary_one():
    """OTHER is what every row recorded before this column implicitly was, so an older client that
    has never heard of the field goes on creating exactly the workshops it always did."""
    assert WorkshopCreate(**_BODY).workshopType == "OTHER"


def test_an_unknown_kind_is_refused_by_both_bodies():
    with pytest.raises(ValidationError):
        WorkshopCreate(**{**_BODY, "workshopType": "DESIGN_WORKSHOP"})
    with pytest.raises(ValidationError):
        WorkshopUpdate(workshopType="design_prototype")  # the enum is uppercase, and this is not it


def test_omitting_the_kind_on_a_correction_leaves_the_stored_one_alone():
    """A PATCH that does not mention the kind must not re-assert OTHER over a workshop somebody
    marked DESIGN_PROTOTYPE — which is also why the Android request field is nullable rather than
    defaulted, since that class is the PATCH body there."""
    update = WorkshopUpdate(title="Bhuj toolkit workshop")

    assert update.workshopType is None
    assert "workshopType" not in update.model_dump(exclude_unset=True)


def test_the_list_filter_refuses_an_unknown_kind_before_it_reaches_prisma(monkeypatch):
    """THE QUERY PARAMETER DOES NOT PASS THROUGH A PYDANTIC MODEL, so the check the two bodies get
    for free has to be repeated by hand here — and the refusal has to happen before the ``where`` is
    handed to Prisma, which would answer an invalid enum filter with a bare 500."""
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
                # The route's paging arguments carry FastAPI ``Query`` defaults, which are markers
                # rather than values: calling the function directly has to pass real ones.
                page=1,
                pageSize=20,
            )
        )

    assert refusal.value.status_code == 422
    assert "DESIGN_PROTOTYPE" in refusal.value.detail


def test_a_known_kind_reaches_the_query_as_an_equality_filter(monkeypatch):
    """And the filter has to actually be applied, or the refusal above would be the only thing the
    parameter ever did."""
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
