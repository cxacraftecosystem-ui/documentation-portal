"""The documentation portal's half of C27: answer the designer portal, and record each artisan's consent.

Two kinds of caller, kept apart by path:

* ``/cross-portal/peer/...`` is the DESIGNER PORTAL'S SERVER, authenticated by the shared bearer
  token and answered with a signed body (``services/cross_portal``). No user session is read or
  accepted there — a signed-in researcher's token is not the peer token and is refused.
* ``/cross-portal/status`` and ``/cross-portal/artisans/{id}/consent`` are this portal's own signed-in
  users: whether the feature is on, and the artisan's sharing consent, which only somebody who may
  edit the artisan may record or withdraw.

Every route here answers 404 while the feature is not active on this server, exactly as if it did
not exist. This portal is the source of truth for the identity fields and never writes to the other.
"""

import json
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value
from app.services import cross_portal
from app.services.access import record_edit_privilege
from app.services.artisan_identity import normalize_pehchan

router = APIRouter(prefix="/cross-portal", tags=["cross-portal"])


def _active_or_404() -> Any:
    settings = get_settings()
    if not cross_portal.active(settings):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


async def require_peer(authorization: str | None = Header(default=None)) -> Any:
    """The designer portal's server, by the shared token, and nobody else."""
    settings = _active_or_404()
    if not cross_portal.bearer_matches(settings.cross_portal_token, authorization):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authorised.")
    return settings


def _signed(settings: Any, payload: Any, status_code: int = 200) -> Response:
    body = json.dumps(payload, separators=(",", ":"), default=str).encode("utf-8")
    return Response(
        content=body,
        status_code=status_code,
        media_type="application/json",
        headers={cross_portal.SIGNATURE_HEADER: cross_portal.sign(settings.cross_portal_token, body)},
    )


class PeerLookup(BaseModel):
    model_config = ConfigDict(extra="forbid")

    pehchanCardNumber: str | None = Field(default=None, max_length=40)
    aadhaarCode: str | None = Field(default=None, min_length=64, max_length=64)


class PeerLink(BaseModel):
    model_config = ConfigDict(extra="forbid")

    remoteArtisanId: str = Field(min_length=1, max_length=64)
    matchedBy: str = Field(pattern="^(PEHCHAN|AADHAAR)$")


class ConsentIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    granted: bool
    note: str | None = Field(default=None, max_length=500)


async def _consenting(artisan_id: str) -> Any | None:
    """The artisan, when their consent is recorded as granted; ``None`` otherwise."""
    consent = await db.artisansharingconsent.find_unique(where={"artisanId": artisan_id})
    if consent is None or not consent.granted:
        return None
    return await db.artisan.find_unique(
        where={"id": artisan_id}, include={"craft": True, "location": True}
    )


def _answer(artisan: Any, matched_by: str) -> dict[str, Any]:
    craft = getattr(artisan, "craft", None)
    location = getattr(artisan, "location", None)
    return {
        "artisanId": artisan.id,
        "matchedBy": matched_by,
        "fields": cross_portal.shared_fields(artisan),
        "craft": (
            {"code": getattr(craft, "craftCode", None), "name": craft.name} if craft else None
        ),
        "statedAddress": (
            {
                "state": getattr(location, "state", None),
                "district": getattr(location, "district", None),
                "village": getattr(location, "village", None),
            }
            if location
            else None
        ),
    }


@router.post("/peer/artisans/lookup")
async def peer_lookup(payload: PeerLookup, settings: Any = Depends(require_peer)) -> Response:
    """One artisan by Pehchan number or by the Aadhaar code, if their consent is recorded here.

    "No such artisan" and "no consent" are the SAME answer, on purpose: telling the other portal that
    somebody is on file here without their agreement would itself be a disclosure.
    """
    pehchan = normalize_pehchan(payload.pehchanCardNumber)
    if pehchan:
        row = await db.artisan.find_first(where={"pehchanCardNumber": pehchan})
        if row is not None and getattr(row, "status", None) != "REJECTED":
            artisan = await _consenting(row.id)
            if artisan is not None:
                return _signed(
                    settings, {"match": _answer(artisan, cross_portal.MATCHED_BY_PEHCHAN)}
                )
    if payload.aadhaarCode:
        # Only artisans who agreed are hashed at all: the set is small, and an artisan without
        # consent is never even compared.
        consents = await db.artisansharingconsent.find_many(where={"granted": True})
        ids = [c.artisanId for c in consents]
        rows = (
            await db.artisan.find_many(where={"id": {"in": ids}, "aadhaarNumber": {"not": None}})
            if ids
            else []
        )
        key = settings.cross_portal_aadhaar_hmac_key
        for row in rows:
            code = cross_portal.aadhaar_code(row.aadhaarNumber, key)
            if cross_portal.codes_match(code, payload.aadhaarCode) and (
                getattr(row, "status", None) != "REJECTED"
            ):
                artisan = await _consenting(row.id)
                if artisan is not None:
                    return _signed(
                        settings, {"match": _answer(artisan, cross_portal.MATCHED_BY_AADHAAR)}
                    )
    return _signed(settings, {"match": None})


@router.get("/peer/artisans/{artisan_id}")
async def peer_refresh(
    artisan_id: str, remoteArtisanId: str, settings: Any = Depends(require_peer)
) -> Response:
    """The identity fields again, for a record the designer portal has LINKED. 404 otherwise."""
    link = await db.crossportalartisanlink.find_first(
        where={"artisanId": artisan_id, "remoteArtisanId": remoteArtisanId}
    )
    artisan = await _consenting(artisan_id) if link is not None else None
    if artisan is None:
        return _signed(settings, {"match": None}, status_code=404)
    return _signed(settings, {"match": _answer(artisan, link.matchedBy)})


@router.post("/peer/artisans/{artisan_id}/links")
async def peer_link(
    artisan_id: str, payload: PeerLink, settings: Any = Depends(require_peer)
) -> Response:
    """Record the designer portal's link. Refused (409) once consent is not granted."""
    if await _consenting(artisan_id) is None:
        return _signed(settings, {"linked": False}, status_code=409)
    existing = await db.crossportalartisanlink.find_first(
        where={"artisanId": artisan_id, "remoteArtisanId": payload.remoteArtisanId}
    )
    if existing is None:
        await db.crossportalartisanlink.create(
            data={
                "artisanId": artisan_id,
                "remoteArtisanId": payload.remoteArtisanId,
                "matchedBy": payload.matchedBy,
            }
        )
    return _signed(settings, {"linked": True})


@router.delete("/peer/artisans/{artisan_id}/links/{remote_artisan_id}")
async def peer_unlink(
    artisan_id: str, remote_artisan_id: str, settings: Any = Depends(require_peer)
) -> Response:
    await db.crossportalartisanlink.delete_many(
        where={"artisanId": artisan_id, "remoteArtisanId": remote_artisan_id}
    )
    return _signed(settings, {"linked": False})


# ──────────────────────────────────────────────────────────────────────────────────────────────
# This portal's own users
# ──────────────────────────────────────────────────────────────────────────────────────────────


@router.get("/status")
async def cross_portal_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Whether the clients should draw the sharing switch at all."""
    return {"enabled": cross_portal.active(get_settings())}


def _consent_payload(consent: Any | None, links: int) -> dict[str, Any]:
    return {
        "granted": bool(consent and consent.granted),
        "recorded": consent is not None,
        "note": getattr(consent, "note", None),
        "recordedAt": getattr(consent, "updatedAt", None) or getattr(consent, "recordedAt", None),
        "linkedRecords": links,
    }


@router.get("/artisans/{artisan_id}/consent")
async def get_consent(artisan_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    _active_or_404()
    if await db.artisan.find_unique(where={"id": artisan_id}) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    consent = await db.artisansharingconsent.find_unique(where={"artisanId": artisan_id})
    links = await db.crossportalartisanlink.count(where={"artisanId": artisan_id})
    return _consent_payload(consent, links)


@router.put("/artisans/{artisan_id}/consent")
async def set_consent(
    artisan_id: str, payload: ConsentIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Record, or withdraw, the artisan's agreement to share with the designer portal.

    Withdrawing also removes this portal's link rows, so a later refresh from the designer portal is
    refused; the designer portal's own copy of what it already filled in is that portal's record.
    """
    _active_or_404()
    artisan = await db.artisan.find_unique(where={"id": artisan_id})
    if artisan is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not await record_edit_privilege(artisan, current_user, "artisan"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only someone who may edit this artisan can record their consent.",
        )
    data = {
        "granted": payload.granted,
        "note": (payload.note or "").strip() or None,
        "recordedById": get_value(current_user, "id"),
        "recordedAt": datetime.now(UTC),
    }
    consent = await db.artisansharingconsent.upsert(
        where={"artisanId": artisan_id},
        data={"create": {"artisanId": artisan_id, **data}, "update": data},
    )
    if not payload.granted:
        await db.crossportalartisanlink.delete_many(where={"artisanId": artisan_id})
    links = await db.crossportalartisanlink.count(where={"artisanId": artisan_id})
    return _consent_payload(consent, links)
