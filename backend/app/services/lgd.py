from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from app.services import address, district_lineage
from app.services.cross_portal_codes import normalise
from app.services.place_atlas import resolve_place

REGISTER_FILE = Path(__file__).resolve().parents[1] / "data" / "lgd_register.json"

CANONICAL = "CANONICAL"
ALIAS = "ALIAS"
GEO_ALIAS = "GEO_ALIAS"
PLACE_ATLAS = "PLACE_ATLAS"
STATE_ONLY = "STATE_ONLY"
UNMATCHED = "UNMATCHED"
MATCHED_BY = (CANONICAL, ALIAS, GEO_ALIAS, PLACE_ATLAS, STATE_ONLY, UNMATCHED)

POLYGON_OWN = "OWN"
POLYGON_PARENT = "PARENT"
POLYGON_STATE = "STATE"

AliasTable = Mapping[tuple[str, str], tuple[int, int | None]]


@dataclass(frozen=True)
class Resolution:
    state_lgd: int | None
    district_lgd: int | None
    matched_by: str
    pending: bool = False


@dataclass(frozen=True)
class Register:
    as_of: str
    list_version: int
    state_by_name: dict[str, int]
    state_name: dict[int, str]
    district_by_key: dict[tuple[str, str], int]
    district_row: dict[int, tuple[int, str, str]]
    pending: frozenset[tuple[str, str]]

    @property
    def state_count(self) -> int:
        return len(self.state_by_name)

    @property
    def district_count(self) -> int:
        return len(self.district_by_key) + len(self.pending)


@lru_cache(maxsize=1)
def register() -> Register:
    raw = json.loads(REGISTER_FILE.read_text(encoding="utf-8"))
    state_by_name = {row["name"]: int(row["lgd"]) for row in raw["states"]}
    state_name = {code: name for name, code in state_by_name.items()}
    district_by_key: dict[tuple[str, str], int] = {}
    district_row: dict[int, tuple[int, str, str]] = {}
    for row in raw["districts"]:
        state = state_name[int(row["stateLgd"])]
        district_by_key[(state, row["name"])] = int(row["lgd"])
        district_row[int(row["lgd"])] = (int(row["stateLgd"]), state, row["name"])
    pending = frozenset((row["state"], row["district"]) for row in raw.get("pending", []))
    return Register(
        as_of=str(raw["asOf"]),
        list_version=int(raw["listVersion"]),
        state_by_name=state_by_name,
        state_name=state_name,
        district_by_key=district_by_key,
        district_row=district_row,
        pending=pending,
    )


def state_lgd(name: str | None) -> int | None:
    canonical = address.canonical_state(name)
    return register().state_by_name.get(canonical) if canonical else None


def district_lgd(state: str | None, district: str | None) -> int | None:
    canonical_state = address.canonical_state(state)
    canonical_district = address.canonical_district(canonical_state, district) if canonical_state else None
    if not canonical_district:
        return None
    return register().district_by_key.get((canonical_state, canonical_district))


def state_name(code: int | None) -> str | None:
    return register().state_name.get(code) if code is not None else None


def district_name(code: int | None) -> str | None:
    row = register().district_row.get(code) if code is not None else None
    return row[2] if row else None


def district_state(code: int | None) -> int | None:
    row = register().district_row.get(code) if code is not None else None
    return row[0] if row else None


def alias_key(raw_state: str | None, raw_district: str | None) -> tuple[str, str]:
    return normalise(raw_state), normalise(raw_district)


def _canonical(raw_state: str | None, raw_district: str | None) -> Resolution | None:
    state = address.canonical_state(raw_state)
    if state is None:
        return None
    reg = register()
    exact_state = (raw_state or "").strip() == state
    text = (raw_district or "").strip()
    if not text:
        return Resolution(reg.state_by_name[state], None, CANONICAL if exact_state else ALIAS)
    district = address.canonical_district(state, text)
    if district is None:
        return Resolution(reg.state_by_name[state], None, STATE_ONLY)
    matched_by = CANONICAL if exact_state and text == district else ALIAS
    if (state, district) in reg.pending:
        return Resolution(reg.state_by_name[state], None, matched_by, pending=True)
    return Resolution(reg.state_by_name[state], reg.district_by_key[(state, district)], matched_by)


def _from_alias(raw_state: str | None, raw_district: str | None, aliases: AliasTable) -> Resolution | None:
    state_key, district_key = alias_key(raw_state, raw_district)
    if not state_key and not district_key:
        return None
    hit = aliases.get((state_key, district_key))
    if hit is not None:
        return Resolution(hit[0], hit[1], GEO_ALIAS)
    state_only = aliases.get((state_key, "")) if district_key else None
    if state_only is not None:
        state = state_name(state_only[0])
        inside = district_lgd(state, raw_district) if state else None
        return Resolution(state_only[0], inside, GEO_ALIAS)
    return None


def _from_place(place: str | None) -> Resolution | None:
    if not place or not place.strip():
        return None
    found = resolve_place(place).place
    if found is None:
        return None
    code = state_lgd(found.state)
    if code is None:
        return None
    return Resolution(code, district_lgd(found.state, found.district), PLACE_ATLAS)


def resolve(
    raw_state: str | None,
    raw_district: str | None,
    aliases: AliasTable | None = None,
    place: str | None = None,
) -> Resolution:
    canonical = _canonical(raw_state, raw_district)
    if canonical is not None and canonical.matched_by != STATE_ONLY:
        return canonical
    if aliases:
        aliased = _from_alias(raw_state, raw_district, aliases)
        if aliased is not None:
            return aliased
    if canonical is not None:
        return canonical
    placed = _from_place(place)
    if placed is not None:
        return placed
    return Resolution(None, None, UNMATCHED)


def lgd_for_unit(state: str | None, district: str | None = None) -> dict[str, Any]:
    canonical_state = address.canonical_state(state)
    code = register().state_by_name.get(canonical_state) if canonical_state else None
    canonical_district = (
        address.canonical_district(canonical_state, district) if canonical_state and district else None
    )
    unit: dict[str, Any] = {
        "stateLgd": code,
        "districtLgd": None,
        "state": canonical_state,
        "district": canonical_district,
        "polygon": POLYGON_STATE,
        "parentLgd": None,
    }
    if not canonical_district:
        unit["district"] = None
        return unit
    unit["districtLgd"] = register().district_by_key.get((canonical_state, canonical_district))
    if district_lineage.draws_state_border(canonical_state, canonical_district):
        return unit
    parent = district_lineage.parent_for(canonical_state, canonical_district)
    if parent:
        unit["polygon"] = POLYGON_PARENT
        unit["parentLgd"] = register().district_by_key.get((canonical_state, parent))
        return unit
    unit["polygon"] = POLYGON_OWN
    return unit
