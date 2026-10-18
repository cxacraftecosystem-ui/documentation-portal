"""The designer portal's searches of this portal: ``POST /cross-portal/peer/search`` and ``.../record``.

The caller is the designer portal's server, admitted by the shared token and a fresh nonce
(``services/cross_portal_peer``), and every answer is signed over the request it answers. Both are
POST so the query text stays out of URLs and proxy logs. Every route answers 404 until
``cross_portal.search_active``.

How a request is treated (docs/SEARCH.md §3.5):

1. The requester's e-mail is matched to an account here. A match brings that account's own read
   rules; no match gets the floor, ``audience = 'ALL'``. On this portal every document is ``ALL``,
   so the two read the same today; the lookup stays so a narrower rule lands in one place. Either
   way only ``shareable`` documents are considered.
2. Consent is re-checked live in the same SQL: every artisan a record names must still have granted
   sharing consent here, so a withdrawal takes effect before the indexer catches up.
3. Each search is logged (``action=SEARCH``, the asking account, how many results) — never the query.
4. 120 searches per 10 minutes per asking account; then a signed 429.
5. What goes back is :data:`cross_portal.PEER_ITEM_KEYS` and nothing else: never a media URL, a
   storage key, a phone number, e-mail, Aadhaar, Pehchan, date of birth or address. This portal
   sends no ``linkedArtisanIds``; only the designer portal fills them.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import db
from app.services import cross_portal, search_embeddings, search_query
from app.services import unified_search as us
from app.services.access_roster import gate_enforced, normalise_email, roster_admits
from app.services.cross_portal_peer import requested_by, require_search_peer, signed
from app.services.records import plain

router = APIRouter(prefix="/cross-portal/peer", tags=["cross-portal"])

ACTION_SEARCH = "SEARCH"
ACTION_RECORD_VIEW = "RECORD_VIEW"

#: Searches one asking account may make in :data:`RATE_WINDOW`.
RATE_LIMIT = 120
RATE_WINDOW = timedelta(minutes=10)

#: Characters of a record's body shown on the other portal's read-only card.
RECORD_SNIPPET_CHARS = 400


class Requester(BaseModel):
    model_config = ConfigDict(extra="ignore")

    userId: str = Field(default="", max_length=64)
    email: str = Field(default="", max_length=320)
    role: str = Field(default="", max_length=40)


class PeerFilters(BaseModel):
    model_config = ConfigDict(extra="ignore")

    craftCodes: list[str] = Field(default_factory=list, max_length=20)
    place: str | None = Field(default=None, max_length=200)
    dateFrom: datetime | None = None
    dateTo: datetime | None = None


class PeerSearch(BaseModel):
    model_config = ConfigDict(extra="ignore")

    q: str = Field(min_length=1, max_length=400)
    types: list[str] = Field(default_factory=list, max_length=20)
    limit: int = Field(default=5, ge=1, le=20)
    type: str | None = None
    page: int = Field(default=1, ge=1, le=1000)
    filters: PeerFilters = Field(default_factory=PeerFilters)
    requester: Requester = Field(default_factory=Requester)


class PeerRecord(BaseModel):
    model_config = ConfigDict(extra="ignore")

    type: str = Field(max_length=40)
    id: str = Field(min_length=1, max_length=200)
    requester: Requester = Field(default_factory=Requester)


def _asker(request: Request, requester: Requester) -> str | None:
    return (requester.userId or "").strip()[:64] or requested_by(request)


async def _local_account(requester: Requester) -> Any | None:
    email = normalise_email(plain(requester.email))
    if not email or "@" not in email:
        return None
    user = await db.user.find_first(where={"email": {"equals": email, "mode": "insensitive"}})
    if user is None:
        return None
    # While the allow-list is enforced, an account it does not admit is not active: the floor.
    if gate_enforced() and not await roster_admits(email):
        return None
    return user


async def _peer_scope(requester: Requester, types: list[str] | None) -> search_query.Scope:
    scope, _refused = await us.account_scope(await _local_account(requester), types)
    scope.shareable_only = True
    scope.require_artisan_consent = True
    return scope


async def _log(asker: str | None, action: str, outcome: str, **fields: Any) -> None:
    await db.crossportalaccesslog.create(
        data={"action": action, "outcome": outcome, "remoteUserId": asker, **fields}
    )


def _open_url(settings: Any, path: str) -> str:
    base = str(settings.next_public_app_url or "").rstrip("/")
    return f"{base}{path}"


@router.post("/search")
async def peer_search(
    body: PeerSearch, request: Request, settings: Any = Depends(require_search_peer)
) -> Response:
    asker = _asker(request, body.requester)
    since = datetime.now(UTC) - RATE_WINDOW
    made = await db.crossportalaccesslog.count(
        where={"remoteUserId": asker, "action": ACTION_SEARCH, "createdAt": {"gte": since}}
    )
    if made >= RATE_LIMIT:
        await _log(asker, ACTION_SEARCH, "rate_limited")
        return signed(settings, request, {"detail": "Too many searches."}, status_code=429)

    text = " ".join(plain(body.q).split())[:200]
    if len(text) < 2:
        return signed(settings, request, {"mode": "text", "groups": []})
    asked = [
        t for t in ([body.type] if body.type else body.types) if t in cross_portal.SEARCH_TYPES
    ]
    if asked and not any(t in us.LOCAL_TYPES for t in asked):
        # Only designer-portal types were asked for: nothing here can answer.
        await _log(asker, ACTION_SEARCH, "ok", resultCount=0)
        return signed(settings, request, {"mode": "text", "groups": []})
    scope = await _peer_scope(body.requester, asked or None)
    scope.craft_codes = [plain(c).strip() for c in body.filters.craftCodes if c and c.strip()]
    scope.place = (plain(body.filters.place).strip() or None) if body.filters.place else None
    scope.date_from = body.filters.dateFrom
    scope.date_to = body.filters.dateTo
    offset = (body.page - 1) * body.limit if body.type else 0

    qvec = None
    if await search_embeddings.available(settings):
        qvec = await search_embeddings.embed_query(text, settings=settings)
    result = await search_query.run(text, scope, limit=body.limit, offset=offset, qvec=qvec)

    groups: list[dict[str, Any]] = []
    count = 0
    for kind in cross_portal.SEARCH_TYPES:
        items = result.items.get(kind) or []
        if not items:
            continue
        out = []
        for item in items:
            projected = {key: item.get(key) for key in cross_portal.PEER_ITEM_KEYS}
            projected["openUrl"] = _open_url(settings, item["openPath"])
            out.append(projected)
        count += len(out)
        groups.append({"type": kind, "total": result.totals.get(kind, len(out)), "items": out})
    await _log(asker, ACTION_SEARCH, "ok", resultCount=count)
    return signed(settings, request, {"mode": result.mode, "groups": groups})


def _record_fields(row: dict[str, Any]) -> list[dict[str, str]]:
    fields: list[dict[str, str]] = [{"label": "Type", "value": us.LABELS[row["entityType"]]}]
    if row.get("subtitle"):
        fields.append({"label": "Details", "value": row["subtitle"]})
    if row.get("place"):
        fields.append({"label": "Place", "value": row["place"]})
    if row.get("craftCodes"):
        fields.append({"label": "Craft code", "value": ", ".join(row["craftCodes"])})
    return fields


@router.post("/search/record")
async def peer_search_record(
    body: PeerRecord, request: Request, settings: Any = Depends(require_search_peer)
) -> Response:
    asker = _asker(request, body.requester)
    kind = body.type if body.type in cross_portal.SEARCH_TYPES else None
    entity_id = plain(body.id).strip()
    row = None
    if kind:
        scope = await _peer_scope(body.requester, [kind])
        if scope.types:
            p = search_query._Params()
            kind_ref, id_ref = p.add(kind), p.add(entity_id)
            where = search_query._where(scope, p)
            rows = await db.query_raw(
                f"""SELECT d."entityType", d."entityId", d."title", d."subtitle", d."place",
                           d."craftCodes", d."recordDate", d."openPath", left(d."body", 2000) AS body
                      FROM "SearchDocument" d
                     WHERE d."entityType" = {kind_ref} AND d."entityId" = {id_ref} AND {where}""",
                *p.values,
            )
            row = rows[0] if rows else None
    if row is None:
        await _log(asker, ACTION_RECORD_VIEW, "not_found", entityType=kind, entityId=entity_id[:64])
        return signed(settings, request, {"record": None}, status_code=404)
    await _log(asker, ACTION_RECORD_VIEW, "ok", entityType=kind, entityId=entity_id[:64])
    body_text = str(row.get("body") or "")
    snippet = body_text[:RECORD_SNIPPET_CHARS].rstrip()
    if len(body_text) > RECORD_SNIPPET_CHARS:
        snippet += "…"
    record = {
        "type": row["entityType"],
        "id": row["entityId"],
        "title": row["title"],
        "subtitle": row.get("subtitle"),
        "fields": _record_fields(row),
        "snippet": snippet,
        "recordDate": row.get("recordDate"),
        "openUrl": _open_url(settings, row["openPath"]),
    }
    return signed(settings, request, {"record": record})
