from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services.datareport import access


def _calls(dependant: Any) -> set[Any]:
    found: set[Any] = set()
    for child in dependant.dependencies:
        if child.call is not None:
            found.add(child.call)
        found |= _calls(child)
    return found


def _routes() -> dict[str, set[Any]]:
    from app.api.routes import data_report

    out: dict[str, set[Any]] = {}
    for router in (data_report.router, data_report.go_router):
        for route in router.routes:
            out["/api" + route.path] = _calls(route.dependant)
    return out


def test_both_report_routers_are_mounted() -> None:
    from pathlib import Path

    text = (Path(__file__).resolve().parents[1] / "app" / "api" / "router.py").read_text(encoding="utf-8")
    assert "include_router(data_report.router)" in text
    assert "include_router(data_report.go_router)" in text


def test_report_links_ask_for_sign_in_not_for_dataset_access() -> None:
    from app.core.deps import get_current_user, require_dataset_downloader

    routes = _routes()
    go = {path: calls for path, calls in routes.items() if path.startswith("/api/data/go/")}
    assert set(go) == {
        "/api/data/go/media/{media_id}/download",
        "/api/data/go/transcript/{media_id}/{fmt}",
        "/api/data/go/open/{kind}/{record_id}",
    }
    for path, calls in go.items():
        assert get_current_user in calls, path
        assert require_dataset_downloader not in calls, path
    for path in ("/api/data/bundles/{bundle_id}", "/api/data/bundles/{bundle_id}/manifest", "/api/data/report/sheets"):
        assert require_dataset_downloader in routes[path], path


def _media(uploader: str, **extra: Any) -> SimpleNamespace:
    return SimpleNamespace(id="m1", objectKey="media/x/a.m4a", uploadedById=uploader, linkedRecordType=None, linkedRecordId=None, **extra)


def test_the_file_check_is_the_media_pages_url_rule(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import records

    async def owners(_user: Any) -> set[str]:
        return {"u1"}

    async def scope(_user: Any) -> tuple[set[str], frozenset[str]]:
        return {"u1"}, frozenset()

    monkeypatch.setattr(records, "media_url_owners", owners)
    if hasattr(records, "media_url_scope"):
        monkeypatch.setattr(records, "media_url_scope", scope)
    viewer = SimpleNamespace(id="u1", role="RESEARCHER", canDownloadDataset=False)
    assert asyncio.run(access.may_take_file(viewer, _media("u1"))) is True
    assert asyncio.run(access.may_take_file(viewer, _media("someone-else"))) is False


def test_a_refused_file_is_403_and_a_missing_one_404(monkeypatch: pytest.MonkeyPatch) -> None:
    async def present(_id: str) -> Any:
        return _media("someone-else")

    async def missing(_id: str) -> Any:
        raise access.not_found("File not found")

    async def never(*_args: Any) -> bool:
        return False

    viewer = SimpleNamespace(id="u1", role="RESEARCHER", canDownloadDataset=False)
    monkeypatch.setattr(access, "may_take_file", never)
    monkeypatch.setattr(access, "live_media", present)
    with pytest.raises(HTTPException) as refused:
        asyncio.run(access.file_for(viewer, "m1"))
    assert refused.value.status_code == 403
    monkeypatch.setattr(access, "live_media", missing)
    with pytest.raises(HTTPException) as gone:
        asyncio.run(access.file_for(viewer, "m1"))
    assert gone.value.status_code == 404


def test_open_media_goes_to_the_page_the_viewer_may_use() -> None:
    plain = SimpleNamespace(id="u1", role="RESEARCHER", canDownloadDataset=False)
    data = SimpleNamespace(id="u2", role="RESEARCHER", canDownloadDataset=True)
    assert access.media_href(plain, "m 1") == "/media/m%201"
    assert access.media_href(data, "m1") == "/data?preview=m1"


def test_an_unknown_kind_is_404() -> None:
    viewer = SimpleNamespace(id="u1", role="RESEARCHER", canDownloadDataset=False)
    with pytest.raises(HTTPException) as unknown:
        asyncio.run(access.record_href(viewer, "spaceship", "x"))
    assert unknown.value.status_code == 404
