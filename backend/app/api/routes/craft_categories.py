"""Craft categories and sub-categories: the two levels above a craft on the master list (docs/CRAFTS.md).

Reading is for anyone signed in. Adding, renaming, moving and reordering is for content admins;
deleting is ``assert_can_delete`` (§5 of the 2026-10-20 requirements) and refused while the row still
holds anything. The same file in both portals.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user
from app.services import craft_master

try:  # The perms stream adds the name; until it lands, a content admin is an admin.
    from app.core.deps import is_content_admin
except ImportError:  # pragma: no cover - depends on which stream merged first
    from app.core.deps import is_admin as is_content_admin

router = APIRouter(tags=["crafts"])


async def require_content_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_content_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Content admin access required")
    return current_user


class CategoryCreate(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str = Field(min_length=1, max_length=120)


class CategoryUpdate(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str | None = Field(default=None, min_length=1, max_length=120)
    sortOrder: int | None = Field(default=None, ge=1)


class SubCategoryCreate(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str = Field(min_length=1, max_length=120)
    categoryId: str = Field(min_length=1)


class SubCategoryUpdate(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str | None = Field(default=None, min_length=1, max_length=120)
    categoryId: str | None = None
    sortOrder: int | None = Field(default=None, ge=1)


def _name(value: str) -> str:
    return " ".join(value.split())


def _taken(what: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail={"code": f"{what}_EXISTS", "message": "That name is already in use here."},
    )


async def _tree() -> list[dict[str, Any]]:
    _, payload = await craft_master.category_tree(db)
    return payload


async def _reorder(rows: list[Any], moving_id: str, position: int, delegate: Any) -> None:
    """Put ``moving_id`` at ``position`` (1-based) among ``rows`` and renumber 1..n."""
    ordered = sorted(rows, key=lambda r: (r.sortOrder, r.name))
    moving = next(r for r in ordered if r.id == moving_id)
    ordered.remove(moving)
    ordered.insert(min(max(position, 1), len(ordered) + 1) - 1, moving)
    await _write_order(ordered, delegate)


async def _renumber(rows: list[Any], delegate: Any) -> None:
    """Close the gaps: ``rows`` numbered 1..n in their current order."""
    await _write_order(sorted(rows, key=lambda r: (r.sortOrder, r.name)), delegate)


async def _write_order(ordered: list[Any], delegate: Any) -> None:
    for index, row in enumerate(ordered, start=1):
        if row.sortOrder != index:
            await delegate.update(where={"id": row.id}, data={"sortOrder": index})


async def _require(delegate: Any, row_id: str, what: str) -> Any:
    row = await delegate.find_unique(where={"id": row_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"{what} not found")
    return row


@router.get("/craft-categories")
async def list_categories(_: Any = Depends(get_current_user)) -> list[dict[str, Any]]:
    return await _tree()


@router.post("/craft-categories", status_code=status.HTTP_201_CREATED)
async def create_category(payload: CategoryCreate, current_user: Any = Depends(require_content_admin)) -> dict[str, Any]:
    name = _name(payload.name)
    if await db.craftcategory.find_unique(where={"name": name}) is not None:
        raise _taken("CATEGORY")
    last = await db.craftcategory.find_first(order={"sortOrder": "desc"})
    created = await db.craftcategory.create(
        data={
            "name": name,
            "slug": await craft_master.unique_slug(db.craftcategory, name, fallback="Category"),
            "sortOrder": (last.sortOrder if last else 0) + 1,
            "createdById": current_user.id,
        }
    )
    return next(c for c in await _tree() if c["id"] == created.id)


@router.patch("/craft-categories/{category_id}")
async def update_category(
    category_id: str, payload: CategoryUpdate, _: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    category = await _require(db.craftcategory, category_id, "Category")
    if payload.name is not None and _name(payload.name) != category.name:
        name = _name(payload.name)
        if await db.craftcategory.find_unique(where={"name": name}) is not None:
            raise _taken("CATEGORY")
        async with db.tx() as tx:
            await tx.craftcategory.update(where={"id": category_id}, data={"name": name})
            # ``Craft.category`` is the compatibility copy of the category name; keep it true.
            await tx.craft.update_many(where={"subCategory": {"is": {"categoryId": category_id}}}, data={"category": name})
    if payload.sortOrder is not None and payload.sortOrder != category.sortOrder:
        await _reorder(await db.craftcategory.find_many(), category_id, payload.sortOrder, db.craftcategory)
    return next(c for c in await _tree() if c["id"] == category_id)


@router.delete("/craft-categories/{category_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_category(category_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await _require(db.craftcategory, category_id, "Category")
    if await db.craftsubcategory.count(where={"categoryId": category_id}):
        raise craft_master.craft_error("CATEGORY_NOT_EMPTY", status.HTTP_409_CONFLICT)
    await db.craftcategory.delete(where={"id": category_id})


@router.post("/craft-sub-categories", status_code=status.HTTP_201_CREATED)
async def create_sub_category(
    payload: SubCategoryCreate, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    await _require(db.craftcategory, payload.categoryId, "Category")
    name = _name(payload.name)
    if await db.craftsubcategory.find_first(where={"categoryId": payload.categoryId, "name": name}) is not None:
        raise _taken("SUB_CATEGORY")
    last = await db.craftsubcategory.find_first(where={"categoryId": payload.categoryId}, order={"sortOrder": "desc"})
    created = await db.craftsubcategory.create(
        data={
            "name": name,
            "categoryId": payload.categoryId,
            "slug": await craft_master.unique_slug(db.craftsubcategory, name, fallback="Sub-Category"),
            "sortOrder": (last.sortOrder if last else 0) + 1,
            "createdById": current_user.id,
        }
    )
    return _sub_payload(created)


def _sub_payload(sub: Any) -> dict[str, Any]:
    return {"id": sub.id, "slug": sub.slug, "name": sub.name, "categoryId": sub.categoryId, "sortOrder": sub.sortOrder}


@router.patch("/craft-sub-categories/{sub_category_id}")
async def update_sub_category(
    sub_category_id: str, payload: SubCategoryUpdate, _: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    sub = await _require(db.craftsubcategory, sub_category_id, "Sub-category")
    category_id = payload.categoryId or sub.categoryId
    target = await _require(db.craftcategory, category_id, "Category")
    name = _name(payload.name) if payload.name is not None else sub.name
    moving = category_id != sub.categoryId
    if (moving or name != sub.name) and await db.craftsubcategory.find_first(
        where={"categoryId": category_id, "name": name, "NOT": {"id": sub_category_id}}
    ):
        raise _taken("SUB_CATEGORY")
    data: dict[str, Any] = {}
    if name != sub.name:
        data["name"] = name
    if moving:
        last = await db.craftsubcategory.find_first(where={"categoryId": category_id}, order={"sortOrder": "desc"})
        data["categoryId"] = category_id
        data["sortOrder"] = (last.sortOrder if last else 0) + 1
    if data:
        async with db.tx() as tx:
            await tx.craftsubcategory.update(where={"id": sub_category_id}, data=data)
            if moving:
                # Its crafts move with it (they point at the sub-category); their category copy follows.
                await tx.craft.update_many(where={"subCategoryId": sub_category_id}, data={"category": target.name})
        if moving:
            await _renumber(await db.craftsubcategory.find_many(where={"categoryId": sub.categoryId}), db.craftsubcategory)
    if payload.sortOrder is not None:
        await _reorder(
            await db.craftsubcategory.find_many(where={"categoryId": category_id}),
            sub_category_id,
            payload.sortOrder,
            db.craftsubcategory,
        )
    return _sub_payload(await db.craftsubcategory.find_unique(where={"id": sub_category_id}))


@router.delete("/craft-sub-categories/{sub_category_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_sub_category(sub_category_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await _require(db.craftsubcategory, sub_category_id, "Sub-category")
    if await db.craft.count(where={"subCategoryId": sub_category_id}):
        raise craft_master.craft_error("SUB_CATEGORY_NOT_EMPTY", status.HTTP_409_CONFLICT)
    await db.craftsubcategory.delete(where={"id": sub_category_id})
