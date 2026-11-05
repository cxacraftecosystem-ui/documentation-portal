from __future__ import annotations

from typing import Any

from app.core.db import db
from app.services.datareport.contract import Banner, Column, ReportContext, Sheet, register
from app.services.datareport.sheets.extras_common import (
    KIND_LABELS,
    KINDS,
    Plugin,
    chunks,
    clip,
    g,
    ident,
    json_text,
    media_facts,
    open_record,
    records,
    redact,
    say,
    sha256_of,
    status_text,
    user_names,
    when,
)

EVENTS_MAX = 50000


async def _logs(ids: list[str]) -> tuple[list[Any], list[Any]]:
    reviews: list[Any] = []
    revisions: list[Any] = []
    for part in chunks(ids):
        reviews.extend(
            await db.reviewlog.find_many(where={"recordId": {"in": part}}, order={"createdAt": "asc"})
        )
        revisions.extend(
            await db.recordrevision.find_many(where={"recordId": {"in": part}}, order={"createdAt": "asc"})
        )
    return reviews, revisions


def _approval(reviews: list[Any]) -> Any:
    approved = [r for r in reviews if status_text(g(r, "status")) == "APPROVED"]
    return approved[-1] if approved else None


SUMMARY_COLUMNS = [
    Column("type", "Type", "text", "What the record is.", width=16),
    Column("record", "Record", "link", "Opens the record or file in the portal.", width=30),
    Column("recordId", "Record ID", "id", "The record's id.", hidden=True, group="ids"),
    Column("status", "Status", "status", "Review status now.", width=12, source="Artisan.status"),
    Column("recordedBy", "Recorded by", "text", "Who created or uploaded it.", width=20, source="Artisan.createdById"),
    Column("recordedAt", "Recorded", "datetime", "When it was captured.", source="Artisan.recordedAt"),
    Column("createdAt", "Saved", "datetime", "When the server stored it.", source="Artisan.createdAt"),
    Column("submittedAt", "Submitted", "datetime", "When it was sent for review.", source="QuestionnaireInterview.submittedAt"),
    Column("reviewedBy", "Reviewed by", "text", "Who reviewed it last.", width=20, source="Artisan.reviewedById"),
    Column("reviewedAt", "Reviewed", "datetime", "When it was reviewed last.", source="Artisan.reviewedAt"),
    Column("reviewNotes", "Review notes", "longtext", "The reviewer's notes.", width=30, source="Artisan.reviewNotes"),
    Column("approvedBy", "Approved by", "text", "Who approved it last.", width=20, source="ReviewLog.reviewerId"),
    Column("approvedAt", "Approved", "datetime", "When it was approved last.", source="ReviewLog.createdAt"),
    Column("reviews", "Reviews", "int", "Review decisions on record.", total="sum", source="ReviewLog.id"),
    Column("edits", "Edits", "int", "Saved edits after creation.", total="sum", source="RecordRevision.id"),
    Column("lastEditBy", "Last edited by", "text", "Who edited it last.", width=20, source="RecordRevision.editedById"),
    Column("lastEditAt", "Last edited", "datetime", "When it was edited last.", source="RecordRevision.createdAt"),
    Column("updatedAt", "Updated", "datetime", "Last change of any kind.", source="Artisan.updatedAt"),
    Column("sha256", "SHA-256", "text", "Checksum of the stored file; compare it with a downloaded copy.", width=30, source="MediaFile.contentSha256"),
    Column("shaVerifiedAt", "Checksum verified", "datetime", "When the server last verified the checksum.", source="MediaFile.shaVerifiedAt"),
    Column("sizeBytes", "Size", "bytes", "Size of the stored file.", total="sum", source="MediaFile.sizeBytes"),
]

TRAIL_COLUMNS = [
    Column("at", "When", "datetime", "When it happened.", width=18, source="ReviewLog.createdAt"),
    Column("event", "Event", "text", "Review decision or edit.", width=10),
    Column("outcome", "Outcome", "status", "The decision, or the fields an edit changed.", width=16, source="ReviewLog.status"),
    Column("type", "Type", "text", "What the record is.", width=16, source="ReviewLog.recordType"),
    Column("record", "Record", "link", "Opens the record in the portal.", width=28),
    Column("recordId", "Record ID", "id", "The record's id.", hidden=True, group="ids", source="ReviewLog.recordId"),
    Column("by", "By", "text", "Reviewer or editor.", width=20, source="ReviewLog.reviewerId"),
    Column("detail", "Detail", "longtext", "Reason, notes, or the edit's before and after values.", width=50, source="RecordRevision.changes"),
]


async def build(ctx: ReportContext) -> list[Sheet]:
    picked: list[tuple[str, Any]] = [(kind, rec) for kind in KINDS for rec in records(ctx, kind)]
    ids = sorted({ident(rec) for _, rec in picked} - {""})
    reviews, revisions = await _logs(ids)
    facts = await media_facts(ctx) if any(k == "media" for k, _ in picked) else {}
    reviews_by: dict[str, list[Any]] = {}
    for log in reviews:
        reviews_by.setdefault(str(log.recordId), []).append(log)
    revisions_by: dict[str, list[Any]] = {}
    for rev in revisions:
        revisions_by.setdefault(str(rev.recordId), []).append(rev)
    people: set[str] = set()
    for _, rec in picked:
        people |= {str(g(rec, k) or "") for k in ("createdById", "uploadedById", "reviewedById")}
    people |= {str(g(r, "reviewerId") or "") for r in reviews}
    people |= {str(g(r, "editedById") or "") for r in revisions}
    names = await user_names(people)

    def who(value: Any) -> str | None:
        key = str(value or "")
        return names.get(key, key) if key else None

    rows = []
    for kind, rec in picked:
        rid = ident(rec)
        fact = facts.get(rid) if kind == "media" else None
        mine = reviews_by.get(rid, [])
        edits = revisions_by.get(rid, [])
        approval = _approval(mine)
        last_edit = edits[-1] if edits else None
        rows.append(
            {
                "type": KIND_LABELS.get(kind, kind),
                "record": open_record(ctx, kind, rec),
                "recordId": rid,
                "status": status_text(g(rec, "status")).title() or None,
                "recordedBy": who(g(rec, "uploadedById") or g(rec, "createdById")),
                "recordedAt": when(g(rec, "recordedAt")) or when(g(rec, "createdAt")),
                "createdAt": when(g(rec, "createdAt")),
                "submittedAt": when(g(rec, "submittedAt")),
                "reviewedBy": who(g(rec, "reviewedById") or (fact or {}).get("reviewedById")),
                "reviewedAt": when(g(rec, "reviewedAt")) or when((fact or {}).get("reviewedAt")),
                "reviewNotes": clip(g(rec, "reviewNotes")),
                "approvedBy": who(g(approval, "reviewerId")) if approval else None,
                "approvedAt": when(g(approval, "createdAt")) if approval else None,
                "reviews": len(mine),
                "edits": len(edits),
                "lastEditBy": who(g(last_edit, "editedById")) if last_edit else None,
                "lastEditAt": when(g(last_edit, "createdAt")) if last_edit else None,
                "updatedAt": when(g(rec, "updatedAt")),
                "sha256": sha256_of(fact) if kind == "media" else None,
                "shaVerifiedAt": when((fact or {}).get("shaVerifiedAt")),
                "sizeBytes": int(g(rec, "sizeBytes") or 0) if kind == "media" else None,
            }
        )
    summary = Sheet(
        key="audit",
        name="Audit and trust",
        columns=SUMMARY_COLUMNS,
        rows=rows,
        banners=[Banner(say(ctx, "banner.checksums", "Each file's SHA-256 checksum lets anyone verify it has not been altered."))],
        note=None if rows else say(ctx, "banner.empty", "Nothing to show for this selection."),
        databar_columns=["sizeBytes"],
        count=len(rows),
        media_ids=[ident(rec) for kind, rec in picked if kind == "media" and ident(rec)],
        media_bytes=sum(int(g(rec, "sizeBytes") or 0) for kind, rec in picked if kind == "media"),
    )
    by_record = {ident(rec): (kind, rec) for kind, rec in picked}
    events: list[dict[str, Any]] = []
    for log in reviews:
        kind, rec = by_record.get(str(log.recordId), ("", None))
        reason = g(log, "reason")
        events.append(
            {
                "at": when(g(log, "createdAt")),
                "event": "Review",
                "outcome": status_text(g(log, "status")).title(),
                "type": KIND_LABELS.get(kind, kind),
                "record": open_record(ctx, kind, rec) if rec is not None else str(log.recordId),
                "recordId": str(log.recordId),
                "by": who(g(log, "reviewerId")),
                "detail": clip(" · ".join(str(x) for x in (reason, g(log, "notes")) if x)) or None,
            }
        )
    for rev in revisions:
        kind, rec = by_record.get(str(rev.recordId), ("", None))
        changes = redact(g(rev, "changes"))
        fields = sorted(changes) if isinstance(changes, dict) else []
        events.append(
            {
                "at": when(g(rev, "createdAt")),
                "event": "Edit",
                "outcome": ", ".join(fields[:12]) if fields else None,
                "type": KIND_LABELS.get(kind, kind),
                "record": open_record(ctx, kind, rec) if rec is not None else str(rev.recordId),
                "recordId": str(rev.recordId),
                "by": who(g(rev, "editedById")),
                "detail": clip(json_text(changes)) if changes is not None else None,
            }
        )
    events.sort(key=lambda e: e["at"].timestamp() if e["at"] else 0.0, reverse=True)
    capped = len(events) > EVENTS_MAX
    shown = events[:EVENTS_MAX]
    if not events:
        trail_note = say(ctx, "banner.noAudit", "No reviews or edits yet.")
    elif capped:
        trail_note = say(ctx, "banner.capped", "Capped at {n} rows — the full data set has more.", n=EVENTS_MAX)
    else:
        trail_note = None
    trail = Sheet(key="auditTrail", name="Audit trail", columns=TRAIL_COLUMNS, rows=shown, note=trail_note, count=len(shown))
    return [summary, trail]


register(
    Plugin(
        "extras_audit",
        "Audit and trust",
        150,
        "audit",
        build,
        lambda: [("audit", SUMMARY_COLUMNS, "Audit and trust"), ("auditTrail", TRAIL_COLUMNS, "Audit trail")],
    )
)
