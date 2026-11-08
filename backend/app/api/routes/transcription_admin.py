from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from app.core.db import db
from app.core.deps import get_current_user, require_admin, require_master_admin
from app.services import (
    ai,
    app_settings,
    audio_isolation,
    job_supersede,
    managed_secrets,
    transcribe_choice,
    transcript_backfill,
    transcription_store,
)
from app.transcription.contract import PROVIDER_IDS
from app.transcription.providers import ADAPTERS

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/settings/transcription", tags=["settings"])

PAUSED_DETAIL = "Transcription is paused. Turn it on in Settings first."


class BudgetIn(BaseModel):
    provider: Literal["elevenlabs", "deepgram", "whisper", "muse"]
    period: Literal["DAY", "MONTH"]
    minutesCap: int | None = Field(default=None, ge=0, le=1_000_000)
    spendCapUsd: float | None = Field(default=None, ge=0, le=1_000_000)


class TranscriptionSettingsUpdate(BaseModel):
    paused: bool | None = None
    disabledProviders: list[Literal["elevenlabs", "deepgram", "whisper", "muse"]] | None = None
    budgets: list[BudgetIn] | None = Field(default=None, max_length=8)
    audioIsolation: bool | None = None
    autoTranscribeVideo: bool | None = None
    autoTranscribeAudio: bool | None = None
    transcriptBackfill: bool | None = None
    confirmBackfill: bool = False


class RetryFailedIn(BaseModel):
    dryRun: bool = True
    limit: int = Field(default=200, ge=1, le=2000)


async def _state() -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()
    row = await app_settings.load_app_settings()
    order = await app_settings.load_stt_provider_order()
    disabled = app_settings.stt_providers_disabled(row)
    configured = ai.transcription_provider_configured()
    chain = ai.transcription_provider_chain(order=order, disabled=disabled)
    providers = [
        {
            "id": provider,
            "name": ADAPTERS[provider].display_name,
            "enabled": provider not in disabled,
            "configured": bool(configured.get(provider)),
            "inChain": provider in chain,
            "pricePerHourUsd": ADAPTERS[provider].price_per_hour_usd,
        }
        for provider in order
        if provider in PROVIDER_IDS
    ]
    snapshot: dict[str, Any] = {}
    try:
        snapshot = await transcription_store.panel_snapshot()
    except Exception as exc:  # noqa: BLE001 - the switches must stay reachable if a read fails
        logger.warning("transcription panel snapshot failed (%s)", type(exc).__name__)
    return {
        "paused": app_settings.transcription_paused(row),
        "providers": providers,
        "window": {
            "enabled": bool(getattr(row, "batchWindowEnabled", False))
            if row is not None
            else False,
            "start": getattr(row, "batchWindowStart", "02:00") if row is not None else "02:00",
            "end": getattr(row, "batchWindowEnd", "05:00") if row is not None else "05:00",
            "timezone": getattr(row, "batchTimezone", app_settings.DEFAULT_TIMEZONE)
            if row is not None
            else app_settings.DEFAULT_TIMEZONE,
        },
        "mode": app_settings.transcription_mode(row),
        "audioIsolation": {
            "enabled": bool(getattr(row, "audioIsolationEnabled", False)) if row is not None else False,
            "keyConfigured": bool(managed_secrets.peek_secret(audio_isolation.KEY_NAME)),
        },
        "autoTranscribe": {
            "video": bool(getattr(row, "autoTranscribeVideo", False)) if row is not None else False,
            "audio": bool(getattr(row, "autoTranscribeAudio", False)) if row is not None else False,
        },
        "backfill": await transcript_backfill.progress(row),
        **snapshot,
    }


@router.get("")
async def get_transcription_settings(_: Any = Depends(require_admin)) -> dict[str, Any]:
    return await _state()


@router.put("")
async def update_transcription_settings(
    payload: TranscriptionSettingsUpdate, current_user: Any = Depends(require_master_admin)
) -> dict[str, Any]:
    if payload.paused is not None or payload.disabledProviders is not None:
        await app_settings.save_transcription_switches(
            paused=payload.paused,
            disabled=list(payload.disabledProviders)
            if payload.disabledProviders is not None
            else None,
            updated_by_id=current_user.id,
        )
    if payload.audioIsolation is not None:
        await audio_isolation.set_enabled(payload.audioIsolation, current_user.id)
    if payload.autoTranscribeVideo is not None or payload.autoTranscribeAudio is not None:
        await transcribe_choice.save_switches(
            video=payload.autoTranscribeVideo,
            audio=payload.autoTranscribeAudio,
            updated_by_id=current_user.id,
        )
    if payload.transcriptBackfill is not None:
        try:
            await transcript_backfill.set_enabled(
                payload.transcriptBackfill,
                confirmed=payload.confirmBackfill,
                updated_by_id=current_user.id,
            )
        except transcript_backfill.ConfirmationNeeded as exc:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    for budget in payload.budgets or []:
        await transcription_store.save_budget(
            budget.provider, budget.period, budget.minutesCap, budget.spendCapUsd, current_user.id
        )
    logger.warning(
        "AUDIT transcription-settings: paused=%s audioIsolation=%s autoVideo=%s autoAudio=%s "
        "backfill=%s disabled=%s budgets=%s by=%s",
        payload.paused,
        payload.audioIsolation,
        payload.autoTranscribeVideo,
        payload.autoTranscribeAudio,
        payload.transcriptBackfill,
        payload.disabledProviders,
        [b.model_dump() for b in payload.budgets or []],
        current_user.email,
    )
    return await _state()


@router.get("/upload-policy")
async def get_upload_policy(_: Any = Depends(get_current_user)) -> dict[str, bool]:
    return await transcribe_choice.policy()


@router.get("/backfill")
async def get_backfill(_: Any = Depends(require_admin)) -> dict[str, Any]:
    return {
        "estimate": await transcript_backfill.estimate(),
        "progress": await transcript_backfill.progress(),
    }


@router.post("/retry-failed")
async def retry_failed_transcriptions(
    payload: RetryFailedIn, current_user: Any = Depends(require_master_admin)
) -> dict[str, Any]:
    jobs = await db.mediaprocessingjob.find_many(
        where=await job_supersede.without_superseded(
            {"jobType": "TRANSCRIPTION", "status": "FAILED"}, client=db
        ),
        order={"createdAt": "asc"},
        take=payload.limit,
    )
    open_media = {
        job.mediaFileId
        for job in await db.mediaprocessingjob.find_many(
            where={
                "jobType": "TRANSCRIPTION",
                "status": {"in": ["QUEUED", "PROCESSING"]},
                "mediaFileId": {"in": [j.mediaFileId for j in jobs]},
            }
        )
    }
    candidates = [job for job in jobs if job.mediaFileId not in open_media]
    answer = {
        "dryRun": payload.dryRun,
        "failed": len(jobs),
        "wouldRequeue": len(candidates),
        "requeued": 0,
    }
    if payload.dryRun:
        return answer
    if await app_settings.load_transcription_paused():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=PAUSED_DETAIL)
    now = datetime.now(UTC)
    seen: set[str] = set()
    for job in candidates:
        if job.mediaFileId in seen:
            continue
        seen.add(job.mediaFileId)
        async with job_supersede.atomic(db) as tx:
            await tx.mediaprocessingjob.update(
                where={"id": job.id},
                data={
                    "status": "QUEUED",
                    "attempts": 0,
                    "runAfter": now,
                    "completedAt": None,
                    "error": None,
                    "openKey": transcription_store.open_key(job.mediaFileId, "TRANSCRIPTION"),
                },
            )
            await job_supersede.clear_failed(
                tx, job.mediaFileId, "TRANSCRIPTION", keep_id=job.id
            )
        answer["requeued"] += 1
    logger.warning(
        "AUDIT transcription-retry-failed: requeued=%s by=%s",
        answer["requeued"],
        current_user.email,
    )
    return answer
