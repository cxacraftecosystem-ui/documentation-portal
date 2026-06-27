import re
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    get_value,
    is_admin,
    is_empty_value,
    require_admin,
    require_questionnaire_manager,
)
from app.services.access import guard_record_edit
from app.schemas.questionnaire import (
    CompletionCellUpdate,
    QuestionnaireInterviewCreate,
    QuestionnaireInterviewUpdate,
    QuestionnaireQuestionCreate,
    QuestionnaireQuestionReorder,
    QuestionnaireQuestionUpdate,
    QuestionnaireSectionCreate,
    QuestionnaireSectionReorder,
    QuestionnaireSectionUpdate,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    public_encode,
    add_date_range,
    attach_location,
    clean_data,
    contains,
    jsonify_metadata,
    merge_field_provenance,
    require_record,
)

router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])


def _norm_code(value: str | None) -> str:
    """Uppercase, alphanumerics-only form of a section code — mirrors the app's filename token() so a
    clip filename's leading section token resolves back to its section."""
    return "".join(ch for ch in (value or "") if ch.isalnum()).upper()


_SECTION_IN_TITLE_RE = re.compile(r"sections?\s+([a-zA-Z][a-zA-Z\s,&/\-]{0,24})", re.IGNORECASE)
_LETTER_RUN_RE = re.compile(r"\b([a-zA-Z](?:\s*,\s*[a-zA-Z]){2,})\b")
_REAL_WORD_RE = re.compile(r"\b(?!and\b)[a-zA-Z]{2,}\b", re.IGNORECASE)


def section_codes_from_title(title: str | None, valid_codes: set[str]) -> set[str]:
    """Best-effort section codes named in an interview's TITLE — the only completion signal for
    interviews recorded BEFORE the clip-filename nomenclature existed. Researchers titled those by the
    sections covered, e.g. "Section K & L", "section F", or "Rudraprayag G,H,I,J,Q". Only tokens that
    exactly match a real section code count, so unrelated words are ignored; admins can still override.
    """
    if not title:
        return set()
    found: set[str] = set()
    # Letters right after the word "section"/"sections", through a short run of separators, stopping at
    # the first real word (2+ letters that isn't "and") so we don't sweep up the rest of the title.
    for match in _SECTION_IN_TITLE_RE.finditer(title):
        run = _REAL_WORD_RE.split(match.group(1))[0]
        for token in re.split(r"[^a-zA-Z]+", run):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    # Bare comma-separated single-letter runs, e.g. "G,H,I,J,Q".
    for match in _LETTER_RUN_RE.finditer(title):
        for token in re.split(r"[^a-zA-Z]+", match.group(1)):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    return found


INTERVIEW_INCLUDE = {
    "createdBy": True,
    "location": True,
    "artisans": {"include": {"artisan": {"include": {"craft": True}}}},
    "responses": {"include": {"question": True, "answeredBy": True}},
    "media": True,
}


_DUPLICATE_SET_DETAIL = (
    "An interview already exists for this exact set of artisans. There is a single shared entry per "
    "artisan set — open it to add or view answers instead of creating another."
)


def artisan_set_key(artisan_ids: list[str]) -> str | None:
    """Deterministic key for the exact set of artisans an interview covers.

    Sorted, de-duplicated, comma-joined artisan ids — identical to the SQL backfill in migration
    ``20260622120000`` (``string_agg(..., ',' ORDER BY ...)``). This is what makes one-interview-per-
    artisan-set enforceable: every client computing the same set lands on the same key. Returns
    ``None`` for an empty set (artisan-less interviews are not deduped). A subset yields a different
    key, i.e. a separate entry.
    """
    unique = sorted({aid for aid in artisan_ids if aid})
    return ",".join(unique) if unique else None


async def next_section_sort_order() -> int:
    sections = await db.questionnairesection.find_many(order={"sortOrder": "desc"}, take=1)
    return (sections[0].sortOrder if sections else 0) + 1


async def next_question_sort_order(section_id: str) -> int:
    questions = await db.questionnairequestion.find_many(
        where={"sectionId": section_id},
        order={"sortOrder": "desc"},
        take=1,
    )
    return (questions[0].sortOrder if questions else 0) + 1


async def require_section(section_id: str) -> Any:
    return await require_record(db.questionnairesection, section_id)


def section_question_data(section: Any) -> dict[str, str]:
    return {"sectionId": section.id, "sectionCode": section.code, "sectionTitle": section.title}


async def section_payloads(active_only: bool = True) -> list[dict[str, Any]]:
    section_where = {"isActive": True} if active_only else {}
    question_where: dict[str, Any] = {"isActive": True} if active_only else {}
    sections = await db.questionnairesection.find_many(where=section_where, order={"sortOrder": "asc"})
    questions = await db.questionnairequestion.find_many(
        where=question_where,
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    questions_by_section: dict[str, list[Any]] = {}
    for question in questions:
        if question.sectionId:
            questions_by_section.setdefault(question.sectionId, []).append(question)
    payload: list[dict[str, Any]] = []
    for section in sections:
        encoded = public_encode(section)
        encoded["questions"] = public_encode(questions_by_section.get(section.id, []))
        payload.append(encoded)
    return payload


async def replace_interview_artisans(interview_id: str, artisan_ids: list[str]) -> None:
    unique_ids = sorted({aid for aid in artisan_ids if aid})
    set_key = artisan_set_key(unique_ids)

    # Short-circuit when the artisan set is unchanged. A plain edit (new responses/media, title, notes)
    # re-sends the same artisanIds; rewriting the unique set key + links on every such save is both
    # wasteful and the ONLY thing that could trip the one-interview-per-set guard. Skipping it means an
    # ordinary edit can never 409. We still heal a drifted cached key (its own value, so no conflict).
    current = await db.questionnaireinterviewartisan.find_many(where={"interviewId": interview_id})
    if sorted({link.artisanId for link in current}) == unique_ids:
        existing = await db.questionnaireinterview.find_unique(where={"id": interview_id})
        if existing is not None and existing.artisanSetKey != set_key:
            await db.questionnaireinterview.update(
                where={"id": interview_id}, data={"artisanSetKey": set_key}
            )
        return

    # The set is genuinely changing. Validate every artisan up front so a bad id can't leave a
    # half-rewritten link set, then keep the unique set key in lock-step with the links.
    for artisan_id in unique_ids:
        await require_record(db.artisan, artisan_id)
    try:
        await db.questionnaireinterview.update(
            where={"id": interview_id}, data={"artisanSetKey": set_key}
        )
    except UniqueViolationError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=_DUPLICATE_SET_DETAIL) from exc
    await db.questionnaireinterviewartisan.delete_many(where={"interviewId": interview_id})
    for artisan_id in unique_ids:
        await db.questionnaireinterviewartisan.create(
            data={"interviewId": interview_id, "artisanId": artisan_id}
        )


async def upsert_responses(interview_id: str, responses: list[Any], current_user: Any) -> None:
    for response in responses:
        await require_record(db.questionnairequestion, response.questionId)
        existing = await db.questionnaireresponse.find_unique(
            where={
                "interviewId_questionId": {
                    "interviewId": interview_id,
                    "questionId": response.questionId,
                }
            }
        )
        if (
            existing
            and existing.answerText
            and existing.answeredById != current_user.id
            and not is_admin(current_user)
        ):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the original answer contributor or an admin can change this response",
            )
        await db.questionnaireresponse.upsert(
            where={
                "interviewId_questionId": {
                    "interviewId": interview_id,
                    "questionId": response.questionId,
                }
            },
            data={
                "create": {
                    "interviewId": interview_id,
                    "questionId": response.questionId,
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answeredById": current_user.id,
                },
                "update": {
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answeredById": current_user.id,
                },
            },
        )


@router.get("/questions")
async def list_questions(
    _: Any = Depends(get_current_user),
    sectionCode: str | None = None,
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    sections = await section_payloads(activeOnly)
    flattened = [
        question
        for section in sections
        for question in section["questions"]
        if not sectionCode or question["sectionCode"] == sectionCode
    ]
    return public_encode(flattened)


@router.get("/sections")
async def list_sections(
    _: Any = Depends(get_current_user),
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    return await section_payloads(activeOnly)


@router.post("/sections", status_code=status.HTTP_201_CREATED)
async def create_section(
    payload: QuestionnaireSectionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    sort_order = payload.sortOrder or await next_section_sort_order()
    created = await db.questionnairesection.create(
        data={
            "code": payload.code.strip(),
            "title": payload.title.strip(),
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@router.patch("/sections/{section_id}")
async def update_section(
    section_id: str,
    payload: QuestionnaireSectionUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    await require_section(section_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "code" in data:
        data["code"] = data["code"].strip()
    if "title" in data:
        data["title"] = data["title"].strip()
    updated = await db.questionnairesection.update(where={"id": section_id}, data=data)
    if "code" in data or "title" in data:
        await db.questionnairequestion.update_many(
            where={"sectionId": section_id},
            data={"sectionCode": updated.code, "sectionTitle": updated.title},
        )
    return public_encode(updated)


@router.delete("/sections/{section_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_section(
    section_id: str,
    _: Any = Depends(require_questionnaire_manager),
) -> None:
    await require_section(section_id)
    await db.questionnairesection.update(where={"id": section_id}, data={"isActive": False})
    await db.questionnairequestion.update_many(where={"sectionId": section_id}, data={"isActive": False})


@router.post("/sections/reorder")
async def reorder_sections(
    payload: QuestionnaireSectionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    for index, section_id in enumerate(payload.sectionIds):
        await require_section(section_id)
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": -(index + 1)})
    for index, section_id in enumerate(payload.sectionIds):
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": index + 1})
    return await section_payloads(active_only=False)


@router.post("/questions", status_code=status.HTTP_201_CREATED)
async def create_question(
    payload: QuestionnaireQuestionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    section = await require_section(payload.sectionId)
    sort_order = payload.sortOrder or await next_question_sort_order(section.id)
    created = await db.questionnairequestion.create(
        data={
            **section_question_data(section),
            "prompt": payload.prompt.strip(),
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@router.patch("/questions/{question_id}")
async def update_question(
    question_id: str,
    payload: QuestionnaireQuestionUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    question = await require_record(db.questionnairequestion, question_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "prompt" in data:
        data["prompt"] = data["prompt"].strip()
    section_id = data.pop("sectionId", None)
    if section_id:
        section = await require_section(section_id)
        data.update(section_question_data(section))
        if "sortOrder" not in data or data["sortOrder"] is None:
            data["sortOrder"] = await next_question_sort_order(section.id)
    elif question.sectionId and ("sectionCode" not in data or "sectionTitle" not in data):
        section = await require_section(question.sectionId)
        data.update({"sectionCode": section.code, "sectionTitle": section.title})
    updated = await db.questionnairequestion.update(where={"id": question_id}, data=data)
    return public_encode(updated)


@router.delete("/questions/{question_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_question(
    question_id: str,
    _: Any = Depends(require_questionnaire_manager),
) -> None:
    await require_record(db.questionnairequestion, question_id)
    await db.questionnairequestion.update(where={"id": question_id}, data={"isActive": False})


@router.post("/questions/reorder")
async def reorder_questions(
    payload: QuestionnaireQuestionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    section = await require_section(payload.sectionId)
    for index, question_id in enumerate(payload.questionIds):
        await require_record(db.questionnairequestion, question_id)
        await db.questionnairequestion.update(
            where={"id": question_id},
            data={**section_question_data(section), "sortOrder": index + 1},
        )
    return await section_payloads(active_only=False)


@router.get("/interviews")
async def list_interviews(
    _: Any = Depends(get_current_user),
    search: str | None = None,
    artisanId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    if search:
        where["OR"] = [{"title": contains(search)}, {"place": contains(search)}, {"notes": contains(search)}]
    if artisanId:
        where["artisans"] = {"some": {"artisanId": artisanId}}
    if statusFilter:
        where["status"] = statusFilter
    add_date_range(where, "interviewDate", dateFrom, dateTo)
    total = await db.questionnaireinterview.count(where=where)
    items = await db.questionnaireinterview.find_many(
        where=where,
        include=INTERVIEW_INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(public_encode(items), total, page, page_size)


# Scalar fields a "create for an existing set" may back-fill on the canonical interview — but ONLY
# when that field is still empty, so one researcher's create can never overwrite another's content.
_MERGEABLE_FILL_FIELDS = ("title", "place", "language", "notes", "interviewDate")


async def merge_into_interview(
    existing: Any, payload: QuestionnaireInterviewCreate, current_user: Any
) -> dict[str, Any]:
    """Fold a create-for-an-already-existing-set into the single canonical interview.

    Fills only empty scalar fields (never clobbers a populated one), upserts the submitted answers
    (``upsert_responses`` already blocks a non-owner from changing someone else's answer), and returns
    the canonical row so the client attaches any media to the shared entry.
    """
    incoming = payload.model_dump()
    fill = {
        field: incoming.get(field)
        for field in _MERGEABLE_FILL_FIELDS
        if not is_empty_value(incoming.get(field)) and is_empty_value(get_value(existing, field))
    }
    if fill:
        await db.questionnaireinterview.update(where={"id": existing.id}, data=fill)
    if payload.responses:
        await upsert_responses(existing.id, payload.responses, current_user)
    hydrated = await db.questionnaireinterview.find_unique(
        where={"id": existing.id}, include=INTERVIEW_INCLUDE
    )
    return public_encode(hydrated)


@router.post("/interviews", status_code=status.HTTP_201_CREATED)
async def create_interview(
    payload: QuestionnaireInterviewCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    # One interview per exact artisan set: if one already exists for this set, fold into it instead
    # of creating a duplicate. This holds for EVERY client (web + old/new app) regardless of UI.
    set_key = artisan_set_key(payload.artisanIds)
    if set_key:
        existing = await db.questionnaireinterview.find_unique(
            where={"artisanSetKey": set_key}, include=INTERVIEW_INCLUDE
        )
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user)

    data = clean_data(payload.model_dump(exclude={"artisanIds", "responses"}))
    data = await attach_location(data)
    data["createdById"] = current_user.id
    data["artisanSetKey"] = set_key
    merge_field_provenance(data, current_user, previous=None)
    jsonify_metadata(data)
    try:
        created = await db.questionnaireinterview.create(data=data)
    except UniqueViolationError:
        # Race: a concurrent request won the create for this set. Fold into the canonical row.
        existing = await db.questionnaireinterview.find_unique(
            where={"artisanSetKey": set_key}, include=INTERVIEW_INCLUDE
        )
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user)
        raise
    if payload.artisanIds:
        await replace_interview_artisans(created.id, payload.artisanIds)
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user)
    hydrated = await db.questionnaireinterview.find_unique(where={"id": created.id}, include=INTERVIEW_INCLUDE)
    return public_encode(hydrated)


@router.get("/interviews/by-artisans")
async def interview_for_artisan_set(
    artisanIds: list[str] = Query(default=[]),
    _: Any = Depends(get_current_user),
) -> dict[str, Any] | None:
    """The single canonical interview for an EXACT set of artisans, or ``null``.

    Lets a client show the one shared entry — and which sections/questions others have already
    recorded — before offering to create. A subset of the artisans is a different set, so it will not
    match here. Declared before ``/{interview_id}`` so the literal path wins the route match.
    """
    set_key = artisan_set_key(artisanIds)
    if not set_key:
        return None
    interview = await db.questionnaireinterview.find_unique(
        where={"artisanSetKey": set_key}, include=INTERVIEW_INCLUDE
    )
    return public_encode(interview) if interview else None


COMPLETION_STATUSES = {"COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"}


async def _derived_completed_sections() -> dict[str, set[str]]:
    """artisanId -> set of sectionIds with recorded content in ANY interview containing the artisan.

    "Containing" covers the artisan interviewed alone, in a group, or as part of a larger superset —
    a section recorded for any interview the artisan belongs to counts as completed for that artisan.
    A section counts as recorded in an interview when it has a non-empty response, or media tagged
    (in ``extraMetadata``) with that section's question or code.
    """
    questions = await db.questionnairequestion.find_many()
    section_by_question = {q.id: q.sectionId for q in questions if q.sectionId}
    sections = await db.questionnairesection.find_many()
    section_id_by_code = {s.code: s.id for s in sections}
    # Normalised code -> sectionId, matching how the app builds clip filenames (uppercase, strip
    # non-alphanumerics) so the SECTION_QUESTION_... nomenclature resolves back to its section.
    section_id_by_norm_code = {_norm_code(s.code): s.id for s in sections if _norm_code(s.code)}

    interviews = await db.questionnaireinterview.find_many(
        include={"artisans": True, "responses": True, "media": True}
    )
    valid_codes = {_norm_code(s.code) for s in sections if _norm_code(s.code)}
    completed: dict[str, set[str]] = {}
    for interview in interviews:
        recorded: set[str] = set()
        # Title-named sections: the only signal for pre-nomenclature recordings (titled by section).
        for code in section_codes_from_title(interview.title, valid_codes):
            section_id = section_id_by_norm_code.get(code)
            if section_id:
                recorded.add(section_id)
        for response in interview.responses or []:
            section_id = section_by_question.get(response.questionId)
            if section_id and not is_empty_value(response.answerText):
                recorded.add(section_id)
        for media in interview.media or []:
            meta = media.extraMetadata if isinstance(media.extraMetadata, dict) else None
            if meta:
                section_id = section_by_question.get(meta.get("questionId"))
                if section_id:
                    recorded.add(section_id)
                code_section = section_id_by_code.get(meta.get("sectionCode"))
                if code_section:
                    recorded.add(code_section)
            # Fallback: the audio clip filename leads with the section code
            # (SECTIONCODE_QUESTION_INTERVIEW_DURATION_STAMP), the only section signal carried by the
            # app's recorded questionnaire clips.
            first_token = (media.originalFilename or "").split("_", 1)[0]
            code_section = section_id_by_norm_code.get(_norm_code(first_token))
            if code_section:
                recorded.add(code_section)
        if recorded:
            for link in interview.artisans or []:
                completed.setdefault(link.artisanId, set()).update(recorded)
    return completed


@router.get("/completion")
async def completion_matrix(
    artisanId: str | None = None,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Completion matrix: artisans (rows) x active sections (columns). Each populated cell carries the
    data-derived completion flag and any admin override (COMPLETED/NEEDS_REVIEW/NEEDS_REDO). Pass
    ``artisanId`` to scope it to a single artisan (the per-artisan View Data view)."""
    sections = await db.questionnairesection.find_many(
        where={"isActive": True}, order={"sortOrder": "asc"}
    )
    if artisanId:
        artisan = await require_record(db.artisan, artisanId)
        artisans = [artisan]
    else:
        artisans = await db.artisan.find_many(order={"name": "asc"})
    artisan_ids = {a.id for a in artisans}

    derived = await _derived_completed_sections()
    status_where = {"artisanId": artisanId} if artisanId else {}
    overrides = await db.questionnairesectionstatus.find_many(
        where=status_where, include={"setBy": True}
    )
    override_by_cell = {
        (o.artisanId, o.sectionId): o for o in overrides if o.artisanId in artisan_ids
    }

    section_ids = {s.id for s in sections}
    cells: list[dict[str, Any]] = []
    for artisan in artisans:
        derived_ids = derived.get(artisan.id, set())
        for section in sections:
            override = override_by_cell.get((artisan.id, section.id))
            is_derived = section.id in derived_ids
            if override is None and not is_derived:
                continue
            cells.append(
                {
                    "artisanId": artisan.id,
                    "sectionId": section.id,
                    "derived": is_derived,
                    "status": override.status if override else None,
                    "setByName": get_value(override.setBy, "name") if override else None,
                }
            )
    # Defensively drop overrides that point at now-inactive sections from the matrix output.
    cells = [c for c in cells if c["sectionId"] in section_ids]
    return {
        "sections": [
            {"id": s.id, "code": s.code, "title": s.title, "sortOrder": s.sortOrder}
            for s in sections
        ],
        "artisans": [{"id": a.id, "name": a.name} for a in artisans],
        "cells": cells,
    }


@router.put("/completion")
async def set_completion_cell(
    payload: CompletionCellUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Admin/master-admin only: set or clear the manual status for one (artisan, section) cell."""
    await require_record(db.artisan, payload.artisanId)
    await require_record(db.questionnairesection, payload.sectionId)
    key = {"artisanId_sectionId": {"artisanId": payload.artisanId, "sectionId": payload.sectionId}}
    if payload.status is None:
        await db.questionnairesectionstatus.delete_many(
            where={"artisanId": payload.artisanId, "sectionId": payload.sectionId}
        )
        return {"cleared": True}
    if payload.status not in COMPLETION_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"status must be one of {sorted(COMPLETION_STATUSES)} or null",
        )
    record = await db.questionnairesectionstatus.upsert(
        where=key,
        data={
            "create": {
                "artisanId": payload.artisanId,
                "sectionId": payload.sectionId,
                "status": payload.status,
                "setById": current_user.id,
            },
            "update": {"status": payload.status, "setById": current_user.id},
        },
    )
    return public_encode(record)


@router.get("/interviews/{interview_id}")
async def get_interview(interview_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    interview = await db.questionnaireinterview.find_unique(where={"id": interview_id}, include=INTERVIEW_INCLUDE)
    interview = await require_record(db.questionnaireinterview, interview_id) if not interview else interview
    return public_encode(interview)


@router.patch("/interviews/{interview_id}")
async def update_interview(
    interview_id: str,
    payload: QuestionnaireInterviewUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    data = clean_data(payload.model_dump(exclude_unset=True, exclude={"artisanIds", "responses"}))
    data = await attach_location(data)
    privileged = await guard_record_edit(interview, current_user, data, "questionnaire")
    merge_field_provenance(data, current_user, previous=interview)
    jsonify_metadata(data)
    if data:
        await db.questionnaireinterview.update(where={"id": interview_id}, data=data)
    if payload.artisanIds is not None:
        link_count = await db.questionnaireinterviewartisan.count(where={"interviewId": interview_id})
        if not privileged:
            assert_can_contribute_relation(interview, current_user, link_count > 0, "artisanIds")
        await replace_interview_artisans(interview_id, payload.artisanIds)
    if payload.responses is not None:
        await upsert_responses(interview_id, payload.responses, current_user)
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id}, include=INTERVIEW_INCLUDE)
    return public_encode(updated)


@router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(interview_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.questionnaireinterview, interview_id)
    await db.questionnaireinterview.delete(where={"id": interview_id})
