import logging
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.db import db
from app.core.deps import require_admin, require_master_admin
from app.schemas.settings import (
    AppSettingDto,
    AppSettingUpdate,
    TranscriptionProviderOrderDto,
    TranscriptionProviderOrderUpdate,
)
from app.services import ai, managed_secrets
from app.services.app_settings import (
    SINGLETON_ID,
    STT_KEY_ABSENT,
    STT_KEY_FAILING,
    STT_KEY_PASSING,
    STT_KEY_UNTESTED,
    VALID_TRANSCRIPTION_MODES,
    freeze_unrankable,
    get_or_create_app_settings,
    invalid_stt_provider_order,
    is_valid_hhmm,
    load_stt_provider_order,
    recalled_key_verdict,
    remember_key_verdict,
    save_stt_provider_order,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/settings", tags=["settings"])


@router.get("", response_model=AppSettingDto)
async def get_app_settings(_: Any = Depends(require_master_admin)) -> Any:
    return await get_or_create_app_settings()


@router.put("", response_model=AppSettingDto)
async def update_app_settings(
    payload: AppSettingUpdate,
    current_user: Any = Depends(require_master_admin),
) -> Any:
    await get_or_create_app_settings(current_user.id)
    data = payload.model_dump(exclude_none=True)
    if "transcriptionMode" in data and data["transcriptionMode"] not in VALID_TRANSCRIPTION_MODES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"transcriptionMode must be one of {sorted(VALID_TRANSCRIPTION_MODES)}",
        )
    for field in ("batchWindowStart", "batchWindowEnd"):
        if field in data and not is_valid_hhmm(data[field]):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"{field} must be a 24-hour HH:mm time",
            )
    if "sttProviderOrder" in data:
        problem = invalid_stt_provider_order(data["sttProviderOrder"])
        if problem:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"sttProviderOrder {problem}",
            )
        verified = await _provider_verification()
        ranked = freeze_unrankable(data["sttProviderOrder"], _rankable_ids(verified))
        data["sttProviderOrder"] = {"set": ranked}
    data["updatedById"] = current_user.id
    return await db.appsetting.update(where={"id": SINGLETON_ID}, data=data)



_PROBE_OK = "OK"
_PROBE_FAILED = "FAILED"


def _require_provider(provider: str) -> tuple[str, str]:
    catalog = {p: (name, key) for p, name, key in ai.transcription_provider_catalog()}
    if provider not in catalog:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Unknown transcription provider {provider!r}. Known engines: {', '.join(catalog)}",
        )
    return catalog[provider]


def _frozen_reason(state: str, key_label: str, error: str | None) -> str | None:
    if state == STT_KEY_ABSENT:
        return (
            f"No {key_label} API key has been added, so this engine cannot transcribe anything. "
            "A master admin adds the key on this page; once it passes a test it can be ranked."
        )
    if state == STT_KEY_UNTESTED:
        return (
            f"The {key_label} key has never been tested, so there is no evidence it works. "
            "Press Test — it asks the provider and takes a second — and this engine unlocks."
        )
    if state == STT_KEY_FAILING:
        return (
            f"The last test of the {key_label} key failed: {error or 'the provider refused it'}. "
            "Fix or replace the key and test again; until it passes, this engine stays at the bottom."
        )
    return None


async def _provider_verification() -> dict[str, dict[str, Any]]:
    described = {entry["key"]: entry for entry in await managed_secrets.list_managed_secrets()}
    configured = ai.transcription_provider_configured()
    labels = {key: spec.label for key, spec in managed_secrets.MANAGED_KEYS.items()}

    verification: dict[str, dict[str, Any]] = {}
    for provider, name, key_name in ai.transcription_provider_catalog():
        entry = described.get(key_name)
        has_key = configured.get(provider, False)
        state, tested_at, error = STT_KEY_ABSENT, None, None
        if has_key:
            last_status = entry.get("lastStatus") if entry is not None else None
            if last_status == _PROBE_OK:
                state, tested_at = STT_KEY_PASSING, entry.get("lastCheckedAt")
            elif last_status == _PROBE_FAILED:
                state, tested_at = STT_KEY_FAILING, entry.get("lastCheckedAt")
                error = entry.get("lastError")
            elif entry is not None and entry.get("source") == managed_secrets.SOURCE_DATABASE:
                state = STT_KEY_UNTESTED
            else:
                recalled = recalled_key_verdict(key_name)
                if recalled is None:
                    state = STT_KEY_UNTESTED
                else:
                    state, tested_at, error = (
                        recalled["status"],
                        recalled["checkedAt"],
                        recalled["error"],
                    )
        key_label = labels.get(key_name, key_name)
        verification[provider] = {
            "id": provider,
            "name": name,
            "keyName": key_name,
            "keyLabel": key_label,
            "configured": has_key,
            "keyState": state,
            "rankable": state == STT_KEY_PASSING,
            "frozenReason": _frozen_reason(state, key_label, error),
            "testedAt": tested_at,
            "testError": error,
        }
    return verification


def _rankable_ids(verification: dict[str, dict[str, Any]]) -> set[str]:
    return {provider for provider, entry in verification.items() if entry["rankable"]}


def _provider_order_state(
    order: list[str],
    verification: dict[str, dict[str, Any]],
    *,
    normalised: bool = False,
    note: str | None = None,
) -> dict[str, Any]:
    return {
        "providers": [verification[provider] for provider in order],
        "effectiveChain": ai.transcription_provider_chain(order=order),
        "verifiedChain": [p for p in order if verification[p]["rankable"]],
        "normalised": normalised,
        "normalisedNote": note,
    }


def _join_names(names: list[str]) -> str:
    if len(names) <= 1:
        return names[0] if names else ""
    return f"{', '.join(names[:-1])} and {names[-1]}"


@router.get("/transcription-providers", response_model=TranscriptionProviderOrderDto)
async def get_transcription_provider_order(_: Any = Depends(require_admin)) -> dict[str, Any]:
    verification = await _provider_verification()
    return _provider_order_state(await load_stt_provider_order(), verification)


@router.put("/transcription-providers", response_model=TranscriptionProviderOrderDto)
async def update_transcription_provider_order(
    payload: TranscriptionProviderOrderUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    problem = invalid_stt_provider_order(payload.order)
    if problem:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"order {problem}",
        )
    verification = await _provider_verification()
    ranked = freeze_unrankable(payload.order, _rankable_ids(verification))
    stored = await save_stt_provider_order(ranked, current_user.id)

    sank = [
        verification[p]["name"]
        for p in payload.order
        if not verification[p]["rankable"] and stored.index(p) > payload.order.index(p)
    ]
    note = None
    if sank:
        subject = _join_names(sank)
        note = (
            f"{subject} {'has' if len(sank) == 1 else 'have'} not passed a provider test, so "
            f"{'it was' if len(sank) == 1 else 'they were'} moved below the engines that have. "
            f"Test {'it' if len(sank) == 1 else 'them'} and the ranking is yours again."
        )
    return _provider_order_state(stored, verification, normalised=bool(sank), note=note)


@router.post("/transcription-providers/{provider}/test", response_model=TranscriptionProviderOrderDto)
async def test_transcription_provider(
    provider: str,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    _, key_name = _require_provider(provider)
    described = await managed_secrets.test_secret(key_name)
    passing = described["lastStatus"] == _PROBE_OK
    if described["source"] != managed_secrets.SOURCE_DATABASE and described["configured"]:
        remember_key_verdict(
            key_name,
            passing=passing,
            checked_at=described.get("lastCheckedAt") or datetime.now(timezone.utc),
            error=described.get("lastError"),
        )
    logger.warning(
        "AUDIT stt-provider test: provider=%s key=%s verdict=%s by=%s",
        provider,
        key_name,
        "PASSING" if passing else "FAILING",
        current_user.email,
    )
    verification = await _provider_verification()
    return _provider_order_state(await load_stt_provider_order(), verification)
