from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import get_value, is_admin


def clean_data(data: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in data.items() if value is not None}


def decimal_to_string(data: dict[str, Any]) -> dict[str, Any]:
    converted: dict[str, Any] = {}
    for key, value in data.items():
        if isinstance(value, Decimal):
            converted[key] = str(value)
        elif isinstance(value, dict):
            converted[key] = decimal_to_string(value)
        else:
            converted[key] = value
    return converted


async def attach_location(data: dict[str, Any]) -> dict[str, Any]:
    location = data.pop("location", None)
    if location:
        location_data = location.model_dump() if hasattr(location, "model_dump") else dict(location)
        created = await db.location.create(data=clean_data(location_data))
        data["locationId"] = created.id
    return data


def contains(value: str) -> dict[str, Any]:
    return {"contains": value, "mode": "insensitive"}


def visibility_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    if is_admin(user):
        return {}
    return {owner_field: get_value(user, "id")}


def add_date_range(where: dict[str, Any], field: str, date_from: datetime | None, date_to: datetime | None) -> None:
    range_filter: dict[str, Any] = {}
    if date_from:
        range_filter["gte"] = date_from
    if date_to:
        range_filter["lte"] = date_to
    if range_filter:
        where[field] = range_filter


async def require_record(delegate: Any, record_id: str) -> Any:
    record = await delegate.find_unique(where={"id": record_id})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


def review_update(status_value: str, notes: str | None, reviewer_id: str) -> dict[str, Any]:
    return {
        "status": status_value,
        "reviewNotes": notes,
        "reviewedById": reviewer_id,
        "reviewedAt": datetime.now(UTC),
    }


def relation_filter(field: str, value: str | None) -> dict[str, Any]:
    return {field: value} if value else {}


def media_relation_data(record_type: str | None, record_id: str | None) -> dict[str, Any]:
    if not record_type or not record_id:
        return {}
    normalized = record_type.lower()
    field_map = {
        "artisan": "artisanId",
        "workshop": "workshopId",
        "product": "productId",
        "tool": "toolId",
    }
    field = field_map.get(normalized)
    return {field: record_id} if field else {}
