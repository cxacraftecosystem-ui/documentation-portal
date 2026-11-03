from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import HTTPException, status

from app.services.concurrency import gather_reads
from app.services.records import add_date_range, contains, viewable_where

RECORD_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools", "media")

PLACED_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools")

UNASSIGNED_WORKSHOP = "none"


def resolve_workshop_ids(raw: list[str] | None) -> tuple[list[str], bool] | None:
    if not raw:
        return None
    wanted = [
        part.strip()
        for value in raw
        for part in str(value).split(",")
        if part.strip()
    ]
    if not wanted:
        return None
    include_unassigned = any(value == UNASSIGNED_WORKSHOP for value in wanted)
    ids = list(dict.fromkeys(value for value in wanted if value != UNASSIGNED_WORKSHOP))
    return ids, include_unassigned


def resolve_craft_ids(raw: list[str] | None) -> list[str] | None:
    if not raw:
        return None
    wanted = [
        part.strip()
        for value in raw
        for part in str(value).split(",")
        if part.strip()
    ]
    return list(dict.fromkeys(wanted)) or None


def workshop_clause(
    ids: list[str], include_unassigned: bool, *, is_workshop_table: bool = False
) -> dict[str, Any] | None:
    if is_workshop_table:
        return {"id": {"in": ids}} if ids else None
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
    if include_unassigned:
        branches.append({"workshopId": None})
    if not branches:
        return None
    return branches[0] if len(branches) == 1 else {"OR": branches}


def artisan_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    branches: list[dict[str, Any]] = []
    if ids:
        branches.extend(
            [
                {"workshopId": {"in": ids}},
                {"workshops": {"some": {"workshopId": {"in": ids}}}},
                {"questionnaireInterviews": {"some": {"interview": {"is": {"workshopId": {"in": ids}}}}}},
            ]
        )
    if include_unassigned:
        branches.append({"workshopId": None})
    return {"OR": branches} if branches else {"id": {"in": []}}


def craft_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
        branches.append({"workshops": {"some": {"workshopId": {"in": ids}}}})
    if include_unassigned:
        branches.append({"AND": [{"workshopId": None}, {"workshops": {"none": {}}}]})
    return {"OR": branches} if branches else {"id": {"in": []}}


def resolve_types(raw: list[str] | None) -> set[str]:
    if not raw:
        return set(RECORD_TYPES)
    wanted = {
        part.strip().lower()
        for value in raw
        for part in str(value).split(",")
        if part.strip()
    }
    if not wanted:
        return set(RECORD_TYPES)
    unknown = sorted(wanted - set(RECORD_TYPES))
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"Unknown search type{'s' if len(unknown) > 1 else ''}: {', '.join(unknown)}. "
                f"Valid types are {', '.join(RECORD_TYPES)}."
            ),
        )
    return wanted


async def build_record_wheres(
    user: Any,
    *,
    q: str | None = None,
    craft_id: str | None = None,
    place: str | None = None,
    artisan_id: str | None = None,
    media_type: str | None = None,
    date_from: datetime | None = None,
    date_to: datetime | None = None,
    workshop_ids: list[str] | None = None,
) -> dict[str, dict[str, Any]]:
    record_visibility, media_visibility = await gather_reads(
        viewable_where(user, owner_field="createdById"),
        viewable_where(user, owner_field="uploadedById"),
    )

    artisan_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    workshop_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    product_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    tool_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    media_where: dict[str, Any] = {"AND": [media_visibility]} if media_visibility else {}

    if q:
        artisan_where["OR"] = [{"name": contains(q)}, {"localName": contains(q)}, {"place": contains(q)}]
        workshop_where["OR"] = [{"title": contains(q)}, {"place": contains(q)}, {"description": contains(q)}]
        product_where["OR"] = [
            {"productName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        tool_where["OR"] = [
            {"toolkitName": contains(q)},
            {"englishName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        media_where["OR"] = [
            {"originalFilename": contains(q)},
            {"caption": contains(q)},
            {"mimeType": contains(q)},
        ]

    if craft_id:
        artisan_where["craftId"] = craft_id
        product_where["craftId"] = craft_id
        tool_where["craftId"] = craft_id
    if place:
        artisan_where["place"] = contains(place)
        workshop_where["place"] = contains(place)
        product_where["place"] = contains(place)
        tool_where["place"] = contains(place)
    if artisan_id:
        product_where["artisanId"] = artisan_id
        tool_where["artisanId"] = artisan_id
    if media_type:
        media_where["mediaType"] = media_type

    if date_from or date_to:
        date_range: dict[str, Any] = {}
        if date_from:
            date_range["gte"] = date_from
        if date_to:
            date_range["lte"] = date_to
        workshop_where.setdefault("AND", []).append(
            {"OR": [{"startDate": date_range}, {"startDate": None, "date": date_range}]}
        )
    add_date_range(artisan_where, "createdAt", date_from, date_to)
    add_date_range(product_where, "createdAt", date_from, date_to)
    add_date_range(tool_where, "createdAt", date_from, date_to)
    add_date_range(media_where, "createdAt", date_from, date_to)

    wheres = {
        "artisans": artisan_where,
        "workshops": workshop_where,
        "products": product_where,
        "tools": tool_where,
        "media": media_where,
    }

    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        for bucket, where in wheres.items():
            clause = workshop_clause(
                ids, include_unassigned, is_workshop_table=(bucket == "workshops")
            )
            if clause is not None:
                where.setdefault("AND", []).append(clause)
            else:
                where.setdefault("AND", []).append({"id": {"in": []}})

    return wheres
