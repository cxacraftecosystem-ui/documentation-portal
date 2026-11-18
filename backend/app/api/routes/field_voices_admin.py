from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from app.core.db import db
from app.core.deps import require_admin
from app.services import field_voices_portal
from app.services.field_voices import withdrawals

router = APIRouter(prefix="/field-voices", tags=["field-voices"])

SubjectType = Literal["ARTISAN", "RECORDING", "WORKSHOP", "USER"]
Basis = Literal["VERBAL_RECORDED", "VERBAL_WITNESSED", "SIGNED_FORM"]


class WithdrawalBody(BaseModel):
    shown: bool
    basis: Basis
    note: str | None = Field(default=None, max_length=withdrawals.NOTE_MAX_LENGTH)


@router.get("/withdrawals/{subject_type}/{subject_id}")
async def get_withdrawal(
    subject_type: SubjectType, subject_id: str, _: Any = Depends(require_admin)
) -> dict[str, Any]:
    try:
        return await withdrawals.status(db, field_voices_portal, subject_type, subject_id)
    except withdrawals.UnknownSubject as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found.") from exc


@router.post("/withdrawals/{subject_type}/{subject_id}")
async def set_withdrawal(
    subject_type: SubjectType, subject_id: str, body: WithdrawalBody, user: Any = Depends(require_admin)
) -> dict[str, Any]:
    try:
        return await withdrawals.record(
            db, field_voices_portal, subject_type, subject_id, shown=body.shown, basis=body.basis,
            note=body.note, actor_id=str(user.id),
        )
    except withdrawals.UnknownSubject as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found.") from exc
