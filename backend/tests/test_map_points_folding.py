import pytest

from app.api.routes.map_points import _group_geography, _origin_points
from app.services.geography import (
    SOURCE_PLACE_TEXT,
    SOURCE_STATED_ADDRESS,
    SOURCE_SUBJECT_PIN,
    STATE_SEATS,
    AdminLevel,
    DistrictAnchors,
)


class FakeLocation:
    def __init__(self, state=None, district=None, pin=None, latitude=None, longitude=None):
        self.state = state
        self.district = district
        self.subjectLatitude = pin[0] if pin else None
        self.subjectLongitude = pin[1] if pin else None
        self.latitude = latitude
        self.longitude = longitude


def anchors() -> DistrictAnchors:
    seeded = DistrictAnchors()
    seeded.seed_from_atlas()
    return seeded


DEGREES = 0.25


def group(count: int, location_id: str | None = None, place: str | None = None) -> dict:
    row: dict = {"_count": {"_all": count}}
    if location_id is not None:
        row["locationId"] = location_id
    if place is not None:
        row["place"] = place
    return row



def test_a_stated_address_beats_the_place_text():
    location = FakeLocation(state="Gujarat", district="Kachchh")
    placed, source = _group_geography(location, "Bagru, Rajasthan", AdminLevel.DISTRICT, anchors(), DEGREES)
    assert placed.key == "district:Gujarat|Kachchh"
    assert source == "STATED"


def test_the_place_text_is_the_fallback_and_says_so():
    placed, source = _group_geography(None, "Bagru, Rajasthan", AdminLevel.DISTRICT, anchors(), DEGREES)
    assert placed.key == "district:Rajasthan|Jaipur"
    assert source == "ATLAS"
    assert placed.source == SOURCE_PLACE_TEXT
    assert placed.precision == "TOWN"


def test_a_location_with_no_state_falls_through_to_the_place_text():
    location = FakeLocation(latitude=22.3, longitude=87.3)
    placed, source = _group_geography(location, "Bareilly", AdminLevel.DISTRICT, anchors(), DEGREES)
    assert source == "ATLAS"
    assert placed.state == "Uttar Pradesh"


def test_neither_source_means_unplaced():
    assert _group_geography(None, "Somewhere nobody has heard of", AdminLevel.DISTRICT, anchors(), DEGREES) is None
    assert _group_geography(None, None, AdminLevel.DISTRICT, anchors(), DEGREES) is None
    assert _group_geography(FakeLocation(), "", AdminLevel.DISTRICT, anchors(), DEGREES) is None


def test_a_subject_pin_beats_the_district_anchor():
    location = FakeLocation(state="Rajasthan", district="Jaipur", pin=(26.5, 75.1))
    placed, _ = _group_geography(location, None, AdminLevel.DISTRICT, anchors(), DEGREES)
    assert (placed.latitude, placed.longitude) == (26.5, 75.1)
    assert placed.source == SOURCE_SUBJECT_PIN


def test_a_pin_with_no_administrative_name_is_still_placed():
    location = FakeLocation(pin=(26.8149, 75.5449))
    placed, source = _group_geography(location, None, AdminLevel.DISTRICT, anchors(), DEGREES)
    assert source == "PIN"
    assert placed.key == "pin:0.25:107_302"
    assert (placed.latitude, placed.longitude) == (26.8149, 75.5449)
    assert placed.precision == "SUBJECT_PIN"
    assert placed.source == SOURCE_SUBJECT_PIN
    assert placed.state is None and placed.district is None
    assert "no state or district recorded" in placed.region


def test_the_pin_rung_is_only_reached_after_the_named_ones():
    location = FakeLocation(state="Rajasthan", district="Jaipur", pin=(26.8149, 75.5449))
    placed, source = _group_geography(location, None, AdminLevel.DISTRICT, anchors(), DEGREES)
    assert source == "STATED"
    assert placed.key == "district:Rajasthan|Jaipur"

    named = FakeLocation(pin=(26.8149, 75.5449))
    placed, source = _group_geography(named, "Bareilly", AdminLevel.DISTRICT, anchors(), DEGREES)
    assert source == "ATLAS"


def test_half_a_pin_does_not_reach_the_pin_rung():
    location = FakeLocation()
    location.subjectLatitude = 26.8
    assert _group_geography(location, None, AdminLevel.DISTRICT, anchors(), DEGREES) is None


def test_the_pin_rung_clusters_at_the_radius_it_is_given():
    locations = {
        "a": FakeLocation(pin=(26.10, 75.10)),
        "b": FakeLocation(pin=(26.40, 75.40)),
    }
    grouped = [("artisans", [group(1, "a"), group(1, "b")])]
    fine, _, _ = _origin_points(grouped, locations, AdminLevel.DISTRICT, anchors(), 0.25)
    assert len(fine) == 2
    coarse, _, _ = _origin_points(grouped, locations, AdminLevel.DISTRICT, anchors(), 1.0)
    assert len(coarse) == 1


def test_a_nameless_pin_folds_into_the_nation_pin_at_nation_level():
    locations = {"a": FakeLocation(pin=(26.10, 75.10))}
    points, unplaced, total = _origin_points(
        [("artisans", [group(3, "a")])], locations, AdminLevel.NATION, anchors(), 5.0
    )
    assert list(points) == ["nation:india"]
    assert total == 3
    assert unplaced == []



def test_counts_and_totals_fold_per_bucket():
    locations = {"l1": FakeLocation(state="Rajasthan", district="Jaipur")}
    points, unplaced, placed_total = _origin_points(
        [
            ("artisans", [group(3, "l1", "Bagru")]),
            ("products", [group(4, "l1", "Bagru")]),
        ],
        locations,
        AdminLevel.DISTRICT,
        anchors(),
        DEGREES,
    )
    assert unplaced == []
    assert placed_total == 7
    point = points["district:Rajasthan|Jaipur"]
    assert point["total"] == 7
    assert point["counts"]["artisans"] == 3
    assert point["counts"]["products"] == 4
    assert set(point["counts"]) == {"artisans", "workshops", "products", "tools", "media"}


def test_the_position_is_the_weighted_mean_of_what_folded_in():
    locations = {
        "a": FakeLocation(state="Rajasthan", district="Jaipur", pin=(26.0, 75.0)),
        "b": FakeLocation(state="Rajasthan", district="Jaipur", pin=(29.0, 75.0)),
    }
    points, _, _ = _origin_points(
        [("artisans", [group(2, "a"), group(1, "b")])],
        locations,
        AdminLevel.DISTRICT,
        anchors(),
        DEGREES,
    )
    point = points["district:Rajasthan|Jaipur"]
    assert point["latitude"] == pytest.approx((26.0 * 2 + 29.0) / 3)


def test_grouping_to_a_district_keeps_the_finer_names():
    locations: dict = {}
    points, _, _ = _origin_points(
        [("artisans", [group(1, None, "Bagru"), group(1, None, "Sanganer")])],
        locations,
        AdminLevel.DISTRICT,
        anchors(),
        DEGREES,
    )
    point = points["district:Rajasthan|Jaipur"]
    assert point["total"] == 2
    assert set(point["places"]) == {"Bagru", "Sanganer"}
    assert point["fromPlaceText"] == 2


def test_pinned_records_are_counted_separately_from_looked_up_ones():
    locations = {
        "pinned": FakeLocation(state="Rajasthan", district="Jaipur", pin=(26.0, 75.0)),
        "stated": FakeLocation(state="Rajasthan", district="Jaipur"),
    }
    points, _, _ = _origin_points(
        [("artisans", [group(2, "pinned"), group(5, "stated")])],
        locations,
        AdminLevel.DISTRICT,
        anchors(),
        DEGREES,
    )
    point = points["district:Rajasthan|Jaipur"]
    assert point["total"] == 7
    assert point["pinnedRecords"] == 2


def test_an_unplaceable_group_is_reported_not_dropped():
    points, unplaced, placed_total = _origin_points(
        [("artisans", [group(5, None, "�"), group(3, None, None)])],
        {},
        AdminLevel.DISTRICT,
        anchors(),
        DEGREES,
    )
    assert points == {}
    assert placed_total == 0
    assert sum(entry["total"] for entry in unplaced) == 8
    assert any(entry["label"] == "No place recorded" for entry in unplaced)


def test_unplaced_is_ordered_busiest_first():
    _, unplaced, _ = _origin_points(
        [("artisans", [group(1, None, "aaa"), group(9, None, "bbb")])],
        {},
        AdminLevel.DISTRICT,
        anchors(),
        DEGREES,
    )
    assert [entry["total"] for entry in unplaced] == [9, 1]


def test_a_zero_count_group_is_ignored_entirely():
    points, unplaced, _ = _origin_points(
        [("artisans", [group(0, None, "Bagru")])], {}, AdminLevel.DISTRICT, anchors(), DEGREES
    )
    assert points == {}
    assert unplaced == []


def test_media_groups_carry_no_place_and_still_fold():
    locations = {"l1": FakeLocation(state="West Bengal", district="Paschim Medinipur")}
    points, unplaced, _ = _origin_points(
        [("media", [group(6, "l1")])], locations, AdminLevel.DISTRICT, anchors(), DEGREES
    )
    assert unplaced == []
    assert points["district:West Bengal|Paschim Medinipur"]["counts"]["media"] == 6



def test_the_state_level_folds_two_districts_into_one_point():
    locations = {
        "a": FakeLocation(state="Rajasthan", district="Jaipur"),
        "b": FakeLocation(state="Rajasthan", district="Barmer"),
    }
    points, _, total = _origin_points(
        [("artisans", [group(2, "a"), group(3, "b")])], locations, AdminLevel.STATE, anchors(), DEGREES
    )
    assert list(points) == ["state:Rajasthan"]
    assert points["state:Rajasthan"]["total"] == 5
    assert total == 5


def test_the_nation_level_folds_two_states_into_one_point():
    locations = {
        "a": FakeLocation(state="Rajasthan", district="Jaipur"),
        "b": FakeLocation(state="Gujarat", district="Kachchh"),
    }
    points, _, _ = _origin_points(
        [("artisans", [group(2, "a"), group(3, "b")])], locations, AdminLevel.NATION, anchors(), DEGREES
    )
    assert list(points) == ["nation:india"]
    assert points["nation:india"]["total"] == 5
    expected = (STATE_SEATS["Rajasthan"][1] * 2 + STATE_SEATS["Gujarat"][1] * 3) / 5
    assert points["nation:india"]["latitude"] == pytest.approx(expected)


def test_the_level_never_changes_the_total():
    locations = {
        "a": FakeLocation(state="Rajasthan", district="Jaipur"),
        "b": FakeLocation(state="Gujarat", district="Kachchh"),
    }
    grouped = [("artisans", [group(2, "a"), group(3, "b")])]
    totals = {
        level: _origin_points(grouped, locations, level, anchors(), DEGREES)[2] for level in AdminLevel
    }
    assert set(totals.values()) == {5}


def test_an_unanchored_district_is_drawn_at_its_state_seat():
    locations = {"a": FakeLocation(state="Kerala", district="Wayanad")}
    points, _, _ = _origin_points(
        [("artisans", [group(1, "a")])], locations, AdminLevel.DISTRICT, anchors(), DEGREES
    )
    point = points["district:Kerala|Wayanad"]
    assert (point["latitude"], point["longitude"]) == (
        STATE_SEATS["Kerala"][1],
        STATE_SEATS["Kerala"][2],
    )
    assert point["precision"] == "STATE"
    assert point["source"] == SOURCE_STATED_ADDRESS
    assert point["pinnedRecords"] == 0
