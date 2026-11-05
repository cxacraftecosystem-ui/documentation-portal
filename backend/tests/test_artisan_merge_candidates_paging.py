from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import artisan_merge


class _Artisans:
    def __init__(self, pairs: int) -> None:
        self.rows = []
        for index in range(pairs):
            for day in (1, 2):
                self.rows.append(
                    SimpleNamespace(id=f"p{index:03d}-{day}", name=f"Artisan {index:03d}", place="Bagru", phone=None, createdAt=datetime(2026, 6, day, tzinfo=UTC))
                )

    async def find_many(self, **_: Any) -> list[Any]:
        return sorted(self.rows, key=lambda row: row.createdAt)


@pytest.mark.asyncio
async def test_every_candidate_pair_is_reachable(monkeypatch):
    monkeypatch.setattr(artisan_merge, "db", SimpleNamespace(artisan=_Artisans(81)))
    seen: list[tuple[str, str]] = []
    offset: int | None = 0
    while offset is not None:
        page = await artisan_merge.merge_candidates(offset=offset)
        seen.extend((pair["keepId"], pair["mergeId"]) for pair in page["items"])
        offset = page["nextOffset"]
    assert len(seen) == len(set(seen)) == 81
    assert {"p080-1", "p080-2"} in [set(pair) for pair in seen]


@pytest.mark.asyncio
async def test_the_first_page_keeps_its_old_keys(monkeypatch):
    monkeypatch.setattr(artisan_merge, "db", SimpleNamespace(artisan=_Artisans(2)))
    page = await artisan_merge.merge_candidates()
    assert page["truncated"] is False and page["nextOffset"] is None and len(page["items"]) == 2
