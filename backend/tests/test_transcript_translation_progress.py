from __future__ import annotations

import asyncio
from typing import Any

from app.services import transcript_pipeline


def test_translation_progress_counts_only_turns_that_need_english(monkeypatch):
    asked: list[Any] = []

    class FakeDb:
        async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
            asked.append((sql, args))
            return [{"id": "v1", "done": 7, "total": 10}, {"id": "v2", "done": 5, "total": 3}]

    monkeypatch.setattr(transcript_pipeline, "db", FakeDb())
    progress = asyncio.run(transcript_pipeline.translation_progress(["v1", "v2"]))
    assert progress == {"v1": (7, 10), "v2": (3, 3)}
    assert "ALREADY_ENGLISH" in asked[0][0] and asked[0][1] == (["v1", "v2"],)
    assert asyncio.run(transcript_pipeline.translation_progress([])) == {}
