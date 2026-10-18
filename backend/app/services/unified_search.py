"""One search across both portals: the rules ``GET /search/unified`` and the peer routes share.

* :func:`account_scope` turns an account into the permission half of a ``search_query.Scope``. On
  the Field Repository every signed-in account reads every record (``records.viewable_where``), so
  the scope is every local type; clusters, design workshops and reports exist only on the designer
  portal and are asked of it, never of this index.
* :func:`ask_peer` asks the designer portal over the signed channel and never raises: a slow,
  missing or untrusted answer is ``remote.status = "unavailable"`` and this portal's results still
  return.
* :func:`drop_unconsented` removes designer items that name one of this portal's artisans whose
  sharing consent is not granted, the third consent check (docs/SEARCH.md §6).
* :func:`merge` interleaves local and remote items per group by reciprocal rank (peer items carry no
  score, so both sides are scored by their position), local first on a tie.

docs/SEARCH.md carries the contract.
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.db import db
from app.core.deps import get_value, role_value
from app.services import cross_portal
from app.services.cross_portal_client import (
    SEARCH_TIMEOUT_SECONDS,
    PeerUnavailable,
    peer_call,
)
from app.services.search_query import RRF_K, Scope

logger = logging.getLogger(__name__)

PORTAL = "fieldRepository"
PORTAL_LABEL = "Field Repository"
PEER_PORTAL = "designer"
PEER_LABEL = "Designer portal"

#: Types this portal indexes, in vocabulary order. The rest of the vocabulary is the designer's.
LOCAL_TYPES: tuple[str, ...] = tuple(
    t for t in cross_portal.SEARCH_TYPES if t not in {"clusters", "designWorkshops", "reports"}
)

LABELS: dict[str, str] = {
    "artisans": "Artisans",
    "crafts": "Crafts",
    "clusters": "Clusters",
    "workshops": "Workshops",
    "designWorkshops": "Design workshops",
    "interviews": "Interviews",
    "transcripts": "Transcripts",
    "reports": "Reports",
    "products": "Products",
    "tools": "Tools",
    "processes": "Processes",
    "media": "Media",
}

STATUS_OK = "ok"
STATUS_OFF = "off"
STATUS_NOT_PERMITTED = "notPermitted"
STATUS_UNAVAILABLE = "unavailable"
STATUS_FILTERED_OUT = "filteredOut"


def parse_types(raw: list[str] | None) -> list[str] | None:
    """Repeatable or comma-separated names, in vocabulary order. ``None`` means every type.
    Unknown names raise ``ValueError`` naming them."""
    if not raw:
        return None
    asked = {part.strip() for value in raw for part in str(value).split(",") if part.strip()}
    unknown = sorted(asked - set(cross_portal.SEARCH_TYPES))
    if unknown:
        raise ValueError(", ".join(unknown))
    return [t for t in cross_portal.SEARCH_TYPES if t in asked]


async def account_scope(user: Any | None, types: list[str] | None) -> tuple[Scope, list[str]]:
    """The permission scope for one account (``None`` is the floor) and the asked types it may not
    read at all. Every account, and the floor, reads every ``ALL`` document here, and every
    document here is ``ALL``; nothing is refused locally."""
    wanted = [t for t in (types or list(LOCAL_TYPES)) if t in LOCAL_TYPES]
    return Scope(types=wanted), []


def may_search_across(user: Any) -> bool:
    return role_value(user) in cross_portal.CROSS_PORTAL_SEARCH_ROLES


async def ask_peer(
    settings: Any,
    user: Any,
    *,
    q: str,
    types: list[str] | None,
    limit: int,
    one_type: str | None,
    page: int,
    filters: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    """``(status, peer body)``. Never raises."""
    body = {
        "q": q,
        "types": types or list(cross_portal.SEARCH_TYPES),
        "limit": limit,
        "type": one_type,
        "page": page,
        "filters": filters,
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
            "/search",
            body=body,
            requested_by=str(get_value(user, "id") or ""),
            timeout=SEARCH_TIMEOUT_SECONDS,
            search=True,
        )
    except PeerUnavailable:
        return STATUS_UNAVAILABLE, {}
    if code != 200 or not isinstance(payload, dict):
        return STATUS_UNAVAILABLE, {}
    return STATUS_OK, payload


async def drop_unconsented(groups: list[Any]) -> list[dict[str, Any]]:
    """The designer's groups without any item naming one of OUR artisans (``linkedArtisanIds``)
    whose sharing consent is not granted here. A group's total drops by what was removed."""
    clean = [g for g in groups or [] if isinstance(g, dict)]
    named: set[str] = set()
    for group in clean:
        for item in group.get("items") or []:
            if isinstance(item, dict) and isinstance(item.get("linkedArtisanIds"), list):
                named.update(str(a) for a in item["linkedArtisanIds"] if a)
    granted: set[str] = set()
    if named:
        rows = await db.artisansharingconsent.find_many(
            where={"artisanId": {"in": sorted(named)}, "granted": True}
        )
        granted = {row.artisanId for row in rows}
    out: list[dict[str, Any]] = []
    for group in clean:
        kept, dropped = [], 0
        for item in group.get("items") or []:
            if not isinstance(item, dict):
                continue
            linked = item.get("linkedArtisanIds")
            linked = [str(a) for a in linked if a] if isinstance(linked, list) else []
            if any(a not in granted for a in linked):
                dropped += 1
                continue
            kept.append(item)
        total = max(int(group.get("total") or 0) - dropped, len(kept))
        out.append({**group, "items": kept, "total": total})
    return out


def _local_item(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "portal": PORTAL,
        "local": True,
        "type": item["type"],
        "id": item["id"],
        "title": item.get("title") or "",
        "subtitle": item.get("subtitle"),
        "snippet": item.get("snippet") or "",
        "highlights": item.get("highlights") or [],
        "place": item.get("place"),
        "craftCodes": item.get("craftCodes") or [],
        "recordDate": item.get("recordDate"),
        "href": item.get("openPath"),
        "openUrl": None,
    }


def _remote_item(item: dict[str, Any], kind: str) -> dict[str, Any] | None:
    item_id = str(item.get("id") or "")
    if not item_id:
        return None
    out = {key: item.get(key) for key in cross_portal.PEER_ITEM_KEYS}
    out.update(
        {
            "portal": PEER_PORTAL,
            "local": False,
            "type": kind,
            "id": item_id,
            "title": str(item.get("title") or ""),
            "snippet": str(item.get("snippet") or ""),
            "highlights": item.get("highlights")
            if isinstance(item.get("highlights"), list)
            else [],
            "craftCodes": item.get("craftCodes")
            if isinstance(item.get("craftCodes"), list)
            else [],
            "href": f"/remote/{kind}/{item_id}",
        }
    )
    return out


def merge(
    local: dict[str, list[dict[str, Any]]],
    local_totals: dict[str, int],
    remote_groups: list[dict[str, Any]],
    *,
    limit: int,
) -> list[dict[str, Any]]:
    """Groups in vocabulary order, empty ones left out, each holding at most ``limit`` items."""
    remote_by_type: dict[str, dict[str, Any]] = {}
    for group in remote_groups or []:
        if isinstance(group, dict) and group.get("type") in cross_portal.SEARCH_TYPES:
            remote_by_type[str(group["type"])] = group
    groups: list[dict[str, Any]] = []
    for kind in cross_portal.SEARCH_TYPES:
        ranked: list[tuple[float, int, int, dict[str, Any]]] = []
        for pos, item in enumerate(local.get(kind, []), start=1):
            entry = _local_item(item)
            entry["score"] = round(1.0 / (RRF_K + pos), 6)
            ranked.append((-entry["score"], 0, pos, entry))
        remote = remote_by_type.get(kind) or {}
        for pos, item in enumerate(remote.get("items") or [], start=1):
            entry = _remote_item(item, kind) if isinstance(item, dict) else None
            if entry is None:
                continue
            entry["score"] = round(1.0 / (RRF_K + pos), 6)
            ranked.append((-entry["score"], 1, pos, entry))
        if not ranked:
            continue
        ranked.sort(key=lambda r: (r[0], r[1], r[2]))
        local_total = int(local_totals.get(kind, 0))
        remote_total = int(remote.get("total") or 0)
        groups.append(
            {
                "type": kind,
                "label": LABELS[kind],
                "total": local_total + remote_total,
                "localTotal": local_total,
                "remoteTotal": remote_total,
                "items": [r[3] for r in ranked[:limit]],
            }
        )
    return groups
