from __future__ import annotations

import asyncio
import os
import socket
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import pytest

_DSN = os.environ.get("DATABASE_URL", "")
_PARTS = urlsplit(_DSN) if _DSN else None
_LOCAL = bool(_PARTS and _PARTS.hostname in {"localhost", "127.0.0.1", "::1"})


def _listening() -> bool:
    if not _LOCAL or _PARTS is None:
        return False
    try:
        with socket.create_connection((_PARTS.hostname, _PARTS.port or 5432), timeout=1.0):
            return True
    except OSError:
        return False


pytestmark = pytest.mark.skipif(
    not _listening(),
    reason="needs an exported DATABASE_URL naming a running Postgres on this machine (never backend/.env)",
)

JOB_TYPES = ("TRANSCRIPTION", "MEASUREMENT", "TRANSCRIPT_INSIGHTS", "AUDIO_ISOLATION", "SHA_VERIFY")


def _run(body: Any) -> Any:
    from app.core.db import db

    async def wrapper() -> Any:
        await db.connect()
        try:
            return await body()
        finally:
            await db.disconnect()

    return asyncio.run(wrapper())


async def _seed(*, media_type: str = "AUDIO", transcript: str | None = None) -> SimpleNamespace:
    from app.core.db import db
    from app.core.security import hash_password

    tag = uuid4().hex[:10]
    owner = await db.user.create(
        data={
            "email": f"supersede-{tag}@example.org",
            "name": "Supersede Owner",
            "role": "RESEARCHER",
            "passwordHash": hash_password("unused"),
        }
    )
    data: dict[str, Any] = {
        "originalFilename": f"supersede-{tag}.m4a",
        "mediaType": media_type,
        "mimeType": "audio/mp4" if media_type == "AUDIO" else "image/jpeg",
        "sizeBytes": 4096,
        "bucket": "ci",
        "objectKey": f"media/{owner.id}/{tag}/supersede.m4a",
        "uploadedById": owner.id,
    }
    if transcript:
        data["transcriptText"] = transcript
        data["transcriptStatus"] = "COMPLETED"
    media = await db.mediafile.create(data=data)
    return SimpleNamespace(owner=owner, media=media)


async def _cleanup(seed: SimpleNamespace) -> None:
    from app.core.db import db

    await db.mediafile.delete_many(where={"id": seed.media.id})
    await db.user.delete_many(where={"id": seed.owner.id})


async def _job(seed: SimpleNamespace, job_type: str, status: str, *, minutes_ago: int) -> Any:
    from app.core.db import db

    at = datetime.now(UTC) - timedelta(minutes=minutes_ago)
    data: dict[str, Any] = {
        "jobType": job_type,
        "status": status,
        "mediaFileId": seed.media.id,
        "requestedById": seed.owner.id,
        "attempts": 3,
        "maxAttempts": 3,
        "createdAt": at,
    }
    if status in {"FAILED", "COMPLETED"}:
        data["completedAt"] = at + timedelta(seconds=30)
    if status == "FAILED":
        data["error"] = "provider said no"
    return await db.mediaprocessingjob.create(data=data)


async def _rows(seed: SimpleNamespace, job_type: str, status: str | None = None) -> list[Any]:
    from app.core.db import db

    where: dict[str, Any] = {"mediaFileId": seed.media.id, "jobType": job_type}
    if status:
        where["status"] = status
    return await db.mediaprocessingjob.find_many(where=where, order={"createdAt": "asc"})


def _name(value: Any) -> str:
    return str(getattr(value, "value", value)).split(".")[-1]


@pytest.mark.parametrize("job_type", JOB_TYPES)
def test_a_new_job_reuses_the_newest_failure_and_completing_it_leaves_no_failure(job_type: str) -> None:
    from app.services import job_supersede, media_queue

    async def body() -> None:
        seed = await _seed()
        try:
            older = await _job(seed, job_type, "FAILED", minutes_ago=90)
            newer = await _job(seed, job_type, "FAILED", minutes_ago=60)
            job = await job_supersede.open_job(
                {"jobType": job_type, "status": "QUEUED", "priority": 60, "mediaFileId": seed.media.id}
            )
            assert job.id == newer.id
            assert _name(job.status) == "QUEUED"
            assert job.attempts == 0 and job.error is None and job.completedAt is None
            assert job.requestedById is None
            ids = [row.id for row in await _rows(seed, job_type)]
            assert ids == [newer.id] and older.id not in ids
            await media_queue._complete_job(job.id, {"status": "COMPLETED"})
            assert await _rows(seed, job_type, "FAILED") == []
            assert [_name(r.status) for r in await _rows(seed, job_type)] == ["COMPLETED"]
        finally:
            await _cleanup(seed)

    _run(body)


def test_a_reused_transcription_keeps_the_parts_it_already_paid_for(monkeypatch) -> None:
    from app.core.db import db
    from app.services import media_queue

    async def allowed(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(may_send=True, refusal=None, decision="GRANTED")

    monkeypatch.setattr(media_queue.recording_consent.gate, "verdict", allowed)

    async def body() -> None:
        seed = await _seed()
        try:
            failed = await _job(seed, "TRANSCRIPTION", "FAILED", minutes_ago=30)
            await db.transcriptionchunk.create(
                data={
                    "jobId": failed.id, "provider": "deepgram", "partIndex": 0, "partCount": 2,
                    "startMs": 0, "endMs": 600000, "status": "COMPLETED", "text": "part one",
                }
            )
            jobs = await media_queue.enqueue_media_processing_jobs(
                seed.media, ["TRANSCRIPTION"], seed.owner.id,
                SimpleNamespace(media_queue_job_max_attempts=3),
            )
            assert [job.id for job in jobs] == [failed.id]
            assert jobs[0].openKey == f"{seed.media.id}:TRANSCRIPTION"
            assert await db.transcriptionchunk.count(where={"jobId": failed.id}) == 1
            assert await _rows(seed, "TRANSCRIPTION", "FAILED") == []
        finally:
            await _cleanup(seed)

    _run(body)


def test_an_insights_retry_reuses_the_failed_row(monkeypatch) -> None:
    from app.services import transcript_insights

    async def no_refusal(*_a: Any, **_k: Any) -> None:
        return None

    monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
    monkeypatch.setattr(transcript_insights, "consent_refusal", no_refusal)

    async def body() -> None:
        seed = await _seed(transcript="Words spoken.")
        try:
            failed = await _job(seed, "TRANSCRIPT_INSIGHTS", "FAILED", minutes_ago=30)
            job = await transcript_insights.enqueue(seed.media.id, requested_by_id=seed.owner.id)
            assert job.id == failed.id and _name(job.status) == "QUEUED"
            assert job.requestedById == seed.owner.id
            assert await _rows(seed, "TRANSCRIPT_INSIGHTS", "FAILED") == []
        finally:
            await _cleanup(seed)

    _run(body)


def test_an_isolation_retry_reuses_the_failed_row(monkeypatch) -> None:
    from app.services import audio_isolation

    async def on() -> bool:
        return True

    monkeypatch.setattr(audio_isolation, "enabled", on)

    async def body() -> None:
        seed = await _seed()
        try:
            failed = await _job(seed, "AUDIO_ISOLATION", "FAILED", minutes_ago=30)
            job = await audio_isolation.enqueue_if_enabled(seed.media.id)
            assert job is not None and job.id == failed.id
            assert await _rows(seed, "AUDIO_ISOLATION", "FAILED") == []
        finally:
            await _cleanup(seed)

    _run(body)


def test_a_questionnaire_retry_reuses_the_failed_row(monkeypatch) -> None:
    from app.services import transcription_enqueue as qt

    async def allow(*_a: Any, **_k: Any) -> str:
        return qt.consent_gate.ALLOW

    monkeypatch.setattr(qt.consent_gate, "verdict", allow)

    async def body() -> None:
        seed = await _seed()
        try:
            failed = await _job(seed, "TRANSCRIPTION", "FAILED", minutes_ago=30)
            answer = await qt.enqueue(seed.media.id, "retry", requested_by_id=seed.owner.id)
            assert answer["jobId"] == failed.id
            assert await _rows(seed, "TRANSCRIPTION", "FAILED") == []
        finally:
            await _cleanup(seed)

    _run(body)


def test_a_completion_clears_a_failure_left_by_an_older_path() -> None:
    from app.core.db import db
    from app.services import job_supersede, media_queue

    async def body() -> None:
        seed = await _seed()
        try:
            await _job(seed, "MEASUREMENT", "FAILED", minutes_ago=60)
            fresh = await db.mediaprocessingjob.create(
                data={"jobType": "MEASUREMENT", "status": "PROCESSING", "mediaFileId": seed.media.id}
            )
            await media_queue._complete_job(fresh.id, {"status": "COMPLETED"})
            assert await _rows(seed, "MEASUREMENT", "FAILED") == []

            await _job(seed, "TRANSCRIPTION", "FAILED", minutes_ago=60)
            await db.mediaprocessingjob.create(
                data={"jobType": "TRANSCRIPTION", "status": "QUEUED", "mediaFileId": seed.media.id}
            )
            await job_supersede.complete_open_jobs(
                seed.media.id, "TRANSCRIPTION", {"status": "COMPLETED", "openKey": None}
            )
            assert await _rows(seed, "TRANSCRIPTION", "FAILED") == []
        finally:
            await _cleanup(seed)

    _run(body)


def test_failed_lists_and_counts_leave_out_superseded_failures(monkeypatch) -> None:
    from app.api.routes import media as media_routes
    from app.api.routes import transcription_admin
    from app.services import app_settings

    async def unpaused() -> bool:
        return False

    monkeypatch.setattr(app_settings, "load_transcription_paused", unpaused)

    async def body() -> None:
        seed = await _seed()
        try:
            superseded = await _job(seed, "MEASUREMENT", "FAILED", minutes_ago=90)
            await _job(seed, "MEASUREMENT", "COMPLETED", minutes_ago=60)
            await _job(seed, "TRANSCRIPTION", "COMPLETED", minutes_ago=90)
            genuine = await _job(seed, "TRANSCRIPTION", "FAILED", minutes_ago=60)
            stale = await _job(seed, "TRANSCRIPT_INSIGHTS", "FAILED", minutes_ago=90)
            await _job(seed, "TRANSCRIPT_INSIGHTS", "COMPLETED", minutes_ago=60)

            page = await media_routes.list_media_processing_jobs(
                current_user=seed.owner, statusFilter="FAILED", page=1, pageSize=100
            )
            ids = {item["id"] for item in page["items"]}
            assert ids == {genuine.id}
            assert page["total"] == 1

            listing = await media_routes.list_media_processing_jobs(
                current_user=seed.owner, statusFilter=None, page=1, pageSize=100
            )
            assert superseded.id not in {item["id"] for item in listing["items"]}
            assert stale.id not in {item["id"] for item in listing["items"]}
            await transcription_admin.retry_failed_transcriptions(
                transcription_admin.RetryFailedIn(dryRun=False, limit=2000),
                SimpleNamespace(email="ci@example.org"),
            )
            requeued = await _rows(seed, "TRANSCRIPTION", "QUEUED")
            assert [row.id for row in requeued] == [genuine.id]
        finally:
            await _cleanup(seed)

    _run(body)


def test_the_cleanup_script_removes_only_superseded_failures_and_is_idempotent() -> None:
    import importlib.util
    from pathlib import Path

    spec = importlib.util.spec_from_file_location(
        "clear_superseded_failed_jobs",
        Path(__file__).resolve().parents[1] / "scripts" / "clear_superseded_failed_jobs.py",
    )
    assert spec is not None and spec.loader is not None
    script = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(script)

    async def body() -> None:
        from app.core.db import db

        seed = await _seed()
        try:
            superseded = await _job(seed, "TRANSCRIPTION", "FAILED", minutes_ago=90)
            await _job(seed, "TRANSCRIPTION", "COMPLETED", minutes_ago=60)
            await _job(seed, "MEASUREMENT", "COMPLETED", minutes_ago=90)
            genuine = await _job(seed, "MEASUREMENT", "FAILED", minutes_ago=60)
            chunk = await db.transcriptionchunk.create(
                data={
                    "jobId": superseded.id, "provider": "deepgram", "partIndex": 0, "partCount": 1,
                    "startMs": 0, "endMs": 1000, "status": "FAILED",
                }
            )
            usage = await db.sttusage.create(
                data={"provider": "deepgram", "mediaFileId": seed.media.id, "jobId": superseded.id,
                      "outcome": "failed", "costUsd": 0.01}
            )

            dry = await script.clear(dry_run=True)
            assert dry["found"] >= 1 and dry["removed"] == 0
            assert await db.mediaprocessingjob.find_unique(where={"id": superseded.id}) is not None

            applied = await script.clear(dry_run=False)
            assert applied["removed"] >= 1
            assert await db.mediaprocessingjob.find_unique(where={"id": superseded.id}) is None
            assert await db.transcriptionchunk.find_unique(where={"id": chunk.id}) is None
            assert await db.mediaprocessingjob.find_unique(where={"id": genuine.id}) is not None
            assert await db.sttusage.find_unique(where={"id": usage.id}) is not None

            again = await script.clear(dry_run=False)
            assert again["found"] == 0 and again["removed"] == 0
            await db.sttusage.delete(where={"id": usage.id})
        finally:
            await _cleanup(seed)

    _run(body)
