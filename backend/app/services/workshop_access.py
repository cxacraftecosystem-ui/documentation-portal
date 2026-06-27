"""Workshop assignment + submission-window enforcement.

An admin assigns researchers to a workshop (WorkshopAssignment rows). Once a workshop has any
assignment, only the assigned researchers (and admins) may create records tied to it. A submission
made outside the workshop's [startDate, endDate] window is allowed through but flagged in the record's
extraMetadata as needing admin approval, so a reviewer can see it was out of window. A workshop with NO
assignments stays open to everyone (backward compatible with records created before assignments).
"""
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import get_value, is_admin


def _as_utc(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _iso(value: Any) -> str | None:
    dt = _as_utc(value)
    return dt.isoformat() if dt else None


async def workshop_assignee_ids(workshop_id: str) -> set[str]:
    rows = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    return {r.userId for r in rows}


def merge_extra(data: dict[str, Any], fragment: dict[str, Any] | None) -> dict[str, Any]:
    """Merge a metadata fragment into data['extraMetadata'] as a plain dict (call before provenance
    merge, which later Json-wraps it)."""
    if not fragment:
        return data
    existing = data.get("extraMetadata")
    base = dict(existing) if isinstance(existing, dict) else {}
    base.update(fragment)
    data["extraMetadata"] = base
    return data


async def enforce_workshop_submission(
    user: Any, workshop_id: str | None, *, when: datetime | None = None
) -> dict[str, Any]:
    """Authorize a non-admin creating/moving a record into `workshop_id`.

    Raises 403 if the workshop has assignments and the user is not assigned. Returns an extraMetadata
    fragment (possibly empty) describing whether the submission is out of the workshop's window and so
    needs admin approval. Admins and an empty workshop_id are unrestricted.
    """
    if not workshop_id or is_admin(user):
        return {}
    workshop = await db.workshop.find_unique(where={"id": workshop_id})
    if workshop is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop not found")
    assignees = await workshop_assignee_ids(workshop_id)
    if assignees and get_value(user, "id") not in assignees:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You are not assigned to this workshop. Ask an admin to assign you to it.",
        )
    now = when or datetime.now(UTC)
    start = _as_utc(workshop.startDate or workshop.date)
    end = _as_utc(workshop.endDate or workshop.date)
    out_of_window = False
    if start and end:
        # Inclusive window; count the whole of the end day as in-window.
        if now < start or now >= end + timedelta(days=1):
            out_of_window = True
    return {
        "workshopSubmission": {
            "workshopId": workshop_id,
            "outOfWindow": out_of_window,
            "needsAdminApproval": out_of_window,
            "windowStart": _iso(workshop.startDate or workshop.date),
            "windowEnd": _iso(workshop.endDate or workshop.date),
            "submittedAt": now.isoformat(),
        }
    }
