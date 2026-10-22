"""The readers' view of the shared questionnaire on Field Repository (UNIFY_2026-10-21.md C10).

Task progress, record completeness and the dataset export read the legacy interview answers
(``QuestionnaireResponse``). With ``QUESTIONNAIRE_V2`` on, the same facts live in sessions, takes
(``questionnaire_take_v``, live sessions only) and answers (``QuestionnaireAnswer`` and its current
revision), and each reader adds them through the helpers here. A soft-deleted session is invisible
to every one of them (the live filter, owner decision 3).

While the switch is off every helper answers empty WITHOUT A QUERY, so the readers behave and cost
exactly as they did before.

The same helpers as designer-portal's ``app/services/questionnaire_readers.py`` (U1), scoped by
``workshopId``; they belong in the shared package once it carries a readers module.
"""

from __future__ import annotations

from typing import Any


def enabled() -> bool:
    from app.core.config import get_settings

    return bool(get_settings().questionnaire_v2)


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def answer_counts(session_ids: list[str], *, client: Any = None) -> dict[str, int]:
    """``{sessionId: answers with text whose current revision was written in that session}``."""
    ids = sorted({str(s) for s in session_ids if s})
    if not enabled() or not ids:
        return {}
    rows = await _db(client).query_raw(
        'SELECT rv."sessionId" AS s, count(*) AS n FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" AND i."deletedAt" IS NULL '
        "WHERE rv.\"sessionId\" = ANY($1::text[]) AND btrim(rv.\"text\") <> '' GROUP BY 1",
        ids,
    )
    return {str(r["s"]): int(r["n"]) for r in rows}


async def covered_pairs(assignee_id: str, section_ids: list[str], *, workshop_id: str | None = None,
                        client: Any = None) -> set[tuple[str, str]]:
    """``{(subjectKey, sectionId)}`` this person covered: their takes (``recordedById``) and their
    typed answers with text (``actorId``). Typed or recorded, either counts (owner, amendment 2)."""
    ids = sorted({str(s) for s in section_ids if s})
    if not enabled() or not ids:
        return set()
    db = _db(client)
    params: list[Any] = [assignee_id, ids]
    scope = ""
    if workshop_id:
        params.append(workshop_id)
        scope = ' AND t."workshopId" = $3'
    takes = await db.query_raw(
        'SELECT DISTINCT t."subjectKey" AS k, t."sectionId" AS s FROM questionnaire_take_v t '
        'WHERE t."recordedById" = $1 AND t."sectionId" = ANY($2::text[])' + scope,
        *params,
    )
    scope = ' AND i."workshopId" = $3' if workshop_id else ""
    typed = await db.query_raw(
        'SELECT DISTINCT COALESCE(\'A:\' || a."artisanId", \'R:\' || a."respondentId") AS k, q."sectionId" AS s '
        'FROM "QuestionnaireAnswer" a JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" AND i."deletedAt" IS NULL '
        'JOIN "QuestionnaireQuestion" q ON q."lineageId" = a."questionLineageId" AND q."supersededById" IS NULL '
        "WHERE rv.\"actorId\" = $1 AND q.\"sectionId\" = ANY($2::text[]) AND btrim(rv.\"text\") <> ''" + scope,
        *params,
    )
    return {(str(r["k"]), str(r["s"])) for r in [*takes, *typed] if r.get("k") and r.get("s")}


async def session_answers(session_ids: list[str], *, client: Any = None) -> dict[str, list[dict[str, Any]]]:
    """``{sessionId: [{"displayCode", "sectionCode", "prompt", "text", "note", "owner"}]}``: the answers
    written in each live session, in the form's order, for the export tree."""
    ids = sorted({str(s) for s in session_ids if s})
    if not enabled() or not ids:
        return {}
    rows = await _db(client).query_raw(
        'SELECT rv."sessionId" AS s, q."displayCode", q."prompt", q."sectionCode", rv."text", rv."note", '
        'COALESCE(ar."name", r."name") AS owner FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" AND i."deletedAt" IS NULL '
        'JOIN "QuestionnaireQuestion" q ON q."lineageId" = a."questionLineageId" AND q."questionnaireId" = a."questionnaireId" '
        '  AND q."supersededById" IS NULL '
        'LEFT JOIN "Artisan" ar ON ar."id" = a."artisanId" LEFT JOIN "QuestionnaireRespondent" r ON r."id" = a."respondentId" '
        "WHERE rv.\"sessionId\" = ANY($1::text[]) AND btrim(rv.\"text\") <> '' "
        'ORDER BY rv."sessionId", q."sortOrder", q."id"',
        ids,
    )
    out: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        out.setdefault(str(r["s"]), []).append({
            "displayCode": r.get("displayCode"), "sectionCode": r.get("sectionCode"), "prompt": r.get("prompt") or "",
            "text": r.get("text") or "", "note": r.get("note"), "owner": r.get("owner"),
        })
    return out
