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

THE MONDAY SUMMARY (``app/services/weekly_digest.py``) rides the same prefix:

    GET  /api/notifications/digest                -> {"eligible", "enabled", "emailAvailable",
                                                      "schedule", "latest": summary | null}
    PUT  /api/notifications/digest  {"enabled": b} -> the same shape; 403 for an account that gets none
    GET  /api/notifications/digests/{id}          -> one summary; the caller's own, else 404

The switch has its own route (not ``/preferences/notifications``) so it shows and saves while mail
is off: the in-app half still goes.

Marking read is idempotent (an already-read notice keeps its first ``readAt``), so the handset's
offline queue may replay a mark as often as it likes. An id that is not the caller's is ignored by
the batch route and is a 404 on the single one — never a 403, which would confirm it exists.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import Field

from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.common import APIModel
from app.schemas.notifications import DigestPreferenceUpdate
from app.services import mailer, weekly_digest
from app.services.notifications import serialize, wants_email

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
    after it); an id that is not the caller's, or no longer exists, starts from the top.

    The next page is found from the anchor's OWN ``createdAt`` and ``id`` (keyset), not with a
    Prisma cursor and ``skip=1``. With ``unreadOnly`` the anchor is often the notice the person just
    opened, so it no longer matches the filter; a cursor would then skip the first real older notice."""
    where: dict[str, Any] = {"userId": current_user.id}
    if unreadOnly:
        where["readAt"] = None
    if before:
        anchor = await db.notification.find_first(where={"id": before, "userId": current_user.id})
        if anchor is not None:
            older = {
                "OR": [
                    {"createdAt": {"lt": anchor.createdAt}},
                    {"createdAt": anchor.createdAt, "id": {"lt": anchor.id}},
                ]
            }
            where = {"AND": [where, older]}
    rows = await db.notification.find_many(
        where=where,
        order=[{"createdAt": "desc"}, {"id": "desc"}],
        take=limit + 1,
    )
    return {
        "items": [serialize(row) for row in rows[:limit]],
        "unreadCount": await _unread(current_user.id),
        "hasMore": len(rows) > limit,
    }


# ---------------------------------------------------------------------------------------------
# The Monday summary. Literal paths, declared before any ``/{notification_id}`` route.
# ---------------------------------------------------------------------------------------------


async def _digest_state(user: Any) -> dict[str, Any]:
    state: dict[str, Any] = {
        "eligible": False,
        "enabled": False,
        "emailAvailable": mailer.mail_configured(),
        "schedule": weekly_digest.SCHEDULE,
        "latest": None,
    }
    if not weekly_digest.is_authority(user):
        return state
    latest = await db.weeklydigest.find_first(
        where={"userId": user.id, "status": weekly_digest.DELIVERED},
        order=[{"weekStart": "desc"}, {"createdAt": "desc"}],
    )
    state.update(
        eligible=True,
        enabled=await wants_email(user.id, weekly_digest.PREFERENCE),
        latest=weekly_digest.summarize_digest(latest) if latest is not None else None,
    )
    return state


@router.get("/digest")
async def digest_preference(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await _digest_state(current_user)


@router.put("/digest")
async def save_digest_preference(
    payload: DigestPreferenceUpdate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Save the switch. Stored even while mail is off; read again at send time."""
    if not weekly_digest.is_authority(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=weekly_digest.NOT_ELIGIBLE_DETAIL)
    fields = {weekly_digest.PREFERENCE: payload.enabled}
    await db.userpreference.upsert(
        where={"userId": current_user.id},
        data={"create": {**fields, "user": {"connect": {"id": current_user.id}}}, "update": fields},
    )
    return await _digest_state(current_user)


@router.get("/digests/{digest_id}")
async def read_digest(digest_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """One delivered summary, the caller's own. Anybody else's — or one never sent — is a 404."""
    row = await db.weeklydigest.find_first(
        where={"id": digest_id, "userId": current_user.id, "status": weekly_digest.DELIVERED}
    )
    if row is None:
        raise HTTPException(status_code=404, detail="Summary not found")
    return weekly_digest.serialize_digest(row)


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
