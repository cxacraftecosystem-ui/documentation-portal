from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    require_record_creator,
    assert_can_delete,
    can_manage_crafts,
    get_current_user,
)
from app.schemas.records import ArtisanCreate, ArtisanUpdate
from app.services.access import guard_record_edit
from app.services.artisan_identity import is_masked_aadhaar, mask_aadhaar, normalize_aadhaar
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    public_encode,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
    contains,
    merge_field_provenance,
    require_record,
    resubmit_status,
    visibility_where,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    link_workshop_artisan,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/artisans", tags=["artisans"])

INCLUDE = {"craft": True, "location": True, "createdBy": True, "workshop": True}

# Unique columns that identify a real-world person, and the human phrasing for each. A collision on
# one of these is the deduplication working as designed, so it has to read as "you already have this
# artisan" rather than as a database error.
_IDENTITY_CONSTRAINTS = {
    "aadhaarNumber": "Aadhaar number",
    "pehchanCardNumber": "Artisan Pehchan Card number",
}


def _violated_identity_field(error: Exception) -> str | None:
    """Which identity column a Prisma unique-constraint error was raised on, if any."""
    text = str(error)
    if "unique" not in text.lower():
        return None
    for field in _IDENTITY_CONSTRAINTS:
        if field in text:
            return field
    return None


async def _identity_conflict(field: str, value: str, exclude_id: str | None = None) -> HTTPException:
    """A 409 naming the artisan that already holds this number, so the researcher can go to them.

    The existing artisan's name and place are safe to return — the caller already possesses the
    identity number, and without them the message is a dead end ("duplicate" with nowhere to go).
    The number itself is echoed back MASKED.
    """
    where: dict[str, Any] = {field: value}
    existing = await db.artisan.find_first(where=where, include={"craft": True})
    detail: dict[str, Any] = {
        "code": "artisan_identity_conflict",
        "field": field,
        "message": f"Another artisan is already recorded with this {_IDENTITY_CONSTRAINTS[field]}.",
    }
    if existing is not None and existing.id != exclude_id:
        detail["existingArtisan"] = {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
        }
        detail["message"] = (
            f"{existing.name} ({existing.place}) is already recorded with this "
            f"{_IDENTITY_CONSTRAINTS[field]}. Open that artisan instead of creating a duplicate."
        )
    if field == "aadhaarNumber":
        detail["maskedValue"] = mask_aadhaar(value)
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)


def _may_read_full_aadhaar(user: Any, artisan: Any) -> bool:
    """May this caller see an artisan's UNMASKED Aadhaar number?

    Aadhaar is regulated personal data, so plain "is signed in" is not the right bar — without this,
    a crowdsource volunteer could read any artisan's full number straight off ``GET /artisans/{id}``.
    The line is drawn at the people who actually need it to do the work: the researcher who recorded
    the artisan, and professor-and-above (which includes admins) who supervise and correct the data.
    Everyone else sees the same masked form the exports show.
    """
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return True
    return bool(get_value(user, "id")) and get_value(artisan, "createdById") == get_value(user, "id")


def _mask_artisan_identity(payload: Any, user: Any, artisan: Any) -> Any:
    """Replace ``aadhaarNumber`` with its masked form for callers not entitled to the raw value.

    Applied to the ENCODED payload, after ``public_encode``, so it covers the response no matter how
    the row was loaded. The masked string is deliberately the same ``XXXX XXXX 9012`` the data
    browser and the workbook show, so one artisan reads identically wherever they appear.
    """
    if not isinstance(payload, dict) or "aadhaarNumber" not in payload:
        return payload
    if _may_read_full_aadhaar(user, artisan):
        return payload
    payload["aadhaarNumber"] = mask_aadhaar(payload.get("aadhaarNumber"))
    return payload


def _drop_unchanged_masked_aadhaar(data: dict[str, Any], artisan: Any) -> dict[str, Any]:
    """Ignore a resubmitted MASK so an entitled-to-edit-but-not-to-read caller can still save.

    A form that received ``XXXX XXXX 9012`` and posts it back unchanged is saying "leave this
    alone", not "set the Aadhaar to that literal string" — and validation would reject it anyway.
    Dropping the key is what makes masking safe to apply to the edit surface.
    """
    if is_masked_aadhaar(data.get("aadhaarNumber")):
        data.pop("aadhaarNumber", None)
    return data


async def _guard_identity_conflicts(data: dict[str, Any], exclude_id: str | None = None) -> None:
    """Reject a duplicate Aadhaar/Pehchan number BEFORE writing, with a message worth reading.

    The unique indexes are the real guarantee — this is the pre-check that turns a would-be 500 into
    an actionable 409. The write is still wrapped in its own handler, because two researchers can
    submit the same artisan in the same instant and only the index can settle that race.
    """
    for field in _IDENTITY_CONSTRAINTS:
        value = data.get(field)
        if not value:
            continue
        clash = await db.artisan.find_first(where={field: value})
        if clash is not None and clash.id != exclude_id:
            raise await _identity_conflict(field, value, exclude_id)


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
    workshopId: str | None = None,
    place: str | None = None,
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so the
    # free-text search OR and the workshop OR can never overwrite one another — nor the row-visibility
    # filter, which joins the same AND.
    and_filters: list[dict[str, Any]] = []
    vis = await visibility_where(current_user)
    if vis:
        and_filters.append(vis)
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
    if workshopId:
        # Either reading counts: the artisan's own workshopId column, or the WorkshopArtisan join
        # (relation named ``workshops``) that carried the link before the column existed.
        and_filters.append({"OR": [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]})
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = statusFilter
    if and_filters:
        where["AND"] = and_filters
    total = await db.artisan.count(where=where)
    items = await db.artisan.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    # A list page hands back many artisans at once, so mask each row the caller is not entitled to
    # read raw — otherwise one request yields every id AND every full Aadhaar on the page.
    encoded = [
        _mask_artisan_identity(row, current_user, artisan)
        for row, artisan in zip(public_encode(items), items)
    ]
    return page_payload(encoded, total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_artisan(
    payload: ArtisanCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    # Workshop entries: enforce assignment, then flag + pin a late submission for admin approval.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    # Aadhaar / Pehchan are the dedup keys: point the researcher at the existing artisan instead of
    # letting the unique index surface as a 500.
    await _guard_identity_conflicts(data)
    try:
        created = await db.artisan.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by _violated_identity_field
        field = _violated_identity_field(exc)
        if field is None:
            raise
        # Lost the race against a concurrent submission of the same person.
        raise await _identity_conflict(field, data[field]) from exc
    # The explicit column ADDS to the WorkshopArtisan join every existing query still reads through.
    await link_workshop_artisan(created.workshopId, created.id)
    return public_encode(created)


@router.get("/{artisan_id}")
async def get_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    artisan = await db.artisan.find_unique(where={"id": artisan_id}, include=INCLUDE)
    artisan = await require_record(db.artisan, artisan_id) if not artisan else artisan
    # Masked unless this caller is entitled to the raw number (creator, or professor+).
    return _mask_artisan_identity(public_encode(artisan), current_user, artisan)


@router.patch("/{artisan_id}")
async def update_artisan(
    artisan_id: str,
    payload: ArtisanUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    # A caller shown the masked number who saves without touching it means "leave it alone".
    data = _drop_unchanged_masked_aadhaar(data, artisan)
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != artisan.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(artisan, current_user, data, "artisan")
    await apply_status_policy_update(current_user, artisan, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=artisan)
    pin_pending_if_late(data, current_user, check=check, record=artisan)
    merge_field_provenance(data, current_user, previous=artisan)
    resubmit_status(artisan, current_user, data)
    # "Card available = Yes" on an edit is only valid if a number exists — either arriving in this
    # PATCH or already stored. The create-time validator cannot make this call, because a PATCH
    # carrying just the flag is legitimate when the record already holds a number.
    #
    # There are two ways a PATCH can break that pair, and the schema validator can see neither: it is
    # handed only the keys the client actually sent, so it never learns what is on the row.
    #   * the answer turns to Yes while no number exists here or on the record, and
    #   * the number is CLEARED while the answer stays Yes — the mirror image, and the one that
    #     otherwise slips through as {"pehchanCardNumber": null} on its own.
    # A PATCH touching neither key is deliberately left alone: rows that predate this feature carry
    # the column's DEFAULT of Yes with no number, and an unrelated edit to one must not be refused.
    answered_yes = data.get("pehchanCardAvailable") is True
    clears_number = "pehchanCardNumber" in data and not data["pehchanCardNumber"]
    still_yes = data.get("pehchanCardAvailable", artisan.pehchanCardAvailable) is True
    if answered_yes or (clears_number and still_yes):
        number = data.get("pehchanCardNumber", artisan.pehchanCardNumber)
        if not number:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "Enter the Artisan Pehchan Card number, or set the card to 'No' if the artisan "
                    "does not hold one."
                ),
            )
    await _guard_identity_conflicts(data, exclude_id=artisan_id)
    try:
        updated = await db.artisan.update(where={"id": artisan_id}, data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by _violated_identity_field
        field = _violated_identity_field(exc)
        if field is None:
            raise
        raise await _identity_conflict(field, data[field], exclude_id=artisan_id) from exc
    await link_workshop_artisan(updated.workshopId, updated.id)
    return _mask_artisan_identity(public_encode(updated), current_user, updated)


@router.get("/lookup/aadhaar")
async def lookup_artisan_by_aadhaar(
    number: str, _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Is this Aadhaar already on an artisan? The form's pre-flight duplicate check.

    Lets the researcher find out while they are still typing, instead of after filling a whole form
    and hitting a 409 on save. Returns ``{found, artisan?}`` and never 404s — "not found" is the
    expected, successful answer. Only the identifying fields of the existing artisan come back, and
    only to a signed-in user who already holds the number they searched with.
    """
    normalized = normalize_aadhaar(number)
    if not normalized:
        return {"found": False, "artisan": None}
    existing = await db.artisan.find_first(
        where={"aadhaarNumber": normalized}, include={"craft": True, "workshop": True}
    )
    if existing is None:
        return {"found": False, "artisan": None}
    return {
        "found": True,
        "artisan": {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
            "workshop": getattr(getattr(existing, "workshop", None), "title", None),
        },
    }


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
