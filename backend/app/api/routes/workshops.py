from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status
from pydantic import BaseModel, Field

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    require_admin,
    require_workshop_manager,
)
from app.schemas.records import WorkshopCreate, WorkshopUpdate
from app.services.access import guard_record_edit
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    public_encode,
    add_date_range,
    attach_location,
    clean_data,
    contains,
    merge_field_provenance,
    require_record,
    visibility_where,
)

router = APIRouter(prefix="/workshops", tags=["workshops"])


class WorkshopAssignmentIn(BaseModel):
    """The full set of researchers assigned to a workshop (replaces the existing set)."""
    userIds: list[str] = Field(default_factory=list)

INCLUDE = {
    "location": True,
    "createdBy": True,
    "artisans": {"include": {"artisan": True}},
    "crafts": {"include": {"craft": True}},
}


async def replace_workshop_artisans(workshop_id: str, artisan_ids: list[str]) -> None:
    await db.workshopartisan.delete_many(where={"workshopId": workshop_id})
    for artisan_id in artisan_ids:
        await db.workshopartisan.create(data={"workshopId": workshop_id, "artisanId": artisan_id})


async def replace_workshop_crafts(workshop_id: str, craft_ids: list[str]) -> None:
    await db.workshopcraft.delete_many(where={"workshopId": workshop_id})
    for craft_id in craft_ids:
        await db.workshopcraft.create(data={"workshopId": workshop_id, "craftId": craft_id})


def normalize_workshop_dates(data: dict[str, Any]) -> dict[str, Any]:
    if not data.get("date") and data.get("startDate"):
        data["date"] = data["startDate"]
    if not data.get("startDate") and data.get("date"):
        data["startDate"] = data["date"]
    if not data.get("endDate") and data.get("startDate"):
        data["endDate"] = data["startDate"]
    return data


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
    add_date_range(where, "startDate", dateFrom, dateTo)
    total = await db.workshop.count(where=where)
    items = await db.workshop.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"startDate": "desc"},
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop(
    payload: WorkshopCreate,
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    artisan_ids = data.pop("artisanIds", [])
    craft_ids = data.pop("craftIds", [])
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    created = await db.workshop.create(data=data)
    if artisan_ids:
        await replace_workshop_artisans(created.id, artisan_ids)
    if craft_ids:
        await replace_workshop_crafts(created.id, craft_ids)
    hydrated = await db.workshop.find_unique(where={"id": created.id}, include=INCLUDE)
    return public_encode(hydrated)


@router.get("/{workshop_id}")
async def get_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    workshop = await db.workshop.find_unique(where={"id": workshop_id}, include=INCLUDE)
    workshop = await require_record(db.workshop, workshop_id) if not workshop else workshop
    return public_encode(workshop)


@router.patch("/{workshop_id}")
async def update_workshop(
    workshop_id: str,
    payload: WorkshopUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    artisan_ids = data.pop("artisanIds", None)
    craft_ids = data.pop("craftIds", None)
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    privileged = await guard_record_edit(workshop, current_user, data, "workshop")
    merge_field_provenance(data, current_user, previous=workshop)
    await db.workshop.update(where={"id": workshop_id}, data=data)
    if artisan_ids is not None:
        link_count = await db.workshopartisan.count(where={"workshopId": workshop_id})
        if not privileged:
            assert_can_contribute_relation(workshop, current_user, link_count > 0, "artisanIds")
        await replace_workshop_artisans(workshop_id, artisan_ids)
    if craft_ids is not None:
        craft_link_count = await db.workshopcraft.count(where={"workshopId": workshop_id})
        if not privileged:
            assert_can_contribute_relation(workshop, current_user, craft_link_count > 0, "craftIds")
        await replace_workshop_crafts(workshop_id, craft_ids)
    updated = await db.workshop.find_unique(where={"id": workshop_id}, include=INCLUDE)
    return public_encode(updated)


@router.get("/{workshop_id}/assignments")
async def list_workshop_assignments(workshop_id: str, _: Any = Depends(get_current_user)) -> list[dict[str, Any]]:
    """The researchers assigned to this workshop. Empty list means the workshop is open to everyone."""
    await require_record(db.workshop, workshop_id)
    rows = await db.workshopassignment.find_many(
        where={"workshopId": workshop_id}, include={"user": True, "assignedBy": True}, order={"createdAt": "asc"}
    )
    return public_encode(rows)


@router.put("/{workshop_id}/assignments")
async def set_workshop_assignments(
    workshop_id: str, payload: WorkshopAssignmentIn, current_user: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    """Admin sets the exact set of researchers assigned to this workshop (replaces the previous set)."""
    await require_record(db.workshop, workshop_id)
    wanted = {uid for uid in payload.userIds if uid}
    existing = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    have = {r.userId for r in existing}
    for uid in have - wanted:
        await db.workshopassignment.delete_many(where={"workshopId": workshop_id, "userId": uid})
    for uid in wanted - have:
        await db.workshopassignment.create(
            data={"workshopId": workshop_id, "userId": uid, "assignedById": current_user.id}
        )
    rows = await db.workshopassignment.find_many(
        where={"workshopId": workshop_id}, include={"user": True, "assignedBy": True}, order={"createdAt": "asc"}
    )
    return public_encode(rows)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.workshop, workshop_id)
    await db.workshop.delete(where={"id": workshop_id})
