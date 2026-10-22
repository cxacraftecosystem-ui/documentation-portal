"""A form's sections and questions, written where the sessions read them (UNIFY U5/U10, both portals).

The shared package reads a form's structure from the instrument tables (``QuestionnaireSection``,
``QuestionnaireQuestion``): coverage, answers, the record and every reader. Designer-portal's
authoring used to write its own tables (``QuestionnaireFormSection``/``QuestionnaireFormQuestion``)
and was kept in step by the migration's trigger mirror. Once a form is UNIFIED its authoring writes
the instrument tables directly, through :func:`tables`, and the old tables are read-only legacy.

UNIFIED, AND WHEN. ``QUESTIONNAIRE_V2`` on, and either the per-form section keys are in place (the
migration's apply ran: every form lives in the instrument tables, and a new form is made there) or
this form already has its sections there. Before that, nothing changes.

:func:`tables` hands back one object with ``sections`` and ``questions`` delegates that speak the
Prisma calls authoring already makes (``find_many``, ``create``, ``create_many``, ``update``,
``delete``...). On the instrument tables they also keep what those tables need and the old ones did
not: a question carries its section's code, title and form (denormalised; renaming a section renames
them), a section's position is unique within its form (an insert or a move shifts the others), and
a question or section that something in v2 points at is retired rather than deleted. A question's
lineage and frozen display code come from the U0 triggers, as for every other writer.

Evidence for the reword rule (an answer is evidence, and the words it was given under are part of
it) is :func:`answered_question_ids`: a current answer with text given under that wording, or a
take aimed at its lineage.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from app.questionnaire import ports

#: The per-form keys the migration's apply puts in place (deferred section_keys migration).
PER_FORM_KEY = "QuestionnaireSection_questionnaireId_sortOrder_key"
_unified_cache: dict[str, bool] = {}


def enabled() -> bool:
    return bool(getattr(ports.get_settings(), "questionnaire_v2", False))


def _db(client: Any) -> Any:
    return client if client is not None else ports.db()


async def authoring_unified(client: Any = None) -> bool:
    """True once every form lives in the instrument tables (v2 on and the per-form keys present).
    Remembered once true: the keys are never taken away again."""
    if not enabled():
        return False
    if _unified_cache.get("keys"):
        return True
    rows = await _db(client).query_raw("SELECT 1 AS ok FROM pg_indexes WHERE indexname = $1 LIMIT 1", PER_FORM_KEY)
    if rows:
        _unified_cache["keys"] = True
    return bool(rows)


async def form_unified(questionnaire_id: str | None, client: Any = None) -> bool:
    """This form's authoring writes the instrument tables (see the module docstring)."""
    if not enabled():
        return False
    if await authoring_unified(client):
        return True
    if not questionnaire_id:
        return False
    rows = await _db(client).query_raw(
        'SELECT 1 AS ok FROM "QuestionnaireSection" WHERE "questionnaireId" = $1 LIMIT 1', questionnaire_id
    )
    return bool(rows)


def _get(obj: Any, name: str) -> Any:
    return obj.get(name) if isinstance(obj, dict) else getattr(obj, name, None)


def _where_id(where: dict[str, Any]) -> str | None:
    value = where.get("id")
    return value if isinstance(value, str) else None


class _Passthrough:
    def __init__(self, delegate: Any) -> None:
        self._d = delegate

    def __getattr__(self, name: str) -> Any:  # find_many, find_first, find_unique, count, group_by...
        return getattr(self._d, name)


class _Sections(_Passthrough):
    """``QuestionnaireSection`` with a position unique within its form."""

    def __init__(self, client: Any) -> None:
        super().__init__(client.questionnairesection)
        self._client = client

    async def _make_room(self, form_id: str, position: int, *, exclude: str | None = None) -> None:
        taken = await self._client.query_raw(
            'SELECT 1 AS ok FROM "QuestionnaireSection" WHERE "questionnaireId" = $1 AND "sortOrder" = $2 AND "id" IS DISTINCT FROM $3',
            form_id, position, exclude,
        )
        if not taken:
            return
        # Two steps, so the shift never trips the per-form key mid-statement.
        await self._client.execute_raw(
            'UPDATE "QuestionnaireSection" SET "sortOrder" = -("sortOrder" + 1) '
            'WHERE "questionnaireId" = $1 AND "sortOrder" >= $2 AND "id" IS DISTINCT FROM $3',
            form_id, position, exclude,
        )
        await self._client.execute_raw(
            'UPDATE "QuestionnaireSection" SET "sortOrder" = -"sortOrder" WHERE "questionnaireId" = $1 AND "sortOrder" < 0',
            form_id,
        )

    async def create(self, data: dict[str, Any], **kwargs: Any) -> Any:
        await self._make_room(str(data["questionnaireId"]), int(data["sortOrder"]))
        return await self._d.create(data=data, **kwargs)

    async def create_many(self, data: list[dict[str, Any]], **kwargs: Any) -> int:
        made = 0
        for row in data:
            await self.create(row)
            made += 1
        return made

    async def update(self, where: dict[str, Any], data: dict[str, Any], **kwargs: Any) -> Any:
        section_id = _where_id(where)
        current = await self._d.find_unique(where={"id": section_id}) if section_id else None
        if current is not None and "sortOrder" in data and data["sortOrder"] != current.sortOrder:
            await self._make_room(str(current.questionnaireId), int(data["sortOrder"]), exclude=current.id)
        updated = await self._d.update(where=where, data=data, **kwargs)
        if current is not None and ("title" in data or "code" in data):
            await self._client.execute_raw(
                'UPDATE "QuestionnaireQuestion" SET "sectionCode" = $2, "sectionTitle" = $3 WHERE "sectionId" = $1',
                current.id, _get(updated, "code"), _get(updated, "title"),
            )
        return updated

    async def delete(self, where: dict[str, Any], **kwargs: Any) -> Any:
        section_id = _where_id(where)
        if section_id and await _section_in_use(self._client, section_id):
            return await self._d.update(where={"id": section_id}, data={"isActive": False})
        if section_id:
            await self._client.execute_raw('DELETE FROM "QuestionnaireQuestion" WHERE "sectionId" = $1', section_id)
        return await self._d.delete(where=where, **kwargs)


class _Questions(_Passthrough):
    """``QuestionnaireQuestion``, carrying its section's code, title and form."""

    def __init__(self, client: Any) -> None:
        super().__init__(client.questionnairequestion)
        self._client = client
        self._sections: dict[str, Any] = {}

    async def _section(self, section_id: str) -> Any:
        if section_id not in self._sections:
            self._sections[section_id] = await self._client.questionnairesection.find_unique(where={"id": section_id})
        return self._sections[section_id]

    async def _with_section(self, data: dict[str, Any]) -> dict[str, Any]:
        section = await self._section(str(data["sectionId"]))
        if section is None:
            return data
        return {**data, "sectionCode": section.code, "sectionTitle": section.title, "questionnaireId": section.questionnaireId}

    async def create(self, data: dict[str, Any], **kwargs: Any) -> Any:
        return await self._d.create(data=await self._with_section(data), **kwargs)

    async def create_many(self, data: list[dict[str, Any]], **kwargs: Any) -> int:
        # One row at a time: the U0 insert trigger gives each its lineage and next display code.
        made = 0
        for row in data:
            await self.create(row)
            made += 1
        return made

    async def update(self, where: dict[str, Any], data: dict[str, Any], **kwargs: Any) -> Any:
        if data.get("sectionId"):
            data = await self._with_section(data)
        return await self._d.update(where=where, data=data, **kwargs)

    async def delete(self, where: dict[str, Any], **kwargs: Any) -> Any:
        question_id = _where_id(where)
        if question_id and await question_in_use(self._client, question_id):
            return await self._d.update(where={"id": question_id}, data={"isActive": False, "retiredAt": ports_now()})
        return await self._d.delete(where=where, **kwargs)


def ports_now() -> Any:
    from app.questionnaire.common import now

    return now()


async def question_in_use(client: Any, question_id: str) -> bool:
    """Something in v2 points at this wording or its lineage: an answer given under it, a take, a draft."""
    rows = await client.query_raw(
        'SELECT 1 AS ok FROM "QuestionnaireQuestion" q WHERE q."id" = $1 AND ('
        'EXISTS (SELECT 1 FROM "QuestionnaireAnswerRevision" rv WHERE rv."askedQuestionId" = q."id") OR '
        'EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" t WHERE t."questionLineageId" = COALESCE(q."lineageId", q."id")) OR '
        'EXISTS (SELECT 1 FROM "QuestionnaireAnswer" a WHERE a."questionLineageId" = COALESCE(q."lineageId", q."id") '
        '        AND NOT EXISTS (SELECT 1 FROM "QuestionnaireQuestion" o WHERE COALESCE(o."lineageId", o."id") = COALESCE(q."lineageId", q."id") AND o."id" <> q."id")))',
        question_id,
    )
    return bool(rows)


async def _section_in_use(client: Any, section_id: str) -> bool:
    rows = await client.query_raw(
        'SELECT 1 AS ok WHERE EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" t WHERE t."sectionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireSectionStatus" s WHERE s."sectionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireQuestion" q WHERE q."sectionId" = $1 AND EXISTS '
        '   (SELECT 1 FROM "QuestionnaireAnswerRevision" rv WHERE rv."askedQuestionId" = q."id"))',
        section_id,
    )
    return bool(rows)


async def answered_question_ids(client: Any, question_ids: list[str]) -> set[str]:
    """The wordings with evidence: a current answer with text given under that wording (the
    revision's ``askedQuestionId``), or a live take aimed at its lineage."""
    ids = sorted({str(q) for q in question_ids if q})
    if not ids:
        return set()
    rows = await _db(client).query_raw(
        'SELECT q."id" FROM "QuestionnaireQuestion" q WHERE q."id" = ANY($1::text[]) AND ('
        'EXISTS (SELECT 1 FROM "QuestionnaireAnswer" a JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        "        WHERE rv.\"askedQuestionId\" = q.\"id\" AND btrim(rv.\"text\") <> '') OR "
        'EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" t JOIN "QuestionnaireRecording" r ON r."id" = t."recordingId" '
        '        WHERE t."questionLineageId" = COALESCE(q."lineageId", q."id") AND r."removedAt" IS NULL))',
        ids,
    )
    return {str(r["id"]) for r in rows}


def tables(client: Any, unified: bool) -> SimpleNamespace:
    """``sections`` and ``questions``: the instrument tables when ``unified``, else the old form
    tables (legacy, before the migration's apply)."""
    if unified:
        return SimpleNamespace(sections=_Sections(client), questions=_Questions(client), unified=True)
    return SimpleNamespace(sections=client.questionnaireformsection, questions=client.questionnaireformquestion, unified=False)


async def unified_form_ids(form_ids: list[str], client: Any = None) -> set[str]:
    """Which of these forms are unified (all of them once the migration's apply ran)."""
    ids = sorted({str(f) for f in form_ids if f})
    if not ids or not enabled():
        return set()
    if await authoring_unified(client):
        return set(ids)
    rows = await _db(client).query_raw(
        'SELECT DISTINCT "questionnaireId" AS q FROM "QuestionnaireSection" WHERE "questionnaireId" = ANY($1::text[])', ids
    )
    return {str(r["q"]) for r in rows}
