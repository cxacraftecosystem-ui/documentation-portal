import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    require_record_creator,
    assert_can_delete,
    get_current_user,
    get_value,
)
from app.schemas.records import ArtisanCreate, ArtisanUpdate, drop_masked_identity_numbers
from app.services import craft_master
from app.services.access import guard_record_edit
from app.services.artisan_merge import created_merged_record, survivor_of
from app.services.artisan_form_contract import assert_form_contract, take_form_contract
from app.services import aadhaar_fingerprint, artisan_card, artisan_card_store, artisan_duplicates
from app.services.artisan_identity import aadhaar_error, mask_aadhaar, normalize_aadhaar
from app.services import completeness as completeness_module
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import (
    artisan_workshop_clause,
    resolve_craft_ids,
    resolve_workshop_ids,
)
from app.services.records import (
    Relation,
    public_encode,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    attach_location,
    clean_data,
    contains,
    count_and_page,
    hydrate_relations,
    include_of,
    merge_field_provenance,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.standard_values import StandardValueError, apply_artisan_values
from app.services.workshop_access import (
    enforce_workshop_submission,
    link_workshop_artisan,
    pin_pending_if_late,
    stamp_workshop_submission,
)

logger = logging.getLogger("app.artisans")

router = APIRouter(prefix="/artisans", tags=["artisans"])

RELATIONS = (
    Relation("craft", "craft", "craftId"),
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("workshop", "workshop", "workshopId"),
)
INCLUDE = include_of(RELATIONS)

_IDENTITY_CONSTRAINTS = {
    "aadhaarNumber": "Aadhaar number",
    "pehchanCardNumber": "Artisan Pehchan Card number",
}

_CLEARABLE_COLUMNS = (
    "localName",
    "gender",
    "phone",
    "phoneCountryCode",
    "email",
    "address",
    "notes",
    "dateOfBirth",
    "craftStartDate",
    "experienceYears",
    "experienceMonths",
    "dos",
    "donts",
)


_IDENTITY_COLUMNS = {
    "aadhaarNumber": aadhaar_fingerprint.FINGERPRINT_FIELD,
    "pehchanCardNumber": "pehchanCardNumber",
}


def _violated_identity_field(error: Exception) -> str | None:
    text = str(error)
    if "unique" not in text.lower():
        return None
    for field, column in _IDENTITY_COLUMNS.items():
        if column in text or field in text:
            return field
    return None


def _fingerprint_unavailable() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail="Aadhaar numbers can't be saved right now. Try again shortly.",
    )


def _seal_aadhaar(data: dict[str, Any], previous: Any = None) -> str | None:
    try:
        return aadhaar_fingerprint.seal(data, previous=previous)
    except aadhaar_fingerprint.FingerprintKeyMissing as exc:
        logger.error("artisan write refused: %s", exc)
        raise _fingerprint_unavailable() from exc


async def _identity_conflict(
    field: str, value: str, exclude_id: str | None = None, existing: Any = None
) -> HTTPException:
    if existing is None and value:
        where = (
            aadhaar_fingerprint.match_where(
                value, legacy=await aadhaar_fingerprint.legacy_pending(db)
            )
            if field == "aadhaarNumber"
            else {field: value}
        )
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
    if field == "aadhaarNumber" and value:
        detail["maskedValue"] = mask_aadhaar(value)
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)


def _may_read_full_aadhaar(user: Any, artisan: Any) -> bool:
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return True
    return bool(get_value(user, "id")) and get_value(artisan, "createdById") == get_value(user, "id")


def _mask_artisan_identity(payload: Any, user: Any, artisan: Any) -> Any:
    if not isinstance(payload, dict) or "aadhaarNumber" not in payload:
        return payload
    if _may_read_full_aadhaar(user, artisan):
        return payload
    payload["aadhaarNumber"] = mask_aadhaar(payload.get("aadhaarNumber"))
    return payload


async def _guard_identity_conflicts(
    data: dict[str, Any], exclude_id: str | None = None, aadhaar: str | None = None
) -> None:
    clauses: list[dict[str, Any]] = []
    if aadhaar:
        legacy = await aadhaar_fingerprint.legacy_pending(db)
        clauses.append(aadhaar_fingerprint.match_where(aadhaar, legacy=legacy))
    pehchan = data.get("pehchanCardNumber")
    if pehchan:
        clauses.append({"pehchanCardNumber": pehchan})
    if not clauses:
        return
    clashes = await db.artisan.find_many(where={"OR": clauses}, include={"craft": True})
    for clash in clashes:
        if clash.id == exclude_id:
            continue
        if aadhaar and aadhaar_fingerprint.holds(clash, aadhaar):
            raise await _identity_conflict("aadhaarNumber", aadhaar, exclude_id, existing=clash)
        if pehchan and getattr(clash, "pehchanCardNumber", None) == pehchan:
            raise await _identity_conflict("pehchanCardNumber", pehchan, exclude_id, existing=clash)



def _duplicate_profile(data: dict[str, Any], location: Any) -> artisan_duplicates.Profile:
    return artisan_duplicates.profile_from_dict(
        {
            "name": data.get("name"),
            "localName": data.get("localName"),
            "gender": data.get("gender"),
            "dateOfBirth": data.get("dateOfBirth"),
            "phone": data.get("phone"),
            "place": data.get("place"),
            "state": getattr(location, "state", None),
            "district": getattr(location, "district", None),
            "village": getattr(location, "village", None),
            "craftId": data.get("craftId"),
            "aadhaarFingerprint": data.get(aadhaar_fingerprint.FINGERPRINT_FIELD),
            "pehchanCardNumber": data.get("pehchanCardNumber"),
        }
    )


def _possible_duplicate(
    held: list[tuple[artisan_duplicates.Profile, artisan_duplicates.Score]], *, typed_aadhaar: bool
) -> HTTPException:
    first, _ = held[0]
    name = first.display.get("name") or "an artisan"
    place = first.display.get("place")
    where = f" ({place})" if place else ""
    candidates = [
        artisan_duplicates.candidate_payload(other, result, typed_aadhaar=typed_aadhaar)
        for other, result in held[: artisan_duplicates.CHECK_MAX_CANDIDATES]
    ]
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail={
            "code": "artisan_possible_duplicate",
            "message": f"This may be {name}{where}, already recorded.",
            "candidates": candidates,
            "existingArtisan": {
                "id": first.id,
                "name": first.display.get("name"),
                "place": place,
                "craft": first.display.get("craft"),
            },
        },
    )


async def resolve_craft_id(
    data: dict[str, Any], current_user: Any, *, stored: Any | None = None
) -> dict[str, Any]:
    del current_user
    return await craft_master.resolve_record_craft(db, data, stored=stored)


def reconcile_aadhaar_not_shared(data: dict[str, Any], stored: Any | None = None) -> dict[str, Any]:
    number = data.get("aadhaarNumber")
    if isinstance(number, str) and number.strip():
        if stored is None or "aadhaarNotShared" in data or get_value(stored, "aadhaarNotShared"):
            data["aadhaarNotShared"] = False
        return data
    if stored is not None:
        stored_number = get_value(stored, aadhaar_fingerprint.FINGERPRINT_FIELD) or get_value(stored, "aadhaarNumber")
        number_after = data["aadhaarNumber"] if "aadhaarNumber" in data else stored_number
        if data.get("aadhaarNotShared") is True and stored_number:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="This artisan's Aadhaar is on file, so it cannot be marked as not shared.",
            )
        if data.get("aadhaarNotShared") is False and get_value(stored, "aadhaarNotShared") and not number_after:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Enter the 12-digit Aadhaar, or keep “Not shared by artisan”.",
            )
    if data.get("aadhaarNotShared") is True:
        data["aadhaarNumber"] = None
    return data


@router.get("")
async def list_artisans(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craft: str | None = None,
    craftId: str | None = None,
    craftIds: list[str] | None = Query(None),
    workshopId: str | None = None,
    place: str | None = None,
    statusFilter: str | None = None,
    createdBy: str | None = None,
    aadhaarNotShared: bool | None = None,
    workshopIds: list[str] | None = Query(None),
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
    resolved_crafts = resolve_craft_ids(craftIds)
    if resolved_crafts is not None:
        and_filters.append({"craftId": {"in": resolved_crafts}})
    if workshopId:
        and_filters.append({"OR": [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]})
    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        and_filters.append(artisan_workshop_clause(*resolved_workshops))
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    if aadhaarNotShared is not None:
        where["aadhaarNotShared"] = aadhaarNotShared
    if completeness_mode := completeness_module.parse_mode(completeness):
        and_filters.append(completeness_module.missing_where("artisan", completeness_mode))
    if and_filters:
        where["AND"] = and_filters
    total, items = await count_and_page(
        db.artisan,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    encoded = await completeness_module.attach("artisan", public_encode(items, current_user), client=db)
    await _attach_cards(encoded, current_user)
    return page_payload(encoded, total, page, page_size)


async def _attach_cards(encoded: list[dict[str, Any]], current_user: Any) -> None:
    settings = get_settings()
    if not encoded or not artisan_card.active(settings):
        return
    ids = [row["id"] for row in encoded if isinstance(row, dict) and row.get("id")]
    try:
        cards = await artisan_card_store.ensure_cards(
            db, ids, settings=settings, user_id=getattr(current_user, "id", None)
        )
        links = await db.crossportalartisanlink.find_many(
            where={"artisanId": {"in": ids}, "remoteCardCode": {"not": None}}
        )
    except Exception:
        logger.warning("artisan list: card fields left off this page", exc_info=True)
        return
    linked: dict[str, list[str]] = {}
    for link in links:
        linked.setdefault(link.artisanId, []).append(link.remoteCardCode)
    for row in encoded:
        card = cards.get(row.get("id"))
        row["card"] = artisan_card_store.card_codes(settings, card) if card else None
        row.setdefault("aadhaarLast4", None)
        row["linkedCardCodes"] = linked.get(row.get("id"), [])


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_artisan(
    payload: ArtisanCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    _standardise(apply_artisan_values, data)
    acknowledged = data.pop("duplicateAcknowledged", None)
    location_input = payload.location
    aadhaar = _seal_aadhaar(data)
    assert_form_contract(take_form_contract(data), data)
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    await _guard_identity_conflicts(data, aadhaar=aadhaar)
    acknowledged_ids = set(acknowledged or [])
    found: list[tuple[artisan_duplicates.Profile, artisan_duplicates.Score]] = []
    try:
        found = await artisan_duplicates.candidates_for_profile(
            _duplicate_profile(data, location_input), viewer=current_user
        )
    except Exception:  # noqa: BLE001
        logger.exception("duplicate scoring at create failed")
    if acknowledged is not None and get_settings().artisan_duplicate_hold:
        held = [
            pair
            for pair in found
            if pair[1].score >= artisan_duplicates.HOLD_THRESHOLD and pair[0].id not in acknowledged_ids
        ]
        if held:
            raise _possible_duplicate(held, typed_aadhaar=bool(aadhaar))
    try:
        created = await db.artisan.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001
        field = _violated_identity_field(exc)
        if field is None:
            raise
        value = aadhaar if field == "aadhaarNumber" else data.get(field)
        raise await _identity_conflict(field, value) from exc
    await link_workshop_artisan(created.workshopId, created.id)
    if found:
        try:
            await artisan_duplicates.store_candidates(
                created.id, found, source="CREATE", acknowledged=acknowledged_ids
            )
        except Exception:  # noqa: BLE001
            logger.exception("storing duplicate pairs at create failed")
    settings = get_settings()
    if artisan_card.active(settings):
        try:
            await artisan_card_store.ensure_card(
                db, created.id, settings=settings, user_id=current_user.id
            )
        except Exception:
            logger.warning("artisan create: card not issued yet", exc_info=True)
    return await completeness_module.attach("artisan", public_encode(created, current_user), client=db)


@router.get("/{artisan_id}")
async def get_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    artisan_id = await survivor_of(artisan_id) or artisan_id
    artisan = await require_record(db.artisan, artisan_id)
    await hydrate_relations([artisan], RELATIONS)
    return await completeness_module.attach("artisan", public_encode(artisan, current_user), client=db)


@router.patch("/{artisan_id}")
async def update_artisan(
    artisan_id: str,
    payload: ArtisanUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    asked_id = artisan_id
    artisan_id = await survivor_of(artisan_id) or artisan_id
    artisan = await require_record(db.artisan, artisan_id)
    data = clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    _standardise(apply_artisan_values, data, artisan)
    expected_updated_at = take_expected_updated_at(data)
    assert_form_contract(take_form_contract(data), data, stored=artisan)
    assert_expected_updated_at(artisan, expected_updated_at)
    data = drop_masked_identity_numbers(data)
    reconcile_aadhaar_not_shared(data, stored=artisan)
    aadhaar = _seal_aadhaar(data, previous=artisan)
    data = await resolve_craft_id(data, current_user, stored=artisan)
    data = await attach_location(data)
    check = None
    if "workshopId" in data and data.get("workshopId") != artisan.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    privileged = None
    if asked_id != artisan_id and await created_merged_record(asked_id, current_user.id):
        privileged = True
    await guard_record_edit(artisan, current_user, data, "artisan", privileged=privileged)
    await apply_status_policy_update(current_user, artisan, data)
    stamp_workshop_submission(data, check=check, record=artisan)
    pin_pending_if_late(data, current_user, check=check, record=artisan)
    merge_field_provenance(data, current_user, previous=artisan)
    resubmit_status(artisan, current_user, data)
    answered_yes = data.get("pehchanCardAvailable") is True
    clears_number = "pehchanCardNumber" in data and not data["pehchanCardNumber"]
    still_yes = data.get("pehchanCardAvailable", artisan.pehchanCardAvailable) is True
    if answered_yes or (clears_number and still_yes):
        number = data.get("pehchanCardNumber", artisan.pehchanCardNumber)
        if not number:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    "Enter the Artisan Pehchan Card number, or set the card to 'No' if the artisan "
                    "does not hold one."
                ),
            )
    await _guard_identity_conflicts(data, exclude_id=artisan_id, aadhaar=aadhaar)
    try:
        updated = await db.artisan.update(where={"id": artisan_id}, data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001
        field = _violated_identity_field(exc)
        if field is None:
            raise
        value = aadhaar if field == "aadhaarNumber" else data.get(field)
        raise await _identity_conflict(field, value, exclude_id=artisan_id) from exc
    await link_workshop_artisan(updated.workshopId, updated.id)
    await artisan_duplicates.rescore_artisan(updated.id)
    return await completeness_module.attach("artisan", public_encode(updated, current_user), client=db)


class AadhaarLookup(BaseModel):
    number: str = Field(max_length=40)


@router.post("/lookup/aadhaar")
async def lookup_artisan_by_aadhaar_body(
    payload: AadhaarLookup, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await _lookup_aadhaar(payload.number)


@router.get("/lookup/aadhaar")
async def lookup_artisan_by_aadhaar(
    number: str, _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await _lookup_aadhaar(number)


async def _lookup_aadhaar(number: str) -> dict[str, Any]:
    normalized = normalize_aadhaar(number)
    if not normalized or aadhaar_error(normalized):
        return {"found": False, "artisan": None}
    try:
        where = aadhaar_fingerprint.match_where(
            normalized, legacy=await aadhaar_fingerprint.legacy_pending(db)
        )
    except aadhaar_fingerprint.FingerprintKeyMissing as exc:
        logger.error("aadhaar lookup refused: %s", exc)
        raise _fingerprint_unavailable() from exc
    existing = await db.artisan.find_first(where=where, include={"craft": True, "workshop": True})
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
    artisan, responses, interview_rows, instruments = await gather_reads(
        db.artisan.find_unique(where={"id": artisan_id}),
        db.questionnaireresponse.find_many(
            where={
                "interview": {"is": {"artisans": {"some": {"artisanId": artisan_id}}}},
                "answerText": {"not": None},
            },
            include={"question": True, "interview": True, "answeredBy": True},
            order={"createdAt": "asc"},
        ),
        db.questionnaireinterview.find_many(
            where={"artisans": {"some": {"artisanId": artisan_id}}},
            include={"artisans": {"include": {"artisan": True}}, "media": True},
            order={"createdAt": "desc"},
        ),
        db.questionnaire.find_many(),
    )
    if not artisan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    instrument_by_id = {row.id: row for row in instruments}
    answered: list[dict[str, Any]] = []
    for response in responses:
        if not (response.answerText and response.answerText.strip()):
            continue
        question = response.question
        interview = response.interview
        answered_by = response.answeredBy
        questionnaire_id = (
            getattr(question, "questionnaireId", None)
            or getattr(interview, "questionnaireId", None)
        )
        instrument = instrument_by_id.get(questionnaire_id)
        answered.append(
            {
                "responseId": response.id,
                "questionId": response.questionId,
                "prompt": question.prompt if question else None,
                "sectionCode": question.sectionCode if question else None,
                "sectionTitle": question.sectionTitle if question else None,
                "sortOrder": question.sortOrder if question else 0,
                "questionnaireId": questionnaire_id,
                "questionnaireTitle": getattr(instrument, "title", None),
                "answerText": response.answerText,
                "notes": response.notes,
                "interviewId": response.interviewId,
                "interviewTitle": interview.title if interview else None,
                "interviewDate": interview.interviewDate if interview else None,
                "answeredByName": answered_by.name if answered_by else None,
            }
        )
    answered.sort(
        key=lambda item: (
            getattr(instrument_by_id.get(item.get("questionnaireId")), "sortOrder", 0) or 0,
            item.get("questionnaireId") or "",
            item.get("sectionCode") or "",
            item.get("sortOrder") or 0,
        )
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
    if await survivor_of(artisan_id) is not None and await db.artisan.find_unique(where={"id": artisan_id}) is None:
        return
    await require_record(db.artisan, artisan_id)
    if callable(getattr(type(db), "query_raw", None)):
        rows = await db.query_raw(
            'SELECT (SELECT count(*) FROM "QuestionnaireRecordingSubject" WHERE "artisanId" = $1) + '
            '(SELECT count(*) FROM "QuestionnaireAnswer" WHERE "artisanId" = $1) AS n',
            artisan_id,
        )
        held = int(rows[0]["n"]) if rows else 0
        if held:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={
                    "code": "HAS_RECORDINGS",
                    "message": f"This artisan has {held} questionnaire takes or answers. Merge them into the other record instead.",
                    "count": held,
                },
            )
    await db.artisan.delete(where={"id": artisan_id})


def _standardise(apply: Any, data: dict[str, Any], stored: Any = None) -> None:
    try:
        apply(data, stored)
    except StandardValueError as exc:
        raise HTTPException(status_code=422, detail=exc.message) from exc
