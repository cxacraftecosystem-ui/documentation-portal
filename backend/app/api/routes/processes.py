from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.core.deps import require_record_creator, assert_can_delete, enum_or_raw, get_current_user
from app.services.access import guard_record_edit
from app.schemas.records import ProcessCreate, ProcessStepInput, ProcessUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    Relation,
    public_encode,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    clean_data,
    contains,
    count_and_page,
    hydrate_relations,
    merge_field_provenance,
    require_record,
    resubmit_status,
    visibility_where,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/processes", tags=["processes"])

# What a process carries on the wire, loaded in one parallel wave (see services/records.py for why).
# The write paths hydrate the row they saved rather than passing an ``include`` — steps are written
# after the process row exists, so there is nothing for a create-time include to load anyway.
RELATIONS = (
    Relation("product", "productdocumentation", "productId"),
    Relation("createdBy", "user", "createdById"),
    Relation("steps", "processstep", "processId", many=True),
    Relation("workshop", "workshop", "workshopId"),
)


def _encode_light(process: Any) -> dict[str, Any]:
    """Encode a process with its steps sorted, without the (heavier) media hydration."""
    encoded = public_encode(process)
    encoded["steps"] = sorted(encoded.get("steps") or [], key=lambda s: s.get("sortOrder", 0))
    return encoded


async def _hydrate(process: Any) -> dict[str, Any]:
    """Full detail: steps in order, plus media attached to the process and each step.

    Media is linked purely through ``linkedRecordType``/``linkedRecordId`` (``process`` for the
    pre-process clips, ``processstep`` for each step) so no MediaFile foreign keys are needed.
    """
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


async def _sync_steps(process_id: str, steps: list[ProcessStepInput]) -> None:
    """Upsert the supplied steps, preserving existing step IDs so their linked media survives edits.

    Batched rather than one statement per step. A process form re-sends its whole step list on every
    save, so an eight-step process cost eight sequential writes plus a delete each for anything
    removed — every one of them a cross-region round trip on this deployment. Now the new steps go in
    with a single insert, the removed ones leave with a single delete, and the only per-step writes
    left are the steps whose content actually changed, which on a typical save is one or none.

    New steps need no ids read back: a freshly inserted step can never be in the previously-existing
    set, so what survives the edit is decided entirely by the ids the caller sent.
    """
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
        where["OR"] = [{"name": contains(search)}, {"notes": contains(search)}]
    if productId:
        where["productId"] = productId
    # The craft/artisan funnel narrows processes through their parent product.
    product_is: dict[str, Any] = {}
    if craftId:
        product_is["craftId"] = craftId
    if artisanId:
        product_is["artisanId"] = artisanId
    if product_is:
        where["product"] = {"is": product_is}
    if workshopId:
        # Either reading counts: the process's own workshopId column, or its parent product's — which
        # is how every process recorded before the column got its workshop.
        and_filters.append({"OR": [
            {"workshopId": workshopId},
            {"product": {"is": {"workshopId": workshopId}}},
        ]})
    if statusFilter:
        where["status"] = statusFilter
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
    await require_record(db.productdocumentation, payload.productId)
    data = clean_data(payload.model_dump(exclude={"steps"}))
    # Workshop entries: enforce assignment, then flag + pin a late submission for admin approval.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    created = await db.process.create(data=data)
    await _sync_steps(created.id, payload.steps)
    # The steps were written after the row, so they are loaded here rather than on the create — and
    # hydrating the row we already hold saves reading it back a second time from another region.
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
    data = clean_data(payload.model_dump(exclude_unset=True, exclude={"steps"}))
    if "productId" in data:
        await require_record(db.productdocumentation, data["productId"])
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != process.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(process, current_user, data, "process")
    await apply_status_policy_update(current_user, process, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
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
