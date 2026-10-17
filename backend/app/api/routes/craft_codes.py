"""Shared craft codes (C26): the catalogue, the crafts nobody has mapped yet, and the mapping itself.

``/craft-codes`` rather than ``/crafts/codes`` so ``/crafts/{craft_id}`` can never read "codes" as an
id. The catalogue is the shared file (``app/data/cross_portal_codes.json``) and is the same in both
portals; reading it is open to any signed-in user because it is a list of craft traditions and
nothing else. Mapping a craft is an administrator's act: a code joins this portal's craft to the
other portal's and to the public site's, so a wrong one merges two traditions in every count that
reads it.

Both portals carry this module under the same name, byte for byte; change both.
"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import db
from app.core.deps import get_current_user, require_admin
from app.services import cross_portal_codes

router = APIRouter(prefix="/craft-codes", tags=["craft-codes"])


class CraftCodeAssign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    #: The shared code, or ``null`` to clear the mapping.
    code: str | None = Field(default=None, max_length=80)
    #: Remember this craft's spelling for the next craft written the same way (an ADMIN spelling).
    rememberSpelling: bool = True


@router.get("")
async def list_craft_codes(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Every shared craft code, with how many of this portal's crafts carry it."""
    rows = await db.craft.find_many(where={"craftCode": {"not": None}})
    counts: dict[str, int] = {}
    for row in rows:
        counts[row.craftCode] = counts.get(row.craftCode, 0) + 1
    items = [
        {**entry, "craftCount": counts.get(entry["code"], 0)}
        for entry in cross_portal_codes.catalogue()
    ]
    return {"version": cross_portal_codes.shared().version, "items": items}


@router.get("/unmapped")
async def list_unmapped_crafts(_: Any = Depends(require_admin)) -> dict[str, Any]:
    """This portal's crafts that carry no code yet, oldest first, for an administrator to map."""
    rows = await db.craft.find_many(where={"craftCode": None}, order={"createdAt": "asc"})
    return {"items": [{"id": row.id, "name": row.name} for row in rows], "total": len(rows)}


@router.get("/questions")
async def list_question_codes(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The shared question codes, per instrument. A reference for a client or an export."""
    shared = cross_portal_codes.shared()
    return {
        "version": shared.version,
        "items": [
            {
                "code": q.code,
                "instrument": q.instrument,
                "section": q.section,
                "prompt": q.prompt,
            }
            for q in shared.questions
        ],
    }


@router.put("/crafts/{craft_id}")
async def assign_craft_code(
    craft_id: str,
    payload: CraftCodeAssign,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Map one craft to a shared code (or clear it), optionally remembering its spelling."""
    craft = await db.craft.find_unique(where={"id": craft_id})
    if craft is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Craft not found.")
    code = (payload.code or "").strip() or None
    if code is not None and not cross_portal_codes.is_known_code(code):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"“{code}” is not a shared craft code. Pick one from the list.",
        )
    return await cross_portal_codes.map_craft(
        db,
        craft=craft,
        code=code,
        remember_spelling=payload.rememberSpelling,
        user_id=getattr(current_user, "id", None),
    )
