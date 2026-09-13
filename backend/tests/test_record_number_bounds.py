"""A negative length is not a measurement, and the refusal names the box it came from.

Every measurement and price on ``ProductDocumentation`` and ``ToolDocumentation`` took a negative
from the form and stored it. Nothing downstream noticed: a workbook printed "-12", an export printed
"-12", and the sibling repository — whose workshop registry declares the fields these are carried
into as non-negative — would refuse on import a quantity this product had accepted.

THE BOUNDS ARE DERIVED FROM THE SCHEMAS, NOT RETYPED. A list copied into this file would agree with
itself and go on passing the day somebody adds a tenth measurement without a bound. The counts are
asserted too, so a field dropped out of the sweep goes red rather than silently uncovered.
"""

from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.schemas.records import ProductCreate, ProductUpdate, ToolCreate, ToolUpdate


def _bounded_measures(schema) -> list[str]:
    """The Decimal columns on a schema that declare a lower bound, read off the model itself.

    Decimal-typed on purpose: ``yearsInUse`` carries ``ge=0`` too and is an ``int``, and folding it
    in here would make the counts below describe something other than "the measurements and the
    prices" — which is the set this change swept.
    """
    found = []
    for name, field in schema.model_fields.items():
        if "Decimal" not in str(field.annotation):
            continue
        if any(getattr(meta, "ge", None) is not None for meta in field.metadata):
            found.append(name)
    return found


PRODUCT_MEASURES = _bounded_measures(ProductCreate)
TOOL_MEASURES = _bounded_measures(ToolCreate)

_PRODUCT_BODY = {
    "craftName": "Bandhani",
    "place": "Bhuj",
    "artisanName": "A. Khatri",
    "productName": "Odhani",
    "location": {"latitude": 23.24, "longitude": 69.66, "state": "Gujarat", "district": "Kachchh"},
}
_TOOL_BODY = {
    "craftName": "Bandhani",
    "place": "Bhuj",
    "artisanName": "A. Khatri",
    "toolkitName": "Dyeing vat",
    "location": {"latitude": 23.24, "longitude": 69.66, "state": "Gujarat", "district": "Kachchh"},
}


def test_the_sweep_covers_every_measurement_and_price_on_both_models():
    """THE COUNTS ARE THE POINT: a field added to either model without a bound, or dropped out of the
    sweep by a careless merge, goes red here rather than quietly accepting a negative again.

    Five on a product — three dimensions, the cost of making and the selling price. Nine on a tool —
    the same three dimensions plus the unit-less ``height``, ``width``, ``thickness``, ``weight``,
    ``radius`` and the replacement cost. The two lists overlap without either containing the other,
    which is the same property the clearable tuples have and for the same reason.
    """
    assert len(PRODUCT_MEASURES) == 5, PRODUCT_MEASURES
    assert len(TOOL_MEASURES) == 9, TOOL_MEASURES
    # Create and update must carry the same bounds, or the number is correctable through a PATCH and
    # therefore not bounded at all.
    assert _bounded_measures(ProductUpdate) == PRODUCT_MEASURES
    assert _bounded_measures(ToolUpdate) == TOOL_MEASURES
    # ``heightInches`` is on the tool list as of migration 20260913120100 — the column that gives a
    # measured tool height somewhere to land that records its unit.
    assert "heightInches" in TOOL_MEASURES


@pytest.mark.parametrize("column", PRODUCT_MEASURES)
def test_a_negative_product_measurement_is_refused_by_name(column):
    """THE ``loc`` HAS TO NAME THE COLUMN, or the client cannot show the researcher which box to fix
    — and a 422 nobody can act on is only marginally better than storing the negative."""
    with pytest.raises(ValidationError) as refusal:
        ProductCreate(**{**_PRODUCT_BODY, column: Decimal("-1")})

    assert column in {str(error["loc"][0]) for error in refusal.value.errors()}


@pytest.mark.parametrize("column", TOOL_MEASURES)
def test_a_negative_tool_measurement_is_refused_by_name(column):
    with pytest.raises(ValidationError) as refusal:
        ToolCreate(**{**_TOOL_BODY, column: Decimal("-1")})

    assert column in {str(error["loc"][0]) for error in refusal.value.errors()}


@pytest.mark.parametrize("column", PRODUCT_MEASURES)
def test_zero_and_omission_are_still_accepted_on_a_product(column):
    """ZERO IS A REAL ANSWER AND ABSENT IS ANOTHER ONE. A free sample has a selling price of 0, and a
    box nobody filled in is not a measurement of nothing."""
    assert getattr(ProductCreate(**{**_PRODUCT_BODY, column: Decimal("0")}), column) == 0
    assert getattr(ProductCreate(**_PRODUCT_BODY), column) is None


@pytest.mark.parametrize("column", TOOL_MEASURES)
def test_zero_and_omission_are_still_accepted_on_a_tool(column):
    assert getattr(ToolCreate(**{**_TOOL_BODY, column: Decimal("0")}), column) == 0
    assert getattr(ToolCreate(**_TOOL_BODY), column) is None


def test_the_bound_is_on_update_too_and_that_is_a_behaviour_change():
    """Stated as a test rather than as a comment because it is the one thing about this change that
    can break a save nobody edited: the web forms PATCH the WHOLE payload back
    (components/forms/ToolForm.tsx), so a row already holding a negative will 422 on its next edit —
    including an edit to a field beside it. The audit query for those rows is in the migration
    comment and in the PR; this pins the behaviour so the next reader knows it was chosen."""
    with pytest.raises(ValidationError):
        ToolUpdate(heightInches=Decimal("-0.5"))
    with pytest.raises(ValidationError):
        ProductUpdate(sellingPrice=Decimal("-1"))
    # And an ordinary correction to an unrelated column still passes, so the bound cannot be read as
    # "editing a measured record is now harder".
    assert ToolUpdate(remarks="Rehandled in 2019").remarks == "Rehandled in 2019"
