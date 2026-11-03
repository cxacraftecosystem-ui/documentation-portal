from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    get_value,
    require_admin,
    require_content_admin,
    require_workshop_manager,
)
from app.schemas.access import (
    WORKSHOP_REQUEST_MAX,
    WorkshopAccessDecisionIn,
    WorkshopAccessRequestIn,
    WorkshopAssignmentIn,
    WorkshopAssignmentUpdateIn,
    WorkshopGrantIn,
)
from app.schemas.questionnaire import WorkshopQuestionnaireUpdate
from app.schemas.records import WORKSHOP_TYPES, WorkshopCreate, WorkshopUpdate
from app.services.questionnaire_instruments import require_questionnaire
from app.services import email_outbox
from app.services.access import guard_record_edit, record_revision
from app.services import completeness as completeness_module
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    Relation,
    public_encode,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    attach_location,
    clean_data,
    client_key_replay,
    client_key_replay_after_violation,
    contains,
    count_and_page,
    hydrate_relations,
    merge_field_provenance,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.workshop_access import (
    DEFAULT_GRANT_LEVEL,
    WORKSHOP_DECISIONS,
    WORKSHOP_LEVEL_DESCRIPTIONS,
    WORKSHOP_LEVELS,
    access_denied_detail,
    describe_workshop_submission,
    enum_str,
    recordable_workshop_ids,
    resolve_workshop_access,
    valid_level,
    workshop_is_curated,
)
from app.services.app_settings import load_app_settings
from app.services.artisan_merge import redirect_merged_artisans
from app.services.workshop_auto_map import auto_map_status, set_auto_map_enabled
from app.services.workshop_inference import (
    apply_workshop_mapping,
    plan_workshop_mapping,
)

router = APIRouter(prefix="/workshops", tags=["workshops"])

RELATIONS = (
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("artisans", "workshopartisan", "workshopId", many=True, include={"artisan": True}),
    Relation("crafts", "workshopcraft", "workshopId", many=True, include={"craft": True}),
    Relation("questionnaire", "questionnaire", "questionnaireId"),
)

ASSIGNMENT_INCLUDE: dict[str, Any] = {
    "user": True,
    "assignedBy": True,
    "requestedBy": True,
    "decidedBy": True,
}
REQUEST_INCLUDE: dict[str, Any] = {**ASSIGNMENT_INCLUDE, "workshop": True}


def _level_or_422(value: Any, fallback: str | None) -> str | None:
    if value is None:
        return fallback
    level = enum_str(value)
    if not valid_level(level):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"accessLevel must be one of {', '.join(WORKSHOP_LEVELS)}",
        )
    return level


def _status_or_422(value: Any, allowed: tuple[str, ...]) -> str:
    state = enum_str(value)
    if state not in allowed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"status must be one of {', '.join(allowed)}",
        )
    return str(state)


async def _assignment_or_404(workshop_id: str, user_id: str) -> Any:
    row = await db.workshopassignment.find_unique(
        where={"workshopId_userId": {"workshopId": workshop_id, "userId": user_id}}
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No assignment for that user on this workshop")
    return row


async def _assignment_list(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.workshopassignment.find_many(
        where={"workshopId": workshop_id}, include=ASSIGNMENT_INCLUDE, order={"createdAt": "asc"}
    )
    return public_encode(rows)


async def _hydrate_assignment(row_id: str) -> dict[str, Any]:
    row = await db.workshopassignment.find_unique(where={"id": row_id}, include=REQUEST_INCLUDE)
    return public_encode(row)


async def replace_workshop_artisans(workshop_id: str, artisan_ids: list[str]) -> None:
    await db.workshopartisan.delete_many(where={"workshopId": workshop_id})
    unique_ids = list(dict.fromkeys(aid for aid in artisan_ids if aid))
    if unique_ids:
        await db.workshopartisan.create_many(
            data=[{"workshopId": workshop_id, "artisanId": aid} for aid in unique_ids]
        )


async def replace_workshop_crafts(workshop_id: str, craft_ids: list[str]) -> None:
    await db.workshopcraft.delete_many(where={"workshopId": workshop_id})
    unique_ids = list(dict.fromkeys(cid for cid in craft_ids if cid))
    if unique_ids:
        await db.workshopcraft.create_many(
            data=[{"workshopId": workshop_id, "craftId": cid} for cid in unique_ids]
        )


async def _repair_empty_rosters(
    workshop_id: str, artisan_ids: list[str], craft_ids: list[str]
) -> None:
    if artisan_ids:
        existing_artisans = await db.workshopartisan.count(where={"workshopId": workshop_id})
        if not existing_artisans:
            await replace_workshop_artisans(workshop_id, artisan_ids)
    if craft_ids:
        existing_crafts = await db.workshopcraft.count(where={"workshopId": workshop_id})
        if not existing_crafts:
            await replace_workshop_crafts(workshop_id, craft_ids)


def normalize_workshop_dates(data: dict[str, Any]) -> dict[str, Any]:
    if not data.get("date") and data.get("startDate"):
        data["date"] = data["startDate"]
    if not data.get("startDate") and data.get("date"):
        data["startDate"] = data["date"]
    if not data.get("endDate") and data.get("startDate"):
        data["endDate"] = data["startDate"]
    return data


@router.get("")
async def list_workshops(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    place: str | None = None,
    workshopType: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    statusFilter: str | None = None,
    createdBy: str | None = None,
    completeness: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    vis = await viewable_where(current_user)
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [{"title": contains(search)}, {"place": contains(search)}, {"description": contains(search)}]
    if place:
        where["place"] = contains(place)
    if workshopType:
        if workshopType not in WORKSHOP_TYPES:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}",
            )
        where["workshopType"] = workshopType
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    completeness_module.add_filter(where, "workshop", completeness)
    add_date_range(where, "startDate", dateFrom, dateTo)
    total, items = await count_and_page(
        db.workshop,
        where=where,
        skip=skip,
        take=page_size,
        order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}],
        relations=RELATIONS,
    )
    encoded = await completeness_module.attach("workshop", public_encode(items), client=db)
    recordable = await recordable_workshop_ids(current_user, [row["id"] for row in encoded])
    for row in encoded:
        row["canRecord"] = row["id"] in recordable
    return page_payload(encoded, total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop(
    payload: WorkshopCreate,
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    await redirect_merged_artisans(payload)
    replayed = await client_key_replay(db.workshop, payload.clientKey, user_id=current_user.id)
    if replayed is not None:
        await _repair_empty_rosters(
            replayed.id, payload.artisanIds or [], payload.craftIds or []
        )
        await hydrate_relations([replayed], RELATIONS)
        return await completeness_module.attach("workshop", public_encode(replayed), client=db)
    data = clean_data(payload.model_dump())
    artisan_ids = data.pop("artisanIds", [])
    craft_ids = data.pop("craftIds", [])
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    try:
        created = await db.workshop.create(data=data)
    except Exception as exc:  # noqa: BLE001
        raced = await client_key_replay_after_violation(
            db.workshop, payload.clientKey, exc, user_id=current_user.id
        )
        if raced is None:
            raise
        await _repair_empty_rosters(raced.id, artisan_ids, craft_ids)
        await hydrate_relations([raced], RELATIONS)
        return await completeness_module.attach("workshop", public_encode(raced), client=db)
    if artisan_ids:
        await replace_workshop_artisans(created.id, artisan_ids)
    if craft_ids:
        await replace_workshop_crafts(created.id, craft_ids)
    await hydrate_relations([created], RELATIONS)
    return await completeness_module.attach("workshop", public_encode(created), client=db)



@router.get("/unmapped")
async def unmapped_records(_: Any = Depends(require_content_admin)) -> dict[str, Any]:
    plan = await plan_workshop_mapping()
    plan["autoMap"] = auto_map_status(await load_app_settings())
    return plan


class AutoMapSetting(BaseModel):
    enabled: bool


@router.put("/unmapped/auto")
async def set_unmapped_auto(
    payload: AutoMapSetting, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    row = await set_auto_map_enabled(payload.enabled, getattr(current_user, "id", None))
    return auto_map_status(row)


@router.post("/unmapped/map")
async def map_unmapped_records(_: Any = Depends(require_content_admin)) -> dict[str, Any]:
    return await apply_workshop_mapping()



@router.get("/access-levels")
async def list_access_levels(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    return [{"level": level, "description": WORKSHOP_LEVEL_DESCRIPTIONS[level]} for level in WORKSHOP_LEVELS]


@router.get("/requestable")
async def list_requestable_workshops(
    current_user: Any = Depends(get_current_user),
    limit: int = Query(WORKSHOP_REQUEST_MAX, ge=1, le=WORKSHOP_REQUEST_MAX),
) -> dict[str, Any]:
    uid = get_value(current_user, "id")
    workshops = await db.workshop.find_many(
        take=limit + 1, order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}]
    )
    truncated = len(workshops) > limit
    workshops = workshops[:limit]
    ids = [w.id for w in workshops]
    rows = await db.workshopassignment.find_many(where={"workshopId": {"in": ids}}) if ids else []
    by_workshop: dict[str, list[Any]] = {}
    for row in rows:
        by_workshop.setdefault(row.workshopId, []).append(row)
    items: list[dict[str, Any]] = []
    for workshop in workshops:
        workshop_rows = by_workshop.get(workshop.id, [])
        mine = next((r for r in workshop_rows if r.userId == uid), None)
        items.append(
            {
                "id": workshop.id,
                "title": workshop.title,
                "place": workshop.place,
                "date": workshop.date,
                "startDate": workshop.startDate,
                "endDate": workshop.endDate,
                "accessStatus": enum_str(mine.status) if mine is not None else None,
                "accessLevel": enum_str(mine.accessLevel) if mine is not None else None,
                "restricted": workshop_is_curated(workshop_rows),
            }
        )
    return {"items": public_encode(items), "truncated": truncated}


@router.post("/access-requests", status_code=status.HTTP_201_CREATED)
async def request_workshop_access(
    payload: WorkshopAccessRequestIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    uid = get_value(current_user, "id")
    level = _level_or_422(payload.accessLevel, DEFAULT_GRANT_LEVEL)
    wanted: list[str] = list(dict.fromkeys(wid for wid in payload.workshopIds if wid))
    if not wanted:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Select at least one workshop")
    found = {w.id for w in await db.workshop.find_many(where={"id": {"in": wanted}})}
    missing = [wid for wid in wanted if wid not in found]
    if missing:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"{'Workshop' if len(missing) == 1 else 'Workshops'} not found: {', '.join(missing)}"
        )
    existing_rows = await db.workshopassignment.find_many(
        where={"workshopId": {"in": wanted}, "userId": uid}
    )
    by_workshop = {row.workshopId: row for row in existing_rows}

    outcomes: list[dict[str, str]] = []
    to_create: list[str] = []
    to_rerequest: list[str] = []
    for workshop_id in wanted:
        existing = by_workshop.get(workshop_id)
        if existing is None:
            to_create.append(workshop_id)
            outcomes.append({"workshopId": workshop_id, "outcome": "CREATED"})
            continue
        state = enum_str(existing.status)
        if state in {"GRANTED", "PENDING"}:
            outcomes.append(
                {"workshopId": workshop_id, "outcome": "ALREADY_GRANTED" if state == "GRANTED" else "ALREADY_PENDING"}
            )
            continue
        to_rerequest.append(existing.id)
        outcomes.append({"workshopId": workshop_id, "outcome": "RE_REQUESTED"})

    if to_create:
        await db.workshopassignment.create_many(
            data=[
                {
                    "workshopId": workshop_id,
                    "userId": uid,
                    "accessLevel": level,
                    "status": "PENDING",
                    "requestedById": uid,
                    "requestNote": payload.note,
                }
                for workshop_id in to_create
            ]
        )
    if to_rerequest:
        await db.workshopassignment.update_many(
            where={"id": {"in": to_rerequest}},
            data={
                "accessLevel": level,
                "status": "PENDING",
                "requestedById": uid,
                "requestNote": payload.note,
                "assignedById": None,
                "decidedById": None,
                "decidedAt": None,
                "decisionNote": None,
            },
        )
    rows = await db.workshopassignment.find_many(
        where={"workshopId": {"in": wanted}, "userId": uid}, include=REQUEST_INCLUDE
    )
    asked = set(to_create) | {row.workshopId for row in existing_rows if row.id in set(to_rerequest)}
    if asked:
        titles = [
            str(getattr(getattr(row, "workshop", None), "title", None) or "a workshop")
            for row in rows
            if row.workshopId in asked
        ]
        await email_outbox.notify_workshop_access_request(
            requester=current_user, workshop_titles=titles, note=payload.note
        )
    return {"outcomes": outcomes, "requests": public_encode(rows)}


@router.get("/access-requests/mine")
async def my_workshop_access(
    current_user: Any = Depends(get_current_user), statusFilter: str | None = None
) -> list[dict[str, Any]]:
    where: dict[str, Any] = {"userId": get_value(current_user, "id")}
    if statusFilter:
        where["status"] = _status_or_422(statusFilter, ("PENDING", "GRANTED", "DENIED", "REVOKED"))
    rows = await db.workshopassignment.find_many(
        where=where, include=REQUEST_INCLUDE, order={"updatedAt": "desc"}
    )
    return public_encode(rows)


@router.get("/access-requests")
async def list_workshop_access_requests(
    _: Any = Depends(require_admin), statusFilter: str = "PENDING"
) -> list[dict[str, Any]]:
    where: dict[str, Any] = {}
    if statusFilter and statusFilter.upper() != "ALL":
        where["status"] = _status_or_422(statusFilter, ("PENDING", "GRANTED", "DENIED", "REVOKED"))
    rows = await db.workshopassignment.find_many(
        where=where, include=REQUEST_INCLUDE, order={"createdAt": "asc"}
    )
    return public_encode(rows)


@router.post("/access-requests/{request_id}/decide")
async def decide_workshop_access_request(
    request_id: str, payload: WorkshopAccessDecisionIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    decision = _status_or_422(payload.status, ("GRANTED", "DENIED"))
    row = await db.workshopassignment.find_unique(where={"id": request_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Access request not found")
    if enum_str(row.status) != "PENDING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This request has already been decided. Change access from the roster.",
        )
    await db.workshopassignment.update(
        where={"id": row.id},
        data={
            "status": decision,
            "accessLevel": _level_or_422(payload.accessLevel, enum_str(row.accessLevel)),
            "decidedById": get_value(current_user, "id"),
            "decidedAt": datetime.now(UTC),
            "decisionNote": payload.note,
        },
    )
    workshop = await db.workshop.find_unique(where={"id": row.workshopId})
    await email_outbox.notify_workshop_access_decision(
        user_id=row.userId,
        actor=current_user,
        workshop_title=getattr(workshop, "title", None),
        decision=decision,
        note=payload.note,
    )
    return await _hydrate_assignment(row.id)


@router.get("/{workshop_id}")
async def get_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    await hydrate_relations([workshop], RELATIONS)
    return await completeness_module.attach("workshop", public_encode(workshop), client=db)


@router.patch("/{workshop_id}")
async def update_workshop(
    workshop_id: str,
    payload: WorkshopUpdate,
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    await redirect_merged_artisans(payload)
    data = clean_data(payload.model_dump(exclude_unset=True))
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(workshop, expected_updated_at)
    artisan_ids = data.pop("artisanIds", None)
    craft_ids = data.pop("craftIds", None)
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    access = await resolve_workshop_access(current_user, workshop_id)
    if get_value(workshop, "createdById") != get_value(current_user, "id") and not access.at_least("CONTRIBUTE"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=access_denied_detail(access, "CONTRIBUTE")
        )
    privileged = access.at_least("EDIT")
    if privileged:
        await record_revision(workshop, current_user, data, "workshop")
    else:
        privileged = await guard_record_edit(workshop, current_user, data, "workshop")
    await apply_status_policy_update(current_user, workshop, data)
    merge_field_provenance(data, current_user, previous=workshop)
    resubmit_status(workshop, current_user, data)
    updated = await db.workshop.update(where={"id": workshop_id}, data=data)
    if artisan_ids is not None:
        link_count = await db.workshopartisan.count(where={"workshopId": workshop_id})
        if not privileged:
            assert_can_contribute_relation(workshop, current_user, link_count > 0, "artisanIds")
        await replace_workshop_artisans(workshop_id, artisan_ids)
    if craft_ids is not None:
        craft_link_count = await db.workshopcraft.count(where={"workshopId": workshop_id})
        if not privileged:
            assert_can_contribute_relation(workshop, current_user, craft_link_count > 0, "craftIds")
        await replace_workshop_crafts(workshop_id, craft_ids)
    await hydrate_relations([updated], RELATIONS)
    return await completeness_module.attach("workshop", public_encode(updated), client=db)


@router.get("/{workshop_id}/submission-check")
async def workshop_submission_check(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    check = await describe_workshop_submission(current_user, workshop_id)
    return check.payload()


@router.get("/{workshop_id}/assignments")
async def list_workshop_assignments(workshop_id: str, _: Any = Depends(require_admin)) -> list[dict[str, Any]]:
    await require_record(db.workshop, workshop_id)
    return await _assignment_list(workshop_id)


@router.put("/{workshop_id}/assignments")
async def set_workshop_assignments(
    workshop_id: str, payload: WorkshopAssignmentIn, current_user: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    await require_record(db.workshop, workshop_id)
    requested_level = _level_or_422(payload.accessLevel, None)
    wanted = {uid for uid in payload.userIds if uid}
    existing = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    by_user = {r.userId: r for r in existing}
    now = datetime.now(UTC)
    admin_id = get_value(current_user, "id")

    revoke_ids = [
        row.id
        for uid, row in by_user.items()
        if uid not in wanted and enum_str(row.status) != "REVOKED"
    ]
    create_rows = [uid for uid in wanted if uid not in by_user]
    grant_ids_by_level: dict[str, list[str]] = {}
    for uid in wanted:
        row = by_user.get(uid)
        if row is None:
            continue
        level = requested_level or enum_str(row.accessLevel) or DEFAULT_GRANT_LEVEL
        grant_ids_by_level.setdefault(level, []).append(row.id)

    if revoke_ids:
        await db.workshopassignment.update_many(
            where={"id": {"in": revoke_ids}},
            data={
                "status": "REVOKED",
                "decidedById": admin_id,
                "decidedAt": now,
                "decisionNote": "Removed from the workshop roster.",
            },
        )
    if create_rows:
        await db.workshopassignment.create_many(
            data=[
                {
                    "workshopId": workshop_id,
                    "userId": uid,
                    "assignedById": admin_id,
                    "accessLevel": requested_level or DEFAULT_GRANT_LEVEL,
                    "status": "GRANTED",
                }
                for uid in create_rows
            ]
        )
    for level, ids in grant_ids_by_level.items():
        await db.workshopassignment.update_many(
            where={"id": {"in": ids}},
            data={
                "assignedById": admin_id,
                "accessLevel": level,
                "status": "GRANTED",
                "decidedById": admin_id,
                "decidedAt": now,
            },
        )
    return await _assignment_list(workshop_id)


@router.post("/{workshop_id}/assignments", status_code=status.HTTP_201_CREATED)
async def grant_workshop_assignment(
    workshop_id: str, payload: WorkshopGrantIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    await require_record(db.workshop, workshop_id)
    user = await db.user.find_unique(where={"id": payload.userId})
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    admin_id = get_value(current_user, "id")
    now = datetime.now(UTC)
    existing = await db.workshopassignment.find_unique(
        where={"workshopId_userId": {"workshopId": workshop_id, "userId": payload.userId}}
    )
    level = _level_or_422(
        payload.accessLevel, enum_str(existing.accessLevel) if existing is not None else DEFAULT_GRANT_LEVEL
    )
    data: dict[str, Any] = {
        "accessLevel": level,
        "status": "GRANTED",
        "assignedById": admin_id,
        "decidedById": admin_id,
        "decidedAt": now,
        "decisionNote": payload.note,
    }
    if existing is None:
        created = await db.workshopassignment.create(
            data={**data, "workshopId": workshop_id, "userId": payload.userId}
        )
        return await _hydrate_assignment(created.id)
    await db.workshopassignment.update(where={"id": existing.id}, data=data)
    return await _hydrate_assignment(existing.id)


@router.patch("/{workshop_id}/assignments/{user_id}")
async def update_workshop_assignment(
    workshop_id: str, user_id: str, payload: WorkshopAssignmentUpdateIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    await require_record(db.workshop, workshop_id)
    row = await _assignment_or_404(workshop_id, user_id)
    data: dict[str, Any] = {}
    level = _level_or_422(payload.accessLevel, None)
    if level is not None:
        data["accessLevel"] = level
    if payload.status is not None:
        data["status"] = _status_or_422(payload.status, WORKSHOP_DECISIONS)
    if not data:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Send accessLevel, status, or both"
        )
    data["decidedById"] = get_value(current_user, "id")
    data["decidedAt"] = datetime.now(UTC)
    if payload.note is not None:
        data["decisionNote"] = payload.note
    await db.workshopassignment.update(where={"id": row.id}, data=data)
    return await _hydrate_assignment(row.id)


@router.delete("/{workshop_id}/assignments/{user_id}")
async def revoke_workshop_assignment(
    workshop_id: str, user_id: str, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    await require_record(db.workshop, workshop_id)
    row = await _assignment_or_404(workshop_id, user_id)
    await db.workshopassignment.update(
        where={"id": row.id},
        data={
            "status": "REVOKED",
            "decidedById": get_value(current_user, "id"),
            "decidedAt": datetime.now(UTC),
            "decisionNote": "Access revoked.",
        },
    )
    return await _hydrate_assignment(row.id)


_LIVE_TASK_STATUSES = ["OPEN", "IN_PROGRESS"]

_REBIND_NOTE = (
    "Cancelled because this workshop's questionnaire changed and this task covered sections of the "
    "previous one. Assign the work again."
)


async def _tasks_bound_to_outgoing_instrument(workshop_id: str, instrument_id: str) -> list[Any]:
    tasks, sections = await gather_reads(
        db.assignedtask.find_many(
            where={"workshopId": workshop_id, "status": {"in": _LIVE_TASK_STATUSES}}
        ),
        db.questionnairesection.find_many(where={"questionnaireId": instrument_id}),
    )
    section_ids = {section.id for section in sections}
    return [task for task in tasks if section_ids & set(get_value(task, "sectionIds") or [])]


async def _clear_task_sections(workshop_id: str, instrument_id: str) -> None:
    outgoing = await db.questionnairesection.find_many(where={"questionnaireId": instrument_id})
    outgoing_ids = {section.id for section in outgoing}
    for task in await _tasks_bound_to_outgoing_instrument(workshop_id, instrument_id):
        remaining = [
            section_id
            for section_id in (get_value(task, "sectionIds") or [])
            if section_id not in outgoing_ids
        ]
        data: dict[str, Any] = {"sectionIds": {"set": remaining}}
        if not remaining and not (get_value(task, "recordTypes") or []):
            existing = (get_value(task, "description") or "").strip()
            data["status"] = "CANCELLED"
            data["description"] = f"{existing}\n\n{_REBIND_NOTE}".strip()
        await db.assignedtask.update(where={"id": task.id}, data=data)


@router.put("/{workshop_id}/questionnaire")
async def set_workshop_questionnaire(
    workshop_id: str,
    payload: WorkshopQuestionnaireUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    target = payload.questionnaireId
    if target:
        instrument = await require_questionnaire(target)
        if not instrument.isActive:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"“{instrument.title}” is retired and cannot be assigned to a workshop.",
            )
    current = get_value(workshop, "questionnaireId")
    changing = bool(current) and current != target
    if changing and not payload.reassignTasks:
        blocked = await _tasks_bound_to_outgoing_instrument(workshop_id, current)
        if blocked:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"{len(blocked)} open {'task at this workshop is' if len(blocked) == 1 else 'tasks at this workshop are'} scoped to sections of the "
                    f"current questionnaire. Clear or re-scope them first, or resend with "
                    f"reassignTasks=true to clear their sectionIds."
                ),
            )
    if changing and payload.reassignTasks:
        await _clear_task_sections(workshop_id, current)
    updated = await db.workshop.update(
        where={"id": workshop_id}, data={"questionnaireId": target}
    )
    await hydrate_relations([updated], RELATIONS)
    return await completeness_module.attach("workshop", public_encode(updated), client=db)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.workshop, workshop_id)
    await db.workshop.delete(where={"id": workshop_id})
