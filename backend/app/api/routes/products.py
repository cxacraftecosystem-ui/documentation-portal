from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.core.deps import require_record_creator, assert_can_delete, get_current_user
from app.schemas.records import ProductCreate, ProductUpdate
from app.services.access import guard_record_edit
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)
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
    decimal_to_string,
    hydrate_relations,
    include_of,
    merge_field_provenance,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)

router = APIRouter(prefix="/products", tags=["products"])

# What a product carries on the wire. Reads load these in one parallel wave (see
# services/records.py for why); writes still pass the derived ``INCLUDE`` to Prisma, so the two can
# never describe different products.
RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "productId", many=True),
    Relation("createdBy", "user", "createdById"),
)
INCLUDE = include_of(RELATIONS)

# PRODUCTDOCUMENTATION'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null``
# through for on this model, so emptying a box on the product form actually empties the column
# instead of answering 200 and keeping the old value.
#
# PER-MODEL AND NOT GLOBAL, for the reason ``clean_data``'s ``clearable`` docstring gives: the global
# set cannot know which table a payload is bound for. Derived from ``model ProductDocumentation`` in
# prisma/schema.prisma, intersected with what ``ProductUpdate`` actually accepts — do not copy this
# tuple to Tool or Artisan, whose nullable columns are a different list.
#
# Only valid because ``update_product`` dumps with ``exclude_unset=True``; see the note at that call.
#
# DELIBERATELY ABSENT: ``craftName``/``place``/``artisanName``/``productName`` (NOT NULL), the two
# enums ``productType``/``marketDemand`` and ``status``/``recordedAt``/``recordedTimezone`` (NOT NULL
# with defaults), ``artisanId``/``craftId``/``workshopId``/``locationId`` (already global), and the
# measurement trio ``measurementImageId``/``measurementAnalysis``/``measurementAnalysisStatus``,
# which ``services/media_queue`` owns — ``records.PROVENANCE_SKIP_FIELDS`` already classes all three
# as system-managed, and no client form sends them. ``extraMetadata`` is left out because naming it
# would be inert: ``merge_field_provenance`` rebuilds and reassigns that column further down this
# route, so a null could never reach Prisma anyway. ``clientKey`` is absent for a stronger reason
# than any of those: it is on no update schema at all, because a correction carrying a key would be
# refused by ``extra="forbid"`` and re-attempted by an outbox for ever.
_CLEARABLE_COLUMNS = (
    "localName",
    "timeTakenToCompleteProduct",
    "size",
    "lengthInches",
    "breadthInches",
    "heightInches",
    "costOfMaking",
    "sellingPrice",
    "rawMaterialsUsed",
    "mainToolsUsed",
    "productFunctionUse",
    "remarks",
)


@router.get("")
async def list_products(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    artisanName: str | None = None,
    workshopId: str | None = None,
    place: str | None = None,
    marketDemand: str | None = None,
    productType: str | None = None,
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
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so that,
    # e.g., a free-text search OR and the artisan-name OR never overwrite one another. The row-visibility
    # filter joins the same AND, so it too is safe from being clobbered by any OR.
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        and_filters.append({"OR": [
            {"productName": contains(search)},
            {"localName": contains(search)},
            {"craftName": contains(search)},
            {"artisanName": contains(search)},
            {"place": contains(search)},
            {"rawMaterialsUsed": contains(search)},
            {"mainToolsUsed": contains(search)},
            {"remarks": contains(search)},
        ]})
    if craftId:
        where["craftId"] = craftId
    if artisanId:
        if artisanName and artisanName.strip():
            # Match products linked to this artisan by FK, PLUS *every* product that carries this
            # artisan's typed name (case-insensitive) regardless of its FK. This is deliberately
            # inclusive so the process form's product dropdown never hides a product that genuinely
            # belongs to the artisan — covering products saved with a typed name and no FK link, and
            # products FK-linked to a duplicate artisan record that shares the same name. The only
            # cost is that two genuinely distinct artisans with an identical name would share a list,
            # which is rare and far preferable to silently dropping a real product.
            and_filters.append({"OR": [
                {"artisanId": artisanId},
                {"artisanName": {"equals": artisanName.strip(), "mode": "insensitive"}},
            ]})
        else:
            where["artisanId"] = artisanId
    if workshopId:
        where["workshopId"] = workshopId
    if place:
        where["place"] = contains(place)
    if marketDemand:
        where["marketDemand"] = marketDemand
    if productType:
        where["productType"] = productType
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await count_and_page(
        db.productdocumentation,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_product(
    payload: ProductCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    # ── THE IDEMPOTENT REPLAY, ABOVE EVERY WRITE AND EVERY GATE IN THIS ROUTE ────────────────────
    #
    # A create whose answer was lost on the way back is still in the client's queue and is sent
    # again; without this branch the second landing writes a SECOND product. Answering from the
    # stored row makes the replay indistinguishable from the first landing, which is the whole point:
    # a client that could tell them apart would have to decide what to do about it, and it has no
    # information to decide with.
    #
    # ABOVE THE GATES, NEVER BELOW. On a replay the row already exists, so re-asking
    # ``enforce_workshop_submission`` can only turn a create that SUCCEEDED into a 403 for a
    # researcher whose workshop assignment was withdrawn in the meantime — and it keeps
    # ``attach_location`` from minting a second, unreferenced ``Location`` row per replay.
    #
    # ``include=INCLUDE`` because the create below answers through the same include; the two
    # responses have to be byte-comparable.
    replayed = await client_key_replay(
        db.productdocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        return public_encode(replayed)
    data = decimal_to_string(clean_data(payload.model_dump()))
    data = await attach_location(data)
    # Workshop entries: enforce assignment, then flag + pin a late submission for admin approval.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.productdocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by is_client_key_violation
        # ``client_key_replay`` above closes the ordinary case with one read. This closes the one it
        # cannot: two passes of the same queue in flight at once (two browser tabs, a phone whose sync
        # fired twice, a restored queue drained beside the original), each finding no row and each
        # planning an INSERT. Only the index can settle that. ``None`` means re-raise, and this does —
        # swallowing an exception that is NOT a ``clientKey`` violation would report a failed create
        # as a successful one. A product has no child writes, so there is nothing here to repair.
        raced = await client_key_replay_after_violation(
            db.productdocumentation,
            payload.clientKey,
            exc,
            user_id=current_user.id,
            include=INCLUDE,
        )
        if raced is None:
            raise
        return public_encode(raced)
    return public_encode(created)


@router.get("/{product_id}")
async def get_product(product_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    await hydrate_relations([product], RELATIONS)
    return public_encode(product)


@router.patch("/{product_id}")
async def update_product(
    product_id: str,
    payload: ProductUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    # The precondition is a QUESTION, not a column — see ``update_artisan`` for why it is popped on
    # the line after the clean, and ``records.assert_expected_updated_at`` for why the check sits
    # above ``guard_record_edit`` rather than beside the write.
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(product, expected_updated_at)
    data = await attach_location(data)
    # Moving a record into (or to a different) workshop is a workshop submission too — re-check
    # assignment + window, so the create-time guard can't be bypassed by PATCHing the workshop in later.
    check = None
    if "workshopId" in data and data.get("workshopId") != product.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(product, current_user, data, "product")
    await apply_status_policy_update(current_user, product, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=product)
    pin_pending_if_late(data, current_user, check=check, record=product)
    merge_field_provenance(data, current_user, previous=product)
    resubmit_status(product, current_user, data)
    updated = await db.productdocumentation.update(where={"id": product_id}, data=data, include=INCLUDE)
    return public_encode(updated)


@router.delete("/{product_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_product(product_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.productdocumentation, product_id)
    await db.productdocumentation.delete(where={"id": product_id})
