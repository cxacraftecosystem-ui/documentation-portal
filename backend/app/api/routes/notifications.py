"""The in-app activity list: a person's own notices, the unread count, and marking them read.

Every route reads or writes only the CALLER's rows — there is no way to name another account — so
the gate is a login and nothing else. The same four routes, with the same shapes, exist in the
designer portal; both web clients and both Android clients are written against them.

    GET  /api/notifications?limit=30&before=<id>&unreadOnly=false
         -> {"items": [notice...], "unreadCount": n, "hasMore": bool}
    GET  /api/notifications/unread-count          -> {"unreadCount": n}
    POST /api/notifications/{id}/read             -> {"item": notice, "unreadCount": n}
    POST /api/notifications/read  {"ids": [...]} or {"all": true}
                                                  -> {"updated": n, "unreadCount": n}

Marking read is idempotent (an already-read notice keeps its first ``readAt``), so the handset's
offline queue may replay a mark as often as it likes. An id that is not the caller's is ignored by
the batch route and is a 404 on the single one — never a 403, which would confirm it exists.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import Field

from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.common import APIModel
from app.services.notifications import serialize

router = APIRouter(prefix="/notifications", tags=["notifications"])

#: The most one page holds. The handset asks for its whole offline copy in one page of this size.
MAX_PAGE = 100
#: The most ids one batch mark may name.
MAX_IDS = 500


class MarkReadRequest(APIModel):
    ids: list[str] = Field(default_factory=list, max_length=MAX_IDS)
    all: bool = False


async def _unread(user_id: str) -> int:
    return await db.notification.count(where={"userId": user_id, "readAt": None})


@router.get("")
async def list_notifications(
    limit: int = Query(30, ge=1, le=MAX_PAGE),
    before: str | None = Query(None, max_length=64),
    unreadOnly: bool = Query(False),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Newest first. ``before`` is the id of the last notice already shown (the next page starts
    after it); an id that is not the caller's, or no longer exists, starts from the top."""
    where: dict[str, Any] = {"userId": current_user.id}
    if unreadOnly:
        where["readAt"] = None
    query: dict[str, Any] = {
        "where": where,
        "order": [{"createdAt": "desc"}, {"id": "desc"}],
        "take": limit + 1,
    }
    if before:
        anchor = await db.notification.find_first(where={"id": before, "userId": current_user.id})
        if anchor is not None:
            query["cursor"] = {"id": anchor.id}
            query["skip"] = 1
    rows = await db.notification.find_many(**query)
    return {
        "items": [serialize(row) for row in rows[:limit]],
        "unreadCount": await _unread(current_user.id),
        "hasMore": len(rows) > limit,
    }


@router.get("/unread-count")
async def unread_count(current_user: Any = Depends(get_current_user)) -> dict[str, int]:
    return {"unreadCount": await _unread(current_user.id)}


@router.post("/read")
async def mark_read(payload: MarkReadRequest, current_user: Any = Depends(get_current_user)) -> dict[str, int]:
    """Mark the named notices read, or every one with ``all``. Already-read ones are left alone."""
    where: dict[str, Any] = {"userId": current_user.id, "readAt": None}
    if not payload.all:
        ids = [value for value in dict.fromkeys(payload.ids) if value]
        if not ids:
            return {"updated": 0, "unreadCount": await _unread(current_user.id)}
        where["id"] = {"in": ids}
    updated = await db.notification.update_many(where=where, data={"readAt": datetime.now(UTC)})
    return {"updated": int(updated or 0), "unreadCount": await _unread(current_user.id)}


@router.post("/{notification_id}/read")
async def mark_one_read(notification_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    row = await db.notification.find_first(where={"id": notification_id, "userId": current_user.id})
    if row is None:
        raise HTTPException(status_code=404, detail="Notification not found")
    if row.readAt is None:
        await db.notification.update_many(
            where={"id": row.id, "readAt": None}, data={"readAt": datetime.now(UTC)}
        )
        row = await db.notification.find_unique(where={"id": row.id})
    return {"item": serialize(row), "unreadCount": await _unread(current_user.id)}
