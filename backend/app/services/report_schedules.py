from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import importlib
import importlib.util
import logging
import re
import uuid
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta, timezone, tzinfo
from typing import Any
from zoneinfo import ZoneInfo

from app.core.config import Settings, get_settings
from app.core.db import db
from app.core.roles import can_download_dataset
from app.services import email_outbox, mailer, naming, s3
from app.services.data_explorer import report_options, saved
from prisma import Json

logger = logging.getLogger(__name__)

DEFAULT_TIMEZONE = "Asia/Kolkata"
DEFAULT_WEEKDAY = 0
DEFAULT_HOUR = 8
MAX_PER_OWNER = 20
TIMEZONE_MAX = 64
CHECK_EVERY = timedelta(minutes=5)
RETRY_AFTER = timedelta(minutes=30)
GIVE_UP_AFTER = timedelta(days=2)
SENDS_PER_PASS = 3
BATCH_SIZE = 200
STORAGE_PREFIX = "report-schedules"
XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
LOCALES = ("en", "hi")
EXTRA_KEY_MAX = 40
EXTRAS_MAX = 40

WEEKDAYS = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

_IST = timezone(timedelta(hours=5, minutes=30), "IST")
_EXTRA_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9_-]*$")
_FILENAME = re.compile(r'filename="([^"]+)"')

LOST_DOWNLOAD = "Your account can no longer download data, so this report was not sent."
LOST_VIEW = "This saved view is no longer shared with you, so this report was not sent."
LOST_FOLDER = "You can no longer see this folder, so this report was not sent."
LOST_SHEETS = "None of the chosen sheets are in this folder any more, so this report was not sent."
NO_ADDRESS = "Your account has no e-mail address, so this report was not sent."
NOT_BUILT = "The report could not be built this time. It tries again shortly."
NOT_FOUND = "Schedule not found"

_last_check: datetime | None = None


class Invalid(ValueError):
    pass


class AccessLost(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def zone(name: str | None) -> tzinfo:
    try:
        return ZoneInfo(name or DEFAULT_TIMEZONE)
    except Exception:  # noqa: BLE001 - unknown zone or no tz database on this host
        try:
            return ZoneInfo(DEFAULT_TIMEZONE)
        except Exception:  # noqa: BLE001 - no tz database at all
            return _IST


def _aware(now: datetime | None) -> datetime:
    moment = now or datetime.now(UTC)
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def latest_slot(now: datetime | None, weekday: int, hour: int, tz_name: str | None) -> datetime:
    where = zone(tz_name)
    local = _aware(now).astimezone(where)
    day = local.date() - timedelta(days=(local.weekday() - weekday) % 7)
    slot = datetime.combine(day, time(hour), tzinfo=where)
    if slot > local:
        slot = datetime.combine(day - timedelta(days=7), time(hour), tzinfo=where)
    return slot.astimezone(UTC)


def next_slot(now: datetime | None, weekday: int, hour: int, tz_name: str | None) -> datetime:
    where = zone(tz_name)
    last = latest_slot(now, weekday, hour, tz_name).astimezone(where)
    day: date = last.date() + timedelta(days=7)
    return datetime.combine(day, time(hour), tzinfo=where).astimezone(UTC)


def _utc(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def due_slot(row: Any, now: datetime | None = None) -> datetime | None:
    if getattr(row, "pausedAt", None) is not None:
        return None
    now = _aware(now)
    slot = latest_slot(now, int(row.weekday), int(row.hourLocal), row.timezone)
    claimed = _utc(getattr(row, "lastSlotAt", None))
    if claimed is not None and claimed >= slot:
        return None
    retry = _utc(getattr(row, "retryAfter", None))
    if retry is not None and retry > now:
        return None
    if now - slot > GIVE_UP_AFTER:
        return None
    return slot


def settle_slot(row: Any, now: datetime | None, weekday: int, hour: int, tz_name: str) -> datetime:
    slot = latest_slot(now, weekday, hour, tz_name)
    claimed = _utc(getattr(row, "lastSlotAt", None)) if row is not None else None
    return claimed if claimed is not None and claimed > slot else slot


def zone_label(tz_name: str | None) -> str:
    return "IST" if (tz_name or DEFAULT_TIMEZONE) == DEFAULT_TIMEZONE else str(tz_name)


def schedule_label(weekday: int, hour: int, tz_name: str | None) -> str:
    return f"every {WEEKDAYS[int(weekday) % 7]} at {int(hour):02d}:00 {zone_label(tz_name)}"


def weekday_of(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 6:
        raise Invalid("weekday takes 0 (Monday) to 6 (Sunday).")
    return value


def hour_of(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 23:
        raise Invalid("hourLocal takes 0 to 23.")
    return value


def is_valid_timezone(name: str) -> bool:
    try:
        ZoneInfo(name)
    except Exception:  # noqa: BLE001 - unknown zone, malformed name, or no tz database at all
        try:
            ZoneInfo(DEFAULT_TIMEZONE)
        except Exception:  # noqa: BLE001 - no tz database: nothing but the fallback is knowable
            return name == DEFAULT_TIMEZONE
        return False
    return True


def timezone_of(value: Any) -> str:
    if value is None or (isinstance(value, str) and not value.strip()):
        return DEFAULT_TIMEZONE
    if not isinstance(value, str) or len(value.strip()) > TIMEZONE_MAX:
        raise Invalid("timezone takes a time zone name such as Asia/Kolkata.")
    name = value.strip()
    if not is_valid_timezone(name):
        raise Invalid("timezone takes a time zone name such as Asia/Kolkata.")
    return name


def options_of(value: Any) -> dict[str, Any]:
    if value is None:
        value = {}
    if not isinstance(value, dict):
        raise Invalid("options must be an object")
    try:
        stored = report_options.stored_options(value)
    except report_options.OptionsInvalid as bad:
        raise Invalid(str(bad)) from None
    locale = value.get("locale") or "en"
    if locale not in LOCALES:
        raise Invalid(f"options.locale takes {', '.join(LOCALES)}")
    thumbnails = value.get("thumbnails", False)
    if not isinstance(thumbnails, bool):
        raise Invalid("options.thumbnails must be true or false")
    raw_extras = value.get("extras") or {}
    if not isinstance(raw_extras, dict) or len(raw_extras) > EXTRAS_MAX:
        raise Invalid("options.extras must be an object of switches")
    extras: dict[str, bool] = {}
    for key, flag in raw_extras.items():
        if not isinstance(key, str) or len(key) > EXTRA_KEY_MAX or not _EXTRA_KEY.match(key):
            raise Invalid("options.extras takes switch names")
        if not isinstance(flag, bool):
            raise Invalid("options.extras takes true or false")
        extras[key] = flag
    return {**stored, "locale": locale, "thumbnails": thumbnails, "extras": extras}


def stored_options_of(row: Any) -> dict[str, Any]:
    raw = getattr(row, "options", None)
    try:
        return options_of(raw if isinstance(raw, dict) else {})
    except Invalid:
        return options_of({})


def unsubscribe_token(schedule_id: str, settings: Settings | None = None) -> str:
    key = (settings or get_settings()).jwt_secret.encode("utf-8")
    mac = hmac.new(key, f"report-schedule-unsubscribe:{schedule_id}".encode(), hashlib.sha256)
    return base64.urlsafe_b64encode(mac.digest()).decode("ascii").rstrip("=")


def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def clean_token(token: str | None) -> str | None:
    text = (token or "").strip()
    if not text or len(text) > 200 or not text.isascii() or not text.isprintable():
        return None
    return text


def new_id() -> str:
    return f"rs{uuid.uuid4().hex}"


def _iso(value: Any) -> str | None:
    moment = _utc(value)
    return moment.isoformat() if moment is not None else None


def schedule_out(row: Any, now: datetime | None = None) -> dict[str, Any]:
    view = getattr(row, "savedView", None)
    paused = getattr(row, "pausedAt", None) is not None
    weekday, hour, tz_name = int(row.weekday), int(row.hourLocal), str(row.timezone)
    last_file = None
    if getattr(row, "lastFileName", None):
        last_file = {
            "fileName": row.lastFileName,
            "sizeBytes": int(row.lastFileBytes or 0),
            "attached": bool(row.lastAttached),
        }
    return {
        "id": row.id,
        "savedViewId": row.savedViewId,
        "viewName": getattr(view, "name", None),
        "path": getattr(view, "path", None),
        "weekday": weekday,
        "hourLocal": hour,
        "timezone": tz_name,
        "label": schedule_label(weekday, hour, tz_name),
        "options": stored_options_of(row),
        "paused": paused,
        "nextRunAt": None if paused else _iso(_next_after(row, now)),
        "lastSentAt": _iso(getattr(row, "lastSentAt", None)),
        "lastError": getattr(row, "lastError", None),
        "lastFile": last_file,
        "createdAt": _iso(getattr(row, "createdAt", None)),
    }


def _next_after(row: Any, now: datetime | None) -> datetime:
    now = _aware(now)
    weekday, hour, tz_name = int(row.weekday), int(row.hourLocal), str(row.timezone)
    slot = latest_slot(now, weekday, hour, tz_name)
    claimed = _utc(getattr(row, "lastSlotAt", None))
    if (claimed is None or claimed < slot) and now - slot <= GIVE_UP_AFTER:
        retry = _utc(getattr(row, "retryAfter", None))
        return retry if retry is not None and retry > now else now
    return next_slot(now, weekday, hour, tz_name)


def file_name_of(disposition: str | None) -> str:
    found = _FILENAME.search(disposition or "")
    if found and found.group(1).lower().endswith(".xlsx"):
        return found.group(1)
    return naming.export_filename("Dataset", "Report", "xlsx")


def _browser() -> Any:
    from app.api.routes import data_browser

    return data_browser


def _core_builder() -> Any | None:
    if importlib.util.find_spec("app.services.datareport.build") is None:
        return None
    return importlib.import_module("app.services.datareport.build")


async def build_workbook(
    owner: Any, path: str, options: dict[str, Any], q: str | None
) -> tuple[bytes, str]:
    core = _core_builder()
    if core is not None:
        return await core.build_xlsx(owner, path, core.options_from({**options, "q": q}))
    picked = report_options.parse(
        sheets=options.get("sheets"),
        answers=options.get("answers"),
        rows=options.get("rows"),
        links=options.get("links"),
        q=q,
    )
    response = await _browser()._build_report(path, "xlsx", None, owner, q=q, options=picked)
    return bytes(response.body), file_name_of(response.headers.get("content-disposition"))


async def mark_delivered(owner: Any, path: str) -> None:
    core = _core_builder()
    if core is None:
        return
    try:
        await core.delivered(owner, path)
    except Exception:
        logger.exception("report schedule: the delivery stamp for %s failed", path)


async def _visible(scope: Any, path: str) -> bool:
    from fastapi import HTTPException

    try:
        await _browser()._list_level(path, scope)
    except HTTPException:
        return False
    return True


BARRED_STATUSES = frozenset({"REJECTED", "SUSPENDED"})


async def _barred(owner: Any) -> bool:
    from app.services import access_roster

    email = getattr(owner, "email", None)
    if access_roster.is_master_admin_email(email):
        return False
    if access_roster.role_value(getattr(owner, "role", None)) == "MASTER_ADMIN":
        return False
    row = await access_roster.roster_row(email)
    return row is not None and access_roster.status_value(row.status) in BARRED_STATUSES


async def may_schedule(user: Any, view: Any) -> bool:
    if view is None:
        return False
    if str(view.ownerId) == str(user.id):
        return True
    if not view.shared:
        return False
    return await _visible(await _browser()._scope_for(user), view.path)


async def check_access(owner: Any, view: Any) -> None:
    if owner is None or not can_download_dataset(owner) or await _barred(owner):
        raise AccessLost(LOST_DOWNLOAD)
    if view is None or (str(view.ownerId) != str(owner.id) and not view.shared):
        raise AccessLost(LOST_VIEW)
    if not await _visible(await _browser()._scope_for(owner), view.path):
        raise AccessLost(LOST_FOLDER)


def storage_key_for(schedule_id: str, file_name: str) -> str:
    return f"{STORAGE_PREFIX}/{schedule_id}/{uuid.uuid4().hex}/{s3.safe_filename(file_name)}"


async def drop_file(key: str | None) -> None:
    if not key:
        return
    try:
        await asyncio.to_thread(s3.delete_object, key)
    except Exception:  # noqa: BLE001 - a stale file must not fail the send
        logger.warning("report schedule: could not delete the previous file %s", key)


@dataclass
class Outcome:
    status: str
    error: str | None = None


async def _record(row_id: str, data: dict[str, Any]) -> None:
    await db.datareportschedule.update(where={"id": row_id}, data=data)


async def deliver(row: Any, now: datetime, settings: Settings) -> Outcome:
    from fastapi import HTTPException

    owner = await db.user.find_unique(where={"id": row.ownerId})
    view = await db.datasavedview.find_unique(where={"id": row.savedViewId})
    try:
        await check_access(owner, view)
    except AccessLost as lost:
        await _record(row.id, {"lastError": lost.reason, "retryAfter": None})
        logger.info("report schedule %s skipped: %s", row.id, lost.reason)
        return Outcome("SKIPPED", lost.reason)
    address = str(getattr(owner, "email", "") or "").strip()
    if not address:
        await _record(row.id, {"lastError": NO_ADDRESS, "retryAfter": None})
        return Outcome("SKIPPED", NO_ADDRESS)
    state = view.state if isinstance(view.state, dict) else {}
    try:
        state = saved.state_of(state)
    except saved.Invalid:
        state = saved.state_of({})
    options = stored_options_of(row)
    try:
        blob, file_name = await build_workbook(owner, view.path, options, state.get("q"))
    except HTTPException as refused:
        if refused.status_code == 404:
            await _record(row.id, {"lastError": LOST_SHEETS, "retryAfter": None})
            return Outcome("SKIPPED", LOST_SHEETS)
        raise
    if blob[:2] != b"PK":
        raise RuntimeError("the report builder did not return a workbook")
    key = storage_key_for(row.id, file_name)
    await asyncio.to_thread(s3.put_object_bytes, key, blob, mime_type=XLSX_MIME)
    attached = len(blob) <= max(int(settings.report_schedule_attach_max_bytes or 0), 0)
    token = unsubscribe_token(row.id, settings)
    digest = token_digest(token)
    params: dict[str, Any] = {
        "recipientName": getattr(owner, "name", None),
        "scheduleId": row.id,
        "viewId": view.id,
        "viewName": view.name,
        "path": view.path,
        "fileName": file_name,
        "sizeBytes": len(blob),
        "generatedAt": now.isoformat(),
        "scheduleLabel": schedule_label(int(row.weekday), int(row.hourLocal), row.timezone),
    }
    if attached:
        params["attachmentKey"] = key
        params["attachmentMime"] = XLSX_MIME
    message = await email_outbox.enqueue(
        mailer.REPORT_SCHEDULE,
        to_address=address,
        recipient_id=str(owner.id),
        params=params,
        secret=token,
        settings=settings,
    )
    if message is None:
        await drop_file(key)
        raise RuntimeError("the e-mail could not be queued")
    await mark_delivered(owner, view.path)
    previous = getattr(row, "lastFileKey", None)
    data: dict[str, Any] = {
        "lastSentAt": now,
        "lastError": None,
        "retryAfter": None,
        "lastFileKey": key,
        "lastFileName": file_name,
        "lastFileBytes": len(blob),
        "lastAttached": attached,
    }
    if digest != getattr(row, "unsubscribeTokenHash", None):
        data["unsubscribeTokenHash"] = digest
    await _record(row.id, data)
    if previous and previous != key:
        await drop_file(previous)
    logger.info(
        "report schedule %s sent to account %s (%s bytes, %s)",
        row.id,
        owner.id,
        len(blob),
        "attached" if attached else "linked",
    )
    return Outcome("SENT")


async def _claim(row: Any, slot: datetime) -> bool:
    claimed = await db.datareportschedule.update_many(
        where={
            "id": row.id,
            "pausedAt": None,
            "OR": [{"lastSlotAt": None}, {"lastSlotAt": {"lt": slot}}],
        },
        data={"lastSlotAt": slot},
    )
    return bool(claimed)


async def _release(row: Any, slot: datetime, now: datetime, error: str) -> None:
    await db.datareportschedule.update_many(
        where={"id": row.id, "lastSlotAt": slot},
        data={
            "lastSlotAt": getattr(row, "lastSlotAt", None),
            "retryAfter": now + RETRY_AFTER,
            "lastError": error,
        },
    )


async def run_once(row: Any, now: datetime, settings: Settings) -> str:
    slot = due_slot(row, now)
    if slot is None:
        return "NOT_DUE"
    if not await _claim(row, slot):
        return "EXISTS"
    try:
        outcome = await deliver(row, now, settings)
    except Exception:
        logger.exception("report schedule %s failed; it tries again after %s", row.id, RETRY_AFTER)
        await _release(row, slot, now, NOT_BUILT)
        return "FAILED"
    return outcome.status


async def run_report_schedules(
    now: datetime | None = None, *, force: bool = False, settings: Settings | None = None
) -> dict[str, Any]:
    global _last_check
    settings = settings or get_settings()
    if not settings.report_schedules_enabled:
        return {"ran": False, "reason": "disabled"}
    if not mailer.mail_configured(settings):
        return {"ran": False, "reason": "mail not configured"}
    now = _aware(now)
    if not force and _last_check is not None and now - _last_check < CHECK_EVERY:
        return {"ran": False, "reason": "throttled"}
    tally: dict[str, Any] = {"ran": True, "sent": 0, "skipped": 0, "failed": 0, "existing": 0}
    sends = 0
    after: str | None = None
    full = False
    while not full:
        where: dict[str, Any] = {"pausedAt": None}
        if after:
            where = {"AND": [where, {"id": {"gt": after}}]}
        rows = await db.datareportschedule.find_many(where=where, order={"id": "asc"}, take=BATCH_SIZE)
        if not rows:
            break
        after = rows[-1].id
        for row in rows:
            if due_slot(row, now) is None:
                continue
            if sends >= SENDS_PER_PASS:
                full = True
                break
            status = await run_once(row, now, settings)
            if status == "EXISTS":
                tally["existing"] += 1
                continue
            sends += 1
            key = {"SENT": "sent", "SKIPPED": "skipped"}.get(status, "failed")
            tally[key] += 1
        if len(rows) < BATCH_SIZE:
            break
    if not full:
        _last_check = now
    if sends:
        logger.info("report schedule pass %s", tally)
    return tally


async def owned(schedule_id: str, user_id: str) -> Any:
    from fastapi import HTTPException

    row = await db.datareportschedule.find_unique(
        where={"id": schedule_id}, include={"savedView": True}
    )
    if row is None or str(row.ownerId) != str(user_id):
        raise HTTPException(status_code=404, detail=NOT_FOUND)
    return row


async def by_token(token: str | None) -> Any | None:
    clean = clean_token(token)
    if clean is None:
        return None
    return await db.datareportschedule.find_unique(
        where={"unsubscribeTokenHash": token_digest(clean)}, include={"savedView": True}
    )


def public_out(row: Any) -> dict[str, Any]:
    view = getattr(row, "savedView", None)
    return {
        "viewName": getattr(view, "name", None),
        "label": schedule_label(int(row.weekday), int(row.hourLocal), row.timezone),
        "paused": getattr(row, "pausedAt", None) is not None,
    }


def options_json(options: dict[str, Any]) -> Any:
    return Json(options)
