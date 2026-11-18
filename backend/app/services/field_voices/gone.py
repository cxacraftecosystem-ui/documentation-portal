from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

from app.services.field_voices import contract, items, ledger, people
from app.services.field_voices.items import Outcome, Subjects

__all__ = ["gone_pass", "gone_reasons"]

MEDIA_SQL = (
    'SELECT "id", "removedAt", "status"::text AS "status" FROM "MediaFile" WHERE "id" = ANY($1::text[])'
)
RECORDING_SQL = (
    'SELECT r."mediaFileId", r."removedAt", n."mediaFileId" AS "successor" FROM "QuestionnaireRecording" r '
    'LEFT JOIN LATERAL (SELECT x."mediaFileId" FROM "QuestionnaireRecording" x '
    'WHERE x."replacesRecordingId" = r."id" AND x."removedAt" IS NULL ORDER BY x."createdAt" DESC LIMIT 1) n '
    'ON TRUE WHERE r."mediaFileId" = ANY($1::text[])'
)
SESSION_SQL = (
    'SELECT "id", "deletedAt", "mergedIntoId" FROM "QuestionnaireInterview" WHERE "id" = ANY($1::text[])'
)
ARTISAN_SQL = 'SELECT "id" FROM "Artisan" WHERE "id" = ANY($1::text[])'
RECORD_SQL = {
    "PRODUCT": 'SELECT "id" FROM "ProductDocumentation" WHERE "id" = ANY($1::text[])',
    "TOOL": 'SELECT "id" FROM "ToolDocumentation" WHERE "id" = ANY($1::text[])',
    "PROCESS": 'SELECT "id" FROM "Process" WHERE "id" = ANY($1::text[])',
    "PROCESS_STEP": 'SELECT "id" FROM "ProcessStep" WHERE "id" = ANY($1::text[])',
    "WORKSHOP": 'SELECT "id" FROM "Workshop" WHERE "id" = ANY($1::text[])',
    "DESIGNER": 'SELECT "id" FROM "User" WHERE "id" = ANY($1::text[])',
}
DESIGN_STAGE = "DESIGN_STAGE"
CRAFT = "CRAFT"
ARTISAN = "ARTISAN"
SESSION = "SESSION"
DESIGN_WORKSHOP = "DESIGN_WORKSHOP"


@dataclass
class GoneFacts:
    sessions_gone: set[str] = field(default_factory=set)
    designs: dict[str, dict[str, Any]] = field(default_factory=dict)
    rows_gone: set[str] = field(default_factory=set)
    present: set[tuple[str, str]] = field(default_factory=set)
    survivors: dict[str, str] = field(default_factory=dict)
    resolved: dict[str, str] = field(default_factory=dict)
    artisans_present: set[str] = field(default_factory=set)


async def _query(client: Any, sql: str, ids: Iterable[str]) -> list[dict[str, Any]]:
    wanted = sorted({str(i) for i in ids if i})
    if not wanted:
        return []
    return [dict(r) for r in await client.query_raw(sql, wanted)]


async def _gone_facts(client: Any, adapter: Any, stream: str, payloads: dict[str, dict[str, Any]]) -> GoneFacts:
    facts = GoneFacts()
    placed = [x for p in payloads.values() for x in _placements(stream, p)]
    session_ids = {str((p.get("session") or {}).get("id")) for p in payloads.values() if (p.get("session") or {}).get("id")}
    session_ids |= {record for kind, record in placed if kind == SESSION and record}
    facts.sessions_gone = await _sessions_gone(client, session_ids)
    design_ids = {
        str(p["workshop"]["id"]) for p in payloads.values()
        if (p.get("workshop") or {}).get("kind") == "DESIGN_WORKSHOP"
    }
    design_ids |= {record for kind, record in placed if kind == DESIGN_WORKSHOP and record}
    facts.designs = await adapter.design_workshops(client, design_ids)
    stage_rows = {r for p in payloads.values() for r in _stage_rows(stream, p)}
    facts.rows_gone = await adapter.stage_rows_gone(client, stage_rows)
    facts.present = await _records_present(client, placed)
    facts.survivors = await adapter.survivors(client)
    artisan_ids = {a for p in payloads.values() for a in _people(p)[0]}
    artisan_ids |= {record for kind, record in placed if kind == ARTISAN and record}
    facts.resolved = {a: people.resolve(a, facts.survivors) for a in artisan_ids}
    facts.artisans_present = {str(r["id"]) for r in await _query(client, ARTISAN_SQL, set(facts.resolved.values()))}
    return facts


def _subjects(item_id: str, payload: dict[str, Any], stored: Any, survivors: dict[str, str]) -> Subjects:
    artisans, users = _people(payload)
    workshop = payload.get("workshop") or {}
    found = Subjects(
        media={item_id},
        artisans=artisans,
        workshops={str(workshop["id"])} if workshop.get("id") else set(),
        users=users,
    ).joined(Subjects.from_stored(stored))
    found.artisans |= {people.resolve(a, survivors) for a in found.artisans}
    return found


def _people(payload: dict[str, Any]) -> tuple[set[str], set[str]]:
    artisans: set[str] = set()
    users: set[str] = set()
    for person in payload.get("people") or []:
        target = artisans if person.get("kind") == people.ARTISAN else users
        target.add(str(person.get("id")))
        target.update(str(m) for m in person.get("mergedIds") or [])
    return artisans, users


def _stage_rows(stream: str, payload: dict[str, Any]) -> set[str]:
    if stream == contract.TRANSCRIPTS:
        stage = payload.get("stage") or {}
        return {str(stage["rowId"])} if stage.get("rowId") else set()
    return {row for kind, row in _placements(stream, payload) if kind == DESIGN_STAGE and row}


def _placements(stream: str, payload: dict[str, Any]) -> list[tuple[str, str | None]]:
    if stream != contract.IMAGES:
        return []
    out: list[tuple[str, str | None]] = []
    for placement in payload.get("belongsTo") or []:
        kind = str(placement.get("kind") or "")
        found = (placement.get("row") or {}).get("id") if kind == DESIGN_STAGE else placement.get("id")
        out.append((kind, str(found) if found else None))
    return out


async def _records_present(client: Any, wanted: Iterable[tuple[str, str | None]]) -> set[tuple[str, str]]:
    by_kind: dict[str, set[str]] = {}
    for kind, record_id in wanted:
        if kind in RECORD_SQL and record_id:
            by_kind.setdefault(kind, set()).add(record_id)
    present: set[tuple[str, str]] = set()
    for kind, ids in sorted(by_kind.items()):
        present |= {(kind, str(r["id"])) for r in await _query(client, RECORD_SQL[kind], ids)}
    return present


def _anchored(placements: list[tuple[str, str | None]], facts: GoneFacts, design_gone: bool) -> bool:
    for kind, record_id in placements:
        if kind == CRAFT:
            return True
        if not record_id:
            continue
        if kind == DESIGN_STAGE:
            found = record_id not in facts.rows_gone and not design_gone
        elif kind == ARTISAN:
            found = facts.resolved.get(record_id, record_id) in facts.artisans_present
        elif kind == SESSION:
            found = record_id not in facts.sessions_gone
        elif kind == DESIGN_WORKSHOP:
            found = not _design_gone({"kind": DESIGN_WORKSHOP, "id": record_id}, facts.designs)
        else:
            found = (kind, record_id) in facts.present
        if found:
            return True
    return False


def _design_gone(workshop: dict[str, Any], designs: dict[str, dict[str, Any]]) -> bool:
    if workshop.get("kind") != "DESIGN_WORKSHOP":
        return False
    design = designs.get(str(workshop.get("id")))
    return design is None or bool(design.get("deletedAt"))


def _deleted(stream: str, payload: dict[str, Any], facts: GoneFacts) -> bool:
    workshop = payload.get("workshop") or {}
    design_gone = _design_gone(workshop, facts.designs)
    rows = _stage_rows(stream, payload)
    if stream == contract.TRANSCRIPTS:
        return design_gone or bool(rows and rows <= facts.rows_gone)
    placements = _placements(stream, payload)
    return bool(placements) and not _anchored(placements, facts, design_gone)


async def _sessions_gone(client: Any, ids: set[str]) -> set[str]:
    rows = {str(r["id"]): r for r in await _query(client, SESSION_SQL, ids)}
    pending = {str(r["mergedIntoId"]) for r in rows.values() if r.get("mergedIntoId")} - set(rows)
    while pending:
        more = {str(r["id"]): r for r in await _query(client, SESSION_SQL, pending)}
        rows.update(more)
        pending = {str(r["mergedIntoId"]) for r in more.values() if r.get("mergedIntoId")} - set(rows)
    gone: set[str] = set()
    for session_id in ids:
        seen: set[str] = set()
        current: str | None = session_id
        while current and current in rows and current not in seen:
            seen.add(current)
            row = rows[current]
            if row.get("mergedIntoId") and str(row["mergedIntoId"]) in rows:
                current = str(row["mergedIntoId"])
                continue
            if row.get("deletedAt"):
                gone.add(session_id)
            break
        else:
            gone.add(session_id)
    return gone


def _media_reason(media: dict[str, Any] | None, recording: dict[str, Any] | None) -> tuple[str, str | None] | None:
    if media is None:
        return contract.DELETED, None
    if recording is not None and recording.get("removedAt"):
        if recording.get("successor"):
            return contract.REPLACED, str(recording["successor"])
        return contract.DELETED, None
    if media.get("removedAt"):
        return contract.DELETED, None
    status = str(media.get("status") or "")
    if status == "REJECTED":
        return contract.DELETED, None
    if status == "DRAFT":
        return contract.UNLISTED, None
    return None


async def gone_reasons(
    client: Any, adapter: Any, stream: str, live: dict[str, dict[str, Any]]
) -> dict[str, tuple[str, str | None]]:
    ids = sorted(live)
    if not ids:
        return {}
    media = {str(r["id"]): r for r in await _query(client, MEDIA_SQL, ids)}
    recordings = {}
    if stream == contract.TRANSCRIPTS:
        recordings = {str(r["mediaFileId"]): r for r in await _query(client, RECORDING_SQL, ids)}
    payloads = {i: (live[i].get("payload") or {}) for i in ids}
    facts = await _gone_facts(client, adapter, stream, payloads)
    out: dict[str, tuple[str, str | None]] = {}
    subjects: dict[str, Subjects] = {}
    for item_id in ids:
        payload = payloads[item_id]
        found = _media_reason(media.get(item_id), recordings.get(item_id))
        session_id = (payload.get("session") or {}).get("id")
        artisans = _people(payload)[0]
        if found is None and session_id and str(session_id) in facts.sessions_gone:
            found = (contract.DELETED, None)
        if found is None and _deleted(stream, payload, facts):
            found = (contract.DELETED, None)
        resolved = facts.resolved
        if found is None and any(
            resolved[a] not in facts.artisans_present for a in artisans if a in resolved and a not in _merged(payload)
        ):
            found = (contract.DELETED, None)
        if found is not None:
            out[item_id] = found
            continue
        subjects[item_id] = _subjects(item_id, payload, live[item_id].get("subjects"), facts.survivors)
    refused = await items.refused_items(client, adapter, subjects)
    if stream == contract.TRANSCRIPTS and subjects:
        from app.questionnaire import ports

        details = await ports.consent_gate.verdict_details(sorted(subjects), client=client)
        refused |= {m for m in subjects if (details.get(m) or {}).get("verdict") == "REFUSED"}
    for item_id in refused:
        out[item_id] = (contract.WITHDRAWN, None)
    return out


def _merged(payload: dict[str, Any]) -> set[str]:
    return {str(m) for person in payload.get("people") or [] for m in person.get("mergedIds") or []}


async def gone_pass(client: Any, adapter: Any, streams: Iterable[str] = contract.STREAMS) -> dict[str, int]:
    await ledger.sync_point(client)
    written: dict[str, int] = {}
    for stream in streams:
        live = await ledger.live_rows(client, stream)
        reasons = await gone_reasons(client, adapter, stream, live)
        outcomes = [
            Outcome(item_id=item_id, reason=reason, successor=successor)
            for item_id, (reason, successor) in sorted(reasons.items())
        ]
        written[stream] = await ledger.write_outcomes(client, stream, outcomes)
    return written
