import pytest

from app.api.routes.map_points import _atlas_place_matches, _pair_belongs
from app.services.geography import AdminLevel



def test_a_state_key_at_state_level_covers_every_district():
    for district in ("Jaipur", "Barmer", None):
        assert _pair_belongs("Rajasthan", district, "Rajasthan", None, "state", AdminLevel.STATE)


def test_a_state_key_at_district_level_covers_only_the_districtless():
    assert _pair_belongs("Rajasthan", None, "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert not _pair_belongs("Rajasthan", "Jaipur", "Rajasthan", None, "state", AdminLevel.DISTRICT)


def test_a_district_that_does_not_resolve_lands_on_the_state_pin():
    assert _pair_belongs("Rajasthan", "Kachchh", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert _pair_belongs("Rajasthan", "Nowhere", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert not _pair_belongs("Rajasthan", "Kachchh", "Rajasthan", "Jaipur", "district", AdminLevel.DISTRICT)



def test_a_legacy_spelling_in_the_column_still_matches_its_canonical_key():
    assert _pair_belongs("gujarat", "kutch", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)
    assert _pair_belongs("Orissa", None, "Odisha", None, "state", AdminLevel.STATE)
    assert _pair_belongs("  West Bengal  ", None, "West Bengal", None, "state", AdminLevel.STATE)
    assert _pair_belongs("Gujarat", "Kachchh District", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)


def test_a_state_that_is_not_a_state_matches_nothing():
    assert not _pair_belongs("Atlantis", None, "Gujarat", None, "state", AdminLevel.STATE)
    assert not _pair_belongs(None, "Kachchh", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)


def test_the_pair_is_matched_as_a_pair():
    assert not _pair_belongs("Gujarat", "Jaipur", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)
    assert not _pair_belongs("Rajasthan", "Kachchh", "Gujarat", "Kachchh", "district", AdminLevel.DISTRICT)



@pytest.mark.parametrize("level", list(AdminLevel))
def test_the_nation_pin_claims_every_resolvable_state(level: AdminLevel):
    for raw in ("Rajasthan", "gujarat", "Orissa"):
        assert _pair_belongs(raw, None, None, None, "nation", level)
    assert not _pair_belongs(None, None, None, None, "nation", level)
    assert not _pair_belongs("Atlantis", None, None, None, "nation", level)


def test_the_nation_pins_atlas_branch_is_not_dead():
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
    assert _atlas_place_matches("Bagru", "Rajasthan", None, "state", AdminLevel.STATE)


def test_a_state_pin_at_district_level_admits_only_places_with_no_district():
    assert not _atlas_place_matches("Bagru", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert _atlas_place_matches("Rajasthan", "Rajasthan", None, "state", AdminLevel.DISTRICT)
    assert _atlas_place_matches("Rajasthan", "Rajasthan", None, "state", AdminLevel.STATE)



def test_half_a_pin_is_not_a_pin_and_does_not_claim_subject_precision():
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
