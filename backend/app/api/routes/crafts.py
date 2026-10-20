from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    get_current_user,
    require_craft_manager,
)
from app.services import craft_master, cross_portal_codes
from app.services.access import guard_record_edit
from app.api.routes.craft_categories import is_content_admin, require_content_admin
from app.schemas.records import CraftAdopt, CraftCreate, CraftMerge, CraftUpdate
from app.services.pagination import page_payload
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
    # WHOSE CRAFTS — same parameter, same reason, as the record lists: My Activity asks the API for
    # one person's entries instead of sifting page one of the whole taxonomy. Note that
    # ``Craft.createdById`` is NULLABLE (the seeded taxonomy has no author), so this narrows to the
    # crafts somebody actually added, which is exactly what the activity page wants.
    createdBy: str | None = None,
    # THE MASTER LIST'S FILTERS (2026-10-20, docs/CRAFTS.md). ``state`` matches a state of origin or
    # of practice; ``status`` defaults to ACTIVE, so a LEGACY or MERGED craft is listed only when asked.
    categoryId: str | None = None,
    subCategoryId: str | None = None,
    giStatus: str | None = None,
    state: str | None = None,
    status_filter: str = Query(craft_master.ACTIVE, alias="status"),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=500),
) -> dict[str, Any]:
    # The cap is 500 here, not the shared 100: the admin table reads the whole list in one page.
    page, page_size = max(1, page), max(1, min(pageSize, 500))
    skip = (page - 1) * page_size
    where: dict[str, Any] = {}
    and_clauses: list[dict[str, Any]] = []
    if status_filter.upper() in craft_master.STATUSES:
        where["status"] = status_filter.upper()
    if categoryId:
        where["subCategory"] = {"is": {"categoryId": categoryId}}
    if subCategoryId:
        where["subCategoryId"] = subCategoryId
    if giStatus and giStatus.upper() in craft_master.GI_STATUSES:
        where["giStatus"] = giStatus.upper()
    if state:
        and_clauses.append({"OR": [{"stateOfOrigin": {"has": state}}, {"stateOfPractice": {"has": state}}]})
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"category": contains(search)},
            {"description": contains(search)},
        ]
    if place:
        where["place"] = contains(place)
    if createdBy:
        where["createdById"] = createdBy
    if workshopId:
        # Either reading counts: the craft's own workshopId column, or the WorkshopCraft join
        # (relation named ``workshops``) that carried the link before the column existed. Nested under
        # AND so it can never overwrite the free-text search OR above.
        and_clauses.append({"OR": [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]})
    if and_clauses:
        where["AND"] = and_clauses
    # ORDERING IS DELIBERATE — alphabetical by name, NOT newest-first. Do not flip it to
    # ``createdAt desc`` for consistency with the other record lists: every consumer of this endpoint
    # is a PICKER (the craft dropdown in the artisan/product/tool/workshop forms, the media entry
    # picker, the funnel filters, Android's craft dropdown), and a picker is scanned by name — a
    # recency order reshuffles it on every new craft. The two views that do want recency already
    # re-sort client-side (``sortRecent`` on the web /data and activity pages,
    # ``sortedByDescending { createdAt }`` on Android), so they are unaffected either way.
    # ``name`` is unique only within a sub-category since the master list, so ``id`` breaks ties and
    # the order stays total: pagination cannot repeat or skip.
    total, items = await count_and_page(
        db.craft, where=where, skip=skip, take=page_size, order=[{"name": "asc"}, {"id": "asc"}], relations=RELATIONS
    )
    return page_payload(await _with_taxonomy(public_encode(items)), total, page, page_size)


async def _with_taxonomy(rows: Any) -> Any:
    """Add each craft's ``categoryId`` (through its sub-category) and ``referenceCount``."""
    many = isinstance(rows, list)
    items = rows if many else [rows]
    subs = {s.id: s.categoryId for s in await db.craftsubcategory.find_many()}
    counts = await craft_master.reference_counts(db, [row["id"] for row in items])
    for row in items:
        row["categoryId"] = subs.get(row.get("subCategoryId") or "")
        row["referenceCount"] = counts.get(row["id"], 0)
    return items if many else items[0]


@router.get("/catalogue")
async def craft_catalogue(
    request: Request,
    response: Response,
    includeInactive: bool = False,
    current_user: Any = Depends(get_current_user),
) -> Any:
    """The one list every craft picker reads (docs/CRAFTS.md). ``ETag``; a matching ``If-None-Match`` is 304.

    Declared before ``/{craft_id}`` so the path is not read as a craft id.
    """
    if includeInactive and not is_content_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Content admin access required")
    catalogue = await craft_master.build_catalogue(db, include_inactive=includeInactive)
    etag = f'"{catalogue["version"]}"'
    headers = {"ETag": etag, "Cache-Control": "private, no-cache"}
    if etag in [tag.strip() for tag in (request.headers.get("if-none-match") or "").split(",")]:
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)
    response.headers.update(headers)
    return catalogue


async def _sub_category(sub_category_id: str) -> Any:
    sub = await db.craftsubcategory.find_unique(where={"id": sub_category_id}, include={"category": True})
    if sub is None:
        raise HTTPException(
            status_code=422, detail={"code": "SUB_CATEGORY_NOT_FOUND", "message": "Pick a sub-category."}
        )
    return sub


async def _assert_name_free(name: str, sub_category_id: str, *, except_id: str | None = None) -> None:
    where: dict[str, Any] = {"subCategoryId": sub_category_id, "name": {"equals": name, "mode": "insensitive"}}
    if except_id:
        where["NOT"] = {"id": except_id}
    if await db.craft.find_first(where=where) is not None:
        raise craft_master.craft_error("CRAFT_EXISTS", status.HTTP_409_CONFLICT)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_craft(payload: CraftCreate, current_user: Any = Depends(require_craft_manager)) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    # THE MASTER LIST (2026-10-20): a new craft joins a sub-category. The server owns ``slug``,
    # ``category`` (the compatibility copy of the category name) and ``status``; a client's
    # ``category`` is ignored.
    sub = await _sub_category(data["subCategoryId"])
    await _assert_name_free(data["name"], sub.id)
    data.pop("category", None)
    data["stateOfOrigin"] = craft_master.clean_states(data.get("stateOfOrigin"))
    data["stateOfPractice"] = craft_master.clean_states(data.get("stateOfPractice"))
    data["category"] = sub.category.name
    data["slug"] = await craft_master.unique_slug(db.craft, data["name"])
    data["status"] = craft_master.ACTIVE
    # Workshop entries: enforce assignment, then flag a late submission for admin approval. Craft has
    # no status column, so pinning is a no-op here; wired for parity with the other record types.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    pin_pending_if_late(data, current_user, check=check)
    # The shared craft code (C26), set AFTER the provenance stamp: it is derived from the name, not
    # a value the person typed, so it must not be recorded as theirs.
    data["craftCode"] = await cross_portal_codes.code_for_new_craft(db, data.get("name"))
    try:
        created = await db.craft.create(data=data, include=INCLUDE)
    except UniqueViolationError as exc:
        # The (sub-category, name) index answering a race the check above lost.
        raise craft_master.craft_error("CRAFT_EXISTS", status.HTTP_409_CONFLICT) from exc
    # The explicit column ADDS to the WorkshopCraft join every existing query still reads through.
    await link_workshop_craft(created.workshopId, created.id)
    return await _with_taxonomy(public_encode(created))


@router.get("/{craft_id}")
async def get_craft(craft_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    craft = await require_record(db.craft, craft_id)
    await hydrate_relations([craft], RELATIONS)
    return await _with_taxonomy(public_encode(craft))


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
    await _master_list_update(craft, data)
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
    # A rename that lands on a known spelling takes that code. A rename that does not keeps whatever
    # code the craft had: an administrator's mapping is not undone by a typo fix.
    if "name" in data and data["name"] != craft.name:
        renamed_code = await cross_portal_codes.code_for_new_craft(db, data["name"])
        if renamed_code is not None:
            data["craftCode"] = renamed_code
    try:
        updated = await db.craft.update(where={"id": craft_id}, data=data, include=INCLUDE)
    except UniqueViolationError as exc:
        raise craft_master.craft_error("CRAFT_EXISTS", status.HTTP_409_CONFLICT) from exc
    await link_workshop_craft(updated.workshopId, updated.id)
    if updated.name != craft.name:
        await craft_master.remember_spelling(db, updated, craft.name, user_id=current_user.id)
    return await _with_taxonomy(public_encode(updated))


async def _master_list_update(craft: Any, data: dict[str, Any]) -> None:
    """The master-list half of a craft edit. Mutates ``data``.

    ``category`` from a client is ignored: it is derived from the sub-category. A name sent back
    unchanged but for the title-casing ``clean_data`` applies is no rename. A real rename keeps the
    old name as an alias, so search and the matcher still find the craft by it.
    """
    data.pop("category", None)
    if "name" in data and craft_master.normalise(data["name"]) == craft_master.normalise(craft.name):
        data.pop("name")
    sub_id = data.get("subCategoryId") or getattr(craft, "subCategoryId", None)
    if data.get("subCategoryId"):
        sub = await _sub_category(data["subCategoryId"])
        data["category"] = sub.category.name
    elif "subCategoryId" in data:
        data.pop("subCategoryId")
    if sub_id and ("name" in data or "subCategoryId" in data):
        await _assert_name_free(data.get("name", craft.name), sub_id, except_id=craft.id)
    for key in ("stateOfOrigin", "stateOfPractice"):
        if key in data:
            data[key] = craft_master.clean_states(data[key])
    if "name" in data:
        extra = data.get("extraMetadata")
        base = dict(extra) if isinstance(extra, dict) else {}
        base[craft_master.ALIASES_KEY] = craft_master.craft_aliases(craft)
        data["extraMetadata"] = craft_master.with_alias(base, craft.name)


@router.delete("/{craft_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_craft(craft_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.craft, craft_id)
    # A referenced craft is never deleted: merging keeps every record's link and the old name.
    references = await craft_master.reference_count(db, craft_id)
    if references:
        raise craft_master.craft_error("CRAFT_IN_USE", status.HTTP_409_CONFLICT, referenceCount=references)
    await db.craft.delete(where={"id": craft_id})


@router.post("/{craft_id}/merge")
async def merge_craft(
    craft_id: str, payload: CraftMerge, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    """Fold this craft into ``intoId``: every reference moves, and this craft stays as MERGED."""
    source = await require_record(db.craft, craft_id)
    if payload.intoId == craft_id:
        raise HTTPException(status_code=422, detail="A craft cannot be merged into itself.")
    target = await craft_master.assert_active_craft(db, payload.intoId)
    if craft_master.status_of(source) == craft_master.MERGED:
        raise HTTPException(status_code=409, detail={"code": "CRAFT_MERGED", "message": "This craft is already merged."})
    async with db.tx() as tx:
        moved = await craft_master.merge_craft(tx, source, target, user_id=current_user.id)
    merged = await db.craft.find_unique(where={"id": craft_id})
    return {"craft": await _with_taxonomy(public_encode(merged)), "intoId": target.id, "moved": moved}


@router.post("/{craft_id}/adopt")
async def adopt_craft(
    craft_id: str, payload: CraftAdopt, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    """A LEGACY craft joins the list, under its own name, in ``subCategoryId``."""
    craft = await require_record(db.craft, craft_id)
    if craft_master.status_of(craft) != craft_master.LEGACY:
        raise HTTPException(status_code=409, detail={"code": "CRAFT_NOT_LEGACY", "message": "Only a craft waiting for review can be added."})
    sub = await _sub_category(payload.subCategoryId)
    await _assert_name_free(craft.name, sub.id, except_id=craft.id)
    updated = await db.craft.update(
        where={"id": craft_id},
        data={"status": craft_master.ACTIVE, "subCategoryId": sub.id, "category": sub.category.name},
    )
    return await _with_taxonomy(public_encode(updated))
