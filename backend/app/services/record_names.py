from typing import Any

from fastapi import HTTPException

from app.schemas.record_names import (
    ARTISAN_NAME_REQUIRED,
    CRAFT_NAME_REQUIRED,
    LINKED_ARTISAN_PLACELESS,
    LINKED_ARTISAN_UNNAMED,
    LINKED_CRAFT_UNNAMED,
    PLACE_REQUIRED,
    given,
)

LINKED_NAME_COLUMNS = ("craftName", "artisanName", "place")


def _text(value: Any) -> str | None:
    if isinstance(value, str) and value.strip():
        return value.strip()
    return None


def artisan_place(artisan: Any) -> str | None:
    place = _text(getattr(artisan, "place", None))
    if place:
        return place
    location = getattr(artisan, "location", None)
    if location is None:
        return None
    return _text(getattr(location, "village", None)) or _text(getattr(location, "district", None))


def _refuse(message: str) -> HTTPException:
    return HTTPException(status_code=422, detail=message)


async def fill_linked_names(
    data: dict[str, Any],
    *,
    client: Any,
    columns: tuple[str, ...] = LINKED_NAME_COLUMNS,
) -> dict[str, Any]:
    wanted = [column for column in columns if not given(data.get(column))]
    if not wanted:
        return data
    craft_id = _text(data.get("craftId"))
    artisan_id = _text(data.get("artisanId"))
    if "craftName" in wanted:
        if not craft_id:
            raise _refuse(CRAFT_NAME_REQUIRED)
        craft = await client.craft.find_unique(where={"id": craft_id})
        name = _text(getattr(craft, "name", None)) if craft is not None else None
        if not name:
            raise _refuse(LINKED_CRAFT_UNNAMED)
        data["craftName"] = name
    if "artisanName" in wanted or "place" in wanted:
        if not artisan_id:
            raise _refuse(ARTISAN_NAME_REQUIRED if "artisanName" in wanted else PLACE_REQUIRED)
        artisan = await client.artisan.find_unique(
            where={"id": artisan_id}, include={"location": True}
        )
        if "artisanName" in wanted:
            name = _text(getattr(artisan, "name", None)) if artisan is not None else None
            if not name:
                raise _refuse(LINKED_ARTISAN_UNNAMED)
            data["artisanName"] = name
        if "place" in wanted:
            place = artisan_place(artisan) if artisan is not None else None
            if not place:
                raise _refuse(LINKED_ARTISAN_PLACELESS)
            data["place"] = place
    return data


async def fill_place_from_artisans(
    data: dict[str, Any], artisan_ids: list[str] | None, *, client: Any
) -> dict[str, Any]:
    if given(data.get("place")):
        return data
    first = next((_text(artisan_id) for artisan_id in artisan_ids or () if _text(artisan_id)), None)
    if first is None:
        return data
    artisan = await client.artisan.find_unique(where={"id": first}, include={"location": True})
    place = artisan_place(artisan) if artisan is not None else None
    if place:
        data["place"] = place
    return data
