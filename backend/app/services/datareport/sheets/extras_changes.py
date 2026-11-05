from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services.datareport.contract import Banner, Column, ReportContext, Sheet, register
from app.services.datareport.sheets.extras_common import (
    KIND_LABELS,
    KINDS,
    Plugin,
    chunks,
    g,
    ident,
    open_record,
    records,
    say,
    status_text,
    user_names,
    when,
)

REPORT_PREFIX = "report:"
ROWS_MAX = 20000

_TRANSCRIPTS_SQL = (
    'SELECT v."mediaFileId", v."createdAt", v."translationStatus", v."translatedAt", v."version" '
    'FROM "TranscriptVersion" v WHERE v."mediaFileId" = ANY($1::text[]) '
    'AND (v."createdAt" > $2::timestamp OR v."translatedAt" > $2::timestamp)'
)


def report_path(path: str) -> str:
    return REPORT_PREFIX + (path or "")


def _uid(user: Any) -> str:
    return str(g(user, "id") or "")


async def last_report(user_id: str, path: str) -> datetime | None:
    if not user_id:
        return None
    row = await db.datafoldervisit.find_unique(
        where={"userId_path": {"userId": user_id, "path": report_path(path)}}
    )
    return when(g(row, "visitedAt"))


async def stamp(user_id: str, path: str, at: datetime | None = None) -> datetime:
    moment = at or datetime.now(UTC)
    key = report_path(path)
    await db.datafoldervisit.upsert(
        where={"userId_path": {"userId": user_id, "path": key}},
        data={
            "create": {"userId": user_id, "path": key, "visitedAt": moment},
            "update": {"visitedAt": moment},
        },
    )
    return moment


def _naive(moment: datetime) -> str:
    return moment.astimezone(UTC).replace(tzinfo=None).isoformat(sep=" ")


def record_changes(ctx: ReportContext, since: datetime) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for kind in KINDS:
        for rec in records(ctx, kind):
            created = when(g(rec, "createdAt"))
            updated = when(g(rec, "updatedAt"))
            if created and created > since:
                change, at = "Added", created
            elif updated and updated > since:
                change, at = "Changed", updated
            else:
                continue
            out.append(
                {
                    "kind": kind,
                    "rec": rec,
                    "change": change,
                    "at": at,
                    "by": str(g(rec, "uploadedById") or g(rec, "createdById") or ""),
                    "detail": status_text(g(rec, "status")).title() or None,
                }
            )
    return out


async def review_changes(ctx: ReportContext, since: datetime) -> list[dict[str, Any]]:
    by_id: dict[str, tuple[str, Any]] = {}
    for kind in KINDS:
        for rec in records(ctx, kind):
            by_id[ident(rec)] = (kind, rec)
    ids = sorted(by_id)
    out: list[dict[str, Any]] = []
    for part in chunks(ids):
        logs = await db.reviewlog.find_many(
            where={"AND": [{"recordId": {"in": part}}, {"createdAt": {"gt": since}}]},
            order={"createdAt": "asc"},
        )
        for log in logs:
            hit = by_id.get(str(log.recordId))
            if hit is None:
                continue
            kind, rec = hit
            detail = status_text(g(log, "status")).title()
            reason = g(log, "reason") or g(log, "notes")
            out.append(
                {
                    "kind": kind,
                    "rec": rec,
                    "change": "Reviewed",
                    "at": when(g(log, "createdAt")),
                    "by": str(g(log, "reviewerId") or ""),
                    "detail": f"{detail}: {reason}" if reason else detail,
                }
            )
    return out


async def transcript_changes(ctx: ReportContext, since: datetime) -> list[dict[str, Any]]:
    media = {ident(m): m for m in records(ctx, "media")}
    audio = sorted(mid for mid, m in media.items() if str(g(m, "mediaType") or "").upper() in ("AUDIO", "VIDEO"))
    out: list[dict[str, Any]] = []
    stamp_at = _naive(since)
    for part in chunks(audio):
        for row in await db.query_raw(_TRANSCRIPTS_SQL, part, stamp_at):
            rec = media.get(str(row["mediaFileId"]))
            created = when(row.get("createdAt"))
            translated = when(row.get("translatedAt"))
            if translated and translated > since:
                change, at = "Translated", translated
            else:
                change, at = "Transcribed", created
            out.append(
                {
                    "kind": "media",
                    "rec": rec,
                    "change": change,
                    "at": at,
                    "by": "",
                    "detail": f"Version {row.get('version')}",
                }
            )
    return out


COLUMNS = [
    Column("at", "When", "datetime", "When the change happened.", width=18),
    Column("change", "Change", "status", "Added, Changed, Reviewed, Transcribed or Translated.", width=14),
    Column("type", "Type", "text", "What the record is.", width=16),
    Column("record", "Record", "link", "Opens the record or file in the portal.", width=30),
    Column("recordId", "Record ID", "id", "The record's id.", hidden=True, group="ids"),
    Column("detail", "Detail", "text", "Review outcome and reason, status or transcript version.", width=28, source="ReviewLog.status"),
    Column("by", "By", "text", "Who made the change, where known.", width=20, source="ReviewLog.reviewerId"),
]


CHANGE_KEYS = {
    "Added": "status.added",
    "Changed": "status.changed",
    "Reviewed": "status.reviewed",
    "Transcribed": "status.transcribed",
    "Translated": "status.translated",
}
EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


def _on_delivery(ctx: ReportContext, uid: str) -> None:
    hooks = getattr(ctx, "delivered", None)
    if uid and isinstance(hooks, list):
        path = ctx.path

        async def hook() -> None:
            await stamp(uid, path)

        hooks.append(hook)


async def build(ctx: ReportContext) -> list[Sheet]:
    uid = _uid(ctx.user)
    _on_delivery(ctx, uid)
    since = await last_report(uid, ctx.path)
    found = record_changes(ctx, since or EPOCH)
    found += await review_changes(ctx, since or EPOCH)
    found += await transcript_changes(ctx, since or EPOCH)
    found.sort(key=lambda r: r["at"].timestamp() if r["at"] else 0.0, reverse=True)
    capped = len(found) > ROWS_MAX
    found = found[:ROWS_MAX]
    names = await user_names({r["by"] for r in found})
    rows = [
        {
            "at": r["at"],
            "change": ctx.t(CHANGE_KEYS[r["change"]], r["change"]),
            "type": KIND_LABELS.get(r["kind"], r["kind"]),
            "record": open_record(ctx, r["kind"], r["rec"]) if r["rec"] is not None else None,
            "recordId": ident(r["rec"]),
            "detail": r["detail"],
            "by": names.get(r["by"]) if r["by"] else None,
        }
        for r in found
    ]
    if since is None:
        banner = Banner(say(ctx, "banner.firstReport", "This is your first report, so everything is listed."))
    else:
        banner = Banner(say(ctx, "banner.since", "Changes since your last report on {date}.", date=f"{since.astimezone(UTC):%d %b %Y, %H:%M} UTC"))
    note = None if rows else say(ctx, "banner.empty", "Nothing to show for this selection.")
    if capped:
        note = say(ctx, "banner.capped", "Capped at {n} rows — the full data set has more.", n=ROWS_MAX)
    return [
        Sheet(key="changes", name="What changed", columns=COLUMNS, rows=rows, banners=[banner], note=note, count=len(rows))
    ]


register(
    Plugin("extras_changes", "What changed", 130, "changes", build, lambda: [("changes", COLUMNS, "What changed")])
)
