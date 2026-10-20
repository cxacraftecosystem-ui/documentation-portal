"""The duplicate-artisan finder: the form's check and the admin review queue.

* ``POST /artisan-duplicates/check`` — any record creator, 30 a minute: who already looks like the
  artisan being typed. At most five, none below 0.60, birth year rather than birth date, and no
  identifier value. An ``AADHAAR`` reason only when the caller typed that number.
* ``GET /artisan-duplicates`` and ``GET /artisan-duplicates/{id}`` — admins: the queue and one pair.
* ``POST .../{id}/dismiss`` and ``.../reopen`` — admins: "different people", and back again.

A pair is MERGED through the one artisan merge (``routes/artisan_merge``, ``/admin/artisan-merge``):
the merge deletes the merged record, and its pairs go with it (``onDelete: Cascade``). Undo lives
there too. Its own prefix, so nothing here is shadowed by ``/artisans/{artisan_id}``. Admin screens
are web-only. The hold at save time lives in ``routes/artisans.create_artisan``. Cross-portal pairs
are the designer portal's: this portal only answers its exact-key match (``routes/cross_portal``).
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import get_value, require_content_admin, require_record_creator
from app.scale.rate_limit import _TokenBuckets
from app.schemas.duplicates import DismissIn, DuplicateCheckIn
from app.services import aadhaar_fingerprint, artisan_duplicates
from app.services.artisan_merge import unjson
from app.services.artisan_identity import aadhaar_error, normalize_aadhaar, normalize_pehchan
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import public_encode

router = APIRouter(prefix="/artisan-duplicates", tags=["artisan-duplicates"])

CHECKS_PER_MINUTE = 30
_check_buckets = _TokenBuckets(capacity=CHECKS_PER_MINUTE, refill_per_second=CHECKS_PER_MINUTE / 60.0)


def reset_check_budget() -> None:
    """Forget every caller's /check budget (tests)."""
    global _check_buckets
    _check_buckets = _TokenBuckets(capacity=CHECKS_PER_MINUTE, refill_per_second=CHECKS_PER_MINUTE / 60.0)


def _enum(value: Any) -> str:
    return str(getattr(value, "value", value))


# ── The form's check ──────────────────────────────────────────────────────────────────────────


async def _craft_for(craft_id: str | None, craft_name: str | None) -> tuple[str | None, str | None]:
    """(craftId, craftCode) for what the form names, so "same craft" can be asked."""
    craft = None
    if craft_id:
        craft = await db.craft.find_unique(where={"id": craft_id})
    if craft is None and craft_name and craft_name.strip():
        craft = await db.craft.find_unique(where={"name": craft_name.strip()})
    if craft is None:
        return craft_id, None
    return craft.id, getattr(craft, "craftCode", None)


def typed_fingerprint(raw: str | None) -> str | None:
    """The fingerprint of a typed Aadhaar, or None. The number itself is never kept."""
    number = normalize_aadhaar(raw) if raw else None
    if not number or aadhaar_error(number):
        return None
    try:
        return aadhaar_fingerprint.fingerprint_of(number)
    except aadhaar_fingerprint.FingerprintKeyMissing:
        return None


async def profile_from_form(
    *,
    name: str,
    localName: str | None = None,
    gender: str | None = None,
    dateOfBirth: Any = None,
    phone: str | None = None,
    place: str | None = None,
    state: str | None = None,
    district: str | None = None,
    village: str | None = None,
    craftId: str | None = None,
    craftName: str | None = None,
    fingerprint: str | None = None,
    pehchanCardNumber: str | None = None,
) -> artisan_duplicates.Profile:
    craft_id, craft_code = await _craft_for(craftId, craftName)
    return artisan_duplicates.profile_from_dict(
        {
            "name": name,
            "localName": localName,
            "gender": gender,
            "dateOfBirth": dateOfBirth,
            "phone": phone,
            "place": place,
            "state": state,
            "district": district,
            "village": village,
            "craftId": craft_id,
            "craftCode": craft_code,
            "aadhaarFingerprint": fingerprint,
            "pehchanCardNumber": normalize_pehchan(pehchanCardNumber) if pehchanCardNumber else None,
        }
    )


@router.post("/check")
async def check_duplicates(
    payload: DuplicateCheckIn, current_user: Any = Depends(require_record_creator)
) -> dict[str, Any]:
    allowed, retry_after = _check_buckets.take(f"user:{get_value(current_user, 'id')}")
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many checks in a minute. Try again shortly.",
            headers={"Retry-After": str(max(1, int(retry_after + 0.999)))},
        )
    fingerprint = typed_fingerprint(payload.aadhaar)
    profile = await profile_from_form(
        name=payload.name,
        localName=payload.localName,
        gender=payload.gender,
        dateOfBirth=payload.dateOfBirth,
        phone=payload.phone,
        place=payload.place,
        state=payload.state,
        district=payload.district,
        village=payload.village,
        craftId=payload.craftId,
        craftName=payload.craftName,
        fingerprint=fingerprint,
        pehchanCardNumber=payload.pehchanCardNumber,
    )
    # Narrowed by records.viewable_where, the same read rule as the artisan list.
    found = await artisan_duplicates.candidates_for_profile(
        profile,
        exclude_id=payload.excludeArtisanId,
        limit=artisan_duplicates.CHECK_MAX_CANDIDATES,
        viewer=current_user,
    )
    return {
        "candidates": [
            artisan_duplicates.candidate_payload(other, result, typed_aadhaar=bool(fingerprint))
            for other, result in found
        ]
    }


# ── The queue ─────────────────────────────────────────────────────────────────────────────────


def _artisan_brief(artisan: Any) -> dict[str, Any] | None:
    if artisan is None:
        return None
    location = getattr(artisan, "location", None)
    craft = getattr(artisan, "craft", None)
    return {
        "id": artisan.id,
        "name": artisan.name,
        "place": artisan.place,
        "district": getattr(location, "district", None),
        "craft": getattr(craft, "name", None),
        "createdAt": artisan.createdAt,
    }


def _pair_item(pair: Any) -> dict[str, Any]:
    kind = _enum(pair.kind)
    return {
        "id": pair.id,
        "kind": kind,
        "score": pair.score,
        "band": pair.band,
        "reasons": unjson(pair.reasons) or [],
        "status": _enum(pair.status),
        "source": _enum(pair.source),
        "creatorSaidDifferent": pair.creatorSaidDifferent,
        "a": _artisan_brief(getattr(pair, "artisanA", None)),
        "b": _artisan_brief(getattr(pair, "artisanB", None)),
        "remote": (
            {"artisanId": pair.remoteArtisanId, "matchedBy": pair.remoteMatchedBy}
            if kind == "CROSS_PORTAL"
            else None
        ),
        "createdAt": pair.createdAt,
    }


_ARTISAN_INCLUDE = {"include": {"craft": True, "location": True}}


@router.get("")
async def list_pairs(
    _: Any = Depends(require_content_admin),
    status_filter: str | None = Query("OPEN", alias="status"),
    kind: str | None = None,
    band: str | None = None,
    artisanId: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    clauses: list[str] = []
    params: list[Any] = []

    def param(value: Any) -> str:
        params.append(value)
        return f"${len(params)}"

    if status_filter:
        if status_filter not in ("OPEN", "NOT_DUPLICATE", "LINKED"):
            raise HTTPException(status_code=422, detail="Unknown status")
        clauses.append(f'"status"::text = {param(status_filter)}')
    if kind:
        if kind not in ("WITHIN", "CROSS_PORTAL"):
            raise HTTPException(status_code=422, detail="Unknown kind")
        clauses.append(f'"kind"::text = {param(kind)}')
    if artisanId:
        marker = param(artisanId)
        clauses.append(f'("artisanAId" = {marker} OR "artisanBId" = {marker})')
    if band and band not in artisan_duplicates.BANDS:
        raise HTTPException(status_code=422, detail="Unknown band")
    base_where = " AND ".join(clauses) or "TRUE"
    count_rows = await db.query_raw(
        f'SELECT "band", count(*)::int AS "n" FROM "ArtisanDuplicateCandidate" WHERE {base_where} GROUP BY "band"',
        *params,
    )
    counts = {b: 0 for b in artisan_duplicates.BANDS}
    for row in count_rows:
        if row["band"] in counts:
            counts[row["band"]] = int(row["n"])
    where = base_where
    if band:
        where += f' AND "band" = {param(band)}'
    total = counts[band] if band else sum(counts.values())
    # Band first, and within a band a pair the creator already called different people goes last.
    id_rows = await db.query_raw(
        f"""SELECT "id" FROM "ArtisanDuplicateCandidate" WHERE {where}
            ORDER BY CASE "band" WHEN 'VERY_LIKELY' THEN 0 WHEN 'LIKELY' THEN 1 ELSE 2 END,
                     "creatorSaidDifferent" ASC, "score" DESC, "createdAt" DESC, "id" ASC
            LIMIT {int(page_size)} OFFSET {int(skip)}""",
        *params,
    )
    ids = [row["id"] for row in id_rows]
    rows = (
        await db.artisanduplicatecandidate.find_many(
            where={"id": {"in": ids}},
            include={"artisanA": _ARTISAN_INCLUDE, "artisanB": _ARTISAN_INCLUDE},
        )
        if ids
        else []
    )
    by_id = {r.id: r for r in rows}
    ordered_rows = [by_id[i] for i in ids if i in by_id]
    payload = page_payload(public_encode([_pair_item(r) for r in ordered_rows]), total, page, page_size)
    payload["counts"] = counts
    return payload


async def _load_pair(pair_id: str) -> Any:
    pair = await db.artisanduplicatecandidate.find_unique(where={"id": pair_id})
    if pair is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pair not found")
    return pair


@router.get("/{pair_id}")
async def get_pair(pair_id: str, _: Any = Depends(require_content_admin)) -> dict[str, Any]:
    pair = await db.artisanduplicatecandidate.find_unique(
        where={"id": pair_id}, include={"artisanA": _ARTISAN_INCLUDE, "artisanB": _ARTISAN_INCLUDE}
    )
    if pair is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pair not found")
    return public_encode(_pair_item(pair))


async def _decide(pair_id: str, status_value: str, user: Any, note: str | None) -> dict[str, Any]:
    pair = await _load_pair(pair_id)
    data: dict[str, Any] = {"status": status_value}
    if status_value == "OPEN":
        data.update({"decidedById": None, "decidedAt": None, "decisionNote": None})
    else:
        data.update(
            {"decidedById": get_value(user, "id"), "decidedAt": datetime.now(UTC), "decisionNote": note}
        )
    updated = await db.artisanduplicatecandidate.update(
        where={"id": pair.id},
        data=data,
        include={"artisanA": _ARTISAN_INCLUDE, "artisanB": _ARTISAN_INCLUDE},
    )
    return public_encode(_pair_item(updated))


@router.post("/{pair_id}/dismiss")
async def dismiss_pair(
    pair_id: str, payload: DismissIn, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    return await _decide(pair_id, "NOT_DUPLICATE", current_user, payload.note)


@router.post("/{pair_id}/reopen")
async def reopen_pair(pair_id: str, current_user: Any = Depends(require_content_admin)) -> dict[str, Any]:
    return await _decide(pair_id, "OPEN", current_user, None)
