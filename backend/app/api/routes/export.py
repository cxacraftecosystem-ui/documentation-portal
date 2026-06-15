from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import get_current_user
from app.services.csv_export import PRODUCT_FIELDS, TOOL_FIELDS, records_to_csv
from app.services.records import visibility_where

router = APIRouter(prefix="/export", tags=["export"])


def csv_response(filename: str, body: str) -> Response:
    return Response(
        content=body,
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get("/products.csv")
async def export_products(current_user: Any = Depends(get_current_user)) -> Response:
    records = await db.productdocumentation.find_many(
        where=visibility_where(current_user),
        include={"media": True},
        order={"createdAt": "desc"},
    )
    return csv_response("products.csv", records_to_csv(records, PRODUCT_FIELDS))


@router.get("/tools.csv")
async def export_tools(current_user: Any = Depends(get_current_user)) -> Response:
    records = await db.tooldocumentation.find_many(
        where=visibility_where(current_user),
        include={"media": True},
        order={"createdAt": "desc"},
    )
    return csv_response("tools.csv", records_to_csv(records, TOOL_FIELDS))
