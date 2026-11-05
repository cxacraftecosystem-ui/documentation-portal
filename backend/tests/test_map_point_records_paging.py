from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import map_points as routes


class _Rows:
    def __init__(self, count: int) -> None:
        start = datetime(2026, 1, 1, tzinfo=UTC)
        self.rows = [
            SimpleNamespace(id=f"a{index:03d}", name=f"Artisan {index}", place="Bagru", status="APPROVED", createdAt=start + timedelta(minutes=index))
            for index in range(count)
        ]

    async def find_many(self, *, skip: int = 0, take: int, **_: Any) -> list[Any]:
        ordered = sorted(self.rows, key=lambda row: (-row.createdAt.timestamp(), row.id))
        return ordered[skip : skip + take]


@pytest.mark.asyncio
async def test_a_point_pages_to_its_last_record(monkeypatch):
    total = routes._STUB_CAP * 2 + 7
    table = _Rows(total)

    async def wheres(*_: Any, **__: Any) -> dict[str, dict[str, Any]]:
        return {"artisans": {}}

    async def narrowing(*_: Any, **__: Any) -> dict[str, dict[str, Any]]:
        return {"artisans": {}}

    monkeypatch.setattr(routes, "build_record_wheres", wheres)
    monkeypatch.setattr(routes, "_capture_narrowing", narrowing)
    monkeypatch.setattr(routes, "_delegate", lambda bucket: table)
    seen: list[str] = []
    offset: int | None = 0
    while offset is not None:
        page = await routes.point_records(
            "capture:1:10_20",
            current_user=SimpleNamespace(id="u1"),
            types=["artisans"],
            workshopIds=None,
            offset=offset,
        )
        seen.extend(item["id"] for item in page["items"])
        offset = page["nextOffset"]
    assert len(seen) == len(set(seen)) == total
    assert "a000" in seen
