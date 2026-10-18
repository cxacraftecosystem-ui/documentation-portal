"""The coverage map's district shapes against the register, the depiction and the national outline.

Runs the same checks ``scripts/build_boundaries.py --shapes`` refuses to write without, over the
committed file, so a hand edit or a stale copy fails the suite. Pure Python; no shapely, no database.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib

import pytest

from app.services import coverage, lgd

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHAPES = ROOT / "frontend" / "public" / "boundaries" / "district-shapes.json"
MANIFEST = ROOT / "frontend" / "public" / "boundaries" / "manifest.json"


def _script():
    spec = importlib.util.spec_from_file_location("build_boundaries", ROOT / "scripts" / "build_boundaries.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def built():
    return _script(), json.loads(SHAPES.read_text(encoding="utf-8"))


def test_the_shapes_decode_and_key_by_register_codes(built):
    script, document = built
    assert document["scale"] == 1000
    reg = lgd.register()
    codes = [row["lgd"] for row in document["districts"]]
    assert len(codes) == len(set(codes)) > 700
    for row in document["districts"]:
        assert reg.district_row[row["lgd"]][0] == row["stateLgd"], row["lgd"]
        rings = [script.decode_ring(encoded) for encoded in row["rings"]]
        assert all(ring and ring[0] == ring[-1] for ring in rings)
        # A sliver can quantise to fewer than three corners; the web skips it, so one real ring is owed.
        assert any(len(ring) >= 4 for ring in rings), row["lgd"]


def test_depiction_outline_tolerance_and_centroids_all_pass(built):
    script, document = built
    assert script.verify_shapes(document["districts"]) == []


def test_the_depiction_points_named_in_the_design(built):
    script, _ = built
    outline = script.EdgeIndex(script.outline_rings())
    for lon, lat in ((74.31, 35.92), (79.60, 35.20), (73.47, 34.36)):
        assert outline.contains(lon, lat), (lon, lat)
    for lon, lat in ((91.17, 29.65), (67.01, 24.86)):  # Lhasa, Karachi
        assert not outline.contains(lon, lat)


def test_the_server_centroids_and_versions_match_the_shapes(built):
    _, document = built
    centres = coverage.centroids()
    assert centres == {row["lgd"]: tuple(row["centroid"]) for row in document["districts"]}
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    assert manifest["shapesVersion"] == coverage.shapes_version() == document["version"]
    assert len(manifest["shapesSourceSha256"]) == 64
