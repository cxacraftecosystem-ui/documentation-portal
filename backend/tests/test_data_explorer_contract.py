from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parents[1]
CONTRACT = BACKEND.parent / "shared" / "data-explorer-contract.json"


def _portal() -> str:
    if (BACKEND / "app" / "services" / "design_workshops.py").is_file():
        return "designer-portal"
    return "documentation-portal"


def _contract() -> dict:
    return json.loads(CONTRACT.read_text(encoding="utf-8"))


def _mounted() -> set[tuple[str, str]]:
    from app.api.router import api_router

    try:
        from fastapi.routing import iter_route_contexts

        routes = list(iter_route_contexts(api_router.routes))
    except ImportError:
        routes = list(api_router.routes)
    found: set[tuple[str, str]] = set()
    for route in routes:
        path = re.sub(r"\{[^}]+\}", "{}", getattr(route, "path", ""))
        for method in getattr(route, "methods", None) or ():
            found.add((method, path))
    return found


ROUTES = [
    route
    for route in _contract()["routes"]
    if _portal() in route.get("portals", ["designer-portal", "documentation-portal"])
]


@pytest.mark.parametrize("route", ROUTES, ids=[r["id"] for r in ROUTES])
def test_every_contract_route_is_mounted_with_its_method(route):
    base = _contract()["basePath"]
    want = (route["method"], re.sub(r"\{[^}]+\}", "{}", base + route["path"]))
    mounted = _mounted()
    assert want in mounted, f"{route['id']}: {want} is not mounted"


def test_the_contract_limits_match_the_server():
    from app.api.routes import data_explorer
    from app.services.data_explorer import browse, report_tools, signing, tree, walk

    limits = _contract()["limits"]
    assert limits["treePageMax"] == tree.TREE_PAGE_MAX
    assert limits["manifestMaxFiles"] == walk.MAX_MANIFEST_FILES
    assert limits["manifestMaxPaths"] == data_explorer.MANIFEST_MAX_PATHS
    assert limits["manifestMaxMediaIds"] == data_explorer.MANIFEST_MAX_MEDIA_IDS
    assert limits["browsePageSizeMax"] == browse.PAGE_SIZE_MAX
    assert limits["browsePageSizeDefault"] == browse.PAGE_SIZE_DEFAULT
    assert limits["reportSearchMaxRows"] == report_tools.SEARCH_MAX_ROWS
    assert limits["transcriptListMax"] == data_explorer.TRANSCRIPT_LIST_MAX
    assert limits["reportPreviewTranscriptChars"] == report_tools.PREVIEW_TRANSCRIPT_CHARS
    assert limits["signedUrlExpiresInSeconds"] == signing.EXPIRES_IN_SECONDS
    assert limits["signedUrlReuseSeconds"] == signing.REUSE_SECONDS


def test_the_contract_type_tokens_match_the_server():
    from app.services.data_explorer import stats

    tokens = [t.strip() for t in _contract()["types"]["typeToken"].split("|")]
    assert tokens == list(stats.TYPE_TOKENS)


def test_the_report_route_takes_every_contract_query_parameter():
    import inspect

    from app.api.routes import data_browser

    report = next(route for route in _contract()["routes"] if route["id"] == "report")
    taken = set(inspect.signature(data_browser.data_report).parameters)
    assert set(report["query"]) <= taken
    assert "offset" in report["query"]
