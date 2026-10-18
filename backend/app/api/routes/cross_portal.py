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

Every peer request is written to ``CrossPortalAccessLog`` (which designer-portal account asked, what
for, and which artisan it reached, or that it reached none) — never the number or the code it asked
with. Every consent change is a ``RecordRevision`` on the artisan, so a withdrawal leaves a trace.
"""

import json
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value
from app.services import aadhaar_fingerprint, artisan_card, artisan_card_store, cross_portal
from app.services.access import record_edit_privilege, record_revision
from app.services.artisan_identity import normalize_pehchan

router = APIRouter(prefix="/cross-portal", tags=["cross-portal"])


def _active_or_404() -> Any:
    settings = get_settings()
    if not cross_portal.active(settings):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


async def require_peer(
    request: Request,
    authorization: str | None = Header(default=None),
    x_cross_portal_nonce: str | None = Header(default=None),
) -> Any:
    """The designer portal's server, by the shared token and a fresh nonce, and nobody else."""
    settings = _active_or_404()
    if not cross_portal.bearer_matches(settings.cross_portal_token, authorization):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authorised.")
    nonce = (x_cross_portal_nonce or "").strip()
    if len(nonce) < cross_portal.MIN_NONCE_LENGTH or len(nonce) > 128:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authorised.")
    request.state.cross_portal_nonce = nonce
    return settings


def _signed(settings: Any, request: Request, payload: Any, status_code: int = 200) -> Response:
    """The answer, signed over the request it answers (method, peer path, nonce) and its body."""
    body = json.dumps(payload, separators=(",", ":"), default=str).encode("utf-8")
    signature = cross_portal.sign(
        settings.cross_portal_token,
        body,
        method=request.method,
        path=request.url.path,
        nonce=getattr(request.state, "cross_portal_nonce", ""),
    )
    return Response(
        content=body,
        status_code=status_code,
        media_type="application/json",
        headers={cross_portal.SIGNATURE_HEADER: signature},
    )


ACTION_LOOKUP = "LOOKUP"
ACTION_REFRESH = "REFRESH"
ACTION_LINK = "LINK"
ACTION_UNLINK = "UNLINK"
ACTION_CARD_RESOLVE = "CARD_RESOLVE"
ACTION_CARD_UPDATE = "CARD_UPDATE"

#: A card code as both portals print it: 10 Crockford characters (services/artisan_card.py).
CARD_CODE_PATTERN = "^[0-9A-HJKMNP-TV-Z]{10}$"


async def _log_access(
    request: Request,
    action: str,
    outcome: str,
    *,
    artisan_id: str | None = None,
    remote_artisan_id: str | None = None,
    matched_by: str | None = None,
) -> None:
    """One row per peer request: who asked (the designer-portal account id), what, and what it
    reached. Never the Pehchan number or the Aadhaar code."""
    asked_by = (request.headers.get(cross_portal.REQUESTED_BY_HEADER) or "").strip()[:64] or None
    await db.crossportalaccesslog.create(
        data={
            "action": action,
            "outcome": outcome,
            "artisanId": artisan_id,
            "remoteUserId": asked_by,
            "remoteArtisanId": (remote_artisan_id or "")[:64] or None,
            "matchedBy": matched_by,
        }
    )


class PeerLookup(BaseModel):
    model_config = ConfigDict(extra="forbid")

    pehchanCardNumber: str | None = Field(default=None, max_length=40)
    aadhaarCode: str | None = Field(default=None, min_length=64, max_length=64)


class PeerLink(BaseModel):
    model_config = ConfigDict(extra="forbid")

    remoteArtisanId: str = Field(min_length=1, max_length=64)
    matchedBy: str = Field(pattern="^(PEHCHAN|AADHAAR|CARD)$")
    # The designer portal's live card code for its record, so its card opens this artisan here.
    remoteCardCode: str | None = Field(default=None, pattern=CARD_CODE_PATTERN)


class PeerCardResolve(BaseModel):
    model_config = ConfigDict(extra="forbid")

    cardCode: str = Field(pattern=CARD_CODE_PATTERN)
    tag: str | None = Field(default=None, pattern="^[0-9A-HJKMNP-TV-Z]{16}$")
    keyVersion: int | None = Field(default=None, ge=1, le=9)


class PeerCardUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    remoteCardCode: str = Field(pattern=CARD_CODE_PATTERN)


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


async def _card_code(artisan_id: str, settings: Any) -> str | None:
    """This portal's live card code for the artisan, issuing one if cards are on; else ``None``."""
    if not artisan_card.active(settings):
        return None
    card, _ = await artisan_card_store.ensure_card(
        db, artisan_id, settings=settings, user_id=None, source="PEER"
    )
    return card.code


async def _hold_remote_card_code(link_id: str | None, code: str | None) -> None:
    """Free ``code`` from any other link before it is written (the column is unique)."""
    if not code:
        return
    where: dict[str, Any] = {"remoteCardCode": code}
    if link_id:
        where["NOT"] = {"id": link_id}
    await db.crossportalartisanlink.update_many(where=where, data={"remoteCardCode": None})


async def _legacy_aadhaar_matches(code: str, settings: Any) -> list[Any]:
    """Consenting artisans the fingerprint backfill has not reached yet, whose code is ``code``.

    Only artisans who agreed are compared at all, and only while any row still holds the legacy
    number without its fingerprint (``aadhaar_fingerprint.legacy_pending``).
    """
    if not aadhaar_fingerprint.LEGACY_COLUMN_PRESENT or not await aadhaar_fingerprint.legacy_pending(db):
        return []
    consents = await db.artisansharingconsent.find_many(where={"granted": True})
    ids = [c.artisanId for c in consents]
    if not ids:
        return []
    rows = await db.artisan.find_many(
        where={
            "id": {"in": ids},
            aadhaar_fingerprint.NUMBER_FIELD: {"not": None},
            aadhaar_fingerprint.FINGERPRINT_FIELD: None,
        }
    )
    key = aadhaar_fingerprint.configured_key(settings)
    out = []
    for row in rows:
        try:
            mine = cross_portal.aadhaar_code(getattr(row, aadhaar_fingerprint.NUMBER_FIELD), key)
        except ValueError:
            continue
        if cross_portal.codes_match(mine, code):
            out.append(row)
    return out


@router.post("/peer/artisans/lookup")
async def peer_lookup(
    payload: PeerLookup, request: Request, settings: Any = Depends(require_peer)
) -> Response:
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
                await _log_access(
                    request,
                    ACTION_LOOKUP,
                    "MATCH",
                    artisan_id=artisan.id,
                    matched_by=cross_portal.MATCHED_BY_PEHCHAN,
                )
                return _signed(
                    settings, request, {"match": _answer(artisan, cross_portal.MATCHED_BY_PEHCHAN)}
                )
    code = (payload.aadhaarCode or "").strip().lower()
    if aadhaar_fingerprint.is_fingerprint(code):
        # THE CODE IS A STORED FINGERPRINT. Both registers keep HMAC-SHA256(AADHAAR_FINGERPRINT_KEY,
        # the 12 digits) and no number, and the designer portal sends exactly that, so this is one
        # lookup on the unique column. Consent is still checked before anything is answered.
        rows = await db.artisan.find_many(where={aadhaar_fingerprint.FINGERPRINT_FIELD: code})
        if not rows:
            rows = await _legacy_aadhaar_matches(code, settings)
        for row in rows:
            if getattr(row, "status", None) != "REJECTED":
                artisan = await _consenting(row.id)
                if artisan is not None:
                    await _log_access(
                        request,
                        ACTION_LOOKUP,
                        "MATCH",
                        artisan_id=artisan.id,
                        matched_by=cross_portal.MATCHED_BY_AADHAAR,
                    )
                    return _signed(
                        settings,
                        request,
                        {"match": _answer(artisan, cross_portal.MATCHED_BY_AADHAAR)},
                    )
    await _log_access(request, ACTION_LOOKUP, "NO_MATCH")
    return _signed(settings, request, {"match": None})


@router.get("/peer/artisans/{artisan_id}")
async def peer_refresh(
    artisan_id: str,
    remoteArtisanId: str,
    request: Request,
    settings: Any = Depends(require_peer),
) -> Response:
    """The identity fields again, for a record the designer portal has LINKED. 404 otherwise."""
    link = await db.crossportalartisanlink.find_first(
        where={"artisanId": artisan_id, "remoteArtisanId": remoteArtisanId}
    )
    artisan = await _consenting(artisan_id) if link is not None else None
    if artisan is None:
        await _log_access(request, ACTION_REFRESH, "NO_MATCH", remote_artisan_id=remoteArtisanId)
        return _signed(settings, request, {"match": None}, status_code=404)
    await _log_access(
        request,
        ACTION_REFRESH,
        "MATCH",
        artisan_id=artisan.id,
        remote_artisan_id=remoteArtisanId,
        matched_by=link.matchedBy,
    )
    return _signed(
        settings,
        request,
        {"match": _answer(artisan, link.matchedBy), "cardCode": await _card_code(artisan.id, settings)},
    )


@router.post("/peer/artisans/{artisan_id}/links")
async def peer_link(
    artisan_id: str, payload: PeerLink, request: Request, settings: Any = Depends(require_peer)
) -> Response:
    """Record the designer portal's link. Refused (409) once consent is not granted."""
    if await _consenting(artisan_id) is None:
        await _log_access(
            request, ACTION_LINK, "REFUSED", remote_artisan_id=payload.remoteArtisanId
        )
        return _signed(settings, request, {"linked": False}, status_code=409)
    existing = await db.crossportalartisanlink.find_first(
        where={"artisanId": artisan_id, "remoteArtisanId": payload.remoteArtisanId}
    )
    await _hold_remote_card_code(getattr(existing, "id", None), payload.remoteCardCode)
    if existing is None:
        await db.crossportalartisanlink.create(
            data={
                "artisanId": artisan_id,
                "remoteArtisanId": payload.remoteArtisanId,
                "matchedBy": payload.matchedBy,
                "remoteCardCode": payload.remoteCardCode,
            }
        )
    elif payload.remoteCardCode and payload.remoteCardCode != getattr(existing, "remoteCardCode", None):
        await db.crossportalartisanlink.update(
            where={"id": existing.id}, data={"remoteCardCode": payload.remoteCardCode}
        )
    await _log_access(
        request,
        ACTION_LINK,
        "LINKED",
        artisan_id=artisan_id,
        remote_artisan_id=payload.remoteArtisanId,
        matched_by=payload.matchedBy,
    )
    return _signed(
        settings, request, {"linked": True, "cardCode": await _card_code(artisan_id, settings)}
    )


@router.put("/peer/artisans/{artisan_id}/links/{remote_artisan_id}/card")
async def peer_update_card(
    artisan_id: str,
    remote_artisan_id: str,
    payload: PeerCardUpdate,
    request: Request,
    settings: Any = Depends(require_peer),
) -> Response:
    """The designer portal reissued its card: its new code replaces the one on the link."""
    link = await db.crossportalartisanlink.find_first(
        where={"artisanId": artisan_id, "remoteArtisanId": remote_artisan_id}
    )
    if link is None:
        await _log_access(
            request, ACTION_CARD_UPDATE, "NO_MATCH", remote_artisan_id=remote_artisan_id
        )
        return _signed(settings, request, {"updated": False}, status_code=404)
    await _hold_remote_card_code(link.id, payload.remoteCardCode)
    await db.crossportalartisanlink.update(
        where={"id": link.id}, data={"remoteCardCode": payload.remoteCardCode}
    )
    await _log_access(
        request,
        ACTION_CARD_UPDATE,
        "UPDATED",
        artisan_id=artisan_id,
        remote_artisan_id=remote_artisan_id,
        matched_by=link.matchedBy,
    )
    return _signed(settings, request, {"updated": True})


@router.post("/peer/cards/resolve")
async def peer_resolve_card(
    payload: PeerCardResolve, request: Request, settings: Any = Depends(require_peer)
) -> Response:
    """The artisan behind one of THIS portal's cards, if their sharing consent is recorded here.

    Answers only for a live card with a valid tag (when one is sent), an artisan who is not
    REJECTED, and a granted consent. Every other case is the same ``match: null``.
    """
    match = None
    card = None
    if artisan_card.active(settings):
        parsed = artisan_card.ParsedCard(
            artisan_card.issuer_of(settings),
            payload.cardCode,
            payload.keyVersion,
            payload.tag,
            payload.tag is None,
        )
        if payload.tag is None or artisan_card.verify(settings, parsed):
            card = await db.artisancard.find_unique(where={"code": payload.cardCode})
    if card is not None and card.revokedAt is None:
        row = await db.artisan.find_unique(where={"id": card.artisanId})
        if row is not None and getattr(row, "status", None) != "REJECTED":
            artisan = await _consenting(row.id)
            if artisan is not None:
                match = {**_answer(artisan, cross_portal.MATCHED_BY_CARD), "cardCode": card.code}
    if match is None:
        await _log_access(request, ACTION_CARD_RESOLVE, "NO_MATCH")
        return _signed(settings, request, {"match": None})
    await _log_access(
        request,
        ACTION_CARD_RESOLVE,
        "MATCH",
        artisan_id=match["artisanId"],
        matched_by=cross_portal.MATCHED_BY_CARD,
    )
    await artisan_card_store.log_event(
        db,
        artisan_card_store.ACTION_PEER_RESOLVE,
        "PEER",
        artisan_id=match["artisanId"],
        card_code=card.code if card else None,
    )
    return _signed(settings, request, {"match": match})


@router.delete("/peer/artisans/{artisan_id}/links/{remote_artisan_id}")
async def peer_unlink(
    artisan_id: str,
    remote_artisan_id: str,
    request: Request,
    settings: Any = Depends(require_peer),
) -> Response:
    await db.crossportalartisanlink.delete_many(
        where={"artisanId": artisan_id, "remoteArtisanId": remote_artisan_id}
    )
    await _log_access(
        request,
        ACTION_UNLINK,
        "UNLINKED",
        artisan_id=artisan_id,
        remote_artisan_id=remote_artisan_id,
    )
    return _signed(settings, request, {"linked": False})


# ──────────────────────────────────────────────────────────────────────────────────────────────
# This portal's own users
# ──────────────────────────────────────────────────────────────────────────────────────────────


@router.get("/status")
async def cross_portal_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Whether the clients should draw the sharing switch at all, and what it shares."""
    return {
        "enabled": cross_portal.active(get_settings()),
        # The consent copy's list, from the one list the lookup answer is built from.
        "sharedFields": cross_portal.consent_fields_phrase(),
    }


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
    before = await db.artisansharingconsent.find_unique(where={"artisanId": artisan_id})
    # THE AUDIT LEDGER, as for any change to the artisan: who granted or withdrew, from what, when.
    # The upsert below keeps only the latest state; this row is what keeps the history.
    await record_revision(
        SimpleNamespace(id=artisan_id, sharingConsent=getattr(before, "granted", None)),
        current_user,
        {"sharingConsent": payload.granted},
        "artisan",
    )
    consent = await db.artisansharingconsent.upsert(
        where={"artisanId": artisan_id},
        data={"create": {"artisanId": artisan_id, **data}, "update": data},
    )
    if not payload.granted:
        await db.crossportalartisanlink.delete_many(where={"artisanId": artisan_id})
    links = await db.crossportalartisanlink.count(where={"artisanId": artisan_id})
    return _consent_payload(consent, links)
