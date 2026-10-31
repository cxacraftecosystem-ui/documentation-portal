"""The transcript pipeline's Prisma half: versions, turns, speakers, spend, glossary, context reads.

IDENTICAL IN BOTH PORTALS (listed in ``shared/transcription.sha256``). It reads only tables both
portals have: MediaFile, Artisan, User, Craft, Location, Workshop/WorkshopCraft, the questionnaire
interview and recording tables, SttUsage and the tables of migration
``20261110090000_transcript_turns``. Whatever only one portal has is reached through
``services/transcript_portal.py`` (one file per portal).

Nothing here calls a provider, and nothing here logs transcript text.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.transcription.transcript import insight
from app.transcription.transcript.turns import Turn, languages_by_share, speaker_order
from prisma import Json

logger = logging.getLogger(__name__)

PENDING = "PENDING"
TRANSLATED = "TRANSLATED"
ALREADY_ENGLISH = "ALREADY_ENGLISH"
STALE = "STALE"
FAILED = "FAILED"

MACHINE = "MACHINE"
EDITED = "EDITED"


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


# --- Versions and turns -----------------------------------------------------------------------


async def current_version(media_id: str) -> Any | None:
    return await db.transcriptversion.find_first(
        where={"mediaFileId": media_id, "supersededAt": None}, order={"version": "desc"}
    )


async def current_versions(media_ids: list[str]) -> dict[str, Any]:
    if not media_ids:
        return {}
    rows = await db.transcriptversion.find_many(
        where={"mediaFileId": {"in": media_ids}, "supersededAt": None}, order={"version": "desc"}
    )
    out: dict[str, Any] = {}
    for row in rows:
        out.setdefault(row.mediaFileId, row)
    return out


async def version_number(media_id: str, number: int) -> Any | None:
    return await db.transcriptversion.find_unique(
        where={"mediaFileId_version": {"mediaFileId": media_id, "version": number}}
    )


async def versions(media_id: str) -> list[Any]:
    return await db.transcriptversion.find_many(
        where={"mediaFileId": media_id}, order={"version": "desc"}
    )


async def turns_of(version_id: str) -> list[Any]:
    return await db.transcriptturn.find_many(
        where={"versionId": version_id}, order={"position": "asc"}
    )


async def turn(turn_id: str) -> Any | None:
    return await db.transcriptturn.find_unique(where={"id": turn_id})


async def stt_cost_for(media_id: str, since: datetime | None = None) -> float:
    """What the newest successful speech-to-text call for this recording cost."""
    where: dict[str, Any] = {"mediaFileId": media_id, "cacheHit": False, "outcome": "COMPLETED"}
    if since is not None:
        where["createdAt"] = {"gte": since}
    row = await db.sttusage.find_first(where=where, order={"createdAt": "desc"})
    return float(row.costUsd or 0.0) if row is not None else 0.0


async def create_version(
    media_id: str,
    turns: list[Turn],
    *,
    stt_provider: str | None,
    stt_model: str | None,
    detected: str | None,
    duration_ms: int | None,
    keyterm_count: int = 0,
    stt_cost_usd: float = 0.0,
) -> Any:
    """A new version with its turns; the previous current version is superseded afterwards, so a
    crash between the two leaves two current rows (readers take the higher) rather than none."""
    previous = await db.transcriptversion.find_first(
        where={"mediaFileId": media_id}, order={"version": "desc"}
    )
    number = (previous.version if previous else 0) + 1
    end = max((t.end_ms for t in turns), default=0)
    row = await db.transcriptversion.create(
        data={
            "mediaFileId": media_id,
            "version": number,
            "sttProvider": stt_provider,
            "sttModel": stt_model,
            "detectedLanguage": detected,
            "languages": languages_by_share(turns),
            "durationMs": duration_ms or (end or None),
            "speakerCount": len(speaker_order(turns)),
            "turnCount": len(turns),
            "keytermCount": keyterm_count,
            "sttCostUsd": float(stt_cost_usd or 0.0),
            "translationStatus": "NOT_NEEDED"
            if turns and all(t.language == "en" for t in turns)
            else PENDING,
        }
    )
    if turns:
        await db.transcriptturn.create_many(
            data=[
                {
                    "versionId": row.id,
                    "mediaFileId": media_id,
                    "position": t.index,
                    "speakerKey": t.speaker_key,
                    "startMs": t.start_ms,
                    "endMs": t.end_ms,
                    "language": t.language or "und",
                    "machineText": t.text,
                    "words": Json(t.words_json()),
                    "lowConfidence": Json(t.low_confidence),
                    "machineEnglish": t.text if t.language == "en" else None,
                    "translationStatus": ALREADY_ENGLISH if t.language == "en" else PENDING,
                }
                for t in turns
            ]
        )
    now = datetime.now(UTC)
    await db.transcriptversion.update_many(
        where={"mediaFileId": media_id, "supersededAt": None, "version": {"lt": number}},
        data={"supersededAt": now},
    )
    return row


async def write_translations(
    version_id: str, outcomes: list[tuple[str, str, str | None]], model: str | None
) -> None:
    """``(turnId, status, english)`` for each turn, in one statement. An English a researcher
    wrote is never overwritten: only the machine column changes."""
    if not outcomes:
        return
    await db.execute_raw(
        'UPDATE "TranscriptTurn" t SET "machineEnglish" = COALESCE(x.english, t."machineEnglish"), '
        '"translationStatus" = x.status, "translationModel" = $5, "updatedAt" = now() '
        "FROM unnest($1::text[], $2::text[], $3::text[]) AS x(id, status, english) "
        'WHERE t."id" = x.id AND t."versionId" = $4',
        [o[0] for o in outcomes],
        [o[1] for o in outcomes],
        [o[2] for o in outcomes],
        version_id,
        model,
    )


async def finish_translation(
    version_id: str, *, status: str, model: str | None, provider: str | None, cost_usd: float, prompt_version: str
) -> None:
    await db.execute_raw(
        'UPDATE "TranscriptVersion" SET "translationStatus" = $2, "translationModel" = $3, '
        '"translationProvider" = $4, "translationCostUsd" = "translationCostUsd" + $5, '
        '"promptVersion" = $6, "translatedAt" = now(), "updatedAt" = now() WHERE "id" = $1',
        version_id,
        status,
        model,
        provider,
        float(cost_usd or 0.0),
        prompt_version,
    )


async def bump_revision(version_id: str) -> int:
    rows = await db.query_raw(
        'UPDATE "TranscriptVersion" SET "revision" = "revision" + 1, "updatedAt" = now() '
        'WHERE "id" = $1 RETURNING "revision"',
        version_id,
    )
    return int(rows[0]["revision"]) if rows else 0


async def add_edit(turn_id: str, field: str, source: str, text: str, actor_id: str | None) -> None:
    await db.transcriptturnedit.create(
        data={"turnId": turn_id, "field": field, "source": source, "text": text, "actorId": actor_id}
    )


async def history(turn_id: str) -> list[Any]:
    return await db.transcriptturnedit.find_many(
        where={"turnId": turn_id}, order={"createdAt": "asc"}
    )


# --- Speakers ---------------------------------------------------------------------------------


async def speakers_of(media_id: str) -> dict[str, Any]:
    rows = await db.transcriptspeaker.find_many(where={"mediaFileId": media_id})
    return {row.speakerKey: row for row in rows}


async def set_speaker(
    media_id: str, key: str, name: str | None, role: str | None, person_id: str | None, by: str | None
) -> None:
    if not name:
        await db.transcriptspeaker.delete_many(where={"mediaFileId": media_id, "speakerKey": key})
        return
    fields = {"name": name, "role": role, "personId": person_id, "setById": by}
    await db.transcriptspeaker.upsert(
        where={"mediaFileId_speakerKey": {"mediaFileId": media_id, "speakerKey": key}},
        data={"create": {"mediaFileId": media_id, "speakerKey": key, **fields}, "update": fields},
    )


# --- Spend and the budget meter ---------------------------------------------------------------


async def record_spend(
    *,
    media_id: str | None,
    kind: str,
    model: str | None,
    key_ref: str | None,
    input_tokens: int,
    output_tokens: int,
    cost_usd: float,
    outcome: str = "COMPLETED",
) -> None:
    try:
        await db.transcriptspend.create(
            data={
                "mediaFileId": media_id,
                "kind": kind,
                "provider": "gemini",
                "model": model,
                "keyRef": key_ref,
                "inputTokens": int(input_tokens or 0),
                "outputTokens": int(output_tokens or 0),
                "costUsd": float(cost_usd or 0.0),
                "outcome": outcome,
            }
        )
    except Exception as exc:  # noqa: BLE001 - a lost ledger row never costs the work it paid for
        logger.warning("could not record transcript spend (%s)", type(exc).__name__)


async def month_spend(now: datetime | None = None) -> tuple[float, float]:
    """``(speech-to-text USD, Gemini USD)`` since the start of this India-time month."""
    since = insight.month_start(now).replace(tzinfo=None).isoformat()
    stt = await db.query_raw(
        'SELECT COALESCE(SUM("costUsd"), 0)::float8 AS usd FROM "SttUsage" '
        'WHERE "createdAt" >= $1::timestamp AND "cacheHit" = false',
        since,
    )
    gem = await db.query_raw(
        'SELECT COALESCE(SUM("costUsd"), 0)::float8 AS usd FROM "TranscriptSpend" '
        'WHERE "createdAt" >= $1::timestamp',
        since,
    )
    return float((stt[0] if stt else {}).get("usd") or 0.0), float((gem[0] if gem else {}).get("usd") or 0.0)


async def media_cost(media_id: str) -> tuple[float, float]:
    stt = await db.query_raw(
        'SELECT COALESCE(SUM("costUsd"), 0)::float8 AS usd FROM "SttUsage" '
        'WHERE "mediaFileId" = $1 AND "cacheHit" = false',
        media_id,
    )
    gem = await db.query_raw(
        'SELECT COALESCE(SUM("costUsd"), 0)::float8 AS usd FROM "TranscriptSpend" WHERE "mediaFileId" = $1',
        media_id,
    )
    return float((stt[0] if stt else {}).get("usd") or 0.0), float((gem[0] if gem else {}).get("usd") or 0.0)


# --- The glossary -----------------------------------------------------------------------------


async def glossary_for(craft_ids: list[str], limit: int = 200) -> list[dict[str, str]]:
    """Terms already heard for these crafts (most heard first), for the prompt and the key terms."""
    scopes = [c for c in craft_ids if c] or [""]
    rows = await db.transcriptglossaryterm.find_many(
        where={"scopeKey": {"in": scopes}}, order={"occurrences": "desc"}, take=limit
    )
    return [{"term": r.term, "gloss": r.gloss or ""} for r in rows]


async def add_sightings(
    media_id: str, craft_id: str | None, sightings: list[insight.Sighting], crafts: list[tuple[str, str]]
) -> int:
    """One glossary row per (craft, term), one sighting per (term, recording). Returns new sightings."""
    added = 0
    now = datetime.now(UTC)
    scope = craft_id or ""
    for s in sightings:
        key = insight.term_key(s.term)
        if not key:
            continue
        row = await db.transcriptglossaryterm.upsert(
            where={"scopeKey_termKey": {"scopeKey": scope, "termKey": key}},
            data={
                "create": {
                    "scopeKey": scope,
                    "craftId": craft_id,
                    "termKey": key,
                    "term": s.term,
                    "gloss": s.gloss or None,
                    "linkedCraftId": insight.match_craft(s.term, crafts),
                    "lastHeardAt": now,
                },
                "update": {"lastHeardAt": now},
            },
        )
        if not row.gloss and s.gloss:
            await db.transcriptglossaryterm.update(where={"id": row.id}, data={"gloss": s.gloss})
        existing = await db.transcriptglossarysighting.find_unique(
            where={"termId_mediaFileId": {"termId": row.id, "mediaFileId": media_id}}
        )
        if existing is not None:
            continue
        await db.transcriptglossarysighting.create(
            data={"termId": row.id, "mediaFileId": media_id, "turnId": s.turn_id, "startMs": s.start_ms}
        )
        await db.transcriptglossaryterm.update(
            where={"id": row.id}, data={"occurrences": {"increment": 1}}
        )
        added += 1
    return added


# --- Context: who is in the recording, which craft, where -------------------------------------


async def recording_context(media: Any) -> dict[str, Any]:
    """Names, crafts and states the recording is about, from the tables both portals share.

    ``{artisans: [{id, name}], researchers: [{id, name}], uploader: {id, name}|None,
    crafts: [{id, name}], states: [str], places: {district, state}, interviewId, recordingId}``
    """
    media_id = str(_value(media, "id"))
    out: dict[str, Any] = {
        "artisans": [],
        "researchers": [],
        "uploader": None,
        "crafts": [],
        "states": [],
        "district": None,
        "interviewId": _value(media, "questionnaireInterviewId"),
        "recordingId": None,
    }

    def add(kind: str, row_id: Any, name: Any) -> None:
        if not row_id or not name or not str(name).strip():
            return
        if all(entry["id"] != str(row_id) for entry in out[kind]):
            out[kind].append({"id": str(row_id), "name": " ".join(str(name).split())})

    try:
        rec = await db.query_raw(
            'SELECT r."id", r."languageCode" FROM "QuestionnaireRecording" r WHERE r."mediaFileId" = $1',
            media_id,
        )
        if rec:
            out["recordingId"] = str(rec[0]["id"])
            out["recordingLanguage"] = rec[0].get("languageCode")
            for row in await db.query_raw(
                'SELECT a."id", a."name", a."craftId" FROM "QuestionnaireRecordingSubject" s '
                'JOIN "Artisan" a ON a."id" = s."artisanId" WHERE s."recordingId" = $1 '
                'ORDER BY (s."role"::text = \'ANSWERED\') DESC, a."name"',
                out["recordingId"],
            ):
                add("artisans", row["id"], row["name"])
                if row.get("craftId"):
                    out.setdefault("_craftIds", []).append(row["craftId"])
        if _value(media, "artisanId"):
            for row in await db.query_raw(
                'SELECT "id", "name", "craftId" FROM "Artisan" WHERE "id" = $1', _value(media, "artisanId")
            ):
                add("artisans", row["id"], row["name"])
                if row.get("craftId"):
                    out.setdefault("_craftIds", []).append(row["craftId"])
        if out["interviewId"]:
            for row in await db.query_raw(
                'SELECT a."id", a."name", a."craftId" FROM "QuestionnaireInterviewArtisan" ia '
                'JOIN "Artisan" a ON a."id" = ia."artisanId" WHERE ia."interviewId" = $1 '
                'ORDER BY ia."sortOrder"',
                out["interviewId"],
            ):
                add("artisans", row["id"], row["name"])
                if row.get("craftId"):
                    out.setdefault("_craftIds", []).append(row["craftId"])
            for row in await db.query_raw(
                'SELECT u."id", u."name" FROM "QuestionnaireInterviewResearcher" ir '
                'JOIN "User" u ON u."id" = ir."userId" WHERE ir."interviewId" = $1',
                out["interviewId"],
            ):
                add("researchers", row["id"], row["name"])
        if _value(media, "uploadedById"):
            for row in await db.query_raw(
                'SELECT "id", "name" FROM "User" WHERE "id" = $1', _value(media, "uploadedById")
            ):
                out["uploader"] = {"id": str(row["id"]), "name": row["name"]}
        craft_ids = [c for c in [_value(media, "craftId"), *out.pop("_craftIds", [])] if c]
        if _value(media, "workshopId"):
            for row in await db.query_raw(
                'SELECT "craftId" FROM "WorkshopCraft" WHERE "workshopId" = $1', _value(media, "workshopId")
            ):
                craft_ids.insert(0, row["craftId"])
        if craft_ids:
            for row in await db.query_raw(
                'SELECT "id", "name" FROM "Craft" WHERE "id" = ANY($1::text[])', list(dict.fromkeys(craft_ids))
            ):
                add("crafts", row["id"], row["name"])
            order = {cid: i for i, cid in enumerate(dict.fromkeys(craft_ids))}
            out["crafts"].sort(key=lambda c: order.get(c["id"], 99))
        if _value(media, "locationId"):
            for row in await db.query_raw(
                'SELECT "state", "district" FROM "Location" WHERE "id" = $1', _value(media, "locationId")
            ):
                if row.get("state"):
                    out["states"].append(row["state"])
                out["district"] = row.get("district")
    except Exception as exc:  # noqa: BLE001 - context sharpens names and terms; never fails a job
        logger.warning("transcript context for media %s was partly unreadable (%s)", media_id, type(exc).__name__)
    return out


async def master_crafts() -> list[tuple[str, str]]:
    """``(id, name)`` of every active craft, in the master list's order where it has one."""
    rows = await db.query_raw(
        'SELECT "id", "name" FROM "Craft" WHERE "status"::text = \'ACTIVE\' ORDER BY "name"'
    )
    return [(str(r["id"]), str(r["name"])) for r in rows]
