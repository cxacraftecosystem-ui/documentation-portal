from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services import app_settings, transcribe_choice
from app.transcription.chain import hourly_rate
from app.transcription.providers.elevenlabs import ElevenLabs

logger = logging.getLogger(__name__)

GEMINI_USD_PER_MINUTE = 0.002
ASSUMED_BYTES_PER_SECOND = 8_000
QUEUED = "queued"
FAILED = "failed"
UNTRANSCRIBED = "untranscribed"
BATCH = 25
INTERVAL_SECONDS = 900
ESTIMATE_TTL_SECONDS = 600
OFF_MESSAGE = "Backfill is off. Turn it on in Settings first."
CONFIRM_MESSAGE = "Confirm the estimate to start the backfill."

_last_run = {"at": float("-inf")}
_estimate_cache: dict[str, Any] = {}


class BackfillOff(Exception):
    def __init__(self) -> None:
        super().__init__(OFF_MESSAGE)


class ConfirmationNeeded(Exception):
    def __init__(self) -> None:
        super().__init__(CONFIRM_MESSAGE)


def stt_usd_per_minute() -> float:
    from app.services import ai

    return hourly_rate(ElevenLabs, keyterms=bool(ai.craft_keyterms())) / 60


@dataclass
class Candidate:
    media_id: str
    kind: str
    recorded_at: Any
    minutes: float

    @property
    def estimate_usd(self) -> float:
        return round(self.minutes * (stt_usd_per_minute() + GEMINI_USD_PER_MINUTE), 6)


def minutes_of(duration_ms: Any, size_bytes: Any) -> float:
    if duration_ms:
        return max(0.0, float(duration_ms) / 60_000)
    return max(0.0, float(size_bytes or 0) / ASSUMED_BYTES_PER_SECOND / 60)


def next_batch(
    candidates: list[Candidate], *, limit: int, budget_left_usd: float | None
) -> list[Candidate]:
    ordered = sorted(candidates, key=lambda c: (str(c.recorded_at or ""), c.media_id))
    chosen: list[Candidate] = []
    spent = 0.0
    for candidate in ordered:
        if len(chosen) >= limit:
            break
        if budget_left_usd is not None and spent + candidate.estimate_usd > budget_left_usd:
            break
        chosen.append(candidate)
        spent += candidate.estimate_usd
    return chosen


CANDIDATES_SQL = (
    'SELECT m."id", m."recordedAt", m."sizeBytes"::float8 AS size, m."transcriptStatus", '
    '(SELECT j."status"::text FROM "MediaProcessingJob" j WHERE j."mediaFileId" = m."id" '
    "AND j.\"jobType\" = 'TRANSCRIPTION' ORDER BY j.\"createdAt\" DESC LIMIT 1) AS job, "
    '(SELECT r."durationMs" FROM "QuestionnaireRecording" r WHERE r."mediaFileId" = m."id") '
    'AS duration FROM "MediaFile" m WHERE m."mediaType"::text IN (\'AUDIO\', \'VIDEO\') '
    "AND m.\"removedAt\" IS NULL AND btrim(coalesce(m.\"transcriptText\", '')) = ''"
)


async def collect() -> tuple[list[Candidate], int]:
    from app.services import transcript_portal

    rows = await db.query_raw(CANDIDATES_SQL)
    candidates: list[Candidate] = []
    refused = 0
    for row in rows:
        job = row.get("job")
        if job == "PROCESSING":
            continue
        kind = QUEUED if job == "QUEUED" else FAILED if job == "FAILED" else UNTRANSCRIBED
        media = await db.mediafile.find_unique(where={"id": row["id"]})
        if media is None:
            continue
        may, _reason = await transcript_portal.may_send(media)
        if not may:
            refused += 1
            continue
        candidates.append(
            Candidate(
                str(row["id"]),
                kind,
                row.get("recordedAt"),
                minutes_of(row.get("duration"), row.get("size")),
            )
        )
    return candidates, refused


def summarise(candidates: list[Candidate], refused: int) -> dict[str, Any]:
    minutes = sum(c.minutes for c in candidates)
    return {
        "recordings": len(candidates),
        "queued": sum(1 for c in candidates if c.kind == QUEUED),
        "failed": sum(1 for c in candidates if c.kind == FAILED),
        "untranscribed": sum(1 for c in candidates if c.kind == UNTRANSCRIBED),
        "leftOutForConsent": refused,
        "minutes": round(minutes, 1),
        "hours": round(minutes / 60, 1),
        "costUsd": round(sum(c.estimate_usd for c in candidates), 2),
    }


async def estimate(*, fresh: bool = False, now: float | None = None) -> dict[str, Any]:
    now = time.monotonic() if now is None else now
    cached = _estimate_cache.get("value")
    if not fresh and cached is not None and now - _estimate_cache["at"] < ESTIMATE_TTL_SECONDS:
        return cached
    candidates, refused = await collect()
    value = {**summarise(candidates, refused), "computedAt": datetime.now(UTC).isoformat()}
    _estimate_cache.update(value=value, at=now)
    return value


def enabled_of(row: Any) -> bool:
    return bool(getattr(row, "transcriptBackfillEnabled", False)) if row is not None else False


async def progress(row: Any | None = None) -> dict[str, Any]:
    row = row if row is not None else await app_settings.load_app_settings()
    marked = {"transcribeRequestSource": transcribe_choice.BACKFILL}
    try:
        total = await db.mediafile.count(where=marked)
        waiting = await db.mediafile.count(
            where={**marked, "OR": [{"transcriptText": None}, {"transcriptText": ""}]}
        )
    except Exception:
        logger.exception("transcript backfill: progress unreadable")
        total = waiting = 0
    last = getattr(row, "transcriptBackfillLastRunAt", None) if row is not None else None
    return {
        "enabled": enabled_of(row),
        "queued": total,
        "done": max(0, total - waiting),
        "waiting": waiting,
        "lastRunAt": last.isoformat() if isinstance(last, datetime) else None,
    }


async def set_enabled(on: bool, *, confirmed: bool, updated_by_id: str | None) -> bool:
    if on and not confirmed:
        raise ConfirmationNeeded()
    await app_settings.get_or_create_app_settings(updated_by_id)
    row = await db.appsetting.update(
        where={"id": app_settings.SINGLETON_ID},
        data={
            "transcriptBackfillEnabled": bool(on),
            "transcriptBackfillById": updated_by_id if on else None,
            "updatedById": updated_by_id,
        },
    )
    return enabled_of(row)


async def _open_backfill_jobs() -> int:
    return await db.mediaprocessingjob.count(
        where={
            "jobType": transcribe_choice.TRANSCRIPTION,
            "status": {"in": ["QUEUED", "PROCESSING"]},
            "mediaFile": {"is": {"transcribeRequestSource": transcribe_choice.BACKFILL}},
        }
    )


async def run_batch(*, limit: int = BATCH) -> dict[str, Any]:
    from app.services import media_queue, transcript_pipeline

    row = await app_settings.load_app_settings()
    if not enabled_of(row):
        raise BackfillOff()
    if app_settings.transcription_paused(row):
        return {"queued": 0, "reason": "paused"}
    room = limit - await _open_backfill_jobs()
    if room <= 0:
        return {"queued": 0, "reason": "busy"}
    candidates, _refused = await collect()
    meter = await transcript_pipeline.meter(row)
    left = None if meter.monthly_usd is None else max(0.0, meter.monthly_usd - meter.spent_usd)
    chosen = next_batch(
        [c for c in candidates if c.kind != QUEUED], limit=room, budget_left_usd=left
    )
    requested_by = getattr(row, "transcriptBackfillById", None)
    queued = 0
    for candidate in chosen:
        media = await db.mediafile.find_unique(where={"id": candidate.media_id})
        if media is None:
            continue
        jobs = await media_queue.enqueue_media_processing_jobs(
            media, [transcribe_choice.TRANSCRIPTION], requested_by
        )
        if jobs:
            await transcribe_choice.record(candidate.media_id, True, transcribe_choice.BACKFILL)
            queued += 1
    await db.appsetting.update(
        where={"id": app_settings.SINGLETON_ID},
        data={"transcriptBackfillLastRunAt": datetime.now(UTC)},
    )
    _estimate_cache.clear()
    reason = "budget" if not chosen and candidates and left is not None else None
    return {"queued": queued, "reason": reason}


async def maybe_run(*, now: float | None = None) -> dict[str, Any] | None:
    now = time.monotonic() if now is None else now
    if now - _last_run["at"] < INTERVAL_SECONDS:
        return None
    _last_run["at"] = now
    try:
        return await run_batch()
    except BackfillOff:
        return None
    except Exception:
        logger.exception("transcript backfill pass failed; it runs again later")
        return None
