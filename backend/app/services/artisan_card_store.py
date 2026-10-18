"""Issuing, reissuing and reading artisan cards, and the card event log.

Every function takes the Prisma client (or a transaction) as ``client`` so the routes, the backfill
script and the tests share one implementation. One LIVE card per artisan is guaranteed by the
partial unique index ``ArtisanCard_one_live_per_artisan``: two first reads that race both try to
insert, one wins, and the loser reads the winner's card back.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status

from app.services import aadhaar_fingerprint, artisan_card

ACTION_ISSUE = "ISSUE"
ACTION_REISSUE = "REISSUE"
ACTION_PRINT = "PRINT"
ACTION_RESOLVE = "RESOLVE"
ACTION_RESOLVE_MISS = "RESOLVE_MISS"
ACTION_PEER_RESOLVE = "PEER_RESOLVE"

SOURCES = ("WEB", "ANDROID", "PEER")
REASONS = ("LOST", "DAMAGED", "OTHER")

#: How far back the miss budget looks.
MISS_WINDOW = timedelta(minutes=10)

#: Attempts at a fresh random code before giving up (a 50-bit collision is already vanishingly rare).
_CODE_ATTEMPTS = 6


def _get(row: Any, key: str) -> Any:
    if isinstance(row, dict):
        return row.get(key)
    return getattr(row, key, None)


def _is_unique_violation(error: Exception) -> bool:
    name = type(error).__name__
    return name == "UniqueViolationError" or "Unique constraint" in str(error)


async def live_card(client: Any, artisan_id: str) -> Any | None:
    return await client.artisancard.find_first(where={"artisanId": artisan_id, "revokedAt": None})


async def live_cards(client: Any, artisan_ids: Sequence[str]) -> dict[str, Any]:
    if not artisan_ids:
        return {}
    rows = await client.artisancard.find_many(
        where={"artisanId": {"in": list(artisan_ids)}, "revokedAt": None}
    )
    return {row.artisanId: row for row in rows}


async def replaced_count(client: Any, artisan_id: str) -> int:
    return await client.artisancard.count(
        where={"artisanId": artisan_id, "revokedAt": {"not": None}}
    )


async def log_event(
    client: Any,
    action: str,
    source: str,
    *,
    user_id: str | None = None,
    artisan_id: str | None = None,
    card_code: str | None = None,
    count: int = 1,
) -> None:
    await client.artisancardevent.create(
        data={
            "action": action,
            "source": source if source in SOURCES else "WEB",
            "userId": user_id,
            "artisanId": artisan_id,
            "cardCode": card_code,
            "count": max(1, int(count)),
        }
    )


async def _insert(client: Any, artisan_id: str, *, user_id: str | None, key_version: int) -> Any:
    last: Exception | None = None
    for _ in range(_CODE_ATTEMPTS):
        try:
            return await client.artisancard.create(
                data={
                    "artisanId": artisan_id,
                    "code": artisan_card.new_code(),
                    "keyVersion": key_version,
                    "issuedById": user_id,
                }
            )
        except Exception as exc:
            if not _is_unique_violation(exc):
                raise
            last = exc
            # Either another request issued this artisan's card first (the partial index), or the
            # random code collided. The first is answered by reading the winner back.
            existing = await live_card(client, artisan_id)
            if existing is not None:
                raise _AlreadyIssued(existing) from exc
    raise RuntimeError("could not draw an unused artisan card code") from last


class _AlreadyIssued(Exception):
    def __init__(self, card: Any) -> None:
        super().__init__("already issued")
        self.card = card


async def ensure_card(
    client: Any,
    artisan_id: str,
    *,
    settings: Any,
    user_id: str | None,
    source: str = "WEB",
) -> tuple[Any, bool]:
    """The artisan's live card, issuing one (and logging ISSUE) if there is none. ``(card, issued)``."""
    card = await live_card(client, artisan_id)
    if card is not None:
        return card, False
    try:
        card = await _insert(
            client, artisan_id, user_id=user_id, key_version=artisan_card.key_version(settings)
        )
    except _AlreadyIssued as raced:
        return raced.card, False
    await log_event(
        client, ACTION_ISSUE, source, user_id=user_id, artisan_id=artisan_id, card_code=card.code
    )
    return card, True


async def reissue(
    client: Any,
    artisan_id: str,
    *,
    settings: Any,
    reason: str,
    user_id: str | None,
    source: str = "WEB",
) -> Any:
    """Revoke the live card (if any) and issue a new one. Logs REISSUE."""
    now = datetime.now(UTC)
    await client.artisancard.update_many(
        where={"artisanId": artisan_id, "revokedAt": None},
        data={
            "revokedAt": now,
            "revokedById": user_id,
            "revokedReason": reason if reason in REASONS else "OTHER",
        },
    )
    try:
        card = await _insert(
            client, artisan_id, user_id=user_id, key_version=artisan_card.key_version(settings)
        )
    except _AlreadyIssued as raced:
        card = raced.card
    await log_event(
        client, ACTION_REISSUE, source, user_id=user_id, artisan_id=artisan_id, card_code=card.code
    )
    return card


async def ensure_cards(
    client: Any, artisan_ids: Iterable[str], *, settings: Any, user_id: str | None
) -> dict[str, Any]:
    """Live cards for every id, issuing the missing ones together in one transaction.

    The backend's only bulk issue path (sheet.pdf and the backfill). If a concurrent first read
    issues one of the same artisans mid-way, the transaction rolls back and the missing set is
    read again once; the partial unique index is what makes that safe.
    """
    ids = list(dict.fromkeys(artisan_ids))
    found = await live_cards(client, ids)
    for _attempt in range(2):
        missing = [i for i in ids if i not in found]
        if not missing:
            return found
        version = artisan_card.key_version(settings)
        try:
            created: list[Any] = []
            async with client.tx() as tx:
                for artisan_id in missing:
                    created.append(
                        await tx.artisancard.create(
                            data={
                                "artisanId": artisan_id,
                                "code": artisan_card.new_code(),
                                "keyVersion": version,
                                "issuedById": user_id,
                            }
                        )
                    )
        except Exception as exc:
            if not _is_unique_violation(exc):
                raise
            found = await live_cards(client, ids)
            continue
        for card in created:
            found[card.artisanId] = card
        await client.artisancardevent.create_many(
            data=[
                {
                    "action": ACTION_ISSUE,
                    "source": "WEB",
                    "userId": user_id,
                    "artisanId": card.artisanId,
                    "cardCode": card.code,
                    "count": 1,
                }
                for card in created
            ]
        )
    missing = [i for i in ids if i not in found]
    for artisan_id in missing:
        found[artisan_id], _ = await ensure_card(
            client, artisan_id, settings=settings, user_id=user_id
        )
    return found


async def assert_under_miss_limit(client: Any, settings: Any, user_id: str | None) -> None:
    """429 once this account has missed ARTISAN_CARD_RESOLVE_MISS_LIMIT times in ten minutes."""
    if not user_id:
        return
    since = datetime.now(UTC) - MISS_WINDOW
    misses = await client.artisancardevent.count(
        where={"userId": user_id, "action": ACTION_RESOLVE_MISS, "createdAt": {"gte": since}}
    )
    limit = int(getattr(settings, "artisan_card_resolve_miss_limit", 20) or 20)
    if misses >= limit:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many unmatched cards. Try again in a few minutes.",
        )


# ──────────────────────────────────────────────────────────────────────────────────────────────
# What a card says
# ──────────────────────────────────────────────────────────────────────────────────────────────


def cluster_of(artisan: Any) -> str | None:
    """The card's cluster line: the stated village, then district, then the typed place."""
    location = _get(artisan, "location")
    for value in (_get(location, "village"), _get(location, "district"), _get(artisan, "place")):
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def craft_of(artisan: Any) -> str | None:
    craft = _get(artisan, "craft")
    name = _get(craft, "name")
    return name.strip() if isinstance(name, str) and name.strip() else None


def card_codes(settings: Any, card: Any) -> dict[str, str]:
    issuer = artisan_card.issuer_of(settings)
    return {
        "code": card.code,
        "shortCode": artisan_card.short_code(issuer, card.code),
        "token": artisan_card.token_for(settings, card.code),
    }


def face(artisan: Any, photo_media_id: str | None) -> dict[str, Any]:
    local = _get(artisan, "localName")
    return {
        "name": _get(artisan, "name"),
        "localName": local if isinstance(local, str) and local.strip() else None,
        "craft": craft_of(artisan),
        "cluster": cluster_of(artisan),
        "aadhaarLast4": aadhaar_fingerprint.stored_last4(artisan),
        "photoMediaId": photo_media_id,
    }


def qr_svg(token: str) -> str:
    """The QR as inline SVG, the same symbol the PDF draws (alphanumeric, level Q, version 3)."""
    import segno

    symbol = segno.make(token, error="q", mode="alphanumeric", micro=False, boost_error=False)
    return symbol.svg_inline(scale=4, border=2, dark="#000", light="#fff")


def card_payload(
    settings: Any, card: Any, artisan: Any, *, replaced: int, photo_media_id: str | None
) -> dict[str, Any]:
    codes = card_codes(settings, card)
    return {
        "artisanId": artisan.id,
        **codes,
        "issuedAt": card.issuedAt,
        "replacedCards": replaced,
        "face": face(artisan, photo_media_id),
        "qrSvg": qr_svg(codes["token"]),
    }


def resolved_artisan(artisan: Any) -> dict[str, Any]:
    """The artisan as a resolve answer shows them: enough to confirm the right person, no more."""
    return {
        "id": artisan.id,
        "name": _get(artisan, "name"),
        "craft": craft_of(artisan),
        "cluster": cluster_of(artisan),
        "place": _get(artisan, "place"),
        "aadhaarLast4": aadhaar_fingerprint.stored_last4(artisan),
    }
