from typing import Any

from fastapi import APIRouter, Depends, Query, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import assert_can_contribute_fields, assert_can_delete, get_current_user
from app.schemas.records import ArtisanCreate, ArtisanUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import attach_location, clean_data, contains, require_record, visibility_where

router = APIRouter(prefix="/artisans", tags=["artisans"])

INCLUDE = {"craft": True, "location": True, "createdBy": True}


async def resolve_craft_id(data: dict[str, Any], current_user: Any) -> dict[str, Any]:
    craft_name = data.pop("craftName", None)
    if data.get("craftId") or not craft_name:
        return data
    existing = await db.craft.find_unique(where={"name": craft_name})
    if existing:
        data["craftId"] = existing.id
        return data
    created = await db.craft.create(data={"name": craft_name, "createdById": current_user.id})
    data["craftId"] = created.id
    return data


@router.get("")
async def list_artisans(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craft: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where = visibility_where(current_user)
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"place": contains(search)},
            {"notes": contains(search)},
            {"craft": {"is": {"name": contains(search)}}},
        ]
    if craft:
        where["craft"] = {"is": {"name": contains(craft)}}
    if craftId:
        where["craftId"] = craftId
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = statusFilter
    total = await db.artisan.count(where=where)
    items = await db.artisan.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(jsonable_encoder(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_artisan(
    payload: ArtisanCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    data["createdById"] = current_user.id
    created = await db.artisan.create(data=data, include=INCLUDE)
    return jsonable_encoder(created)


@router.get("/{artisan_id}")
async def get_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    artisan = await db.artisan.find_unique(where={"id": artisan_id}, include=INCLUDE)
    artisan = await require_record(db.artisan, artisan_id) if not artisan else artisan
    return jsonable_encoder(artisan)


@router.patch("/{artisan_id}")
async def update_artisan(
    artisan_id: str,
    payload: ArtisanUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    assert_can_contribute_fields(artisan, current_user, data)
    updated = await db.artisan.update(where={"id": artisan_id}, data=data, include=INCLUDE)
    return jsonable_encoder(updated)


@router.delete("/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.artisan, artisan_id)
    await db.artisan.delete(where={"id": artisan_id})
