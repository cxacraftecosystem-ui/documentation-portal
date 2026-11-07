import asyncio
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import invalidate_cached_user, require_admin
from app.schemas.access_roster import AccessRosterCreate, AccessRosterUpdate
from app.services.access_roster import (
    ADMITTING_STATUS,
    DECIDABLE_STATUSES,
    clean_text,
    ensure_admitted,
    is_master_admin_email,
    normalise_email,
    pending_count,
    role_value,
    roster_payload,
    status_value,
)
from app.services.access_roster import MAX_FULL_NAME_LENGTH, MAX_NOTES_LENGTH
from app.services import signin_role
from app.services.email_outbox import notify_access_granted
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains

from app.api.routes.users import (
    assert_can_manage_target,
    assert_may_grant_role,
    create_account,
    serialize_user,
)
from app.schemas.users import UserCreate

router = APIRouter(prefix="/access-roster", tags=["access-roster"])

_NOT_FOUND = "Record not found"

_MASTER_ADMIN_PROTECTED = (
    "The master admin address cannot be removed from the access roster. It is the break-glass "
    "account and the sign-in gate never applies to it."
)


async def _accounts_for(rows: list[Any]) -> dict[str, Any]:
    emails = [row.email for row in rows if row.email]
    if not emails:
        return {}
    accounts = await db.user.find_many(where={"email": {"in": emails}})
    return {str(account.email).lower(): account for account in accounts}



@router.get("/pending-count")
async def pending_requests_count(_: Any = Depends(require_admin)) -> dict[str, int]:
    return {"pending": await pending_count()}



@router.get("")
async def list_roster(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    status_filter: str | None = Query(default=None, alias="status"),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    where: dict[str, Any] = {}
    if status_filter:
        wanted = status_value(status_filter).upper()
        if wanted not in DECIDABLE_STATUSES | {"PENDING"}:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Unknown access status filter",
            )
        where["status"] = wanted
    if search:
        where["OR"] = [{"email": contains(search)}, {"fullName": contains(search)}]

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await asyncio.gather(
        db.accessroster.count(where=where),
        db.accessroster.find_many(
            where=where, skip=skip, take=clean_size, order={"lastRequestedAt": "desc"}
        ),
    )
    accounts = await _accounts_for(rows)
    items = [roster_payload(row, account=accounts.get(row.email)) for row in rows]
    return page_payload(items, total, clean_page, clean_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def add_to_roster(
    payload: AccessRosterCreate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    email = normalise_email(payload.email)
    if not email:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="An access roster entry needs an email address",
        )
    assert_may_grant_role(payload.grantedRole, current_user)

    existing = await db.accessroster.find_unique(where={"email": email})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{email} is already on the access roster with status "
                f"{status_value(existing.status)}. Update entry {existing.id} instead of adding it "
                f"again."
            ),
        )

    if payload.password:
        if not payload.isActive:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="An account made with a password is admitted at once. Leave it active, or add the address without a password.",
            )
        account, _extra = await create_account(
            current_user,
            UserCreate(
                email=email,
                name=(payload.fullName or "").strip() or email.split("@")[0],
                password=payload.password,
                passwordMode="SET",
                mustChangePassword=payload.mustChangePassword,
                role=payload.grantedRole or get_settings().default_signup_role,
            ),
            via="added this address to the access list with a password",
        )
        row = await db.accessroster.find_unique(where={"email": account.email})
        if row is not None and payload.notes:
            row = await db.accessroster.update(
                where={"id": row.id}, data={"notes": clean_text(payload.notes, MAX_NOTES_LENGTH)}
            )
        if payload.sendInvite:
            await notify_access_granted(email, name=payload.fullName)
        return {**roster_payload(row, account=account), "accountCreated": True, "account": serialize_user(account)}

    if payload.isActive:
        row = await ensure_admitted(
            email,
            granted_role=payload.grantedRole,
            full_name=payload.fullName,
            notes=payload.notes,
            decided_by_id=current_user.id,
        )
        if payload.sendInvite:
            await notify_access_granted(email, name=payload.fullName)
        held = await _role_kept(row.email, role_value(getattr(row, "grantedRole", None)))
        return {**roster_payload(row), "accountPromotionHeld": held}
    else:
        now = datetime.now(UTC)
        row = await db.accessroster.create(
            data={
                "email": email,
                "status": "PENDING",
                "grantedRole": payload.grantedRole or "CROWDSOURCE_VOLUNTEER",
                "fullName": clean_text(payload.fullName, MAX_FULL_NAME_LENGTH),
                "notes": clean_text(payload.notes, MAX_NOTES_LENGTH),
                "requestCount": 0,
                "firstRequestedAt": now,
                "lastRequestedAt": now,
            }
        )
    return roster_payload(row)


@router.patch("/{roster_id}")
async def update_roster_entry(
    roster_id: str,
    payload: AccessRosterUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    row = await db.accessroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_NOT_FOUND)

    values = payload.model_dump(exclude_unset=True)
    new_status = status_value(values.get("status")).upper() if values.get("status") else None
    if new_status and new_status not in DECIDABLE_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                "Choose May sign in, Refused or Suspended."
            ),
        )
    if new_status and new_status != ADMITTING_STATUS and is_master_admin_email(row.email):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=_MASTER_ADMIN_PROTECTED)
    if "grantedRole" in values:
        assert_may_grant_role(values.get("grantedRole"), current_user)
    readdressed = bool(values.get("email")) and normalise_email(values["email"]) != row.email
    if (new_status and new_status != ADMITTING_STATUS) or readdressed:
        await _assert_may_manage_holder(row.email, current_user)

    if new_status == ADMITTING_STATUS:
        updated = await ensure_admitted(
            row.email,
            granted_role=values.get("grantedRole"),
            full_name=values.get("fullName") if "fullName" in values else None,
            notes=values.get("notes") if "notes" in values else None,
            decided_by_id=current_user.id,
        )
        await _invalidate_account(row.email)
        if values.get("sendInvite"):
            await notify_access_granted(updated.email, name=getattr(updated, "fullName", None))
        held = await _role_kept(updated.email, role_value(getattr(updated, "grantedRole", None)))
        return {**roster_payload(updated), "accountPromotionHeld": held}

    data: dict[str, Any] = {}
    if "email" in values and values["email"]:
        email = normalise_email(values["email"])
        if email and email != row.email:
            clash = await db.accessroster.find_unique(where={"email": email})
            if clash is not None:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"{email} is already on the access roster as entry {clash.id}.",
                )
            data["email"] = email
    if "fullName" in values:
        data["fullName"] = clean_text(values["fullName"], MAX_FULL_NAME_LENGTH)
    if "notes" in values:
        data["notes"] = clean_text(values["notes"], MAX_NOTES_LENGTH)
    if "grantedRole" in values and values["grantedRole"]:
        data["grantedRole"] = values["grantedRole"]
    if new_status:
        data["status"] = new_status
        data["decidedAt"] = datetime.now(UTC)
        data["decidedById"] = current_user.id
    if not data:
        return roster_payload(row)

    updated = await db.accessroster.update(where={"id": roster_id}, data=data)
    if new_status:
        await _invalidate_account(row.email, end_sessions=new_status != ADMITTING_STATUS)
    return roster_payload(updated)


@router.delete("/{roster_id}")
async def suspend_roster_entry(
    roster_id: str, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    row = await db.accessroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_NOT_FOUND)
    if is_master_admin_email(row.email):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=_MASTER_ADMIN_PROTECTED)
    await _assert_may_manage_holder(row.email, current_user)
    if status_value(row.status) == "SUSPENDED":
        return roster_payload(row)

    updated = await db.accessroster.update(
        where={"id": roster_id},
        data={
            "status": "SUSPENDED",
            "decidedAt": row.decidedAt or datetime.now(UTC),
            "decidedById": current_user.id,
        },
    )
    await _invalidate_account(row.email, end_sessions=True)
    return roster_payload(updated)


async def _role_kept(email: str, role: str | None) -> str | None:
    if not role:
        return None
    address = normalise_email(email)
    account = await db.user.find_unique(where={"email": address})
    if account is None or role_value(getattr(account, "role", None)) == "MASTER_ADMIN":
        return None
    return signin_role.role_kept_sentence(address, role_value(getattr(account, "role", None)), role)


async def _assert_may_manage_holder(email: str, current_user: Any) -> None:
    account = await db.user.find_unique(where={"email": email})
    if account is not None:
        assert_can_manage_target(current_user, account)


async def _invalidate_account(email: str, *, end_sessions: bool = False) -> None:
    account = await db.user.find_unique(where={"email": email})
    if account is None:
        return
    if end_sessions:
        await db.user.update(where={"id": account.id}, data={"sessionsValidFrom": datetime.now(UTC)})
    invalidate_cached_user(account.id)
