"""The researcher's inbox (DESIGN 4.7): everything that needs a person, never a silent guess.

Each section is scoped to the caller: their own uploads, and the sessions they created or are a
researcher on. Admins see every row.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import timedelta
from typing import Any

from app.core.deps import is_admin
from app.services.questionnaire_v2.common import as_date, iso, now

LEGACY_TYPES = ("questionnaire", "questionnaireinterview", "questionnaire_session")


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

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
        'ORDER BY m."createdAt" LIMIT 200',
        uid, list(LEGACY_TYPES),
    )
    decisions = await db.query_raw(
        'SELECT r."id", r."decisionKind", r."capturedAt", r."createdAt", m."questionnaireInterviewId" AS "sessionId", '
        'm."extraMetadata"->\'questionnaireV2\'->\'candidates\' AS candidates, '
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
        'WHERE e."kind" = \'PRIMARY_CHANGED\' AND e."at" > now() - interval \'30 days\' AND (' + (everything if admin else _mine_sql("i", "$1")) + ') '
        'ORDER BY e."at" DESC LIMIT 100',
        uid,
    )

    legacy = [d for d in decisions if d.get("decisionKind") == "UNRESOLVED_LEGACY"]
    needs = [d for d in decisions if d.get("decisionKind") != "UNRESOLVED_LEGACY"]
    by_day: dict[tuple[Any, Any], list] = defaultdict(list)
    for s in set_dates:
        by_day[(s.get("workshopId"), s.get("workshop"))].append(s)
    out = {
        "toFile": [
            {"mediaId": str(m["id"]), "name": m.get("originalFilename"), "date": iso(m.get("createdAt")),
             "sessionId": m.get("sessionId"), "candidate": m.get("candidate")}
            for m in to_file
        ],
        "needsDecision": [
            {"recordingId": str(d["id"]), "sessionId": d.get("sessionId"), "decisionKind": d.get("decisionKind"),
             "targets": (d.get("targets") or "").split(",") if d.get("targets") else [], "artisanNames": d.get("names"),
             "capturedAt": iso(d.get("capturedAt"))}
            for d in needs
        ],
        "legacy": [
            {"recordingId": str(d["id"]), "sessionId": d.get("sessionId"), "candidates": d.get("candidates") or [],
             "capturedAt": iso(d.get("capturedAt"))}
            for d in legacy
        ],
        "setDates": [
            {"workshopId": wid, "workshop": name, "sessionIds": [str(s["id"]) for s in rows],
             "from": min((as_date(s.get("sessionDate")).isoformat() for s in rows if s.get("sessionDate")), default=None),
             "to": max((as_date(s.get("sessionDate")).isoformat() for s in rows if s.get("sessionDate")), default=None),
             "count": len(rows)}
            for (wid, name), rows in by_day.items()
        ],
        "noParticipants": [{"sessionId": str(s["id"]), "title": s.get("title"), "date": iso(s.get("sessionDate"))} for s in no_participants],
        "emptySessions": [{"sessionId": str(s["id"]), "title": s.get("title"), "approvedAt": iso(s.get("reviewedAt"))} for s in empty],
        "primaryChanged": [{"eventId": str(e["id"]), "recordingId": str(e["recordingId"]), "change": e.get("after"), "at": iso(e.get("at"))} for e in primary],
    }
    cutoff = now() - timedelta(hours=24)
    stamps = [m.get("createdAt") for m in to_file] + [d.get("createdAt") for d in decisions] + [s.get("createdAt") for s in no_participants]
    from app.services.questionnaire_v2.common import aware

    total = sum(len(v) for k, v in out.items() if isinstance(v, list))
    out["counts"] = {"total": total, "olderThan24h": sum(1 for s in stamps if s and aware(s) < cutoff)}
    return out
