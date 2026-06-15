from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user
from app.services.pagination import normalize_pagination
from app.services.records import add_date_range, contains, visibility_where

router = APIRouter(prefix="/search", tags=["search"])


@router.get("")
async def global_search(
    current_user: Any = Depends(get_current_user),
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(10, ge=1, le=50),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)

    artisan_where = visibility_where(current_user)
    workshop_where = visibility_where(current_user)
    product_where = visibility_where(current_user)
    tool_where = visibility_where(current_user)
    media_where = visibility_where(current_user, owner_field="uploadedById")

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
        media_where["OR"] = [{"originalFilename": contains(q)}, {"caption": contains(q)}, {"mimeType": contains(q)}]

    if craftId:
        artisan_where["craftId"] = craftId
        product_where["craftId"] = craftId
        tool_where["craftId"] = craftId
    if place:
        artisan_where["place"] = contains(place)
        workshop_where["place"] = contains(place)
        product_where["place"] = contains(place)
        tool_where["place"] = contains(place)
    if artisanId:
        product_where["artisanId"] = artisanId
        tool_where["artisanId"] = artisanId
    if mediaType:
        media_where["mediaType"] = mediaType

    add_date_range(workshop_where, "date", dateFrom, dateTo)
    add_date_range(product_where, "createdAt", dateFrom, dateTo)
    add_date_range(tool_where, "createdAt", dateFrom, dateTo)
    add_date_range(media_where, "createdAt", dateFrom, dateTo)

    artisans = await db.artisan.find_many(where=artisan_where, skip=skip, take=page_size, order={"createdAt": "desc"})
    workshops = await db.workshop.find_many(where=workshop_where, skip=skip, take=page_size, order={"date": "desc"})
    products = await db.productdocumentation.find_many(
        where=product_where,
        include={"media": True},
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    tools = await db.tooldocumentation.find_many(
        where=tool_where,
        include={"media": True},
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    media = await db.mediafile.find_many(where=media_where, skip=skip, take=page_size, order={"createdAt": "desc"})

    return jsonable_encoder(
        {
            "query": q,
            "page": page,
            "pageSize": page_size,
            "artisans": artisans,
            "workshops": workshops,
            "products": products,
            "tools": tools,
            "media": media,
        }
    )
