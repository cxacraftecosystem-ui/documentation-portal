from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.db import db
from app.core.deps import require_master_admin
from app.schemas.settings import AppSettingDto, AppSettingUpdate
from app.services.app_settings import (
    SINGLETON_ID,
    VALID_TRANSCRIPTION_MODES,
    get_or_create_app_settings,
    is_valid_hhmm,
)

router = APIRouter(prefix="/settings", tags=["settings"])


@router.get("", response_model=AppSettingDto)
async def get_app_settings(_: Any = Depends(require_master_admin)) -> Any:
    """Current global settings (creating the singleton with defaults on first read)."""
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
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"transcriptionMode must be one of {sorted(VALID_TRANSCRIPTION_MODES)}",
        )
    for field in ("batchWindowStart", "batchWindowEnd"):
        if field in data and not is_valid_hhmm(data[field]):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{field} must be a 24-hour HH:mm time",
            )
    data["updatedById"] = current_user.id
    return await db.appsetting.update(where={"id": SINGLETON_ID}, data=data)
