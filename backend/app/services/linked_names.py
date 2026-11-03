from typing import Any

from fastapi import HTTPException, status

from app.core.db import db

_MISSING_NAME = {
    "craftName": "Pick a craft from the list.",
    "artisanName": "Enter the artisan name.",
    "place": "The linked artisan has no place on file. Enter the place.",
}


def _sent_link(data: dict[str, Any], key: str, stored: Any | None) -> Any:
    if key not in data:
        return None
    value = data.get(key)
    if stored is not None and value == getattr(stored, key, None):
        return None
    return value


async def fill_product_names(data: dict[str, Any], *, stored: Any | None = None) -> dict[str, Any]:
    artisan_id = _sent_link(data, "artisanId", stored)
    craft_id = _sent_link(data, "craftId", stored)
    if artisan_id:
        artisan = await db.artisan.find_unique(where={"id": artisan_id}, include={"craft": True})
        if artisan is not None:
            if (artisan.name or "").strip():
                data["artisanName"] = artisan.name
            if (artisan.place or "").strip():
                data["place"] = artisan.place
            stored_craft = getattr(stored, "craftId", None) if stored is not None else None
            if not craft_id and not stored_craft and artisan.craftId:
                craft_id = artisan.craftId
                data["craftId"] = artisan.craftId
    if craft_id:
        craft = await db.craft.find_unique(where={"id": craft_id})
        if craft is not None and (craft.name or "").strip():
            data["craftName"] = craft.name
    if stored is None:
        for column, message in _MISSING_NAME.items():
            if not (data.get(column) or "").strip():
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=message)
    return data


async def fill_craft_name(data: dict[str, Any], *, stored: Any | None = None) -> dict[str, Any]:
    craft_id = _sent_link(data, "craftId", stored)
    if craft_id:
        craft = await db.craft.find_unique(where={"id": craft_id})
        if craft is not None and (craft.name or "").strip():
            data["craftName"] = craft.name
    if stored is None and not (data.get("craftName") or "").strip():
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=_MISSING_NAME["craftName"])
    return data
