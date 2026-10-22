"""A form's sections and questions as the session screens read them (UNIFY 4.2): one read per table.

``GET /questionnaire/forms/{id}/structure`` in both portals. Every question carries its lineage (what
an answer and a take target), its frozen ``displayCode``, whether it is required (A19: Finish reports
the unanswered ones, never blocks), its help text and the artisan field it may prefill from (B24).
Retired and superseded questions are left out unless ``include_retired``: a session never offers
them, and the record shows them only where they carry an answer.
"""

from __future__ import annotations

from typing import Any

from app.questionnaire import ports
from app.questionnaire.common import fail


async def form_structure(questionnaire_id: str, *, include_retired: bool = False, client: Any = None) -> dict[str, Any]:
    db = client if client is not None else ports.db()
    forms = await db.query_raw(
        'SELECT "id", "title", "kind", "isActive", "deletedAt", "version" FROM "Questionnaire" WHERE "id" = $1', questionnaire_id
    )
    if not forms or forms[0].get("deletedAt") is not None:
        fail("NOT_FOUND", "That form does not exist or was deleted.")
    form = forms[0]
    sections = await db.query_raw(
        'SELECT "id", "code", "title", "sortOrder" FROM "QuestionnaireSection" WHERE "questionnaireId" = $1 '
        'AND ("isActive" OR $2) ORDER BY "sortOrder", "id"',
        questionnaire_id, include_retired,
    )
    questions = await db.query_raw(
        'SELECT "id", "sectionId", "prompt", "helpText", "sortOrder", "isActive", "isRequired", "lineageId", "displayCode", '
        '"artisanField", "retiredAt", "supersededById" FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1 '
        'AND ($2 OR ("isActive" AND "retiredAt" IS NULL AND "supersededById" IS NULL)) ORDER BY "sortOrder", "id"',
        questionnaire_id, include_retired,
    )
    by_section: dict[str, list[dict[str, Any]]] = {}
    for q in questions:
        by_section.setdefault(str(q["sectionId"]), []).append({
            "id": str(q["id"]), "prompt": q.get("prompt") or "", "helpText": q.get("helpText"),
            "sortOrder": int(q.get("sortOrder") or 0), "isActive": bool(q.get("isActive")) and not q.get("retiredAt") and not q.get("supersededById"),
            "isRequired": bool(q.get("isRequired")), "lineageId": str(q.get("lineageId") or q["id"]),
            "displayCode": q.get("displayCode"), "artisanField": q.get("artisanField"),
        })
    return {
        "questionnaireId": str(form["id"]), "title": form.get("title") or "", "kind": form.get("kind"),
        "isActive": bool(form.get("isActive")), "version": int(form.get("version") or 1),
        "sections": [
            {"id": str(s["id"]), "code": s.get("code") or "", "title": s.get("title") or "", "sortOrder": int(s.get("sortOrder") or 0),
             "questions": by_section.get(str(s["id"]), [])}
            for s in sections
        ],
    }
