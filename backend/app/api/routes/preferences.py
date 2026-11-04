from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.preferences import NotificationPreferencesUpdate, PreferencesUpdateRequest
from app.services import mailer

router = APIRouter(prefix="/preferences", tags=["preferences"])


PREFERENCE_FIELDS = (
    "theme",
    "reducedMotion",
    "largerText",
    "highContrast",
)


def _serialize(row: Any) -> dict[str, Any]:
    data: dict[str, Any] = {
        "id": row.id,
        "userId": row.userId,
        "updatedAt": jsonable_encoder(row.updatedAt),
    }
    for field in PREFERENCE_FIELDS:
        data[field] = getattr(row, field, None)
    return data


@router.get("/me")
async def my_preferences(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    preferences = await db.userpreference.find_unique(where={"userId": current_user.id})
    return _serialize(preferences) if preferences else {}


@router.put("/me")
async def upsert_my_preferences(
    payload: PreferencesUpdateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    fields = {field: getattr(payload, field) for field in PREFERENCE_FIELDS}
    preferences = await db.userpreference.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return _serialize(preferences)


@router.get("/notifications")
async def my_notification_preferences(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    row = await db.userpreference.find_unique(where={"userId": current_user.id})
    return {
        "available": mailer.mail_configured(),
        "emailTaskUpdates": bool(getattr(row, "emailTaskUpdates", True)) if row else True,
    }


@router.put("/notifications")
async def update_my_notification_preferences(
    payload: NotificationPreferencesUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    fields = {"emailTaskUpdates": payload.emailTaskUpdates}
    row = await db.userpreference.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return {"available": mailer.mail_configured(), "emailTaskUpdates": row.emailTaskUpdates}
