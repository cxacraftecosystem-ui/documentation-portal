from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from fastapi import HTTPException, status
from prisma import Json

from app.core.db import db


def to_json(value: Any) -> Any:
    """Wrap dict/list values destined for a Prisma Json column. prisma-client-py rejects raw dicts."""
    if isinstance(value, (dict, list)):
        return Json(value)
    return value


def jsonify_metadata(data: dict[str, Any], *fields: str) -> dict[str, Any]:
    """Wrap the given JSON-column fields in ``Json`` if they are plain dict/list values."""
    keys = fields or ("extraMetadata", "measurementAnalysis", "result")
    for key in keys:
        if key in data and isinstance(data[key], (dict, list)):
            data[key] = Json(data[key])
    return data

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
    # All authenticated users can discover all repository records. Edit and delete
    # permissions are enforced on mutation routes.
    return {}


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


# Fields that are infrastructural / system-managed and should not be attributed to a contributor.
PROVENANCE_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "createdById",
    "createdAt",
    "updatedAt",
    "reviewedById",
    "reviewNotes",
    "reviewedAt",
    "recordedAt",
    "recordedTimezone",
    "measurementAnalysis",
    "measurementAnalysisStatus",
    "measurementImageId",
}


def merge_field_provenance(new_data: dict[str, Any], user: Any, previous: Any | None = None) -> None:
    """Record which user populated/changed each field, stored under extraMetadata.fieldProvenance.

    On create (``previous`` is ``None``) every non-empty field is attributed to ``user``. On update
    only fields whose value actually changes are re-attributed; unchanged fields keep the original
    contributor carried over from the previous record. This mutates ``new_data`` in place.
    """
    from app.core.deps import get_value, is_empty_value, values_match

    incoming_extra = new_data.get("extraMetadata")
    base_extra: dict[str, Any] = dict(incoming_extra) if isinstance(incoming_extra, dict) else {}

    provenance: dict[str, Any] = {}
    if previous is not None:
        previous_extra = get_value(previous, "extraMetadata")
        if isinstance(previous_extra, dict) and isinstance(previous_extra.get("fieldProvenance"), dict):
            provenance = dict(previous_extra["fieldProvenance"])

    stamp = {
        "by": get_value(user, "id"),
        "byName": get_value(user, "name"),
        "at": datetime.now(UTC).isoformat(),
    }

    for field, value in new_data.items():
        if field in PROVENANCE_SKIP_FIELDS or is_empty_value(value):
            continue
        previous_value = get_value(previous, field) if previous is not None else None
        if previous is None or is_empty_value(previous_value) or not values_match(previous_value, value):
            provenance[field] = stamp

    if provenance:
        base_extra["fieldProvenance"] = provenance
    if base_extra:
        # Prisma Json columns must receive a Json wrapper, not a raw dict.
        new_data["extraMetadata"] = Json(base_extra)
    else:
        new_data.pop("extraMetadata", None)


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
        "craft": "craftId",
        "workshop": "workshopId",
        "product": "productId",
        "tool": "toolId",
        "questionnaire": "questionnaireInterviewId",
        "questionnaireinterview": "questionnaireInterviewId",
    }
    field = field_map.get(normalized)
    return {field: record_id} if field else {}
