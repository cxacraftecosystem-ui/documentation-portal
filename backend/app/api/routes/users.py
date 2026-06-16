from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.config import get_settings
from app.core.deps import is_master_admin, require_admin
from app.core.security import hash_password
from app.schemas.users import UserCreate, UserUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains

router = APIRouter(prefix="/users", tags=["users"])

ALLOWED_ROLES = {"MASTER_ADMIN", "ADMIN", "RESEARCHER"}


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    return payload


def assert_role(role: str | None, current_user: Any) -> None:
    if role and role not in ALLOWED_ROLES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid user role")
    if role == "MASTER_ADMIN" and not is_master_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the master admin can grant master admin")


def assert_questionnaire_permission_change(value: bool | None, current_user: Any) -> None:
    if value is not None and not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the master admin can grant questionnaire management access",
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


@router.get("")
async def list_users(
    current_user: Any = Depends(require_admin),
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
    if payload.canManageQuestionnaire:
        assert_questionnaire_permission_change(payload.canManageQuestionnaire, current_user)
    existing = await db.user.find_unique(where={"email": payload.email.lower()})
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")
    user = await db.user.create(
        data={
            "email": payload.email.lower(),
            "name": payload.name,
            "passwordHash": hash_password(payload.password),
            "role": role,
            "authProvider": "LOCAL",
            "canManageQuestionnaire": role == "MASTER_ADMIN" or payload.canManageQuestionnaire,
        }
    )
    return serialize_user(user)


@router.patch("/{user_id}")
async def update_user(
    user_id: str,
    payload: UserUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    assert_role(payload.role, current_user)
    assert_questionnaire_permission_change(payload.canManageQuestionnaire, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "email" in data:
        data["email"] = data["email"].lower()
    if "password" in data:
        data["passwordHash"] = hash_password(data.pop("password"))
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    assert_not_demoting_master(user, data.get("role"), current_user)
    if "email" in data and is_master_email(data["email"]):
        data["role"] = "MASTER_ADMIN"
        data["canManageQuestionnaire"] = True
    if data.get("role") == "MASTER_ADMIN":
        data["canManageQuestionnaire"] = True
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
    await db.user.delete(where={"id": user_id})
