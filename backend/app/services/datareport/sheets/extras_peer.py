from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.core.config import get_settings
from app.core.db import db
from app.services import cross_portal, unified_search as us
from app.services.datareport.contract import Banner, Column, Link, ReportContext, Sheet, register
from app.services.datareport.sheets.extras_common import (
    Plugin,
    cached,
    chunks,
    fingerprint,
    g,
    ident,
    label,
    open_record,
    records,
    say,
    when,
)

logger = logging.getLogger(__name__)

PEER_MAX = 40
PARALLEL = 4
PER_ARTISAN = 20

STATUS_NOTES = {
    us.STATUS_OFF: ("banner.otherPortalOff", "The link to the {peer} is switched off, so nothing from it is shown."),
    us.STATUS_NOT_PERMITTED: ("banner.otherPortalNotPermitted", "Your role can't see records from the {peer}."),
    us.STATUS_UNAVAILABLE: (
        "banner.otherPortalUnavailable",
        "The {peer} couldn't be reached while this report was made. Make the report again to include it.",
    ),
}


async def links_for(aids: list[str]) -> dict[str, list[tuple[str, str]]]:
    out: dict[str, list[tuple[str, str]]] = {}
    for part in chunks(aids):
        for row in await db.crossportalartisanlink.find_many(where={"artisanId": {"in": part}}):
            remote = str(g(row, "remoteArtisanId") or "")
            if remote:
                out.setdefault(str(row.artisanId), []).append((remote, str(g(row, "matchedBy") or "")))
    return out


def belongs(item: dict[str, Any], kind: str, local_id: str, remote_ids: set[str]) -> bool:
    if kind == "artisans" and str(item.get("id") or "") in remote_ids:
        return True
    linked = item.get("linkedArtisanIds")
    return isinstance(linked, list) and local_id in {str(a) for a in linked}


async def _keep_consented(groups: list[Any]) -> list[Any]:
    drop = getattr(us, "drop_unconsented", None)
    if drop is None:
        return [grp for grp in groups or [] if isinstance(grp, dict)]
    return await drop(groups)


async def ask_for(settings: Any, user: Any, name: str) -> tuple[str, list[Any]]:
    state, payload = await us.ask_peer(
        settings,
        user,
        q=name,
        types=None,
        limit=PER_ARTISAN,
        one_type=None,
        page=1,
        filters={},
    )
    if state != us.STATUS_OK:
        return state, []
    return state, await _keep_consented(payload.get("groups") or [])


def peer_status(settings: Any, user: Any) -> str:
    if not cross_portal.search_active(settings):
        return us.STATUS_OFF
    if not us.may_search_across(user):
        return us.STATUS_NOT_PERMITTED
    return us.STATUS_OK


async def gather(ctx: ReportContext, artisans: list[Any], links: dict[str, list[tuple[str, str]]]) -> tuple[str, list[dict[str, Any]]]:
    settings = get_settings()
    state = peer_status(settings, ctx.user)
    if state != us.STATUS_OK:
        return state, []
    gate = asyncio.Semaphore(PARALLEL)
    shared = [a for a in artisans if ident(a) in links][:PEER_MAX]

    async def one(artisan: Any) -> tuple[str, list[dict[str, Any]]]:
        aid = ident(artisan)
        remote_ids = {r for r, _ in links[aid]}
        async with gate:
            answer, groups = await ask_for(settings, ctx.user, str(g(artisan, "name") or ""))
        found: list[dict[str, Any]] = []
        for group in groups:
            kind = str(group.get("type") or "")
            for item in group.get("items") or []:
                if isinstance(item, dict) and belongs(item, kind, aid, remote_ids):
                    found.append({"artisanId": aid, "kind": kind, **item})
        return answer, found

    try:
        answers = await asyncio.gather(*(one(a) for a in shared))
    except Exception as exc:  # noqa: BLE001
        logger.warning("report peer sheet: %s", type(exc).__name__)
        return us.STATUS_UNAVAILABLE, []
    states = {s for s, _ in answers}
    items = [item for _, found in answers for item in found]
    if states and us.STATUS_OK not in states:
        return us.STATUS_UNAVAILABLE, []
    return us.STATUS_OK, items


COLUMNS = [
    Column("artisan", "Artisan here", "link", "The artisan in this portal.", width=24, source="Artisan.name"),
    Column("artisanId", "Artisan ID", "id", "This portal's artisan id.", hidden=True, group="ids", source="CrossPortalArtisanLink.artisanId"),
    Column("remoteId", "Their artisan ID", "id", "The same artisan's id in the other portal.", hidden=True, group="ids", source="CrossPortalArtisanLink.remoteArtisanId"),
    Column("matchedBy", "Matched by", "text", "How the two records were linked: Pehchan card, Aadhaar or artisan card.", width=12, source="CrossPortalArtisanLink.matchedBy"),
    Column("portal", "Portal", "text", "Where the record lives.", width=16),
    Column("type", "Type", "text", "What the record is there.", width=14),
    Column("title", "Record", "link", "Opens the record in the other portal (sign in there).", width=30),
    Column("subtitle", "Details", "text", "What the other portal says about it.", width=30),
    Column("place", "Place", "text", "Place recorded there.", width=18),
    Column("recordDate", "Date", "datetime", "Date recorded there."),
    Column("snippet", "Snippet", "longtext", "A short extract shared under consent.", width=40),
]


async def build(ctx: ReportContext) -> list[Sheet]:
    peer = us.PEER_LABEL
    artisans = records(ctx, "artisans")
    aids = sorted({ident(a) for a in artisans} - {""})
    links = await links_for(aids) if aids else {}
    if not links:
        return [
            Sheet(
                key="bothPortals",
                name="Both portals",
                columns=COLUMNS,
                rows=[],
                note=say(ctx, "banner.noLinkedArtisans", "No artisan in this folder is linked to the {peer} yet.", peer=peer),
            )
        ]

    async def load() -> tuple[str, list[dict[str, Any]]]:
        return await gather(ctx, artisans, links)

    key = "extras.peer." + fingerprint([str(g(ctx.user, "id") or "")], sorted(links))
    state, items = await cached(ctx, key, load)
    by_id = {ident(a): a for a in artisans}
    banners = [
        Banner(say(ctx, "banner.linkedArtisans", "{n} artisans here are also in the {peer}.", n=len(links), peer=peer)),
        Banner(say(ctx, "banner.consent", "Shown only where the artisan's consent allows.")),
    ]
    if len(links) > PEER_MAX:
        banners.append(Banner(say(ctx, "banner.firstLinked", "The first {n} linked artisans are included.", n=PEER_MAX)))
    note = say(ctx, *STATUS_NOTES[state], peer=peer) if state in STATUS_NOTES else None
    rows: list[dict[str, Any]] = []
    for item in items:
        aid = item["artisanId"]
        artisan = by_id.get(aid)
        remote, matched = links.get(aid, [("", "")])[0]
        url = str(item.get("openUrl") or "")
        title = str(item.get("title") or "")
        rows.append(
            {
                "artisan": open_record(ctx, "artisans", artisan) if artisan is not None else aid,
                "artisanId": aid,
                "remoteId": remote,
                "matchedBy": matched.title() or None,
                "portal": peer,
                "type": us.LABELS.get(item["kind"], item["kind"]),
                "title": Link(title, url) if url.startswith("https://") else title,
                "subtitle": item.get("subtitle"),
                "place": item.get("place"),
                "recordDate": when(item.get("recordDate")),
                "snippet": item.get("snippet"),
            }
        )
    if state == us.STATUS_OK and not rows:
        note = say(ctx, "banner.otherPortalNothing", "The {peer} holds nothing it may share for these artisans.", peer=peer)
    present = {r["artisanId"] for r in rows}
    for aid in sorted(links):
        if aid not in present and aid in by_id and state == us.STATUS_OK and rows:
            remote, matched = links[aid][0]
            rows.append(
                {
                    "artisan": open_record(ctx, "artisans", by_id[aid]),
                    "artisanId": aid,
                    "remoteId": remote,
                    "matchedBy": matched.title() or None,
                    "portal": peer,
                    "type": None,
                    "title": say(ctx, "text.nothingShared", "Nothing shared for {name}", name=label("artisans", by_id[aid])),
                    "subtitle": None,
                    "place": None,
                    "recordDate": None,
                    "snippet": None,
                }
            )
    return [
        Sheet(key="bothPortals", name="Both portals", columns=COLUMNS, rows=rows, banners=banners, note=note, count=len(rows))
    ]


register(Plugin("extras_peer", "Both portals", 140, "peer", build, lambda: [("bothPortals", COLUMNS, "Both portals")]))
