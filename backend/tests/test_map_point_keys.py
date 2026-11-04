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
    app = FastAPI()

    @app.get("/map/points/{point_key:path}/records")
    def echo(point_key: str) -> dict[str, str]:
        return {"key": point_key}

    return TestClient(app)


@pytest.mark.parametrize("key", KEYS)
def test_a_point_key_survives_being_a_url_path_segment(echo_client: TestClient, key: str):
    response = echo_client.get(f"/map/points/{quote(key, safe='')}/records")
    assert response.status_code == 200
    assert response.json()["key"] == key


def test_every_stated_key_the_map_can_emit_parses_back():
    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
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
    for key in ("capture:0.25:89_-1", "capture:1:26_75"):
        assert parse_stated_key(key) is None


def test_the_cluster_size_is_part_of_the_capture_key():
    from app.api.routes.map_points import _CLUSTER_DEGREES_BY_LEVEL, _capture_key

    keys = {
        _capture_key(26.9124, 75.7873, degrees) for degrees in _CLUSTER_DEGREES_BY_LEVEL.values()
    }
    assert len(keys) == len(_CLUSTER_DEGREES_BY_LEVEL), "two levels produced the same capture key"
    for key in keys:
        size = key.split(":")[1]
        assert float(size) in set(_CLUSTER_DEGREES_BY_LEVEL.values())
