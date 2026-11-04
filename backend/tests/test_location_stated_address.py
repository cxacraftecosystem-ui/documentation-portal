from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas.common import (
    LOCATION_NOT_CLEARABLE_MESSAGE,
    LOCATION_REQUIRED_MESSAGE,
    STATED_ADDRESS_REQUIRED_MESSAGE,
    LocationInput,
    forbid_clearing_location,
    require_location,
)

BAGRU = {"latitude": 26.8137, "longitude": 75.545}
KHARAGPUR = {"latitude": 22.3149, "longitude": 87.3105}


class _Create:
    def __init__(self, location: LocationInput | None) -> None:
        self.location = location


class _Update:
    def __init__(self, **fields: object) -> None:
        self.location = fields.get("location")
        self.model_fields_set = set(fields)


def _location(**overrides: object) -> LocationInput:
    return LocationInput(**{**BAGRU, "state": "Rajasthan", "district": "Jaipur", **overrides})


def test_a_create_needs_a_state_and_a_district() -> None:
    with pytest.raises(ValueError) as caught:
        require_location(_Create(LocationInput(**KHARAGPUR)))
    assert str(caught.value) == STATED_ADDRESS_REQUIRED_MESSAGE


def test_a_create_still_needs_a_location_at_all() -> None:
    with pytest.raises(ValueError) as caught:
        require_location(_Create(None))
    assert str(caught.value) == LOCATION_REQUIRED_MESSAGE


def test_a_state_without_a_district_is_not_half_an_answer() -> None:
    with pytest.raises(ValueError):
        require_location(_Create(LocationInput(**BAGRU, state="Rajasthan")))


def test_a_complete_create_passes() -> None:
    model = _Create(_location())
    assert require_location(model) is model


def test_the_pincode_is_still_not_demanded() -> None:
    assert require_location(_Create(_location(pincode=None))) is not None


def test_an_update_may_omit_the_stated_address_for_ever() -> None:
    model = _Update(location=LocationInput(**KHARAGPUR), phone="9876543210")
    assert forbid_clearing_location(model) is model


def test_an_update_that_mentions_no_location_at_all_is_fine() -> None:
    model = _Update(phone="9876543210")
    assert forbid_clearing_location(model) is model


def test_an_update_still_may_not_empty_a_stored_location() -> None:
    with pytest.raises(ValueError) as caught:
        forbid_clearing_location(_Update(location=None))
    assert str(caught.value) == LOCATION_NOT_CLEARABLE_MESSAGE


def test_the_district_is_judged_inside_its_own_state() -> None:
    with pytest.raises(ValidationError) as caught:
        LocationInput(**BAGRU, state="Uttarakhand", district="Jaipur")
    assert "Rajasthan" in str(caught.value)


def test_a_geocoder_spelling_of_the_district_resolves() -> None:
    assert LocationInput(latitude=32.7, longitude=74.87, state="Jammu and Kashmir", district="Jammu district").district == "Jammu"


def test_a_state_alias_still_scopes_its_district() -> None:
    resolved = LocationInput(latitude=23.24, longitude=69.67, state="gujarat", district="Kutch")
    assert (resolved.state, resolved.district) == ("Gujarat", "Kachchh")


def test_the_subject_pin_is_a_pair_or_nothing() -> None:
    with pytest.raises(ValidationError):
        LocationInput(**BAGRU, state="Rajasthan", district="Jaipur", subjectLatitude=26.8)


def test_the_subject_pin_is_never_derived_from_the_fix() -> None:
    resolved = _location()
    assert resolved.subjectLatitude is None and resolved.subjectLongitude is None
    assert (resolved.latitude, resolved.longitude) == (BAGRU["latitude"], BAGRU["longitude"])


def test_the_subject_pin_and_the_fix_can_be_1500_km_apart() -> None:
    resolved = LocationInput(
        **KHARAGPUR,
        state="Rajasthan",
        district="Jaipur",
        village="Bagru",
        subjectLatitude=BAGRU["latitude"],
        subjectLongitude=BAGRU["longitude"],
    )
    assert resolved.state == "Rajasthan"
    assert resolved.latitude == KHARAGPUR["latitude"]
    assert resolved.subjectLatitude == BAGRU["latitude"]
