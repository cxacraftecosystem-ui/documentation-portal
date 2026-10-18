"""This portal's half of the shared transcription service: the Prisma store behind the chain.

IDENTICAL IN BOTH PORTALS (the models it reads are the same, see shared/transcription.sha256's
neighbour test ``tests/test_transcription_parity.py``). It implements
``app.transcription.store.TranscriptionStore`` plus the reads the admin Transcription panel needs.

Every write here is bookkeeping: a failure is logged by the caller's bridge and never fails a
transcription. Nothing here calls a provider.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

from app.core.db import db
from app.transcription import chain
from app.transcription.audio import PartPlan
from app.transcription.chain import ChainState, UsageEvent
from app.transcription.contract import PROVIDER_IDS, Segment, SttResult
from app.transcription.providers import ADAPTERS
from prisma import Json

logger = logging.getLogger(__name__)

IST = timezone(timedelta(hours=5, minutes=30), name="IST")
PERIODS = ("DAY", "MONTH")
OPEN_STATUSES = ("QUEUED", "PROCESSING")


def period_start(period: str, now: datetime | None = None) -> datetime:
    """Start of the current India-time day or month, in UTC."""
    local = (now or datetime.now(UTC)).astimezone(IST)
    start = local.replace(hour=0, minute=0, second=0, microsecond=0)
    if period == "MONTH":
        start = start.replace(day=1)
    return start.astimezone(UTC)


def open_key(media_id: str, job_type: str) -> str:
    return f"{media_id}:{job_type}"


def _segments_json(segments: list[Segment]) -> list[dict[str, Any]]:
    return [segment.as_json() for segment in segments]


def _segments_from(raw: Any) -> list[Segment]:
    out: list[Segment] = []
    for item in raw if isinstance(raw, list) else []:
        if isinstance(item, dict):
            out.append(
                Segment(
                    int(item.get("startMs") or 0),
                    int(item.get("endMs") or 0),
                    item.get("speaker"),
                    str(item.get("text") or ""),
                )
            )
    return out


async def _usage_totals(provider: str, since: datetime) -> tuple[int, float]:
    rows = await db.query_raw(
        'SELECT COALESCE(SUM("audioMs"), 0)::bigint AS ms, COALESCE(SUM("costUsd"), 0)::float8 AS usd '
        'FROM "SttUsage" WHERE "provider" = $1 AND "createdAt" >= $2::timestamp AND "cacheHit" = false',
        provider,
        since.replace(tzinfo=None).isoformat(),
    )
    row = rows[0] if rows else {}
    return int(row.get("ms") or 0), float(row.get("usd") or 0.0)


class PrismaTranscriptionStore:
    async def load_state(self, *, job_id: str | None, providers: list[str]) -> ChainState:
        state = ChainState()
        now = datetime.now(UTC)
        try:
            for row in await db.sttproviderstate.find_many(where={"cooldownUntil": {"gt": now}}):
                state.cooldowns[(row.provider, row.keyRef)] = row.cooldownUntil
            for budget in await db.sttbudget.find_many(where={"provider": {"in": providers}}):
                used_ms, used_usd = await _usage_totals(
                    budget.provider, period_start(budget.period, now)
                )
                left: list[float] = []
                if budget.minutesCap is not None:
                    left.append(max(0.0, budget.minutesCap - used_ms / 60_000))
                adapter = ADAPTERS.get(budget.provider)
                if (
                    budget.spendCapUsd is not None
                    and adapter is not None
                    and adapter.price_per_hour_usd
                ):
                    left.append(
                        max(0.0, (budget.spendCapUsd - used_usd) / adapter.price_per_hour_usd * 60)
                    )
                if left:
                    current = state.budget_minutes_left.get(budget.provider)
                    state.budget_minutes_left[budget.provider] = min(
                        [*left, *([current] if current is not None else [])]
                    )
            if job_id:
                for part in await db.transcriptionchunk.find_many(
                    where={"jobId": job_id, "status": "COMPLETED"}
                ):
                    state.done_parts[(part.provider, part.partIndex)] = SttResult(
                        "COMPLETED" if (part.text or "").strip() else "EMPTY",
                        part.text or "",
                        part.provider,
                        part.model or "",
                        segments=_segments_from(part.segments),
                        language=part.language,
                        speakers=part.speakers,
                    )
        except Exception as exc:  # noqa: BLE001 - no state is the old behaviour, never a failure
            logger.warning(
                "transcription state could not be read (%s); using none", type(exc).__name__
            )
        return state

    async def record_usage(
        self,
        event: UsageEvent,
        *,
        media_id: str | None,
        cache_hit: bool = False,
        job_id: str | None = None,
    ) -> None:
        await db.sttusage.create(
            data={
                "provider": event.provider,
                "model": event.model,
                "keyRef": event.key_ref,
                "mediaFileId": media_id,
                "jobId": job_id,
                "partIndex": event.part_index,
                "audioMs": event.audio_ms,
                "latencyMs": event.latency_ms,
                "httpStatus": event.http_status,
                "outcome": event.outcome,
                "costUsd": event.cost_usd,
                "cacheHit": cache_hit,
            }
        )

    async def set_cooldown(
        self, provider: str, key_ref: str, retry_after: float | None
    ) -> datetime | None:
        now = datetime.now(UTC)
        existing = await db.sttproviderstate.find_unique(
            where={"provider_keyRef": {"provider": provider, "keyRef": key_ref}}
        )
        consecutive = (existing.consecutive429 if existing else 0) + 1
        until = chain.cooldown_until(now, consecutive, retry_after)
        await db.sttproviderstate.upsert(
            where={"provider_keyRef": {"provider": provider, "keyRef": key_ref}},
            data={
                "create": {
                    "provider": provider,
                    "keyRef": key_ref,
                    "cooldownUntil": until,
                    "consecutive429": consecutive,
                    "lastError": "throttled",
                    "lastErrorAt": now,
                },
                "update": {
                    "cooldownUntil": until,
                    "consecutive429": consecutive,
                    "lastError": "throttled",
                    "lastErrorAt": now,
                },
            },
        )
        return until

    async def clear_cooldown(self, provider: str, key_ref: str) -> None:
        await db.sttproviderstate.update_many(
            where={"provider": provider, "keyRef": key_ref},
            data={"cooldownUntil": None, "consecutive429": 0},
        )

    async def save_part(
        self, *, job_id: str, provider: str, plan: PartPlan, result: SttResult
    ) -> None:
        fields = {
            "partCount": plan.count,
            "startMs": plan.start_ms,
            "endMs": plan.end_ms,
            "status": "COMPLETED",
            "text": result.text,
            "segments": Json(_segments_json(result.segments)),
            "speakers": result.speakers,
            "language": result.language,
            "model": result.model,
        }
        await db.transcriptionchunk.upsert(
            where={
                "jobId_provider_partIndex": {
                    "jobId": job_id,
                    "provider": provider,
                    "partIndex": plan.index,
                }
            },
            data={
                "create": {
                    "jobId": job_id,
                    "provider": provider,
                    "partIndex": plan.index,
                    **fields,
                },
                "update": fields,
            },
        )

    # --- Dedup ---------------------------------------------------------------------------------

    async def cache_get(self, sha256: str, options_hash: str) -> Any | None:
        row = await db.transcriptcache.find_unique(
            where={"sha256_optionsHash": {"sha256": sha256, "optionsHash": options_hash}}
        )
        if row is not None:
            await db.transcriptcache.update(where={"id": row.id}, data={"hits": {"increment": 1}})
        return row

    async def cache_put(self, sha256: str, options_hash: str, result: SttResult) -> None:
        fields = {
            "provider": result.provider,
            "model": result.model,
            "text": result.text,
            "segments": Json(_segments_json(result.segments)),
            "language": result.language,
            "speakers": result.speakers,
        }
        await db.transcriptcache.upsert(
            where={"sha256_optionsHash": {"sha256": sha256, "optionsHash": options_hash}},
            data={
                "create": {"sha256": sha256, "optionsHash": options_hash, **fields},
                "update": fields,
            },
        )


def cached_result(row: Any) -> SttResult:
    return SttResult(
        "COMPLETED" if (row.text or "").strip() else "EMPTY",
        row.text or "",
        row.provider,
        row.model or "",
        segments=_segments_from(row.segments),
        language=row.language,
        speakers=row.speakers or 0,
    )


# --- The admin Transcription panel ----------------------------------------------------------------


async def panel_snapshot() -> dict[str, Any]:
    """Queue depth, minutes and spend per provider (today and this month), cooldowns, budgets and
    the last 20 failures. Reads only."""
    now = datetime.now(UTC)
    depth_rows = await db.query_raw(
        'SELECT "status"::text AS status, COUNT(*)::int AS n FROM "MediaProcessingJob" '
        'WHERE "jobType" = \'TRANSCRIPTION\' GROUP BY "status"'
    )
    depth = {str(row["status"]): int(row["n"]) for row in depth_rows}
    usage: list[dict[str, Any]] = []
    for provider in PROVIDER_IDS:
        day_ms, day_usd = await _usage_totals(provider, period_start("DAY", now))
        month_ms, month_usd = await _usage_totals(provider, period_start("MONTH", now))
        usage.append(
            {
                "provider": provider,
                "dayMinutes": round(day_ms / 60_000, 1),
                "daySpendUsd": round(day_usd, 2),
                "monthMinutes": round(month_ms / 60_000, 1),
                "monthSpendUsd": round(month_usd, 2),
            }
        )
    cooldowns = [
        {
            "provider": row.provider,
            "keyRef": row.keyRef,
            "until": row.cooldownUntil,
            "consecutive": row.consecutive429,
        }
        for row in await db.sttproviderstate.find_many(where={"cooldownUntil": {"gt": now}})
    ]
    budgets = [
        {
            "provider": row.provider,
            "period": row.period,
            "minutesCap": row.minutesCap,
            "spendCapUsd": row.spendCapUsd,
        }
        for row in await db.sttbudget.find_many(order={"provider": "asc"})
    ]
    failures = [
        {
            "provider": row.provider,
            "outcome": row.outcome,
            "httpStatus": row.httpStatus,
            "mediaFileId": row.mediaFileId,
            "at": row.createdAt,
        }
        for row in await db.sttusage.find_many(
            where={"outcome": {"not_in": ["COMPLETED", "EMPTY"]}},
            order={"createdAt": "desc"},
            take=20,
        )
    ]
    return {
        "queue": depth,
        "usage": usage,
        "cooldowns": cooldowns,
        "budgets": budgets,
        "failures": failures,
    }


async def save_budget(
    provider: str, period: str, minutes_cap: int | None, spend_cap_usd: float | None, by: str | None
) -> None:
    if minutes_cap is None and spend_cap_usd is None:
        await db.sttbudget.delete_many(where={"provider": provider, "period": period})
        return
    fields = {"minutesCap": minutes_cap, "spendCapUsd": spend_cap_usd, "updatedById": by}
    await db.sttbudget.upsert(
        where={"provider_period": {"provider": provider, "period": period}},
        data={"create": {"provider": provider, "period": period, **fields}, "update": fields},
    )
