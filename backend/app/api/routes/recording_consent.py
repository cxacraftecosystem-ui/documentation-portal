"""Record whether an artisan's recordings may be sent for transcription (the consent rule).

``/artisans/{id}/recording-consent`` answers for every recording of that artisan;
``/media/{id}/recording-consent`` for one recording, and wins over the artisan's grant. Anyone who
may edit the record may record the answer, and every change is appended, never overwritten.

Both write the ONE consent store, the ``Consent`` ledger (``services/recording_consent.py``): the artisan
route is the same row ``/artisans/{id}/consent`` writes. The web and Android apps now use that route;
these stay for the Android builds already installed.
"""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import db
from app.core.deps import get_current_user, get_value, is_admin
from app.services import recording_consent
from app.services.access import record_edit_privilege

router = APIRouter(tags=["recording-consent"])


class RecordingConsentIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: Literal["GRANTED", "REFUSED"]
    note: str | None = Field(default=None, max_length=500)


async def _artisan_or_404(artisan_id: str) -> Any:
    artisan = await db.artisan.find_unique(where={"id": artisan_id})
    if artisan is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return artisan


async def _media_or_404(media_id: str) -> Any:
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return media


@router.get("/artisans/{artisan_id}/recording-consent")
async def get_artisan_recording_consent(artisan_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    await _artisan_or_404(artisan_id)
    row = await recording_consent.latest_decision(artisan_id=artisan_id)
    return recording_consent.payload(row, str(row["state"]) if row else recording_consent.NOT_RECORDED)


@router.put("/artisans/{artisan_id}/recording-consent")
async def set_artisan_recording_consent(
    artisan_id: str, body: RecordingConsentIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan = await _artisan_or_404(artisan_id)
    if not await record_edit_privilege(artisan, current_user, "artisan"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only someone who may edit this artisan can record their consent.",
        )
    row = await recording_consent.record(
        decision=body.decision, actor_id=get_value(current_user, "id"), artisan_id=artisan_id, note=body.note
    )
    return recording_consent.payload(row, body.decision)


@router.get("/media/{media_id}/recording-consent")
async def get_media_recording_consent(media_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await _media_or_404(media_id)
    own = await recording_consent.latest_decision(media_id=media_id)
    decision = await recording_consent.decision_for(media)
    return recording_consent.payload(own, decision)


@router.put("/media/{media_id}/recording-consent")
async def set_media_recording_consent(
    media_id: str, body: RecordingConsentIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    media = await _media_or_404(media_id)
    uploader = get_value(media, "uploadedById")
    if not (
        is_admin(current_user)
        or (uploader is not None and uploader == get_value(current_user, "id"))
        or await record_edit_privilege(media, current_user, "media")
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only someone who may edit this recording can record its consent.",
        )
    row = await recording_consent.record(
        decision=body.decision, actor_id=get_value(current_user, "id"), media_id=media_id, note=body.note
    )
    return recording_consent.payload(row, body.decision)
