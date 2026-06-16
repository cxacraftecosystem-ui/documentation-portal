from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_fields,
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    is_admin,
    require_questionnaire_manager,
)
from app.schemas.questionnaire import (
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
    add_date_range,
    attach_location,
    clean_data,
    contains,
    jsonify_metadata,
    merge_field_provenance,
    require_record,
)

router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])

INTERVIEW_INCLUDE = {
    "createdBy": True,
    "location": True,
    "artisans": {"include": {"artisan": {"include": {"craft": True}}}},
    "responses": {"include": {"question": True, "answeredBy": True}},
    "media": True,
}


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
        encoded = jsonable_encoder(section)
        encoded["questions"] = jsonable_encoder(questions_by_section.get(section.id, []))
        payload.append(encoded)
    return payload


async def replace_interview_artisans(interview_id: str, artisan_ids: list[str]) -> None:
    await db.questionnaireinterviewartisan.delete_many(where={"interviewId": interview_id})
    for artisan_id in artisan_ids:
        await require_record(db.artisan, artisan_id)
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
    return jsonable_encoder(flattened)


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
    return jsonable_encoder(created)


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
    return jsonable_encoder(updated)


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
    return jsonable_encoder(created)


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
    return jsonable_encoder(updated)


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
    return page_payload(jsonable_encoder(items), total, page, page_size)


@router.post("/interviews", status_code=status.HTTP_201_CREATED)
async def create_interview(
    payload: QuestionnaireInterviewCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump(exclude={"artisanIds", "responses"}))
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    jsonify_metadata(data)
    created = await db.questionnaireinterview.create(data=data)
    if payload.artisanIds:
        await replace_interview_artisans(created.id, payload.artisanIds)
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user)
    hydrated = await db.questionnaireinterview.find_unique(where={"id": created.id}, include=INTERVIEW_INCLUDE)
    return jsonable_encoder(hydrated)


@router.get("/interviews/{interview_id}")
async def get_interview(interview_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    interview = await db.questionnaireinterview.find_unique(where={"id": interview_id}, include=INTERVIEW_INCLUDE)
    interview = await require_record(db.questionnaireinterview, interview_id) if not interview else interview
    return jsonable_encoder(interview)


@router.patch("/interviews/{interview_id}")
async def update_interview(
    interview_id: str,
    payload: QuestionnaireInterviewUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    data = clean_data(payload.model_dump(exclude_unset=True, exclude={"artisanIds", "responses"}))
    data = await attach_location(data)
    assert_can_contribute_fields(interview, current_user, data)
    merge_field_provenance(data, current_user, previous=interview)
    jsonify_metadata(data)
    if data:
        await db.questionnaireinterview.update(where={"id": interview_id}, data=data)
    if payload.artisanIds is not None:
        link_count = await db.questionnaireinterviewartisan.count(where={"interviewId": interview_id})
        assert_can_contribute_relation(interview, current_user, link_count > 0, "artisanIds")
        await replace_interview_artisans(interview_id, payload.artisanIds)
    if payload.responses is not None:
        await upsert_responses(interview_id, payload.responses, current_user)
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id}, include=INTERVIEW_INCLUDE)
    return jsonable_encoder(updated)


@router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(interview_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.questionnaireinterview, interview_id)
    await db.questionnaireinterview.delete(where={"id": interview_id})
