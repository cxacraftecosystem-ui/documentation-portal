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

RUN_FROM = time(2, 0)

CHECK_EVERY = timedelta(minutes=10)

_last_checked: dict[str, datetime] = {}

_last_scrubbed: dict[str, datetime] = {}


_IST = timezone(timedelta(hours=5, minutes=30), "IST")


def _zone(row: Any | None) -> tzinfo:
    name = getattr(row, "batchTimezone", None) or DEFAULT_TIMEZONE
    for candidate in (name, DEFAULT_TIMEZONE):
        try:
            return ZoneInfo(candidate)
        except Exception:  # noqa: BLE001
            continue
    return _IST


def night_start(now: datetime, zone: tzinfo) -> datetime | None:
    local = now.astimezone(zone)
    start = datetime.combine(local.date(), RUN_FROM, tzinfo=zone)
    if local < start:
        return None
    return start.astimezone(UTC)


def is_due(last_run: datetime | None, now: datetime, zone: tzinfo) -> bool:
    start = night_start(now, zone)
    if start is None:
        return False
    if last_run is None:
        return True
    if last_run.tzinfo is None:
        last_run = last_run.replace(tzinfo=UTC)
    return last_run < start


def auto_map_status(row: Any | None) -> dict[str, Any]:
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
    payload = await apply_workshop_mapping(rungs=CLEAR_CUT_RUNGS)
    by_bucket = {bucket["bucket"]: int(bucket.get("applied") or 0) for bucket in payload["buckets"]}
    return {"applied": sum(by_bucket.values()), "byBucket": by_bucket}


async def maybe_run_nightly_auto_map(now: datetime | None = None, *, force_check: bool = False) -> dict[str, Any] | None:
    now = now or datetime.now(UTC)
    last = _last_checked.get("at")
    if not force_check and last is not None and now - last < CHECK_EVERY:
        return None
    _last_checked["at"] = now

    row = await load_app_settings()
    if row is None:
        row = await get_or_create_app_settings(None)
    zone = _zone(row)
    await maybe_scrub_merge_snapshots(now, zone)
    if not getattr(row, "workshopAutoMapEnabled", True):
        return None
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
    return stored


async def maybe_scrub_merge_snapshots(now: datetime, zone: tzinfo) -> int | None:
    if not is_due(_last_scrubbed.get("at"), now, zone):
        return None
    try:
        scrubbed = await scrub_expired_merge_snapshots(now)
    except Exception:  # noqa: BLE001
        logger.exception("Scrubbing expired artisan-merge snapshots failed")
        return None
    _last_scrubbed["at"] = now
    return scrubbed
