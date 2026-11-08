from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.core.deps import get_value, role_value
from app.services import cross_portal
from app.services.cross_portal_client import (
    SEARCH_TIMEOUT_SECONDS,
    PeerUnavailable,
    peer_call,
)
from app.services.search_query import DEFAULT_SORT, RRF_K, Scope

logger = logging.getLogger(__name__)

PORTAL = "fieldRepository"
PORTAL_LABEL = "Field Repository"
PEER_PORTAL = "designer"
PEER_LABEL = "Designer portal"

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
    if not raw:
        return None
    asked = {part.strip() for value in raw for part in str(value).split(",") if part.strip()}
    unknown = sorted(asked - set(cross_portal.SEARCH_TYPES))
    if unknown:
        raise ValueError(", ".join(unknown))
    return [t for t in cross_portal.SEARCH_TYPES if t in asked]


async def account_scope(user: Any | None, types: list[str] | None) -> tuple[Scope, list[str]]:
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
    sort: str | None = None,
) -> tuple[str, dict[str, Any]]:
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
        **({"sort": sort} if sort else {}),
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
        **({"startMs": item["startMs"]} if isinstance(item.get("startMs"), int) else {}),
        **({"mediaType": item["mediaType"]} if item.get("mediaType") else {}),
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


def _when(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        moment = value
    elif isinstance(value, str) and value.strip():
        try:
            moment = datetime.fromisoformat(value.strip())
        except ValueError:
            return None
    else:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def _sort_value(entry: dict[str, Any], key: str) -> Any:
    if key == "name":
        return str(entry.get("title") or "").lower()
    return _when(entry.get("recordDate"))


def order_items(
    ranked: list[tuple[float, int, int, dict[str, Any]]], sort: tuple[str, str]
) -> list[tuple[float, int, int, dict[str, Any]]]:
    key, direction = sort

    def tie(row: tuple[float, int, int, dict[str, Any]]) -> tuple[int, str, str]:
        return (row[1], str(row[3].get("title") or "").lower(), str(row[3].get("id") or ""))

    present = [r for r in ranked if _sort_value(r[3], key) is not None]
    missing = [r for r in ranked if _sort_value(r[3], key) is None]
    present.sort(key=tie)
    present.sort(key=lambda r: _sort_value(r[3], key), reverse=direction == "desc")
    missing.sort(key=tie)
    return present + missing


def merge(
    local: dict[str, list[dict[str, Any]]],
    local_totals: dict[str, int],
    remote_groups: list[dict[str, Any]],
    *,
    limit: int,
    links: dict[str, str] | None = None,
    sort: tuple[str, str] = DEFAULT_SORT,
) -> list[dict[str, Any]]:
    linked = {remote: local for local, remote in (links or {}).items()}
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
        local_by_id = {r[3]["id"]: r[3] for r in ranked}
        merged = 0
        for pos, item in enumerate(remote.get("items") or [], start=1):
            entry = _remote_item(item, kind) if isinstance(item, dict) else None
            if entry is None:
                continue
            twin = local_by_id.get(linked.get(entry["id"], "")) if kind == "artisans" else None
            if twin is not None:
                twin["alsoIn"] = {
                    "portal": entry["portal"],
                    "id": entry["id"],
                    "href": entry["href"],
                    "openUrl": entry.get("openUrl"),
                    "subtitle": entry.get("subtitle"),
                }
                merged += 1
                continue
            entry["score"] = round(1.0 / (RRF_K + pos), 6)
            ranked.append((-entry["score"], 1, pos, entry))
        local_total = int(local_totals.get(kind, 0))
        remote_total = max(0, int(remote.get("total") or 0) - merged)
        if not ranked and not local_total + remote_total:
            continue
        if sort[0] == DEFAULT_SORT[0]:
            ranked.sort(key=lambda r: (r[0], r[1], r[2]))
        else:
            ranked = order_items(ranked, sort)
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


async def artisan_links(local_ids: list[str]) -> dict[str, str]:
    if not local_ids:
        return {}
    from app.core.db import db

    try:
        rows = await db.crossportalartisanlink.find_many(where={"artisanId": {"in": local_ids}})
    except Exception:  # noqa: BLE001
        return {}
    return {str(r.artisanId): str(r.remoteArtisanId) for r in rows if r.remoteArtisanId}
