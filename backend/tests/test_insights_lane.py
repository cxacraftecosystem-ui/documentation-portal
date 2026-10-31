"""The translation lane (0.0.5): English and summaries beside transcription, and the self-clearing hold.

THE SAME FILE IN BOTH PORTALS. ``media_queue`` is each portal's own, but the lane, the hold and the
revive were written identically into both, and this pins them with a fake job table: nothing here
reaches a database or Gemini.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import media_queue as mq
from app.transcription.transcript import gemini


class _Jobs:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.finds: list[dict[str, Any]] = []
        self.updates: list[tuple[str, dict[str, Any]]] = []
        self.update_manys: list[dict[str, Any]] = []

    async def find_many(self, *, where: dict[str, Any], take: int | None = None, **_kw: Any) -> list[Any]:
        self.finds.append(where)
        rows = [r for r in self.rows if r.status == "QUEUED"]
        jt = where.get("jobType")
        if isinstance(jt, str):
            rows = [r for r in rows if r.jobType == jt]
        return rows[:take] if take else rows

    async def update(self, *, where: dict[str, Any], data: dict[str, Any]) -> Any:
        self.updates.append((where["id"], data))
        return SimpleNamespace(id=where["id"], **data)

    async def update_many(self, *, where: dict[str, Any], data: dict[str, Any]) -> int:
        self.update_manys.append(where)
        return 2


def _job(i: int, kind: str = mq.TRANSCRIPT_INSIGHTS) -> Any:
    return SimpleNamespace(id=f"j{i}", jobType=kind, status="QUEUED", attempts=1, mediaFileId=f"m{i}")


@pytest.fixture
def lane(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    jobs = _Jobs([_job(1), _job(2), _job(3), _job(4, mq.TRANSCRIPTION)])
    seen = SimpleNamespace(processed=[], live=0, peak=0, failed=[])

    async def lock(job_id: str, worker_id: str) -> Any:
        return next(r for r in jobs.rows if r.id == job_id)

    async def process(job: Any, _settings: Any) -> None:
        seen.live += 1
        seen.peak = max(seen.peak, seen.live)
        await asyncio.sleep(0.01)
        seen.live -= 1
        seen.processed.append(job.id)

    async def fail(job_id: str, exc: Exception) -> None:
        seen.failed.append(job_id)

    async def settings_row() -> Any:
        return SimpleNamespace()

    monkeypatch.setattr(mq, "db", SimpleNamespace(mediaprocessingjob=jobs))
    monkeypatch.setattr(mq, "_lock_job", lock)
    monkeypatch.setattr(mq, "_process_job", process)
    monkeypatch.setattr(mq, "_handle_job_failure", fail)
    monkeypatch.setattr(mq, "load_app_settings", settings_row)
    monkeypatch.setattr(mq.transcript_insights, "enabled", lambda: True)
    monkeypatch.setattr(mq.transcript_pipeline, "holds_translation", lambda _row: False)
    monkeypatch.setattr(mq, "_last_revive", float("-inf"))
    return SimpleNamespace(jobs=jobs, seen=seen)


def test_the_lane_takes_only_translation_jobs_and_runs_them_side_by_side(lane: SimpleNamespace) -> None:
    result = asyncio.run(mq.process_next_insights_jobs(settings=SimpleNamespace(media_queue_insights_concurrency=2)))
    assert result == {"processed": 2, "succeeded": 2, "failed": 0}
    assert lane.seen.processed and all(j in {"j1", "j2", "j3"} for j in lane.seen.processed), "never a transcription"
    assert lane.seen.peak == 2, "two translations were in flight at once"
    assert lane.jobs.finds[-1]["jobType"] == mq.TRANSCRIPT_INSIGHTS


def test_the_lane_waits_while_translation_is_switched_off_or_out_of_budget(
    lane: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(mq.transcript_pipeline, "holds_translation", lambda _row: True)
    assert asyncio.run(mq.process_next_insights_jobs())["processed"] == 0
    monkeypatch.setattr(mq.transcript_pipeline, "holds_translation", lambda _row: False)
    monkeypatch.setattr(mq.transcript_insights, "enabled", lambda: False)
    assert asyncio.run(mq.process_next_insights_jobs())["processed"] == 0
    assert lane.seen.processed == []


def test_jobs_that_failed_only_because_gemini_was_busy_or_unpaid_are_revived_once(lane: SimpleNamespace) -> None:
    assert asyncio.run(mq.revive_held_insights(now=1_000.0)) == 2
    (where,) = lane.jobs.update_manys
    assert where["jobType"] == mq.TRANSCRIPT_INSIGHTS and where["status"] == mq.FAILED
    assert set(where["error"]["in"]) == {
        gemini.MESSAGES[gemini.THROTTLED],
        gemini.MESSAGES[gemini.UNREACHABLE],
        gemini.MESSAGES[gemini.BILLING],
    }
    assert asyncio.run(mq.revive_held_insights(now=1_010.0)) == 0, "at most every ten minutes"


def test_a_held_job_goes_back_to_the_queue_without_spending_an_attempt(lane: SimpleNamespace) -> None:
    job = SimpleNamespace(id="j9", attempts=3)
    before = datetime.now(UTC)
    asyncio.run(mq._hold_insights_job(job, gemini.MESSAGES[gemini.BILLING], 900))
    ((job_id, data),) = lane.jobs.updates
    assert job_id == "j9" and data["status"] == mq.QUEUED and data["attempts"] == 2
    assert data["runAfter"] >= before + timedelta(seconds=899)
    assert data["lockedAt"] is None and data["lockedBy"] is None


def test_the_main_loop_leaves_translation_to_the_lane(lane: SimpleNamespace, monkeypatch: pytest.MonkeyPatch) -> None:
    async def nothing(*_a: Any, **_k: Any) -> int:
        return 0

    monkeypatch.setattr(mq, "recover_stale_processing_jobs", nothing)
    monkeypatch.setattr(mq, "transcription_paused", lambda _row: False)
    monkeypatch.setattr(mq, "within_processing_window", lambda _row: True)
    asyncio.run(mq.process_next_media_jobs(limit=5, settings=SimpleNamespace(media_queue_batch_size=5), skip_insights=True))
    jt = lane.jobs.finds[-1]["jobType"]
    assert jt == {"not": mq.TRANSCRIPT_INSIGHTS} or mq.TRANSCRIPT_INSIGHTS in jt.get("not_in", [])


def test_gemini_402_is_billing_and_holds_rather_than_fails() -> None:
    err = gemini.GeminiError(gemini.BILLING, status=402)
    assert err.retry and err.hold_seconds == 900
    assert "topped up" in str(err)
    assert gemini.GeminiError(gemini.THROTTLED).hold_seconds == 180
    assert gemini.GeminiError(gemini.KEY).hold_seconds is None
    assert gemini._worst([gemini.THROTTLED, gemini.BILLING]) == gemini.BILLING
