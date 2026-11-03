from __future__ import annotations

from typing import Any

from app.questionnaire import ports


def enabled() -> bool:
    return bool(getattr(ports.get_settings(), "questionnaire_v2", False))


def _db(client: Any) -> Any:
    return client if client is not None else ports.db()


async def forms_for_design_workshop(design_workshop_id: str, *, kind: str | None = None, client: Any = None) -> list[dict[str, Any]]:
    if not enabled():
        return []
    params: list[Any] = [design_workshop_id]
    kind_sql = ""
    if kind:
        params.append(kind)
        kind_sql = ' AND q."kind" = $2'
    rows = await _db(client).query_raw(
        'SELECT DISTINCT q."id", q."title", q."kind", q."createdAt", b."isPrimary" FROM "QuestionnaireBinding" b '
        'JOIN "Questionnaire" q ON q."id" = b."questionnaireId" '
        'WHERE b."designWorkshopId" = $1 AND q."isActive" AND q."deletedAt" IS NULL' + kind_sql + ' ORDER BY q."createdAt"',
        *params,
    )
    return [dict(r) for r in rows]


async def session_counts(form_ids: list[str], *, design_workshop_id: str | None = None, client: Any = None) -> dict[str, dict[str, Any]]:
    ids = sorted({str(f) for f in form_ids if f})
    if not enabled() or not ids:
        return {}
    params: list[Any] = [ids]
    scope = ""
    if design_workshop_id:
        params.append(design_workshop_id)
        scope = ' AND i."designWorkshopId" = $2'
    rows = await _db(client).query_raw(
        'SELECT i."questionnaireId" AS q, count(*) AS n, max(i."sessionDate") AS latest FROM "QuestionnaireInterview" i '
        'WHERE i."questionnaireId" = ANY($1::text[]) AND i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL '
        'AND i."clientKey" IS NOT NULL' + scope + ' GROUP BY 1',
        *params,
    )
    return {str(r["q"]): {"count": int(r["n"]), "latest": r.get("latest")} for r in rows}


async def answer_counts(session_ids: list[str], *, client: Any = None) -> dict[str, int]:
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
