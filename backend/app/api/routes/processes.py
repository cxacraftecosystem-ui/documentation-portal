from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    enum_or_raw,
    get_current_user,
    require_record_creator,
)
from app.services.access import guard_record_edit, record_edit_privilege
from app.schemas.records import ProcessCreate, ProcessStepInput, ProcessUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    Relation,
    public_encode,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
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
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/processes", tags=["processes"])

_CLEARABLE_COLUMNS = ("notes",)

RELATIONS = (
    Relation("product", "productdocumentation", "productId"),
    Relation("createdBy", "user", "createdById"),
    Relation("steps", "processstep", "processId", many=True),
    Relation("workshop", "workshop", "workshopId"),
)


def _encode_light(process: Any) -> dict[str, Any]:
    encoded = public_encode(process)
    encoded["steps"] = sorted(encoded.get("steps") or [], key=lambda s: s.get("sortOrder", 0))
    return encoded


async def _hydrate(process: Any) -> dict[str, Any]:
    encoded = _encode_light(process)
    step_ids = [s["id"] for s in encoded["steps"]]
    lookup_ids = [process.id, *step_ids]
    media = await db.mediafile.find_many(
        where={"linkedRecordId": {"in": lookup_ids}},
        order={"createdAt": "asc"},
    )
    media_encoded = public_encode(media)
    by_record: dict[str, list[dict[str, Any]]] = {}
    for item in media_encoded:
        by_record.setdefault(item.get("linkedRecordId"), []).append(item)
    encoded["media"] = by_record.get(process.id, [])
    for step in encoded["steps"]:
        step["media"] = by_record.get(step["id"], [])
    return encoded


def _steps_touch_existing(existing: list[Any], steps: list[ProcessStepInput]) -> bool:
    by_id = {step.id: step for step in existing}
    kept: set[str] = set()
    for index, step in enumerate(steps):
        current = by_id.get(step.id) if step.id else None
        if current is None:
            continue
        kept.add(step.id)
        order = step.sortOrder if step.sortOrder else index + 1
        if (
            current.name != step.name
            or str(enum_or_raw(current.stepType)) != str(step.stepType)
            or current.sortOrder != order
            or current.notes != ((step.notes or "").strip() or None)
        ):
            return True
    return any(step.id not in kept for step in existing)


async def _sync_steps(process_id: str, steps: list[ProcessStepInput]) -> None:
    existing = await db.processstep.find_many(where={"processId": process_id})
    by_id = {step.id: step for step in existing}
    keep: set[str] = set()
    to_create: list[dict[str, Any]] = []
    to_update: list[tuple[str, dict[str, Any]]] = []
    for index, step in enumerate(steps):
        order = step.sortOrder if step.sortOrder else index + 1
        notes = (step.notes or "").strip() or None
        current = by_id.get(step.id) if step.id else None
        if current is not None:
            keep.add(step.id)
            unchanged = (
                current.name == step.name
                and str(enum_or_raw(current.stepType)) == str(step.stepType)
                and current.sortOrder == order
                and current.notes == notes
            )
            if not unchanged:
                to_update.append(
                    (step.id, {"name": step.name, "stepType": step.stepType, "sortOrder": order, "notes": notes})
                )
            continue
        to_create.append(
            {
                "processId": process_id,
                "name": step.name,
                "stepType": step.stepType,
                "sortOrder": order,
                "notes": notes,
            }
        )
    if to_create:
        await db.processstep.create_many(data=to_create)
    for step_id, data in to_update:
        await db.processstep.update(where={"id": step_id}, data=data)
    removed = [step.id for step in existing if step.id not in keep]
    if removed:
        await db.processstep.delete_many(where={"id": {"in": removed}})


async def _replay_response(process: Any, payload: ProcessCreate) -> dict[str, Any]:
    await hydrate_relations([process], RELATIONS)
    stored_steps = getattr(process, "steps", None) or []
    if payload.steps and not stored_steps:
        await _sync_steps(process.id, payload.steps)
        repaired = await db.process.find_unique(where={"id": process.id})
        await hydrate_relations([repaired], RELATIONS)
        return await _hydrate(repaired)
    return await _hydrate(process)


@router.get("")
async def list_processes(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    productId: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    createdBy: str | None = None,
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
        where["OR"] = [{"name": contains(search)}, {"notes": contains(search)}]
    if productId:
        where["productId"] = productId
    product_is: dict[str, Any] = {}
    if craftId:
        product_is["craftId"] = craftId
    if artisanId:
        product_is["artisanId"] = artisanId
    if product_is:
        where["product"] = {"is": product_is}
    if workshopId:
        and_filters.append({"OR": [
            {"workshopId": workshopId},
            {"product": {"is": {"workshopId": workshopId}}},
        ]})
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await count_and_page(
        db.process,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    return page_payload([_encode_light(item) for item in items], total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_process(
    payload: ProcessCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    replayed = await client_key_replay(db.process, payload.clientKey, user_id=current_user.id)
    if replayed is not None:
        return await _replay_response(replayed, payload)
    await require_record(db.productdocumentation, payload.productId)
    data = clean_data(payload.model_dump(exclude={"steps"}))
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.process.create(data=data)
    except Exception as exc:  # noqa: BLE001
        raced = await client_key_replay_after_violation(
            db.process, payload.clientKey, exc, user_id=current_user.id
        )
        if raced is None:
            raise
        return await _replay_response(raced, payload)
    await _sync_steps(created.id, payload.steps)
    await hydrate_relations([created], RELATIONS)
    return await _hydrate(created)


@router.get("/{process_id}")
async def get_process(process_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    process = await require_record(db.process, process_id)
    await hydrate_relations([process], RELATIONS)
    return await _hydrate(process)


@router.patch("/{process_id}")
async def update_process(
    process_id: str,
    payload: ProcessUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    process = await require_record(db.process, process_id)
    data = clean_data(
        payload.model_dump(exclude_unset=True, exclude={"steps"}), clearable=_CLEARABLE_COLUMNS
    )
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(process, expected_updated_at)
    if "productId" in data:
        if data["productId"] is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    "A process must belong to a product. Send another product's id to move it, or "
                    "delete the process."
                ),
            )
        await require_record(db.productdocumentation, data["productId"])
    check = None
    if "workshopId" in data and data.get("workshopId") != process.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    privileged = await record_edit_privilege(process, current_user, "process")
    if payload.steps is not None and not privileged:
        existing_steps = await db.processstep.find_many(where={"processId": process_id})
        assert_can_contribute_relation(
            process, current_user, _steps_touch_existing(existing_steps, payload.steps), "steps"
        )
    await guard_record_edit(process, current_user, data, "process", privileged=privileged)
    await apply_status_policy_update(current_user, process, data)
    stamp_workshop_submission(data, check=check, record=process)
    pin_pending_if_late(data, current_user, check=check, record=process)
    merge_field_provenance(data, current_user, previous=process)
    resubmit_status(process, current_user, data)
    if data:
        await db.process.update(where={"id": process_id}, data=data)
    if payload.steps is not None:
        await _sync_steps(process_id, payload.steps)
    hydrated = await db.process.find_unique(where={"id": process_id})
    await hydrate_relations([hydrated], RELATIONS)
    return await _hydrate(hydrated)


@router.delete("/{process_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_process(process_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.process, process_id)
    await db.process.delete(where={"id": process_id})
