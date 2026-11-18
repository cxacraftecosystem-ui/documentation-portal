from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response

from app.core.db import db
from app.services import cross_portal, field_voices_portal
from app.services.cross_portal_peer import (
    requested_by,
    require_field_voices_images_peer,
    require_field_voices_peer,
    signed,
)
from app.services.field_voices import contract, gone, ledger, pages

router = APIRouter(prefix="/cross-portal/peer", tags=["cross-portal"])

PORTAL = field_voices_portal.PORTAL
WINDOW = pages.RateWindow()


def _answer(
    settings: Any, request: Request, payload: Any, *, status_code: int = 200, retry_after: int | None = None
) -> Response:
    response = signed(settings, request, payload, status_code=status_code)
    response.headers["Cache-Control"] = "no-store"
    if retry_after is not None:
        response.headers["Retry-After"] = str(retry_after)
    return response


async def _log(request: Request, action: str, outcome: str, *, stream: str | None, count: int) -> None:
    data: dict[str, Any] = {
        "action": action,
        "outcome": outcome,
        "remoteUserId": requested_by(request),
        "resultCount": count,
    }
    if stream is not None:
        data.update({"entityType": contract.ENTITY_STREAM, "entityId": stream})
    await db.crossportalaccesslog.create(data=data)


async def _limited(settings: Any, request: Request, action: str, stream: str | None) -> Response | None:
    wait = WINDOW.admit(requested_by(request))
    if wait is None:
        return None
    await _log(request, action, contract.OUTCOME_RATE_LIMITED, stream=stream, count=0)
    return _answer(settings, request, {"detail": contract.DETAIL_TOO_MANY}, status_code=429, retry_after=wait)


@router.get("/field-voices")
async def peer_field_voices_state(request: Request, settings: Any = Depends(require_field_voices_peer)) -> Response:
    refused = await _limited(settings, request, contract.ACTION_STATE, None)
    if refused is not None:
        return refused
    try:
        await gone.gone_pass(db, field_voices_portal)
    except ledger.Busy:
        await _log(request, contract.ACTION_STATE, contract.OUTCOME_BUSY, stream=None, count=0)
        return _answer(
            settings, request, {"detail": contract.DETAIL_BUSY}, status_code=503,
            retry_after=contract.BUSY_RETRY_AFTER_SECONDS,
        )
    payload = await pages.state(
        db, portal=PORTAL, token=settings.cross_portal_token,
        images_enabled=cross_portal.field_voices_images_active(settings),
    )
    await _log(request, contract.ACTION_STATE, contract.OUTCOME_OK, stream=None, count=0)
    return _answer(settings, request, payload)


async def _stream(settings: Any, request: Request, stream: str, cursor: str | None, view: str | None) -> Response:
    action = contract.ACTIONS[stream]
    refused = await _limited(settings, request, action, stream)
    if refused is not None:
        return refused
    image_url = pages.presigned if stream == contract.IMAGES else None
    try:
        payload = await pages.page(
            db, portal=PORTAL, token=settings.cross_portal_token, stream=stream, view=view, cursor=cursor,
            image_url=image_url,
        )
    except pages.BadView:
        await _log(request, action, contract.OUTCOME_BAD_VIEW, stream=stream, count=0)
        return _answer(settings, request, {"detail": contract.DETAIL_UNKNOWN_VIEW}, status_code=400)
    except ledger.UnknownCursor:
        await _log(request, action, contract.OUTCOME_BAD_CURSOR, stream=stream, count=0)
        return _answer(settings, request, {"detail": contract.DETAIL_UNKNOWN_CURSOR}, status_code=400)
    await _log(request, action, contract.OUTCOME_OK, stream=stream, count=len(payload["changes"]))
    return _answer(settings, request, payload)


@router.get("/field-voices/transcripts")
async def peer_field_voices_transcripts(
    request: Request,
    cursor: str | None = None,
    view: str | None = None,
    settings: Any = Depends(require_field_voices_peer),
) -> Response:
    return await _stream(settings, request, contract.TRANSCRIPTS, cursor, view)


@router.get("/field-voices/images")
async def peer_field_voices_images(
    request: Request,
    cursor: str | None = None,
    view: str | None = None,
    settings: Any = Depends(require_field_voices_images_peer),
) -> Response:
    return await _stream(settings, request, contract.IMAGES, cursor, view)
