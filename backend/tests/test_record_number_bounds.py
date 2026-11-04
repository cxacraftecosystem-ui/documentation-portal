from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.schemas.records import ProductCreate, ProductUpdate, ToolCreate, ToolUpdate


def _bounded_measures(schema) -> list[str]:
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
    assert len(PRODUCT_MEASURES) == 5, PRODUCT_MEASURES
    assert len(TOOL_MEASURES) == 9, TOOL_MEASURES
    assert _bounded_measures(ProductUpdate) == PRODUCT_MEASURES
    assert _bounded_measures(ToolUpdate) == TOOL_MEASURES
    assert "heightInches" in TOOL_MEASURES


@pytest.mark.parametrize("column", PRODUCT_MEASURES)
def test_a_negative_product_measurement_is_refused_by_name(column):
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
    assert getattr(ProductCreate(**{**_PRODUCT_BODY, column: Decimal("0")}), column) == 0
    assert getattr(ProductCreate(**_PRODUCT_BODY), column) is None


@pytest.mark.parametrize("column", TOOL_MEASURES)
def test_zero_and_omission_are_still_accepted_on_a_tool(column):
    assert getattr(ToolCreate(**{**_TOOL_BODY, column: Decimal("0")}), column) == 0
    assert getattr(ToolCreate(**_TOOL_BODY), column) is None


def test_the_bound_is_on_update_too_and_that_is_a_behaviour_change():
    with pytest.raises(ValidationError):
        ToolUpdate(heightInches=Decimal("-0.5"))
    with pytest.raises(ValidationError):
        ProductUpdate(sellingPrice=Decimal("-1"))
    assert ToolUpdate(remarks="Rehandled in 2019").remarks == "Rehandled in 2019"
