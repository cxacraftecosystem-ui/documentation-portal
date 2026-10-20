"""The coverage map's routes, on the ``/map`` router beside ``map_points``.

``GET /map/coverage`` folds every record the caller may read into LGD units, with the gaps (shared
crafts nobody has documented, districts and states with no workshop). ``GET /map/coverage/units/...``
pages the records behind one unit. The unmatched report and the alias routes are an administrator's:
they turn a free-text place spelling the register cannot place into an LGD code, once, for every row
written that way. Each alias write is a ``RecordRevision`` (``recordType`` ``geoalias``).

Reading coverage is open to any signed-in account, the same rule as ``/map``. See
``services/coverage`` for how the fold keeps the visibility rule in ``record_filters``.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import db
from app.core.deps import get_current_user, is_admin, require_content_admin
from app.services import coverage, lgd
from app.services.access import record_revision

router = APIRouter(prefix="/map", tags=["map"])


def _filters(
    level: str | None,
    craftCode: list[str] | None,
    scheme: list[str] | None,
    fy: list[str] | None,
    stateLgd: int | None,
) -> coverage.Filters:
    if stateLgd is not None and lgd.state_name(stateLgd) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Unknown state code."
        )
    return coverage.Filters(
        level=coverage.resolve_level(level),
        craft_codes=tuple(coverage.resolve_craft_codes(craftCode)),
        schemes=tuple(coverage.resolve_schemes(scheme)),
        fys=tuple(coverage.resolve_fys(fy)),
        state_lgd=stateLgd,
    )


@router.get("/coverage")
async def map_coverage(
    current_user: Any = Depends(get_current_user),
    level: str | None = None,
    craftCode: list[str] | None = Query(None),
    scheme: list[str] | None = Query(None),
    fy: list[str] | None = Query(None),
    stateLgd: int | None = None,
) -> dict[str, Any]:
    filters = _filters(level, craftCode, scheme, fy, stateLgd)
    fold = await coverage.fold_for(await coverage.scope_wheres(current_user))
    return coverage.coverage_payload(fold, filters, admin=is_admin(current_user))


@router.get("/coverage/units/{state_lgd}/{district_lgd}/records")
async def coverage_unit_records(
    state_lgd: int,
    district_lgd: int,
    current_user: Any = Depends(get_current_user),
    level: str | None = None,
    craftCode: list[str] | None = Query(None),
    scheme: list[str] | None = Query(None),
    fy: list[str] | None = Query(None),
    bucket: str | None = None,
    cursor: str | None = None,
) -> dict[str, Any]:
    if lgd.state_name(state_lgd) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown state code.")
    if district_lgd and lgd.district_state(district_lgd) != state_lgd:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown district code.")
    filters = _filters(level, craftCode, scheme, fy, None)
    fold = await coverage.fold_for(await coverage.scope_wheres(current_user))
    return coverage.unit_records(
        fold, filters, state_lgd, district_lgd, bucket=bucket, cursor=cursor
    )


@router.get("/coverage/unmatched")
async def coverage_unmatched(current_user: Any = Depends(require_content_admin)) -> dict[str, Any]:
    fold = await coverage.fold_for(await coverage.scope_wheres(current_user))
    items = coverage.unmatched_items(fold.rows)
    return {"items": items, "total": len(items)}


class AliasIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rawState: str | None = Field(default=None, max_length=200)
    rawDistrict: str | None = Field(default=None, max_length=200)
    stateLgd: int
    districtLgd: int | None = None


def _alias_out(row: Any) -> dict[str, Any]:
    return {
        "id": row.id,
        "rawStateKey": row.rawStateKey,
        "rawDistrictKey": row.rawDistrictKey,
        "stateLgd": row.stateLgd,
        "districtLgd": row.districtLgd,
        "state": lgd.state_name(row.stateLgd),
        "district": lgd.district_name(row.districtLgd),
        "createdAt": getattr(row, "createdAt", None),
    }


@router.post("/coverage/aliases", status_code=status.HTTP_201_CREATED)
async def create_alias(payload: AliasIn, current_user: Any = Depends(require_content_admin)) -> dict[str, Any]:
    if lgd.state_name(payload.stateLgd) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Unknown state code."
        )
    if payload.districtLgd is not None and lgd.district_state(payload.districtLgd) != payload.stateLgd:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="That district is not in the chosen state.",
        )
    state_key, district_key = lgd.alias_key(payload.rawState, payload.rawDistrict)
    if not state_key and not district_key:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Give the spelling to map."
        )
    existing = await db.geoalias.find_unique(
        where={"rawStateKey_rawDistrictKey": {"rawStateKey": state_key, "rawDistrictKey": district_key}}
    )
    if existing is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That spelling is already mapped.")
    row = await db.geoalias.create(
        data={
            "rawStateKey": state_key,
            "rawDistrictKey": district_key,
            "stateLgd": payload.stateLgd,
            "districtLgd": payload.districtLgd,
            "createdById": getattr(current_user, "id", None),
        }
    )
    # THE AUDIT LEDGER: an alias moves every row written this way onto a district, so who mapped
    # what to what is kept like any other edit.
    await record_revision(
        SimpleNamespace(id=row.id, rawStateKey=None, rawDistrictKey=None, stateLgd=None, districtLgd=None),
        current_user,
        {
            "rawStateKey": state_key,
            "rawDistrictKey": district_key,
            "stateLgd": payload.stateLgd,
            "districtLgd": payload.districtLgd,
        },
        "geoalias",
    )
    coverage.clear_cache()
    return _alias_out(row)


@router.delete("/coverage/aliases/{alias_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_alias(alias_id: str, current_user: Any = Depends(require_content_admin)) -> Response:
    row = await db.geoalias.find_unique(where={"id": alias_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Alias not found.")
    await db.geoalias.delete(where={"id": alias_id})
    await record_revision(
        row,
        current_user,
        {"rawStateKey": None, "rawDistrictKey": None, "stateLgd": None, "districtLgd": None},
        "geoalias",
    )
    coverage.clear_cache()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/coverage/aliases")
async def list_aliases(_: Any = Depends(require_content_admin)) -> dict[str, Any]:
    rows = await db.geoalias.find_many(order={"createdAt": "desc"})
    return {"items": [_alias_out(row) for row in rows], "total": len(rows)}
