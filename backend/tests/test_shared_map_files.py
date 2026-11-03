from __future__ import annotations

import hashlib
import os
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.fieldrepository.app"
PEER_PACKAGE = "com.designprototype.workshop"
KOTLIN = Path("android/app/src/main/java")
SHARED_KOTLIN = (
    "ui/MapZoom.kt",
    "ui/IndiaOutline.kt",
    "ui/CoverageMapCanvas.kt",
    "data/CoverageModels.kt",
    "data/CoverageMath.kt",
)
SHARED_BYTES = (
    "android/app/src/main/res/raw/district_shapes.bin",
    "android/app/src/main/res/raw/india_outline.bin",
)
SHARED_TEXT = (
    "scripts/build_boundaries.py",
    "frontend/e2e/fixtures/coverage-parity.json",
    "android/app/src/test/resources/coverage-parity.json",
)


def _text(path: Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n")


def _kotlin(path: Path, package: str) -> bytes:
    return _text(path).replace(package.encode(), b"PACKAGE")


def _peer() -> Path:
    other = os.environ.get("CROSS_PORTAL_PEER_REPO", "").strip()
    if not other:
        pytest.skip("CROSS_PORTAL_PEER_REPO is not set")
    return Path(other)


def test_the_web_and_android_read_one_parity_fixture() -> None:
    web = ROOT / "frontend" / "e2e" / "fixtures" / "coverage-parity.json"
    android = ROOT / "android" / "app" / "src" / "test" / "resources" / "coverage-parity.json"
    assert _text(web) == _text(android)


def test_the_district_polygons_are_present_and_framed_like_the_outline() -> None:
    blob = (ROOT / "android" / "app" / "src" / "main" / "res" / "raw" / "district_shapes.bin").read_bytes()
    outline = (ROOT / "android" / "app" / "src" / "main" / "res" / "raw" / "india_outline.bin").read_bytes()
    assert blob[:4] == b"DSH1"
    assert blob[4:36] == outline[4:36]


@pytest.mark.parametrize("name", SHARED_KOTLIN)
def test_the_shared_kotlin_names_no_portal_specific_symbol(name: str) -> None:
    text = (ROOT / KOTLIN / "com" / "fieldrepository" / "app" / name).read_text(encoding="utf-8")
    for line in text.splitlines():
        if line.startswith(f"import {PACKAGE}."):
            assert line.split()[1].rsplit(".", 1)[0] in {f"{PACKAGE}.data", PACKAGE}, line
        assert "WorkshopRepository" not in line, line
        assert "FieldRepository" not in line, line
        assert "MaterialTheme.field" not in line, line


@pytest.mark.parametrize("name", SHARED_KOTLIN)
def test_the_shared_kotlin_matches_the_other_portal(name: str) -> None:
    peer = _peer()
    theirs = peer / KOTLIN / "com" / "designprototype" / "workshop" / name
    if not theirs.is_file():
        pytest.skip(f"{name} is not in the other portal yet")
    ours = ROOT / KOTLIN / "com" / "fieldrepository" / "app" / name
    assert _kotlin(ours, PACKAGE) == _kotlin(theirs, PEER_PACKAGE), f"{name} differs between the portals"


@pytest.mark.parametrize("name", SHARED_BYTES)
def test_the_shared_blob_matches_the_other_portal(name: str) -> None:
    theirs = _peer() / name
    if not theirs.is_file():
        pytest.skip(f"{name} is not in the other portal yet")
    ours = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
    assert ours == hashlib.sha256(theirs.read_bytes()).hexdigest(), f"{name} differs between the portals"


@pytest.mark.parametrize("name", SHARED_TEXT)
def test_the_shared_text_matches_the_other_portal(name: str) -> None:
    theirs = _peer() / name
    if not theirs.is_file():
        pytest.skip(f"{name} is not in the other portal yet")
    assert _text(ROOT / name) == _text(theirs), f"{name} differs between the portals"
