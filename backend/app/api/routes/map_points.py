from __future__ import annotations

import math
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import get_current_user
from app.services.concurrency import gather_reads
from app.services.geography import (
    PRECISION_SUBJECT_PIN,
    SOURCE_PLACE_TEXT,
    SOURCE_SUBJECT_PIN,
    AdminLevel,
    StatedPoint,
    DistrictAnchors,
    address_completeness,
    mean_point,
    parse_stated_key,
    resolve_admin_level,
    stated_point,
)
from app.services.place_atlas import resolve_place
from app.services.record_filters import (
    PLACED_TYPES,
    RECORD_TYPES,
    build_record_wheres,
    resolve_types,
)

router = APIRouter(prefix="/map", tags=["map"])

_BUCKETS: dict[str, tuple[str, tuple[str, ...]]] = {
    "artisans": ("artisan", ("name",)),
    "workshops": ("workshop", ("title",)),
    "products": ("productdocumentation", ("productName",)),
    "tools": ("tooldocumentation", ("toolkitName", "englishName")),
    "media": ("mediafile", ("originalFilename",)),
}


def _delegate(bucket: str) -> Any:
    return getattr(db, _BUCKETS[bucket][0])


_CLUSTER_DEGREES_BY_LEVEL: dict[AdminLevel, float] = {
    AdminLevel.DISTRICT: 0.25,
    AdminLevel.STATE: 1.0,
    AdminLevel.NATION: 5.0,
}

_CHILD_LEVEL: dict[AdminLevel, AdminLevel | None] = {
    AdminLevel.NATION: AdminLevel.STATE,
    AdminLevel.STATE: AdminLevel.DISTRICT,
    AdminLevel.DISTRICT: None,
}

_MAX_CHILDREN = 60

_MAX_CAPTURE_POINTS = 4000

_MAX_LOCATION_ROWS = 20000

_MAX_ANCHOR_ROWS = 5000

_STUB_CAP = 40

_BLANK_PLACE = "(blank)"


def _title(row: Any, bucket: str) -> str:
    for column in _BUCKETS[bucket][1]:
        value = getattr(row, column, None)
        if value:
            return str(value)
    return "Untitled record"


def _cell(latitude: float, longitude: float, degrees: float) -> tuple[int, int]:
    return (math.floor(latitude / degrees), math.floor(longitude / degrees))


def _capture_key(latitude: float, longitude: float, degrees: float) -> str:
    cell_latitude, cell_longitude = _cell(latitude, longitude, degrees)
    return f"capture:{degrees:g}:{cell_latitude}_{cell_longitude}"


def _metres_across(points: list[tuple[float, float]]) -> int:
    if len(points) < 2:
        return 0
    latitudes = [latitude for latitude, _ in points]
    longitudes = [longitude for _, longitude in points]
    scale = math.cos(math.radians(sum(latitudes) / len(latitudes)))
    north = (max(latitudes) - min(latitudes)) * 111_320
    east = (max(longitudes) - min(longitudes)) * 111_320 * scale
    return round(math.hypot(north, east))


def _empty_counts() -> dict[str, int]:
    return {bucket: 0 for bucket in RECORD_TYPES}



def _group_geography(
    location: Any | None,
    place_text: str | None,
    level: AdminLevel,
    anchors: DistrictAnchors,
    degrees: float,
) -> tuple[Any, str] | None:
    if location is not None:
        placed = stated_point(
            level=level,
            state=getattr(location, "state", None),
            district=getattr(location, "district", None),
            subject_latitude=getattr(location, "subjectLatitude", None),
            subject_longitude=getattr(location, "subjectLongitude", None),
            anchors=anchors,
        )
        if placed is not None:
            return placed, "STATED"

    resolved = resolve_place(place_text)
    if resolved.place is not None:
        place = resolved.place
        placed = stated_point(
            level=level,
            state=place.state,
            district=place.district,
            subject_latitude=place.latitude,
            subject_longitude=place.longitude,
            anchors=anchors,
            pin_precision=place.precision.value,
            pin_source=SOURCE_PLACE_TEXT,
        )
        if placed is not None:
            return placed, "ATLAS"

    if location is not None:
        latitude = getattr(location, "subjectLatitude", None)
        longitude = getattr(location, "subjectLongitude", None)
        if latitude is not None and longitude is not None:
            return _pin_point(float(latitude), float(longitude), degrees), "PIN"

    return None


def _pin_key(latitude: float, longitude: float, degrees: float) -> str:
    cell_latitude, cell_longitude = _cell(latitude, longitude, degrees)
    return f"pin:{degrees:g}:{cell_latitude}_{cell_longitude}"


def _pin_point(latitude: float, longitude: float, degrees: float) -> StatedPoint:
    return StatedPoint(
        key=_pin_key(latitude, longitude, degrees),
        label="Pinned place",
        region=f"{latitude:.4f}, {longitude:.4f} — no state or district recorded",
        state=None,
        district=None,
        latitude=latitude,
        longitude=longitude,
        precision=PRECISION_SUBJECT_PIN,
        source=SOURCE_SUBJECT_PIN,
    )


def _child_shell(placed: Any, level: AdminLevel, layer: str) -> dict[str, Any]:
    return {
        "key": placed.key,
        "level": level.value,
        "layer": layer,
        "label": placed.label,
        "region": placed.region,
        "state": getattr(placed, "state", None),
        "district": getattr(placed, "district", None),
        "latitude": placed.latitude,
        "longitude": placed.longitude,
        "precision": placed.precision,
        "source": placed.source,
        "total": 0,
        "counts": _empty_counts(),
    }


def _finish_children(
    children: dict[str, dict[str, dict[str, Any]]],
    positions: dict[tuple[str, str], list[tuple[float, float, int]]],
    points: dict[str, dict[str, Any]],
) -> None:
    for key, point in points.items():
        entries = list(children.get(key, {}).values())
        for entry in entries:
            centre = mean_point(positions.get((key, entry["key"]), []))
            if centre is not None:
                entry["latitude"], entry["longitude"] = centre
        entries.sort(key=lambda entry: (-entry["total"], entry["label"]))
        if len(entries) < 2:
            point["children"] = []
            point["childrenTruncated"] = False
            continue
        point["children"] = entries[:_MAX_CHILDREN]
        point["childrenTruncated"] = len(entries) > _MAX_CHILDREN


def _origin_points(
    grouped: list[tuple[str, list[Any]]],
    locations: dict[str, Any],
    level: AdminLevel,
    anchors: DistrictAnchors,
    degrees: float,
    child_level: AdminLevel | None = None,
    child_degrees: float = 0.0,
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]], int]:
    points: dict[str, dict[str, Any]] = {}
    unplaced: dict[str, dict[str, Any]] = {}
    placed_total = 0
    positions: dict[str, list[tuple[float, float, int]]] = {}
    children: dict[str, dict[str, dict[str, Any]]] = {}
    child_positions: dict[tuple[str, str], list[tuple[float, float, int]]] = {}

    for bucket, groups in grouped:
        for group in groups:
            count = group["_count"]["_all"]
            if not count:
                continue
            place_text = group.get("place")
            location = locations.get(group.get("locationId") or "")
            resolved = _group_geography(location, place_text, level, anchors, degrees)

            if resolved is None:
                label = (place_text or "").strip()
                key = label.lower() or _BLANK_PLACE
                entry = unplaced.setdefault(
                    key,
                    {"label": label or "No place recorded", "total": 0, "counts": _empty_counts()},
                )
                entry["total"] += count
                entry["counts"][bucket] += count
                continue

            placed, source_kind = resolved
            point = points.setdefault(
                placed.key,
                {
                    "key": placed.key,
                    "layer": "ORIGIN",
                    "label": placed.label,
                    "region": placed.region,
                    "state": placed.state,
                    "district": placed.district,
                    "latitude": placed.latitude,
                    "longitude": placed.longitude,
                    "precision": placed.precision,
                    "source": placed.source,
                    "total": 0,
                    "counts": _empty_counts(),
                    "places": [],
                    "pinnedRecords": 0,
                    "fromPlaceText": 0,
                },
            )
            point["total"] += count
            point["counts"][bucket] += count
            placed_total += count
            positions.setdefault(placed.key, []).append(
                (placed.latitude, placed.longitude, count)
            )
            if placed.source == SOURCE_SUBJECT_PIN:
                point["pinnedRecords"] += count
            if source_kind == "ATLAS":
                point["fromPlaceText"] += count

            label = (place_text or "").strip()
            for name in (label, placed.label):
                if name and name != point["label"] and name not in point["places"]:
                    point["places"].append(name)

            if child_level is not None:
                resolved_child = _group_geography(
                    location, place_text, child_level, anchors, child_degrees
                )
                if resolved_child is not None:
                    child_placed, _child_kind = resolved_child
                    entry = children.setdefault(placed.key, {}).setdefault(
                        child_placed.key, _child_shell(child_placed, child_level, "ORIGIN")
                    )
                    entry["total"] += count
                    entry["counts"][bucket] += count
                    child_positions.setdefault((placed.key, child_placed.key), []).append(
                        (child_placed.latitude, child_placed.longitude, count)
                    )

    for key, point in points.items():
        centre = mean_point(positions.get(key, []))
        if centre is not None:
            point["latitude"], point["longitude"] = centre
        point["places"].sort(key=lambda name: (-len(name), name))

    _finish_children(children, child_positions, points)

    return points, sorted(unplaced.values(), key=lambda entry: -entry["total"]), placed_total


def _locations_in_play(
    grouped: list[tuple[str, list[Any]]],
) -> tuple[dict[str, dict[str, int]], list[str], bool]:
    per_location: dict[str, dict[str, int]] = {}
    for bucket, groups in grouped:
        for group in groups:
            location_id = group.get("locationId")
            if not location_id:
                continue
            counts = per_location.setdefault(location_id, _empty_counts())
            counts[bucket] += group["_count"]["_all"]

    busiest = sorted(per_location.items(), key=lambda item: -sum(item[1].values()))
    wanted_ids = [location_id for location_id, _ in busiest[:_MAX_LOCATION_ROWS]]

    truncated = len(per_location) > _MAX_CAPTURE_POINTS
    if truncated:
        per_location = dict(busiest[:_MAX_CAPTURE_POINTS])
    return per_location, wanted_ids, truncated


def _capture_points(
    rows: list[Any],
    per_location: dict[str, dict[str, int]],
    degrees: float,
    child_level: AdminLevel | None = None,
    child_degrees: float = 0.0,
) -> tuple[dict[str, dict[str, Any]], int]:
    clusters: dict[tuple[int, int], dict[str, Any]] = {}
    captured_total = 0
    child_clusters: dict[tuple[int, int], dict[tuple[int, int], dict[str, Any]]] = {}
    for row in rows:
        latitude = getattr(row, "latitude", None)
        longitude = getattr(row, "longitude", None)
        if latitude is None or longitude is None:
            continue
        counts = per_location.get(row.id)
        if counts is None:
            continue
        parent_cell = _cell(latitude, longitude, degrees)
        cluster = clusters.setdefault(
            parent_cell,
            {"fixes": [], "counts": _empty_counts(), "total": 0, "accuracies": []},
        )
        cluster["fixes"].append((latitude, longitude))
        accuracy = getattr(row, "accuracy", None)
        if accuracy is not None:
            cluster["accuracies"].append(float(accuracy))
        child: dict[str, Any] | None = None
        if child_level is not None and child_degrees > 0:
            child = child_clusters.setdefault(parent_cell, {}).setdefault(
                _cell(latitude, longitude, child_degrees),
                {"fixes": [], "counts": _empty_counts(), "total": 0},
            )
            child["fixes"].append((latitude, longitude))
        for bucket, value in counts.items():
            cluster["counts"][bucket] += value
            cluster["total"] += value
            captured_total += value
            if child is not None:
                child["counts"][bucket] += value
                child["total"] += value

    points: dict[str, dict[str, Any]] = {}
    for parent_cell, cluster in clusters.items():
        fixes = cluster["fixes"]
        latitude = sum(value for value, _ in fixes) / len(fixes)
        longitude = sum(value for _, value in fixes) / len(fixes)
        key = _capture_key(fixes[0][0], fixes[0][1], degrees)
        accuracies = cluster["accuracies"]
        points[key] = {
            "key": key,
            "layer": "CAPTURE",
            "label": "Recorded here",
            "region": f"{latitude:.4f}, {longitude:.4f}",
            "state": None,
            "district": None,
            "latitude": latitude,
            "longitude": longitude,
            "precision": "MEASURED",
            "source": "DEVICE_FIX",
            "total": cluster["total"],
            "counts": cluster["counts"],
            "places": [],
            "pinnedRecords": cluster["total"],
            "fromPlaceText": 0,
            "fixes": len(fixes),
            "spreadMetres": _metres_across(fixes),
            "medianAccuracy": round(sorted(accuracies)[len(accuracies) // 2], 1) if accuracies else None,
        }

        entries: list[dict[str, Any]] = []
        for child in (child_clusters.get(parent_cell) or {}).values():
            child_fixes = child["fixes"]
            child_latitude = sum(value for value, _ in child_fixes) / len(child_fixes)
            child_longitude = sum(value for _, value in child_fixes) / len(child_fixes)
            entries.append(
                {
                    "key": _capture_key(child_fixes[0][0], child_fixes[0][1], child_degrees),
                    "level": child_level.value if child_level else None,
                    "layer": "CAPTURE",
                    "label": "Recorded here",
                    "region": f"{child_latitude:.4f}, {child_longitude:.4f}",
                    "state": None,
                    "district": None,
                    "latitude": child_latitude,
                    "longitude": child_longitude,
                    "precision": "MEASURED",
                    "source": "DEVICE_FIX",
                    "total": child["total"],
                    "counts": child["counts"],
                    "fixes": len(child_fixes),
                    "spreadMetres": _metres_across(child_fixes),
                }
            )
        entries.sort(key=lambda entry: (-entry["total"], entry["region"]))
        points[key]["children"] = entries[:_MAX_CHILDREN] if len(entries) > 1 else []
        points[key]["childrenTruncated"] = len(entries) > _MAX_CHILDREN

    return points, captured_total


async def _focus_keys(
    focus_type: str,
    focus_id: str,
    wheres: dict[str, dict[str, Any]],
    level: AdminLevel,
    anchors: DistrictAnchors,
    degrees: float,
) -> dict[str, Any]:
    if focus_type not in _BUCKETS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Unknown record type '{focus_type}'. Valid types are {', '.join(RECORD_TYPES)}.",
        )
    delegate = _delegate(focus_type)
    row = await delegate.find_unique(where={"id": focus_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    in_scope = bool(
        await delegate.count(where={"AND": [wheres[focus_type], {"id": focus_id}]})
    )

    keys: list[str] = []
    place = getattr(row, "place", None)
    location_id = getattr(row, "locationId", None)
    location = await db.location.find_unique(where={"id": location_id}) if location_id else None

    resolved = _group_geography(location, place, level, anchors, degrees)
    if resolved is not None:
        keys.append(resolved[0].key)

    if location is not None and location.latitude is not None and location.longitude is not None:
        keys.append(_capture_key(location.latitude, location.longitude, degrees))

    return {
        "type": focus_type,
        "id": row.id,
        "title": _title(row, focus_type),
        "place": place,
        "pointKeys": keys,
        "inScope": in_scope,
    }


@router.get("/points")
async def map_points(
    current_user: Any = Depends(get_current_user),
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    types: list[str] | None = Query(None),
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    workshopIds: list[str] | None = Query(None),
    level: str | None = None,
    focusType: str | None = None,
    focusId: str | None = None,
) -> dict[str, Any]:
    selected = resolve_types(types)
    admin_level = resolve_admin_level(level)
    degrees = _CLUSTER_DEGREES_BY_LEVEL[admin_level]
    child_level = _CHILD_LEVEL[admin_level]
    child_degrees = _CLUSTER_DEGREES_BY_LEVEL[child_level] if child_level else 0.0
    wheres = await build_record_wheres(
        current_user,
        q=q,
        craft_id=craftId,
        place=place,
        artisan_id=artisanId,
        media_type=mediaType,
        date_from=dateFrom,
        date_to=dateTo,
        workshop_ids=workshopIds,
    )

    counted = [bucket for bucket in RECORD_TYPES if bucket in selected]
    placed = [bucket for bucket in PLACED_TYPES if bucket in selected]

    results = await gather_reads(
        *(_delegate(bucket).count(where=wheres[bucket]) for bucket in counted),
        *(
            _delegate(bucket).group_by(by=["locationId", "place"], count=True, where=wheres[bucket])
            for bucket in placed
        ),
        *(
            _delegate(bucket).group_by(by=["locationId"], count=True, where=wheres[bucket])
            for bucket in counted
            if bucket not in placed
        ),
    )
    split = len(counted)
    totals = dict(zip(counted, results[:split]))
    unplaced_buckets = [bucket for bucket in counted if bucket not in placed]
    grouped = [
        *zip(placed, results[split : split + len(placed)]),
        *zip(unplaced_buckets, results[split + len(placed) :]),
    ]
    per_location, wanted_location_ids, truncated = _locations_in_play(grouped)

    location_rows, anchor_rows = await gather_reads(
        db.location.find_many(where={"id": {"in": wanted_location_ids}}) if wanted_location_ids else _none(),
        db.location.find_many(
            where={"subjectLatitude": {"not": None}, "subjectLongitude": {"not": None}},
            take=_MAX_ANCHOR_ROWS,
            order={"id": "asc"},
        ),
    )
    location_rows = location_rows or []

    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
    anchor_votes = anchors.learn(anchor_rows)

    locations = {row.id: row for row in location_rows}
    origin, unplaced, origin_total = _origin_points(
        grouped, locations, admin_level, anchors, degrees, child_level, child_degrees
    )
    capture, capture_total = _capture_points(
        location_rows, per_location, degrees, child_level, child_degrees
    )

    focus: dict[str, Any] | None = None
    if focusType and focusId:
        focus = await _focus_keys(focusType, focusId, wheres, admin_level, anchors, degrees)

    points = sorted(
        [*origin.values(), *capture.values()],
        key=lambda point: (-point["total"], point["label"]),
    )

    return {
        "scope": "record"
        if focus
        else (
            "filtered"
            if _is_filtered(q, craftId, place, artisanId, mediaType, types, dateFrom, dateTo, workshopIds)
            else "all"
        ),
        "level": admin_level.value,
        "levels": [member.value for member in AdminLevel],
        "childLevel": child_level.value if child_level else None,
        "types": [bucket for bucket in RECORD_TYPES if bucket in selected],
        "points": points,
        "unplaced": unplaced,
        "focus": focus,
        "summary": {
            "records": sum(totals.values()),
            "byType": totals,
            "originRecords": origin_total,
            "captureRecords": capture_total,
            "unplacedRecords": sum(entry["total"] for entry in unplaced),
            "originExcludes": [bucket for bucket in RECORD_TYPES if bucket not in PLACED_TYPES],
            "captureTruncated": truncated,
            "clusterKilometres": round(degrees * 111.32),
            "address": address_completeness(location_rows),
            "anchoredDistricts": anchors.anchored_districts,
            "anchorPins": anchor_votes,
            "anchorsTruncated": len(anchor_rows) >= _MAX_ANCHOR_ROWS,
        },
    }


async def _none() -> None:
    return None


def _is_filtered(*values: Any) -> bool:
    return any(value for value in values)


@router.get("/points/{point_key:path}/records")
async def point_records(
    point_key: str,
    current_user: Any = Depends(get_current_user),
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    types: list[str] | None = Query(None),
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    workshopIds: list[str] | None = Query(None),
    level: str | None = None,
) -> dict[str, Any]:
    selected = resolve_types(types)
    admin_level = resolve_admin_level(level)
    wheres = await build_record_wheres(
        current_user,
        q=q,
        craft_id=craftId,
        place=place,
        artisan_id=artisanId,
        media_type=mediaType,
        date_from=dateFrom,
        date_to=dateTo,
        workshop_ids=workshopIds,
    )

    if point_key.startswith("capture:"):
        narrowed = await _capture_narrowing(point_key, wheres, selected, "latitude", "longitude")
    elif point_key.startswith("pin:"):
        narrowed = await _capture_narrowing(
            point_key, wheres, selected, "subjectLatitude", "subjectLongitude"
        )
    elif parse_stated_key(point_key) is not None:
        narrowed = await _stated_narrowing(point_key, wheres, selected, admin_level)
    else:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=(
                "A point key must be one of 'nation:india', 'state:<State>', "
                "'district:<State>|<District>', 'pin:<size>:<lat cell>_<lon cell>' or "
                "'capture:<size>:<lat cell>_<lon cell>'."
            ),
        )

    buckets = [bucket for bucket in RECORD_TYPES if bucket in selected and bucket in narrowed]
    if not buckets:
        return {"key": point_key, "items": [], "total": 0, "truncated": False}

    rows = await gather_reads(
        *(
            _delegate(bucket).find_many(
                where=narrowed[bucket], take=_STUB_CAP + 1, order={"createdAt": "desc"}
            )
            for bucket in buckets
        )
    )
    truncated = any(len(bucket_rows) > _STUB_CAP for bucket_rows in rows)

    items: list[dict[str, Any]] = []
    for bucket, bucket_rows in zip(buckets, rows):
        for row in bucket_rows[:_STUB_CAP]:
            items.append(
                {
                    "type": bucket,
                    "id": row.id,
                    "title": _title(row, bucket),
                    "place": getattr(row, "place", None),
                    "craft": getattr(row, "craftName", None),
                    "status": str(getattr(row, "status", "")),
                    "createdAt": getattr(row, "createdAt", None),
                }
            )

    items.sort(key=lambda item: (item["createdAt"] is None, item["createdAt"]), reverse=True)
    return {
        "key": point_key,
        "items": items,
        "shown": len(items),
        "total": len(items),
        "cap": _STUB_CAP,
        "truncated": truncated,
    }


async def _stated_narrowing(
    point_key: str,
    wheres: dict[str, dict[str, Any]],
    selected: set[str],
    level: AdminLevel,
) -> dict[str, dict[str, Any]]:
    parsed = parse_stated_key(point_key)
    if parsed is None:
        return {}
    kind, state, district = parsed

    atlas_buckets = [bucket for bucket in PLACED_TYPES if bucket in selected]
    grouped = (
        await gather_reads(
            *(
                _delegate(bucket).group_by(by=["place"], count=True, where=wheres[bucket])
                for bucket in atlas_buckets
            )
        )
        if atlas_buckets
        else []
    )
    spellings_by_bucket: dict[str, list[str]] = {}
    for bucket, groups in zip(atlas_buckets, grouped):
        spellings = [
            group["place"]
            for group in groups
            if _atlas_place_matches(group.get("place"), state, district, kind, level)
        ]
        if spellings:
            spellings_by_bucket[bucket] = spellings

    if kind == "nation":
        placeable: dict[str, dict[str, Any]] = {}
        for bucket in RECORD_TYPES:
            if bucket not in selected:
                continue
            branches: list[dict[str, Any]] = [
                {"location": {"is": {"state": {"not": None}}}},
                {
                    "location": {
                        "is": {
                            "subjectLatitude": {"not": None},
                            "subjectLongitude": {"not": None},
                        }
                    }
                },
            ]
            spellings = spellings_by_bucket.get(bucket)
            if spellings:
                branches.append({"place": {"in": spellings}})
            placeable[bucket] = {"AND": [wheres[bucket], {"OR": branches}]}
        return placeable

    pairs = await db.location.group_by(by=["state", "district"], count=True)
    matching = [
        {"state": raw_state, "district": raw_district}
        for raw_state, raw_district in (
            (group.get("state"), group.get("district")) for group in pairs
        )
        if _pair_belongs(raw_state, raw_district, state, district, kind, level)
    ]
    stated_branch: dict[str, Any] = (
        {"location": {"is": {"OR": matching}}} if matching else {"id": {"in": []}}
    )

    narrowed: dict[str, dict[str, Any]] = {}
    for bucket in RECORD_TYPES:
        if bucket not in selected:
            continue
        branches: list[dict[str, Any]] = [stated_branch]
        spellings = spellings_by_bucket.get(bucket)
        if spellings:
            branches.append(
                {
                    "AND": [
                        {"place": {"in": spellings}},
                        {"OR": [{"locationId": None}, {"location": {"is": {"state": None}}}]},
                    ]
                }
            )
        narrowed[bucket] = {"AND": [wheres[bucket], {"OR": branches}]}
    return narrowed


def _pair_belongs(
    raw_state: str | None,
    raw_district: str | None,
    state: str | None,
    district: str | None,
    kind: str,
    level: AdminLevel,
) -> bool:
    from app.services.address import canonical_district, canonical_state

    pair_state = canonical_state(raw_state)
    if kind == "nation":
        return pair_state is not None
    if pair_state != state:
        return False
    pair_district = canonical_district(pair_state, raw_district) if pair_state else None
    if kind == "district":
        return pair_district == district
    return True if level is AdminLevel.STATE else pair_district is None


def _atlas_place_matches(
    place_text: str | None,
    state: str | None,
    district: str | None,
    kind: str,
    level: AdminLevel,
) -> bool:
    resolved = resolve_place(place_text)
    if resolved.place is None:
        return False
    from app.services.address import canonical_district, canonical_state

    place_state = canonical_state(resolved.place.state)
    if kind == "nation":
        return place_state is not None
    if place_state != state:
        return False
    place_district = (
        canonical_district(place_state, resolved.place.district)
        if place_state and resolved.place.district
        else None
    )
    if kind == "district":
        return place_district == district
    return True if level is AdminLevel.STATE else place_district is None


async def _capture_narrowing(
    point_key: str,
    wheres: dict[str, dict[str, Any]],
    selected: set[str],
    latitude_column: str,
    longitude_column: str,
) -> dict[str, dict[str, Any]]:
    remainder = point_key.split(":", 1)[1]
    try:
        raw_degrees, cell = remainder.split(":", 1)
        degrees = float(raw_degrees)
        cell_latitude, cell_longitude = (int(part) for part in cell.split("_"))
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="A capture point key looks like 'capture:<cell size>:<lat cell>_<lon cell>'.",
        ) from error
    if degrees <= 0:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="A capture cell size must be greater than zero.",
        )

    window = {
        latitude_column: {"gte": cell_latitude * degrees, "lt": (cell_latitude + 1) * degrees},
        longitude_column: {"gte": cell_longitude * degrees, "lt": (cell_longitude + 1) * degrees},
    }
    rows = await db.location.find_many(where=window, take=_MAX_CAPTURE_POINTS, order={"id": "asc"})
    ids = [row.id for row in rows]
    if not ids:
        return {}
    return {
        bucket: {"AND": [wheres[bucket], {"locationId": {"in": ids}}]}
        for bucket in RECORD_TYPES
        if bucket in selected
    }
