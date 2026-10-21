"""Every questionnaire feature is served in both portals, on web and Android (UNIFY section 5.3).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal. ``shared/questionnaire-features.json``
lists every row of the spec's matrix (A1-A20, B1-B40, C1-C10) and owner amendment 2 (D1-D4), with a
value per client: ``required``, or ``{"na": "ministry-admin-authoring"}`` for designer-portal Android
only (owner: ministry/admin authoring is web-only there). Each check runs against this repository and,
when CI provides it, the other portal's checkout, so a feature built in one place and not the other
fails in BOTH repositories.

WHAT "planned" MEANS, AND HOW IT FLIPS. A client is ``tagged`` (its marker exists: a Playwright test
titled ``@qf:<id>`` under ``frontend/e2e``, or ``@QFeature("<id>")`` under
``android/app/src/{test,androidTest}``) or ``planned`` (the stream in ``stream`` builds it). ``planned``
keeps CI green while U1-U4 build, and stops being accepted, for every required client and server:

1. when ``unifiedRelease`` is true in the registry: U5 sets it in the commit that cuts the unified
   release and switches ``QUESTIONNAIRE_V2`` on in designer-portal (the switch-on and the flip are
   one commit, so the switch-on cannot ship with a planned feature);
2. for this portal's own clients, when its ``Settings`` default for ``questionnaire_v2`` is true;
3. for one feature in every client, when the portal's ``Settings`` default for that feature's
   ``flag`` (``questionnaire_answer_locks`` for A11) is true.

A marker present on a client still marked ``planned`` also fails: the registry must say ``tagged``.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
REGISTRY = json.loads((REPO / "shared" / "questionnaire-features.json").read_text(encoding="utf-8"))
FEATURES = REGISTRY["features"]
CLIENTS = ("dr-web", "dr-android", "fr-web", "fr-android")
NA = {"na": "ministry-admin-authoring"}
DR, FR = "designer-portal", "documentation-portal"
HERE = DR if (REPO / "android" / "app" / "src" / "main" / "java" / "com" / "designprototype").is_dir() else FR
WEB_MARK = re.compile(r"@qf:([A-Z]\d+)\b")
ANDROID_MARK = re.compile(r'@QFeature\(\s*"([A-Z]\d+)"\s*\)')


def _peer() -> Path | None:
    other = FR if HERE == DR else DR
    for candidate in (os.environ.get("CROSS_PORTAL_PEER_REPO", "").strip(), os.environ.get("SIBLING_REPO", "").strip(),
                      str(REPO.parent / other)):
        if candidate and (Path(candidate) / "shared" / "questionnaire-features.json").is_file():
            return Path(candidate)
    return None


def _root_of(portal: str) -> Path | None:
    return REPO if portal == HERE else _peer()


def _marks(root: Path, client: str) -> set[str]:
    found: set[str] = set()
    if client.endswith("web"):
        for path in (root / "frontend" / "e2e").rglob("*.ts"):
            if "node_modules" not in path.parts:
                found |= set(WEB_MARK.findall(path.read_text(encoding="utf-8", errors="replace")))
    else:
        for sub in ("test", "androidTest"):
            for path in (root / "android" / "app" / "src" / sub).rglob("*.kt"):
                found |= set(ANDROID_MARK.findall(path.read_text(encoding="utf-8", errors="replace")))
    return found


def _settings_default(name: str) -> bool:
    from app.core.config import Settings

    field = Settings.model_fields.get(name)
    return bool(field is not None and field.default is True)


def test_the_registry_lists_every_row_of_the_matrix_once() -> None:
    ids = [f["id"] for f in FEATURES]
    assert len(ids) == len(set(ids)), "a feature id is listed twice"
    wanted = {f"A{n}" for n in range(1, 21)} | {f"B{n}" for n in range(1, 41)} | {f"C{n}" for n in range(1, 11)} | {f"D{n}" for n in range(1, 5)}
    assert wanted <= set(ids), f"missing from the registry: {sorted(wanted - set(ids))}"
    assert REGISTRY["clients"] == {"dr-web": DR, "dr-android": DR, "fr-web": FR, "fr-android": FR}


def test_only_designer_portal_android_may_be_not_applicable() -> None:
    for f in FEATURES:
        assert set(f["clients"]) == set(CLIENTS), f["id"]
        for client, value in f["clients"].items():
            if value == "required":
                assert f["status"][client] in ("tagged", "planned"), (f["id"], client, f["status"][client])
            else:
                assert client == "dr-android" and value == NA, f"{f['id']}: N/A is allowed only for dr-android ministry/admin authoring"
                assert f["status"][client] == "na", (f["id"], client)
        for portal in (DR, FR):
            assert f["server"][portal] in ("built", "planned"), (f["id"], portal)


def _enforced(feature: dict, client: str) -> bool:
    if REGISTRY.get("unifiedRelease"):
        return True
    if REGISTRY["clients"][client] == HERE and _settings_default("questionnaire_v2"):
        return True
    flag = feature.get("flag")
    return bool(flag and _settings_default(flag))


@pytest.mark.parametrize("client", CLIENTS)
def test_every_required_client_has_its_marker(client: str) -> None:
    root = _root_of(REGISTRY["clients"][client])
    if root is None:
        pytest.skip(f"no checkout of {REGISTRY['clients'][client]} beside this one")
    marks = _marks(root, client)
    problems = []
    for f in FEATURES:
        if f["clients"][client] != "required":
            continue
        state = f["status"][client]
        if state == "tagged" and f["id"] not in marks:
            problems.append(f"{f['id']} is 'tagged' for {client} but no test carries its marker")
        if state == "planned" and f["id"] in marks:
            problems.append(f"{f['id']} has a marker in {client}: set its status to 'tagged'")
        if state == "planned" and _enforced(f, client):
            problems.append(f"{f['id']} is still 'planned' for {client} (stream {f['stream'][client]}) and the switch is on")
    assert not problems, "\n".join(problems)


def _mounted() -> set[tuple[str, str]]:
    from app.api.router import api_router

    try:
        from fastapi.routing import iter_route_contexts

        routes = list(iter_route_contexts(api_router.routes))
    except ImportError:  # an older FastAPI keeps them on .routes
        routes = list(api_router.routes)
    have = set()
    for route in routes:
        path = re.sub(r"\{[^}]+\}", "{}", getattr(route, "path", ""))
        path = path[len("/api"):] if path.startswith("/api") else path
        for method in getattr(route, "methods", None) or ():
            have.add((method, path))
    return have


def test_every_built_feature_is_served_by_this_portal() -> None:
    have = _mounted()
    missing = []
    for f in FEATURES:
        if f["server"][HERE] == "built" or REGISTRY.get("unifiedRelease"):
            for entry in f["api"][HERE]:
                method, path = entry.split(" ", 1)
                if (method, re.sub(r"\{[^}]+\}", "{}", path)) not in have:
                    missing.append(f"{f['id']}: {entry}")
        if REGISTRY.get("unifiedRelease"):
            assert f["server"][HERE] == "built", f"{f['id']} is not built in {HERE}"
    assert not missing, f"routes the registry says {HERE} serves but it does not: {missing}"
