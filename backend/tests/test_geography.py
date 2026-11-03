import pytest

from app.services.address import INDIAN_STATES_AND_UNION_TERRITORIES
from app.services.geography import (
    PRECISION_DISTRICT,
    PRECISION_STATE,
    PRECISION_SUBJECT_PIN,
    SOURCE_PLACE_TEXT,
    SOURCE_STATED_ADDRESS,
    SOURCE_SUBJECT_PIN,
    STATE_SEATS,
    AdminLevel,
    DistrictAnchors,
    address_completeness,
    district_key,
    mean_point,
    parse_stated_key,
    resolve_admin_level,
    state_seat,
    stated_point,
)
from app.services.place_atlas import atlas_district_anchors


class FakeLocation:
    def __init__(
        self,
        state=None,
        district=None,
        subject_latitude=None,
        subject_longitude=None,
        pincode=None,
    ):
        self.state = state
        self.district = district
        self.subjectLatitude = subject_latitude
        self.subjectLongitude = subject_longitude
        self.pincode = pincode


def anchors_with(*rows) -> DistrictAnchors:
    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
    anchors.learn(rows)
    return anchors



def test_every_state_the_validator_accepts_has_a_seat():
    missing = [state for state in INDIAN_STATES_AND_UNION_TERRITORIES if state not in STATE_SEATS]
    assert missing == [], f"no map seat for: {missing}"


def test_the_seat_table_invents_no_states():
    extra = [state for state in STATE_SEATS if state not in INDIAN_STATES_AND_UNION_TERRITORIES]
    assert extra == [], f"seat for a state the validator rejects: {extra}"


def test_every_seat_is_inside_india():
    for state, (name, latitude, longitude) in STATE_SEATS.items():
        assert 6.0 <= latitude <= 38.0, f"{state} seat {name} latitude {latitude}"
        assert 68.0 <= longitude <= 98.0, f"{state} seat {name} longitude {longitude}"


def test_seats_are_reachable_through_any_spelling_the_validator_accepts():
    assert state_seat("gujarat")[0] == "Gandhinagar"
    assert state_seat("Orissa")[0] == "Bhubaneswar"
    assert state_seat("  West Bengal  ")[0] == "Kolkata"
    assert state_seat("Atlantis") is None
    assert state_seat(None) is None


def test_haryana_punjab_and_chandigarh_share_one_seat_deliberately():
    assert STATE_SEATS["Haryana"] == STATE_SEATS["Punjab"] == STATE_SEATS["Chandigarh"]



def test_every_atlas_anchor_names_a_district_the_validator_knows():
    from app.services.address import normalize_district, normalize_state

    for state, district, latitude, longitude in atlas_district_anchors():
        canonical_state = normalize_state(state)
        assert canonical_state, f"atlas state not canonical: {state}"
        assert normalize_district(canonical_state, district), (
            f"atlas district {district!r} is not a district of {canonical_state}"
        )
        assert 6.0 <= latitude <= 38.0 and 68.0 <= longitude <= 98.0


def test_the_atlas_seeds_the_districts_it_names():
    anchors = DistrictAnchors()
    assert anchors.anchor("Rajasthan", "Jaipur") is None
    anchors.seed_from_atlas()
    seeded = anchors.anchor("Rajasthan", "Jaipur")
    assert seeded is not None
    assert seeded[0] == pytest.approx((26.8149 + 26.8168) / 2, abs=1e-4)



def test_a_subject_pin_is_used_as_the_position_and_reported_as_measured():
    point = stated_point(
        level=AdminLevel.DISTRICT,
        state="Rajasthan",
        district="Jaipur",
        subject_latitude=26.5,
        subject_longitude=75.1,
        anchors=anchors_with(),
    )
    assert (point.latitude, point.longitude) == (26.5, 75.1)
    assert point.precision == PRECISION_SUBJECT_PIN
    assert point.source == SOURCE_SUBJECT_PIN
    assert point.key == "district:Rajasthan|Jaipur"


def test_a_district_with_no_pin_falls_back_to_its_learned_anchor():
    anchors = anchors_with(
        FakeLocation(state="Rajasthan", district="Jaipur", subject_latitude=27.0, subject_longitude=75.0)
    )
    point = stated_point(
        level=AdminLevel.DISTRICT,
        state="Rajasthan",
        district="Jaipur",
        subject_latitude=None,
        subject_longitude=None,
        anchors=anchors,
    )
    assert point.precision == PRECISION_DISTRICT
    assert point.source == SOURCE_STATED_ADDRESS
    assert point.latitude == pytest.approx((26.8149 + 26.8168 + 27.0) / 3, abs=1e-4)


def test_an_unanchored_district_is_drawn_at_the_state_seat_and_says_so():
    point = stated_point(
        level=AdminLevel.DISTRICT,
        state="Kerala",
        district="Wayanad",
        subject_latitude=None,
        subject_longitude=None,
        anchors=anchors_with(),
    )
    assert point.precision == PRECISION_STATE, "a district with no anchor must not claim district precision"
    assert (point.latitude, point.longitude) == (STATE_SEATS["Kerala"][1], STATE_SEATS["Kerala"][2])
    assert "Thiruvananthapuram" in point.region
    assert "Wayanad" in point.region


def test_a_state_with_no_district_still_places():
    point = stated_point(
        level=AdminLevel.DISTRICT,
        state="Assam",
        district=None,
        subject_latitude=None,
        subject_longitude=None,
        anchors=anchors_with(),
    )
    assert point.key == "state:Assam"
    assert point.precision == PRECISION_STATE
    assert "no district stated" in point.region


def test_no_state_means_unplaced_rather_than_a_guess():
    assert (
        stated_point(
            level=AdminLevel.DISTRICT,
            state=None,
            district="Bilaspur",
            subject_latitude=22.0,
            subject_longitude=82.0,
            anchors=anchors_with(),
        )
        is None
    )


def test_the_atlas_path_keeps_its_own_precision():
    point = stated_point(
        level=AdminLevel.DISTRICT,
        state="Rajasthan",
        district="Jaipur",
        subject_latitude=26.8149,
        subject_longitude=75.5449,
        anchors=anchors_with(),
        pin_precision="TOWN",
        pin_source=SOURCE_PLACE_TEXT,
    )
    assert point.precision == "TOWN"
    assert point.source == SOURCE_PLACE_TEXT



def test_the_state_level_drops_the_district_from_the_key():
    point = stated_point(
        level=AdminLevel.STATE,
        state="Rajasthan",
        district="Jaipur",
        subject_latitude=26.5,
        subject_longitude=75.1,
        anchors=anchors_with(),
    )
    assert point.key == "state:Rajasthan"
    assert point.district is None
    assert (point.latitude, point.longitude) == (26.5, 75.1)


def test_the_state_level_falls_back_to_the_seat_without_a_coordinate():
    point = stated_point(
        level=AdminLevel.STATE,
        state="Bihar",
        district="Patna",
        subject_latitude=None,
        subject_longitude=None,
        anchors=anchors_with(),
    )
    assert (point.latitude, point.longitude) == (STATE_SEATS["Bihar"][1], STATE_SEATS["Bihar"][2])
    assert point.precision == PRECISION_STATE


def test_the_nation_level_folds_everything_into_one_key():
    for state, district in (("Rajasthan", "Jaipur"), ("Kerala", "Wayanad")):
        point = stated_point(
            level=AdminLevel.NATION,
            state=state,
            district=district,
            subject_latitude=None,
            subject_longitude=None,
            anchors=anchors_with(),
        )
        assert point.key == "nation:india"


def test_the_nation_level_needs_something_to_stand_on():
    assert (
        stated_point(
            level=AdminLevel.NATION,
            state=None,
            district=None,
            subject_latitude=None,
            subject_longitude=None,
            anchors=anchors_with(),
        )
        is None
    )


def test_the_level_parameter_defaults_rather_than_failing():
    assert resolve_admin_level(None) is AdminLevel.DISTRICT
    assert resolve_admin_level("") is AdminLevel.DISTRICT
    assert resolve_admin_level("nonsense") is AdminLevel.DISTRICT
    assert resolve_admin_level("state") is AdminLevel.STATE
    assert resolve_admin_level("  NATION ") is AdminLevel.NATION



def test_the_state_is_part_of_every_district_key():
    assert district_key("Chhattisgarh", "Bilaspur") != district_key("Himachal Pradesh", "Bilaspur")


def test_every_stated_key_round_trips():
    for level, state, district, expected in (
        (AdminLevel.DISTRICT, "Gujarat", "Kachchh", ("district", "Gujarat", "Kachchh")),
        (AdminLevel.STATE, "Gujarat", "Kachchh", ("state", "Gujarat", None)),
        (AdminLevel.NATION, "Gujarat", "Kachchh", ("nation", None, None)),
    ):
        point = stated_point(
            level=level,
            state=state,
            district=district,
            subject_latitude=23.0,
            subject_longitude=70.0,
            anchors=anchors_with(),
        )
        assert parse_stated_key(point.key) == expected


def test_a_key_that_is_not_a_stated_key_parses_to_nothing():
    for key in ("capture:0.25:89_-1", "origin:bagru", "district:Nowhere|Nothing", "district:Gujarat", ""):
        assert parse_stated_key(key) is None, key



def test_the_weighted_mean_follows_the_record_counts():
    assert mean_point([(10.0, 20.0, 3), (20.0, 40.0, 1)]) == pytest.approx((12.5, 25.0))
    assert mean_point([]) is None
    assert mean_point([(10.0, 20.0, 0)]) is None


def test_address_completeness_counts_what_a_researcher_can_act_on():
    counts = address_completeness(
        [
            FakeLocation(state="Rajasthan", district="Jaipur", pincode="303007",
                         subject_latitude=26.8, subject_longitude=75.5),
            FakeLocation(state="Rajasthan"),
            FakeLocation(),
            FakeLocation(state="Rajasthan", district="Kachchh"),
        ]
    )
    assert counts == {
        "locations": 4,
        "withState": 3,
        "withDistrict": 1,
        "withPincode": 1,
        "withSubjectPin": 1,
    }
