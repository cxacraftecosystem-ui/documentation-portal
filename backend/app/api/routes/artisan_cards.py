"""Artisan cards: issue, reissue, print and resolve (services/artisan_card.py has the code format).

Every route answers 404 while cards are not active on this server (``artisan_card.active``: the
flag and ARTISAN_CARD_KEY), except ``/artisan-cards/status``, which answers ``enabled: false`` so
the clients hide the card without a word.

Reading a card follows reading the artisan: every signed-in account may. Reissuing follows editing
the artisan. Resolving needs a session, logs every attempt, and answers every kind of miss (unknown,
forged, revoked-and-gone, a designer card that is not linked) with one 404 sentence, counting it
towards the account's miss budget.

A designer-portal card (issuer ``D``) resolves here only through the link the designer portal made:
it pushes its card code at link time and at reissue (``CrossPortalArtisanLink.remoteCardCode``).
This server never calls the designer portal.
"""

from __future__ import annotations

import asyncio
import math
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, get_value
from app.services import artisan_card, artisan_card_pdf, cross_portal, naming
from app.services import artisan_card_store as store
from app.services.access import record_edit_privilege
from app.services.records import ALL_MEDIA_URLS, media_url_owners

router = APIRouter(tags=["artisan-cards"])

#: The portal name printed on every card.
PORTAL_NAME = "Field Repository"

#: The most cards one sheet request prints.
SHEET_LIMIT = 200

MISS = "No artisan matches this card."
UNLINKED_DESIGNER_CARD = (
    "This card is from the Design Prototype Workshop and this artisan isn't linked here."
)
BAD_CODE = "Check the code. One character is wrong."

#: A photo larger than this is not fetched for a card at all.
_PHOTO_MAX_BYTES = 15 * 1024 * 1024
#: The most photo bytes one PDF request pulls from storage.
_PHOTO_BUDGET_BYTES = 80 * 1024 * 1024
_PHOTO_CONCURRENCY = 6

_ARTISAN_INCLUDE = {"craft": True, "location": True}


def _active_or_404() -> Any:
    settings = get_settings()
    if not artisan_card.active(settings):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


async def _artisan_or_404(artisan_id: str) -> Any:
    artisan = await db.artisan.find_unique(where={"id": artisan_id}, include=_ARTISAN_INCLUDE)
    if artisan is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return artisan


def _source(value: str | None) -> str:
    value = (value or "").upper()
    return value if value in ("WEB", "ANDROID") else "WEB"


async def _photos(artisan_ids: list[str], viewer: Any) -> dict[str, Any]:
    """The first image of each artisan this viewer may take, as ``{artisanId: MediaFile}``.

    Only images whose uploader the viewer may download from (``media_url_owners``), so a card never
    hands out a photo the media library would not.
    """
    if not artisan_ids:
        return {}
    rows = await db.mediafile.find_many(
        where={"artisanId": {"in": artisan_ids}, "mediaType": "IMAGE"},
        order={"createdAt": "asc"},
    )
    allowed = await media_url_owners(viewer)
    out: dict[str, Any] = {}
    for row in rows:
        if row.artisanId in out:
            continue
        if allowed is not ALL_MEDIA_URLS and row.uploadedById not in (allowed or set()):
            continue
        out[row.artisanId] = row
    return out


async def _photo_bytes(media: dict[str, Any]) -> dict[str, bytes]:
    """Fetch the photos, a few at a time and within a byte budget. A failed fetch prints no photo."""
    from app.services import s3

    budget = _PHOTO_BUDGET_BYTES
    chosen: list[tuple[str, Any]] = []
    for artisan_id, row in media.items():
        size = int(getattr(row, "sizeBytes", 0) or 0)
        if size > _PHOTO_MAX_BYTES or size > budget:
            continue
        budget -= size
        chosen.append((artisan_id, row))
    gate = asyncio.Semaphore(_PHOTO_CONCURRENCY)
    out: dict[str, bytes] = {}

    async def fetch(artisan_id: str, row: Any) -> None:
        async with gate:
            try:
                out[artisan_id] = await asyncio.to_thread(s3.get_object_bytes, row.objectKey)
            except Exception:
                return

    await asyncio.gather(*(fetch(a, r) for a, r in chosen))
    return out


def _face(settings: Any, artisan: Any, card: Any, photo: bytes | None) -> artisan_card_pdf.CardFace:
    codes = store.card_codes(settings, card)
    face = store.face(artisan, None)
    return artisan_card_pdf.CardFace(
        name=face["name"] or "",
        local_name=face["localName"],
        craft=face["craft"],
        cluster=face["cluster"],
        aadhaar_last4=face["aadhaarLast4"],
        token=codes["token"],
        short_code=codes["shortCode"],
        photo=photo,
    )


PORTAL = naming.PORTAL_FIELD_REPOSITORY
def _card_file_name(faces: list[Any]) -> str:
    """One card names its artisan; a sheet is the day's artisan cards (services/naming.py)."""
    if len(faces) == 1:
        face = faces[0]
        return naming.file_name(
            PORTAL, "Artisan", "Artisan-Card", "pdf",
            day=naming.ist_date(), place=face.cluster, craft=face.craft, subject=face.name,
        )
    return naming.file_name(PORTAL, "Artisan", "Artisan-Cards", "pdf", day=naming.ist_date())


def _pdf(content: bytes, filename: str) -> Response:
    return Response(
        content=content,
        media_type="application/pdf",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


# ──────────────────────────────────────────────────────────────────────────────────────────────


@router.get("/artisan-cards/status")
async def card_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    settings = get_settings()
    enabled = artisan_card.active(settings)
    return {"enabled": enabled, "issuer": artisan_card.issuer_of(settings) if enabled else None}


@router.get("/artisans/{artisan_id}/card")
async def get_card(
    artisan_id: str,
    source: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = _active_or_404()
    artisan = await _artisan_or_404(artisan_id)
    card, _ = await store.ensure_card(
        db,
        artisan_id,
        settings=settings,
        user_id=get_value(current_user, "id"),
        source=_source(source),
    )
    photos = await _photos([artisan_id], current_user)
    photo = photos.get(artisan_id)
    return store.card_payload(
        settings,
        card,
        artisan,
        replaced=await store.replaced_count(db, artisan_id),
        photo_media_id=photo.id if photo else None,
    )


class ReissueIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reason: Literal["LOST", "DAMAGED", "OTHER"]
    source: str | None = Field(default=None, max_length=16)


@router.post("/artisans/{artisan_id}/card/reissue")
async def reissue_card(
    artisan_id: str, payload: ReissueIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    settings = _active_or_404()
    artisan = await _artisan_or_404(artisan_id)
    if not await record_edit_privilege(artisan, current_user, "artisan"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only someone who may edit this artisan can replace their card.",
        )
    card = await store.reissue(
        db,
        artisan_id,
        settings=settings,
        reason=payload.reason,
        user_id=get_value(current_user, "id"),
        source=_source(payload.source),
    )
    photos = await _photos([artisan_id], current_user)
    photo = photos.get(artisan_id)
    return store.card_payload(
        settings,
        card,
        artisan,
        replaced=await store.replaced_count(db, artisan_id),
        photo_media_id=photo.id if photo else None,
    )


@router.get("/artisans/{artisan_id}/card.pdf")
async def card_pdf(
    artisan_id: str,
    size: Literal["id1", "a6"] = "id1",
    photo: int = Query(1, ge=0, le=1),
    source: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> Response:
    settings = _active_or_404()
    artisan = await _artisan_or_404(artisan_id)
    user_id = get_value(current_user, "id")
    card, _ = await store.ensure_card(
        db, artisan_id, settings=settings, user_id=user_id, source=_source(source)
    )
    image = None
    if photo:
        image = (await _photo_bytes(await _photos([artisan_id], current_user))).get(artisan_id)
    content = artisan_card_pdf.render(
        [_face(settings, artisan, card, image)], size, portal=PORTAL_NAME
    )
    await store.log_event(
        db,
        store.ACTION_PRINT,
        _source(source),
        user_id=user_id,
        artisan_id=artisan_id,
        card_code=card.code,
    )
    return _pdf(content, _card_file_name([_face(settings, artisan, card, None)]))


class SheetIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    artisanIds: list[str] | None = Field(default=None, max_length=SHEET_LIMIT)
    workshopId: str | None = Field(default=None, max_length=64)
    # The designer portal's source; accepted so one client contract fits both, refused if sent here.
    designWorkshopId: str | None = Field(default=None, max_length=64)
    size: Literal["id1", "a6"] = "id1"
    photo: bool = True
    source: str | None = Field(default=None, max_length=16)


async def _sheet_artisans(payload: SheetIn) -> list[Any]:
    sources = [s for s in (payload.artisanIds, payload.workshopId, payload.designWorkshopId) if s]
    if len(sources) != 1 or payload.designWorkshopId:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Choose the artisans, or one workshop.",
        )
    if payload.artisanIds:
        ids = list(dict.fromkeys(payload.artisanIds))
        rows = await db.artisan.find_many(where={"id": {"in": ids}}, include=_ARTISAN_INCLUDE)
        by_id = {row.id: row for row in rows}
        unknown = [i for i in ids if i not in by_id]
        if unknown:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"{len(unknown)} of these artisans can't be printed by you.",
            )
        return [by_id[i] for i in ids]
    workshop = await db.workshop.find_unique(where={"id": payload.workshopId})
    if workshop is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    rows = await db.artisan.find_many(
        where={
            "OR": [
                {"workshopId": payload.workshopId},
                {"workshops": {"some": {"workshopId": payload.workshopId}}},
            ]
        },
        include=_ARTISAN_INCLUDE,
        order={"name": "asc"},
        take=SHEET_LIMIT + 1,
    )
    if len(rows) > SHEET_LIMIT:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Print at most {SHEET_LIMIT} cards at a time.",
        )
    if not rows:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="This workshop has no artisans yet.",
        )
    return rows


@router.post("/artisan-cards/sheet.pdf")
async def sheet_pdf(payload: SheetIn, current_user: Any = Depends(get_current_user)) -> Response:
    settings = _active_or_404()
    artisans = await _sheet_artisans(payload)
    user_id = get_value(current_user, "id")
    ids = [a.id for a in artisans]
    cards = await store.ensure_cards(db, ids, settings=settings, user_id=user_id)
    images: dict[str, bytes] = {}
    if payload.photo:
        images = await _photo_bytes(await _photos(ids, current_user))
    faces = [_face(settings, a, cards[a.id], images.get(a.id)) for a in artisans]
    content = artisan_card_pdf.render(
        faces, payload.size, portal=PORTAL_NAME, sheet=True, title="Artisan cards"
    )
    await store.log_event(
        db,
        store.ACTION_PRINT,
        _source(payload.source),
        user_id=user_id,
        artisan_id=ids[0] if len(ids) == 1 else None,
        count=len(ids),
    )
    pages = len(ids) if payload.size == "a6" else math.ceil(len(ids) / artisan_card_pdf.PER_SHEET)
    response = _pdf(content, _card_file_name(faces))
    response.headers["X-Card-Pages"] = str(pages)
    return response


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Resolve
# ──────────────────────────────────────────────────────────────────────────────────────────────


class ResolveIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    input: str = Field(min_length=1, max_length=64)
    source: str | None = Field(default=None, max_length=16)
    # The designer portal's consent tick for a REMOTE prefill. Accepted and ignored here: this
    # server never asks the other one.
    consent: bool = False


async def _miss(user_id: str | None, source: str, detail: str = MISS) -> HTTPException:
    await store.log_event(db, store.ACTION_RESOLVE_MISS, source, user_id=user_id)
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=detail)


async def _found(artisan_id: str, via: str, code: str, user_id: str | None, source: str) -> dict:
    artisan = await db.artisan.find_unique(where={"id": artisan_id}, include=_ARTISAN_INCLUDE)
    if artisan is None:
        raise await _miss(user_id, source)
    await store.log_event(
        db, store.ACTION_RESOLVE, source, user_id=user_id, artisan_id=artisan_id, card_code=code
    )
    return {"status": "FOUND", "via": via, "artisan": store.resolved_artisan(artisan)}


@router.post("/artisan-cards/resolve")
async def resolve_card(
    payload: ResolveIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    settings = _active_or_404()
    user_id = get_value(current_user, "id")
    source = _source(payload.source)
    await store.assert_under_miss_limit(db, settings, user_id)
    try:
        parsed = artisan_card.parse(payload.input)
    except artisan_card.CardParseError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=BAD_CODE
        ) from exc
    if parsed.issuer == artisan_card.issuer_of(settings):
        if not artisan_card.verify(settings, parsed):
            raise await _miss(user_id, source)
        card = await db.artisancard.find_unique(where={"code": parsed.code})
        if card is None:
            raise await _miss(user_id, source)
        if card.revokedAt is not None:
            if await db.artisan.find_unique(where={"id": card.artisanId}) is None:
                raise await _miss(user_id, source)
            await store.log_event(
                db,
                store.ACTION_RESOLVE,
                source,
                user_id=user_id,
                artisan_id=card.artisanId,
                card_code=card.code,
            )
            return {"status": "REPLACED", "artisanId": card.artisanId}
        return await _found(card.artisanId, "LOCAL", card.code, user_id, source)
    # The other portal's card: only through the link its server told us about.
    link = None
    if cross_portal.active(settings):
        link = await db.crossportalartisanlink.find_first(where={"remoteCardCode": parsed.code})
    if link is None:
        raise await _miss(user_id, source, UNLINKED_DESIGNER_CARD)
    return await _found(link.artisanId, "LINK", parsed.code, user_id, source)
