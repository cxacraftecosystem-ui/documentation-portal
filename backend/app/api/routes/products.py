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
    attach_location,
    clean_data,
    contains,
    count_and_page,
    decimal_to_string,
    hydrate_relations,
    include_of,
    merge_field_provenance,
    require_record,
    resubmit_status,
    visibility_where,
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
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so that,
    # e.g., a free-text search OR and the artisan-name OR never overwrite one another. The row-visibility
    # filter joins the same AND, so it too is safe from being clobbered by any OR.
    and_filters: list[dict[str, Any]] = []
    vis = await visibility_where(current_user)
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
    created = await db.productdocumentation.create(data=data, include=INCLUDE)
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
    data = decimal_to_string(clean_data(payload.model_dump(exclude_unset=True)))
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
