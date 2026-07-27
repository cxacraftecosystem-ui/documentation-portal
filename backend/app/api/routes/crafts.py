from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    get_current_user,
    require_craft_manager,
)
from app.services.access import guard_record_edit
from app.schemas.records import CraftCreate, CraftUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    Relation,
    apply_status_policy_update,
    clean_data,
    contains,
    count_and_page,
    hydrate_relations,
    include_of,
    merge_field_provenance,
    require_record,
    resubmit_status,
)
from app.services.records import public_encode
from app.services.workshop_access import (
    enforce_workshop_submission,
    link_workshop_craft,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/crafts", tags=["crafts"])

# One relation, so the wave saves nothing here — but the count no longer waits for the page, which
# on this deployment is a whole cross-region round trip off the craft dropdown every form opens with.
RELATIONS = (Relation("workshop", "workshop", "workshopId"),)
INCLUDE = include_of(RELATIONS)


@router.get("")
async def list_crafts(
    _: Any = Depends(get_current_user),
    search: str | None = None,
    place: str | None = None,
    workshopId: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"category": contains(search)},
            {"description": contains(search)},
        ]
    if place:
        where["place"] = contains(place)
    if workshopId:
        # Either reading counts: the craft's own workshopId column, or the WorkshopCraft join
        # (relation named ``workshops``) that carried the link before the column existed. Nested under
        # AND so it can never overwrite the free-text search OR above.
        where["AND"] = [{"OR": [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]}]
    # ORDERING IS DELIBERATE — alphabetical by name, NOT newest-first. Do not flip it to
    # ``createdAt desc`` for consistency with the other record lists: every consumer of this endpoint
    # is a PICKER (the craft dropdown in the artisan/product/tool/workshop forms, the media entry
    # picker, the funnel filters, Android's craft dropdown), and a picker is scanned by name — a
    # recency order reshuffles it on every new craft. The two views that do want recency already
    # re-sort client-side (``sortRecent`` on the web /data and activity pages,
    # ``sortedByDescending { createdAt }`` on Android), so they are unaffected either way.
    # ``name`` is @unique (and indexed), so the order is total: pagination cannot repeat or skip.
    total, items = await count_and_page(
        db.craft, where=where, skip=skip, take=page_size, order={"name": "asc"}, relations=RELATIONS
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_craft(payload: CraftCreate, current_user: Any = Depends(require_craft_manager)) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    # Workshop entries: enforce assignment, then flag a late submission for admin approval. Craft has
    # no status column, so pinning is a no-op here; wired for parity with the other record types.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.craft.create(data=data, include=INCLUDE)
    except UniqueViolationError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Craft name already exists") from exc
    # The explicit column ADDS to the WorkshopCraft join every existing query still reads through.
    await link_workshop_craft(created.workshopId, created.id)
    return public_encode(created)


@router.get("/{craft_id}")
async def get_craft(craft_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    craft = await require_record(db.craft, craft_id)
    await hydrate_relations([craft], RELATIONS)
    return public_encode(craft)


@router.patch("/{craft_id}")
async def update_craft(
    craft_id: str,
    payload: CraftUpdate,
    # A craft is the shared taxonomy every other record points at, so editing one is not a
    # contribution to somebody's entry — it renames a category under everyone. Professor and above,
    # the same floor as creating one; ``guard_record_edit`` below still audits and still decides
    # whether THIS professor may overwrite a field another professor populated.
    current_user: Any = Depends(require_craft_manager),
) -> dict[str, Any]:
    craft = await require_record(db.craft, craft_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != craft.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(craft, current_user, data, "craft")
    # Craft has no status column, so this is a no-op here; wired for parity with the other 6 PATCHes.
    await apply_status_policy_update(current_user, craft, data)
    stamp_workshop_submission(data, check=check, record=craft)
    pin_pending_if_late(data, current_user, check=check, record=craft)
    merge_field_provenance(data, current_user, previous=craft)
    resubmit_status(craft, current_user, data)
    updated = await db.craft.update(where={"id": craft_id}, data=data, include=INCLUDE)
    await link_workshop_craft(updated.workshopId, updated.id)
    return public_encode(updated)


@router.delete("/{craft_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_craft(craft_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.craft, craft_id)
    await db.craft.delete(where={"id": craft_id})
