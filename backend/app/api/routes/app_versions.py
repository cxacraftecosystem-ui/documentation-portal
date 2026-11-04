from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query

from app.core.deps import require_admin
from app.services import app_versions

router = APIRouter(prefix="/admin", tags=["admin"])


@router.get("/app-versions")
async def app_versions_summary(days: int = Query(30, ge=1, le=365), _: Any = Depends(require_admin)) -> dict[str, Any]:
    return await app_versions.summary(days=days)
