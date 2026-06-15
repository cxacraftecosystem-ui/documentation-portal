from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import assert_can_delete, assert_owner_or_admin, get_current_user
from app.schemas.records import WorkshopCreate, WorkshopUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import add_date_range, attach_location, clean_data, contains, require_record, visibility_where

router = APIRouter(prefix="/workshops", tags=["workshops"])

INCLUDE = {"location": True, "createdBy": True, "artisans": {"include": {"artisan": True}}}


async def replace_workshop_artisans(workshop_id: str, artisan_ids: list[str]) -> None:
    await db.workshopartisan.delete_many(where={"workshopId": workshop_id})
    for artisan_id in artisan_ids:
        await db.workshopartisan.create(data={"workshopId": workshop_id, "artisanId": artisan_id})


@router.get("")
async def list_workshops(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    place: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where = visibility_where(current_user)
    if search:
        where["OR"] = [{"title": contains(search)}, {"place": contains(search)}, {"description": contains(search)}]
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = statusFilter
    add_date_range(where, "date", dateFrom, dateTo)
    total = await db.workshop.count(where=where)
    items = await db.workshop.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"date": "desc"},
    )
    return page_payload(jsonable_encoder(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop(
    payload: WorkshopCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    artisan_ids = data.pop("artisanIds", [])
    data = await attach_location(data)
    data["createdById"] = current_user.id
    created = await db.workshop.create(data=data)
    if artisan_ids:
        await replace_workshop_artisans(created.id, artisan_ids)
    hydrated = await db.workshop.find_unique(where={"id": created.id}, include=INCLUDE)
    return jsonable_encoder(hydrated)


@router.get("/{workshop_id}")
async def get_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    workshop = await db.workshop.find_unique(where={"id": workshop_id}, include=INCLUDE)
    workshop = await require_record(db.workshop, workshop_id) if not workshop else workshop
    return jsonable_encoder(workshop)


@router.patch("/{workshop_id}")
async def update_workshop(
    workshop_id: str,
    payload: WorkshopUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    assert_owner_or_admin(workshop, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True))
    artisan_ids = data.pop("artisanIds", None)
    data = await attach_location(data)
    await db.workshop.update(where={"id": workshop_id}, data=data)
    if artisan_ids is not None:
        await replace_workshop_artisans(workshop_id, artisan_ids)
    updated = await db.workshop.find_unique(where={"id": workshop_id}, include=INCLUDE)
    return jsonable_encoder(updated)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.workshop, workshop_id)
    await db.workshop.delete(where={"id": workshop_id})
