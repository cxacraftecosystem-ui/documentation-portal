from __future__ import annotations

import math
from typing import Any

from app.core.db import db
from app.core.deps import get_value, role_value
from app.services import cross_portal
from app.services.cross_portal_client import SEARCH_TIMEOUT_SECONDS, PeerUnavailable, peer_call
from app.services.data_explorer.filters import NONE, Filter, day_bounds, real

PAGE_SIZE_MAX = 20
STATUS_OK = "ok"
STATUS_OFF = "off"
STATUS_NOT_PERMITTED = "notPermitted"
STATUS_UNAVAILABLE = "unavailable"
STATUS_FILTERED_OUT = "filteredOut"

BROWSE_TO_PEER: dict[str, str] = {
    "artisan": "artisans",
    "product": "products",
    "tool": "tools",
    "workshop": "workshops",
    "craft": "crafts",
    "interview": "interviews",
    "process": "processes",
    "media": "media",
}
LOCAL_ONLY_PARAMS: tuple[tuple[str, str], ...] = (
    ("workshopIds", "workshops"),
    ("designWorkshopIds", "design_workshops"),
    ("mediaTypes", "media_types"),
    ("statuses", "statuses"),
    ("recordedByIds", "recorded_by"),
    ("languages", "languages"),
)


def peer_type(kind: str | None) -> str | None:
    if not kind:
        return None
    key = kind.strip()
    if key in BROWSE_TO_PEER:
        return BROWSE_TO_PEER[key]
    return key if key in cross_portal.SEARCH_TYPES else None


def not_applied(flt: Filter) -> list[str]:
    return [param for param, field in LOCAL_ONLY_PARAMS if getattr(flt, field)]


async def craft_codes(ids: tuple[str, ...]) -> list[str] | None:
    if not ids:
        return []
    rows = await db.craft.find_many(where={"id": {"in": list(ids)}})
    codes = sorted({str(r.craftCode).strip() for r in rows if getattr(r, "craftCode", None)})
    return codes or None


async def remote_artisans(ids: tuple[str, ...]) -> list[str] | None:
    if not ids:
        return []
    links = await db.crossportalartisanlink.find_many(where={"artisanId": {"in": list(ids)}})
    remote = sorted({str(link.remoteArtisanId) for link in links if link.remoteArtisanId})
    return remote or None


def row(
    item: dict[str, Any], portal: str, portal_label: str, kind: str | None
) -> dict[str, Any] | None:
    item_id = str(item.get("id") or "")
    item_type = str(item.get("type") or kind or "")
    if not item_id or item_type not in cross_portal.SEARCH_TYPES:
        return None
    codes = item.get("craftCodes")
    return {
        "portal": portal,
        "portalLabel": portal_label,
        "local": False,
        "type": item_type,
        "id": item_id,
        "title": str(item.get("title") or ""),
        "subtitle": item.get("subtitle") or None,
        "place": item.get("place") or None,
        "recordDate": item.get("recordDate") or None,
        "craftCodes": codes if isinstance(codes, list) else [],
        "href": f"/remote/{item_type}/{item_id}",
        "openUrl": item.get("openUrl") or None,
    }


def envelope(
    portal: str,
    portal_label: str,
    state: str,
    items: list[dict[str, Any]],
    total: int,
    page: int,
    page_size: int,
    skipped: list[str],
) -> dict[str, Any]:
    return {
        "portal": portal,
        "portalLabel": portal_label,
        "status": state,
        "items": items,
        "total": int(total),
        "page": page,
        "pageSize": page_size,
        "pages": max(1, math.ceil(int(total) / page_size)) if page_size else 1,
        "notApplied": skipped,
    }


async def explore(
    settings: Any,
    user: Any,
    flt: Filter,
    *,
    kind: str | None,
    q: str | None,
    page: int,
    page_size: int,
    allowed: bool,
    active: bool,
    portal: str,
    portal_label: str,
) -> dict[str, Any]:
    flt = real(flt) or NONE
    size = max(1, min(PAGE_SIZE_MAX, int(page_size)))
    skipped = not_applied(flt)
    if not active:
        return envelope(portal, portal_label, STATUS_OFF, [], 0, page, size, skipped)
    if not allowed:
        return envelope(portal, portal_label, STATUS_NOT_PERMITTED, [], 0, page, size, skipped)
    codes = await craft_codes(flt.crafts)
    remote = await remote_artisans(flt.artisans)
    if codes is None or remote is None:
        return envelope(portal, portal_label, STATUS_FILTERED_OUT, [], 0, page, size, skipped)
    low, high = day_bounds(flt.date_from, flt.date_to)
    one = peer_type(kind)
    body = {
        "q": (q or "").strip()[:200],
        "type": one,
        "page": page,
        "limit": size,
        "filters": {
            "craftCodes": codes,
            "artisanIds": remote,
            "dateFrom": low.isoformat() if low else None,
            "dateTo": high.isoformat() if high else None,
        },
        "requester": {
            "userId": str(get_value(user, "id") or ""),
            "email": str(get_value(user, "email") or ""),
            "role": role_value(user),
        },
    }
    try:
        code, payload = await peer_call(
            settings,
            "POST",
            "/explore",
            body=body,
            requested_by=str(get_value(user, "id") or ""),
            timeout=SEARCH_TIMEOUT_SECONDS * 2,
            search=True,
        )
    except PeerUnavailable:
        return envelope(portal, portal_label, STATUS_UNAVAILABLE, [], 0, page, size, skipped)
    if code != 200 or not isinstance(payload, dict):
        return envelope(portal, portal_label, STATUS_UNAVAILABLE, [], 0, page, size, skipped)
    items = [
        shaped
        for item in payload.get("items") or []
        if isinstance(item, dict) and (shaped := row(item, portal, portal_label, one)) is not None
    ]
    try:
        total = int(payload.get("total") or len(items))
    except TypeError, ValueError:
        total = len(items)
    return envelope(
        portal, portal_label, STATUS_OK, items, max(total, len(items)), page, size, skipped
    )
