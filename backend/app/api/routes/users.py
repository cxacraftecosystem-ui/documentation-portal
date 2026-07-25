from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.config import get_settings
from app.core.deps import (
    ROLE_RANK,
    get_current_user,
    is_admin,
    is_master_admin,
    require_admin,
    require_professor,
    role_rank,
)
from app.core.security import hash_password
from app.schemas.users import UserCreate, UserUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains

router = APIRouter(prefix="/users", tags=["users"])

ALLOWED_ROLES = set(ROLE_RANK)


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    return payload


def assert_role(role: str | None, current_user: Any) -> None:
    """A user may assign roles at or below their own tier: admins promote to their level and
    beneath; only the master admin can mint MASTER_ADMIN."""
    if not role:
        return
    if role not in ALLOWED_ROLES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid user role")
    if role == "MASTER_ADMIN" and not is_master_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the master admin can grant master admin")
    if ROLE_RANK[role] > role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only assign roles at or below your own tier",
        )


def assert_can_manage_target(current_user: Any, target_user: Any) -> None:
    """Only the master admin may manage peers; everyone else manages strictly lower tiers.
    This blocks one admin from silently rewriting another admin's account."""
    if is_master_admin(current_user):
        return
    if role_rank(target_user) >= role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only manage users below your own tier",
        )


def is_master_email(email: str | None) -> bool:
    if not email:
        return False
    return email.lower() == get_settings().master_admin_email.lower()


def assert_not_demoting_master(target_user: Any, payload_role: str | None, current_user: Any) -> None:
    if not is_master_email(target_user.email):
        return
    if not is_master_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account is protected")
    if payload_role and payload_role != "MASTER_ADMIN":
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="The master admin must keep MASTER_ADMIN role")


@router.get("/directory")
async def user_directory(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
) -> list[dict[str, Any]]:
    """A minimal directory of all users (id, name, email, role) readable by ANY authenticated user.

    Powers the data-access "request access from a researcher" picker, where a non-admin needs to choose
    a colleague. Returns no privileges or password material — just enough to identify a person.
    """
    where: dict[str, Any] = {}
    if search:
        where["OR"] = [{"name": contains(search)}, {"email": contains(search)}]
    users = await db.user.find_many(where=where, order={"name": "asc"}, take=500)
    return [
        {"id": u.id, "name": u.name, "email": u.email, "role": str(getattr(u.role, "value", u.role))}
        for u in users
    ]


@router.get("")
async def list_users(
    current_user: Any = Depends(require_professor),
    search: str | None = None,
    role: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    assert_role(role, current_user)
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    if role:
        where["role"] = role
    if search:
        where["OR"] = [{"name": contains(search)}, {"email": contains(search)}]
    total = await db.user.count(where=where)
    users = await db.user.find_many(where=where, skip=skip, take=page_size, order={"createdAt": "desc"})
    return page_payload([serialize_user(user) for user in users], total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_user(payload: UserCreate, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    role = "MASTER_ADMIN" if is_master_email(payload.email) else payload.role
    assert_role(role, current_user)
    existing = await db.user.find_unique(where={"email": payload.email.lower()})
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")
    is_master = role == "MASTER_ADMIN"
    user = await db.user.create(
        data={
            "email": payload.email.lower(),
            "name": payload.name,
            "passwordHash": hash_password(payload.password),
            "role": role,
            "authProvider": "LOCAL",
            "canManageQuestionnaire": is_master or payload.canManageQuestionnaire,
            "canManageCrafts": is_master or payload.canManageCrafts,
            "canManageWorkshops": is_master or payload.canManageWorkshops,
            "canReview": is_master or payload.canReview,
            "canViewProvenance": is_master or payload.canViewProvenance,
            # Dataset download is grantable by any admin (the whole route is admin-gated), unlike the
            # master-admin-only grants above — so it needs no extra permission assertion.
            "canDownloadDataset": is_master or payload.canDownloadDataset,
        }
    )
    return serialize_user(user)


@router.patch("/{user_id}")
async def update_user(
    user_id: str,
    payload: UserUpdate,
    current_user: Any = Depends(require_professor),
) -> dict[str, Any]:
    assert_role(payload.role, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if not is_admin(current_user):
        # Professors manage the ladder, not accounts: they may promote/demote people below them
        # (up to their own tier, per assert_role + assert_can_manage_target) but everything else —
        # identity, passwords, privilege flags — stays admin-only.
        extra_fields = set(data) - {"role"}
        if extra_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Professors can only change a user's role",
            )
    if "email" in data:
        data["email"] = data["email"].lower()
    if "password" in data:
        data["passwordHash"] = hash_password(data.pop("password"))
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if user.id == current_user.id:
        # Self-service is limited to identity fields; nobody edits their own role or privileges.
        privileged_fields = set(data) - {"name", "email", "passwordHash"}
        if privileged_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You cannot change your own role or privileges",
            )
    else:
        assert_can_manage_target(current_user, user)
    assert_not_demoting_master(user, data.get("role"), current_user)
    if "email" in data and data["email"] != user.email:
        if is_master_email(data["email"]) and not is_master_admin(current_user):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the master admin can assign the master admin email",
            )
        clash = await db.user.find_unique(where={"email": data["email"]})
        if clash and clash.id != user_id:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")
    if "email" in data and is_master_email(data["email"]):
        data["role"] = "MASTER_ADMIN"
    if data.get("role") == "MASTER_ADMIN":
        data["canManageQuestionnaire"] = True
        data["canManageCrafts"] = True
        data["canManageWorkshops"] = True
        data["canReview"] = True
        data["canViewProvenance"] = True
        data["canDownloadDataset"] = True
    updated = await db.user.update(where={"id": user_id}, data=data)
    return serialize_user(updated)


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(user_id: str, current_user: Any = Depends(require_admin)) -> None:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if is_master_email(user.email):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account cannot be deleted")
    if user.id == current_user.id:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="You cannot delete your own account")
    assert_can_manage_target(current_user, user)
    await db.user.delete(where={"id": user_id})
