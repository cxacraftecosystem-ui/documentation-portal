"""The shared transcription service is byte-identical in both portals.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal. Each repository carries
``shared/transcription.sha256``, written by designer-portal's ``scripts/sync_transcription.py``;
both manifests are the same file, so a change made in one portal and not copied to the other fails
here, in that portal's CI. Digests are over LF-normalised text (Windows checkouts are CRLF).
"""

from __future__ import annotations

import hashlib
from pathlib import Path

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
