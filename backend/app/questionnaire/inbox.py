"""The researcher's inbox (DESIGN 4.7): everything that needs a person, never a silent guess.

Each section is scoped to the caller: their own uploads, and the sessions they created or are a
researcher on. Admins see every row.

Every decision row names what its buttons act on (audit H13, H14): ``otherRecordingId`` for
"Keep both / Keep one" (``POST /recordings/{id}/decide``), ``part2OfRecordingId`` for "Part 2"
(``CONTINUATION``), and ``candidate`` / ``candidates`` for "File it" (``POST /recordings/{id}/file``,
or ``POST /questionnaire/media/{mediaId}/file`` for a clip that has no recording yet).
"""

from __future__ import annotations

from collections import defaultdict
from datetime import timedelta
from typing import Any

from app.questionnaire import ports
from app.questionnaire.ports import is_admin
from app.questionnaire.common import as_date, aware, iso, now

LEGACY_TYPES = ("questionnaire", "questionnaireinterview", "questionnaire_session")


def _db(client: Any) -> Any:
    if client is not None:
        return client
    db = ports.db()

    return db


def _mine_sql(alias: str, param: str) -> str:
    return (
        f'({alias}."createdById" = {param} OR EXISTS (SELECT 1 FROM "QuestionnaireInterviewResearcher" rr '
        f'WHERE rr."interviewId" = {alias}."id" AND rr."userId" = {param}))'
    )


async def inbox(user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    admin = is_admin(user)
    uid = str(user.id)
    # An admin sees every row; "$1 IS NOT NULL" keeps the parameter referenced (Postgres refuses an
    # unused one it cannot type).
    everything = "$1::text IS NOT NULL"
    scope_media = everything if admin else 'm."uploadedById" = $1'
    scope_session = everything if admin else _mine_sql("i", "$1")

    to_file = await db.query_raw(
        'SELECT m."id", m."originalFilename", m."createdAt", m."extraMetadata"->\'questionnaireV2\'->\'candidate\' AS candidate, '
        'm."questionnaireInterviewId" AS "sessionId" FROM "MediaFile" m '
        'WHERE m."removedAt" IS NULL AND m."mediaType" IN (\'AUDIO\', \'VIDEO\') AND ' + scope_media + ' '
        'AND (lower(m."linkedRecordType") = ANY($2::text[]) OR m."questionnaireInterviewId" IS NOT NULL '
        "     OR m.\"extraMetadata\"->'questionnaireV2'->>'toFile' = 'true') "
        'AND NOT EXISTS (SELECT 1 FROM "QuestionnaireRecording" r WHERE r."mediaFileId" = m."id") '
        "AND m.\"extraMetadata\"->'questionnaireV2'->'notNeeded' IS NULL "
        'ORDER BY m."createdAt" LIMIT 200',
        uid, list(LEGACY_TYPES),
    )
    decisions = await db.query_raw(
        'SELECT r."id", r."decisionKind", r."capturedAt", r."createdAt", m."questionnaireInterviewId" AS "sessionId", '
        'm."extraMetadata"->\'questionnaireV2\'->\'candidates\' AS candidates, '
        'm."extraMetadata"->\'questionnaireV2\'->>\'twinRecordingId\' AS twin, '
        '(SELECT string_agg(t."targetKey", \',\') FROM "QuestionnaireRecordingTarget" t WHERE t."recordingId" = r."id") AS targets, '
        '(SELECT string_agg(a."name", \', \') FROM "QuestionnaireRecordingSubject" s JOIN "Artisan" a ON a."id" = s."artisanId" '
        '  WHERE s."recordingId" = r."id" AND s."role" = \'ANSWERED\') AS names '
        'FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        'WHERE r."removedAt" IS NULL AND m."removedAt" IS NULL AND r."filing" = \'NEEDS_DECISION\' '
        'AND (' + (everything if admin else 'r."recordedById" = $1 OR ' + _mine_sql("i", "$1")) + ') '
        'ORDER BY r."capturedAt" LIMIT 200',
        uid,
    )
    others = await _other_takes(db, [str(d["id"]) for d in decisions])
    part2 = await _part2_recordings(db, decisions)
    set_dates = await db.query_raw(
        'SELECT i."id", i."workshopId", i."sessionDate", i."createdAt", w."title" AS workshop FROM "QuestionnaireInterview" i '
        'LEFT JOIN "Workshop" w ON w."id" = i."workshopId" WHERE i."mergedIntoId" IS NULL AND i."origin" = \'LEGACY_RECONSTRUCTED\' '
        'AND (i."sessionDateEstimated" OR i."sessionDate" IS NULL) AND ' + scope_session + ' ORDER BY i."sessionDate" NULLS FIRST',
        uid,
    )
    no_participants = await db.query_raw(
        'SELECT i."id", i."sessionDate", i."title", i."createdAt" FROM "QuestionnaireInterview" i WHERE i."mergedIntoId" IS NULL '
        'AND NOT EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" x WHERE x."interviewId" = i."id") AND ' + scope_session
        + ' ORDER BY i."createdAt" LIMIT 200',
        uid,
    )
    empty = await db.query_raw(
        'SELECT i."id", i."sessionDate", i."title", i."reviewedAt", i."createdAt" FROM "QuestionnaireInterview" i '
        'WHERE i."mergedIntoId" IS NULL AND i."status" = \'APPROVED\' '
        'AND NOT EXISTS (SELECT 1 FROM "MediaFile" m WHERE m."questionnaireInterviewId" = i."id" AND m."removedAt" IS NULL) '
        'AND NOT EXISTS (SELECT 1 FROM "QuestionnaireAnswerRevision" v WHERE v."sessionId" = i."id") '
        'AND NOT EXISTS (SELECT 1 FROM "QuestionnaireResponse" q WHERE q."interviewId" = i."id") AND ' + scope_session
        + ' ORDER BY i."createdAt" LIMIT 200',
        uid,
    )
    primary = await db.query_raw(
        'SELECT e."id", e."recordingId", e."after", e."at" FROM "QuestionnaireRecordingEvent" e '
        'JOIN "QuestionnaireRecording" r ON r."id" = e."recordingId" JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        # The affected researchers (DESIGN 3.5): those of the take that is primary now AND those of
        # the take that stopped being primary, which a late arrival from another session demotes.
        'WHERE e."kind" = \'PRIMARY_CHANGED\' AND e."at" > now() - interval \'30 days\' AND ('
        + (everything if admin else _mine_sql("i", "$1") + ' OR EXISTS (SELECT 1 FROM "QuestionnaireRecording" fr '
           'JOIN "MediaFile" fm ON fm."id" = fr."mediaFileId" JOIN "QuestionnaireInterview" fi ON fi."id" = fm."questionnaireInterviewId" '
           'WHERE fr."id" = e."after"->>\'from\' AND ' + _mine_sql("fi", "$1") + ')') + ') '
        'ORDER BY e."at" DESC LIMIT 100',
        uid,
    )

    legacy = [d for d in decisions if d.get("decisionKind") == "UNRESOLVED_LEGACY"]
    needs = [d for d in decisions if d.get("decisionKind") != "UNRESOLVED_LEGACY"]
    # "Set dates" is one row per workshop DAY (audit Low): the sessions an estimated date put on one
    # day are fixed together, never a whole workshop's worth at once.
    by_day: dict[tuple[Any, Any, Any], list] = defaultdict(list)
    for s in set_dates:
        day = as_date(s.get("sessionDate"))
        by_day[(s.get("workshopId"), s.get("workshop"), day.isoformat() if day else None)].append(s)
    out = {
        "toFile": [
            {"mediaId": str(m["id"]), "name": m.get("originalFilename"), "date": iso(m.get("createdAt")),
             "sessionId": m.get("sessionId"), "candidate": m.get("candidate")}
            for m in to_file
        ],
        "needsDecision": [
            {"recordingId": str(d["id"]), "sessionId": d.get("sessionId"), "decisionKind": d.get("decisionKind"),
             "targets": (d.get("targets") or "").split(",") if d.get("targets") else [], "artisanNames": d.get("names"),
             "capturedAt": iso(d.get("capturedAt")),
             "otherRecordingId": d.get("twin") or (others.get(str(d["id"])) or [None])[0],
             "otherRecordingIds": [d["twin"]] if d.get("twin") else others.get(str(d["id"]), []),
             "part2OfRecordingId": part2.get(str(d["id"]))}
            for d in needs
        ],
        "legacy": [
            {"recordingId": str(d["id"]), "sessionId": d.get("sessionId"), "candidates": _list(d.get("candidates")),
             "candidate": next((c for c in _list(d.get("candidates")) if isinstance(c, dict) and not c.get("part2Of")), None),
             "capturedAt": iso(d.get("capturedAt")),
             "otherRecordingId": (others.get(str(d["id"])) or [None])[0],
             "part2OfRecordingId": part2.get(str(d["id"]))}
            for d in legacy
        ],
        "setDates": [
            {"workshopId": wid, "workshop": name, "day": day, "sessionIds": [str(s["id"]) for s in rows],
             "from": day, "to": day, "count": len(rows)}
            for (wid, name, day), rows in by_day.items()
        ],
        "noParticipants": [{"sessionId": str(s["id"]), "title": s.get("title"), "date": iso(s.get("sessionDate"))} for s in no_participants],
        "emptySessions": [{"sessionId": str(s["id"]), "title": s.get("title"), "approvedAt": iso(s.get("reviewedAt"))} for s in empty],
        "primaryChanged": [{"eventId": str(e["id"]), "recordingId": str(e["recordingId"]), "change": e.get("after"), "at": iso(e.get("at"))} for e in primary],
    }
    cutoff = now() - timedelta(hours=24)
    stamps = [m.get("createdAt") for m in to_file] + [d.get("createdAt") for d in decisions] + [s.get("createdAt") for s in no_participants]

    total = sum(len(v) for k, v in out.items() if isinstance(v, list))
    out["counts"] = {"total": total, "olderThan24h": sum(1 for s in stamps if s and aware(s) < cutoff)}
    return out


def _list(value: Any) -> list[Any]:
    if isinstance(value, str):
        import json

        try:
            value = json.loads(value)
        except ValueError:
            return []
    return list(value) if isinstance(value, list) else []


async def _other_takes(db: Any, recording_ids: list[str]) -> dict[str, list[str]]:
    """recordingId -> the other live takes "Keep one" chooses between: same session, a shared target
    and a shared ANSWERED artisan, earliest first."""
    if not recording_ids:
        return {}
    rows = await db.query_raw(
        'SELECT DISTINCT ON (r."id", o."id") r."id" AS rid, o."id" AS oid, o."capturedAt" FROM "QuestionnaireRecording" r '
        'JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        'JOIN "QuestionnaireInterview" si ON si."id" = m."questionnaireInterviewId" '
        'JOIN "QuestionnaireRecordingTarget" t ON t."recordingId" = r."id" '
        'JOIN "QuestionnaireRecordingTarget" ot ON ot."targetKey" = t."targetKey" AND ot."recordingId" <> r."id" '
        'JOIN "QuestionnaireRecording" o ON o."id" = ot."recordingId" AND o."removedAt" IS NULL '
        'JOIN "MediaFile" om ON om."id" = o."mediaFileId" AND om."removedAt" IS NULL '
        'JOIN "QuestionnaireInterview" oi ON oi."id" = om."questionnaireInterviewId" '
        # The same session, or (DESIGN 3.3 rule 3) a CONCURRENT take's twin in another session of the
        # same day, captured within the same two hours: "Keep one" must be able to name it.
        '  AND (om."questionnaireInterviewId" = m."questionnaireInterviewId" OR (r."decisionKind" = \'CONCURRENT\' '
        '       AND oi."sessionDate" = si."sessionDate" AND abs(extract(epoch FROM o."capturedAt" - r."capturedAt")) <= 7200)) '
        'WHERE r."id" = ANY($1::text[]) AND EXISTS (SELECT 1 FROM "QuestionnaireRecordingSubject" s '
        '  JOIN "QuestionnaireRecordingSubject" os ON os."artisanId" = s."artisanId" AND os."recordingId" = o."id" '
        "  WHERE s.\"recordingId\" = r.\"id\" AND s.\"role\" = 'ANSWERED' AND os.\"role\" = 'ANSWERED') "
        'ORDER BY r."id", o."id"',
        recording_ids,
    )
    out: dict[str, list[tuple[Any, str]]] = defaultdict(list)
    for r in rows:
        out[str(r["rid"])].append((aware(r["capturedAt"]), str(r["oid"])))
    return {rid: [oid for _at, oid in sorted(pairs)] for rid, pairs in out.items()}


async def _part2_recordings(db: Any, decisions: list[dict[str, Any]]) -> dict[str, str]:
    """recordingId -> the recording a "Part 2" candidate continues (the backfill names its file)."""
    wanted: dict[str, str] = {}
    for d in decisions:
        for c in _list(d.get("candidates")):
            if isinstance(c, dict) and c.get("part2Of"):
                wanted[str(d["id"])] = str(c["part2Of"])
    if not wanted:
        return {}
    rows = await db.query_raw(
        'SELECT "id", "mediaFileId" FROM "QuestionnaireRecording" WHERE "mediaFileId" = ANY($1::text[])', sorted(set(wanted.values()))
    )
    by_media = {str(r["mediaFileId"]): str(r["id"]) for r in rows}
    return {rid: by_media[mid] for rid, mid in wanted.items() if mid in by_media}
