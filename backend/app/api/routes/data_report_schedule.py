from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Response, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import require_dataset_downloader
from app.services import mailer, report_schedules, s3

router = APIRouter(tags=["data-report-schedules"])

_INCLUDE = {"savedView": True}
FILE_LINK_SECONDS = 900


def _refuse(bad: Exception) -> HTTPException:
    return HTTPException(status_code=422, detail=str(bad))


@router.get("/data/report/schedules")
async def list_schedules(current_user: Any = Depends(require_dataset_downloader)) -> dict[str, Any]:
    rows = await db.datareportschedule.find_many(
        where={"ownerId": str(current_user.id)},
        include=_INCLUDE,
        order={"createdAt": "asc"},
        take=report_schedules.MAX_PER_OWNER,
    )
    now = datetime.now(UTC)
    settings = get_settings()
    return {
        "items": [report_schedules.schedule_out(row, now) for row in rows],
        "enabled": bool(settings.report_schedules_enabled),
        "mailAvailable": mailer.mail_configured(settings),
        "maxPerOwner": report_schedules.MAX_PER_OWNER,
        "attachMaxBytes": int(settings.report_schedule_attach_max_bytes),
        "defaultTimezone": report_schedules.DEFAULT_TIMEZONE,
    }


@router.post("/data/report/schedules")
async def create_schedule(
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    try:
        weekday = report_schedules.weekday_of(body.get("weekday", report_schedules.DEFAULT_WEEKDAY))
        hour = report_schedules.hour_of(body.get("hourLocal", report_schedules.DEFAULT_HOUR))
        tz_name = report_schedules.timezone_of(body.get("timezone"))
        options = report_schedules.options_of(body.get("options"))
    except report_schedules.Invalid as bad:
        raise _refuse(bad) from None
    view_id = body.get("savedViewId")
    if not isinstance(view_id, str) or not view_id.strip():
        raise HTTPException(status_code=422, detail="Pick a saved view to schedule.")
    uid = str(current_user.id)
    view = await db.datasavedview.find_unique(where={"id": view_id.strip()})
    if not await report_schedules.may_schedule(current_user, view):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="View not found")
    if await db.datareportschedule.count(where={"ownerId": uid}) >= report_schedules.MAX_PER_OWNER:
        raise HTTPException(
            status_code=422,
            detail=f"You can keep {report_schedules.MAX_PER_OWNER} scheduled reports. Delete one first.",
        )
    now = datetime.now(UTC)
    schedule_id = report_schedules.new_id()
    row = await db.datareportschedule.create(
        data={
            "id": schedule_id,
            "ownerId": uid,
            "savedViewId": view.id,
            "weekday": weekday,
            "hourLocal": hour,
            "timezone": tz_name,
            "options": report_schedules.options_json(options),
            "lastSlotAt": report_schedules.latest_slot(now, weekday, hour, tz_name),
            "unsubscribeTokenHash": report_schedules.token_digest(
                report_schedules.unsubscribe_token(schedule_id)
            ),
        },
        include=_INCLUDE,
    )
    return report_schedules.schedule_out(row, now)


@router.patch("/data/report/schedules/{schedule_id}")
async def update_schedule(
    schedule_id: str,
    body: dict[str, Any] = Body(...),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    current = await report_schedules.owned(schedule_id, str(current_user.id))
    now = datetime.now(UTC)
    try:
        weekday = (
            report_schedules.weekday_of(body["weekday"]) if "weekday" in body else int(current.weekday)
        )
        hour = (
            report_schedules.hour_of(body["hourLocal"]) if "hourLocal" in body else int(current.hourLocal)
        )
        tz_name = (
            report_schedules.timezone_of(body["timezone"]) if "timezone" in body else current.timezone
        )
        options = report_schedules.options_of(body["options"]) if "options" in body else None
        paused = body.get("paused")
        if paused is not None and not isinstance(paused, bool):
            raise report_schedules.Invalid("paused must be true or false")
    except report_schedules.Invalid as bad:
        raise _refuse(bad) from None
    data: dict[str, Any] = {}
    timing = (weekday, hour, tz_name) != (int(current.weekday), int(current.hourLocal), current.timezone)
    if timing:
        data.update(weekday=weekday, hourLocal=hour, timezone=tz_name)
    if options is not None:
        data["options"] = report_schedules.options_json(options)
    resumed = paused is False and current.pausedAt is not None
    if paused is True and current.pausedAt is None:
        data["pausedAt"] = now
    if resumed:
        data["pausedAt"] = None
    if timing or resumed:
        data["lastSlotAt"] = report_schedules.settle_slot(current, now, weekday, hour, tz_name)
        data["retryAfter"] = None
    if not data:
        return report_schedules.schedule_out(current, now)
    row = await db.datareportschedule.update(where={"id": current.id}, data=data, include=_INCLUDE)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=report_schedules.NOT_FOUND)
    return report_schedules.schedule_out(row, now)


@router.delete("/data/report/schedules/{schedule_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_schedule(
    schedule_id: str, current_user: Any = Depends(require_dataset_downloader)
) -> Response:
    row = await report_schedules.owned(schedule_id, str(current_user.id))
    await db.datareportschedule.delete_many(where={"id": row.id})
    await report_schedules.drop_file(row.lastFileKey)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/data/report/schedules/{schedule_id}/file")
async def schedule_file(
    schedule_id: str, current_user: Any = Depends(require_dataset_downloader)
) -> dict[str, Any]:
    row = await report_schedules.owned(schedule_id, str(current_user.id))
    if not row.lastFileKey or not row.lastFileName:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No report has been sent yet.")
    try:
        await report_schedules.check_access(current_user, row.savedView)
    except report_schedules.AccessLost as lost:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=lost.reason) from None
    url = await asyncio.to_thread(
        s3.presign_get_url,
        row.lastFileKey,
        filename=row.lastFileName,
        mime_type=report_schedules.XLSX_MIME,
        expires_in=FILE_LINK_SECONDS,
    )
    return {
        "url": url,
        "fileName": row.lastFileName,
        "sizeBytes": int(row.lastFileBytes or 0),
        "sentAt": report_schedules.schedule_out(row)["lastSentAt"],
        "viewName": getattr(row.savedView, "name", None),
    }


def _token_of(body: dict[str, Any]) -> str:
    token = body.get("token") if isinstance(body, dict) else None
    return token if isinstance(token, str) else ""


async def _unsubscribe_row(token: str) -> Any:
    row = await report_schedules.by_token(token)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="This unsubscribe link is not valid any more. The schedule may have been deleted.",
        )
    return row


@router.post("/report-schedule/unsubscribe/lookup")
async def unsubscribe_lookup(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    return report_schedules.public_out(await _unsubscribe_row(_token_of(body)))


@router.post("/report-schedule/unsubscribe")
async def unsubscribe(body: dict[str, Any] = Body(...)) -> dict[str, Any]:
    token = _token_of(body)
    row = await _unsubscribe_row(token)
    if row.pausedAt is None:
        await db.datareportschedule.update_many(
            where={"id": row.id, "pausedAt": None}, data={"pausedAt": datetime.now(UTC)}
        )
        row = await _unsubscribe_row(token)
    return report_schedules.public_out(row)
