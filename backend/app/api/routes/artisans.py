from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_fields,
    assert_can_delete,
    can_manage_crafts,
    get_current_user,
)
from app.schemas.records import ArtisanCreate, ArtisanUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    public_encode,
    attach_location,
    clean_data,
    contains,
    merge_field_provenance,
    require_record,
    visibility_where,
)

router = APIRouter(prefix="/artisans", tags=["artisans"])

INCLUDE = {"craft": True, "location": True, "createdBy": True}


async def resolve_craft_id(data: dict[str, Any], current_user: Any) -> dict[str, Any]:
    craft_name = data.pop("craftName", None)
    if data.get("craftId") or not craft_name:
        return data
    existing = await db.craft.find_unique(where={"name": craft_name})
    if existing:
        data["craftId"] = existing.id
        return data
    # Creating a brand-new craft is a granted privilege. Non-managers must pick an existing craft.
    if not can_manage_crafts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                f"Craft '{craft_name}' does not exist yet. Select an existing craft, or ask the "
                "master admin to grant you craft creation access."
            ),
        )
    created = await db.craft.create(data={"name": craft_name, "createdById": current_user.id})
    data["craftId"] = created.id
    return data


@router.get("")
async def list_artisans(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craft: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where = visibility_where(current_user)
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"place": contains(search)},
            {"notes": contains(search)},
            {"craft": {"is": {"name": contains(search)}}},
        ]
    if craft:
        where["craft"] = {"is": {"name": contains(craft)}}
    if craftId:
        where["craftId"] = craftId
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = statusFilter
    total = await db.artisan.count(where=where)
    items = await db.artisan.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_artisan(
    payload: ArtisanCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    created = await db.artisan.create(data=data, include=INCLUDE)
    return public_encode(created)


@router.get("/{artisan_id}")
async def get_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    artisan = await db.artisan.find_unique(where={"id": artisan_id}, include=INCLUDE)
    artisan = await require_record(db.artisan, artisan_id) if not artisan else artisan
    return public_encode(artisan)


@router.patch("/{artisan_id}")
async def update_artisan(
    artisan_id: str,
    payload: ArtisanUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    assert_can_contribute_fields(artisan, current_user, data)
    merge_field_provenance(data, current_user, previous=artisan)
    updated = await db.artisan.update(where={"id": artisan_id}, data=data, include=INCLUDE)
    return public_encode(updated)


@router.get("/{artisan_id}/questionnaire")
async def get_artisan_questionnaire(artisan_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Everything recorded against this artisan in the questionnaire, gathered per artisan.

    Because one interview is shared across an exact set of artisans, a recording/note/answer made for
    a group (or a larger superset) belongs to EACH member individually — so it surfaces here for every
    artisan in the set, letting each be validated on their own, as part of a subset, or for the whole
    set. Returns: ``answered`` (non-empty answers across every interview the artisan belongs to) and
    ``interviews`` (each interview the artisan is in, with its recordings/media, notes, and the other
    artisans it was recorded with). Deletion of any media stays uploader-or-admin; the interview row is
    admin-only — enforced on the media/questionnaire routes, not here.
    """
    await require_record(db.artisan, artisan_id)
    responses = await db.questionnaireresponse.find_many(
        where={
            "interview": {"is": {"artisans": {"some": {"artisanId": artisan_id}}}},
            "answerText": {"not": None},
        },
        include={"question": True, "interview": True, "answeredBy": True},
        order={"createdAt": "asc"},
    )
    answered: list[dict[str, Any]] = []
    for response in responses:
        if not (response.answerText and response.answerText.strip()):
            continue
        question = response.question
        interview = response.interview
        answered_by = response.answeredBy
        answered.append(
            {
                "responseId": response.id,
                "questionId": response.questionId,
                "prompt": question.prompt if question else None,
                "sectionCode": question.sectionCode if question else None,
                "sectionTitle": question.sectionTitle if question else None,
                "sortOrder": question.sortOrder if question else 0,
                "answerText": response.answerText,
                "notes": response.notes,
                "interviewId": response.interviewId,
                "interviewTitle": interview.title if interview else None,
                "interviewDate": interview.interviewDate if interview else None,
                "answeredByName": answered_by.name if answered_by else None,
            }
        )
    answered.sort(key=lambda item: ((item.get("sectionCode") or ""), item.get("sortOrder") or 0))

    # Every interview the artisan belongs to (alone, in a subset, or in a larger set), with its
    # recordings and the co-artisans, so the same content is validatable for this artisan individually.
    interview_rows = await db.questionnaireinterview.find_many(
        where={"artisans": {"some": {"artisanId": artisan_id}}},
        include={"artisans": {"include": {"artisan": True}}, "media": True},
        order={"createdAt": "desc"},
    )
    interviews: list[dict[str, Any]] = []
    for interview in interview_rows:
        co_artisans = [
            link.artisan.name
            for link in (interview.artisans or [])
            if link.artisan and link.artisanId != artisan_id
        ]
        interviews.append(
            {
                "interviewId": interview.id,
                "title": interview.title,
                "notes": interview.notes,
                "interviewDate": interview.interviewDate,
                "place": interview.place,
                "language": interview.language,
                "status": interview.status,
                "artisanCount": len(interview.artisans or []),
                "coArtisans": co_artisans,
                "media": public_encode(interview.media or []),
            }
        )
    return public_encode(
        {
            "artisanId": artisan_id,
            "answered": answered,
            "total": len(answered),
            "interviews": interviews,
        }
    )


@router.delete("/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.artisan, artisan_id)
    await db.artisan.delete(where={"id": artisan_id})
