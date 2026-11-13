from __future__ import annotations

from app.questionnaire.sessions import details_conflict, field_diff

CURRENT = {
    "place": "Sanganer, Jaipur",
    "note": "First visit",
    "sessionDate": "2026-11-09",
    "crafts": ["Block printing", "Dabu"],
    "location": {"latitude": 26.8213, "longitude": 75.7849, "placeName": "Sanganer", "capturedAt": "2026-11-09T05:00:00+00:00"},
}


def test_the_loaded_version_is_never_a_conflict() -> None:
    assert details_conflict({"place": "Bagru, Jaipur"}, CURRENT, 4, 4) is None


def test_a_stale_version_whose_sent_fields_match_is_not_a_conflict() -> None:
    sent = {"place": "Sanganer, Jaipur", "sessionDate": "2026-11-09", "crafts": ["dabu", "block printing"]}
    assert details_conflict(sent, CURRENT, 3, 5) is None


def test_a_stale_version_names_only_the_sent_fields_that_differ() -> None:
    sent = {"place": "Bagru, Jaipur", "note": "First visit"}
    assert details_conflict(sent, CURRENT, 3, 5) == {"place": {"current": "Sanganer, Jaipur", "yours": "Bagru, Jaipur"}}


def test_a_location_matches_on_the_fields_sent_and_not_on_the_capture_time() -> None:
    same = {"location": {"latitude": 26.8213, "longitude": 75.7849, "capturedAt": "2026-11-10T00:00:00Z"}}
    moved = {"location": {"latitude": 26.8105, "longitude": 75.5466}}
    assert details_conflict(same, CURRENT, 1, 2) is None
    assert set(details_conflict(moved, CURRENT, 1, 2) or {}) == {"location"}


def test_clearing_a_location_conflicts_only_when_one_is_set() -> None:
    assert field_diff({"location": None}, CURRENT) == {"location": {"current": CURRENT["location"], "yours": None}}
    assert field_diff({"location": None}, {**CURRENT, "location": None}) == {}


def test_crafts_differ_when_the_set_differs() -> None:
    assert field_diff({"crafts": ["Dabu"]}, CURRENT) == {"crafts": {"current": CURRENT["crafts"], "yours": ["Dabu"]}}
