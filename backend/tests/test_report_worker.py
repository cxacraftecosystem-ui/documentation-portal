from __future__ import annotations

import ast
import asyncio
import inspect
import os
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import data_browser
from app.services.data_explorer import report_cache
from app.services.datareport import jobs
from tests import report_fixtures
from tests.report_worker import body, report_kwargs, running_worker, store_only

ADMIN = SimpleNamespace(id="admin-1", role="MASTER_ADMIN", canDownloadDataset=True)
OTHER = SimpleNamespace(id="admin-2", role="MASTER_ADMIN", canDownloadDataset=True)
PLAIN = SimpleNamespace(id="plain-1", role="RESEARCHER", canDownloadDataset=False)
USERS = {u.id: u for u in (ADMIN, OTHER, PLAIN)}

ROUTES = Path(__file__).resolve().parents[1] / "app" / "api" / "routes"
ROUTE_FILES = ("data_browser.py", "data_report.py", "data_report_pack.py", "data_report_schedule.py")
BUILDER_NAMES = frozenset(
    {"build_report", "build_xlsx", "render_parts", "response_parts", "core_xlsx", "auto_xlsx", "deliverable", "legacy_xlsx"}
)
API_SIDE = ("enqueue", "wait_for", "fetch", "scope_key", "_serve", "_scope")


def _names(tree: ast.AST) -> set[str]:
    found: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute):
            found.add(node.attr)
        elif isinstance(node, ast.Name):
            found.add(node.id)
        elif isinstance(node, ast.alias):
            found.add(node.name.split(".")[-1])
            if node.asname:
                found.add(node.asname)
    return found


@pytest.mark.parametrize("name", ROUTE_FILES)
def test_the_report_routes_never_call_the_builder(name: str) -> None:
    path = ROUTES / name
    if not path.is_file():
        pytest.skip(f"{name} is not in this portal")
    tree = ast.parse(path.read_text(encoding="utf-8"))
    called = sorted(_names(tree) & BUILDER_NAMES)
    assert called == [], f"{name} calls the report builder in the API process: {called}"
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and (node.module or "").endswith("datareport"):
            assert not {a.name for a in node.names} & {"pack"}, f"{name} imports the report pack builder"


@pytest.mark.parametrize("name", API_SIDE)
def test_the_api_side_of_the_queue_never_touches_the_builder(name: str) -> None:
    source = inspect.getsource(getattr(jobs, name))
    assert "datareport import build" not in source and "datareport import pack" not in source
    assert not _names(ast.parse(source.strip())) & BUILDER_NAMES


def _tools() -> dict[str, Any]:
    return data_browser._sheet("Tools", "#000000", ["Tool"], [[f"tool {i}"] for i in range(3)])


def _stub(monkeypatch: pytest.MonkeyPatch, builds: list[str]) -> None:
    async def version(_db: Any) -> str:
        return "v1"

    async def scope(_user: Any, *_args: Any) -> Any:
        return data_browser.Scope(records={}, media={})

    async def material(norm: str, *_args: Any) -> Any:
        builds.append(norm)
        return {"sheets": [_tools()], "level": "Repository", "builtAt": "2026-11-08T10:00:00+05:30"}

    async def records(_segs: Any, _scope: Any) -> dict[str, list[Any]]:
        found: dict[str, list[Any]] = {key: [] for key in data_browser._REPORT_KEYS}
        found["tools"] = [SimpleNamespace(id=f"t{i}", toolkitName=f"tool {i}", status="APPROVED") for i in range(3)]
        return found

    monkeypatch.setattr(data_browser.report_cache, "data_version", version)
    monkeypatch.setattr(data_browser, "_scope_for", scope)
    monkeypatch.setattr(data_browser, "_report_material", material)
    monkeypatch.setattr(data_browser, "_report_records", records)
    monkeypatch.setattr(data_browser, "db", report_fixtures.FakeDB())


def _xlsx(user: Any = ADMIN, **kwargs: Any) -> Any:
    return data_browser.data_report(
        **report_kwargs(data_browser.data_report, path="p", format="xlsx", current_user=user, **kwargs)
    )


def test_the_api_only_enqueues_and_says_when_to_come_back(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services.datareport import build

    async def never(*_args: Any, **_kwargs: Any) -> Any:
        raise AssertionError("the API process built a report")

    _stub(monkeypatch, [])
    monkeypatch.setattr(build, "build_report", never)
    monkeypatch.setattr(jobs, "WAIT_SECONDS", 0.05)
    with store_only():
        with pytest.raises(HTTPException) as busy:
            asyncio.run(_xlsx())
        queued = jobs.records()
    assert busy.value.status_code == 503
    assert busy.value.headers == {"Retry-After": str(report_cache.RETRY_AFTER_SECONDS)}
    assert [r["state"] for r in queued] == [jobs.QUEUED]
    assert queued[0]["userId"] == ADMIN.id and queued[0]["kind"] == jobs.REPORT


def test_the_worker_builds_and_stores_the_file_and_the_download_serves_it(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services.datareport import build

    builds: list[str] = []
    made: list[str] = []
    real = build.build_report

    async def counted(*args: Any, **kwargs: Any) -> Any:
        made.append("built")
        return await real(*args, **kwargs)

    _stub(monkeypatch, builds)
    monkeypatch.setattr(build, "build_report", counted)

    async def story() -> tuple[Any, Any, list[dict[str, Any]], list[str]]:
        async with running_worker(USERS) as where:
            first = await _xlsx(name="Kamla dyes")
            again = await _xlsx(name="Kamla dyes")
            return first, again, jobs.records(), sorted(p.suffix for p in where.iterdir())

    first, again, stored, files = asyncio.run(story())
    assert first.status_code == 200 and body(first)[:2] == b"PK"
    assert body(again) == body(first)
    assert 'filename="Kamla-dyes.xlsx"' in first.headers["content-disposition"]
    assert first.headers["cache-control"] == "no-store"
    assert [r["state"] for r in stored] == [jobs.DONE]
    assert ".bin" in files and ".json" in files
    assert made == ["built"]


def test_one_user_never_gets_another_users_report(monkeypatch: pytest.MonkeyPatch) -> None:
    _stub(monkeypatch, [])

    async def story() -> tuple[Any, Any, list[dict[str, Any]], HTTPException]:
        async with running_worker(USERS):
            mine = await _xlsx(ADMIN)
            theirs = await _xlsx(OTHER)
            stored = jobs.records()
            first = next(r for r in stored if r["userId"] == ADMIN.id)
            try:
                await jobs.wait_for(first["id"], OTHER, wait=0.05)
            except HTTPException as refused:
                return mine, theirs, stored, refused
            raise AssertionError("another user read a stored report")

    mine, theirs, stored, refused = asyncio.run(story())
    assert mine.status_code == 200 and theirs.status_code == 200
    assert sorted(r["userId"] for r in stored) == [ADMIN.id, OTHER.id]
    assert len({r["id"] for r in stored}) == 2
    assert refused.status_code == 404


def _fixed_key(monkeypatch: pytest.MonkeyPatch) -> None:
    async def key(*_args: Any) -> str:
        return "k"

    monkeypatch.setattr(jobs, "scope_key", key)


def test_identical_requests_in_flight_share_one_job(monkeypatch: pytest.MonkeyPatch) -> None:
    _fixed_key(monkeypatch)
    calls: list[str] = []

    async def slow(record: dict[str, Any], _user: Any, into: Path) -> tuple[str, str, dict[str, str]]:
        calls.append(record["id"])
        await asyncio.sleep(0.1)
        into.write_bytes(b"PK\x03\x04shared")
        return "Report.xlsx", jobs.XLSX_MIME, {}

    monkeypatch.setitem(jobs.BUILDERS, jobs.REPORT, slow)

    async def story() -> list[Any]:
        async with running_worker(USERS):
            params = {"path": "p", "options": {}}
            return list(await asyncio.gather(*(jobs.fetch(ADMIN, jobs.REPORT, params) for _ in range(4))))

    answers = asyncio.run(story())
    assert len(calls) == 1
    assert {body(a) for a in answers} == {b"PK\x03\x04shared"}


def test_a_failed_job_is_reported_once_then_cleared(monkeypatch: pytest.MonkeyPatch) -> None:
    _fixed_key(monkeypatch)
    outcomes = iter(["boom", "ok"])

    async def flaky(_record: dict[str, Any], _user: Any, into: Path) -> tuple[str, str, dict[str, str]]:
        if next(outcomes) == "boom":
            raise RuntimeError("boom")
        into.write_bytes(b"PK\x03\x04ok")
        return "Report.xlsx", jobs.XLSX_MIME, {}

    monkeypatch.setitem(jobs.BUILDERS, jobs.REPORT, flaky)
    trims: list[int] = []
    monkeypatch.setattr(jobs, "_trim_heap", lambda: trims.append(1))

    async def story() -> tuple[HTTPException, list[dict[str, Any]], Any, list[dict[str, Any]]]:
        async with running_worker(USERS):
            params = {"path": "p", "options": {}}
            try:
                await jobs.fetch(ADMIN, jobs.REPORT, params)
            except HTTPException as failed:
                left = jobs.records()
                retried = await jobs.fetch(ADMIN, jobs.REPORT, params)
                return failed, left, retried, jobs.records()
            raise AssertionError("a broken build was served")

    failed, left, retried, after = asyncio.run(story())
    assert failed.status_code == 500 and failed.detail == jobs.NOT_BUILT
    assert [(r["state"], r.get("reported")) for r in left] == [(jobs.FAILED, True)]
    assert body(retried) == b"PK\x03\x04ok"
    assert [r["state"] for r in after] == [jobs.DONE]
    assert len(trims) == 2


def test_a_build_past_the_time_limit_fails_and_frees_the_lane(monkeypatch: pytest.MonkeyPatch) -> None:
    _fixed_key(monkeypatch)
    monkeypatch.setattr(jobs, "BUILD_TIMEOUT_SECONDS", 0.05)

    async def stuck(_record: dict[str, Any], _user: Any, _into: Path) -> tuple[str, str, dict[str, str]]:
        await asyncio.sleep(10)
        raise AssertionError("not reached")

    monkeypatch.setitem(jobs.BUILDERS, jobs.REPORT, stuck)

    async def story() -> HTTPException:
        async with running_worker(USERS):
            try:
                await jobs.fetch(ADMIN, jobs.REPORT, {"path": "p", "options": {}})
            except HTTPException as refused:
                return refused
            raise AssertionError("a stuck build was served")

    refused = asyncio.run(story())
    assert refused.status_code == 504 and refused.detail == jobs.TOO_LONG


def test_the_worker_rechecks_download_access(monkeypatch: pytest.MonkeyPatch) -> None:
    _fixed_key(monkeypatch)

    async def story() -> HTTPException:
        async with running_worker(USERS):
            try:
                await jobs.fetch(PLAIN, jobs.REPORT, {"path": "p", "options": {}})
            except HTTPException as refused:
                return refused
            raise AssertionError("a user without download access got a report")

    assert asyncio.run(story()).status_code == 403


def test_a_full_queue_refuses_new_jobs_with_retry_after(monkeypatch: pytest.MonkeyPatch) -> None:
    _stub(monkeypatch, [])
    monkeypatch.setattr(jobs, "DEPTH", 0)
    with store_only(), pytest.raises(HTTPException) as busy:
        asyncio.run(_xlsx())
    assert busy.value.status_code == 503
    assert busy.value.detail == report_cache.REPORT_BUSY


def test_the_pack_route_enqueues_a_pack_job(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.api.routes import data_report_pack

    _fixed_key(monkeypatch)
    monkeypatch.setattr(jobs, "WAIT_SECONDS", 0.05)
    route = data_report_pack.data_report_pack
    with store_only():
        with pytest.raises(HTTPException) as bad:
            asyncio.run(route(path="p", answers="klingon", status_value=None, current_user=ADMIN))
        with pytest.raises(HTTPException) as busy:
            asyncio.run(
                route(
                    path="p", q=None, sheets=None, answers=None, rows=None, links=None, name=None,
                    thumbnails=None, locale="hi", extras=None, status_value=None, current_user=ADMIN,
                )
            )
        queued = jobs.records()
    assert bad.value.status_code == 422 and queued and queued[0]["kind"] == jobs.PACK
    assert busy.value.status_code == 503
    assert queued[0]["params"]["extra"] == {"locale": "hi"}


def test_abandoned_jobs_are_dropped_and_cut_off_builds_fail() -> None:
    with store_only():
        old = time.time() - jobs.ABANDON_SECONDS - 5
        jobs._create("a" * 64, {"id": "a" * 64, "state": jobs.QUEUED, "createdAt": old, "userId": "u"})
        jobs._create("b" * 64, {"id": "b" * 64, "state": jobs.RUNNING, "createdAt": old, "startedAt": old, "userId": "u"})
        assert jobs.next_job() is None
        assert jobs.read("a" * 64) is None
        assert jobs.recover() == 1
        assert jobs.read("b" * 64)["state"] == jobs.FAILED


def test_the_sweep_removes_files_older_than_a_day() -> None:
    with store_only() as where:
        stale = where / f"{'c' * 64}.bin"
        fresh = where / f"{'d' * 64}.bin"
        stale.write_bytes(b"x")
        fresh.write_bytes(b"x")
        day_ago = time.time() - jobs.KEEP_SECONDS - 60
        os.utime(stale, (day_ago, day_ago))
        jobs.sweep()
        assert not stale.exists() and fresh.exists()


def test_the_lane_sends_scheduled_reports_only_when_no_download_waits(monkeypatch: pytest.MonkeyPatch) -> None:
    _fixed_key(monkeypatch)
    order: list[str] = []

    async def quick(_record: dict[str, Any], _user: Any, into: Path) -> tuple[str, str, dict[str, str]]:
        order.append("job")
        into.write_bytes(b"PK\x03\x04")
        return "Report.xlsx", jobs.XLSX_MIME, {}

    monkeypatch.setitem(jobs.BUILDERS, jobs.REPORT, quick)

    async def schedules() -> None:
        order.append("schedules")

    async def story() -> None:
        with store_only():
            monkeypatch.setattr(jobs, "load_user", lambda _uid: asyncio.sleep(0, ADMIN))
            await jobs.enqueue(ADMIN, jobs.REPORT, {"path": "p", "options": {}})
            stop = asyncio.Event()
            lane = asyncio.create_task(jobs.run_lane(stop, idle_seconds=0.01, between=schedules))
            for _ in range(200):
                if "schedules" in order:
                    break
                await asyncio.sleep(0.01)
            stop.set()
            await lane

    asyncio.run(story())
    assert order[0] == "job" and "schedules" in order
