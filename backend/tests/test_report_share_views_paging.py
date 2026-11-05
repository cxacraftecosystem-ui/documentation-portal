from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import data_report_share as routes
from app.services.datareport import share_links


class _Views:
    def __init__(self, count: int) -> None:
        start = datetime(2026, 11, 1, tzinfo=UTC)
        self.rows = [
            SimpleNamespace(id=f"v{index:04d}", openedAt=start + timedelta(minutes=index), viewer=None, ipHash="abcdef123456", userAgent="Firefox", shareId="s1")
            for index in range(count)
        ]

    async def count(self, *, where: dict[str, Any]) -> int:
        return len(self.rows)

    async def find_many(self, *, skip: int = 0, take: int, **_: Any) -> list[Any]:
        ordered = sorted(self.rows, key=lambda row: (-row.openedAt.timestamp(), row.id))
        return ordered[skip : skip + take]


@pytest.mark.asyncio
async def test_every_view_of_a_link_is_reachable(monkeypatch):
    total = share_links.VIEWS_LIST_MAX + 31
    monkeypatch.setattr(routes, "db", SimpleNamespace(datareportshareview=_Views(total)))

    async def owned(share_id: str, uid: str) -> Any:
        return SimpleNamespace(id=share_id, ownerId=uid)

    monkeypatch.setattr(routes, "_owned", owned)
    user = SimpleNamespace(id="u1")
    first = await routes.share_views("s1", current_user=user)
    assert first["truncated"] is True and len(first["items"]) == share_links.VIEWS_LIST_MAX
    rest = await routes.share_views("s1", offset=len(first["items"]), current_user=user)
    assert rest["truncated"] is False and len(rest["items"]) == 31
    ids = [row["id"] for row in first["items"] + rest["items"]]
    assert len(set(ids)) == total and ids[-1] == "v0000"
