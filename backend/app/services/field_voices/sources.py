from __future__ import annotations

import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta, timezone, tzinfo
from typing import Any

__all__ = ["Sources", "iso_moment", "ist_day", "load_sources", "plain_json"]

IST = timezone(timedelta(hours=5, minutes=30))
INDIA_ZONES = frozenset({"", "Asia/Kolkata", "Asia/Calcutta", "IST"})

MEDIA_SQL = (
    'SELECT m."id", m."mediaType"::text AS "mediaType", m."mimeType", m."sizeBytes"::text AS "sizeBytes", '
    'm."objectKey", m."originalFilename", m."caption", m."transcriptText", m."transcriptLanguage", '
    'm."transcriptEditedAt", m."linkedRecordType", m."linkedRecordId", m."status"::text AS "status", '
    'm."extraMetadata", m."recordedAt", m."recordedTimezone", m."createdAt", m."artisanId", m."craftId", '
    'm."workshopId", m."productId", m."toolId", m."questionnaireInterviewId", '
    'm."removedAt", m."contentSha256", m."shaVerifiedAt" FROM "MediaFile" m WHERE m."id" = ANY($1::text[])'
)
CRAFT_SQL = 'SELECT "id", "slug", "craftCode", "name", "mergedIntoId" FROM "Craft"'
PRODUCT_SQL = (
    'SELECT "id", "productName", "localName", "productType"::text AS "productType", "craftId", '
    '"artisanId", "workshopId", "measurementImageId" FROM "ProductDocumentation" WHERE "id" = ANY($1::text[])'
)
MEASURED_SQL = (
    'SELECT "id", "measurementImageId" FROM "ProductDocumentation" '
    'WHERE "measurementImageId" = ANY($1::text[]) ORDER BY "id"'
)
TOOL_SQL = (
    'SELECT "id", "toolkitName", "localName", "englishName", "craftId", "artisanId", "workshopId" '
    'FROM "ToolDocumentation" WHERE "id" = ANY($1::text[])'
)
TOOL_CRAFT_SQL = (
    'SELECT "toolId", "craftId" FROM "ToolCraft" WHERE "toolId" = ANY($1::text[]) '
    'ORDER BY "toolId", "createdAt", "craftId"'
)
TOOL_ARTISAN_SQL = (
    'SELECT "toolId", "artisanId" FROM "ToolArtisan" WHERE "toolId" = ANY($1::text[]) '
    'ORDER BY "toolId", "createdAt", "artisanId"'
)
PROCESS_SQL = (
    'SELECT "id", "name", "productId", "workshopId" FROM "Process" WHERE "id" = ANY($1::text[])'
)
STEP_SQL = (
    'SELECT "id", "processId", "name", "sortOrder" FROM "ProcessStep" WHERE "id" = ANY($1::text[])'
)
WORKSHOP_SQL = (
    'SELECT w."id", w."title", w."date", w."startDate", w."endDate", l."state", l."district" FROM "Workshop" w '
    'LEFT JOIN "Location" l ON l."id" = w."locationId" WHERE w."id" = ANY($1::text[])'
)
WORKSHOP_CRAFT_SQL = (
    'SELECT "workshopId", "craftId" FROM "WorkshopCraft" WHERE "workshopId" = ANY($1::text[]) '
    'ORDER BY "workshopId", "craftId"'
)
SESSION_SQL = (
    'SELECT i."id", i."title", i."sessionDate"::text AS "sessionDate", i."interviewDate", '
    'i."purpose"::text AS "purpose", i."workshopId", i."designWorkshopId", i."questionnaireId", '
    'i."deletedAt", i."mergedIntoId", q."title" AS "questionnaireTitle", l."state", l."district" '
    'FROM "QuestionnaireInterview" i '
    'LEFT JOIN "Questionnaire" q ON q."id" = i."questionnaireId" '
    'LEFT JOIN "Location" l ON l."id" = i."locationId" WHERE i."id" = ANY($1::text[])'
)
SESSION_ARTISAN_SQL = (
    'SELECT "interviewId", "artisanId" FROM "QuestionnaireInterviewArtisan" '
    'WHERE "interviewId" = ANY($1::text[]) ORDER BY "interviewId", "artisanId"'
)
EVIDENCE_SQL = (
    'SELECT DISTINCT "evidenceMediaId" FROM "Consent" WHERE "evidenceMediaId" = ANY($1::text[])'
)


def plain_json(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except ValueError:
            return None
    return value


def _moment(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, str) and value.strip():
        try:
            return _moment(datetime.fromisoformat(value.strip()))
        except ValueError:
            return None
    return None


def _zone(name: Any) -> tzinfo:
    text = str(name or "").strip()
    if text in INDIA_ZONES:
        return IST
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

    try:
        return ZoneInfo(text)
    except (ZoneInfoNotFoundError, ValueError):
        return IST


def iso_moment(value: Any, zone_name: Any = None) -> str | None:
    moment = _moment(value)
    if moment is None:
        return None
    return moment.astimezone(_zone(zone_name)).isoformat(timespec="seconds")


def ist_day(value: Any) -> date | None:
    if isinstance(value, date) and not isinstance(value, datetime):
        return value
    if isinstance(value, str) and len(value.strip()) == 10:
        try:
            return date.fromisoformat(value.strip())
        except ValueError:
            return None
    moment = _moment(value)
    return moment.astimezone(IST).date() if moment else None


def day_text(value: Any) -> str | None:
    found = ist_day(value)
    return found.isoformat() if found else None


def _by_id(rows: Iterable[Mapping[str, Any]], key: str = "id") -> dict[str, dict[str, Any]]:
    return {str(row[key]): dict(row) for row in rows}


def _grouped(rows: Iterable[Mapping[str, Any]], key: str, value: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for row in rows:
        if row.get(value):
            found = out.setdefault(str(row[key]), [])
            if str(row[value]) not in found:
                found.append(str(row[value]))
    return out


@dataclass
class Sources:
    crafts: dict[str, dict[str, Any]] = field(default_factory=dict)
    media: dict[str, dict[str, Any]] = field(default_factory=dict)
    products: dict[str, dict[str, Any]] = field(default_factory=dict)
    measured: dict[str, list[str]] = field(default_factory=dict)
    tools: dict[str, dict[str, Any]] = field(default_factory=dict)
    tool_crafts: dict[str, list[str]] = field(default_factory=dict)
    tool_artisans: dict[str, list[str]] = field(default_factory=dict)
    processes: dict[str, dict[str, Any]] = field(default_factory=dict)
    steps: dict[str, dict[str, Any]] = field(default_factory=dict)
    workshops: dict[str, dict[str, Any]] = field(default_factory=dict)
    workshop_crafts: dict[str, list[str]] = field(default_factory=dict)
    sessions: dict[str, dict[str, Any]] = field(default_factory=dict)
    session_artisans: dict[str, list[str]] = field(default_factory=dict)
    evidence: set[str] = field(default_factory=set)
    design_workshops: dict[str, dict[str, Any]] = field(default_factory=dict)
    media_design_workshops: dict[str, str] = field(default_factory=dict)

    def craft_end(self, craft_id: str | None) -> str | None:
        seen: set[str] = set()
        current = craft_id
        while current and current in self.crafts and current not in seen:
            seen.add(current)
            target = self.crafts[current].get("mergedIntoId")
            if not target or target == current or target not in self.crafts:
                return current
            current = str(target)
        return None

    def craft_object(self, craft_id: str) -> dict[str, Any]:
        row = self.crafts[craft_id]
        return {"id": row["id"], "slug": row["slug"], "code": row.get("craftCode"), "name": row["name"]}

    def session_end(self, session_id: str | None) -> dict[str, Any] | None:
        seen: set[str] = set()
        current = session_id
        while current and current in self.sessions and current not in seen:
            seen.add(current)
            row = self.sessions[current]
            target = row.get("mergedIntoId")
            if not target or target not in self.sessions:
                return row
            current = str(target)
        return None


async def load_crafts(client: Any) -> dict[str, dict[str, Any]]:
    return _by_id(await client.query_raw(CRAFT_SQL))


async def _rows(client: Any, sql: str, ids: Iterable[str]) -> list[dict[str, Any]]:
    wanted = sorted({str(i) for i in ids if i})
    if not wanted:
        return []
    return [dict(row) for row in await client.query_raw(sql, wanted)]


def _linked(media: Iterable[Mapping[str, Any]], kind: str) -> set[str]:
    return {
        str(m["linkedRecordId"])
        for m in media
        if str(m.get("linkedRecordType") or "").lower() == kind and m.get("linkedRecordId")
    }


async def load_sources(
    client: Any,
    adapter: Any,
    media_ids: Iterable[str],
    *,
    crafts: dict[str, dict[str, Any]],
    extra_products: Iterable[str] = (),
    extra_tools: Iterable[str] = (),
    extra_processes: Iterable[str] = (),
    extra_design_workshops: Iterable[str] = (),
) -> Sources:
    found = Sources(crafts=crafts)
    found.media = _by_id(await _rows(client, MEDIA_SQL, media_ids))
    media = list(found.media.values())
    for row in await _rows(client, MEASURED_SQL, found.media):
        found.measured.setdefault(str(row["measurementImageId"]), []).append(str(row["id"]))
    found.steps = _by_id(await _rows(client, STEP_SQL, _linked(media, "processstep")))
    process_ids = _linked(media, "process") | {s["processId"] for s in found.steps.values()}
    found.processes = _by_id(await _rows(client, PROCESS_SQL, process_ids | set(extra_processes)))
    product_ids = {m["productId"] for m in media if m.get("productId")} | _linked(media, "product")
    product_ids |= {p["productId"] for p in found.processes.values() if p.get("productId")}
    product_ids |= {pid for pids in found.measured.values() for pid in pids} | set(extra_products)
    found.products = _by_id(await _rows(client, PRODUCT_SQL, product_ids))
    tool_ids = {m["toolId"] for m in media if m.get("toolId")} | _linked(media, "tool") | set(extra_tools)
    found.tools = _by_id(await _rows(client, TOOL_SQL, tool_ids))
    found.tool_crafts = _grouped(await _rows(client, TOOL_CRAFT_SQL, found.tools), "toolId", "craftId")
    found.tool_artisans = _grouped(await _rows(client, TOOL_ARTISAN_SQL, found.tools), "toolId", "artisanId")
    session_ids = {m["questionnaireInterviewId"] for m in media if m.get("questionnaireInterviewId")}
    sessions = _by_id(await _rows(client, SESSION_SQL, session_ids))
    merged = {s["mergedIntoId"] for s in sessions.values() if s.get("mergedIntoId")} - set(sessions)
    while merged:
        more = _by_id(await _rows(client, SESSION_SQL, merged))
        sessions.update(more)
        merged = {s["mergedIntoId"] for s in more.values() if s.get("mergedIntoId")} - set(sessions)
    found.sessions = sessions
    found.session_artisans = _grouped(
        await _rows(client, SESSION_ARTISAN_SQL, sessions), "interviewId", "artisanId"
    )
    workshop_ids = {m["workshopId"] for m in media if m.get("workshopId")} | _linked(media, "workshop")
    workshop_ids |= {s["workshopId"] for s in sessions.values() if s.get("workshopId")}
    for record in (*found.products.values(), *found.tools.values(), *found.processes.values()):
        if record.get("workshopId"):
            workshop_ids.add(record["workshopId"])
    found.workshops = _by_id(await _rows(client, WORKSHOP_SQL, workshop_ids))
    found.workshop_crafts = _grouped(
        await _rows(client, WORKSHOP_CRAFT_SQL, found.workshops), "workshopId", "craftId"
    )
    found.evidence = {
        str(row["evidenceMediaId"]) for row in await _rows(client, EVIDENCE_SQL, found.media)
    }
    found.media_design_workshops = await adapter.media_design_workshops(client, found)
    design_ids = set(found.media_design_workshops.values()) | set(extra_design_workshops)
    found.design_workshops = await adapter.design_workshops(client, design_ids)
    return found
