from __future__ import annotations

import hashlib
import os
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "shared" / "data-report.sha256"
PACKAGE = REPO / "backend" / "app" / "services" / "datareport"


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def _entries() -> list[tuple[str, str]]:
    lines = MANIFEST.read_text(encoding="utf-8").replace("\r\n", "\n").splitlines()
    return [tuple(line.split("  ", 1)) for line in lines if line.strip()]  # type: ignore[misc]


def test_every_shared_file_matches_the_manifest() -> None:
    drifted = [rel for digest, rel in _entries() if not (REPO / rel).is_file() or _digest(REPO / rel) != digest]
    assert not drifted, (
        f"{drifted} differ from shared/data-report.sha256. Edit designer-portal's copy and run "
        "`python scripts/sync_data_report.py --to ../documentation-portal` there."
    )


def test_every_package_file_is_listed() -> None:
    listed = {rel for _, rel in _entries()}
    package = {
        path.relative_to(REPO).as_posix()
        for path in PACKAGE.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts and path.suffix in (".py", ".json")
    }
    assert package <= listed, f"unlisted shared files: {sorted(package - listed)}"


def test_portal_differences_come_from_config_only() -> None:
    for path in PACKAGE.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        for forbidden in ("documentation-portal", "designer-portal", "FIELD_REPOSITORY_WEB_URL"):
            assert forbidden not in text, f"{path.name} names a portal directly: {forbidden}"


def _sibling() -> Path | None:
    here_is_designer = (REPO / "android" / "app" / "src" / "main" / "java" / "com" / "designprototype").is_dir()
    other = "documentation-portal" if here_is_designer else "designer-portal"
    for candidate in (
        os.environ.get("CROSS_PORTAL_PEER_REPO", "").strip(),
        os.environ.get("SIBLING_REPO", "").strip(),
        str(REPO.parent / other),
    ):
        if candidate and (Path(candidate) / "shared" / "data-report.sha256").is_file():
            return Path(candidate)
    return None


def test_the_manifest_is_the_other_portals() -> None:
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no checkout of the other portal beside this one")
    theirs = (sibling / "shared" / "data-report.sha256").read_bytes().replace(b"\r\n", b"\n")
    assert MANIFEST.read_bytes().replace(b"\r\n", b"\n") == theirs, (
        "shared/data-report.sha256 differs between the portals; run "
        "`python scripts/sync_data_report.py --to ../documentation-portal` from designer-portal."
    )
