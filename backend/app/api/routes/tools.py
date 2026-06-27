from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user
from app.schemas.records import ToolArtisanAssign, ToolCreate, ToolUpdate
from app.services.access import guard_record_edit
from app.services.workshop_access import enforce_workshop_submission, merge_extra
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

INCLUDE = {
    "artisan": True,
    "craft": True,
    "workshop": True,
    "location": True,
    "media": True,
    "createdBy": True,
    "artisanLinks": {"include": {"artisan": True}},
}


async def _assigned_artisans(tool_id: str) -> list[dict[str, Any]]:
    """All artisans a tool is assigned to (the many-to-many links), oldest first."""
    links = await db.toolartisan.find_many(
        where={"toolId": tool_id},
        include={"artisan": True},
        order={"createdAt": "asc"},
    )
    return public_encode([link.artisan for link in links if link.artisan])


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
    workshop_flag = await enforce_workshop_submission(current_user, data.get("workshopId"))
    data = merge_extra(data, workshop_flag)
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
    await guard_record_edit(tool, current_user, data, "tool")
    merge_field_provenance(data, current_user, previous=tool)
    updated = await db.tooldocumentation.update(where={"id": tool_id}, data=data, include=INCLUDE)
    return public_encode(updated)


@router.delete("/{tool_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.tooldocumentation, tool_id)
    await db.tooldocumentation.delete(where={"id": tool_id})


@router.get("/{tool_id}/artisans")
async def list_tool_artisans(tool_id: str, current_user: Any = Depends(get_current_user)) -> list[dict[str, Any]]:
    await require_record(db.tooldocumentation, tool_id)
    return await _assigned_artisans(tool_id)


@router.post("/{tool_id}/artisans")
async def assign_tool_artisans(
    tool_id: str,
    payload: ToolArtisanAssign,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """Assign the tool to the given artisans (idempotent: existing links are kept, new ones added)."""
    await require_record(db.tooldocumentation, tool_id)
    existing = await db.toolartisan.find_many(where={"toolId": tool_id})
    have = {link.artisanId for link in existing}
    for artisan_id in payload.artisanIds:
        if artisan_id and artisan_id not in have:
            await require_record(db.artisan, artisan_id)
            await db.toolartisan.create(data={"toolId": tool_id, "artisanId": artisan_id})
            have.add(artisan_id)
    return await _assigned_artisans(tool_id)


@router.delete("/{tool_id}/artisans/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def unassign_tool_artisan(
    tool_id: str,
    artisan_id: str,
    current_user: Any = Depends(get_current_user),
) -> None:
    link = await db.toolartisan.find_unique(where={"toolId_artisanId": {"toolId": tool_id, "artisanId": artisan_id}})
    if link:
        await db.toolartisan.delete(where={"id": link.id})
