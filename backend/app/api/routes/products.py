from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.services.artisan_merge import redirect_merged_artisans
from app.core.deps import require_record_creator, assert_can_delete, get_current_user
from app.schemas.records import ProductCreate, ProductUpdate
from app.services.access import guard_record_edit
from app.services import craft_master
from app.services.linked_names import fill_product_names
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)
from app.services import completeness as completeness_module
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

RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "productId", many=True),
    Relation("createdBy", "user", "createdById"),
)
INCLUDE = include_of(RELATIONS)

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
    createdBy: str | None = None,
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
    if completeness_mode := completeness_module.parse_mode(completeness):
        and_filters.append(completeness_module.missing_where("product", completeness_mode))
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
    encoded = await completeness_module.attach("product", public_encode(items), client=db)
    return page_payload(encoded, total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_product(
    payload: ProductCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    replayed = await client_key_replay(
        db.productdocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        return await completeness_module.attach("product", public_encode(replayed), client=db)
    await redirect_merged_artisans(payload)
    data = decimal_to_string(clean_data(payload.model_dump()))
    data = await craft_master.resolve_record_craft(db, data, keep_name=True)
    data = await fill_product_names(data)
    data = await attach_location(data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.productdocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001
        raced = await client_key_replay_after_violation(
            db.productdocumentation,
            payload.clientKey,
            exc,
            user_id=current_user.id,
            include=INCLUDE,
        )
        if raced is None:
            raise
        return await completeness_module.attach("product", public_encode(raced), client=db)
    return await completeness_module.attach("product", public_encode(created), client=db)


@router.get("/{product_id}")
async def get_product(product_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    await hydrate_relations([product], RELATIONS)
    return await completeness_module.attach("product", public_encode(product), client=db)


@router.patch("/{product_id}")
async def update_product(
    product_id: str,
    payload: ProductUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    await redirect_merged_artisans(payload)
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(product, expected_updated_at)
    data = await craft_master.resolve_record_craft(db, data, stored=product, keep_name=True)
    data = await fill_product_names(data, stored=product)
    data = await attach_location(data)
    check = None
    if "workshopId" in data and data.get("workshopId") != product.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(product, current_user, data, "product")
    await apply_status_policy_update(current_user, product, data)
    stamp_workshop_submission(data, check=check, record=product)
    pin_pending_if_late(data, current_user, check=check, record=product)
    merge_field_provenance(data, current_user, previous=product)
    resubmit_status(product, current_user, data)
    updated = await db.productdocumentation.update(where={"id": product_id}, data=data, include=INCLUDE)
    return await completeness_module.attach("product", public_encode(updated), client=db)


@router.delete("/{product_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_product(product_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.productdocumentation, product_id)
    await db.productdocumentation.delete(where={"id": product_id})
