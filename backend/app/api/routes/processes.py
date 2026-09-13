from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

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

# PROCESS'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null`` through for
# on this model, so emptying the notes box on the process form actually empties the column instead of
# answering 200 and keeping the old text.
#
# It holds exactly one name, and it is a module constant anyway rather than a tuple written inline at
# the call: that is what lets a test read the list off the route instead of retyping it, which is the
# difference between pinning what this route declares and pinning what the test itself believes. The
# three sibling record routes each expose the same constant for the same reason.
#
# PER-MODEL AND NOT GLOBAL, for the reason ``clean_data``'s ``clearable`` docstring gives. Only valid
# because ``update_process`` dumps with ``exclude_unset=True``; see the note at that call.
#
# DELIBERATELY ABSENT: ``name``, ``productId``, ``preProcessAvailable``, ``status``, ``recordedAt``
# and ``recordedTimezone`` are NOT NULL; ``workshopId`` is already global; ``extraMetadata`` would be
# inert because ``merge_field_provenance`` reassigns (or pops) that key further down this route, so a
# null could never reach Prisma through it; and ``steps`` is a relation with its own write path,
# excluded from the dump entirely.
#
# ``productId`` IS THE ONE NAME LEAVING IT OUT DOES NOT ACTUALLY KEEP OUT, and that is worth knowing
# before you read the route. It sits in the GLOBAL ``records.CLEARABLE_KEYS`` — correctly, because it
# is a nullable back-reference on the models that merely POINT AT a product — so ``clean_data`` keeps
# an explicit null for it here too, and no per-model tuple can subtract from the global set. On
# ``Process`` the column is NOT NULL, so ``update_process`` refuses that null outright; see the
# branch beside ``require_record``.
_CLEARABLE_COLUMNS = ("notes",)

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


async def _replay_response(process: Any, payload: ProcessCreate) -> dict[str, Any]:
    """Answer a replayed create from the row it already wrote, repairing steps ONLY if there are none.

    TWO FAILURES ARE BEING TRADED OFF HERE AND BOTH ARE SILENT, which is why the rule is written out
    rather than inferred from the code.

    RE-RUNNING ``_sync_steps`` ON A PROCESS THAT HAS STEPS ORPHANS ITS MEDIA. A create body carries
    no step ids, so every step would land in ``to_create`` and the originals would be deleted: same
    names, same order, BRAND NEW ids. ``MediaFile.linkedRecordId`` has no foreign key onto
    ``ProcessStep``, and :func:`_hydrate` matches media against the CURRENT ids, so every file
    captured against a step would silently stop belonging to anything.

    NOT RUNNING IT ON A PROCESS THAT HAS NONE MAKES A HALF-FINISHED CREATE PERMANENT. There is no
    transaction here: ``db.process.create`` commits, and a failure in ``_sync_steps`` after it leaves
    a row with no steps that every later replay would answer 200 from, while the outbox records the
    entry as sent and the researcher's step list is gone for good.

    So: steps are written on a replay in exactly one case — the stored process has ZERO of them and
    the payload has some. That is the only shape a half-finished create leaves behind, and it is the
    one shape in which writing them cannot churn an id, because there is no id to churn.
    """
    await hydrate_relations([process], RELATIONS)
    stored_steps = getattr(process, "steps", None) or []
    if payload.steps and not stored_steps:
        await _sync_steps(process.id, payload.steps)
        # Re-read through the same relation wave, so the response carries the steps just written
        # rather than the empty list this row was hydrated with a moment ago.
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
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so the
    # free-text search OR and the workshop OR can never overwrite one another — nor the row-visibility
    # filter, which joins the same AND.
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
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
    # ── THE REPLAY MUST NOT RE-RUN ``_sync_steps`` ON A PROCESS THAT ALREADY HAS STEPS ───────────
    #
    # A create body carries no step ids (``ProcessStepInput.id`` is None on every step of a create),
    # so ``_sync_steps`` would put every step in ``to_create``, find nothing to ``keep``, and
    # ``delete_many`` all the originals. The count would be unchanged — the list does NOT double —
    # and every step id would be BRAND NEW. ``MediaFile.linkedRecordId`` addresses those ids with no
    # foreign key, and ``_hydrate`` below matches a step's media against the CURRENT ids, so every
    # photograph and clip captured against a step would become an orphan: still in the bucket, still
    # a row, attached to nothing, invisible on the record and undetectable after the fact. That is
    # the defect this branch guards, and it is why the guard is id equality rather than step count.
    #
    # AND YET IT CANNOT SIMPLY SKIP THE STEPS, BECAUSE THIS BACKEND HAS NO TRANSACTIONS. The row is
    # committed before ``_sync_steps`` runs, so a create that failed on the step list leaves a
    # permanently step-less process — and every later replay would answer 200 from it while the
    # outbox reports success and the researcher's steps are simply gone. So the repair is CONDITIONAL
    # and the condition is "the stored process has no steps at all": that is the only shape a
    # half-finished create can leave behind, and it is the one shape in which re-running ``_sync_steps``
    # cannot churn an id, because there are none to churn.
    #
    # Above ``require_record`` and above every gate, for the reason ``client_key_replay`` gives.
    replayed = await client_key_replay(db.process, payload.clientKey, user_id=current_user.id)
    if replayed is not None:
        return await _replay_response(replayed, payload)
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
    try:
        created = await db.process.create(data=data)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by is_client_key_violation
        # The race the pre-read cannot close: two drains of the same queue in flight at once, each
        # finding no row and each planning an INSERT. Only the index can settle it. ``None`` means
        # re-raise, and this does. The winner's row is answered from through the same conditional
        # repair the ordinary replay uses — never through ``_sync_steps`` on a populated process.
        raced = await client_key_replay_after_violation(
            db.process, payload.clientKey, exc, user_id=current_user.id
        )
        if raced is None:
            raise
        return await _replay_response(raced, payload)
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
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = clean_data(
        payload.model_dump(exclude_unset=True, exclude={"steps"}), clearable=_CLEARABLE_COLUMNS
    )
    # The precondition is a QUESTION, not a column — popped before anything reads ``data``, and
    # checked above ``guard_record_edit`` because that call ends in a COMMITTED ``RecordRevision``
    # row and this backend has no transaction to roll one back with.
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(process, expected_updated_at)
    if "productId" in data:
        # AN EXPLICIT ``null`` IS REFUSED HERE RATHER THAN FORWARDED. ``Process.productId`` is NOT
        # NULL — a process is documentation OF a product and cannot be orphaned — but the name is in
        # the global ``records.CLEARABLE_KEYS``, which a per-model ``clearable`` tuple can add to and
        # never subtract from, so the null survives the clean on this route as well. Until this branch
        # existed it fell straight into ``require_record(db.productdocumentation, None)`` — a lookup
        # for a product with no id, whose best case is a 404 blaming a product for not existing when
        # the real fault is that the caller asked to clear a column the model forbids clearing, and
        # whose worst case is a NOT NULL violation on the update below. 422 says what happened.
        if data["productId"] is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "A process must belong to a product. Send another product's id to move it, or "
                    "delete the process."
                ),
            )
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
