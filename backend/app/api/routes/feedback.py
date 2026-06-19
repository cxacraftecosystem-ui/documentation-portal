from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user, require_master_admin
from app.schemas.feedback import FeedbackUpsertRequest

router = APIRouter(prefix="/feedback", tags=["feedback"])


def _serialize(row: Any) -> dict[str, Any]:
    """Serialise a feedback row, attaching only the author's safe identity fields (never the
    password hash or other sensitive user columns) when the relation is loaded."""
    user = getattr(row, "user", None)
    return {
        "id": row.id,
        "userId": row.userId,
        "rating": row.rating,
        "comment": row.comment,
        "createdAt": jsonable_encoder(row.createdAt),
        "updatedAt": jsonable_encoder(row.updatedAt),
        "user": None
        if user is None
        else {
            "id": user.id,
            "name": user.name,
            "email": user.email,
            "role": getattr(user.role, "value", user.role),
        },
    }


@router.get("/me")
async def my_feedback(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The current user's own feedback, or an empty object if they haven't given any yet."""
    feedback = await db.feedback.find_unique(where={"userId": current_user.id})
    return _serialize(feedback) if feedback else {}


@router.put("/me")
async def upsert_my_feedback(
    payload: FeedbackUpsertRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Create or update the current user's feedback — they can revisit and change it any time."""
    fields = {"rating": payload.rating, "comment": payload.comment}
    feedback = await db.feedback.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return _serialize(feedback)


@router.get("")
async def list_feedback(_: Any = Depends(require_master_admin)) -> list[dict[str, Any]]:
    """Master-admin only: every user's feedback, most recently updated first, each with its author."""
    rows = await db.feedback.find_many(
        order=[{"updatedAt": "desc"}],
        include={"user": True},
    )
    return [_serialize(row) for row in rows]
