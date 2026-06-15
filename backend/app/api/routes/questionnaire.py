from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user
from app.schemas.questionnaire import QuestionnaireInterviewCreate, QuestionnaireInterviewUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import add_date_range, attach_location, clean_data, contains, require_record

router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])

INTERVIEW_INCLUDE = {
    "createdBy": True,
    "location": True,
    "artisans": {"include": {"artisan": {"include": {"craft": True}}}},
    "responses": {"include": {"question": True, "answeredBy": True}},
    "media": True,
}


async def replace_interview_artisans(interview_id: str, artisan_ids: list[str]) -> None:
    await db.questionnaireinterviewartisan.delete_many(where={"interviewId": interview_id})
    for artisan_id in artisan_ids:
        await require_record(db.artisan, artisan_id)
        await db.questionnaireinterviewartisan.create(
            data={"interviewId": interview_id, "artisanId": artisan_id}
        )


async def upsert_responses(interview_id: str, responses: list[Any], answered_by_id: str) -> None:
    for response in responses:
        await require_record(db.questionnairequestion, response.questionId)
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
                    "answeredById": answered_by_id,
                },
                "update": {
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answeredById": answered_by_id,
                },
            },
        )


@router.get("/questions")
async def list_questions(
    _: Any = Depends(get_current_user),
    sectionCode: str | None = None,
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    where: dict[str, Any] = {}
    if sectionCode:
        where["sectionCode"] = sectionCode
    if activeOnly:
        where["isActive"] = True
    questions = await db.questionnairequestion.find_many(
        where=where,
        order=[{"sectionCode": "asc"}, {"sortOrder": "asc"}],
    )
    return jsonable_encoder(questions)


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
    created = await db.questionnaireinterview.create(data=data)
    if payload.artisanIds:
        await replace_interview_artisans(created.id, payload.artisanIds)
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user.id)
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
    await require_record(db.questionnaireinterview, interview_id)
    data = clean_data(payload.model_dump(exclude_unset=True, exclude={"artisanIds", "responses"}))
    data = await attach_location(data)
    if data:
        await db.questionnaireinterview.update(where={"id": interview_id}, data=data)
    if payload.artisanIds is not None:
        await replace_interview_artisans(interview_id, payload.artisanIds)
    if payload.responses is not None:
        await upsert_responses(interview_id, payload.responses, current_user.id)
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id}, include=INTERVIEW_INCLUDE)
    return jsonable_encoder(updated)


@router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(interview_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.questionnaireinterview, interview_id)
    await db.questionnaireinterview.delete(where={"id": interview_id})
