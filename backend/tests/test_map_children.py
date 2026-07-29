"""A point's dropdown: the finer breakdown of itself, one administrative level down.

At NATION level the whole country is one dot and therefore one row, and at STATE level a state is one
dot and one row — so "click a pin, the list scrolls to its row" has nowhere to go. Each point therefore
carries the level below it, and the list renders that as a disclosure.

The rules a refactor would break silently:

  * the children must be a REAL re-fold of the same rows, so their counts sum to the parent's;
  * their keys must be the keys those places have AT the child level, or drilling in would ask the API
    for a point that does not exist;
  * a single child is not a dropdown — a disclosure that only restates its own row teaches a reader to
    stop opening them;
  * DISTRICT has no children at all, because it is the finest unit an Indian address names.
"""

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
    """``_origin_points`` at ``level``, with its dropdown level wired exactly as the route wires it."""
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


# Three districts in two states, so a nation folds to two states and Rajasthan folds to two districts.
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


# --- The three levels ------------------------------------------------------------------------------


def test_the_nation_point_lists_the_states_inside_it():
    points, _unplaced, total = fold(AdminLevel.NATION, LOCATIONS, GROUPED)
    assert total == 10
    [nation] = [point for point in points.values() if point["key"] == "nation:india"]
    assert nation["total"] == 10
    labels = {child["label"]: child["total"] for child in nation["children"]}
    assert labels == {"Rajasthan": 8, "Gujarat": 2}
    # Busiest first, exactly as the drawn points are ordered.
    assert [child["label"] for child in nation["children"]] == ["Rajasthan", "Gujarat"]
    # The children's counts sum to their parent's, because they are the same rows re-folded.
    assert sum(child["total"] for child in nation["children"]) == nation["total"]


def test_the_state_points_list_the_districts_inside_them():
    points, _unplaced, _total = fold(AdminLevel.STATE, LOCATIONS, GROUPED)
    rajasthan = points["state:Rajasthan"]
    assert {child["label"]: child["total"] for child in rajasthan["children"]} == {
        "Jaipur": 5,
        "Jodhpur": 3,
    }
    # Gujarat holds one district, so it is NOT given a one-item dropdown.
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


# --- The keys are the ones the child level really uses --------------------------------------------


def test_a_childs_key_is_the_key_that_place_has_at_the_child_level():
    """This is what makes the dropdown navigable rather than decorative: the client hands the key back
    to ``/map/points?level=<childLevel>`` and gets the same pin the level toggle would have drawn."""
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
    """At DISTRICT level a group naming a state and no district keys as ``state:<name>`` — "in Rajasthan,
    district not stated". A state's dropdown therefore legitimately contains an entry with its own key,
    and dropping it would make the dropdown undercount the records inside the state."""
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


# --- The CAPTURE layer behaves the same way -------------------------------------------------------


def test_a_capture_pin_lists_the_tighter_clusters_inside_it():
    """Two venues ~180 km apart fold into one pin at NATION level (a five-degree cell) and into two at
    STATE level (one degree), so the nation pin's dropdown is those two venues.

    The coordinates are chosen to sit in the SAME 5-degree cell and DIFFERENT 1-degree cells — the whole
    point being tested — which is a stricter condition than "far apart": 22.3,87.3 and 26.9,75.8 are 1200
    km apart and land in two different five-degree cells, so they would never have folded at all.
    """
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
        # The cluster size is part of a capture key, so a child's key names the CHILD's radius — a key
        # that omitted it would open a 25 km box while the map showed a 110 km one.
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
