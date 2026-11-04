from datetime import datetime
from typing import Any, NamedTuple

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.services.artisan_merge import created_merged_record, merged_redirects, redirect_merged_artisans
from app.core.deps import (
    require_record_creator,
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
)
from app.schemas.records import (
    TOOL_CRAFT_NAME_MAX,
    ToolArtisanAssign,
    ToolCreate,
    ToolUpdate,
)
from app.services import craft_master
from app.services.access import guard_record_edit, record_edit_privilege
from app.services.concurrency import gather_reads
from app.services.standard_values import StandardValueError, apply_tool_values, retired_tradition_codes
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

router = APIRouter(prefix="/tools", tags=["tools"])

RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "toolId", many=True),
    Relation("createdBy", "user", "createdById"),
    Relation("artisanLinks", "toolartisan", "toolId", many=True, include={"artisan": True}),
    Relation("craftLinks", "toolcraft", "toolId", many=True, include={"craft": True}),
)
INCLUDE = include_of(RELATIONS)

_LINK_RELATIONS = tuple(rel for rel in RELATIONS if rel.field in ("artisanLinks", "craftLinks"))

_CLEARABLE_COLUMNS = (
    "materialFamily",
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


_FOREIGN_ARTISAN_REFUSAL = (
    "Only the tool's owner, a professor senior to its author, an EDIT-grant collaborator, or an "
    "admin can assign this tool to artisans created by someone else; you may only assign it to "
    "your own artisans."
)


def _clean_link_ids(raw: Any) -> list[str] | None:
    if raw is None:
        return None
    return list(dict.fromkeys(str(value).strip() for value in raw if str(value).strip()))


def _pop_link_ids(data: dict[str, Any], key: str) -> list[str] | None:
    return _clean_link_ids(data.pop(key, None))


class _LinkSet(NamedTuple):
    stored_is_populated: bool = False
    changed: bool = False

    @property
    def replaces_populated(self) -> bool:
        return self.changed and self.stored_is_populated


class _ToolLinks(NamedTuple):
    crafts: _LinkSet = _LinkSet()
    artisans: _LinkSet = _LinkSet()


async def _resolve_tool_links(
    data: dict[str, Any],
    craft_ids: list[str] | None,
    artisan_ids: list[str] | None,
    current_user: Any,
    *,
    tool: Any | None,
) -> _ToolLinks:
    if craft_ids is None and artisan_ids is None:
        return _ToolLinks()

    reads: dict[str, Any] = {}
    if craft_ids:
        reads["crafts"] = db.craft.find_many(where={"id": {"in": craft_ids}})
    if artisan_ids:
        reads["artisans"] = db.artisan.find_many(where={"id": {"in": artisan_ids}})
    if tool is not None and craft_ids is not None:
        reads["storedCrafts"] = db.toolcraft.find_many(where={"toolId": tool.id})
    if tool is not None and artisan_ids is not None:
        reads["storedArtisans"] = db.toolartisan.find_many(where={"toolId": tool.id})
    fetched = dict(zip(reads, await gather_reads(*reads.values())))

    stored_crafts = {link.craftId for link in fetched.get("storedCrafts") or []}
    stored_artisans = {link.artisanId for link in fetched.get("storedArtisans") or []}
    links = _ToolLinks(
        crafts=_LinkSet(
            stored_is_populated=bool(stored_crafts),
            changed=craft_ids is not None and set(craft_ids) != stored_crafts,
        ),
        artisans=_LinkSet(
            stored_is_populated=bool(stored_artisans),
            changed=artisan_ids is not None and set(artisan_ids) != stored_artisans,
        ),
    )

    if craft_ids:
        by_id = {craft.id: craft for craft in fetched.get("crafts") or []}
        for craft_id in craft_ids:
            if craft_id not in by_id:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="Record not found"
                )
        joined = ", ".join(by_id[craft_id].name for craft_id in craft_ids)
        if len(joined) > TOOL_CRAFT_NAME_MAX:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    f"{len(craft_ids)} linked crafts name this tool's craft in {len(joined)} "
                    f"characters, and a tool record can hold {TOOL_CRAFT_NAME_MAX}. "
                    f"Link fewer crafts to this tool."
                ),
            )
        data["craftId"] = craft_ids[0]
        data["craftName"] = joined

    if artisan_ids:
        by_id = {artisan.id: artisan for artisan in fetched.get("artisans") or []}
        for artisan_id in artisan_ids:
            if artisan_id not in by_id:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="Record not found"
                )
        if tool is not None:
            foreign_additions = [
                artisan_id
                for artisan_id in artisan_ids
                if artisan_id not in stored_artisans
                and getattr(by_id[artisan_id], "createdById", None) != current_user.id
            ]
            if foreign_additions and not await _may_manage_tool_links(tool, current_user):
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN, detail=_FOREIGN_ARTISAN_REFUSAL
                )
        data["artisanId"] = artisan_ids[0]

    return links


async def _write_link_rows(
    tool_id: str, craft_ids: list[str] | None, artisan_ids: list[str] | None
) -> bool:
    wrote = False
    if craft_ids:
        await db.toolcraft.create_many(
            data=[{"toolId": tool_id, "craftId": cid} for cid in craft_ids]
        )
        wrote = True
    if artisan_ids:
        await db.toolartisan.create_many(
            data=[{"toolId": tool_id, "artisanId": aid} for aid in artisan_ids]
        )
        wrote = True
    return wrote


async def _replace_craft_links(tool_id: str, craft_ids: list[str]) -> None:
    await db.toolcraft.delete_many(where={"toolId": tool_id})
    if craft_ids:
        await db.toolcraft.create_many(
            data=[{"toolId": tool_id, "craftId": cid} for cid in craft_ids]
        )


async def _replace_artisan_links(tool_id: str, artisan_ids: list[str]) -> None:
    await db.toolartisan.delete_many(where={"toolId": tool_id})
    if artisan_ids:
        await db.toolartisan.create_many(
            data=[{"toolId": tool_id, "artisanId": aid} for aid in artisan_ids]
        )


async def _repair_empty_links(
    tool_id: str, craft_ids: list[str] | None, artisan_ids: list[str] | None
) -> bool:
    wrote = False
    if craft_ids and not await db.toolcraft.count(where={"toolId": tool_id}):
        live = {craft.id for craft in await db.craft.find_many(where={"id": {"in": craft_ids}})}
        wanted = [cid for cid in craft_ids if cid in live]
        if wanted:
            await db.toolcraft.create_many(
                data=[{"toolId": tool_id, "craftId": cid} for cid in wanted]
            )
            wrote = True
    if artisan_ids and not await db.toolartisan.count(where={"toolId": tool_id}):
        live = {a.id for a in await db.artisan.find_many(where={"id": {"in": artisan_ids}})}
        wanted = [aid for aid in artisan_ids if aid in live]
        if wanted:
            await db.toolartisan.create_many(
                data=[{"toolId": tool_id, "artisanId": aid} for aid in wanted]
            )
            wrote = True
    return wrote


def _order_craft_links(tool: dict[str, Any]) -> None:
    names = [part.strip() for part in (tool.get("craftName") or "").split(",")]
    at = {name: index for index, name in enumerate(names) if name}
    links = tool.get("craftLinks") or []
    links.sort(
        key=lambda link: (
            at.get(((link.get("craft") or {}).get("name") or "").strip(), len(at)),
            link.get("createdAt") or "",
            link.get("id") or "",
        )
    )


def _order_artisan_links(tool: dict[str, Any]) -> None:
    first = tool.get("artisanId")
    links = tool.get("artisanLinks") or []
    links.sort(
        key=lambda link: (
            0 if first is not None and link.get("artisanId") == first else 1,
            link.get("createdAt") or "",
            link.get("id") or "",
        )
    )


def _tool_payload(tool: Any) -> dict[str, Any]:
    encoded = public_encode(tool)
    _order_craft_links(encoded)
    _order_artisan_links(encoded)
    return encoded


def _tool_page(tools: Any) -> list[dict[str, Any]]:
    encoded = public_encode(tools)
    for tool in encoded:
        _order_craft_links(tool)
        _order_artisan_links(tool)
    return encoded


async def _assigned_artisans(tool_id: str) -> list[dict[str, Any]]:
    links = await db.toolartisan.find_many(
        where={"toolId": tool_id},
        include={"artisan": True},
        order=[{"createdAt": "asc"}, {"id": "asc"}],
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
        same = [traditionType] + [
            old for old, new in retired_tradition_codes().items() if new == traditionType
        ]
        where["traditionType"] = same[0] if len(same) == 1 else {"in": same}
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    completeness_module.add_filter(where, "tool", completeness)
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await count_and_page(
        db.tooldocumentation,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    encoded = await completeness_module.attach("tool", _tool_page(items), client=db)
    return page_payload(encoded, total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_tool(
    payload: ToolCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    replayed = await client_key_replay(
        db.tooldocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        if await _repair_empty_links(
            replayed.id, _clean_link_ids(payload.craftIds), _clean_link_ids(payload.artisanIds)
        ):
            await hydrate_relations([replayed], _LINK_RELATIONS)
        return await completeness_module.attach("tool", _tool_payload(replayed), client=db)
    await redirect_merged_artisans(payload)
    data = decimal_to_string(clean_data(payload.model_dump()))
    _standardise(apply_tool_values, data)
    craft_ids = _pop_link_ids(data, "craftIds")
    craft_ids = await craft_master.resolve_tool_crafts(db, data, craft_ids)
    artisan_ids = _pop_link_ids(data, "artisanIds")
    await _resolve_tool_links(data, craft_ids, artisan_ids, current_user, tool=None)
    data = await attach_location(data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.tooldocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001
        raced = await client_key_replay_after_violation(
            db.tooldocumentation, payload.clientKey, exc, user_id=current_user.id, include=INCLUDE
        )
        if raced is None:
            raise
        if await _repair_empty_links(raced.id, craft_ids, artisan_ids):
            await hydrate_relations([raced], _LINK_RELATIONS)
        return await completeness_module.attach("tool", _tool_payload(raced), client=db)
    if await _write_link_rows(created.id, craft_ids, artisan_ids):
        await hydrate_relations([created], _LINK_RELATIONS)
    return await completeness_module.attach("tool", _tool_payload(created), client=db)


@router.get("/{tool_id}")
async def get_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    await hydrate_relations([tool], RELATIONS)
    return await completeness_module.attach("tool", _tool_payload(tool), client=db)


@router.patch("/{tool_id}")
async def update_tool(
    tool_id: str,
    payload: ToolUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    await redirect_merged_artisans(payload)
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    _standardise(apply_tool_values, data, tool)
    expected_updated_at = take_expected_updated_at(data)
    assert_expected_updated_at(tool, expected_updated_at)
    craft_ids = _pop_link_ids(data, "craftIds")
    craft_ids = await craft_master.resolve_tool_crafts(db, data, craft_ids, tool=tool)
    artisan_ids = _pop_link_ids(data, "artisanIds")
    links = await _resolve_tool_links(data, craft_ids, artisan_ids, current_user, tool=tool)
    data = await attach_location(data)
    check = None
    if "workshopId" in data and data.get("workshopId") != tool.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    privileged = await record_edit_privilege(tool, current_user, "tool")
    if not privileged:
        assert_can_contribute_relation(
            tool, current_user, links.crafts.replaces_populated, "craftIds"
        )
        assert_can_contribute_relation(
            tool, current_user, links.artisans.replaces_populated, "artisanIds"
        )
    await guard_record_edit(tool, current_user, data, "tool", privileged=privileged)
    await apply_status_policy_update(current_user, tool, data)
    stamp_workshop_submission(data, check=check, record=tool)
    pin_pending_if_late(data, current_user, check=check, record=tool)
    merge_field_provenance(data, current_user, previous=tool)
    resubmit_status(tool, current_user, data)
    updated = await db.tooldocumentation.update(where={"id": tool_id}, data=data, include=INCLUDE)
    if links.crafts.changed:
        await _replace_craft_links(tool_id, craft_ids)
    if links.artisans.changed:
        await _replace_artisan_links(tool_id, artisan_ids)
    if links.crafts.changed or links.artisans.changed:
        await hydrate_relations([updated], _LINK_RELATIONS)
    return await completeness_module.attach("tool", _tool_payload(updated), client=db)


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
    tool = await require_record(db.tooldocumentation, tool_id)
    may_assign_any = await _may_manage_tool_links(tool, current_user)
    existing = await db.toolartisan.find_many(where={"toolId": tool_id})
    have = {link.artisanId for link in existing}
    redirects = await merged_redirects()
    via: dict[str, str] = {}
    for aid in payload.artisanIds:
        if aid in redirects:
            via.setdefault(redirects[aid], aid)
    asked = [redirects.get(aid, aid) for aid in payload.artisanIds]
    wanted = [aid for aid in dict.fromkeys(asked) if aid and aid not in have]
    if not wanted:
        return await _assigned_artisans(tool_id)
    artisans = await db.artisan.find_many(where={"id": {"in": wanted}})
    by_id = {a.id: a for a in artisans}
    for artisan_id in wanted:
        artisan = by_id.get(artisan_id)
        if artisan is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        if not may_assign_any and getattr(artisan, "createdById", None) != current_user.id:
            if artisan_id in via and await created_merged_record(via[artisan_id], current_user.id):
                continue
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail=_FOREIGN_ARTISAN_REFUSAL
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
    tool = await require_record(db.tooldocumentation, tool_id)
    if not await _may_manage_tool_links(tool, current_user):
        artisan = await db.artisan.find_unique(where={"id": artisan_id})
        if not artisan or getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, the artisan's creator, a professor senior to the "
                "tool's author, an EDIT-grant collaborator, or an admin can unassign artisans from "
                "this tool.",
            )
    await db.toolartisan.delete_many(where={"toolId": tool_id, "artisanId": artisan_id})


async def _may_manage_tool_links(tool: Any, current_user: Any) -> bool:
    return await record_edit_privilege(tool, current_user, "tool")


def _standardise(apply: Any, data: dict[str, Any], stored: Any = None) -> None:
    try:
        apply(data, stored)
    except StandardValueError as exc:
        raise HTTPException(status_code=422, detail=exc.message) from exc
