from typing import Any

from fastapi import APIRouter, Depends

from app.core.deps import get_current_user
from app.services.address import address_reference
from app.services.district_lineage import lineage_reference
from app.services.standard_values import catalogue

router = APIRouter(prefix="/reference", tags=["reference"])


@router.get("/address")
async def get_address_reference(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return address_reference()


@router.get("/district-lineage")
async def get_district_lineage(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return lineage_reference()


@router.get("/standard-values")
async def get_standard_values(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return catalogue()
