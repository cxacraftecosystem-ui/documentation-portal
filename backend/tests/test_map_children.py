from app.api.routes.map_points import (
    _CHILD_LEVEL,
    _CLUSTER_DEGREES_BY_LEVEL,
    _capture_points,
    _origin_points,
)
from app.services.geography import AdminLevel, DistrictAnchors


class FakeLocation:
    def __init__(self, id=None, state=None, district=None, pin=None, latitude=None, longitude=None):
        self.id = id
        self.state = state
        self.district = district
        self.subjectLatitude = pin[0] if pin else None
        self.subjectLongitude = pin[1] if pin else None
        self.latitude = latitude
        self.longitude = longitude
        self.accuracy = None


def anchors() -> DistrictAnchors:
    seeded = DistrictAnchors()
    seeded.seed_from_atlas()
    return seeded


def group(count: int, location_id: str | None = None, place: str | None = None) -> dict:
    row: dict = {"_count": {"_all": count}}
    if location_id is not None:
        row["locationId"] = location_id
    if place is not None:
        row["place"] = place
    return row


DEGREES = {level: _CLUSTER_DEGREES_BY_LEVEL[level] for level in AdminLevel}


def fold(level: AdminLevel, locations: dict, grouped: list):
    child = _CHILD_LEVEL[level]
    points, unplaced, total = _origin_points(
        grouped,
        locations,
        level,
        anchors(),
        DEGREES[level],
        child,
        DEGREES[child] if child else 0.0,
    )
    return points, unplaced, total


LOCATIONS = {
    "l-bagru": FakeLocation(id="l-bagru", state="Rajasthan", district="Jaipur"),
    "l-jodhpur": FakeLocation(id="l-jodhpur", state="Rajasthan", district="Jodhpur"),
    "l-kutch": FakeLocation(id="l-kutch", state="Gujarat", district="Kachchh"),
}
GROUPED = [
    (
        "artisans",
        [group(5, "l-bagru"), group(3, "l-jodhpur"), group(2, "l-kutch")],
    )
]



def test_the_nation_point_lists_the_states_inside_it():
    points, _unplaced, total = fold(AdminLevel.NATION, LOCATIONS, GROUPED)
    assert total == 10
    [nation] = [point for point in points.values() if point["key"] == "nation:india"]
    assert nation["total"] == 10
    labels = {child["label"]: child["total"] for child in nation["children"]}
    assert labels == {"Rajasthan": 8, "Gujarat": 2}
    assert [child["label"] for child in nation["children"]] == ["Rajasthan", "Gujarat"]
    assert sum(child["total"] for child in nation["children"]) == nation["total"]


def test_the_state_points_list_the_districts_inside_them():
    points, _unplaced, _total = fold(AdminLevel.STATE, LOCATIONS, GROUPED)
    rajasthan = points["state:Rajasthan"]
    assert {child["label"]: child["total"] for child in rajasthan["children"]} == {
        "Jaipur": 5,
        "Jodhpur": 3,
    }
    assert points["state:Gujarat"]["children"] == []


def test_district_points_have_no_children_because_nothing_is_finer():
    points, _unplaced, _total = fold(AdminLevel.DISTRICT, LOCATIONS, GROUPED)
    assert set(points) == {
        "district:Rajasthan|Jaipur",
        "district:Rajasthan|Jodhpur",
        "district:Gujarat|Kachchh",
    }
    for point in points.values():
        assert point["children"] == []
        assert point["childrenTruncated"] is False



def test_a_childs_key_is_the_key_that_place_has_at_the_child_level():
    nation, _unplaced, _total = fold(AdminLevel.NATION, LOCATIONS, GROUPED)
    state_points, _u, _t = fold(AdminLevel.STATE, LOCATIONS, GROUPED)
    child_keys = {child["key"] for child in nation["nation:india"]["children"]}
    assert child_keys == set(state_points)
    for child in nation["nation:india"]["children"]:
        assert child["level"] == AdminLevel.STATE.value

    state_children = {
        child["key"] for child in state_points["state:Rajasthan"]["children"]
    }
    district_points, _u2, _t2 = fold(AdminLevel.DISTRICT, LOCATIONS, GROUPED)
    assert state_children <= set(district_points)


def test_a_state_with_no_district_stated_keeps_its_records_in_its_own_dropdown():
    locations = {
        "l-known": FakeLocation(id="l-known", state="Rajasthan", district="Jaipur"),
        "l-vague": FakeLocation(id="l-vague", state="Rajasthan"),
    }
    grouped = [("artisans", [group(4, "l-known"), group(6, "l-vague")])]
    points, _unplaced, _total = fold(AdminLevel.STATE, locations, grouped)
    rajasthan = points["state:Rajasthan"]
    assert rajasthan["total"] == 10
    assert sum(child["total"] for child in rajasthan["children"]) == 10
    keys = {child["key"]: child["total"] for child in rajasthan["children"]}
    assert keys == {"district:Rajasthan|Jaipur": 4, "state:Rajasthan": 6}



def test_a_capture_pin_lists_the_tighter_clusters_inside_it():
    rows = [
        FakeLocation(id="l1", latitude=22.3, longitude=87.3),
        FakeLocation(id="l2", latitude=22.4, longitude=87.4),
        FakeLocation(id="l3", latitude=23.6, longitude=88.6),
    ]
    per_location = {
        "l1": {"media": 4},
        "l2": {"media": 2},
        "l3": {"media": 3},
    }
    points, total = _capture_points(
        rows,
        per_location,
        DEGREES[AdminLevel.NATION],
        AdminLevel.STATE,
        DEGREES[AdminLevel.STATE],
    )
    assert total == 9
    assert len(points) == 1
    [pin] = points.values()
    assert pin["total"] == 9
    assert [child["total"] for child in pin["children"]] == [6, 3]
    assert sum(child["total"] for child in pin["children"]) == pin["total"]
    for child in pin["children"]:
        assert child["key"].startswith("capture:1:")
        assert child["level"] == AdminLevel.STATE.value


def test_a_capture_pin_holding_one_cluster_gets_no_dropdown():
    rows = [FakeLocation(id="l1", latitude=22.3, longitude=87.3)]
    points, _total = _capture_points(
        rows,
        {"l1": {"media": 4}},
        DEGREES[AdminLevel.NATION],
        AdminLevel.STATE,
        DEGREES[AdminLevel.STATE],
    )
    [pin] = points.values()
    assert pin["children"] == []


def test_no_child_level_means_no_children_and_no_extra_work():
    points, _total = _capture_points(
        [FakeLocation(id="l1", latitude=22.3, longitude=87.3)],
        {"l1": {"media": 4}},
        DEGREES[AdminLevel.DISTRICT],
    )
    [pin] = points.values()
    assert pin["children"] == []
    assert pin["childrenTruncated"] is False
