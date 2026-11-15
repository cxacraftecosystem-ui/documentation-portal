import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from app.services import media_queue


class _Jobs:
    def __init__(self, jobs: list[Any]) -> None:
        self.jobs = jobs
        self.updates: list[dict[str, Any]] = []

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return list(self.jobs)

    async def find_unique(self, **kwargs: Any) -> Any:
        wanted = (kwargs.get("where") or {}).get("id")
        return next((job for job in self.jobs if job.id == wanted), None)

    async def update(self, **kwargs: Any) -> Any:
        self.updates.append(kwargs)
        return None


class _Media:
    def __init__(self) -> None:
        self.update_many_calls: list[dict[str, Any]] = []

    async def update_many(self, **kwargs: Any) -> int:
        self.update_many_calls.append(kwargs)
        return 1


class _DB:
    def __init__(self, jobs: list[Any]) -> None:
        self.mediaprocessingjob = _Jobs(jobs)
        self.mediafile = _Media()


def _job(**overrides: Any) -> SimpleNamespace:
    base = {
        "id": "job-1",
        "jobType": media_queue.TRANSCRIPTION,
        "mediaFileId": "m-1",
        "attempts": 3,
        "maxAttempts": 3,
        "lockedBy": "queue-service",
        "lockedAt": datetime(2026, 9, 3, tzinfo=UTC),
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def _wire(monkeypatch, jobs: list[Any]) -> _DB:
    db = _DB(jobs)
    monkeypatch.setattr(media_queue, "db", db)
    return db


def test_an_exhausted_transcription_job_marks_its_queued_recording_failed(monkeypatch):
    db = _wire(monkeypatch, [_job()])
    asyncio.run(media_queue._handle_job_failure("job-1", media_queue.JobFailure("The provider refused the file.")))
    assert db.mediaprocessingjob.updates[0]["data"]["status"] == media_queue.FAILED
    [call] = db.mediafile.update_many_calls
    assert call["where"]["id"] == "m-1"
    assert call["where"]["transcriptStatus"] == {"in": [media_queue.QUEUED, media_queue.PROCESSING]}
    assert call["where"]["OR"] == [{"transcriptText": None}, {"transcriptText": ""}]
    assert call["data"] == {"transcriptStatus": media_queue.FAILED, "transcriptError": "The provider refused the file."}


def test_an_unexpected_failure_shows_the_plain_reason_on_the_recording(monkeypatch):
    db = _wire(monkeypatch, [_job()])
    asyncio.run(media_queue._handle_job_failure("job-1", RuntimeError("socket at 10.0.0.4 reset")))
    [call] = db.mediafile.update_many_calls
    assert call["data"]["transcriptError"] == media_queue.UNEXPECTED_JOB_FAILURE
    assert "10.0.0.4" not in call["data"]["transcriptError"]


def test_a_job_with_attempts_left_leaves_the_recording_queued(monkeypatch):
    db = _wire(monkeypatch, [_job(attempts=1)])
    asyncio.run(media_queue._handle_job_failure("job-1", media_queue.JobFailure("Busy.")))
    assert db.mediaprocessingjob.updates[0]["data"]["status"] == media_queue.QUEUED
    assert db.mediafile.update_many_calls == []


def test_a_failed_measurement_job_leaves_the_transcript_columns_alone(monkeypatch):
    db = _wire(monkeypatch, [_job(jobType=media_queue.MEASUREMENT)])
    asyncio.run(media_queue._handle_job_failure("job-1", media_queue.JobFailure("No grid.")))
    assert db.mediaprocessingjob.updates[0]["data"]["status"] == media_queue.FAILED
    assert db.mediafile.update_many_calls == []


def test_an_interrupted_transcription_out_of_attempts_marks_its_recording_failed(monkeypatch):
    db = _wire(monkeypatch, [_job()])
    asyncio.run(media_queue.recover_stale_processing_jobs())
    assert db.mediaprocessingjob.updates[0]["data"]["status"] == media_queue.FAILED
    [call] = db.mediafile.update_many_calls
    assert call["data"] == {"transcriptStatus": media_queue.FAILED, "transcriptError": media_queue.INTERRUPTED_ERROR}
    assert "queue-service" not in call["data"]["transcriptError"]


def test_an_interrupted_job_with_attempts_left_is_requeued_and_the_recording_untouched(monkeypatch):
    db = _wire(monkeypatch, [_job(attempts=1)])
    asyncio.run(media_queue.recover_stale_processing_jobs())
    assert db.mediaprocessingjob.updates[0]["data"]["status"] == media_queue.QUEUED
    assert db.mediafile.update_many_calls == []


def test_a_recording_write_failure_never_abandons_the_sweep(monkeypatch):
    class _Exploding(_Media):
        async def update_many(self, **_kwargs: Any) -> int:
            raise RuntimeError("connection reset")

    db = _wire(monkeypatch, [_job(id="job-1", mediaFileId="m-1"), _job(id="job-2", mediaFileId="m-2")])
    db.mediafile = _Exploding()
    asyncio.run(media_queue.recover_stale_processing_jobs())
    assert {call["where"]["id"] for call in db.mediaprocessingjob.updates} == {"job-1", "job-2"}
