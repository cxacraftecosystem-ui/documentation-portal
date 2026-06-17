from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.core.deps import assert_can_contribute_fields, assert_can_delete, get_current_user
from app.schemas.records import ToolCreate, ToolUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    public_encode,
    add_date_range,
    attach_location,
    clean_data,
    contains,
    decimal_to_string,
    merge_field_provenance,
    require_record,
    visibility_where,
)

router = APIRouter(prefix="/tools", tags=["tools"])

INCLUDE = {"artisan": True, "craft": True, "workshop": True, "location": True, "media": True, "createdBy": True}


@router.get("")
async def list_tools(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    place: str | None = None,
    maker: str | None = None,
    traditionType: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where = visibility_where(current_user)
    if search:
        where["OR"] = [
            {"toolkitName": contains(search)},
            {"localName": contains(search)},
            {"englishName": contains(search)},
            {"craftName": contains(search)},
            {"artisanName": contains(search)},
            {"place": contains(search)},
            {"processUsedIn": contains(search)},
            {"material": contains(search)},
            {"remarks": contains(search)},
        ]
    if craftId:
        where["craftId"] = craftId
    if artisanId:
        where["artisanId"] = artisanId
    if workshopId:
        where["workshopId"] = workshopId
    if place:
        where["place"] = contains(place)
    if maker:
        where["maker"] = maker
    if traditionType:
        where["traditionType"] = traditionType
    if statusFilter:
        where["status"] = statusFilter
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total = await db.tooldocumentation.count(where=where)
    items = await db.tooldocumentation.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_tool(
    payload: ToolCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    data = decimal_to_string(clean_data(payload.model_dump()))
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    created = await db.tooldocumentation.create(data=data, include=INCLUDE)
    return public_encode(created)


@router.get("/{tool_id}")
async def get_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    tool = await db.tooldocumentation.find_unique(where={"id": tool_id}, include=INCLUDE)
    tool = await require_record(db.tooldocumentation, tool_id) if not tool else tool
    return public_encode(tool)


@router.patch("/{tool_id}")
async def update_tool(
    tool_id: str,
    payload: ToolUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    data = decimal_to_string(clean_data(payload.model_dump(exclude_unset=True)))
    data = await attach_location(data)
    assert_can_contribute_fields(tool, current_user, data)
    merge_field_provenance(data, current_user, previous=tool)
    updated = await db.tooldocumentation.update(where={"id": tool_id}, data=data, include=INCLUDE)
    return public_encode(updated)


@router.delete("/{tool_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.tooldocumentation, tool_id)
    await db.tooldocumentation.delete(where={"id": tool_id})
