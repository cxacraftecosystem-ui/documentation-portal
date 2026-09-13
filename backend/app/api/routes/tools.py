from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import require_record_creator, assert_can_delete, get_current_user, is_admin
from app.schemas.records import ToolArtisanAssign, ToolCreate, ToolUpdate
from app.services.access import effective_tier_for_record, guard_record_edit
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

router = APIRouter(prefix="/tools", tags=["tools"])

# What a tool carries on the wire. Reads load these in one parallel wave (see services/records.py
# for why — this list is the longest in the app, and it is why /tools was the slowest endpoint);
# writes still pass the derived ``INCLUDE`` to Prisma, so the two can never describe different tools.
RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "toolId", many=True),
    Relation("createdBy", "user", "createdById"),
    Relation("artisanLinks", "toolartisan", "toolId", many=True, include={"artisan": True}),
)
INCLUDE = include_of(RELATIONS)

# TOOLDOCUMENTATION'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null``
# through for on this model, so emptying a box on the tool form actually empties the column instead
# of answering 200 and keeping the old value.
#
# PER-MODEL AND NOT GLOBAL, for the reason ``clean_data``'s ``clearable`` docstring gives: the global
# set cannot know which table a payload is bound for. Derived from ``model ToolDocumentation`` in
# prisma/schema.prisma, intersected with what ``ToolUpdate`` actually accepts. It OVERLAPS the
# product list without being it — a tool has ``height``/``width``/``thickness``/``weight``/``radius``
# beside the documented trio, and it has no ``size`` or ``costOfMaking`` — so the two must not be
# shared.
#
# ``height`` AND ``heightInches`` ARE BOTH HERE AND THEY ARE TWO DIFFERENT COLUMNS. ``heightInches``
# landed with migration 20260913120100 and is the only one of the two that records its unit; the bare
# ``height`` is kept because rows already hold values in it and nothing in the database can say what
# unit those are in. Both have to be clearable or a researcher who filled the wrong one of the pair
# could never empty it again.
#
# Only valid because ``update_tool`` dumps with ``exclude_unset=True``; see the note at that call.
#
# DELIBERATELY ABSENT: ``craftName``/``place``/``artisanName``/``toolkitName`` (NOT NULL), the enums
# ``maker``/``traditionType`` and ``status``/``recordedAt``/``recordedTimezone`` (NOT NULL with
# defaults), ``artisanId``/``craftId``/``workshopId``/``locationId`` (already global), and the
# measurement trio ``measurementImageId``/``measurementAnalysis``/``measurementAnalysisStatus``,
# which ``services/media_queue`` owns and ``records.PROVENANCE_SKIP_FIELDS`` already classes as
# system-managed. ``extraMetadata`` is left out because naming it would be inert:
# ``merge_field_provenance`` rebuilds and reassigns that column further down this route.
# ``clientKey`` is on no update schema at all — see the products route for why.
_CLEARABLE_COLUMNS = (
    "localName",
    "englishName",
    "processUsedIn",
    "material",
    "yearsInUse",
    "height",
    "width",
    "lengthInches",
    "breadthInches",
    "heightInches",
    "thickness",
    "weight",
    "radius",
    "replacementCost",
    "suggestionsForToolImprovement",
    "remarks",
)


async def _assigned_artisans(tool_id: str) -> list[dict[str, Any]]:
    """All artisans a tool is assigned to (the many-to-many links), oldest first."""
    links = await db.toolartisan.find_many(
        where={"toolId": tool_id},
        include={"artisan": True},
        order={"createdAt": "asc"},
    )
    return public_encode([link.artisan for link in links if link.artisan])


@router.get("")
async def list_tools(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    place: str | None = None,
    maker: str | None = None,
    traditionType: str | None = None,
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
    # Visibility is AND-composed so the search OR (assigned below) can never overwrite it.
    vis = await viewable_where(current_user)
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [
            {"toolkitName": contains(search)},
            {"localName": contains(search)},
            {"englishName": contains(search)},
            {"craftName": contains(search)},
            {"artisanName": contains(search)},
            {"place": contains(search)},
            {"processUsedIn": contains(search)},
            {"material": contains(search)},
            {"remarks": contains(search)},
        ]
    if craftId:
        where["craftId"] = craftId
    if artisanId:
        where["artisanId"] = artisanId
    if workshopId:
        where["workshopId"] = workshopId
    if place:
        where["place"] = contains(place)
    if maker:
        where["maker"] = maker
    if traditionType:
        where["traditionType"] = traditionType
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await count_and_page(
        db.tooldocumentation,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_tool(
    payload: ToolCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    # ── THE IDEMPOTENT REPLAY, ABOVE EVERY WRITE AND EVERY GATE IN THIS ROUTE ────────────────────
    #
    # See ``products.create_product`` for the argument in full: a create whose answer was lost is
    # sent again by the queue, and without this branch the second landing writes a second tool.
    # Above the gates, never below — on a replay the row already exists, so re-asking can only turn a
    # create that SUCCEEDED into a 403, and it keeps ``attach_location`` from minting a second
    # ``Location`` row per replay.
    replayed = await client_key_replay(
        db.tooldocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        return public_encode(replayed)
    data = decimal_to_string(clean_data(payload.model_dump()))
    data = await attach_location(data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.tooldocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by is_client_key_violation
        # The race the pre-read cannot close: two drains of the same queue in flight at once, each
        # finding no row and each planning an INSERT. Only the index can settle it. ``None`` means
        # re-raise, and this does. A tool has no child writes, so there is nothing here to repair.
        raced = await client_key_replay_after_violation(
            db.tooldocumentation, payload.clientKey, exc, user_id=current_user.id, include=INCLUDE
        )
        if raced is None:
            raise
        return public_encode(raced)
    return public_encode(created)


@router.get("/{tool_id}")
async def get_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    await hydrate_relations([tool], RELATIONS)
    return public_encode(tool)


@router.patch("/{tool_id}")
async def update_tool(
    tool_id: str,
    payload: ToolUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    # The precondition is a QUESTION, not a column — popped before anything reads ``data``, and
    # checked above ``guard_record_edit`` because that call ends in a COMMITTED ``RecordRevision``
    # row and this backend has no transaction to roll one back with.
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(tool, expected_updated_at)
    data = await attach_location(data)
    # Re-check workshop assignment + window if this edit moves the tool into/between workshops, so the
    # create-time guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != tool.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(tool, current_user, data, "tool")
    await apply_status_policy_update(current_user, tool, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=tool)
    pin_pending_if_late(data, current_user, check=check, record=tool)
    merge_field_provenance(data, current_user, previous=tool)
    resubmit_status(tool, current_user, data)
    updated = await db.tooldocumentation.update(where={"id": tool_id}, data=data, include=INCLUDE)
    return public_encode(updated)


@router.delete("/{tool_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.tooldocumentation, tool_id)
    await db.tooldocumentation.delete(where={"id": tool_id})


@router.get("/{tool_id}/artisans")
async def list_tool_artisans(tool_id: str, current_user: Any = Depends(get_current_user)) -> list[dict[str, Any]]:
    await require_record(db.tooldocumentation, tool_id)
    return await _assigned_artisans(tool_id)


@router.post("/{tool_id}/artisans")
async def assign_tool_artisans(
    tool_id: str,
    payload: ToolArtisanAssign,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """Assign the tool to the given artisans (idempotent: existing links are kept, new ones added).

    Permission: an admin, the tool's owner, or a collaborator holding an EDIT-tier grant on the
    tool may assign it to any artisan; anyone else may only assign it to artisans THEY created.
    Validation happens for the WHOLE batch before any link is written, so a rejected request never
    leaves partial state behind."""
    tool = await require_record(db.tooldocumentation, tool_id)
    may_assign_any = await _may_manage_tool_links(tool, tool_id, current_user)
    existing = await db.toolartisan.find_many(where={"toolId": tool_id})
    have = {link.artisanId for link in existing}
    # Every artisan being added is fetched in ONE query and every link written in ONE insert. Asking
    # per artisan cost two cross-region round trips each, so assigning a tool to a workshop's worth
    # of makers took longer than recording the tool did.
    wanted = [aid for aid in dict.fromkeys(payload.artisanIds) if aid and aid not in have]
    if not wanted:
        return await _assigned_artisans(tool_id)
    artisans = await db.artisan.find_many(where={"id": {"in": wanted}})
    by_id = {a.id: a for a in artisans}
    for artisan_id in wanted:
        artisan = by_id.get(artisan_id)
        if artisan is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        if not may_assign_any and getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, an EDIT-grant collaborator, or an admin can "
                "assign this tool to artisans created by someone else; you may only assign "
                "it to your own artisans.",
            )
    await db.toolartisan.create_many(
        data=[{"toolId": tool_id, "artisanId": aid} for aid in wanted]
    )
    return await _assigned_artisans(tool_id)


@router.delete("/{tool_id}/artisans/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def unassign_tool_artisan(
    tool_id: str,
    artisan_id: str,
    current_user: Any = Depends(get_current_user),
) -> None:
    """Remove a tool-artisan link. Whoever could have created the link can remove it: the tool's
    owner, an EDIT-grant collaborator, an admin, or the artisan's own creator (so a mistaken
    self-service link is reversible by the person who made it)."""
    tool = await require_record(db.tooldocumentation, tool_id)
    if not await _may_manage_tool_links(tool, tool_id, current_user):
        artisan = await db.artisan.find_unique(where={"id": artisan_id})
        if not artisan or getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, the artisan's creator, an EDIT-grant collaborator, "
                "or an admin can unassign artisans from this tool.",
            )
    # One statement, and still a no-op when the link is already gone — reading the row back first
    # only bought us its id, at the price of another cross-region round trip.
    await db.toolartisan.delete_many(where={"toolId": tool_id, "artisanId": artisan_id})


async def _may_manage_tool_links(tool: Any, tool_id: str, current_user: Any) -> bool:
    """Admin, tool owner, or an EDIT-tier collaborator — the same people who may edit the tool's
    populated fields (guard_record_edit) may manage its artisan links."""
    if is_admin(current_user) or getattr(tool, "createdById", None) == current_user.id:
        return True
    owner_id = getattr(tool, "createdById", None)
    if not owner_id:
        return False
    tier = await effective_tier_for_record(current_user, owner_id, "tool", tool_id)
    return tier == "EDIT"
