from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import require_admin
from app.core.security import hash_password
from app.schemas.users import UserCreate, UserUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains

router = APIRouter(prefix="/users", tags=["users"])

ALLOWED_ROLES = {"ADMIN", "RESEARCHER"}


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    return payload


def assert_role(role: str | None) -> None:
    if role and role not in ALLOWED_ROLES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid user role")


@router.get("")
async def list_users(
    _: Any = Depends(require_admin),
    search: str | None = None,
    role: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    assert_role(role)
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
async def create_user(payload: UserCreate, _: Any = Depends(require_admin)) -> dict[str, Any]:
    assert_role(payload.role)
    existing = await db.user.find_unique(where={"email": payload.email.lower()})
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")
    user = await db.user.create(
        data={
            "email": payload.email.lower(),
            "name": payload.name,
            "passwordHash": hash_password(payload.password),
            "role": payload.role,
            "authProvider": "LOCAL",
        }
    )
    return serialize_user(user)


@router.patch("/{user_id}")
async def update_user(
    user_id: str,
    payload: UserUpdate,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    assert_role(payload.role)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "email" in data:
        data["email"] = data["email"].lower()
    if "password" in data:
        data["passwordHash"] = hash_password(data.pop("password"))
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    updated = await db.user.update(where={"id": user_id}, data=data)
    return serialize_user(updated)


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(user_id: str, _: Any = Depends(require_admin)) -> None:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    await db.user.delete(where={"id": user_id})
