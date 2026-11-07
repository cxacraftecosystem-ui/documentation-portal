import csv
import re
from datetime import UTC, datetime, timedelta
from io import StringIO
from typing import Any

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
from fastapi.encoders import jsonable_encoder
from fastapi.responses import Response
from prisma.errors import UniqueViolationError

from app.api.routes.export import csv_response
from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_create_records,
    assert_can_delete,
    can_access_review,
    can_create_records,
    can_manage_questionnaire,
    get_current_user,
    get_value,
    is_admin,
    is_content_admin,
    is_empty_value,
    require_admin,
    require_content_admin,
    require_questionnaire_manager,
)
from app.questionnaire import authoring as questionnaire_authoring
from app.questionnaire import filters as shared_filters
from app.questionnaire import kinds as questionnaire_kinds
from app.questionnaire import sessions as v2_sessions
from app.questionnaire import shims_fr as v2_shims
from app.questionnaire.common import fail as v2_fail
from app.questionnaire.locks import lock_key
from app.schemas.questionnaire import (
    MAX_HELP_CHARS,
    CompletionCellUpdate,
    QuestionnaireCreate,
    QuestionnaireDefaultUpdate,
    QuestionnaireInterviewCreate,
    QuestionnaireInterviewUpdate,
    QuestionnaireQuestionCreate,
    QuestionnaireQuestionReorder,
    QuestionnaireQuestionUpdate,
    QuestionnaireResponseInput,
    QuestionnaireReuse,
    QuestionnaireSectionCreate,
    QuestionnaireSectionReorder,
    QuestionnaireSectionUpdate,
    QuestionnaireUpdate,
)
from app.services import answer_drafts, cross_portal_codes, naming, questionnaire_library
from app.services import completeness as completeness_module
from app.services.access import (
    guard_record_edit,
    owner_download_scope,
    record_edit_privilege,
)
from app.services.artisan_merge import redirect_merged_artisans
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.questionnaire_consolidation import (
    CSV_COLUMNS,
    consolidate_for_artisan,
    consolidated_rows,
)
from app.services.questionnaire_instruments import (
    default_questionnaire_id,
    require_questionnaire,
    resolve_questionnaire_id,
)
from app.services.questionnaire_xlsx import (
    MAX_SECTIONS,
    PRO_FORMA_FILENAME,
    XLSX_MIME,
    ParsedQuestionnaire,
    QuestionnaireXlsxError,
    build_pro_forma,
    build_question_set_workbook,
    build_questionnaire_workbook,
    derive_section_code,
    download_filename,
    parse_questionnaire_workbook,
    question_set_filename,
)
from app.services.record_filters import (
    artisan_workshop_clause,
    resolve_workshop_ids,
    workshop_clause,
)
from app.services.records import (
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
    contains,
    count_and_page,
    hydrate_relations,
    jsonify_metadata,
    merge_field_provenance,
    public_encode,
    require_record,
    resubmit_status,
    viewable_where,
)
from app.services.standard_values import StandardValueError, apply_interview_values
from app.services.uploads import read_upload_bounded
from app.services.workshop_access import (
    WorkshopSubmissionCheck,
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)
from app.services.workshop_inference import count_unassigned_interviews
from prisma import Json

router = APIRouter()
questionnaire_router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])
instruments_router = APIRouter(prefix="/questionnaires", tags=["questionnaires"])


def _norm_code(value: str | None) -> str:
    return "".join(ch for ch in (value or "") if ch.isalnum()).upper()


_SECTION_IN_TITLE_RE = re.compile(r"sections?\s+([a-zA-Z][a-zA-Z\s,&/\-]{0,24})", re.IGNORECASE)
_LETTER_RUN_RE = re.compile(r"\b([a-zA-Z](?:\s*,\s*[a-zA-Z]){2,})\b")
_REAL_WORD_RE = re.compile(r"\b(?!and\b)[a-zA-Z]{2,}\b", re.IGNORECASE)


def section_codes_from_title(title: str | None, valid_codes: set[str]) -> set[str]:
    if not title:
        return set()
    found: set[str] = set()
    for match in _SECTION_IN_TITLE_RE.finditer(title):
        run = _REAL_WORD_RE.split(match.group(1))[0]
        for token in re.split(r"[^a-zA-Z]+", run):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    for match in _LETTER_RUN_RE.finditer(title):
        for token in re.split(r"[^a-zA-Z]+", match.group(1)):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    return found


RELATIONS = (
    Relation("createdBy", "user", "createdById"),
    Relation("location", "location", "locationId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("questionnaire", "questionnaire", "questionnaireId"),
    Relation(
        "artisans",
        "questionnaireinterviewartisan",
        "interviewId",
        many=True,
        include={"artisan": {"include": {"craft": True}}},
    ),
    Relation(
        "responses",
        "questionnaireresponse",
        "interviewId",
        many=True,
        include={"question": True, "answeredBy": True},
    ),
    Relation("media", "mediafile", "questionnaireInterviewId", many=True, where={"removedAt": None}),
)


_DUPLICATE_SET_DETAIL = (
    "An interview already exists for this exact set of artisans. There is a single shared entry per "
    "artisan set — open it to add or view answers instead of creating another."
)

_DUPLICATE_SET_CODE = "artisan_set_taken"


async def duplicate_set_conflict(interview_id: str, set_key: str | None) -> HTTPException:
    holder: dict[str, Any] | None = None
    if set_key:
        interview = await db.questionnaireinterview.find_unique(where={"id": interview_id})
        questionnaire_id = get_value(interview, "questionnaireId") if interview else None
        if questionnaire_id:
            row = await db.questionnaireinterview.find_first(
                where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
            )
            if row is not None and get_value(row, "id") != interview_id:
                holder = {"id": get_value(row, "id"), "title": get_value(row, "title")}
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail={
            "code": _DUPLICATE_SET_CODE,
            "message": _DUPLICATE_SET_DETAIL,
            "holder": holder,
        },
    )


def artisan_set_key(artisan_ids: list[str]) -> str | None:
    unique = sorted({aid for aid in artisan_ids if aid})
    return ",".join(unique) if unique else None


def default_interview_date(data: dict[str, Any]) -> dict[str, Any]:
    if data.get("interviewDate") is None:
        data["interviewDate"] = data.get("recordedAt") or datetime.now(UTC)
    return data


async def next_section_sort_order(questionnaire_id: str) -> int:
    sections = await db.questionnairesection.find_many(
        where={"questionnaireId": questionnaire_id}, order={"sortOrder": "desc"}, take=1
    )
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


FORM_DELETE_REFUSAL = "Only the questionnaire's author or an admin can delete from it."
FORM_EDIT_REFUSAL = "Only the questionnaire's author or an admin can edit it."


def is_form_author(form: Any, user: Any) -> bool:
    return questionnaire_authoring.is_form_author(form, user)


async def assert_may_delete_from_form(questionnaire_id: str | None, user: Any) -> None:
    if not await may_change_form(questionnaire_id, user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_DELETE_REFUSAL)


async def may_change_form(questionnaire_id: str | None, user: Any) -> bool:
    if is_content_admin(user):
        return True
    form = await db.questionnaire.find_unique(where={"id": questionnaire_id}) if questionnaire_id else None
    return questionnaire_authoring.may_change_form(user, form)


async def assert_may_edit_form(questionnaire_id: str | None, user: Any) -> None:
    if not await may_change_form(questionnaire_id, user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_EDIT_REFUSAL)


async def require_form_editor(questionnaire_id: str, current_user: Any = Depends(get_current_user)) -> Any:
    await assert_may_edit_form(questionnaire_id, current_user)
    return current_user


def section_question_data(section: Any) -> dict[str, str]:
    return {"sectionId": section.id, "sectionCode": section.code, "sectionTitle": section.title}


async def section_payloads(questionnaire_id: str, active_only: bool = True) -> list[dict[str, Any]]:
    section_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    question_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    if active_only:
        section_where["isActive"] = True
        question_where["isActive"] = True
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


class _SetHeld(Exception):
    pass


async def replace_interview_artisans(interview_id: str, artisan_ids: list[str]) -> None:
    unique_ids = sorted({aid for aid in artisan_ids if aid})
    set_key = artisan_set_key(unique_ids)

    current = await db.questionnaireinterviewartisan.find_many(where={"interviewId": interview_id})
    if sorted({link.artisanId for link in current}) == unique_ids:
        existing = await db.questionnaireinterview.find_unique(where={"id": interview_id})
        if existing is not None and existing.artisanSetKey != set_key:
            await db.questionnaireinterview.update(
                where={"id": interview_id}, data={"artisanSetKey": set_key}
            )
        return

    if unique_ids:
        found = await db.artisan.find_many(where={"id": {"in": unique_ids}})
        if len(found) != len(unique_ids):
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    try:
        if set_key and not v2_shims.enabled():
            row = await db.questionnaireinterview.find_unique(where={"id": interview_id})
            holders = await db.questionnaireinterview.find_many(
                where={"questionnaireId": row.questionnaireId if row else None, "artisanSetKey": set_key, "mergedIntoId": None}
            )
            if any(h.id != interview_id for h in holders):
                raise _SetHeld()
        await db.questionnaireinterview.update(where={"id": interview_id}, data={"artisanSetKey": set_key})
    except (UniqueViolationError, _SetHeld) as exc:
        raise (await duplicate_set_conflict(interview_id, set_key)) from exc
    await db.questionnaireinterviewartisan.delete_many(where={"interviewId": interview_id})
    if unique_ids:
        await db.questionnaireinterviewartisan.create_many(
            data=[{"interviewId": interview_id, "artisanId": aid} for aid in unique_ids]
        )


def source_of(response: Any) -> str | None:
    return getattr(response, "answerSource", None) or None


async def upsert_responses(
    interview_id: str, responses: list[Any], current_user: Any, questionnaire_id: str
) -> None:
    if not responses:
        return
    question_ids = sorted({r.questionId for r in responses if r.questionId})
    existing_rows, questions = await gather_reads(
        db.questionnaireresponse.find_many(
            where={"interviewId": interview_id, "questionId": {"in": question_ids}}
        ),
        db.questionnairequestion.find_many(where={"id": {"in": question_ids}}),
    )
    known = {q.id for q in questions}
    if len(known) != len(question_ids):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    strays = sorted({q.id for q in questions if q.questionnaireId != questionnaire_id})
    if strays:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"These questions belong to a different questionnaire than this interview: "
                f"{strays}. Reload the questionnaire and try again."
            ),
        )
    by_question = {row.questionId: row for row in existing_rows}

    to_create: list[dict[str, Any]] = []
    to_update: list[tuple[str, dict[str, Any]]] = []
    for response in responses:
        existing = by_question.get(response.questionId)
        if existing is None:
            to_create.append(
                {
                    "interviewId": interview_id,
                    "questionId": response.questionId,
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answerSource": source_of(response),
                    "answeredById": current_user.id,
                }
            )
            continue
        if (
            existing.answerText
            and existing.answeredById != current_user.id
            and not is_content_admin(current_user)
        ):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the original answer contributor or an admin can change this response",
            )
        if existing.answerText == response.answerText and existing.notes == response.notes:
            continue
        to_update.append(
            (
                existing.id,
                {
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answerSource": (
                        source_of(response)
                        if existing.answerText != response.answerText
                        else get_value(existing, "answerSource")
                    ),
                    "answeredById": current_user.id,
                    "answerOrigin": answer_drafts.origin_after_edit(
                        getattr(existing, "answerOrigin", None),
                        existing.answerText,
                        response.answerText,
                    ),
                },
            )
        )

    if to_create:
        await db.questionnaireresponse.create_many(data=to_create)
    for row_id, data in to_update:
        await db.questionnaireresponse.update(where={"id": row_id}, data=data)


@questionnaire_router.get("/questions")
async def list_questions(
    _: Any = Depends(get_current_user),
    sectionCode: str | None = None,
    activeOnly: bool = True,
    questionnaireId: str | None = None,
    workshopId: str | None = None,
) -> list[dict[str, Any]]:
    qid = await resolve_questionnaire_id(questionnaireId, workshopId)
    sections = await section_payloads(qid, activeOnly)
    flattened = [
        question
        for section in sections
        for question in section["questions"]
        if not sectionCode or question["sectionCode"] == sectionCode
    ]
    return public_encode(flattened)


@questionnaire_router.get("/sections")
async def list_sections(
    _: Any = Depends(get_current_user),
    activeOnly: bool = True,
    questionnaireId: str | None = None,
    workshopId: str | None = None,
) -> list[dict[str, Any]]:
    qid = await resolve_questionnaire_id(questionnaireId, workshopId)
    return await section_payloads(qid, activeOnly)


@questionnaire_router.post("/sections", status_code=status.HTTP_201_CREATED)
async def create_section(
    payload: QuestionnaireSectionCreate,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    questionnaire_id = await resolve_questionnaire_id(payload.questionnaireId)
    await assert_may_edit_form(questionnaire_id, user)
    sort_order = payload.sortOrder or await next_section_sort_order(questionnaire_id)
    try:
        created = await db.questionnairesection.create(
            data={
                "questionnaireId": questionnaire_id,
                "code": payload.code.strip(),
                "title": payload.title.strip(),
                "sortOrder": sort_order,
                "isActive": payload.isActive,
            }
        )
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"A section with code “{payload.code.strip()}” (or that position) already exists "
                f"in this questionnaire."
            ),
        ) from exc
    return public_encode(created)


@questionnaire_router.patch("/sections/{section_id}")
async def update_section(
    section_id: str,
    payload: QuestionnaireSectionUpdate,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    section = await require_section(section_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if data.get("isActive") is False and section.isActive:
        await assert_may_delete_from_form(section.questionnaireId, user)
    else:
        await assert_may_edit_form(section.questionnaireId, user)
    if "code" in data:
        data["code"] = data["code"].strip()
    if "title" in data:
        data["title"] = data["title"].strip()
    try:
        updated = await db.questionnairesection.update(where={"id": section_id}, data=data)
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Another section in this questionnaire already has that code or position.",
        ) from exc
    if "code" in data or "title" in data:
        await db.questionnairequestion.update_many(
            where={"sectionId": section_id},
            data={"sectionCode": updated.code, "sectionTitle": updated.title},
        )
        await cross_portal_codes.reconcile_question_codes(db, questionnaire_id=updated.questionnaireId)
    if data.get("isActive") is True:
        switched = await _questions_switched_off_with(section_id)
        if switched:
            await db.questionnairequestion.update_many(where={"id": {"in": switched}}, data={"isActive": True})
    return public_encode(updated)


SECTION_DELETE_REVISION = "questionnaire_section"


async def _questions_switched_off_with(section_id: str) -> list[str]:
    rows = await db.query_raw(
        'SELECT "changes" FROM "RecordRevision" WHERE "recordType" = $1 AND "recordId" = $2 ORDER BY "createdAt" DESC LIMIT 1',
        SECTION_DELETE_REVISION, section_id,
    )
    changes = rows[0].get("changes") if rows else None
    if isinstance(changes, str):
        import json

        changes = json.loads(changes)
    return [str(i) for i in ((changes or {}).get("questionsSwitchedOff") or [])]


@questionnaire_router.delete("/sections/{section_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_section(
    section_id: str,
    user: Any = Depends(get_current_user),
) -> None:
    section = await require_section(section_id)
    await assert_may_delete_from_form(section.questionnaireId, user)
    live = await db.questionnairequestion.find_many(where={"sectionId": section_id, "isActive": True})
    await db.questionnairesection.update(where={"id": section_id}, data={"isActive": False})
    await db.questionnairequestion.update_many(where={"sectionId": section_id}, data={"isActive": False})
    await db.recordrevision.create(
        data={"recordType": SECTION_DELETE_REVISION, "recordId": section_id, "editedById": getattr(user, "id", None),
              "changes": Json({"questionsSwitchedOff": [q.id for q in live]})}
    )


@questionnaire_router.post("/sections/reorder")
async def reorder_sections(
    payload: QuestionnaireSectionReorder,
    user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    sections = await db.questionnairesection.find_many(where={"id": {"in": payload.sectionIds}})
    by_id = {section.id: section for section in sections}
    if len(by_id) != len(set(payload.sectionIds)):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    instrument_ids = {section.questionnaireId for section in sections}
    if len(instrument_ids) != 1:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"sectionIds must all belong to one questionnaire; these span "
                f"{len(instrument_ids)}."
            ),
        )
    questionnaire_id = next(iter(instrument_ids))
    await assert_may_edit_form(questionnaire_id, user)
    for index, section_id in enumerate(payload.sectionIds):
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": -(index + 1)})
    for index, section_id in enumerate(payload.sectionIds):
        await db.questionnairesection.update(where={"id": section_id}, data={"sortOrder": index + 1})
    return await section_payloads(questionnaire_id, active_only=False)


@questionnaire_router.post("/questions", status_code=status.HTTP_201_CREATED)
async def create_question(
    payload: QuestionnaireQuestionCreate,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    section = await require_section(payload.sectionId)
    await assert_may_edit_form(section.questionnaireId, user)
    sort_order = payload.sortOrder or await next_question_sort_order(section.id)
    created = await db.questionnairequestion.create(
        data={
            "questionnaireId": section.questionnaireId,
            **section_question_data(section),
            "prompt": payload.prompt.strip(),
            "questionCode": cross_portal_codes.question_code(section.code, payload.prompt.strip()),
            "helpText": payload.helpText,
            "isRequired": payload.isRequired,
            "artisanField": payload.artisanField,
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@questionnaire_router.patch("/questions/{question_id}")
async def update_question(
    question_id: str,
    payload: QuestionnaireQuestionUpdate,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    question = await require_record(db.questionnairequestion, question_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if data.get("isActive") is False and question.isActive:
        await assert_may_delete_from_form(question.questionnaireId, _)
    else:
        await assert_may_edit_form(question.questionnaireId, _)
    if "prompt" in data:
        data["prompt"] = data["prompt"].strip()
    if "helpText" in payload.model_fields_set and payload.helpText is None:
        data["helpText"] = None
    if "artisanField" in payload.model_fields_set and payload.artisanField is None:
        data["artisanField"] = None
    section_id = data.pop("sectionId", None)
    if section_id:
        section = await require_section(section_id)
        if section.questionnaireId != question.questionnaireId:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    f"A question cannot be moved between questionnaires. {question.sectionCode} "
                    f"#{question.sortOrder} belongs to «{question.questionnaireId}»; the target "
                    f"section belongs to «{section.questionnaireId}»."
                ),
            )
        if section.id != question.sectionId:
            await _refuse_reparent_with_takes([question])
        data.update(section_question_data(section))
        if "sortOrder" not in data or data["sortOrder"] is None:
            data["sortOrder"] = await next_question_sort_order(section.id)
    elif question.sectionId and ("sectionCode" not in data or "sectionTitle" not in data):
        section = await require_section(question.sectionId)
        data.update({"sectionCode": section.code, "sectionTitle": section.title})
    data["questionCode"] = cross_portal_codes.question_code(
        data.get("sectionCode", question.sectionCode), data.get("prompt", question.prompt)
    )
    if "prompt" in data and data["prompt"] != question.prompt and await _question_has_evidence(question):
        return public_encode(await _supersede_question(question, data, getattr(_, "id", None)))
    updated = await db.questionnairequestion.update(where={"id": question_id}, data=data)
    changed = {
        key: {"old": jsonable_encoder(getattr(question, key, None)), "new": jsonable_encoder(value)}
        for key, value in data.items()
        if key in ("prompt", "sectionId", "sortOrder", "helpText", "isActive", "artisanField")
        and getattr(question, key, None) != value
    }
    if changed and v2_shims.enabled():
        await db.recordrevision.create(
            data={"recordType": "questionnaire_question", "recordId": question_id, "editedById": getattr(_, "id", None),
                  "changes": Json(changed)}
        )
    return public_encode(updated)


async def _question_has_evidence(question: Any) -> bool:
    rows = await db.query_raw(
        'SELECT (EXISTS (SELECT 1 FROM "QuestionnaireRecordingTarget" t WHERE t."askedQuestionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireAnswerRevision" v WHERE v."askedQuestionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireResponse" r WHERE r."questionId" = $1) '
        'OR EXISTS (SELECT 1 FROM "QuestionnaireAnswerDraft" d WHERE d."questionId" = $1)) AS used',
        question.id,
    )
    return bool(rows and rows[0].get("used"))


async def _supersede_question(question: Any, data: dict[str, Any], editor_id: str | None) -> Any:
    fields = ("questionnaireId", "sectionId", "sectionCode", "sectionTitle", "prompt", "questionCode", "helpText",
              "isRequired", "artisanField", "sortOrder", "isActive")
    row = {key: data.get(key, getattr(question, key, None)) for key in fields}
    async with db.tx() as tx:
        await tx.execute_raw(
            'UPDATE "QuestionnaireQuestion" SET "retiredAt" = now(), "isActive" = false '
            'WHERE "id" = $1',
            question.id,
        )
        created = await tx.questionnairequestion.create(data={
            **{k: v for k, v in row.items() if v is not None},
            "lineageId": getattr(question, "lineageId", None) or question.id,
            "displayCode": getattr(question, "displayCode", None),
        })
        await tx.execute_raw('UPDATE "QuestionnaireQuestion" SET "supersededById" = $2 WHERE "id" = $1', question.id, created.id)
        await tx.recordrevision.create(
            data={"recordType": "questionnaire_question", "recordId": question.id, "editedById": editor_id,
                  "changes": Json({"supersededBy": created.id, "prompt": {"old": question.prompt, "new": row["prompt"]}})}
        )
    return await db.questionnairequestion.find_unique(where={"id": created.id})


async def _refuse_reparent_with_takes(questions: list[Any]) -> None:
    lineages = sorted({getattr(q, "lineageId", None) or q.id for q in questions})
    takes = await db.query_raw(
        'SELECT count(*) AS n FROM "QuestionnaireRecordingTarget" t JOIN "QuestionnaireRecording" r '
        'ON r."id" = t."recordingId" WHERE r."removedAt" IS NULL AND t."questionLineageId" = ANY($1::text[])',
        lineages,
    )
    if takes and int(takes[0]["n"]):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "HAS_RECORDINGS", "message": "This question has recordings. Add a new question in the other section instead.",
                    "count": int(takes[0]["n"])},
        )


@questionnaire_router.delete("/questions/{question_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_question(
    question_id: str,
    user: Any = Depends(get_current_user),
) -> None:
    question = await require_record(db.questionnairequestion, question_id)
    await assert_may_delete_from_form(question.questionnaireId, user)
    await db.questionnairequestion.update(where={"id": question_id}, data={"isActive": False})


@questionnaire_router.post("/questions/reorder")
async def reorder_questions(
    payload: QuestionnaireQuestionReorder,
    user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    section = await require_section(payload.sectionId)
    await assert_may_edit_form(section.questionnaireId, user)
    questions = await db.questionnairequestion.find_many(where={"id": {"in": payload.questionIds}})
    by_id = {question.id: question for question in questions}
    if len(by_id) != len(set(payload.questionIds)):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    strays = sorted(
        q.id for q in questions if q.questionnaireId != section.questionnaireId
    )
    if strays:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                f"A question cannot be moved between questionnaires. These belong to a different "
                f"one from section {section.code}: {strays}."
            ),
        )
    moving = [q for q in questions if q.sectionId != section.id]
    if moving:
        await _refuse_reparent_with_takes(moving)
    for index, question_id in enumerate(payload.questionIds):
        await db.questionnairequestion.update(
            where={"id": question_id},
            data={**section_question_data(section), "sortOrder": index + 1},
        )
    return await section_payloads(section.questionnaireId, active_only=False)


@questionnaire_router.get("/interviews")
async def list_interviews(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    workshopIds: list[str] | None = Query(None),
    questionnaireId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    createdBy: str | None = None,
    completeness: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}

    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)

    if search:
        where["OR"] = [{"title": contains(search)}, {"place": contains(search)}, {"notes": contains(search)}]
    if artisanId:
        where["artisans"] = {"some": {"artisanId": artisanId}}
    if workshopId:
        where["workshopId"] = workshopId
    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        ids, include_unassigned = resolved_workshops
        clause = workshop_clause(ids, include_unassigned)
        and_filters.append(clause if clause else {"id": {"in": []}})
    if questionnaireId:
        where["questionnaireId"] = questionnaireId
    where["mergedIntoId"] = None
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    if completeness_mode := completeness_module.parse_mode(completeness):
        and_filters.append(completeness_module.missing_where("interview", completeness_mode))
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "interviewDate", dateFrom, dateTo)
    total, items = await count_and_page(
        db.questionnaireinterview,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    encoded = await completeness_module.attach("interview", public_encode(items, current_user), client=db)
    return page_payload(encoded, total, page, page_size)


_MERGEABLE_FILL_FIELDS = ("title", "place", "language", "notes", "interviewDate", "workshopId")


async def merge_into_interview(
    existing: Any,
    payload: QuestionnaireInterviewCreate,
    current_user: Any,
    check: WorkshopSubmissionCheck | None = None,
) -> dict[str, Any]:
    incoming = payload.model_dump()
    fill = {
        field: incoming.get(field)
        for field in _MERGEABLE_FILL_FIELDS
        if not is_empty_value(incoming.get(field)) and is_empty_value(get_value(existing, field))
    }
    if "workshopId" in fill:
        existing_extra = get_value(existing, "extraMetadata")
        if isinstance(existing_extra, dict):
            fill["extraMetadata"] = dict(existing_extra)
        stamp_workshop_submission(fill, check=check)
        pin_pending_if_late(fill, current_user, check=check, record=existing)
        jsonify_metadata(fill)
    canonical = existing
    if fill:
        canonical = await db.questionnaireinterview.update(where={"id": existing.id}, data=fill)
    if payload.responses:
        await upsert_responses(
            existing.id, payload.responses, current_user, get_value(existing, "questionnaireId")
        )
    await hydrate_relations([canonical], RELATIONS)
    return await completeness_module.attach("interview", public_encode(canonical, current_user), client=db)


def interview_title_from(
    people: list[Any], when: datetime | None, *, workshop: dict[str, Any] | None = None
) -> str:
    moment = when or datetime.now(UTC)
    day = naming.as_ist(moment if moment.tzinfo else moment.replace(tzinfo=UTC))
    return naming.session_title(naming.TitleInput(
        sessionDate=day.date() if isinstance(day, datetime) else day,
        workshop=workshop,
        participants=[p if isinstance(p, naming.Person) else naming.Person(name=str(p)) for p in people if p],
    ))


async def automatic_interview_title(
    artisan_ids: list[str], questionnaire_id: str | None, when: datetime | None
) -> str:
    ids = [aid for aid in artisan_ids if aid]
    people: list[Any] = []
    for aid in ids:
        artisan = await db.artisan.find_unique(where={"id": aid})
        if artisan is not None:
            people.append(naming.Person(name=get_value(artisan, "name"), localName=get_value(artisan, "localName")))
    return interview_title_from(people, when)


CREATE_TX_MAX_WAIT = timedelta(seconds=15)
CREATE_TX_TIMEOUT = timedelta(seconds=30)


async def create_interview_atomically(
    data: dict[str, Any], questionnaire_id: str, set_key: str | None, artisan_ids: list[str]
) -> tuple[Any | None, Any | None]:
    unique_ids = sorted({aid for aid in artisan_ids if aid})
    if unique_ids:
        found = await db.artisan.find_many(where={"id": {"in": unique_ids}})
        if len(found) != len(unique_ids):
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    async with db.tx(max_wait=CREATE_TX_MAX_WAIT, timeout=CREATE_TX_TIMEOUT) as tx:
        if set_key:
            await lock_key(tx, f"legacy-set:{questionnaire_id}:{set_key}")
            winner = await tx.questionnaireinterview.find_first(
                where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
            )
            if winner is not None:
                return None, winner
        created = await tx.questionnaireinterview.create(data=data)
        if unique_ids:
            await tx.questionnaireinterviewartisan.create_many(
                data=[{"interviewId": created.id, "artisanId": aid} for aid in unique_ids]
            )
    return created, None


@questionnaire_router.post("/interviews", status_code=status.HTTP_201_CREATED)
async def create_interview(
    payload: QuestionnaireInterviewCreate,
    request: Request,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    await redirect_merged_artisans(payload)
    check = await enforce_workshop_submission(current_user, payload.workshopId)

    questionnaire_id = await resolve_questionnaire_id(payload.questionnaireId, payload.workshopId)

    if payload.questionnaireId and payload.workshopId:
        workshop = await db.workshop.find_unique(where={"id": payload.workshopId})
        bound = get_value(workshop, "questionnaireId") if workshop else None
        if bound and bound != payload.questionnaireId and not can_manage_questionnaire(current_user):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    "This workshop uses a different questionnaire. Record the interview on the "
                    "workshop's questionnaire, or ask a questionnaire manager to change which one "
                    "the workshop uses."
                ),
            )

    if v2_shims.enabled():
        v2_shims.assert_old_build_may_create(request.headers)
        if not can_create_records(current_user):
            match = await v2_shims.matching_session(questionnaire_id, payload.workshopId, payload.artisanIds)
            if match:
                attached = await v2_shims.attach_to_session(match, payload, current_user, questionnaire_id)
                await hydrate_relations([attached], RELATIONS)
                return public_encode(attached, current_user)
        assert_can_create_records(current_user)
        created = await v2_shims.create_interview_shim(payload, current_user, questionnaire_id, check)
        await hydrate_relations([created], RELATIONS)
        return public_encode(created, current_user)

    set_key = artisan_set_key(payload.artisanIds)
    if set_key:
        existing = await db.questionnaireinterview.find_first(
            where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
        )
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user, check)

    assert_can_create_records(current_user)
    data = clean_data(payload.model_dump(exclude={"artisanIds", "responses"}))
    _standardise(apply_interview_values, data)
    if not (data.get("title") or "").strip():
        data["title"] = await automatic_interview_title(
            payload.artisanIds, questionnaire_id, payload.recordedAt
        )
    default_interview_date(data)
    data = await attach_location(data)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    data["artisanSetKey"] = set_key
    data["questionnaireId"] = questionnaire_id
    merge_field_provenance(data, current_user, previous=None)
    jsonify_metadata(data)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created, winner = await create_interview_atomically(data, questionnaire_id, set_key, payload.artisanIds)
    except UniqueViolationError:
        created, winner = None, await db.questionnaireinterview.find_first(
            where={"questionnaireId": questionnaire_id, "artisanSetKey": set_key, "mergedIntoId": None}
        )
        if winner is None:
            raise
    if winner is not None:
        return await merge_into_interview(winner, payload, current_user, check)
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user, questionnaire_id)
    await hydrate_relations([created], RELATIONS)
    return await completeness_module.attach("interview", public_encode(created, current_user), client=db)


@questionnaire_router.get("/interviews/by-artisans")
async def interview_for_artisan_set(
    artisanIds: list[str] = Query(default=[]),
    questionnaireId: str | None = None,
    workshopId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any] | None:
    set_key = artisan_set_key(artisanIds)
    if not set_key:
        return None
    qid = await resolve_questionnaire_id(questionnaireId, workshopId)
    interview = await db.questionnaireinterview.find_first(
        where={"questionnaireId": qid, "artisanSetKey": set_key, "mergedIntoId": None}
    )
    if not interview:
        return None
    await hydrate_relations([interview], RELATIONS)
    return public_encode(interview, current_user)


COMPLETION_STATUSES = {"COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"}


async def _zero() -> int:
    return 0


async def _derived_completed_sections(
    questionnaire_id: str,
    artisan_id: str | None = None,
    workshop_ids: list[str] | None = None,
) -> dict[str, set[str]]:
    interview_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    if artisan_id:
        interview_where["artisans"] = {"some": {"artisanId": artisan_id}}
    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        clause = workshop_clause(ids, include_unassigned)
        interview_where.setdefault("AND", []).append(clause if clause else {"id": {"in": []}})
    questions, sections, interviews = await gather_reads(
        db.questionnairequestion.find_many(where={"questionnaireId": questionnaire_id}),
        db.questionnairesection.find_many(where={"questionnaireId": questionnaire_id}),
        db.questionnaireinterview.find_many(
            where={"AND": [interview_where, {"mergedIntoId": None}]},
            include={"artisans": True, "responses": True, "media": {"where": {"removedAt": None}}},
        ),
    )
    section_by_question = {q.id: q.sectionId for q in questions if q.sectionId}
    section_id_by_code = {s.code: s.id for s in sections}
    section_id_by_norm_code = {_norm_code(s.code): s.id for s in sections if _norm_code(s.code)}
    valid_codes = {_norm_code(s.code) for s in sections if _norm_code(s.code)}
    completed: dict[str, set[str]] = {}
    for interview in interviews:
        recorded: set[str] = set()
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
            first_token = (media.originalFilename or "").split("_", 1)[0]
            code_section = section_id_by_norm_code.get(_norm_code(first_token))
            if code_section:
                recorded.add(code_section)
        if recorded:
            for link in interview.artisans or []:
                completed.setdefault(link.artisanId, set()).update(recorded)
    return completed


@questionnaire_router.get("/completion")
async def completion_matrix(
    artisanId: str | None = None,
    questionnaireId: str | None = None,
    workshopIds: list[str] | None = Query(None),
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    instrument = await require_questionnaire(
        questionnaireId if questionnaireId else await default_questionnaire_id()
    )
    questionnaire_id = instrument.id
    status_where = {"artisanId": artisanId} if artisanId else {}
    artisan_where: dict[str, Any] = {"id": artisanId} if artisanId else {}

    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        artisan_where.setdefault("AND", []).append(
            artisan_workshop_clause(*resolved_workshops)
        )

    unassigned_relevant = resolved_workshops is not None and not resolved_workshops[1]
    sections, artisan_rows, derived, overrides, unassigned_interviews = await gather_reads(
        db.questionnairesection.find_many(
            where={"isActive": True, "questionnaireId": questionnaire_id},
            order={"sortOrder": "asc"},
        ),
        db.artisan.find_many(where=artisan_where, order={"name": "asc"}),
        _derived_completed_sections(questionnaire_id, artisanId, workshopIds),
        db.questionnairesectionstatus.find_many(where=status_where, include={"setBy": True}),
        count_unassigned_interviews([artisanId] if artisanId else None)
        if unassigned_relevant
        else _zero(),
    )
    if (
        artisanId
        and not artisan_rows
        and await db.artisan.find_unique(where={"id": artisanId}) is None
    ):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    artisans = list(artisan_rows)
    artisan_ids = {a.id for a in artisans}

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
    cells = [c for c in cells if c["sectionId"] in section_ids]
    return {
        "questionnaireId": questionnaire_id,
        "questionnaireTitle": instrument.title,
        "sections": [
            {"id": s.id, "code": s.code, "title": s.title, "sortOrder": s.sortOrder}
            for s in sections
        ],
        "artisans": [{"id": a.id, "name": a.name} for a in artisans],
        "cells": cells,
        "unassignedInterviews": int(unassigned_interviews or 0),
        "overridesAreRepositoryWide": True,
    }


@questionnaire_router.put("/completion")
async def set_completion_cell(
    payload: CompletionCellUpdate,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    await require_record(db.artisan, payload.artisanId)
    await require_record(db.questionnairesection, payload.sectionId)
    key = {"artisanId_sectionId": {"artisanId": payload.artisanId, "sectionId": payload.sectionId}}
    if payload.status is None:
        await db.questionnairesectionstatus.delete_many(
            where={"artisanId": payload.artisanId, "sectionId": payload.sectionId}
        )
        return {"cleared": True}
    if payload.status == "MARKED_COMPLETE":
        payload = payload.model_copy(update={"status": "COMPLETED"})
    if payload.status not in COMPLETION_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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


@questionnaire_router.get("/interviews/{interview_id}")
async def get_interview(
    interview_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    await hydrate_relations([interview], RELATIONS)
    return await completeness_module.attach("interview", public_encode(interview, current_user), client=db)


@questionnaire_router.patch("/interviews/{interview_id}")
async def update_interview(
    interview_id: str,
    payload: QuestionnaireInterviewUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    await redirect_merged_artisans(payload)
    data = clean_data(payload.model_dump(exclude_unset=True, exclude={"artisanIds", "responses"}))
    _standardise(apply_interview_values, data, interview)
    data = await attach_location(data)
    check = None
    if "workshopId" in data and data.get("workshopId") != interview.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    privileged = await guard_record_edit(interview, current_user, data, "questionnaire")
    v2_on = v2_shims.enabled()
    if v2_on:
        v2_shims.legacy_title_change(data)
    await apply_status_policy_update(current_user, interview, data)
    stamp_workshop_submission(data, check=check, record=interview)
    pin_pending_if_late(data, current_user, check=check, record=interview)
    merge_field_provenance(data, current_user, previous=interview)
    resubmit_status(interview, current_user, data)
    jsonify_metadata(data)
    if data:
        await db.questionnaireinterview.update(where={"id": interview_id}, data=data)
    if payload.artisanIds is not None:
        link_count = await db.questionnaireinterviewartisan.count(where={"interviewId": interview_id})
        if not privileged:
            assert_can_contribute_relation(interview, current_user, link_count > 0, "artisanIds")
        if not v2_on:
            await replace_interview_artisans(interview_id, payload.artisanIds)
    if payload.responses is not None and not v2_on:
        await upsert_responses(
            interview_id, payload.responses, current_user, get_value(interview, "questionnaireId")
        )
    if v2_on:
        await v2_shims.patch_interview_shim(interview, payload, current_user)
        from app.questionnaire import titles as v2_titles

        await v2_titles.regenerate_title(db, interview_id)
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    await hydrate_relations([updated], RELATIONS)
    return await completeness_module.attach("interview", public_encode(updated, current_user), client=db)


_MARKDOWN_BOLD = re.compile(r"\*\*(.+?)\*\*")
_MARKDOWN_RULE = re.compile(r"(?m)^\s*-{3,}\s*$")


def transcript_answer_text(transcript: str | None) -> str:
    text = _MARKDOWN_BOLD.sub(r"\1", transcript or "")
    text = _MARKDOWN_RULE.sub("", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


_CLIP_QUESTION_CAPTION = re.compile(r"^question\s+audio:\s*([A-Za-z]+?)\s*(\d+)\b\s*-?\s*(.*)$", re.IGNORECASE | re.DOTALL)


def _same_words(a: str | None, b: str | None) -> bool:
    return bool(a) and bool(b) and " ".join(str(a).split()).casefold() == " ".join(str(b).split()).casefold()


def clip_question_id(
    media: Any, questions: list[Any], links: list[dict[str, Any]] | None = None
) -> str | None:
    by_id = {str(get_value(q, "id")): q for q in questions}
    extra = get_value(media, "extraMetadata")
    question_id = extra.get("questionId") if isinstance(extra, dict) else None
    if isinstance(question_id, str) and question_id in by_id:
        return question_id
    for link in links or []:
        asked = link.get("askedQuestionId")
        if asked and str(asked) in by_id:
            return str(asked)
        lineage = link.get("questionLineageId")
        if lineage:
            for q in questions:
                if str(get_value(q, "lineageId") or get_value(q, "id")) == str(lineage):
                    return str(get_value(q, "id"))
    found = _CLIP_QUESTION_CAPTION.match((get_value(media, "caption") or "").strip())
    if found:
        code, prompt = found.group(1).upper(), found.group(3).strip()
        if isinstance(extra, dict) and extra.get("questionPrompt"):
            prompt = str(extra["questionPrompt"])
        matches = [
            q for q in questions
            if (get_value(q, "sectionCode") or "").upper() == code and _same_words(get_value(q, "prompt"), prompt)
        ]
        if len(matches) == 1:
            return str(get_value(matches[0], "id"))
    return None


def question_clip_transcripts(
    media_rows: list[Any], questions: list[Any], links_by_media: dict[str, list[dict[str, Any]]] | None = None
) -> dict[str, str]:
    newest: dict[str, tuple[Any, str]] = {}
    for media in media_rows:
        question_id = clip_question_id(media, questions, (links_by_media or {}).get(str(get_value(media, "id"))))
        text = transcript_answer_text(get_value(media, "transcriptText"))
        if not question_id or not text:
            continue
        stamp = get_value(media, "createdAt")
        held = newest.get(question_id)
        if held is None or (stamp is not None and (held[0] is None or stamp > held[0])):
            newest[question_id] = (stamp, text)
    return {question_id: text for question_id, (_, text) in newest.items()}


async def _clip_question_links(media_rows: list[Any]) -> dict[str, list[dict[str, Any]]]:
    ids = [str(get_value(m, "id")) for m in media_rows if get_value(m, "id")]
    if not ids:
        return {}
    recordings = await db.questionnairerecording.find_many(
        where={"mediaFileId": {"in": ids}, "removedAt": None}, include={"targets": True}
    )
    links: dict[str, list[dict[str, Any]]] = {}
    for recording in recordings:
        links[str(get_value(recording, "mediaFileId"))] = [
            {"askedQuestionId": get_value(t, "askedQuestionId"), "questionLineageId": get_value(t, "questionLineageId")}
            for t in (get_value(recording, "targets") or [])
            if get_value(t, "askedQuestionId") or get_value(t, "questionLineageId")
        ]
    return links


@questionnaire_router.post("/interviews/{interview_id}/apply-transcripts")
async def apply_interview_transcripts(
    interview_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    if not (can_create_records(current_user) or can_access_review(current_user)):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Using transcripts as answers needs Researcher or review access.",
        )
    interview = await require_record(db.questionnaireinterview, interview_id)
    questionnaire_id = get_value(interview, "questionnaireId")
    media_rows, responses, questions = await gather_reads(
        db.mediafile.find_many(where={"questionnaireInterviewId": interview_id, "removedAt": None}),
        db.questionnaireresponse.find_many(where={"interviewId": interview_id}),
        db.questionnairequestion.find_many(where={"questionnaireId": questionnaire_id, "isActive": True}),
    )
    transcripts = question_clip_transcripts(media_rows, questions, await _clip_question_links(media_rows))
    answered = {r.questionId for r in responses if (r.answerText or "").strip()}
    fills = [
        QuestionnaireResponseInput(questionId=qid, answerText=text, answerSource="TRANSCRIPT")
        for qid, text in sorted(transcripts.items())
        if qid not in answered
    ]
    if fills:
        await guard_record_edit(
            interview,
            current_user,
            {"transcriptAnswers": {fill.questionId: fill.answerText for fill in fills}},
            "questionnaire",
        )
        data: dict[str, Any] = {}
        pin_pending_if_late(data, current_user, record=interview)
        resubmit_status(interview, current_user, data)
        await upsert_responses(interview_id, fills, current_user, questionnaire_id)
        if "status" in data:
            await db.questionnaireinterview.update(
                where={"id": interview_id}, data={"status": data["status"]}
            )
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    await hydrate_relations([updated], RELATIONS)
    return {
        "applied": len(fills),
        "skipped": len(transcripts) - len(fills),
        "interview": public_encode(updated, current_user),
    }


_RESPONSE_TEXT_FIELDS = ("answerText", "notes")

_MERGE_CONFLICT_CODE = "merge_answer_conflict"


@questionnaire_router.post("/interviews/{interview_id}/merge-into/{target_id}")
async def merge_interview_into(
    interview_id: str,
    target_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    assert_can_delete(current_user)
    if interview_id == target_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="An interview cannot be merged into itself.",
        )
    source = await require_record(db.questionnaireinterview, interview_id)
    target = await require_record(db.questionnaireinterview, target_id)

    if get_value(source, "questionnaireId") != get_value(target, "questionnaireId"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                "These two interviews were taken on different questionnaires. Answers cannot move "
                "between instruments — they would land under questions the sitting was never asked."
            ),
        )

    privileged = await record_edit_privilege(source, current_user, "questionnaire")
    if not privileged:
        assert_can_contribute_relation(source, current_user, True, "responses")

    source_rows, target_rows = await gather_reads(
        db.questionnaireresponse.find_many(where={"interviewId": interview_id}),
        db.questionnaireresponse.find_many(where={"interviewId": target_id}),
    )
    surviving = {get_value(row, "questionId"): row for row in target_rows}

    conflicts: list[dict[str, Any]] = []
    movable: list[str] = []
    fills: list[tuple[str, dict[str, Any]]] = []
    stale: list[str] = []

    for row in source_rows:
        question_id = get_value(row, "questionId")
        twin = surviving.get(question_id)
        if twin is None:
            movable.append(question_id)
            continue
        clashing = [
            field
            for field in _RESPONSE_TEXT_FIELDS
            if not is_empty_value(get_value(row, field))
            and not is_empty_value(get_value(twin, field))
            and get_value(row, field) != get_value(twin, field)
        ]
        if clashing:
            conflicts.append({"questionId": question_id, "fields": clashing})
            continue
        if all(is_empty_value(get_value(twin, field)) for field in _RESPONSE_TEXT_FIELDS) and any(
            not is_empty_value(get_value(row, field)) for field in _RESPONSE_TEXT_FIELDS
        ):
            stale.append(get_value(twin, "id"))
            movable.append(question_id)
            continue
        fill = {
            field: get_value(row, field)
            for field in _RESPONSE_TEXT_FIELDS
            if not is_empty_value(get_value(row, field)) and is_empty_value(get_value(twin, field))
        }
        if "answerText" in fill:
            fill["answerOrigin"] = get_value(row, "answerOrigin")
        if fill:
            fills.append((get_value(twin, "id"), fill))
        stale.append(get_value(row, "id"))

    if conflicts:
        questions = await db.questionnairequestion.find_many(
            where={"id": {"in": sorted({item["questionId"] for item in conflicts})}}
        )
        by_id = {get_value(question, "id"): question for question in questions}
        named = [
            {
                "questionId": item["questionId"],
                "sectionCode": get_value(by_id.get(item["questionId"]), "sectionCode"),
                "prompt": get_value(by_id.get(item["questionId"]), "prompt"),
                "fields": item["fields"],
            }
            for item in conflicts
        ]
        named.sort(key=lambda item: (item["sectionCode"] or "", item["prompt"] or ""))
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "code": _MERGE_CONFLICT_CODE,
                "message": (
                    f"Both interviews answer {len(named)} {'question' if len(named) == 1 else 'questions'} differently. Nothing was "
                    "moved. Make the answers match, then try again."
                ),
                "questions": named,
            },
        )

    await guard_record_edit(
        source, current_user, {"mergedIntoId": target_id}, "questionnaire", privileged=privileged
    )

    if stale:
        await db.questionnaireresponse.delete_many(where={"id": {"in": sorted(stale)}})
    if movable:
        await db.questionnaireresponse.update_many(
            where={"interviewId": interview_id, "questionId": {"in": sorted(movable)}},
            data={"interviewId": target_id},
        )
    for row_id, fill in fills:
        await db.questionnaireresponse.update(where={"id": row_id}, data=fill)

    if await _has_v2_data(interview_id):
        await v2_sessions.merge_into(interview_id, target_id, current_user)
        survivor = await db.questionnaireinterview.find_unique(where={"id": target_id})
        await hydrate_relations([survivor], RELATIONS)
        return public_encode(survivor, current_user)

    await db.mediafile.update_many(
        where={"questionnaireInterviewId": interview_id},
        data={"questionnaireInterviewId": target_id},
    )

    await db.questionnaireinterview.delete(where={"id": interview_id})

    survivor = await db.questionnaireinterview.find_unique(where={"id": target_id})
    await hydrate_relations([survivor], RELATIONS)
    return public_encode(survivor, current_user)



async def _consolidated_or_404(
    artisan_id: str,
    current_user: Any,
    workshop_ids: list[str] | None = None,
    questionnaire_id: str | None = None,
) -> dict[str, Any]:
    payload = await consolidate_for_artisan(
        artisan_id, current_user, workshop_ids, questionnaire_id
    )
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return payload


@questionnaire_router.get("/artisans/{artisan_id}/consolidated")
async def consolidated_questionnaire(
    artisan_id: str,
    workshopIds: list[str] | None = Query(None),
    questionnaireId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await _consolidated_or_404(artisan_id, current_user, workshopIds, questionnaireId)


@questionnaire_router.get("/artisans/{artisan_id}/consolidated.csv")
async def consolidated_questionnaire_csv(
    artisan_id: str,
    workshopIds: list[str] | None = Query(None),
    questionnaireId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> Response:
    artisan = await require_record(db.artisan, artisan_id)
    scope = await owner_download_scope(current_user, get_value(artisan, "createdById"))
    if scope is not None and artisan_id not in scope.get("artisans", set()):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Your download access to this researcher's data does not cover this artisan. "
            "You can still read the consolidated questionnaire on screen.",
        )
    payload = await _consolidated_or_404(artisan_id, current_user, workshopIds, questionnaireId)
    output = StringIO()
    writer = csv.writer(output)
    writer.writerow(CSV_COLUMNS)
    for row in consolidated_rows(payload):
        writer.writerow(row)
    name = payload.get("artisan", {}).get("name") or artisan_id
    return csv_response(
        naming.export_filename("Questionnaire", "Export", "csv", subject=name),
        output.getvalue(),
    )


@questionnaire_router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(interview_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.questionnaireinterview, interview_id)
    live, revisions, pending = await gather_reads(
        db.mediafile.count(where={"questionnaireInterviewId": interview_id, "removedAt": None}),
        db.questionnaireanswerrevision.count(where={"sessionId": interview_id}),
        db.mediauploadintent.count(
            where={"sessionId": interview_id, "closedAt": None, "expiresAt": {"gt": datetime.now(UTC)}}
        ),
    )
    held = int(live or 0) + int(revisions or 0)
    if held:
        v2_fail("HAS_RECORDINGS", f"Move or remove its {held} recordings first.", count=held)
    if pending:
        v2_fail(
            "HAS_RECORDINGS",
            "A recording is still uploading to this interview. Try again once it lands.",
            count=int(pending),
            pending=int(pending),
        )
    await db.questionnaireinterview.delete(where={"id": interview_id})


async def _has_v2_data(interview_id: str) -> bool:
    media = await db.mediafile.find_many(where={"questionnaireInterviewId": interview_id})
    media_ids = [str(get_value(m, "id")) for m in media]
    recordings, revisions, researchers, intents = await gather_reads(
        db.questionnairerecording.count(where={"mediaFileId": {"in": media_ids}}) if media_ids else _zero(),
        db.questionnaireanswerrevision.count(where={"sessionId": interview_id}),
        db.questionnaireinterviewresearcher.count(where={"interviewId": interview_id}),
        db.mediauploadintent.count(where={"sessionId": interview_id}),
    )
    return bool(recordings or revisions or researchers or intents)



def _instrument_payload(row: Any, counts: dict[str, dict[str, int]], user: Any = None) -> dict[str, Any]:
    return questionnaire_library.form_payload(row, counts, user)


@instruments_router.get("")
async def list_questionnaires(
    current_user: Any = Depends(get_current_user),
    activeOnly: bool = True,
    scope: str | None = None,
    workshopId: str | None = None,
    kind: str | None = None,
    request: Request = None,  # type: ignore[assignment]
) -> list[dict[str, Any]]:
    library_filters = shared_filters.parse(request.query_params if request is not None else {}).without("kind")
    if scope == "workshop":
        library_filters = library_filters.without("workshopId")
    matching = await shared_filters.form_ids_matching(library_filters)
    return await questionnaire_library.list_forms(
        current_user, scope=scope, workshop_id=workshopId, kind=kind, active_only=activeOnly, ids=matching
    )


@instruments_router.get("/options")
async def questionnaire_options(
    designWorkshopId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    forms = await questionnaire_library.list_forms(current_user, active_only=True)
    return [
        {
            "id": form["id"],
            "title": form["title"],
            "version": form["version"],
            "designWorkshopId": None,
            "isShared": form["isShared"],
            "kind": form["kind"],
            "kindLabel": form["kindLabel"],
            "isDefault": form["isDefault"],
            "sectionCount": form["sectionCount"],
            "attachedElsewhere": False,
        }
        for form in forms
        if form.get("deletedAt") is None
    ]


WORKBOOK_MAX_UPLOAD_BYTES = 8 * 1024 * 1024

_XLSX_SUFFIXES = (".xlsx", ".xlsm", ".xltx")


class QuestionnaireWorkbookError(ValueError):
    pass


def _now() -> datetime:
    return datetime.now(UTC)


def _uploaded_answer_rows(parsed: ParsedQuestionnaire) -> int:
    total = 0
    for label in parsed.entryLabels:
        for section in parsed.sections:
            for question in section.questions:
                if question.answers.get(label) or question.answerNotes.get(label):
                    total += 1
    return total


def _came_out_of_the_platform(parsed: ParsedQuestionnaire) -> str | None:
    origin = (parsed.questionnaireId or "").strip()
    if origin:
        return f"its Details sheet names questionnaire {origin}"
    if any(
        (question.questionId or "").strip()
        for section in parsed.sections
        for question in section.questions
    ):
        return "its Question ID column is filled in"
    return None


def _unique_code(code: str, title: str, taken: set[str]) -> str:
    wanted = (code or "").strip()
    if not wanted or wanted.lower() in taken:
        wanted = derive_section_code(title or wanted or "Section", taken)
    else:
        taken.add(wanted.lower())
    return wanted


def workbook_question_data(section: Any) -> dict[str, str]:
    return {"questionnaireId": section.questionnaireId, **section_question_data(section)}


def _clip_help(
    text: str | None,
    *,
    parsed: ParsedQuestionnaire,
    row: int | None,
    problems: list[dict[str, Any]],
) -> str | None:
    if text is None:
        return None
    if len(text) <= MAX_HELP_CHARS:
        return text
    problems.append(
        {
            "sheet": parsed.sheet,
            "row": row,
            "severity": "warning",
            "reason": (
                f"The help text on this row was longer than {MAX_HELP_CHARS} characters and was "
                "shortened to fit. The question itself was imported in full."
            ),
            "value": text[:120],
        }
    )
    return text[: MAX_HELP_CHARS - 1] + "…"


async def _answer_evidence(questions: list[Any], client: Any = None) -> tuple[set[str], set[str]]:
    client = client or db
    question_ids = [str(get_value(q, "id")) for q in questions]
    if not question_ids:
        return set(), set()
    lineage_of = {str(get_value(q, "id")): str(get_value(q, "lineageId") or get_value(q, "id")) for q in questions}
    lineages = sorted(set(lineage_of.values()))
    rows = await client.questionnaireresponse.find_many(where={"questionId": {"in": question_ids}})
    targets = await client.questionnairerecordingtarget.find_many(
        where={"OR": [{"askedQuestionId": {"in": question_ids}}, {"questionLineageId": {"in": lineages}}]}
    )
    answers = await client.questionnaireanswer.find_many(where={"questionLineageId": {"in": lineages}})
    revisions = await client.questionnaireanswerrevision.find_many(where={"askedQuestionId": {"in": question_ids}})
    take_drafts = await client.questionnairetakedraft.find_many(where={"questionLineageId": {"in": lineages}})
    drafts = await client.questionnaireanswerdraft.find_many(where={"questionId": {"in": question_ids}})
    answered = {
        row.questionId
        for row in rows
        if (row.answerText or "").strip() or (row.notes or "").strip()
    }
    asked = {str(get_value(r, "askedQuestionId")) for r in [*targets, *revisions] if get_value(r, "askedQuestionId")}
    asked |= {str(get_value(r, "questionId")) for r in drafts}
    held = {str(get_value(r, "questionLineageId")) for r in [*targets, *answers, *take_drafts] if get_value(r, "questionLineageId")}
    answered |= {qid for qid in question_ids if qid in asked or lineage_of[qid] in held}
    return answered, {row.questionId for row in rows}


def _answers_not_imported_reason(answer_rows: int, evidence: str | None) -> str:
    origin = f" (it came out of the platform — {evidence})" if evidence else ""
    plural = "answer was" if answer_rows == 1 else "answers were"
    return (
        f"The {answer_rows} {plural} typed into this workbook{origin} and NOT imported. An answer "
        "belongs to an interview: it names the artisan who gave it, where it was "
        "recorded and who recorded it, and a spreadsheet column cannot say any of that. The "
        "questions on those rows were imported in full. Record answers on the Questionnaire page, "
        "against an artisan."
    )


def _empty_workbook_report(version: int) -> dict[str, Any]:
    return {
        "created": 0,
        "updated": 0,
        "sections": 0,
        "sectionsCreated": 0,
        "sectionsRetired": 0,
        "superseded": 0,
        "retired": 0,
        "removed": 0,
        "unchanged": 0,
        "answersSkipped": 0,
        "assignedTasksAffected": 0,
        "provenance": None,
        "versionBefore": version,
        "versionAfter": version,
        "problems": [],
        "details": [],
    }


async def create_from_parsed(
    parsed: ParsedQuestionnaire,
    *,
    title: str | None = None,
    description: str | None = None,
    source_filename: str | None = None,
    created_by_id: str | None = None,
    answers_imported: bool = False,
) -> tuple[str, dict[str, Any]]:
    rows = await db.questionnaire.find_many(order={"sortOrder": "desc"}, take=1)
    wanted_title = (title or parsed.title or "Untitled questionnaire").strip()
    try:
        questionnaire = await db.questionnaire.create(
            data={
                "title": wanted_title,
                "description": (description or parsed.description or None),
                "sortOrder": (rows[0].sortOrder if rows else 0) + 1,
                "createdById": created_by_id,
                "sourceFilename": source_filename,
            }
        )
    except UniqueViolationError as exc:
        raise QuestionnaireWorkbookError(
            f"A questionnaire titled “{wanted_title}” already exists. Change the title on "
            "the Details sheet, or upload this file over that questionnaire to edit it instead."
        ) from exc

    report = _empty_workbook_report(questionnaire.version)
    report["problems"] = [p.payload() for p in parsed.problems]
    kept_sections = parsed.sections[:MAX_SECTIONS]
    report["sections"] = len(kept_sections)

    codes_taken: set[str] = set()
    for index, section in enumerate(kept_sections, start=1):
        code = _unique_code(section.code, section.title, codes_taken)
        made = await db.questionnairesection.create(
            data={
                "questionnaireId": questionnaire.id,
                "code": code,
                "title": section.title or code,
                "sortOrder": index,
            }
        )
        report["sectionsCreated"] += 1
        for position, question in enumerate(section.questions, start=1):
            await db.questionnairequestion.create(
                data={
                    **workbook_question_data(made),
                    "prompt": question.prompt,
                    "helpText": _clip_help(
                        question.helpText,
                        parsed=parsed,
                        row=question.row,
                        problems=report["problems"],
                    ),
                    "isRequired": question.isRequired,
                    "sortOrder": position,
                }
            )
            report["created"] += 1

    if not answers_imported:
        _note_skipped_answers(parsed, report)
    await cross_portal_codes.reconcile_question_codes(db, questionnaire_id=questionnaire.id)
    return questionnaire.id, report


def _note_skipped_answers(parsed: ParsedQuestionnaire, report: dict[str, Any]) -> None:
    answer_rows = _uploaded_answer_rows(parsed)
    report["answersSkipped"] = answer_rows
    if not answer_rows:
        return
    reason = _answers_not_imported_reason(answer_rows, _came_out_of_the_platform(parsed))
    report["provenance"] = {
        "action": "answersNotImported",
        "sourceQuestionnaireId": (parsed.questionnaireId or "").strip() or None,
        "answersSkipped": answer_rows,
        "reason": reason,
    }
    report["problems"].append(
        {
            "sheet": parsed.sheet,
            "row": None,
            "severity": "warning",
            "reason": reason,
            "value": None,
        }
    )


def _match_question(
    parsed_question: Any,
    section: Any,
    by_id: dict[str, Any],
    by_prompt: dict[tuple[str, str], Any],
    problems: list[dict[str, Any]],
    parsed: ParsedQuestionnaire,
) -> Any | None:
    wanted = (parsed_question.questionId or "").strip()
    if wanted:
        found = by_id.get(wanted)
        if found is not None:
            return found
        problems.append(
            {
                "sheet": parsed.sheet,
                "row": parsed_question.row,
                "severity": "warning",
                "reason": (
                    f"Question ID '{wanted}' does not belong to this questionnaire, so the row was "
                    "imported as a new question. If you meant to edit an existing question, "
                    "download this questionnaire again and edit that copy."
                ),
                "value": parsed_question.prompt[:120],
            }
        )
        return None
    return by_prompt.get((section.id, parsed_question.prompt.strip().lower()))


WORKBOOK_TX_MAX_WAIT = timedelta(seconds=15)
WORKBOOK_TX_TIMEOUT = timedelta(seconds=120)


async def apply_parsed_edit(
    questionnaire_id: str,
    parsed: ParsedQuestionnaire,
    *,
    title: str | None = None,
    description: str | None = None,
) -> dict[str, Any]:
    async with db.tx(max_wait=WORKBOOK_TX_MAX_WAIT, timeout=WORKBOOK_TX_TIMEOUT) as tx:
        return await apply_parsed_edit_in(tx, questionnaire_id, parsed, title=title, description=description)


async def apply_parsed_edit_in(
    client: Any,
    questionnaire_id: str,
    parsed: ParsedQuestionnaire,
    *,
    title: str | None = None,
    description: str | None = None,
) -> dict[str, Any]:
    existing = await client.questionnaire.find_unique(where={"id": questionnaire_id})
    if existing is None:
        raise QuestionnaireWorkbookError("That questionnaire no longer exists.")
    version_before = existing.version

    sections = await client.questionnairesection.find_many(
        where={"questionnaireId": questionnaire_id},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    all_questions = await client.questionnairequestion.find_many(
        where={"questionnaireId": questionnaire_id},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    section_ids = {s.id for s in sections}
    questions = [q for q in all_questions if q.sectionId in section_ids]
    orphans = [q for q in all_questions if not q.sectionId]

    answered, has_response_row = await _answer_evidence(all_questions, client)

    report = _empty_workbook_report(version_before)
    report["problems"] = [p.payload() for p in parsed.problems]
    problems: list[dict[str, Any]] = report["problems"]

    for orphan in orphans:
        problems.append(
            {
                "sheet": parsed.sheet,
                "row": None,
                "severity": "warning",
                "reason": (
                    f"Question {orphan.id} in this questionnaire is not attached to any section, so "
                    "it cannot be in this workbook and was left exactly as it is. It is invisible on "
                    "every screen that reads the questionnaire, and any answers recorded against it "
                    "are kept. Move it into a section from the questionnaire "
                    "builder if it should be asked, or ask an admin to remove it if it should not."
                ),
                "value": (orphan.prompt or "")[:120],
            }
        )

    by_id = {q.id: q for q in questions}
    section_by_code = {s.code.strip().lower(): s for s in sections}
    by_prompt: dict[tuple[str, str], Any] = {}
    for question in questions:
        if question.isActive:
            by_prompt.setdefault((question.sectionId, question.prompt.strip().lower()), question)

    version_bumps = 0
    touched_questions: set[str] = set()
    touched_sections: set[str] = set()
    codes_taken = {s.code.strip().lower() for s in sections}
    kept_sections = parsed.sections[:MAX_SECTIONS]
    report["sections"] = len(kept_sections)

    for position, section in enumerate(sections, start=1):
        await client.questionnairesection.update(
            where={"id": section.id}, data={"sortOrder": -position}
        )
        section.sortOrder = -position

    for index, parsed_section in enumerate(kept_sections, start=1):
        code = (parsed_section.code or "").strip()
        section = section_by_code.get(code.lower()) if code else None
        if section is None:
            new_code = _unique_code(code, parsed_section.title, codes_taken)
            section = await client.questionnairesection.create(
                data={
                    "questionnaireId": questionnaire_id,
                    "code": new_code,
                    "title": parsed_section.title or new_code,
                    "sortOrder": index,
                }
            )
            section_by_code[new_code.lower()] = section
            report["sectionsCreated"] += 1
        else:
            section = await _apply_workbook_section(section, parsed_section, index, client)
        touched_sections.add(section.id)

        for position, parsed_question in enumerate(parsed_section.questions, start=1):
            help_text = _clip_help(
                parsed_question.helpText,
                parsed=parsed,
                row=parsed_question.row,
                problems=problems,
            )
            match = _match_question(parsed_question, section, by_id, by_prompt, problems, parsed)
            if match is None:
                await client.questionnairequestion.create(
                    data={
                        **workbook_question_data(section),
                        "prompt": parsed_question.prompt,
                        "helpText": help_text,
                        "isRequired": parsed_question.isRequired,
                        "sortOrder": position,
                    }
                )
                report["created"] += 1
                continue

            touched_questions.add(match.id)
            reworded = match.prompt.strip() != parsed_question.prompt.strip()

            if not match.isActive and match.retiredAt is not None:
                if reworded:
                    problems.append(
                        {
                            "sheet": parsed.sheet,
                            "row": parsed_question.row,
                            "severity": "warning",
                            "reason": (
                                "This question was retired because it already had answers recorded "
                                "against it, so the new wording on this row was NOT applied. Edit "
                                + (
                                    f"the question that replaced it ({match.supersededById})"
                                    if match.supersededById
                                    else "an active question"
                                )
                                + " instead, or add a new row with a blank Question ID."
                            ),
                            "value": parsed_question.prompt[:120],
                        }
                    )
                report["unchanged"] += 1
                continue

            data: dict[str, Any] = {}
            if not match.isActive:
                data["isActive"] = True

            if reworded and match.id in answered:
                replacement = await client.questionnairequestion.create(
                    data={
                        **workbook_question_data(section),
                        "prompt": parsed_question.prompt,
                        "helpText": help_text,
                        "isRequired": parsed_question.isRequired,
                        "sortOrder": position,
                    }
                )
                await client.questionnairequestion.update(
                    where={"id": match.id},
                    data={
                        "isActive": False,
                        "retiredAt": _now(),
                        "supersededById": replacement.id,
                    },
                )
                touched_questions.add(replacement.id)
                report["superseded"] += 1
                version_bumps += 1
                report["details"].append(
                    {
                        "action": "superseded",
                        "questionId": match.id,
                        "replacementId": replacement.id,
                        "before": match.prompt,
                        "after": parsed_question.prompt,
                        "reason": (
                            "This question already has answers recorded against it in interviews, so "
                            "its original wording and those answers were kept and your new wording "
                            "was added as a new question."
                        ),
                    }
                )
                continue

            if reworded:
                data["prompt"] = parsed_question.prompt
            if match.helpText != help_text:
                data["helpText"] = help_text
            if match.isRequired != parsed_question.isRequired:
                data["isRequired"] = parsed_question.isRequired
            if match.sortOrder != position:
                data["sortOrder"] = position
            if match.sectionId != section.id:
                data.update(workbook_question_data(section))
            if data:
                await client.questionnairequestion.update(where={"id": match.id}, data=data)
                report["updated"] += 1
            else:
                report["unchanged"] += 1

    version_bumps += await _remove_absent(
        questions,
        sections,
        answered,
        has_response_row,
        touched_questions,
        touched_sections,
        len(kept_sections) + 1,
        report,
        client,
    )

    _note_skipped_answers(parsed, report)

    updates: dict[str, Any] = {}
    if title and title.strip() and title.strip() != existing.title:
        updates["title"] = title.strip()
    elif parsed.title and parsed.title.strip() != existing.title:
        updates["title"] = parsed.title.strip()
    if description is not None and description != existing.description:
        updates["description"] = description
    elif parsed.description and parsed.description != existing.description:
        updates["description"] = parsed.description
    if version_bumps:
        updates["version"] = version_before + version_bumps
    if updates.get("title"):
        clash = await client.questionnaire.find_many(where={"title": updates["title"]})
        if any(get_value(row, "id") != questionnaire_id for row in clash):
            rejected = updates.pop("title")
            problems.append(
                {
                    "sheet": parsed.sheet,
                    "row": None,
                    "severity": "warning",
                    "reason": (
                        "Another questionnaire already has the title on this workbook's Details "
                        "sheet, so the title was left as it was. Every question in the file was "
                        "applied as normal."
                    ),
                    "value": str(rejected),
                }
            )
    if updates:
        await client.questionnaire.update(where={"id": questionnaire_id}, data=updates)
    report["versionAfter"] = version_before + version_bumps
    await cross_portal_codes.reconcile_question_codes(client, questionnaire_id=questionnaire_id)
    return report


async def _apply_workbook_section(section: Any, parsed_section: Any, index: int, client: Any = None) -> Any:
    client = client or db
    wanted_title = parsed_section.title or section.title
    renamed = section.title != wanted_title
    updated = await client.questionnairesection.update(
        where={"id": section.id},
        data={"title": wanted_title, "sortOrder": index, "isActive": True},
    )
    if renamed:
        await client.questionnairequestion.update_many(
            where={"sectionId": section.id},
            data={"sectionCode": updated.code, "sectionTitle": updated.title},
        )
    return updated


async def _remove_absent(
    questions: list[Any],
    sections: list[Any],
    answered: set[str],
    has_response_row: set[str],
    touched_questions: set[str],
    touched_sections: set[str],
    next_section_order: int,
    report: dict[str, Any],
    client: Any = None,
) -> int:
    client = client or db
    bumps = 0
    for question in questions:
        if question.id in touched_questions:
            continue
        if question.id in answered:
            if question.isActive:
                await client.questionnairequestion.update(
                    where={"id": question.id},
                    data={"isActive": False, "retiredAt": _now()},
                )
                report["retired"] += 1
                bumps += 1
                report["details"].append(
                    {
                        "action": "retired",
                        "questionId": question.id,
                        "before": question.prompt,
                        "reason": (
                            "This question was removed from your spreadsheet but answers have "
                            "already been recorded against it in interviews, so it was retired "
                            "rather than deleted. It is no longer asked, and its answers are still "
                            "in the record."
                        ),
                    }
                )
            continue
        if question.id in has_response_row:
            if question.isActive:
                await client.questionnairequestion.update(
                    where={"id": question.id}, data={"isActive": False}
                )
                report["removed"] += 1
            continue
        await client.questionnairequestion.delete(where={"id": question.id})
        report["removed"] += 1

    order = next_section_order
    for section in sections:
        if section.id in touched_sections:
            continue
        verdicts = await client.questionnairesectionstatus.count(where={"sectionId": section.id})
        tasks = await client.assignedtask.count(where={"sectionIds": {"has": section.id}})
        await client.questionnairesection.update(
            where={"id": section.id},
            data={"isActive": False, "sortOrder": order},
        )
        order += 1
        report["sectionsRetired"] += 1
        report["assignedTasksAffected"] += tasks
        report["details"].append(
            {
                "action": "sectionRetired",
                "questionId": section.id,
                "before": f"{section.code} — {section.title}",
                "reason": (
                    f"Section {section.code} was not in your spreadsheet, so it was switched off "
                    "rather than deleted: "
                    f"{verdicts} completion {'verdict' if verdicts == 1 else 'verdicts'} and {tasks} assigned "
                    f"{'task' if tasks == 1 else 'tasks'} still point at "
                    "it. It is no longer asked. Nothing was removed from any task — check the "
                    "assignments if that section was somebody's work."
                ),
            }
        )
    return bumps



async def _workbook_sections(questionnaire_id: str, *, active_only: bool) -> list[dict[str, Any]]:
    section_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    question_where: dict[str, Any] = {"questionnaireId": questionnaire_id}
    if active_only:
        section_where["isActive"] = True
        question_where["isActive"] = True
    sections, questions = await gather_reads(
        db.questionnairesection.find_many(
            where=section_where, order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]
        ),
        db.questionnairequestion.find_many(
            where=question_where, order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]
        ),
    )
    by_section: dict[str, list[Any]] = {}
    for question in questions:
        if question.sectionId:
            by_section.setdefault(question.sectionId, []).append(question)
    return [
        {
            "code": section.code,
            "title": section.title,
            "questions": [
                {
                    "id": question.id,
                    "prompt": question.prompt,
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    "answers": {},
                    "answerNotes": {},
                }
                for question in by_section.get(section.id, [])
            ],
        }
        for section in sections
    ]


async def export_instrument_payload(questionnaire_id: str) -> dict[str, Any] | None:
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        return None
    return {
        "title": row.title,
        "description": row.description,
        "questionnaire_id": row.id,
        "version": row.version,
        "sections": await _workbook_sections(questionnaire_id, active_only=False),
        "entry_labels": [],
    }


async def export_question_set_payload(questionnaire_id: str) -> dict[str, Any] | None:
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        return None
    sections = await _workbook_sections(questionnaire_id, active_only=True)
    for section in sections:
        for question in section["questions"]:
            question["id"] = ""
    return {
        "title": row.title,
        "description": row.description,
        "sections": sections,
        "source_title": row.title,
        "exported_on": datetime.now(UTC).date().isoformat(),
    }


async def load_workbook_form(
    questionnaire_id: str, *, include_retired: bool = False
) -> dict[str, Any] | None:
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        return None
    payload = _instrument_payload(row, {})
    payload["version"] = row.version
    payload["sourceFilename"] = row.sourceFilename
    payload["sections"] = await section_payloads(
        questionnaire_id, active_only=not include_retired
    )
    payload["sectionCount"] = len(payload["sections"])
    payload["questionCount"] = sum(len(s["questions"]) for s in payload["sections"])
    return payload



async def _read_workbook_upload(file: UploadFile, request: Request | None = None) -> bytes:
    name = (file.filename or "").lower()
    if name and not name.endswith(_XLSX_SUFFIXES):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"'{file.filename}' is not an Excel workbook. Fill in the .xlsx pro-forma and "
                "upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
            ),
        )
    content = await read_upload_bounded(
        file,
        WORKBOOK_MAX_UPLOAD_BYTES,
        request=request,
        purpose="questionnaire workbook",
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="The upload was empty. Attach the filled-in pro-forma.",
        )
    return content


def _xlsx_response(payload: bytes, filename: str) -> Response:
    return Response(
        content=payload,
        media_type=XLSX_MIME,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _parse_or_422(content: bytes, filename: str | None) -> ParsedQuestionnaire:
    try:
        return parse_questionnaire_workbook(content, filename=filename)
    except QuestionnaireXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
        ) from exc


@instruments_router.get("/pro-forma")
async def download_pro_forma(_: Any = Depends(require_questionnaire_manager)) -> Response:
    return _xlsx_response(build_pro_forma(), PRO_FORMA_FILENAME)


@instruments_router.get("/{questionnaire_id}/xlsx")
async def download_questionnaire_workbook(
    questionnaire_id: str, _: Any = Depends(require_form_editor)
) -> Response:
    payload = await export_instrument_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return _xlsx_response(
        build_questionnaire_workbook(**payload), download_filename(payload["title"])
    )


@instruments_router.get("/{questionnaire_id}/question-set.xlsx")
async def download_question_set(
    questionnaire_id: str, _: Any = Depends(require_questionnaire_manager)
) -> Response:
    payload = await export_question_set_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return _xlsx_response(
        build_question_set_workbook(**payload), question_set_filename(payload["title"])
    )


@instruments_router.post("/upload", status_code=status.HTTP_201_CREATED)
async def upload_questionnaire(
    request: Request,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    description: str | None = Form(default=None),
    kind: str | None = Form(default=None),
    workshopId: str | None = Form(default=None),
    importAnswers: bool = Form(default=True),
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    content = await _read_workbook_upload(file, request)
    parsed = _parse_or_422(content, file.filename)
    try:
        wanted_kind = questionnaire_kinds.coerce_kind(kind)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    if workshopId:
        await require_record(db.workshop, workshopId)
    hand_filled = bool(importAnswers) and parsed.hasAnswers and _came_out_of_the_platform(parsed) is None
    try:
        questionnaire_id, report = await create_from_parsed(
            parsed,
            title=title,
            description=description,
            source_filename=file.filename,
            created_by_id=get_value(current_user, "id"),
            answers_imported=hand_filled,
        )
    except QuestionnaireWorkbookError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"ownerId": get_value(current_user, "id"), "kind": wanted_kind}
    )
    report["answersImported"] = 0
    report["sessionsCreated"] = 0
    if hand_filled:
        imported = await questionnaire_library.import_workbook_answers(
            questionnaire_id, parsed, current_user, workshop_id=workshopId
        )
        report["answersImported"] = imported["answers"]
        report["sessionsCreated"] = imported["sessions"]
        if imported["answers"]:
            sittings = imported["sessions"]
            report["provenance"] = {
                "action": "answersImported",
                "sourceQuestionnaireId": None,
                "answersSkipped": 0,
                "reason": (
                    f"{imported['answers']} answer{'s' if imported['answers'] != 1 else ''} imported as "
                    f"{sittings} uploaded session{'s' if sittings != 1 else ''}."
                ),
            }
    form = await load_workbook_form(questionnaire_id)
    return public_encode({"questionnaire": form, "report": report})


@instruments_router.post("/{questionnaire_id}/upload")
async def reupload_questionnaire(
    questionnaire_id: str,
    request: Request,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    _: Any = Depends(require_form_editor),
) -> dict[str, Any]:
    record = await require_questionnaire(questionnaire_id)
    content = await _read_workbook_upload(file, request)
    parsed = _parse_or_422(content, file.filename)

    if parsed.questionnaireId and parsed.questionnaireId != questionnaire_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That workbook was downloaded from a different questionnaire "
                f"(its Details sheet says {parsed.questionnaireId}). Download this questionnaire "
                "again and edit that copy, or upload the file as a new questionnaire."
            ),
        )
    try:
        report = await apply_parsed_edit(questionnaire_id, parsed, title=title)
    except QuestionnaireWorkbookError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
        ) from exc
    if file.filename and file.filename != getattr(record, "sourceFilename", None):
        await db.questionnaire.update(
            where={"id": questionnaire_id}, data={"sourceFilename": file.filename}
        )
    form = await load_workbook_form(questionnaire_id, include_retired=True)
    return public_encode({"questionnaire": form, "report": report})


@instruments_router.post("/bundles/import", status_code=status.HTTP_201_CREATED)
async def import_questionnaire_bundle(
    request: Request,
    response: Response,
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    limit = questionnaire_library.BUNDLE_LIMITS["maxUncompressedBytes"]
    if request.headers.get("content-type", "").startswith("multipart/"):
        form = await request.form()
        upload = form.get("file")
        if upload is None or not hasattr(upload, "read"):
            v2_fail("VALIDATION_FAILED", "Choose a bundle file.")
        raw = await upload.read(limit + 1)
    else:
        raw = await request.body()
    if len(raw) > limit:
        v2_fail("VALIDATION_FAILED", "This bundle is too large.")
    document = questionnaire_library.read_bundle_bytes(raw)
    payload, created = await questionnaire_library.import_bundle(document, current_user)
    if not created:
        response.status_code = status.HTTP_200_OK
    return {"questionnaire": payload, "created": created}


@instruments_router.get("/{questionnaire_id}")
async def get_questionnaire(
    questionnaire_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    row = await require_questionnaire(questionnaire_id)
    if get_value(row, "deletedAt") is not None and not (
        is_content_admin(current_user) or is_form_author(row, current_user)
    ):
        v2_fail("FORM_DELETED")
    payload = _instrument_payload(row, {}, current_user)
    payload["sections"] = await section_payloads(questionnaire_id, active_only=False)
    payload["sectionCount"] = len(payload["sections"])
    payload["questionCount"] = sum(len(s["questions"]) for s in payload["sections"])
    return payload


@instruments_router.post("", status_code=status.HTTP_201_CREATED)
async def create_questionnaire(
    payload: QuestionnaireCreate,
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    sort_order = payload.sortOrder
    if sort_order is None:
        rows = await db.questionnaire.find_many(order={"sortOrder": "desc"}, take=1)
        sort_order = (rows[0].sortOrder if rows else 0) + 1
    try:
        kind = questionnaire_kinds.coerce_kind(payload.kind)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    try:
        created = await db.questionnaire.create(
            data={
                "title": payload.title.strip(),
                "description": payload.description,
                "isActive": payload.isActive,
                "sortOrder": sort_order,
                "createdById": get_value(current_user, "id"),
                "ownerId": get_value(current_user, "id"),
                "kind": kind,
            }
        )
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A questionnaire titled “{payload.title.strip()}” already exists.",
        ) from exc
    return _instrument_payload(created, {}, current_user)


@instruments_router.patch("/{questionnaire_id}")
async def update_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireUpdate,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    row = await require_questionnaire(questionnaire_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "kind" in payload.model_fields_set:
        try:
            data["kind"] = questionnaire_kinds.coerce_kind(payload.kind)
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    sharing = data.pop("isShared", None)
    editing = bool({k for k in data if k != "isActive"}) or data.get("isActive") is True
    if editing and not (is_content_admin(user) or is_form_author(row, user)):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_EDIT_REFUSAL)
    if sharing is not None:
        if not questionnaire_authoring.may_admin_act(user):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=questionnaire_authoring.ADMIN_ACT_REFUSAL)
        data["isShared"] = bool(sharing)
    if data.get("isActive") is True and get_value(row, "deletedAt") is not None:
        if not questionnaire_library.may_restore(user, row):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=questionnaire_library.RESTORE_REFUSAL)
        data["deletedAt"] = None
        data["deletedById"] = None
    if "title" in data:
        data["title"] = data["title"].strip()
    if data.get("isActive") is False:
        if not (is_content_admin(user) or is_form_author(row, user)):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the questionnaire's author or an admin can retire it.",
            )
        if row.isDefault:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    "This is the default questionnaire and cannot be retired. Point the default at "
                    "another questionnaire first."
                ),
            )
        bound = await db.workshop.find_many(where={"questionnaireId": questionnaire_id})
        if bound:
            titles = sorted(get_value(w, "title") or w.id for w in bound)
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"{len(titles)} {'workshop still uses' if len(titles) == 1 else 'workshops still use'} this questionnaire and it cannot be "
                    f"retired: {titles}. Rebind or detach them first."
                ),
            )
    if not data:
        return _instrument_payload(row, {}, user)
    try:
        updated = await db.questionnaire.update(where={"id": questionnaire_id}, data=data)
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A questionnaire with that title already exists.",
        ) from exc
    return _instrument_payload(updated, {}, user)


@instruments_router.delete("/{questionnaire_id}")
async def delete_questionnaire(
    questionnaire_id: str,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    row = await require_questionnaire(questionnaire_id)
    if not (is_content_admin(user) or is_form_author(row, user)):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=FORM_DELETE_REFUSAL)
    if get_value(row, "deletedAt") is not None:
        return _instrument_payload(row, {}, user)
    if row.isDefault:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="This is the default questionnaire. Choose another default first.",
        )
    bound = await db.workshop.find_many(where={"questionnaireId": questionnaire_id})
    if bound:
        titles = sorted(get_value(w, "title") or w.id for w in bound)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Unbind it from {', '.join(titles)} first.",
        )
    updated = await db.questionnaire.update(
        where={"id": questionnaire_id},
        data={"isActive": False, "deletedAt": datetime.now(UTC), "deletedById": get_value(user, "id")},
    )
    return _instrument_payload(updated, {}, user)


@instruments_router.post("/{questionnaire_id}/restore")
async def restore_questionnaire(questionnaire_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await questionnaire_library.restore(questionnaire_id, user)


@instruments_router.post("/{questionnaire_id}/reuse", status_code=status.HTTP_201_CREATED)
async def reuse_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireReuse,
    current_user: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    return await questionnaire_library.reuse(
        questionnaire_id, current_user, title=payload.title, description=payload.description,
        kind=payload.kind, keep_kind="kind" not in payload.model_fields_set,
    )


@instruments_router.get("/{questionnaire_id}/bundle")
async def download_questionnaire_bundle(
    questionnaire_id: str, _: Any = Depends(require_questionnaire_manager)
) -> Response:
    document = await questionnaire_library.bundle_document(questionnaire_id)
    filename = questionnaire_library.bundle_filename(document["form"]["title"])
    return Response(
        content=questionnaire_library.bundle_bytes(document),
        media_type="application/gzip",
        headers={"Content-Disposition": naming.content_disposition(filename)},
    )


@instruments_router.put("/{questionnaire_id}/default")
async def set_default_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireDefaultUpdate,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    row = await require_questionnaire(questionnaire_id)
    if payload.isDefault and not row.isActive:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="A retired questionnaire cannot be the default. Reactivate it first.",
        )
    if not payload.isDefault:
        updated = await db.questionnaire.update(where={"id": questionnaire_id}, data={"isDefault": False})
        return _instrument_payload(updated, {})
    await db.questionnaire.update_many(where={"isDefault": True}, data={"isDefault": False})
    updated = await db.questionnaire.update(where={"id": questionnaire_id}, data={"isDefault": True})
    return _instrument_payload(updated, {})


router.include_router(questionnaire_router)
router.include_router(instruments_router)


def _standardise(apply: Any, data: dict[str, Any], stored: Any = None) -> None:
    try:
        apply(data, stored)
    except StandardValueError as exc:
        raise HTTPException(status_code=422, detail=exc.message) from exc
