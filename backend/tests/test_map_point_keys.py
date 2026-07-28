"""Map point keys survive the round trip through a URL, and parse back to what built them.

A point key is the ONE handle a client has on a pin: it is what comes back to ask "which records are
here". Two properties therefore have to hold, and neither is obvious from reading either side alone.

  1. The key survives being a URL PATH SEGMENT. The keys now contain ':', '|' and — for a state or
     district — SPACES ("state:Jammu and Kashmir", "district:Dadra and Nagar Haveli and Daman and
     Diu|Daman"). A key that came back mangled would not be a visible error; it would be a 422 or an
     empty record list under a pin showing a count, which reads as "there is nothing here".
  2. The key parses back to the administrative unit that produced it, through the STRICT resolvers.
     ``parse_stated_key`` is what turns a click into a query, so a key it cannot invert is a pin that
     cannot be opened.
"""

from urllib.parse import quote

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.services.geography import (
    AdminLevel,
    DistrictAnchors,
    parse_stated_key,
    stated_point,
)

# The four shapes a key can take, plus the two that exercise the characters most likely to break
# routing: a space-bearing state name, and the longest union territory name in the register.
KEYS = [
    "nation:india",
    "state:Rajasthan",
    "state:Jammu and Kashmir",
    "district:Rajasthan|Jaipur",
    "district:Dadra and Nagar Haveli and Daman and Diu|Daman",
    "capture:0.25:89_-1",
    "capture:1:26_75",
    "capture:5:-1_-1",
]


@pytest.fixture(scope="module")
def echo_client() -> TestClient:
    """A one-route app declared EXACTLY as ``map_points`` declares it, so this tests the real shape.

    Standing up the real route would need a database; the thing under test here is the path converter
    and the percent-decoding around it, which is a property of the declaration, not of the handler.
    """
    app = FastAPI()

    @app.get("/map/points/{point_key:path}/records")
    def echo(point_key: str) -> dict[str, str]:
        return {"key": point_key}

    return TestClient(app)


@pytest.mark.parametrize("key", KEYS)
def test_a_point_key_survives_being_a_url_path_segment(echo_client: TestClient, key: str):
    # `quote(safe="")` is what `encodeURIComponent` does on the client.
    response = echo_client.get(f"/map/points/{quote(key, safe='')}/records")
    assert response.status_code == 200
    assert response.json()["key"] == key


def test_every_stated_key_the_map_can_emit_parses_back():
    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
    # A state whose name has spaces, and a district inside it, at each level — the combination most
    # likely to be broken by a naive split.
    for level, expected in (
        (AdminLevel.DISTRICT, ("district", "Jammu and Kashmir", "Jammu")),
        (AdminLevel.STATE, ("state", "Jammu and Kashmir", None)),
        (AdminLevel.NATION, ("nation", None, None)),
    ):
        point = stated_point(
            level=level,
            state="Jammu and Kashmir",
            district="Jammu",
            subject_latitude=32.7,
            subject_longitude=74.8,
            anchors=anchors,
        )
        assert parse_stated_key(point.key) == expected


def test_a_capture_key_is_never_mistaken_for_a_stated_one():
    # `point_records` checks the capture prefix FIRST, but the stated parser must also refuse these on
    # its own — otherwise reordering those two branches would silently route a capture pin into the
    # administrative narrowing and return the wrong records rather than an error.
    for key in ("capture:0.25:89_-1", "capture:1:26_75"):
        assert parse_stated_key(key) is None


def test_the_cluster_size_is_part_of_the_capture_key():
    """Two levels must not produce the same capture key for the same place.

    The key is inverted into a coordinate WINDOW, and the window's size comes from the key. If the size
    were omitted, the same cell integers would mean a 28 km box at district level and a 110 km box at
    state level — so a panel opened after moving the toggle would list the records of a differently
    sized area than the pin it was opened from.
    """
    from app.api.routes.map_points import _CLUSTER_DEGREES_BY_LEVEL, _capture_key

    keys = {
        _capture_key(26.9124, 75.7873, degrees) for degrees in _CLUSTER_DEGREES_BY_LEVEL.values()
    }
    assert len(keys) == len(_CLUSTER_DEGREES_BY_LEVEL), "two levels produced the same capture key"
    # And the size is written in a form that parses back exactly.
    for key in keys:
        size = key.split(":")[1]
        assert float(size) in set(_CLUSTER_DEGREES_BY_LEVEL.values())
