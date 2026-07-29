"""Which stored addresses belong to which pin — the half of the map that has to AGREE with the counts.

Every defect these guard against has the same shape and the same symptom: a pin shows a count, the
panel behind it lists a different number of records, and nothing on screen says which is right. That is
the worst failure mode this endpoint has, because both numbers look authoritative.

The functions here are pure over already-fetched values, which is why they are testable without a
database — the route hands them the reads.
"""

import pytest

from app.api.routes.map_points import _atlas_place_matches, _pair_belongs
from app.services.geography import AdminLevel


# --- The level decides what a `state:` key means --------------------------------------------


def test_a_state_key_at_state_level_covers_every_district():
    for district in ("Jaipur", "Barmer", None):
        assert _pair_belongs("Rajasthan", district, "Rajasthan", None, "state", AdminLevel.STATE)


def test_a_state_key_at_district_level_covers_only_the_districtless():
    """THE regression this file exists for.

    ``stated_point`` emits ``state:Rajasthan`` for two different reasons — "every district in Rajasthan"
    at STATE level, and "in Rajasthan with no district stated" at DISTRICT level — and the KEY IS
    IDENTICAL. Only the level tells them apart. Ignoring it made the Rajasthan pin at district level
    list every Rajasthan record, including all the ones the Jaipur and Barmer pins beside it were
    already accounting for, so the panel's count exceeded the pin's.
    """
    assert _pair_belongs("Rajasthan", None, "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert not _pair_belongs("Rajasthan", "Jaipur", "Rajasthan", None, "state", AdminLevel.DISTRICT)


def test_a_district_that_does_not_resolve_lands_on_the_state_pin():
    # Wider than "the column is null", and deliberately: a district ``stated_point`` could not use is
    # one it drew at the state, so the narrowing has to agree. "Kachchh" is real — of Gujarat — so
    # inside Rajasthan it resolves to nothing.
    assert _pair_belongs("Rajasthan", "Kachchh", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert _pair_belongs("Rajasthan", "Nowhere", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    # ...and it must NOT also be claimed by a district pin.
    assert not _pair_belongs("Rajasthan", "Kachchh", "Rajasthan", "Jaipur", "district", AdminLevel.DISTRICT)


# --- Canonical keys against raw columns -----------------------------------------------------


def test_a_legacy_spelling_in_the_column_still_matches_its_canonical_key():
    """``Location.state`` is nullable and predates the validator, so these rows exist.

    Comparing the canonical key to the raw column with plain equality counted such a row in a pin and
    then listed it nowhere — the pin's number and the panel's number disagreeing on exactly the legacy
    rows the address-derived map exists to bring in.
    """
    assert _pair_belongs("gujarat", "kutch", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)
    assert _pair_belongs("Orissa", None, "Odisha", None, "state", AdminLevel.STATE)
    assert _pair_belongs("  West Bengal  ", None, "West Bengal", None, "state", AdminLevel.STATE)
    assert _pair_belongs("Gujarat", "Kachchh District", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)


def test_a_state_that_is_not_a_state_matches_nothing():
    assert not _pair_belongs("Atlantis", None, "Gujarat", None, "state", AdminLevel.STATE)
    assert not _pair_belongs(None, "Kachchh", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)


def test_the_pair_is_matched_as_a_pair():
    # Two independent `in` lists would admit the cross product: a Jaipur record listed under Kachchh.
    assert not _pair_belongs("Gujarat", "Jaipur", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)
    assert not _pair_belongs("Rajasthan", "Kachchh", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)


# --- The nation pin -------------------------------------------------------------------------


@pytest.mark.parametrize("level", list(AdminLevel))
def test_the_nation_pin_claims_every_resolvable_state(level: AdminLevel):
    for raw in ("Rajasthan", "gujarat", "Orissa"):
        assert _pair_belongs(raw, None, None, None, "nation", level)
    assert not _pair_belongs(None, None, None, None, "nation", level)
    assert not _pair_belongs("Atlantis", None, None, None, "nation", level)


def test_the_nation_pins_atlas_branch_is_not_dead():
    """It was: ``place_state != state`` compared a real state name to ``None`` for every input.

    The consequence was silent and specific — the nation pin COUNTED the records placed from prose and
    then listed none of them, so a corpus whose locations are all legacy free text opened a pin reading
    "16 records" onto an empty panel.
    """
    for level in AdminLevel:
        assert _atlas_place_matches("Bagru", None, None, "nation", level)
        assert _atlas_place_matches("Kutch, Gujrat", None, None, "nation", level)
        assert not _atlas_place_matches("somewhere nobody has heard of", None, None, "nation", level)
        assert not _atlas_place_matches(None, None, None, "nation", level)


def test_the_atlas_branch_still_narrows_by_state_and_district():
    for level in AdminLevel:
        assert _atlas_place_matches("Bagru", "Rajasthan", "Jaipur", "district", level)
        assert not _atlas_place_matches("Bagru", "Rajasthan", "Barmer", "district", level)
        assert not _atlas_place_matches("Bagru", "Gujarat", None, "state", level)
    # A STATE pin means "every district in this state", so a town the atlas can place belongs to it.
    assert _atlas_place_matches("Bagru", "Rajasthan", None, "state", AdminLevel.STATE)


def test_a_state_pin_at_district_level_admits_only_places_with_no_district():
    """The atlas branch has to make the SAME fork ``_pair_belongs`` makes, or the two arms of one OR
    admit different rows.

    A ``state:X`` key means "every district in X" at STATE level and "in X, district not stated" at
    DISTRICT level. Without the level, the atlas branch admitted every atlas place in the state at BOTH —
    so a ``state:Jammu and Kashmir`` pin drawn at district detail (one product recorded as the bare state
    name) listed the eleven Jammu-DISTRICT rows as well, and the panel held more rows than the pin it was
    opened from counted. On the live corpus every ``Location`` carries a NULL state, which makes the atlas
    branch the only branch that fires, so this was the whole answer rather than half of it.
    """
    # "Bagru" resolves to Jaipur district, so at DISTRICT level it belongs to Jaipur's pin, not the state's.
    assert not _atlas_place_matches("Bagru", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    # A bare state name resolves with no district, so the state pin is the only pin it can belong to.
    assert _atlas_place_matches("Rajasthan", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert _atlas_place_matches("Rajasthan", "Rajasthan", None, "state", AdminLevel.STATE)


# --- Half a pin -----------------------------------------------------------------------------


def test_half_a_pin_is_not_a_pin_and_does_not_claim_subject_precision():
    """A ``subjectLatitude`` with a NULL ``subjectLongitude`` is a real row shape.

    At NATION level the point falls back to the state seat, and reporting that as ``SUBJECT_PIN`` would
    call a lookup a measurement — inflating ``pinnedRecords``, which is the number a reader uses to
    judge how much of the map is measured.
    """
    from app.services.geography import (
        PRECISION_STATE,
        SOURCE_STATED_ADDRESS,
        STATE_SEATS,
        DistrictAnchors,
        stated_point,
    )

    anchors = DistrictAnchors()
    anchors.seed_from_atlas()

    nation = stated_point(
        level=AdminLevel.NATION,
        state="Rajasthan",
        district=None,
        subject_latitude=26.8,
        subject_longitude=None,
        anchors=anchors,
    )
    assert (nation.latitude, nation.longitude) == (
        STATE_SEATS["Rajasthan"][1],
        STATE_SEATS["Rajasthan"][2],
    )
    assert nation.source == SOURCE_STATED_ADDRESS

    district = stated_point(
        level=AdminLevel.DISTRICT,
        state="Kerala",
        district="Wayanad",
        subject_latitude=11.6,
        subject_longitude=None,
        anchors=anchors,
    )
    assert district.precision == PRECISION_STATE
    assert district.source == SOURCE_STATED_ADDRESS
