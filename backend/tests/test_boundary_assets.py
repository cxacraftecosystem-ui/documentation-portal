"""The generated border assets, checked as data rather than trusted as build output.

These files are committed, not built in CI, so nothing else would notice if a regeneration shipped a
truncated payload or an encoding the clients cannot read. Every assertion here is about a failure that
would be INVISIBLE on screen — a border layer that silently draws nothing looks exactly like a level
with no borders, which is what the map looked like before the assets existed.

The decoder is reimplemented rather than imported because there is no Python decoder to import: the
readers live in TypeScript and Kotlin. Re-deriving it here from the format is the point — it is the
only thing in the repo that checks the two encoders agree with the two decoders.
"""

import json
import pathlib
import struct

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WEB = ROOT / "frontend" / "public" / "boundaries"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "res" / "raw"

ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
VALUES = {ch: i for i, ch in enumerate(ALPHABET)}
SCALE = 1000
OUTLINE_MAGIC = 0x494E4431

def outline_bounds() -> tuple[float, float, float, float]:
    """The bounds in the OUTLINE's header, read rather than retyped.

    Android derives a world space from whichever bounds an asset declares, so every asset drawn together
    must declare the same ones — that is what `test_every_asset_declares_the_outlines_bounds` checks.
    Hard-coding them here would let a regeneration change all three files and still pass.
    """
    blob = require(ANDROID / "india_outline.bin").read_bytes()
    magic, min_lon, max_lon, min_lat, max_lat = struct.unpack_from(">idddd", blob, 0)
    assert magic == OUTLINE_MAGIC
    return min_lon, max_lon, min_lat, max_lat


#: The extent the WEB payloads are quantised in, which is `projection.INDIA_BOUNDS` — the outline's
#: extent rounded to the three decimals that payload carries. Deliberately not the same numbers as
#: `outline_bounds()`; each renderer is internally consistent with the file it reads.
WEB_BOUNDS = (68.2060, 97.3940, 6.7560, 37.0820)

LEVELS = ("state", "district")


def require(path: pathlib.Path) -> pathlib.Path:
    if not path.exists():
        pytest.skip(f"{path.name} not built; run scripts/build_boundaries.py")
    return path


def decode_web(payload: str) -> list[list[tuple[float, float]]]:
    """The scheme `borderGeometry.decodeBorders` implements, re-derived from the format."""
    lines: list[list[tuple[float, float]]] = []
    index = x = y = 0

    def read_int() -> int:
        nonlocal index
        shift = result = 0
        while True:
            value = VALUES.get(payload[index], 0)
            index += 1
            result |= (value & 0x1F) << shift
            shift += 5
            if value < 0x20:
                break
        return ~(result >> 1) if result & 1 else result >> 1

    while index < len(payload):
        count = read_int()
        assert count > 0, "a non-positive point count means the payload is corrupt"
        line = []
        for _ in range(count):
            x += read_int()
            y += read_int()
            line.append((x / SCALE, y / SCALE))
        lines.append(line)
    return lines


def decode_android(blob: bytes) -> list[list[tuple[float, float]]]:
    """The `IND1` binary `IndiaOutline.loadIndiaGeometry` reads."""
    magic, min_lon, max_lon, min_lat, max_lat, count = struct.unpack_from(">idddd i", blob, 0)
    assert magic == OUTLINE_MAGIC
    offset = struct.calcsize(">idddd i")
    lon_step = (max_lon - min_lon) / 65535
    lat_step = (max_lat - min_lat) / 65535
    lines = []
    for _ in range(count):
        (n,) = struct.unpack_from(">i", blob, offset)
        offset += 4
        line = []
        for _ in range(n):
            qx, qy = struct.unpack_from(">HH", blob, offset)
            offset += 4
            line.append((min_lon + qx * lon_step, min_lat + qy * lat_step))
        lines.append(line)
    assert offset == len(blob), "trailing bytes: the binary is longer than its own record count"
    return lines


@pytest.fixture(scope="module")
def manifest() -> dict:
    return json.loads(require(WEB / "manifest.json").read_text(encoding="utf-8"))


# --- Both encodings round-trip ----------------------------------------------------------------


@pytest.mark.parametrize("level", LEVELS)
def test_the_web_payload_decodes_to_the_polyline_count_the_manifest_claims(level, manifest):
    lines = decode_web(require(WEB / f"{level}-borders.txt").read_text(encoding="utf-8"))
    assert len(lines) == manifest[f"{level}Polylines"]
    # A one-point "polyline" draws nothing but costs a subpath; the generator drops them.
    assert all(len(line) >= 2 for line in lines)


@pytest.mark.parametrize("level", LEVELS)
def test_the_android_binary_decodes_to_the_same_polylines(level, manifest):
    blob = require(ANDROID / f"{level}_borders.bin").read_bytes()
    lines = decode_android(blob)
    assert len(lines) == manifest[f"{level}Polylines"]
    assert len(blob) == manifest[f"{level}AndroidBytes"]


@pytest.mark.parametrize("level", LEVELS)
def test_the_two_encodings_describe_the_same_geometry(level):
    """THE test that stops the two platforms drifting.

    Different quantisations — 3 decimal places for the web, a uint16 grid for Android — so the check is
    agreement within the coarser of the two grids rather than equality. A transposed axis or a wrong
    bounds constant, the two failures that actually happen, blow this apart by degrees.
    """
    web = decode_web(require(WEB / f"{level}-borders.txt").read_text(encoding="utf-8"))
    android = decode_android(require(ANDROID / f"{level}_borders.bin").read_bytes())
    assert len(web) == len(android)
    lo_lon, hi_lon, lo_lat, hi_lat = outline_bounds()
    tolerance = max((hi_lon - lo_lon) / 65535, (hi_lat - lo_lat) / 65535, 1 / SCALE) * 2
    for a, b in zip(web, android):
        assert len(a) == len(b)
        for (ax, ay), (bx, by) in zip(a, b):
            assert abs(ax - bx) <= tolerance, f"{level}: longitude {ax} vs {bx}"
            assert abs(ay - by) <= tolerance, f"{level}: latitude {ay} vs {by}"


# --- The geometry is where it should be -------------------------------------------------------


@pytest.mark.parametrize("level", LEVELS)
def test_every_point_is_inside_the_national_bounds(level):
    # Quantisation to the outline's own grid makes this true by construction, so a failure here means
    # the bounds constant moved without the assets being rebuilt.
    lines = decode_web(require(WEB / f"{level}-borders.txt").read_text(encoding="utf-8"))
    for line in lines:
        for lon, lat in line:
            assert WEB_BOUNDS[0] - 0.05 <= lon <= WEB_BOUNDS[1] + 0.05, lon
            assert WEB_BOUNDS[2] - 0.05 <= lat <= WEB_BOUNDS[3] + 0.05, lat


@pytest.mark.parametrize("level", LEVELS)
def test_every_asset_declares_the_outlines_bounds(level):
    """The three Android assets must share ONE world space.

    Android un-quantises each asset with the bounds in its own header and then projects into a world
    space derived from them. Two assets with different bounds sit in two slightly different spaces, so
    a district border drifts from the coastline it is supposed to meet — by a fraction of a pixel here,
    but by however much a future divergence introduces, and silently.
    """
    blob = require(ANDROID / f"{level}_borders.bin").read_bytes()
    assert struct.unpack_from(">dddd", blob, 4) == outline_bounds()


def test_the_state_layer_is_much_smaller_than_the_district_layer():
    """A sanity check on the classification, not on the size.

    State borders are a subset of the interior: the edges between two states. If the classifier ever
    put every interior edge in both buckets — the easiest way to get this wrong — the two files would
    be about the same size, and nothing on screen would look obviously broken.
    """
    state = (WEB / "state-borders.txt")
    district = (WEB / "district-borders.txt")
    require(state)
    require(district)
    assert state.stat().st_size < district.stat().st_size / 2


def test_kashmir_has_border_coverage():
    """Territory inside the national outline must have interior borders drawn in it.

    The one region where an empty border layer would be a substantive error rather than a cosmetic
    one, and it regressed once already: dropping the two PoK polygons the register cannot name left
    their shared edges counted once, so the classifier read them as coastline and deleted them.
    """
    lines = decode_web(require(WEB / "district-borders.txt").read_text(encoding="utf-8"))
    north_west = [
        point
        for line in lines
        for point in line
        if 73.0 <= point[0] <= 78.0 and 33.0 <= point[1] <= 36.5
    ]
    assert len(north_west) > 100, f"only {len(north_west)} border points in Jammu & Kashmir"


def test_the_manifest_records_its_sources_and_its_gap(manifest):
    assert manifest["source"]["districts"]
    assert manifest["source"]["states"]
    assert manifest["source"]["country"]
    assert manifest["coverage"]["districts"] == 795
    # The number the product's copy quotes. If a refresh changes it, the copy has to change with it.
    assert manifest["coverage"]["joined"] + len(manifest["missing"]) == 795
