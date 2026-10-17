"""The nightly workshop filing (A6): clear-cut records are filed without anybody pressing a button.

WHAT IT FILES. Only rows the ladder in ``services/workshop_inference`` decides on the PARENT rung — a
clip whose interview names a workshop, a process whose product does. That is not inference; it is the
same fact read off the record the row hangs off, so there is nothing for an admin to judge. Rows the
artisans or the dates merely SUGGEST, and rows nothing settles, are left for the admin card, which now
leads with how many of those there are.

WHEN. Once per local night, on the first worker iteration at or after ``RUN_FROM`` in the deployment's
timezone (the transcription batch window's, Asia/Kolkata by default). A worker that was down overnight
runs it on its first pass of the day instead — late is fine, skipped is not.

ONE RUN PER NIGHT, WHATEVER IS RUNNING. The night is claimed by a conditional update on
``AppSetting.workshopAutoMapLastRunAt`` (only a row whose stamp is older than tonight's start matches),
so two worker processes, or a restart mid-night, can never both run it. The writes themselves are the
ladder's own idempotent ``workshopId: None`` updates, so even a double run would change nothing twice.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime, time, timedelta, timezone, tzinfo
from typing import Any
from zoneinfo import ZoneInfo

from prisma import Json

from app.core.db import db
from app.services.artisan_merge import scrub_expired_merge_snapshots
from app.services.app_settings import DEFAULT_TIMEZONE, SINGLETON_ID, get_or_create_app_settings, load_app_settings
from app.services.workshop_inference import CLEAR_CUT_RUNGS, apply_workshop_mapping

logger = logging.getLogger(__name__)

#: Local time the night's run may start from. 02:00 — after the day's uploads have synced, and the
#: same hour the transcription batch window opens by default.
RUN_FROM = time(2, 0)

#: How often the worker loop even asks. The loop turns every few seconds; reading the settings row
#: that often to learn "not yet" would be a cross-region round trip for nothing.
CHECK_EVERY = timedelta(minutes=10)

_last_checked: dict[str, datetime] = {}


#: India Standard Time as a fixed offset — the fallback when the zone database cannot be read (a
#: Windows checkout without ``tzdata``) or a stored name is not a zone. IST has no daylight saving, so
#: the fixed offset is exact for the default.
_IST = timezone(timedelta(hours=5, minutes=30), "IST")


def _zone(row: Any | None) -> tzinfo:
    name = getattr(row, "batchTimezone", None) or DEFAULT_TIMEZONE
    for candidate in (name, DEFAULT_TIMEZONE):
        try:
            return ZoneInfo(candidate)
        except Exception:  # noqa: BLE001 - a bad stored name must not stop the job
            continue
    return _IST


def night_start(now: datetime, zone: tzinfo) -> datetime | None:
    """The instant tonight's run became due, in UTC, or None while it is not due yet today.

    Pure, so the window is tested without a clock: before ``RUN_FROM`` local time the answer is None
    (yesterday's run already covered last night); from ``RUN_FROM`` on it is today's ``RUN_FROM``.
    """
    local = now.astimezone(zone)
    start = datetime.combine(local.date(), RUN_FROM, tzinfo=zone)
    if local < start:
        return None
    return start.astimezone(UTC)


def is_due(last_run: datetime | None, now: datetime, zone: tzinfo) -> bool:
    """Has tonight's run not happened yet? Pure; mirrors the claim's ``where``."""
    start = night_start(now, zone)
    if start is None:
        return False
    if last_run is None:
        return True
    if last_run.tzinfo is None:
        last_run = last_run.replace(tzinfo=UTC)
    return last_run < start


def auto_map_status(row: Any | None) -> dict[str, Any]:
    """What the admin card says about the job: on or off, and what the last night filed."""
    result = getattr(row, "workshopAutoMapLastResult", None) if row is not None else None
    if result is not None and hasattr(result, "data"):
        result = result.data
    last_run = getattr(row, "workshopAutoMapLastRunAt", None) if row is not None else None
    return {
        "enabled": bool(getattr(row, "workshopAutoMapEnabled", True)) if row is not None else True,
        "runsAt": RUN_FROM.strftime("%H:%M"),
        "timezone": getattr(row, "batchTimezone", None) or DEFAULT_TIMEZONE,
        "lastRunAt": last_run.isoformat() if isinstance(last_run, datetime) else None,
        "lastFiled": int((result or {}).get("applied") or 0) if isinstance(result, dict) else 0,
    }


async def set_auto_map_enabled(enabled: bool, updated_by_id: str | None) -> Any:
    await get_or_create_app_settings(updated_by_id)
    return await db.appsetting.update(
        where={"id": SINGLETON_ID},
        data={"workshopAutoMapEnabled": bool(enabled), "updatedById": updated_by_id},
    )


async def run_clear_cut_mapping() -> dict[str, Any]:
    """File every PARENT-rung row now, and return {"applied", "byBucket"}."""
    payload = await apply_workshop_mapping(rungs=CLEAR_CUT_RUNGS)
    by_bucket = {bucket["bucket"]: int(bucket.get("applied") or 0) for bucket in payload["buckets"]}
    return {"applied": sum(by_bucket.values()), "byBucket": by_bucket}


async def maybe_run_nightly_auto_map(now: datetime | None = None, *, force_check: bool = False) -> dict[str, Any] | None:
    """Run tonight's filing if it is due and this process wins the claim. Returns what it filed.

    Cheap when there is nothing to do: at most one settings read per ``CHECK_EVERY``.
    """
    now = now or datetime.now(UTC)
    last = _last_checked.get("at")
    if not force_check and last is not None and now - last < CHECK_EVERY:
        return None
    _last_checked["at"] = now

    row = await load_app_settings()
    if row is None:
        row = await get_or_create_app_settings(None)
    if not getattr(row, "workshopAutoMapEnabled", True):
        return None
    zone = _zone(row)
    if not is_due(getattr(row, "workshopAutoMapLastRunAt", None), now, zone):
        return None
    start = night_start(now, zone)
    claimed = await db.appsetting.update_many(
        where={
            "id": SINGLETON_ID,
            "OR": [
                {"workshopAutoMapLastRunAt": None},
                {"workshopAutoMapLastRunAt": {"lt": start}},
            ],
        },
        data={"workshopAutoMapLastRunAt": now},
    )
    if not claimed:
        return None
    result = await run_clear_cut_mapping()
    stored = {"at": now.isoformat(), **result}
    await db.appsetting.update(
        where={"id": SINGLETON_ID}, data={"workshopAutoMapLastResult": Json(stored)}
    )
    logger.info("Nightly workshop filing: %s record(s) filed", result["applied"])
    # The same night's other piece of hygiene: merges past their undo window stop holding a raw copy
    # of anybody's identity numbers (services/artisan_merge).
    try:
        await scrub_expired_merge_snapshots(now)
    except Exception:  # noqa: BLE001 - the filing above already ran; this retries tomorrow
        logger.exception("Scrubbing expired artisan-merge snapshots failed")
    return stored
