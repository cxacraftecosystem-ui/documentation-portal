"""The modules both portals carry byte for byte (docs/SEARCH.md).

``services/cross_portal.py``, ``services/cross_portal_client.py``, ``services/cross_portal_peer.py``
and ``services/search_embeddings.py`` are the same files in the designer portal and the Field Repository; a change to one is a change to
both. Point ``CROSS_PORTAL_PEER_REPO`` at a checkout of the other repository to compare them (line
endings normalised, as git stores them); without it the comparison skips and only the local rules run.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

SHARED = (
    "cross_portal.py",
    "cross_portal_client.py",
    "cross_portal_peer.py",
    "search_embeddings.py",
)
HERE = Path(__file__).resolve().parents[1] / "app" / "services"


def _normalised(path: Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n")


@pytest.mark.parametrize("name", SHARED)
def test_the_shared_module_matches_the_other_portal(name):
    other = os.environ.get("CROSS_PORTAL_PEER_REPO", "").strip()
    if not other:
        pytest.skip("CROSS_PORTAL_PEER_REPO is not set")
    theirs = Path(other) / "backend" / "app" / "services" / name
    assert theirs.is_file(), theirs
    assert _normalised(HERE / name) == _normalised(theirs), f"{name} differs between the portals"


@pytest.mark.parametrize("name", SHARED)
def test_the_shared_module_names_no_portal_specific_module(name):
    """What would stop the file being copied verbatim: an import only one portal has."""
    text = (HERE / name).read_text(encoding="utf-8")
    for line in text.splitlines():
        if line.startswith(("from app.", "import app.")):
            assert line.split()[1] in {
                "app.core.config",
                "app.core.db",
                "app.services",
            }, line
