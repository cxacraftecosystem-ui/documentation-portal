"""The coverage map: which districts the documented work reaches, and which it has not reached yet.

WHAT THIS ANSWERS, beside ``/map/points``. The Places view plots records where they are; this folds
the same records into LGD units (``services/lgd``) so a reader can see COVERAGE: how many workshops,
artisans and records each district holds, which shared crafts nobody has documented, and which
districts have no workshop at all.

THE VISIBILITY RULE STAYS IN ONE PLACE. Every row is read through the ``where`` that
``record_filters.build_record_wheres`` builds for the caller, exactly as ``/map/points`` does. This
module never writes a ``where`` of its own and never runs raw SQL; the coverage filters (craft code,
scheme, financial year, state) are applied in Python over the rows that ``where`` already allowed.

ONE FOLD PER BUCKET, THEN FILTERS. The folded rows depend only on the caller's scope, so they are
cached for :data:`CACHE_TTL_SECONDS` per scope and every filter combination is answered from the same
fold. A bucket reads at most :data:`ROW_CAP` rows; past that the answer says ``truncated``.

NAMES ONLY. A folded row keeps an id, a title, a link, its craft codes and its financial year. Aadhaar,
Pehchan, phone and address text are never copied out of the database row.

PLACE RESOLUTION, per row: the row's ``Location`` state and district through ``lgd.resolve`` (canonical
names, then an administrator's ``GeoAlias``, then the free-text ``place`` through ``place_atlas``). A
row whose location says nothing is resolved from its ``place`` alone, and its place text then stands
as the raw state, so an administrator can map a recurring free-text place once.
"""

from __future__ import annotations

import json
import time
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from app.scale.replica import read_via_replica
from app.services import cross_portal_codes, lgd
from app.services.concurrency import gather_reads
from app.services.record_filters import build_record_wheres

IST = timezone(timedelta(hours=5, minutes=30))

#: Rows read per bucket before the answer is flagged ``truncated``.
ROW_CAP = 20_000
#: Markers drawn before the answer is flagged ``markersTruncated``.
MARKER_CAP = 500
#: How long one scope's fold is reused.
CACHE_TTL_SECONDS = 60
#: Unmatched spellings shown to an administrator inline with the map.
UNMATCHED_SAMPLE = 20
#: Records per page in the unit panel.
PAGE_SIZE = 40

LEVEL_STATE = "STATE"
LEVEL_DISTRICT = "DISTRICT"
LEVELS = (LEVEL_STATE, LEVEL_DISTRICT)

BUCKETS: tuple[str, ...] = ("workshops", "artisans", "products", "tools", "media")
PEER_COUNT_KEYS: tuple[str, ...] = ("workshops", "artisans", "products", "tools", "media")
COUNT_KEYS: tuple[str, ...] = ("workshops", "artisans", "crafts", "products", "tools", "media")

_DELEGATES = {
    "workshops": "workshop",
    "artisans": "artisan",
    "products": "productdocumentation",
    "tools": "tooldocumentation",
    "media": "mediafile",
}
_TITLE_COLUMNS = {
    "workshops": ("title",),
    "artisans": ("name",),
    "products": ("productName",),
    "tools": ("toolkitName", "englishName"),
    "media": ("originalFilename",),
}
_HREF = {
    "workshops": "/workshops/{id}",
    "artisans": "/artisans/{id}",
    "products": "/products/{id}",
    "tools": "/tools/{id}",
    "media": "/media",
}
_INCLUDE: dict[str, dict[str, Any]] = {
    "workshops": {"location": True, "crafts": {"include": {"craft": True}}},
    "artisans": {"location": True, "craft": True, "workshop": True},
    "products": {"location": True, "craft": True, "workshop": True},
    "tools": {"location": True, "craft": True, "workshop": True},
    "media": {"location": True, "craft": True, "workshop": True},
}

#: The scheme vocabulary on this portal is ``Workshop.workshopType``. ``DPD`` is the shared contract's
#: name for ``DESIGN_PROTOTYPE``; both spellings are accepted.
SCHEMES: tuple[dict[str, str], ...] = (
    {"value": "DPD", "label": "Design & Prototype Development Workshop"},
    {"value": "OTHER", "label": "Other workshop"},
)
_SCHEME_ALIASES = {"DPD": "DESIGN_PROTOTYPE", "DESIGN_PROTOTYPE": "DESIGN_PROTOTYPE", "OTHER": "OTHER"}

BOUNDARIES_DIR = Path(__file__).resolve().parents[1] / "data" / "boundaries"
CENTROIDS_FILE = BOUNDARIES_DIR / "district-centroids.json"
SHAPES_MANIFEST_FILE = BOUNDARIES_DIR / "shapes-manifest.json"
DEPICTION = "Survey of India"

#: Keys a stub or a peer payload must never carry. Tests scan the JSON for them.
FORBIDDEN_KEY_PREFIXES = ("aadhaar", "pehchan", "phone", "address", "village", "pincode")


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Small pure helpers
# ──────────────────────────────────────────────────────────────────────────────────────────────


def fy_of(when: datetime | None) -> int | None:
    """The financial year a moment falls in, by its start year: 1 April to 31 March, in IST."""
    if when is None:
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=UTC)
    local = when.astimezone(IST)
    return local.year if local.month >= 4 else local.year - 1


def fy_label(fy: int) -> str:
    """``2025`` -> ``"2025-26"``."""
    return f"{fy}-{(fy + 1) % 100:02d}"


def resolve_level(raw: str | None) -> str:
    value = (raw or LEVEL_DISTRICT).strip().upper()
    return value if value in LEVELS else LEVEL_DISTRICT


def split_values(raw: list[str] | None) -> list[str]:
    """Repeated or comma-joined query values, trimmed, de-duplicated, order kept."""
    out: list[str] = []
    for item in raw or []:
        for part in str(item).split(","):
            text = part.strip()
            if text and text not in out:
                out.append(text)
    return out


def resolve_schemes(raw: list[str] | None) -> list[str]:
    """Scheme values as ``workshopType`` values. Unknown values are dropped."""
    out: list[str] = []
    for value in split_values(raw):
        mapped = _SCHEME_ALIASES.get(value.upper())
        if mapped and mapped not in out:
            out.append(mapped)
    return out


def resolve_fys(raw: list[str] | None) -> list[int]:
    out: list[int] = []
    for value in split_values(raw):
        try:
            year = int(value)
        except ValueError:
            continue
        if 1990 <= year <= 2100 and year not in out:
            out.append(year)
    return out


def resolve_craft_codes(raw: list[str] | None) -> list[str]:
    return [code for code in split_values(raw) if cross_portal_codes.is_known_code(code)]


def _title(row: Any, bucket: str) -> str:
    for column in _TITLE_COLUMNS[bucket]:
        value = getattr(row, column, None)
        if value:
            return str(value)
    return "Untitled record"


@lru_cache(maxsize=1)
def centroids() -> dict[int, tuple[float, float]]:
    """District code -> (lon, lat) of a point inside its polygon, from the shapes build."""
    if not CENTROIDS_FILE.exists():
        return {}
    raw = json.loads(CENTROIDS_FILE.read_text(encoding="utf-8"))
    return {int(code): (float(point[0]), float(point[1])) for code, point in raw.items()}


@lru_cache(maxsize=1)
def shapes_version() -> str | None:
    if not SHAPES_MANIFEST_FILE.exists():
        return None
    raw = json.loads(SHAPES_MANIFEST_FILE.read_text(encoding="utf-8"))
    return raw.get("shapesVersion")


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The fold
# ──────────────────────────────────────────────────────────────────────────────────────────────


@dataclass
class Folded:
    """One record, reduced to what the coverage map may show about it."""

    bucket: str
    id: str
    title: str
    href: str
    craft_codes: tuple[str, ...]
    #: What "crafts" counts: the shared code, else the craft row, else the normalised craft name.
    craft_keys: tuple[str, ...]
    fy: int | None
    scheme: str | None
    state_lgd: int | None
    district_lgd: int | None
    matched_by: str
    pending: bool
    #: The raw text the place came from, for the unmatched report, and which table holds it.
    raw_state: str | None
    raw_district: str | None
    raw_model: str | None
    pin: tuple[float, float] | None = None


@dataclass
class Fold:
    rows: list[Folded] = field(default_factory=list)
    truncated: bool = False
    at: float = 0.0


def _craft_code_for_name(name: str | None, spellings: dict[str, str]) -> str | None:
    code = cross_portal_codes.shared_craft_code(name)
    if code:
        return code
    key = cross_portal_codes.normalise(name)
    return spellings.get(key) if key else None


def _craft_of(row: Any, bucket: str, spellings: dict[str, str]) -> tuple[tuple[str, ...], tuple[str, ...]]:
    codes: list[str] = []
    keys: list[str] = []

    def add(code: str | None, key: str | None) -> None:
        if code and code not in codes:
            codes.append(code)
        token = code or key
        if token and token not in keys:
            keys.append(token)

    if bucket == "workshops":
        for link in getattr(row, "crafts", None) or []:
            craft = getattr(link, "craft", None)
            if craft is not None:
                add(getattr(craft, "craftCode", None), f"craft:{craft.id}")
        return tuple(codes), tuple(keys)
    craft = getattr(row, "craft", None)
    if craft is not None:
        code = getattr(craft, "craftCode", None) or _craft_code_for_name(craft.name, spellings)
        add(code, f"craft:{craft.id}")
    else:
        name = getattr(row, "craftName", None)
        if name and name.strip():
            add(_craft_code_for_name(name, spellings), f"name:{cross_portal_codes.normalise(name)}")
    return tuple(codes), tuple(keys)


def _when(row: Any, bucket: str) -> datetime | None:
    if bucket == "workshops":
        return getattr(row, "startDate", None) or getattr(row, "date", None)
    return getattr(row, "createdAt", None)


def _scheme(row: Any, bucket: str) -> str | None:
    if bucket == "workshops":
        value = getattr(row, "workshopType", None)
    else:
        workshop = getattr(row, "workshop", None)
        value = getattr(workshop, "workshopType", None) if workshop is not None else None
    if value is None:
        return None
    return str(getattr(value, "value", value))


def fold_row(row: Any, bucket: str, aliases: lgd.AliasTable, spellings: dict[str, str]) -> Folded:
    location = getattr(row, "location", None)
    place = getattr(row, "place", None)
    raw_state = (getattr(location, "state", None) or "").strip() or None
    raw_district = (getattr(location, "district", None) or "").strip() or None
    if raw_state or raw_district:
        resolution = lgd.resolve(raw_state, raw_district, aliases, place=place)
        raw_model = "Location"
    elif place and str(place).strip():
        # Nothing structured: the free-text place stands as the raw state, so an alias can map it.
        raw_state, raw_district = str(place).strip(), None
        resolution = lgd.resolve(raw_state, None, aliases, place=raw_state)
        raw_model = "Workshop" if bucket == "workshops" else None
    else:
        resolution = lgd.Resolution(None, None, lgd.UNMATCHED)
        raw_model = None
    pin = None
    if location is not None:
        lat = getattr(location, "subjectLatitude", None)
        lon = getattr(location, "subjectLongitude", None)
        if lat is not None and lon is not None:
            pin = (float(lon), float(lat))
    codes, keys = _craft_of(row, bucket, spellings)
    return Folded(
        bucket=bucket,
        id=row.id,
        title=_title(row, bucket),
        href=_HREF[bucket].format(id=row.id),
        craft_codes=codes,
        craft_keys=keys,
        fy=fy_of(_when(row, bucket)),
        scheme=_scheme(row, bucket),
        state_lgd=resolution.state_lgd,
        district_lgd=resolution.district_lgd,
        matched_by=resolution.matched_by,
        pending=getattr(resolution, "pending", False),
        raw_state=raw_state,
        raw_district=raw_district,
        raw_model=raw_model,
        pin=pin,
    )


async def _load(client: Any, wheres: dict[str, dict[str, Any]]) -> tuple[dict[str, list[Any]], dict, dict]:
    reads = await gather_reads(
        *(
            getattr(client, _DELEGATES[bucket]).find_many(
                where=wheres[bucket], include=_INCLUDE[bucket], take=ROW_CAP + 1, order={"id": "asc"}
            )
            for bucket in BUCKETS
        ),
        client.geoalias.find_many(),
        client.craftspelling.find_many(where={"source": cross_portal_codes.SOURCE_ADMIN}),
    )
    rows = dict(zip(BUCKETS, reads[: len(BUCKETS)], strict=True))
    aliases = {
        (r.rawStateKey, r.rawDistrictKey or ""): (r.stateLgd, r.districtLgd) for r in reads[len(BUCKETS)] or []
    }
    spellings = {r.spelling: r.craftCode for r in reads[len(BUCKETS) + 1] or []}
    return rows, aliases, spellings


#: The read the fold goes through. A seam for the tests, which hand it a fake client.
reader = read_via_replica

_cache: dict[str, Fold] = {}


def clear_cache() -> None:
    _cache.clear()


def _scope_key(wheres: dict[str, dict[str, Any]]) -> str:
    return json.dumps(wheres, sort_keys=True, default=str)


async def fold_for(wheres: dict[str, dict[str, Any]]) -> Fold:
    """Every visible record, folded, for one scope. Cached for :data:`CACHE_TTL_SECONDS`."""
    key = _scope_key(wheres)
    now = time.monotonic()
    hit = _cache.get(key)
    if hit is not None and now - hit.at < CACHE_TTL_SECONDS:
        return hit
    rows, aliases, spellings = await reader(lambda client: _load(client, wheres))
    fold = Fold(at=now)
    for bucket in BUCKETS:
        bucket_rows = rows[bucket] or []
        if len(bucket_rows) > ROW_CAP:
            fold.truncated = True
            bucket_rows = bucket_rows[:ROW_CAP]
        fold.rows.extend(fold_row(row, bucket, aliases, spellings) for row in bucket_rows)
    if len(_cache) > 64:
        _cache.clear()
    _cache[key] = fold
    return fold


async def scope_wheres(user: Any) -> dict[str, dict[str, Any]]:
    """The caller's visibility, as the shared filter module builds it, with no other filter."""
    return await build_record_wheres(user)


#: The principal the peer feed folds as: every row, because only aggregate counts leave.
PEER_PRINCIPAL = SimpleNamespace(id="cross-portal-peer", role="MASTER_ADMIN")


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Filtering and aggregation
# ──────────────────────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Filters:
    level: str = LEVEL_DISTRICT
    craft_codes: tuple[str, ...] = ()
    schemes: tuple[str, ...] = ()
    fys: tuple[int, ...] = ()
    state_lgd: int | None = None

    def passes(self, row: Folded) -> bool:
        if self.craft_codes and not set(row.craft_codes) & set(self.craft_codes):
            return False
        if self.schemes and row.scheme not in self.schemes:
            return False
        if self.fys and row.fy not in self.fys:
            return False
        return self.state_lgd is None or row.state_lgd == self.state_lgd

    def echo(self) -> dict[str, Any]:
        scheme_names = {v: k for k, v in _SCHEME_ALIASES.items() if k != "DESIGN_PROTOTYPE"}
        return {
            "level": self.level,
            "craftCode": list(self.craft_codes),
            "scheme": [scheme_names.get(s, s) for s in self.schemes],
            "fy": list(self.fys),
            "stateLgd": self.state_lgd,
        }


def unit_key(row: Folded, level: str) -> tuple[int, int | None] | None:
    if row.state_lgd is None:
        return None
    if level == LEVEL_STATE:
        return (row.state_lgd, None)
    return (row.state_lgd, row.district_lgd)


def _unit_shell(state_code: int, district_code: int | None) -> dict[str, Any]:
    state = lgd.state_name(state_code)
    district = lgd.district_name(district_code) if district_code is not None else None
    if district:
        unit = lgd.lgd_for_unit(state, district)
    else:
        unit = {
            "stateLgd": state_code,
            "districtLgd": None,
            "state": state,
            "district": None,
            "polygon": lgd.POLYGON_STATE,
            "parentLgd": None,
        }
    unit["stateLgd"] = state_code
    unit["districtLgd"] = district_code
    return unit


def _is_unmatched(row: Folded) -> bool:
    """A raw spelling an administrator could map: unresolved text, or a district that did not land."""
    if row.raw_model is None:
        return False
    if row.matched_by == lgd.UNMATCHED:
        return True
    return row.matched_by == lgd.STATE_ONLY and bool(row.raw_district)


def aggregate(fold: Fold, filters: Filters) -> dict[str, Any]:
    """The coverage answer for one filter set, from one fold. Pure; no database."""
    rows = [row for row in fold.rows if filters.passes(row)]
    units: dict[tuple[int, int | None], dict[str, Any]] = {}
    craft_sets: dict[tuple[int, int | None], set[str]] = defaultdict(set)
    code_sets: dict[tuple[int, int | None], set[str]] = defaultdict(set)
    documented: set[str] = set()
    workshop_districts: set[int] = set()
    workshop_states: set[int] = set()
    markers: list[dict[str, Any]] = []
    unmatched = 0
    centre = centroids()

    for row in rows:
        documented.update(row.craft_codes)
        if _is_unmatched(row):
            unmatched += 1
        key = unit_key(row, filters.level)
        if key is None:
            continue
        unit = units.get(key)
        if unit is None:
            unit = _unit_shell(*key)
            unit["counts"] = {name: 0 for name in COUNT_KEYS}
            unit["peer"] = None
            units[key] = unit
        unit["counts"][row.bucket] += 1
        craft_sets[key].update(row.craft_keys)
        code_sets[key].update(row.craft_codes)
        if row.bucket == "workshops":
            workshop_states.add(row.state_lgd)  # type: ignore[arg-type]
            if row.district_lgd is not None:
                workshop_districts.add(row.district_lgd)
            marker = _marker(row, centre)
            if marker is not None:
                markers.append(marker)

    for key, unit in units.items():
        unit["counts"]["crafts"] = len(craft_sets[key])
        unit["craftCodes"] = sorted(code_sets[key])
        unit["clusters"] = []

    ordered = sorted(
        units.values(),
        key=lambda u: (u["state"] or "", u["district"] is None, u["district"] or ""),
    )
    markers.sort(key=lambda m: (m["title"].lower(), m["id"]))
    return {
        "filters": filters.echo(),
        "units": ordered,
        "markers": markers[:MARKER_CAP],
        "markersTruncated": len(markers) > MARKER_CAP,
        "gaps": gaps(documented, workshop_districts, workshop_states, filters.state_lgd),
        "unmatched": {"count": unmatched},
        "truncated": fold.truncated,
        "_rows": rows,
    }


def _marker(row: Folded, centre: dict[int, tuple[float, float]]) -> dict[str, Any] | None:
    point, precision = row.pin, "SUBJECT_PIN"
    if point is None and row.district_lgd is not None:
        unit = _unit_shell(row.state_lgd, row.district_lgd)  # type: ignore[arg-type]
        code = unit["parentLgd"] if unit["polygon"] == lgd.POLYGON_PARENT else row.district_lgd
        point, precision = centre.get(code) if code is not None else None, "DISTRICT_CENTROID"
    if point is None:
        return None
    return {
        "kind": "WORKSHOP",
        "id": row.id,
        "title": row.title,
        "clusterName": None,
        "craftCode": row.craft_codes[0] if row.craft_codes else None,
        "stateLgd": row.state_lgd,
        "districtLgd": row.district_lgd,
        "lon": round(point[0], 4),
        "lat": round(point[1], 4),
        "precision": precision,
        "href": row.href,
    }


def gaps(
    documented: set[str],
    workshop_districts: set[int],
    workshop_states: set[int],
    state_lgd: int | None,
) -> dict[str, Any]:
    """Shared crafts with no record, and register units with no workshop, under the filters."""
    crafts = [c for c in cross_portal_codes.catalogue() if c["code"] not in documented]
    by_state: list[dict[str, Any]] = []
    count = 0
    raw = _register_rows()
    for state_code, state, districts in raw:
        if state_lgd is not None and state_code != state_lgd:
            continue
        without = [d for d in districts if d["lgd"] is None or d["lgd"] not in workshop_districts]
        count += len(without)
        if without:
            by_state.append(
                {"stateLgd": state_code, "state": state, "total": len(districts), "without": without}
            )
    states = [
        {"lgd": code, "name": name}
        for code, name, _ in raw
        if code not in workshop_states and (state_lgd is None or code == state_lgd)
    ]
    return {
        "craftsNeverDocumented": crafts,
        "districtsWithoutWorkshop": {"count": count, "byState": by_state},
        "statesWithoutWorkshop": states,
    }


@lru_cache(maxsize=1)
def _register_rows() -> tuple[tuple[int, str, list[dict[str, Any]]], ...]:
    """Every state with its districts in register order; a pending district has ``lgd: null``."""
    from app.services import address

    reg = lgd.register()
    out = []
    for state in address.INDIAN_STATES_AND_UNION_TERRITORIES:
        districts = [
            {"lgd": reg.district_by_key.get((state, name)), "name": name}
            for name in address.DISTRICTS_BY_STATE[state]
        ]
        out.append((reg.state_by_name[state], state, districts))
    return tuple(out)


def catalogue(fold: Fold) -> dict[str, Any]:
    years = sorted({row.fy for row in fold.rows if row.fy is not None}, reverse=True)
    return {
        "crafts": cross_portal_codes.catalogue(),
        "schemes": [dict(s) for s in SCHEMES],
        "years": [{"fy": fy, "label": fy_label(fy)} for fy in years],
    }


def register_block() -> dict[str, Any]:
    reg = lgd.register()
    return {
        "lgdAsOf": reg.as_of,
        "listVersion": reg.list_version,
        "districtCount": reg.district_count,
        "stateCount": reg.state_count,
    }


def boundaries_block() -> dict[str, Any]:
    return {"shapesVersion": shapes_version(), "depiction": DEPICTION}


def now_iso() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def coverage_payload(fold: Fold, filters: Filters, *, admin: bool) -> dict[str, Any]:
    body = aggregate(fold, filters)
    rows = body.pop("_rows")
    if admin:
        body["unmatched"]["sample"] = unmatched_items(rows)[:UNMATCHED_SAMPLE]
    return {
        "asOf": now_iso(),
        "register": register_block(),
        "boundaries": boundaries_block(),
        "filters": body["filters"],
        "catalogue": catalogue(fold),
        "units": body["units"],
        "markers": body["markers"],
        "markersTruncated": body["markersTruncated"],
        "gaps": body["gaps"],
        "unmatched": body["unmatched"],
        "truncated": body["truncated"],
    }


# ──────────────────────────────────────────────────────────────────────────────────────────────
# One unit's records, the unmatched report, and the peer feed
# ──────────────────────────────────────────────────────────────────────────────────────────────


def unit_records(
    fold: Fold,
    filters: Filters,
    state_code: int,
    district_code: int,
    *,
    bucket: str | None = None,
    cursor: str | None = None,
) -> dict[str, Any]:
    """The records behind one unit, per bucket, paged. ``district_code`` 0 is the state-level match."""
    wanted_district = district_code or None
    whole_state = filters.level == LEVEL_STATE and wanted_district is None
    selected = [bucket] if bucket in BUCKETS else list(BUCKETS)
    try:
        offset = max(0, int(cursor or 0))
    except ValueError:
        offset = 0
    lists: dict[str, list[Folded]] = {name: [] for name in selected}
    for row in fold.rows:
        if row.bucket not in lists or not filters.passes(row) or row.state_lgd != state_code:
            continue
        if not whole_state and row.district_lgd != wanted_district:
            continue
        lists[row.bucket].append(row)
    buckets: dict[str, Any] = {}
    for name, rows in lists.items():
        rows.sort(key=lambda r: (r.title.lower(), r.id))
        start = offset if bucket == name else 0
        page = rows[start : start + PAGE_SIZE]
        nxt = start + PAGE_SIZE
        buckets[name] = {
            "total": len(rows),
            "items": [
                {
                    "id": r.id,
                    "title": r.title,
                    "href": r.href,
                    "craftCode": r.craft_codes[0] if r.craft_codes else None,
                    "fy": r.fy,
                }
                for r in page
            ],
            "nextCursor": str(nxt) if nxt < len(rows) else None,
        }
    unit = _unit_shell(state_code, None if whole_state else wanted_district)
    return {"unit": unit, "buckets": buckets, "peerHref": None}


def _suggest(raw_state: str | None, raw_district: str | None) -> dict[str, Any] | None:
    """Only one kind of suggestion: a real state, and a district that exists exactly in ONE other state."""
    from app.services import address

    state = address.canonical_state(raw_state)
    if state is None or not raw_district:
        return None
    homes = [
        s
        for s in address.INDIAN_STATES_AND_UNION_TERRITORIES
        if s != state and address.canonical_district(s, raw_district)
    ]
    if len(homes) != 1:
        return None
    home = homes[0]
    district = address.canonical_district(home, raw_district)
    code = lgd.district_lgd(home, district)
    if code is None:
        return None
    return {
        "stateLgd": lgd.state_lgd(home),
        "districtLgd": code,
        "reason": f"{district} is a district of {home}, not {state}.",
    }


def unmatched_items(rows: list[Folded]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, str, str], int] = defaultdict(int)
    for row in rows:
        if _is_unmatched(row):
            groups[(row.raw_model or "Location", row.raw_state or "", row.raw_district or "")] += 1
    items = [
        {
            "model": model,
            "rawState": raw_state or None,
            "rawDistrict": raw_district or None,
            "rows": count,
            "suggestion": _suggest(raw_state, raw_district),
        }
        for (model, raw_state, raw_district), count in groups.items()
    ]
    items.sort(key=lambda item: (-item["rows"], item["rawState"] or "", item["rawDistrict"] or ""))
    return items


def peer_payload(fold: Fold, filters: Filters) -> dict[str, Any]:
    """Aggregate counts per district for the designer portal. No id, name or stub leaves."""
    units: dict[tuple[int, int | None], dict[str, int]] = {}
    documented: set[str] = set()
    by_state: dict[int, set[str]] = defaultdict(set)
    for row in fold.rows:
        if not filters.passes(row):
            continue
        documented.update(row.craft_codes)
        if row.state_lgd is not None:
            by_state[row.state_lgd].update(row.craft_codes)
        key = unit_key(row, LEVEL_DISTRICT)
        if key is None:
            continue
        counts = units.setdefault(key, {name: 0 for name in PEER_COUNT_KEYS})
        counts[row.bucket] += 1
    reg = lgd.register()
    return {
        "asOf": now_iso(),
        "listVersion": reg.list_version,
        "units": [
            {"stateLgd": s, "districtLgd": d, "counts": counts}
            for (s, d), counts in sorted(units.items(), key=lambda item: (item[0][0], item[0][1] or 0))
        ],
        "craftCodesDocumented": sorted(documented),
        # Per state, so a Regional Director's "never documented" list counts only their states.
        "craftCodesByState": {str(s): sorted(codes) for s, codes in sorted(by_state.items()) if codes},
    }


def peer_coverage_active(settings: Any) -> bool:
    """The peer feed answers only with its flag on and the shared token set (32+ characters)."""
    if not getattr(settings, "cross_portal_coverage_enabled", False):
        return False
    token = getattr(settings, "cross_portal_token", None)
    return bool(token) and len(token.strip()) >= 32
