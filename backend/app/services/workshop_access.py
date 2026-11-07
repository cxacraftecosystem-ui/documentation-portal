from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta, timezone
from typing import Any

from fastapi import HTTPException, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import get_value, is_admin

WORKSHOP_TIMEZONE = "Asia/Kolkata"
WORKSHOP_TZ = timezone(timedelta(hours=5, minutes=30))


def _as_utc(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _iso(value: Any) -> str | None:
    dt = _as_utc(value)
    return dt.isoformat() if dt else None


def _boundary_day(value: Any) -> date | None:
    dt = _as_utc(value)
    return dt.date() if dt else None


def workshop_local_day(moment: datetime) -> date:
    return moment.astimezone(WORKSHOP_TZ).date()


WORKSHOP_LEVELS: tuple[str, ...] = ("VIEW", "CONTRIBUTE", "EDIT")
WORKSHOP_LEVEL_ORDER: dict[str, int] = {"VIEW": 1, "CONTRIBUTE": 2, "EDIT": 3}

WORKSHOP_LEVEL_DESCRIPTIONS: dict[str, str] = {
    "VIEW": "Read this workshop's data. Cannot create or change records in it.",
    "CONTRIBUTE": "Everything in View, plus create records in this workshop (the normal level).",
    "EDIT": "Everything in Contribute, plus change other researchers' records in this workshop "
    "(every change is tracked in the edit history).",
}

WORKSHOP_STATUSES: tuple[str, ...] = ("PENDING", "GRANTED", "DENIED", "REVOKED")
WORKSHOP_DECISIONS: tuple[str, ...] = ("GRANTED", "DENIED", "REVOKED")

OPEN_WORKSHOP_LEVEL = "CONTRIBUTE"

DEFAULT_GRANT_LEVEL = "CONTRIBUTE"


def enum_str(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def level_rank(level: Any) -> int:
    return WORKSHOP_LEVEL_ORDER.get(enum_str(level) or "", 0)


def level_at_least(level: Any, minimum: str) -> bool:
    return level_rank(level) >= WORKSHOP_LEVEL_ORDER.get(minimum, 99)


def valid_level(level: Any) -> bool:
    return enum_str(level) in WORKSHOP_LEVEL_ORDER


@dataclass(frozen=True)
class WorkshopAccess:
    level: str | None = OPEN_WORKSHOP_LEVEL
    restricted: bool = False
    rowStatus: str | None = None
    rowLevel: str | None = None
    unrestricted: bool = False

    def at_least(self, minimum: str) -> bool:
        return level_at_least(self.level, minimum)


REFUSED_ROW_STATUSES = frozenset({"DENIED", "REVOKED"})


def workshop_is_curated(rows: Any) -> bool:
    return any(enum_str(r.status) == "GRANTED" and get_value(r, "assignedById") for r in rows)


async def resolve_workshop_access(user: Any, workshop_id: str) -> WorkshopAccess:
    rows = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    return access_from_rows(user, rows)


def access_from_rows(user: Any, rows: Any) -> WorkshopAccess:
    uid = get_value(user, "id")
    mine = next((r for r in rows if r.userId == uid), None)
    row_status = enum_str(mine.status) if mine is not None else None
    row_level = enum_str(mine.accessLevel) if mine is not None else None
    restricted = workshop_is_curated(rows)
    if is_admin(user):
        return WorkshopAccess("EDIT", restricted, row_status, row_level, unrestricted=True)
    granted = row_level if row_status == "GRANTED" else None
    if restricted or granted is not None or row_status in REFUSED_ROW_STATUSES:
        level = granted
    else:
        level = OPEN_WORKSHOP_LEVEL
    return WorkshopAccess(level, restricted, row_status, row_level)


async def recordable_workshop_ids(user: Any, workshop_ids: list[str]) -> set[str]:
    if not workshop_ids:
        return set()
    if is_admin(user):
        return set(workshop_ids)
    rows = await db.workshopassignment.find_many(where={"workshopId": {"in": list(workshop_ids)}})
    by_workshop: dict[str, list[Any]] = {wid: [] for wid in workshop_ids}
    for row in rows:
        by_workshop.setdefault(row.workshopId, []).append(row)
    return {wid for wid in workshop_ids if access_from_rows(user, by_workshop.get(wid, [])).at_least("CONTRIBUTE")}


async def workshop_assignee_ids(workshop_id: str) -> set[str]:
    rows = await db.workshopassignment.find_many(where={"workshopId": workshop_id, "status": "GRANTED"})
    return {r.userId for r in rows}


async def workshop_edit_privilege(user: Any, workshop_id: str | None) -> bool:
    if not workshop_id:
        return False
    access = await resolve_workshop_access(user, workshop_id)
    return access.at_least("EDIT")


async def assert_workshop_access(user: Any, workshop_id: str | None, minimum: str = "CONTRIBUTE") -> WorkshopAccess:
    if not workshop_id:
        return WorkshopAccess()
    access = await resolve_workshop_access(user, workshop_id)
    if not access.at_least(minimum):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=access_denied_detail(access, minimum))
    return access


def access_denied_detail(access: WorkshopAccess, minimum: str) -> str:
    if access.level is None:
        if access.rowStatus == "PENDING":
            return (
                "Your access request for this workshop is still waiting on an admin decision. "
                "You cannot add to it until the request is approved."
            )
        if access.rowStatus == "DENIED":
            return "Your access request for this workshop was denied. Ask an admin if you need it reconsidered."
        if access.rowStatus == "REVOKED":
            return "Your access to this workshop was revoked. Request access again if you still need it."
        return "You are not assigned to this workshop. Ask an admin to assign you to it."
    return (
        f"Your access to this workshop is {str(access.level).lower()}-only, which does not allow this. "
        f"Ask an admin to raise it to {minimum.lower()}."
    )


def merge_extra(data: dict[str, Any], fragment: dict[str, Any] | None) -> dict[str, Any]:
    if not fragment:
        return data
    existing = data.get("extraMetadata")
    base = dict(existing) if isinstance(existing, dict) else {}
    base.update(fragment)
    data["extraMetadata"] = base
    return data


@dataclass(frozen=True)
class WorkshopSubmissionCheck:
    workshopId: str | None = None
    title: str | None = None
    startDate: str | None = None
    endDate: str | None = None
    isOver: bool = False
    outOfWindow: bool = False
    needsAdminApproval: bool = False
    assigned: bool = True
    canSubmit: bool = True
    submittedAt: str = ""
    unrestricted: bool = False
    accessLevel: str | None = OPEN_WORKSHOP_LEVEL
    requestStatus: str | None = None
    restricted: bool = False
    canEdit: bool = False

    @property
    def notYetStarted(self) -> bool:
        return self.outOfWindow and not self.isOver

    @property
    def metadata(self) -> dict[str, Any]:
        if not self.workshopId or self.unrestricted:
            return {}
        return {
            "workshopSubmission": {
                "workshopId": self.workshopId,
                "outOfWindow": self.outOfWindow,
                "needsAdminApproval": self.needsAdminApproval,
                "submittedAt": self.submittedAt,
                "endDate": self.endDate,
                "windowStart": self.startDate,
                "windowEnd": self.endDate,
            }
        }

    def payload(self) -> dict[str, Any]:
        return {
            "workshopId": self.workshopId,
            "title": self.title,
            "endDate": self.endDate,
            "isOver": self.isOver,
            "outOfWindow": self.outOfWindow,
            "needsAdminApproval": self.needsAdminApproval,
            "assigned": self.assigned,
            "canSubmit": self.canSubmit,
            "accessLevel": self.accessLevel,
            "requestStatus": self.requestStatus,
            "restricted": self.restricted,
            "canEdit": self.canEdit,
        }


async def describe_workshop_submission(
    user: Any, workshop_id: str | None, *, when: datetime | None = None, need: str = "CONTRIBUTE"
) -> WorkshopSubmissionCheck:
    now = when or datetime.now(UTC)
    if not workshop_id:
        return WorkshopSubmissionCheck(submittedAt=now.isoformat())
    workshop = await db.workshop.find_unique(where={"id": workshop_id})
    if workshop is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop not found")
    today = workshop_local_day(now)
    start_day = _boundary_day(workshop.startDate or workshop.date)
    end_day = _boundary_day(workshop.endDate or workshop.date)
    is_over = bool(end_day and today > end_day)
    not_started = bool(start_day and today < start_day)
    out_of_window = is_over or not_started
    admin = is_admin(user)
    access = await resolve_workshop_access(user, workshop_id)
    return WorkshopSubmissionCheck(
        workshopId=workshop_id,
        title=workshop.title,
        startDate=_iso(workshop.startDate or workshop.date),
        endDate=_iso(workshop.endDate or workshop.date),
        isOver=is_over,
        outOfWindow=out_of_window,
        needsAdminApproval=out_of_window and not admin,
        assigned=access.level is not None,
        canSubmit=access.at_least(need),
        submittedAt=now.isoformat(),
        unrestricted=admin,
        accessLevel=access.level,
        requestStatus=access.rowStatus,
        restricted=access.restricted,
        canEdit=access.at_least("EDIT"),
    )


async def enforce_workshop_submission(
    user: Any, workshop_id: str | None, *, when: datetime | None = None, need: str = "CONTRIBUTE"
) -> WorkshopSubmissionCheck:
    check = await describe_workshop_submission(user, workshop_id, when=when, need=need)
    if not check.canSubmit:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=access_denied_detail(
                WorkshopAccess(check.accessLevel, check.restricted, check.requestStatus), need
            ),
        )
    return check


def stored_submission_stamp(record: Any) -> dict[str, Any] | None:
    extra = get_value(record, "extraMetadata")
    stamp = extra.get("workshopSubmission") if isinstance(extra, dict) else None
    return stamp if isinstance(stamp, dict) else None


def record_needs_admin_approval(record: Any) -> bool:
    stamp = stored_submission_stamp(record)
    return bool(stamp and stamp.get("needsAdminApproval"))


def stamp_workshop_submission(
    data: dict[str, Any], check: WorkshopSubmissionCheck | None = None, record: Any = None
) -> dict[str, Any]:
    fragment = check.metadata if check is not None else {}
    if fragment:
        if record is not None and record_needs_admin_approval(record):
            fragment = {
                "workshopSubmission": {
                    **fragment["workshopSubmission"],
                    "needsAdminApproval": True,
                    "relinkedFrom": (stored_submission_stamp(record) or {}).get("workshopId"),
                }
            }
        return merge_extra(data, fragment)
    carried = stored_submission_stamp(record) if record is not None else None
    if carried is not None:
        return merge_extra(data, {"workshopSubmission": carried})
    existing = data.get("extraMetadata")
    if isinstance(existing, dict) and "workshopSubmission" in existing:
        data["extraMetadata"] = {k: v for k, v in existing.items() if k != "workshopSubmission"}
    return data


def pin_pending_if_late(
    data: dict[str, Any],
    user: Any,
    check: WorkshopSubmissionCheck | None = None,
    record: Any = None,
) -> dict[str, Any]:
    if is_admin(user):
        return data
    late = bool(check is not None and check.needsAdminApproval)
    if not late and record is not None:
        late = record_needs_admin_approval(record)
    if not late:
        return data
    if record is not None:
        if get_value(record, "status") is None:
            return data
    elif "status" not in data:
        return data
    data["status"] = "PENDING"
    return data


async def link_workshop_artisan(workshop_id: str | None, artisan_id: str) -> None:
    if not workshop_id or not artisan_id:
        return
    existing = await db.workshopartisan.find_unique(
        where={"workshopId_artisanId": {"workshopId": workshop_id, "artisanId": artisan_id}}
    )
    if existing is not None:
        return
    try:
        await db.workshopartisan.create(data={"workshopId": workshop_id, "artisanId": artisan_id})
    except UniqueViolationError:
        pass


async def link_workshop_craft(workshop_id: str | None, craft_id: str) -> None:
    if not workshop_id or not craft_id:
        return
    existing = await db.workshopcraft.find_unique(
        where={"workshopId_craftId": {"workshopId": workshop_id, "craftId": craft_id}}
    )
    if existing is not None:
        return
    try:
        await db.workshopcraft.create(data={"workshopId": workshop_id, "craftId": craft_id})
    except UniqueViolationError:
        pass
