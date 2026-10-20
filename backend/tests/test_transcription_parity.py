"""The shared transcription service is byte-identical in both portals.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal. Each repository carries
``shared/transcription.sha256``, written by designer-portal's ``scripts/sync_transcription.py``;
both manifests are the same file, so a change made in one portal and not copied to the other fails
here, in that portal's CI. Digests are over LF-normalised text (Windows checkouts are CRLF).
"""

from __future__ import annotations

import hashlib
import os
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "shared" / "transcription.sha256"


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def _entries() -> list[tuple[str, str]]:
    lines = MANIFEST.read_text(encoding="utf-8").replace("\r\n", "\n").splitlines()
    return [tuple(line.split("  ", 1)) for line in lines if line.strip()]  # type: ignore[misc]


def test_every_shared_file_matches_the_manifest() -> None:
    drifted = [rel for digest, rel in _entries() if _digest(REPO / rel) != digest]
    assert not drifted, (
        f"{drifted} differ from shared/transcription.sha256. Edit designer-portal's copy and run "
        "`python scripts/sync_transcription.py --to ../documentation-portal` there."
    )


def test_every_package_file_is_listed() -> None:
    listed = {rel for _, rel in _entries()}
    package = {
        path.relative_to(REPO).as_posix()
        for path in (REPO / "backend" / "app" / "transcription").rglob("*.py")
        if "__pycache__" not in path.parts
    }
    assert package <= listed, f"unlisted shared files: {sorted(package - listed)}"


def test_the_package_imports_no_portal_code() -> None:
    """No FastAPI, no Prisma, no portal module: the package must work unchanged in either portal."""
    for path in (REPO / "backend" / "app" / "transcription").rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        for forbidden in (
            "import fastapi",
            "from fastapi",
            "import prisma",
            "from prisma",
            "app.services",
            "app.core",
            "app.api",
        ):
            assert forbidden not in text, f"{path.name} reaches outside the package: {forbidden}"


def _sibling() -> Path | None:
    """The other portal's checkout: $CROSS_PORTAL_PEER_REPO or $SIBLING_REPO (CI clones the other
    repository's main there), else the checkout beside this one."""
    here_is_designer = (REPO / "android" / "app" / "src" / "main" / "java" / "com" / "designprototype").is_dir()
    other = "documentation-portal" if here_is_designer else "designer-portal"
    for candidate in (
        os.environ.get("CROSS_PORTAL_PEER_REPO", "").strip(),
        os.environ.get("SIBLING_REPO", "").strip(),
        str(REPO.parent / other),
    ):
        if candidate and (Path(candidate) / "shared" / "transcription.sha256").is_file():
            return Path(candidate)
    return None


def test_the_manifest_is_the_other_portals() -> None:
    """The two manifests are one file, so a shared change copied to one portal only fails here."""
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no checkout of the other portal beside this one")
    theirs = (sibling / "shared" / "transcription.sha256").read_bytes().replace(b"\r\n", b"\n")
    assert MANIFEST.read_bytes().replace(b"\r\n", b"\n") == theirs, (
        "shared/transcription.sha256 differs between the portals; run "
        "`python scripts/sync_transcription.py --to ../documentation-portal` from designer-portal."
    )
