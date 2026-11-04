import logging
from datetime import UTC, datetime, time
from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value
from app.services import (
    aadhaar_fingerprint,
    artisan_card,
    artisan_card_store,
    artisan_merge,
    coverage,
    cross_portal,
    cross_portal_peer,
)
from app.services.access import record_edit_privilege, record_revision
from app.services.artisan_identity import normalize_pehchan

router = APIRouter(prefix="/cross-portal", tags=["cross-portal"])
logger = logging.getLogger("app.cross_portal")


def _active_or_404() -> Any:
    settings = get_settings()
    if not cross_portal.active(settings):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


require_peer = cross_portal_peer.require_peer
_signed = cross_portal_peer.signed


ACTION_LOOKUP = "LOOKUP"
ACTION_REFRESH = "REFRESH"
ACTION_LINK = "LINK"
ACTION_UNLINK = "UNLINK"
ACTION_COVERAGE = "COVERAGE"
ACTION_SCAN = "SCAN"

MATCH_BATCH_LIMIT = 100
MATCH_CALLS_PER_DAY = 50
ACTION_CARD_RESOLVE = "CARD_RESOLVE"
ACTION_CARD_UPDATE = "CARD_UPDATE"

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
    await db.crossportalaccesslog.create(
        data=_access_row(
            request, action, outcome, artisan_id=artisan_id, remote_artisan_id=remote_artisan_id, matched_by=matched_by
        )
    )


def _access_row(
    request: Request,
    action: str,
    outcome: str,
    *,
    artisan_id: str | None = None,
    remote_artisan_id: str | None = None,
    matched_by: str | None = None,
) -> dict[str, Any]:
    asked_by = (request.headers.get(cross_portal.REQUESTED_BY_HEADER) or "").strip()[:64] or None
    return {
        "action": action,
        "outcome": outcome,
        "artisanId": artisan_id,
        "remoteUserId": asked_by,
        "remoteArtisanId": (remote_artisan_id or "")[:64] or None,
        "matchedBy": matched_by,
    }


class PeerLookup(BaseModel):
    model_config = ConfigDict(extra="forbid")

    pehchanCardNumber: str | None = Field(default=None, max_length=40)
    aadhaarCode: str | None = Field(default=None, min_length=64, max_length=64)


class PeerLink(BaseModel):
    model_config = ConfigDict(extra="forbid")

    remoteArtisanId: str = Field(min_length=1, max_length=64)
    matchedBy: str = Field(pattern="^(PEHCHAN|AADHAAR|CARD)$")
    remoteCardCode: str | None = Field(default=None, pattern=CARD_CODE_PATTERN)


class PeerCardResolve(BaseModel):
    model_config = ConfigDict(extra="forbid")

    cardCode: str = Field(pattern=CARD_CODE_PATTERN)
    tag: str | None = Field(default=None, pattern="^[0-9A-HJKMNP-TV-Z]{16}$")
    keyVersion: int | None = Field(default=None, ge=1, le=9)


class PeerCardUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    remoteCardCode: str = Field(pattern=CARD_CODE_PATTERN)


class PeerMatchItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ref: str = Field(min_length=1, max_length=64)
    pehchanCardNumber: str | None = Field(default=None, max_length=40)
    aadhaarCode: str | None = Field(default=None, min_length=64, max_length=64)


class PeerMatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[PeerMatchItem] = Field(max_length=MATCH_BATCH_LIMIT)


class ConsentIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    granted: bool
    note: str | None = Field(default=None, max_length=500)


async def _consenting(artisan_id: str) -> Any | None:
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
    if not artisan_card.active(settings):
        return None
    try:
        card, _ = await artisan_card_store.ensure_card(
            db, artisan_id, settings=settings, user_id=None, source="PEER"
        )
    except Exception:
        logger.warning("cross-portal: card code left off this answer", exc_info=True)
        return None
    return card.code


async def _hold_remote_card_code(link_id: str | None, code: str | None) -> None:
    if not code:
        return
    where: dict[str, Any] = {"remoteCardCode": code}
    if link_id:
        where["NOT"] = {"id": link_id}
    await db.crossportalartisanlink.update_many(where=where, data={"remoteCardCode": None})


@router.post("/peer/artisans/lookup")
async def peer_lookup(
    payload: PeerLookup, request: Request, settings: Any = Depends(require_peer)
) -> Response:
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
    if payload.aadhaarCode:
        consents = await db.artisansharingconsent.find_many(where={"granted": True})
        ids = [c.artisanId for c in consents]
        rows = await db.artisan.find_many(where={"id": {"in": ids}}) if ids else []
        key = cross_portal.aadhaar_key(settings)
        for row in rows:
            code = cross_portal.stored_aadhaar_code(row, key)
            if code and cross_portal.codes_match(code, payload.aadhaarCode) and (
                getattr(row, "status", None) != "REJECTED"
            ):
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


@router.post("/peer/artisans/match")
async def peer_match(
    payload: PeerMatch, request: Request, settings: Any = Depends(require_peer)
) -> Response:
    day_start = datetime.combine(datetime.now(UTC).date(), time.min, tzinfo=UTC)
    calls_today = await db.crossportalaccesslog.count(
        where={"action": ACTION_SCAN, "outcome": "SUMMARY", "createdAt": {"gte": day_start}}
    )
    if calls_today >= MATCH_CALLS_PER_DAY:
        return _signed(settings, request, {"matches": [], "limited": True}, status_code=429)
    pehchans: dict[str, list[str]] = {}
    codes: dict[str, list[str]] = {}
    for item in payload.items:
        pehchan = normalize_pehchan(item.pehchanCardNumber)
        if pehchan:
            pehchans.setdefault(pehchan, []).append(item.ref)
        code = (item.aadhaarCode or "").strip().lower()
        if aadhaar_fingerprint.is_fingerprint(code):
            codes.setdefault(code, []).append(item.ref)
    candidates: dict[str, Any] = {}
    if pehchans:
        for row in await db.artisan.find_many(where={"pehchanCardNumber": {"in": list(pehchans)}}):
            candidates[row.id] = row
    if codes:
        for row in await db.artisan.find_many(
            where={aadhaar_fingerprint.FINGERPRINT_FIELD: {"in": list(codes)}}
        ):
            candidates[row.id] = row
    consenting: set[str] = set()
    if candidates:
        consents = await db.artisansharingconsent.find_many(
            where={"artisanId": {"in": list(candidates)}, "granted": True}
        )
        consenting = {c.artisanId for c in consents}
    matches: list[dict[str, Any]] = []
    logged: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for row in sorted(candidates.values(), key=lambda candidate: candidate.id):
        if row.id not in consenting or getattr(row, "status", None) == "REJECTED":
            continue
        hits: list[tuple[str, str]] = []
        for ref in pehchans.get(getattr(row, "pehchanCardNumber", None) or "", []):
            hits.append((ref, cross_portal.MATCHED_BY_PEHCHAN))
        fingerprint = getattr(row, aadhaar_fingerprint.FINGERPRINT_FIELD, None) or ""
        for ref in codes.get(fingerprint, []):
            hits.append((ref, cross_portal.MATCHED_BY_AADHAAR))
        for ref, matched_by in hits:
            if (ref, row.id) in seen:
                continue
            seen.add((ref, row.id))
            matches.append({"ref": ref, "artisanId": row.id, "matchedBy": matched_by})
            logged.append(
                _access_row(
                    request,
                    ACTION_SCAN,
                    "MATCH",
                    artisan_id=row.id,
                    remote_artisan_id=ref,
                    matched_by=matched_by,
                )
            )
    if logged:
        await db.crossportalaccesslog.create_many(data=logged)
    await _log_access(request, ACTION_SCAN, "SUMMARY")
    return _signed(settings, request, {"matches": matches})


async def _linked_record(artisan_id: str, remote_artisan_id: str) -> Any | None:
    links = await db.crossportalartisanlink.find_many(where={"remoteArtisanId": remote_artisan_id})
    if not links:
        return None
    resolved = await artisan_merge.survivor_of(artisan_id)
    for link in links:
        if link.artisanId in (artisan_id, resolved):
            return link
    joins = await db.artisanmerge.find_many(
        where={
            "OR": [
                {"survivorId": artisan_id, "mergedId": {"in": [link.artisanId for link in links]}},
                {"mergedId": artisan_id, "survivorId": {"in": [link.artisanId for link in links]}},
            ]
        }
    )
    joined = {j.mergedId for j in joins} | {j.survivorId for j in joins}
    for link in links:
        if link.artisanId in joined:
            return link
    return None


@router.get("/peer/artisans/{artisan_id}")
async def peer_refresh(
    artisan_id: str,
    remoteArtisanId: str,
    request: Request,
    settings: Any = Depends(require_peer),
) -> Response:
    link = await _linked_record(artisan_id, remoteArtisanId)
    artisan = await _consenting(link.artisanId) if link is not None else None
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
    artisan_id = await artisan_merge.survivor_of(artisan_id) or artisan_id
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
    resolved = await artisan_merge.survivor_of(artisan_id)
    await db.crossportalartisanlink.delete_many(
        where={
            "artisanId": {"in": [i for i in (artisan_id, resolved) if i]},
            "remoteArtisanId": remote_artisan_id,
        }
    )
    await _log_access(
        request,
        ACTION_UNLINK,
        "UNLINKED",
        artisan_id=artisan_id,
        remote_artisan_id=remote_artisan_id,
    )
    return _signed(settings, request, {"linked": False})


async def require_coverage_peer(
    request: Request,
    authorization: str | None = Header(default=None),
    x_cross_portal_nonce: str | None = Header(default=None),
) -> Any:
    settings = get_settings()
    if not coverage.peer_coverage_active(settings):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    if not cross_portal.bearer_matches(settings.cross_portal_token, authorization):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authorised.")
    nonce = (x_cross_portal_nonce or "").strip()
    if len(nonce) < cross_portal.MIN_NONCE_LENGTH or len(nonce) > 128:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authorised.")
    request.state.cross_portal_nonce = nonce
    return settings


@router.get("/peer/coverage")
async def peer_coverage(
    request: Request,
    craftCode: list[str] | None = Query(None),
    fy: list[str] | None = Query(None),
    scheme: list[str] | None = Query(None),
    settings: Any = Depends(require_coverage_peer),
) -> Response:
    del scheme
    filters = coverage.Filters(
        craft_codes=tuple(coverage.resolve_craft_codes(craftCode)),
        fys=tuple(coverage.resolve_fys(fy)),
    )
    fold = await coverage.fold_for(await coverage.scope_wheres(coverage.PEER_PRINCIPAL))
    await _log_access(request, ACTION_COVERAGE, "OK")
    return _signed(settings, request, coverage.peer_payload(fold, filters))



@router.get("/status")
async def cross_portal_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return {
        "enabled": cross_portal.active(get_settings()),
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
