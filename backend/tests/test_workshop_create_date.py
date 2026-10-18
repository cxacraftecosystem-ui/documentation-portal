"""A workshop create with no date is a 422, not a 500.

``date`` is a required column. Before the schema checked it, a body with neither ``date`` nor
``startDate`` reached Prisma and the route answered a bare 500.
"""

from __future__ import annotations

from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from app.schemas.records import WorkshopCreate

_LOCATION = {"latitude": 23.24, "longitude": 69.66, "state": "Gujarat", "district": "Kachchh"}
_DAY = datetime(2026, 9, 3, tzinfo=UTC)


def test_a_create_with_neither_date_is_refused():
    with pytest.raises(ValidationError, match="date or startDate is required"):
        WorkshopCreate(title="Bhuj visit", place="Bhuj", location=_LOCATION)


@pytest.mark.parametrize("field", ["date", "startDate"])
def test_either_date_is_enough(field):
    body = WorkshopCreate(title="Bhuj visit", place="Bhuj", location=_LOCATION, **{field: _DAY})
    assert getattr(body, field) == _DAY
