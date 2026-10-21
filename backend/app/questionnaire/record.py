"""One artisan record (DESIGN 4.6, decision D11): a timeline of every recording they were present
for, plus a by-question document, per instrument. ``GET /api/artisans/{id}/record`` and ``.csv``.
"""

from __future__ import annotations

import csv
import io
from collections import defaultdict
from typing import Any

from app.questionnaire import ports
from app.questionnaire.ports import naming
from app.questionnaire import answers as answers_service
from app.questionnaire import coverage, recordings, sessions
from app.questionnaire.common import fail


def _db(client: Any) -> Any:
    if client is not None:
        return client
    db = ports.db()

    return db


async def artisan_record(artisan_id: str, user: Any, *, questionnaire_id: str | None = None, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    artisan = await db.query_raw('SELECT "id", "name" FROM "Artisan" WHERE "id" = $1', artisan_id)
    if not artisan:
        fail("NOT_FOUND", "No such artisan.")
    params: list[Any] = [artisan_id]
    narrow = ""
    if questionnaire_id:
        params.append(questionnaire_id)
        narrow = ' AND q."id" = $2'
    instruments = await db.query_raw(
        'SELECT DISTINCT q."id", q."title", q."sortOrder" FROM "Questionnaire" q WHERE (EXISTS (SELECT 1 FROM "QuestionnaireInterview" i '
        'JOIN "QuestionnaireInterviewArtisan" x ON x."interviewId" = i."id" WHERE i."questionnaireId" = q."id" AND x."artisanId" = $1 '
        'AND i."mergedIntoId" IS NULL) OR EXISTS (SELECT 1 FROM "QuestionnaireAnswer" a WHERE a."questionnaireId" = q."id" AND a."artisanId" = $1))'
        + narrow + ' ORDER BY q."sortOrder"',
        *params,
    )
    out = []
    for inst in instruments:
        qid = str(inst["id"])
        grid = await coverage.grid(qid, artisan_ids=[artisan_id], client=db)
        cells = [(s, s["cells"][0]) for s in grid["sections"] if s["cells"]]
        covered = [s for s, c in cells if coverage.section_covered(c)]
        takes = await coverage.take_rows(db, qid, artisan_ids=[artisan_id])
        repeats = len({str(t["recordingId"]) for t in takes if (t.get("position") or 1) >= 2 and not t.get("continuationOfId")})
        session_rows = await db.query_raw(
            'SELECT i.*, i."state"::text AS state_t, i."purpose"::text AS purpose_t, i."origin"::text AS origin_t, '
            'i."status"::text AS status_t FROM "QuestionnaireInterview" i JOIN "QuestionnaireInterviewArtisan" x ON x."interviewId" = i."id" '
            'WHERE x."artisanId" = $1 AND i."questionnaireId" = $2 AND i."mergedIntoId" IS NULL '
            'ORDER BY i."sessionDate" DESC NULLS LAST, i."createdAt" DESC',
            artisan_id, qid,
        )
        serialized = await sessions.serialize_sessions(db, session_rows)
        # Every session's recordings in one read and one serialisation, then dealt back out per
        # session in the order the per-session read returned them (the ORDER BY is the same).
        session_ids = [str(srow["id"]) for srow in session_rows]
        all_rows = await recordings.recording_rows(db, session_ids=session_ids) if session_ids else []
        all_recs = await recordings.serialize_recordings(db, all_rows, user)
        recs_by_session: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row, rec in zip(all_rows, all_recs):
            recs_by_session[str(row["sessionId"])].append(rec)
        timeline = []
        for srow, s in zip(session_rows, serialized):
            recs = recs_by_session.get(str(srow["id"]), [])
            mine = [r for r in recs if not r["subjects"] or any(x["artisanId"] == artisan_id for x in r["subjects"])]
            timeline.append({
                "session": s,
                "recordings": [r for r in mine if not r["estimated"]],
                "uncertain": [r for r in mine if r["estimated"]],
            })
        primaries = await coverage.primaries(db, artisan_id, takes)
        current = {str(a["questionLineageId"]): a for a in await answers_service.answers_for(db, artisan_ids=[artisan_id], questionnaire_id=qid)}
        questions = await db.query_raw(
            'SELECT q."id", q."lineageId", q."displayCode", q."prompt", q."sectionId", q."retiredAt", q."supersededById", q."isActive", '
            'q."createdAt", s."code" AS scode, s."sortOrder" AS ssort, s."isActive" AS "sectionActive" FROM "QuestionnaireQuestion" q '
            'LEFT JOIN "QuestionnaireSection" s ON s."id" = q."sectionId" '
            'WHERE q."questionnaireId" = $1 ORDER BY s."sortOrder", q."displayCode", q."createdAt"',
            qid,
        )
        takes_by_key = defaultdict(list)
        for t in takes:
            takes_by_key[str(t["targetKey"])].append(t)
        doc: dict[str, list] = defaultdict(list)
        retired = []
        section_order: list[str] = []
        # ONE entry per lineage (audit M17): a reworded question is listed once, under the wording asked
        # now; a question the builder deleted (isActive off) or whose section was deleted is not live
        # and is listed under "retired" only when it holds takes or an answer.
        lineages: dict[str, list[dict[str, Any]]] = {}
        for q in questions:
            lineages.setdefault(str(q.get("lineageId") or q["id"]), []).append(q)
        for lineage, wordings in lineages.items():
            asked_now = [w for w in wordings if _is_live(w)]
            q = asked_now[0] if asked_now else max(wordings, key=lambda w: str(w.get("createdAt") or ""))
            key = coverage.question_key(lineage)
            qtakes = takes_by_key.get(key, [])
            answer = current.get(lineage)
            primary = primaries.get(key)
            section_key = coverage.section_key(str(q["sectionId"])) if q.get("sectionId") else None
            recorded = bool(qtakes) or bool(section_key and takes_by_key.get(section_key))
            typed = bool(answer and str(answer.get("text") or "").strip())
            entry = {
                "displayCode": q.get("displayCode"),
                "answer": answers_service.answer_out(answer) if answer else None,
                "primaryRecordingId": primary,
                "otherRecordingIds": sorted({str(t["recordingId"]) for t in qtakes} - {primary} if primary else set()),
                "accountsDiffer": False,
                # Where this question's coverage comes from (owner, amendment 2 point 4): a recording
                # when there is one (preferred), else a typed answer, else nothing.
                "coverageSource": coverage.SOURCE_RECORDED if recorded else (coverage.SOURCE_TYPED if typed else None),
                "recordedUnderEarlierWording": any(t.get("askedQuestionId") and str(t["askedQuestionId"]) != str(q["id"]) for t in qtakes),
            }
            if not asked_now:
                if qtakes or answer:
                    retired.append({**entry, "prompt": q.get("prompt")})
                continue
            code = q.get("scode") or "?"
            if code not in section_order:
                section_order.append(code)
            doc[code].append(entry)
        out.append({
            "questionnaireId": qid,
            "title": inst["title"],
            "summary": {
                "sectionsCovered": len(covered), "sectionsTotal": len(cells), "sessions": len(session_rows),
                "repeats": repeats, "open": [s["code"] for s, c in cells if not coverage.section_covered(c)],
            },
            "sessions": timeline,
            "document": [{"sectionCode": code, "questions": doc[code]} for code in section_order],
            "retired": retired,
        })
    return {"artisanId": artisan_id, "instruments": out}


def _is_live(q: dict[str, Any]) -> bool:
    return (q.get("retiredAt") is None and q.get("supersededById") is None and q.get("isActive") is not False
            and q.get("sectionActive") is not False)


async def artisan_record_csv(artisan_id: str, user: Any, *, questionnaire_id: str | None = None, client: Any = None) -> tuple[str, str]:
    """``(file name, csv text)``: one row per question, with its answer and takes."""
    db = _db(client)
    record = await artisan_record(artisan_id, user, questionnaire_id=questionnaire_id, client=db)
    name_rows = await db.query_raw('SELECT "name", "localName" FROM "Artisan" WHERE "id" = $1', artisan_id)
    name = name_rows[0]["name"] if name_rows else None
    local = name_rows[0].get("localName") if name_rows else None
    subject = name if (name or "").isascii() else (local if (local or "").isascii() and local else name)
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["Questionnaire", "Section", "Question", "Answer", "Origin", "Attribution", "Primary recording", "Other recordings", "Covered by"])
    for inst in record["instruments"]:
        for section in inst["document"]:
            for q in section["questions"]:
                a = q["answer"] or {}
                writer.writerow([inst["title"], section["sectionCode"], q["displayCode"], a.get("text", ""), a.get("origin", ""),
                                 a.get("attribution", ""), q["primaryRecordingId"] or "", " ".join(q["otherRecordingIds"]),
                                 {"RECORDED": "Recorded", "TYPED": "Typed"}.get(q.get("coverageSource") or "", "")])
    filename = naming.export_filename(entity="Questionnaire", what="Export", ext="csv", subject=subject)
    return filename, buffer.getvalue()
