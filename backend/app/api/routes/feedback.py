from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user, require_master_admin
from app.schemas.feedback import FeedbackUpsertRequest

router = APIRouter(prefix="/feedback", tags=["feedback"])


# Every persisted feedback field that is echoed straight back to the client (quantitative ints +
# qualitative strings). Kept as one list so the serializer and the upsert stay in lockstep.
FEEDBACK_FIELDS = (
    "rating",
    "easeOfUse",
    "reliability",
    "performance",
    "design",
    "features",
    "recommend",
    "comment",
    "likeMost",
    "improve",
    "bugs",
    "featureRequests",
    "role",
)


def _serialize(row: Any) -> dict[str, Any]:
    """Serialise a feedback row, attaching only the author's safe identity fields (never the
    password hash or other sensitive user columns) when the relation is loaded."""
    user = getattr(row, "user", None)
    data: dict[str, Any] = {
        "id": row.id,
        "userId": row.userId,
        "createdAt": jsonable_encoder(row.createdAt),
        "updatedAt": jsonable_encoder(row.updatedAt),
    }
    for field in FEEDBACK_FIELDS:
        data[field] = getattr(row, field, None)
    data["user"] = (
        None
        if user is None
        else {
            "id": user.id,
            "name": user.name,
            "email": user.email,
            "role": getattr(user.role, "value", user.role),
        }
    )
    return data


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
    fields = {field: getattr(payload, field) for field in FEEDBACK_FIELDS}
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
