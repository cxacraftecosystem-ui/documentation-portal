"""The content-admin set is written once on the server and copied into the web and the handset.

Owner's ruling, 2026-10-20: ADMIN, MASTER_ADMIN and MINISTRY_ADMIN add, edit and delete every
content type; every other role adds and edits and never deletes. ``app/core/roles.py::
CONTENT_ADMIN_ROLES`` decides it, ``frontend/lib/permissions.ts::CONTENT_ADMIN_ROLES`` hides the
controls and ``FieldPermissions.CONTENT_ADMIN_ROLES`` does the same on the phone. Nothing compiles the
three against each other, so this file reads the two copies as text and holds them to the server.

The designer-portal carries the identical set by the identical names (its ``core/deps.py``); with
``CROSS_PORTAL_PEER_REPO`` set, the last test reads it from there too.
"""

import os
import re
from pathlib import Path

import pytest

from app.core.roles import CONTENT_ADMIN_ROLES, is_admin, is_content_admin

REPO = Path(__file__).resolve().parents[2]
WEB = REPO / "frontend" / "lib" / "permissions.ts"
KOTLIN = REPO / "android" / "app" / "src" / "main" / "java" / "com" / "fieldrepository" / "app" / "ui" / "AppNavigation.kt"
EXPECTED = frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"})


def _names(text: str) -> set[str]:
    return set(re.findall(r'"([A-Z_]+)"', text))


def test_the_server_set_is_the_three_admin_tiers_and_is_admin_is_not_widened() -> None:
    assert EXPECTED == CONTENT_ADMIN_ROLES
    assert is_content_admin({"role": "MINISTRY_ADMIN"})
    assert not is_admin({"role": "MINISTRY_ADMIN"})
    for role in ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR"):
        assert not is_content_admin({"role": role}), role


def test_the_web_carries_the_same_set() -> None:
    if not WEB.is_file():
        pytest.skip("the frontend is not present in this checkout")
    found = re.search(r"export const CONTENT_ADMIN_ROLES: readonly string\[\] = \[([^\]]*)\];", WEB.read_text(encoding="utf-8"))
    assert found, "frontend/lib/permissions.ts declares no CONTENT_ADMIN_ROLES"
    assert _names(found.group(1)) == set(CONTENT_ADMIN_ROLES)


def test_the_handset_carries_the_same_set() -> None:
    if not KOTLIN.is_file():
        pytest.skip("the Android tree is not present in this checkout")
    found = re.search(r"val CONTENT_ADMIN_ROLES: Set<String> = setOf\(([^)]*)\)", KOTLIN.read_text(encoding="utf-8"))
    assert found, "FieldPermissions declares no CONTENT_ADMIN_ROLES"
    assert _names(found.group(1)) == set(CONTENT_ADMIN_ROLES)


def test_the_sibling_portal_carries_the_same_set() -> None:
    other = os.environ.get("CROSS_PORTAL_PEER_REPO")
    if not other:
        pytest.skip("CROSS_PORTAL_PEER_REPO is not set")
    deps = Path(other) / "backend" / "app" / "core" / "deps.py"
    found = re.search(r"^CONTENT_ADMIN_ROLES = frozenset\(\{([^}]*)\}\)", deps.read_text(encoding="utf-8"), re.MULTILINE)
    assert found, "the designer-portal declares no CONTENT_ADMIN_ROLES"
    assert _names(found.group(1)) == set(CONTENT_ADMIN_ROLES)
