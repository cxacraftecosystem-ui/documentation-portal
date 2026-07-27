"""The two halves of a location, and the line between what may be demanded and what may not.

These tests exist because the rules they cover are the fix for a data finding rather than a feature,
and a rule that came from a finding is exactly the kind that gets relaxed later by somebody who no
longer remembers why it was tight. The finding: all fifteen artisans on the live database that carry
a location sit in Kharagpur, West Bengal, while the places their researchers typed are in Rajasthan,
Gujarat, Uttarakhand and Andhra Pradesh. The coordinates are genuine GPS fixes of the desk the
record was typed at; the schema simply had nowhere to put the artisan's own address, so the desk's
coordinates were read as one.

So the two assertions that matter here are opposites, and both have to hold at once: a CREATE may
not proceed without a stated state and district, and an UPDATE may proceed without them for ever.
"""

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

# Bagru, Rajasthan — the block-printing cluster three of the fifteen records name in prose.
BAGRU = {"latitude": 26.8137, "longitude": 75.545}
# Kharagpur, West Bengal — where all fifteen of them actually are.
KHARAGPUR = {"latitude": 22.3149, "longitude": 87.3105}


class _Create:
    """The shape ``require_location`` is handed: a create model with a ``location`` attribute."""

    def __init__(self, location: LocationInput | None) -> None:
        self.location = location


class _Update:
    """The shape ``forbid_clearing_location`` is handed, ``model_fields_set`` and all."""

    def __init__(self, **fields: object) -> None:
        self.location = fields.get("location")
        self.model_fields_set = set(fields)


def _location(**overrides: object) -> LocationInput:
    return LocationInput(**{**BAGRU, "state": "Rajasthan", "district": "Jaipur", **overrides})


def test_a_create_needs_a_state_and_a_district() -> None:
    """The rule the finding bought. A coordinate alone is a record about a desk."""
    with pytest.raises(ValueError) as caught:
        require_location(_Create(LocationInput(**KHARAGPUR)))
    assert str(caught.value) == STATED_ADDRESS_REQUIRED_MESSAGE


def test_a_create_still_needs_a_location_at_all() -> None:
    """The older rule is untouched, and still reported in its own words."""
    with pytest.raises(ValueError) as caught:
        require_location(_Create(None))
    assert str(caught.value) == LOCATION_REQUIRED_MESSAGE


def test_a_state_without_a_district_is_not_half_an_answer() -> None:
    """A district is what makes the state useful; "Rajasthan" alone locates nobody."""
    with pytest.raises(ValueError):
        require_location(_Create(LocationInput(**BAGRU, state="Rajasthan")))


def test_a_complete_create_passes() -> None:
    model = _Create(_location())
    assert require_location(model) is model


def test_the_pincode_is_still_not_demanded() -> None:
    """Deliberate, and the whole reason district is requirable and pincode is not.

    A district can be answered from memory of where you are standing, off a list the form already
    holds. A pincode cannot, and 57 of 60 sampled rural Indian points return no postcode to a
    geocoder either — so requiring it would make the create unsatisfiable in exactly the conditions
    fieldwork happens in.
    """
    assert require_location(_Create(_location(pincode=None))) is not None


def test_an_update_may_omit_the_stated_address_for_ever() -> None:
    """THE LEGACY ROWS, and the reason the two validators disagree on purpose.

    All fifteen stored locations have a NULL state and a NULL district. ``attach_location`` writes a
    brand new Location row on every save, so an edit re-sends whatever the form is holding — which
    for those records is a coordinate and two empty dropdowns. If this validator demanded the pair,
    a researcher who opened one of them to correct a phone number could not save the correction
    until they had also decided, possibly from a desk 1,500 km away, which district the artisan
    lives in. Nothing is backfilled and nothing is migrated; the form flags the gap instead.
    """
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
    """A real district of the wrong state is the mistake worth catching, and it names the right one."""
    with pytest.raises(ValidationError) as caught:
        LocationInput(**BAGRU, state="Uttarakhand", district="Jaipur")
    assert "Rajasthan" in str(caught.value)


def test_a_geocoder_spelling_of_the_district_resolves() -> None:
    """MapTiler answers "Jammu district" lowercase and "Akola District" capitalised; both are real."""
    assert LocationInput(latitude=32.7, longitude=74.87, state="Jammu and Kashmir", district="Jammu district").district == "Jammu"


def test_a_state_alias_still_scopes_its_district() -> None:
    """An old spelling of the state must not cost the district its scope.

    "Kutch" is what MapTiler returns for the Gujarat coast and what one of the fifteen records
    types; the canonical district is Kachchh. Resolving it requires the state to have resolved
    first, which is why the district is a MODEL validator and not a field one.
    """
    resolved = LocationInput(latitude=23.24, longitude=69.67, state="gujarat", district="Kutch")
    assert (resolved.state, resolved.district) == ("Gujarat", "Kachchh")


def test_the_subject_pin_is_a_pair_or_nothing() -> None:
    """Half a pin is 111 km of meridian stored in the column that exists to be precise."""
    with pytest.raises(ValidationError):
        LocationInput(**BAGRU, state="Rajasthan", district="Jaipur", subjectLatitude=26.8)


def test_the_subject_pin_is_never_derived_from_the_fix() -> None:
    """The separation, asserted rather than assumed.

    A payload that carries a device fix and no pin comes out of validation with no pin. Nothing in
    this schema copies one coordinate pair into the other, which is the single behaviour that would
    put the finding back.
    """
    resolved = _location()
    assert resolved.subjectLatitude is None and resolved.subjectLongitude is None
    assert (resolved.latitude, resolved.longitude) == (BAGRU["latitude"], BAGRU["longitude"])


def test_the_subject_pin_and_the_fix_can_be_1500_km_apart() -> None:
    """The case the whole model exists for: documented in Bagru, typed up in Kharagpur."""
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
