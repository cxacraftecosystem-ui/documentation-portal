"""The corpus feed (DESIGN 5.4): answers and recordings of one instrument, ``schemaVersion: 1``.

Filters on RECORDING and REVISION status, never on session status (the downstream invariant of
3.10). It carries the consent state and source per recording but does not filter on it: exporting
sends the audio nowhere. ``s-cross-portal`` reads this feed now; ``f-search-doc`` when it lands.
"""

from __future__ import annotations

import csv
import io
import json
from typing import Any

from app.services import consent_gate, naming
from app.services.questionnaire_v2 import coverage
from app.services.questionnaire_v2.common import fail, iso

SCHEMA_VERSION = 1
ANSWER_FIELDS = [
    "schemaVersion", "answerId", "revisionId", "artisanId", "questionLineageId", "displayCode", "askedQuestionId",
    "askedWording", "text", "languageCode", "attribution", "origin", "status", "sourceRecordingId", "takeCount",
    "primaryRecordingId", "capturedAt", "capturedAtSource",
]
RECORDING_FIELDS = [
    "schemaVersion", "recordingId", "mediaFileId", "sessionId", "sessionDate", "targets", "answered", "present",
    "rosterCount", "researchers", "durationMs", "contentSha256", "transcriptStatus", "languageCode", "flags",
    "status", "consentVerdict", "consentSource",
]


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def corpus(questionnaire_id: str, *, status: str | None = "APPROVED", client: Any = None) -> dict[str, list[dict[str, Any]]]:
    db = _db(client)
    inst = await db.query_raw('SELECT "title" FROM "Questionnaire" WHERE "id" = $1', questionnaire_id)
    if not inst:
        fail("NOT_FOUND", "No such questionnaire.")
    answers = await db.query_raw(
        'SELECT a."id" AS "answerId", rv."id" AS "revisionId", a."artisanId", a."questionLineageId", q."displayCode", rv."askedQuestionId", '
        'aq."prompt" AS "askedWording", rv."text", rv."languageCode", rv."attribution"::text AS attribution, rv."origin"::text AS origin, '
        'rv."status"::text AS status, rv."sourceRecordingId" FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireQuestion" q ON q."id" = a."questionLineageId" JOIN "QuestionnaireQuestion" aq ON aq."id" = rv."askedQuestionId" '
        'WHERE a."questionnaireId" = $1 AND ($2::text IS NULL OR rv."status"::text = $2) ORDER BY a."artisanId", q."displayCode"',
        questionnaire_id, status,
    )
    takes = await coverage.take_rows(db, questionnaire_id)
    by_key: dict[tuple[str, str], list] = {}
    for t in takes:
        by_key.setdefault((str(t["artisanId"]), str(t["targetKey"])), []).append(t)
    answer_rows = []
    # Every answering artisan's primary takes in one batched read, not three round trips each.
    takes_by_artisan: dict[str, list] = {str(a["artisanId"]): [] for a in answers}
    for t in takes:
        if str(t["artisanId"]) in takes_by_artisan:
            takes_by_artisan[str(t["artisanId"])].append(t)
    primary_cache = await coverage.primaries_many(db, takes_by_artisan)
    for a in answers:
        artisan_id = str(a["artisanId"])
        key = coverage.question_key(str(a["questionLineageId"]))
        rows = by_key.get((artisan_id, key), [])
        primary = primary_cache[artisan_id].get(key)
        ptake = next((t for t in rows if str(t["recordingId"]) == primary), None)
        answer_rows.append({
            "schemaVersion": SCHEMA_VERSION, **{k: a.get(k) for k in ANSWER_FIELDS if k in a},
            "takeCount": len({str(t["recordingId"]) for t in rows}), "primaryRecordingId": primary,
            "capturedAt": iso(ptake["capturedAt"]) if ptake else None, "capturedAtSource": ptake.get("source") if ptake else None,
        })
    recs = await db.query_raw(
        'SELECT r."id" AS "recordingId", r."mediaFileId", m."questionnaireInterviewId" AS "sessionId", i."sessionDate", r."rosterCount", '
        'r."durationMs", m."contentSha256", m."transcriptStatus", r."languageCode", m."status"::text AS status, '
        '(SELECT json_agg(t."targetKey" ORDER BY t."targetKey") FROM "QuestionnaireRecordingTarget" t WHERE t."recordingId" = r."id") AS targets, '
        "(SELECT json_agg(s.\"artisanId\" ORDER BY s.\"artisanId\") FROM \"QuestionnaireRecordingSubject\" s WHERE s.\"recordingId\" = r.\"id\" AND s.\"role\" = 'ANSWERED') AS answered, "
        "(SELECT json_agg(s.\"artisanId\" ORDER BY s.\"artisanId\") FROM \"QuestionnaireRecordingSubject\" s WHERE s.\"recordingId\" = r.\"id\" AND s.\"role\" = 'PRESENT') AS present, "
        '(SELECT json_agg(DISTINCT s."flag"::text) FROM "QuestionnaireRecordingSubject" s WHERE s."recordingId" = r."id" AND s."flag" IS NOT NULL) AS flags, '
        '(SELECT json_agg(x."userId" ORDER BY x."userId") FROM "QuestionnaireInterviewResearcher" x WHERE x."interviewId" = i."id") AS researchers '
        'FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        'WHERE i."questionnaireId" = $1 AND r."removedAt" IS NULL AND m."removedAt" IS NULL AND r."filing" = \'FILED\' '
        'AND ($2::text IS NULL OR m."status"::text = $2) ORDER BY r."capturedAt", r."id"',
        questionnaire_id, status,
    )
    recording_rows = []
    details = await consent_gate.verdict_details([str(r["mediaFileId"]) for r in recs], client=db)
    for r in recs:
        detail = details[str(r["mediaFileId"])]
        recording_rows.append({
            "schemaVersion": SCHEMA_VERSION, **{k: _plain(r.get(k)) for k in RECORDING_FIELDS if k in r},
            "sessionDate": str(r["sessionDate"])[:10] if r.get("sessionDate") else None,
            "consentVerdict": detail["verdict"], "consentSource": detail["source"],
        })
    return {"title": inst[0]["title"], "answers": answer_rows, "recordings": recording_rows}


def _plain(value: Any) -> Any:
    if isinstance(value, str) and value[:1] in "[{":
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


def render(rows: list[dict[str, Any]], fields: list[str], fmt: str) -> str:
    if fmt == "jsonl":
        return "".join(json.dumps(row, ensure_ascii=False, default=str, sort_keys=True) + "\n" for row in rows)
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=fields, extrasaction="ignore")
    writer.writeheader()
    for row in rows:
        writer.writerow({k: (json.dumps(v) if isinstance(v, (list, dict)) else v) for k, v in row.items()})
    return buffer.getvalue()


def file_names(title: str, fmt: str) -> dict[str, str]:
    return {
        "answers": naming.export_filename(entity="Questionnaire", what="Corpus-Answers", ext=fmt, subject=title),
        "recordings": naming.export_filename(entity="Questionnaire", what="Corpus-Recordings", ext=fmt, subject=title),
    }
