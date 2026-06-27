from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    get_current_user,
    require_craft_manager,
)
from app.services.access import guard_record_edit
from app.schemas.records import CraftCreate, CraftUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains, merge_field_provenance, require_record
from app.services.records import public_encode

router = APIRouter(prefix="/crafts", tags=["crafts"])


@router.get("")
async def list_crafts(
    _: Any = Depends(get_current_user),
    search: str | None = None,
    place: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"category": contains(search)},
            {"description": contains(search)},
        ]
    if place:
        where["place"] = contains(place)
    total = await db.craft.count(where=where)
    items = await db.craft.find_many(where=where, skip=skip, take=page_size, order={"name": "asc"})
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_craft(payload: CraftCreate, current_user: Any = Depends(require_craft_manager)) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    try:
        created = await db.craft.create(data=data)
    except UniqueViolationError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Craft name already exists") from exc
    return public_encode(created)


@router.get("/{craft_id}")
async def get_craft(craft_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    craft = await require_record(db.craft, craft_id)
    return public_encode(craft)


@router.patch("/{craft_id}")
async def update_craft(
    craft_id: str,
    payload: CraftUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    craft = await require_record(db.craft, craft_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    await guard_record_edit(craft, current_user, data, "craft")
    merge_field_provenance(data, current_user, previous=craft)
    updated = await db.craft.update(where={"id": craft_id}, data=data)
    return public_encode(updated)


@router.delete("/{craft_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_craft(craft_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.craft, craft_id)
    await db.craft.delete(where={"id": craft_id})
