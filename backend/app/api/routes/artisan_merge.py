"""The duplicate-artisan merge tool (C24), admins only.

Mounted BEFORE ``routes/artisans`` and under the same ``/artisans`` prefix, so the literal ``/merge``
segment is matched before ``/artisans/{artisan_id}`` could read it as an artisan whose id is the word
"merge". See ``services/artisan_merge`` for what a merge does and how it is undone.
"""

from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from app.core.deps import require_admin
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
    #: {fieldKey: "keep" | "merge" | "both"}. A field left out takes the kept record's answer where it
    #: has one, the other record's where only it does — the preview's ``default``.
    picks: dict[str, str] = Field(default_factory=dict)


@router.get("/candidates")
async def candidates(_: Any = Depends(require_admin)) -> dict[str, Any]:
    """Pairs that look like one person: the same phone number, or the same name in the same place."""
    return await merge_candidates()


@router.get("/preview")
async def preview(
    keepId: str = Query(...), mergeId: str = Query(...), _: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Both records side by side, what would move, and anything that blocks the merge. A read."""
    return await preview_merge(keepId, mergeId)


@router.post("")
async def merge(payload: MergeRequest, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    """Fold ``mergeId`` into ``keepId``. Undoable for 30 days from the history."""
    return await merge_artisans(payload.keepId, payload.mergeId, payload.picks, current_user)


@router.get("/history")
async def history(_: Any = Depends(require_admin)) -> dict[str, Any]:
    return await merge_history()


@router.post("/{merge_id}/undo")
async def undo(merge_id: str, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    return await undo_merge(merge_id, current_user)
