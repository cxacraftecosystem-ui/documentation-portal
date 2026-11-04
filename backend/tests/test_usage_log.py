from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.services import usage_log


class _Table:
    def __init__(self) -> None:
        self.rows: list[dict[str, Any]] = []
        self.deleted: list[Any] = []

    async def create_many(self, data: list[dict[str, Any]]) -> int:
        self.rows.extend(data)
        return len(data)

    async def delete_many(self, where: dict[str, Any]) -> int:
        self.deleted.append(where)
        return 0


def _app() -> FastAPI:
    app = FastAPI()
    app.add_middleware(usage_log.TimingMiddleware)

    @app.get("/api/data/media/{media_id}/download")
    async def download(media_id: str) -> dict[str, str]:
        return {"id": media_id}

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    return app


def test_a_request_is_timed_under_its_route_template_never_its_path(monkeypatch):
    usage_log._buffer.clear()
    table = _Table()
    monkeypatch.setattr(usage_log, "db", SimpleNamespace(usageevent=table))
    client = TestClient(_app())
    client.get("/api/data/media/secret-id/download", headers={"x-client-app": "android"})
    client.get("/health")
    client.get("/nowhere/abc")
    asyncio.run(usage_log.flush_all())
    routes = [row["routeTemplate"] for row in table.rows]
    assert routes == ["/api/data/media/{media_id}/download", usage_log.UNMATCHED_ROUTE]
    first = table.rows[0]
    assert first["statusCode"] == 200 and first["clientApp"] == "android" and first["durationMs"] >= 0
    assert "secret-id" not in repr(table.rows)
    assert set(first) == {"routeTemplate", "method", "statusCode", "durationMs", "clientApp"}
    assert table.rows[1]["statusCode"] == 404


def test_the_buffer_is_bounded_and_an_unknown_client_is_api(monkeypatch):
    usage_log._buffer.clear()
    for _ in range(usage_log.BUFFER_CEILING + 10):
        usage_log.record(route="/x", method="GET", status_code=200, duration_ms=1, app_name="api")
    assert usage_log.pending() == usage_log.BUFFER_CEILING
    usage_log._buffer.clear()
    assert usage_log.client_app({"headers": [(b"x-client-app", b"toaster")]}) == "api"


def test_old_timings_are_purged_at_most_every_six_hours(monkeypatch):
    table = _Table()
    monkeypatch.setattr(usage_log, "db", SimpleNamespace(usageevent=table))
    monkeypatch.setitem(usage_log._last_purge, "at", float("-inf"))
    asyncio.run(usage_log.purge())
    asyncio.run(usage_log.purge())
    assert len(table.deleted) == 1
