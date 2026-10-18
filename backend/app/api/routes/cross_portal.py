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

MERGED RECORDS (services/artisan_merge.py). An artisan merged here keeps answering under its old id:
the refresh, link and unlink routes resolve a merged id to the record it now lives on, and the
refresh answers with the CURRENT id, which is how a merge (or its undo) reaches the designer portal
without this server ever calling it.
"""

import json
from datetime import UTC, datetime, time
from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value
from app.services import aadhaar_fingerprint, artisan_merge, cross_portal
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
ACTION_SCAN = "SCAN"

#: The designer portal's daily duplicate scan: at most this many items per call, and this many
#: calls per UTC day. Its nightly job sends every unlinked artisan holding a key, 100 at a time.
MATCH_BATCH_LIMIT = 100
MATCH_CALLS_PER_DAY = 50


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
    matchedBy: str = Field(pattern="^(PEHCHAN|AADHAAR)$")


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


@router.post("/peer/artisans/match")
async def peer_match(
    payload: PeerMatch, request: Request, settings: Any = Depends(require_peer)
) -> Response:
    """Which of these designer-portal artisans this register holds, by exact key only. IDS ONLY.

    Each item names a designer-portal artisan (``ref``) by its Pehchan number or its Aadhaar
    fingerprint. The answer lists, for the items that match a CONSENTING artisan here whose status
    is not REJECTED, this register's id and which key matched — nothing else about the record. "Not
    on file" and "no consent" stay the same answer, exactly as ``peer_lookup``. Every match is one
    ``CrossPortalAccessLog`` row (``SCAN``/``MATCH``), and each call one summary row, so the day's
    budget (:data:`MATCH_CALLS_PER_DAY`, then 429) is counted from the log itself.
    """
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
    seen: set[tuple[str, str]] = set()
    for row in candidates.values():
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
            await _log_access(
                request,
                ACTION_SCAN,
                "MATCH",
                artisan_id=row.id,
                remote_artisan_id=ref,
                matched_by=matched_by,
            )
    await _log_access(request, ACTION_SCAN, "SUMMARY")
    return _signed(settings, request, {"matches": matches})


async def _linked_record(artisan_id: str, remote_artisan_id: str) -> Any | None:
    """The link the designer portal's record holds here, reached through ``artisan_id`` — the id it
    knows, which a merge here may have retired.

    Found by ``remoteArtisanId`` alone, then accepted when its ``artisanId`` is the path id, the
    record the path id was merged into, or a record whose merge (or undone merge) joins the two."""
    links = await db.crossportalartisanlink.find_many(where={"remoteArtisanId": remote_artisan_id})
    if not links:
        return None
    resolved = await artisan_merge.resolve(artisan_id)
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
    """The identity fields again, for a record the designer portal has LINKED. 404 otherwise.

    Answers with the record's CURRENT id: after a merge (or an undo) here it differs from the path
    id, and the designer portal moves its link to it."""
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
    return _signed(settings, request, {"match": _answer(artisan, link.matchedBy)})


@router.post("/peer/artisans/{artisan_id}/links")
async def peer_link(
    artisan_id: str, payload: PeerLink, request: Request, settings: Any = Depends(require_peer)
) -> Response:
    """Record the designer portal's link. Refused (409) once consent is not granted."""
    artisan_id = await artisan_merge.resolve(artisan_id) or artisan_id
    if await _consenting(artisan_id) is None:
        await _log_access(
            request, ACTION_LINK, "REFUSED", remote_artisan_id=payload.remoteArtisanId
        )
        return _signed(settings, request, {"linked": False}, status_code=409)
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
    await _log_access(
        request,
        ACTION_LINK,
        "LINKED",
        artisan_id=artisan_id,
        remote_artisan_id=payload.remoteArtisanId,
        matched_by=payload.matchedBy,
    )
    return _signed(settings, request, {"linked": True})


@router.delete("/peer/artisans/{artisan_id}/links/{remote_artisan_id}")
async def peer_unlink(
    artisan_id: str,
    remote_artisan_id: str,
    request: Request,
    settings: Any = Depends(require_peer),
) -> Response:
    resolved = await artisan_merge.resolve(artisan_id)
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
