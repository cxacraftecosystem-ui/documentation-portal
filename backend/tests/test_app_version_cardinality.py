from __future__ import annotations

import asyncio
import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db

from app.services import app_versions


@pytest.fixture(autouse=True)
def _fresh_cache():
    app_versions._last_write.clear()
    yield
    app_versions._last_write.clear()


def _scope(code: str, name: str = "0.0.1") -> dict:
    headers = {"x-app-platform": "android", "x-app-version-code": code, "x-app-version": name}
    return {"type": "http", "headers": [(k.encode(), v.encode("latin-1")) for k, v in headers.items()]}


def test_the_throttle_cache_stays_bounded_whatever_codes_a_caller_invents():
    for code in range(1, 60_001):
        app_versions.should_write("user_1", "android", code, now=1000.0)
    assert len(app_versions._last_write) <= 20_000


def test_a_huge_version_name_is_cut_down_before_it_is_stored():
    build = app_versions.build_of(_scope("31", "x" * 20_000))
    assert build is not None
    assert len(build[2] or "") <= 64


def test_an_implausible_version_code_is_not_recorded():
    assert app_versions.build_of(_scope("999999999")) is None


def test_detached_writes_are_capped_when_the_database_is_slow(monkeypatch):
    release = asyncio.Event()
    started: list[int] = []

    async def slow_record(user_id: str, platform: str, code: int, name: str | None, **_k: Any) -> None:
        started.append(code)
        await release.wait()

    monkeypatch.setattr(app_versions, "record", slow_record)

    async def inner(scope: dict, receive: Any, send: Any) -> None:
        app_versions.note_user(SimpleNamespace(scope=scope), "user_1")

    middleware = app_versions.AppVersionMiddleware(inner)

    async def go() -> int:
        for code in range(1, 2001):
            await middleware(_scope(str(code)), None, None)
        await asyncio.sleep(0)
        peak = len(app_versions._pending)
        release.set()
        for _ in range(5):
            await asyncio.sleep(0)
        return peak

    peak = asyncio.run(go())
    assert peak <= 64
    assert len(started) <= 64


@needs_db
def test_rows_per_account_and_platform_stay_bounded_and_keep_the_newest():
    from prisma import Prisma

    from app.core.security import hash_password

    async def go() -> tuple[int, set[int]]:
        client = Prisma()
        await client.connect()
        user = await client.user.create(
            data={
                "email": f"app-version-cap-{uuid.uuid4().hex[:10]}@example.org",
                "name": "App version cap",
                "passwordHash": hash_password("unused"),
            }
        )
        try:
            for code in range(1, 121):
                await app_versions.record(user.id, "android", code, f"0.0.{code}", client=client)
            rows = await client.query_raw(
                'SELECT "versionCode" FROM "AppClientVersion" WHERE "userId" = $1', user.id
            )
            return len(rows), {int(r["versionCode"]) for r in rows}
        finally:
            await client.user.delete(where={"id": user.id})
            await client.disconnect()

    count, codes = asyncio.run(go())
    assert count <= 20
    assert 120 in codes
