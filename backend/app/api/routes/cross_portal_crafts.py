from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response

from app.core.db import db
from app.services import craft_feed
from app.services.cross_portal_peer import requested_by, require_craft_feed_peer, signed

router = APIRouter(prefix="/cross-portal/peer", tags=["cross-portal"])

PORTAL = craft_feed.FIELD_REPOSITORY

ACTION_CRAFT_FEED = "CRAFT_FEED"
OUTCOME_OK = "OK"


@router.get("/crafts")
async def peer_crafts(
    request: Request, settings: Any = Depends(require_craft_feed_peer)
) -> Response:
    payload = await craft_feed.build(db, PORTAL)
    await db.crossportalaccesslog.create(
        data={
            "action": ACTION_CRAFT_FEED,
            "outcome": OUTCOME_OK,
            "remoteUserId": requested_by(request),
            "resultCount": len(payload["crafts"]),
        }
    )
    return signed(settings, request, payload)
