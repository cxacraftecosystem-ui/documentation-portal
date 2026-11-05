from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from app.core.deps import require_content_admin
from app.services.artisan_merge import (
    merge_artisans,
    merge_candidates,
    merge_history,
    preview_merge,
    undo_merge,
)

router = APIRouter(prefix="/artisans/merge", tags=["artisans"])


class MergeRequest(BaseModel):
    keepId: str = Field(min_length=1)
    mergeId: str = Field(min_length=1)
    picks: dict[str, str] = Field(default_factory=dict)


@router.get("/candidates")
async def candidates(
    offset: int = Query(0, ge=0), _: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    return await merge_candidates(offset=offset if isinstance(offset, int) else 0)


@router.get("/preview")
async def preview(
    keepId: str = Query(...), mergeId: str = Query(...), _: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    return await preview_merge(keepId, mergeId)


@router.post("")
async def merge(payload: MergeRequest, current_user: Any = Depends(require_content_admin)) -> dict[str, Any]:
    return await merge_artisans(payload.keepId, payload.mergeId, payload.picks, current_user)


@router.get("/history")
async def history(_: Any = Depends(require_content_admin)) -> dict[str, Any]:
    return await merge_history()


@router.post("/{merge_id}/undo")
async def undo(merge_id: str, current_user: Any = Depends(require_content_admin)) -> dict[str, Any]:
    return await undo_merge(merge_id, current_user)
