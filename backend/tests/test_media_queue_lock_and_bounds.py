import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from app.services import media_queue
from app.services.s3 import ObjectTooLarge


class _Jobs:
    def __init__(self, jobs: list[Any]) -> None:
        self.jobs = jobs
        self.updates: list[dict[str, Any]] = []
        self.update_many_calls: list[dict[str, Any]] = []

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return list(self.jobs)

    async def find_unique(self, **kwargs: Any) -> Any:
        wanted = (kwargs.get("where") or {}).get("id")
        return next((job for job in self.jobs if job.id == wanted), None)

    async def update(self, **kwargs: Any) -> Any:
        self.updates.append(kwargs)
        return None

    async def update_many(self, **kwargs: Any) -> int:
        self.update_many_calls.append(kwargs)
        return 1


class _Media:
    async def update_many(self, **_kwargs: Any) -> int:
        return 1

    async def update(self, **_kwargs: Any) -> Any:
        return None


class _DB:
    def __init__(self, jobs: list[Any]) -> None:
        self.mediaprocessingjob = _Jobs(jobs)
        self.mediafile = _Media()


def _job(**overrides: Any) -> SimpleNamespace:
    base = {
        "id": "job-1",
        "jobType": media_queue.MEASUREMENT,
        "mediaFileId": "m-1",
        "attempts": 1,
        "maxAttempts": 3,
        "lockedBy": "queue-service",
        "lockedAt": datetime(2026, 9, 3, tzinfo=UTC),
        "requestedById": "usr_dummy",
        "productId": None,
        "toolId": None,
        "mediaFile": SimpleNamespace(
            id="m-1",
            objectKey="media/usr_dummy/grid.jpg",
            originalFilename="grid.jpg",
            mimeType="image/jpeg",
            sizeBytes=1024,
            uploadedById="usr_dummy",
            extraMetadata=None,
            transcriptText=None,
        ),
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def _quiet_drain(monkeypatch, db: _DB) -> None:
    async def _nothing(*_a: Any, **_k: Any) -> Any:
        return 0

    async def _settings_row() -> Any:
        return SimpleNamespace()

    monkeypatch.setattr(media_queue, "db", db)
    monkeypatch.setattr(media_queue, "recover_stale_processing_jobs", _nothing)
    monkeypatch.setattr(media_queue, "load_app_settings", _settings_row)
    monkeypatch.setattr(media_queue, "transcription_paused", lambda _row: False)
    monkeypatch.setattr(media_queue, "within_processing_window", lambda _row: True)
    monkeypatch.setattr(media_queue.transcript_insights, "enabled", lambda: False)
    monkeypatch.setattr(media_queue.transcript_pipeline, "holds_translation", lambda _row: False)


def test_a_job_still_running_keeps_its_lock_fresh_so_recovery_cannot_run_it_twice(monkeypatch):
    job = _job(lockedBy=None, lockedAt=None)
    db = _DB([job])
    _quiet_drain(monkeypatch, db)

    async def _lock(job_id: str, worker_id: str) -> Any:
        return _job(lockedBy=worker_id, lockedAt=datetime.now(UTC))

    async def _long_job(_job: Any, _settings: Any) -> None:
        await asyncio.sleep(0.3)

    monkeypatch.setattr(media_queue, "_lock_job", _lock)
    monkeypatch.setattr(media_queue, "_process_job", _long_job)
    monkeypatch.setattr(media_queue, "LOCK_HEARTBEAT_SECONDS", 0.02, raising=False)

    asyncio.run(media_queue.process_next_media_jobs(limit=1, worker_id="queue-service", settings=SimpleNamespace(media_queue_batch_size=1)))

    beats = [
        call
        for call in db.mediaprocessingjob.update_many_calls
        if "lockedAt" in call.get("data", {}) and call["data"]["lockedAt"] is not None
    ]
    assert beats, "a job running longer than the stale window would be recovered and claimed again"
    for call in beats:
        assert call["where"]["id"] == "job-1"
        assert call["where"]["lockedBy"] == "queue-service"
        assert call["where"]["status"] == media_queue.PROCESSING


def test_the_heartbeat_stops_when_the_job_finishes(monkeypatch):
    db = _DB([_job()])
    _quiet_drain(monkeypatch, db)

    async def _lock(job_id: str, worker_id: str) -> Any:
        return _job(lockedBy=worker_id)

    async def _quick(_job: Any, _settings: Any) -> None:
        return None

    monkeypatch.setattr(media_queue, "_lock_job", _lock)
    monkeypatch.setattr(media_queue, "_process_job", _quick)
    monkeypatch.setattr(media_queue, "LOCK_HEARTBEAT_SECONDS", 0.01, raising=False)

    async def _drain_then_wait() -> None:
        await media_queue.process_next_media_jobs(limit=1, worker_id="queue-service", settings=SimpleNamespace(media_queue_batch_size=1))
        before = len(db.mediaprocessingjob.update_many_calls)
        await asyncio.sleep(0.1)
        assert len(db.mediaprocessingjob.update_many_calls) == before

    asyncio.run(_drain_then_wait())


def _recover(monkeypatch, jobs: list[Any]) -> _DB:
    db = _DB(jobs)
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue.recover_stale_processing_jobs())
    return db


def test_a_stale_job_out_of_attempts_is_failed_not_requeued_forever(monkeypatch):
    db = _recover(monkeypatch, [_job(jobType=media_queue.MEASUREMENT, attempts=3, maxAttempts=3)])
    written = db.mediaprocessingjob.updates[0]["data"]
    assert written["status"] == media_queue.FAILED
    assert written["lockedAt"] is None
    assert written["lockedBy"] is None
    assert written["completedAt"] is not None


def test_a_stale_job_with_attempts_left_is_requeued(monkeypatch):
    db = _recover(monkeypatch, [_job(jobType=media_queue.MEASUREMENT, attempts=1, maxAttempts=3)])
    assert db.mediaprocessingjob.updates[0]["data"]["status"] == media_queue.QUEUED


def test_a_hand_inserted_row_with_zero_max_attempts_is_not_immortal(monkeypatch):
    db = _recover(monkeypatch, [_job(jobType=media_queue.MEASUREMENT, attempts=1, maxAttempts=0)])
    assert db.mediaprocessingjob.updates[0]["data"]["status"] == media_queue.FAILED


HUGE = 5 * 1024 * 1024 * 1024


def test_a_queued_measurement_never_reads_an_oversized_object_into_memory(monkeypatch):
    reads: list[Any] = []
    analysed: list[int] = []
    applied: list[dict[str, Any]] = []

    def _fetch(object_key: str, *args: Any, **kwargs: Any) -> bytes:
        limit = kwargs.get("max_bytes")
        reads.append(limit)
        if limit is not None and limit < HUGE:
            raise ObjectTooLarge(object_key, HUGE, limit)
        return b"\xff\xd8" + b"\x00" * 64

    async def _analyse(content: bytes, *args: Any, **kwargs: Any) -> dict[str, Any]:
        analysed.append(len(content))
        return {"available": True, "status": "COMPLETED", "analysis": None}

    async def _apply(job: Any, result: dict[str, Any]) -> None:
        applied.append(result)

    monkeypatch.setattr(media_queue, "get_object_bytes", _fetch)
    monkeypatch.setattr(media_queue, "head_object", lambda _key: None, raising=False)
    monkeypatch.setattr(media_queue, "analyze_measurement_image_bytes", _analyse)
    monkeypatch.setattr(media_queue, "_apply_measurement_result", _apply)

    asyncio.run(media_queue._process_job(_job(), SimpleNamespace()))

    assert None not in reads, "the measurement fetch had no byte ceiling"
    assert analysed == [], "an object larger than any photograph reached the paid model"
    assert applied and str(applied[0]["status"]).upper() == "UNAVAILABLE"
    assert "too large" in applied[0]["message"]
