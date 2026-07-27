"""Where the documented work comes from, as points a map can draw.

THE ONE THING TO UNDERSTAND BEFORE READING THIS FILE is that the repository holds two different
geographies and this endpoint returns both, in two clearly separated LAYERS, because merging them
produces a map that is confidently wrong.

CAPTURE (``layer: "CAPTURE"``) is the ``Location`` relation: a real GPS fix, taken by the phone at
the moment the record was made. It is the only measured location in the system. Across the live
corpus every fix lands inside a box about 800 m across at the workshop venue, because the artisans
were brought to a workshop and documented there — so at the scale of India this layer is honestly
ONE pin, and it says "this is where the recording happened", not "this is where the craft lives".

ORIGIN (``layer: "ORIGIN"``) is the free-text ``place`` column resolved through
``services/place_atlas``: Bagru, Bareilly, Kachchh, Almora, Jammu. This is the geography a reader
means by "where is this from". It is derived from prose a researcher typed, so it is approximate by
construction and every point carries the ``precision`` that says how approximate.

Neither layer is dropped in favour of the other and neither is silently mixed into the other. A
place the atlas cannot resolve is returned in ``unplaced`` with its counts, because a place missing
from a map looks exactly like a place with no records.

VISIBILITY. Every count and every record stub here comes out of a ``where`` built by
``services/record_filters``, which folds in the same ``visibility_where`` predicate the list routes
use — media through ``uploadedById``, everything else through ``createdById``. There is no second
rule and no place to forget one: this module never builds a bare ``where`` of its own. A record the
caller could not open therefore cannot reach a pin, and because of that the record stubs may safely
carry the artisan's NAME. Nothing else identifying travels: Aadhaar and Pehchan numbers are not read
from the database at all, let alone serialised.
"""

from __future__ import annotations

import math
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import get_current_user
from app.services.concurrency import gather_reads
from app.services.place_atlas import resolve_place
from app.services.record_filters import (
    PLACED_TYPES,
    RECORD_TYPES,
    build_record_wheres,
    resolve_types,
)

router = APIRouter(prefix="/map", tags=["map"])

# The delegate and the column that holds each bucket's display name. One table rather than five
# branches, because every loop below needs the same two facts about a bucket.
_BUCKETS: dict[str, tuple[str, tuple[str, ...]]] = {
    "artisans": ("artisan", ("name",)),
    "workshops": ("workshop", ("title",)),
    "products": ("productdocumentation", ("productName",)),
    "tools": ("tooldocumentation", ("toolkitName", "englishName")),
    "media": ("mediafile", ("originalFilename",)),
}


def _delegate(bucket: str) -> Any:
    return getattr(db, _BUCKETS[bucket][0])


# How far apart two GPS fixes may be and still be drawn as one pin: about 25 km, which at the scale
# a whole-country map is rendered at is roughly the size of the pin itself. Two fixes closer than
# this cannot be told apart by eye, so drawing them separately would only stack them — see the
# `fixes` and `spreadMetres` fields, which is how a folded pin admits what it contains.
_CLUSTER_DEGREES = 0.25

# A hard ceiling on how many distinct capture points one request will fold. The live corpus has a
# few hundred; this exists so that a repository which one day holds a hundred thousand located media
# files degrades into a truncated map with a flag set, rather than into an out-of-memory kill on a
# 1 GiB box.
_MAX_CAPTURE_POINTS = 4000

# Record stubs returned when a caller opens one pin. A pin is a way IN to the records, not a second
# list view — past this many the panel links to Browse records with the same filters applied.
_STUB_CAP = 40

# The grouping key for records whose place column is empty. Only ever a dictionary key — what
# reaches the client is the "No place recorded" label beside it.
_BLANK_PLACE = "(blank)"


def _title(row: Any, bucket: str) -> str:
    for column in _BUCKETS[bucket][1]:
        value = getattr(row, column, None)
        if value:
            return str(value)
    return "Untitled record"


def _cell(latitude: float, longitude: float) -> tuple[int, int]:
    return (
        math.floor(latitude / _CLUSTER_DEGREES),
        math.floor(longitude / _CLUSTER_DEGREES),
    )


def _metres_across(points: list[tuple[float, float]]) -> int:
    """How much ground the fixes folded into one pin actually cover — the diagonal of their extent.

    The bounding box rather than the widest pair: it is one pass instead of every pair, which at the
    cluster ceiling is four thousand operations instead of eight million, and for a caption reading
    "these fixes span 813 m" the two answers are the same number.

    Equirectangular rather than haversine because these points are already known to be within a
    quarter of a degree of each other, where the two agree to well under a metre.
    """
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


def _origin_points(
    grouped: list[tuple[str, list[Any]]],
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]], int]:
    """Fold every bucket's ``place`` groups into atlas points, plus what would not resolve.

    Takes the grouped counts rather than fetching them, so the route can put every read it needs
    into one wave — see the comment above ``gather_reads`` in :func:`map_points`.

    The counts come from ``group_by`` rather than from reading rows: the question is "how many
    records per place", the answer is a couple of dozen rows whatever the corpus size, and reading
    the records to count them would pull every media transcript in the repository across the wire.
    """
    points: dict[str, dict[str, Any]] = {}
    unplaced: dict[str, dict[str, Any]] = {}
    placed_total = 0

    for bucket, groups in grouped:
        for group in groups:
            raw = group.get("place")
            count = group["_count"]["_all"]
            resolved = resolve_place(raw)
            label = (raw or "").strip()
            if resolved.place is None:
                # Blank and unreadable place strings are both real states of this data — five
                # interviews hold a single replacement character — so they are reported as one
                # honest "not recorded" bucket rather than as a pin somewhere plausible.
                key = label.lower() or _BLANK_PLACE
                entry = unplaced.setdefault(
                    key,
                    {"label": label or "No place recorded", "total": 0, "counts": _empty_counts()},
                )
                entry["total"] += count
                entry["counts"][bucket] += count
                continue

            place = resolved.place
            key = f"origin:{place.key}"
            point = points.setdefault(
                key,
                {
                    "key": key,
                    "layer": "ORIGIN",
                    "label": place.label,
                    "region": place.region,
                    "state": place.state,
                    "latitude": place.latitude,
                    "longitude": place.longitude,
                    "precision": place.precision.value,
                    "total": 0,
                    "counts": _empty_counts(),
                    "spellings": [],
                },
            )
            point["total"] += count
            point["counts"][bucket] += count
            placed_total += count
            if label and label not in point["spellings"]:
                point["spellings"].append(label)

    for point in points.values():
        # Longest first: "Bagru, Jaipur, Rajasthan" tells a reader more about what was typed than
        # "Bagru" does, and the panel shows only the first few.
        point["spellings"].sort(key=lambda spelling: (-len(spelling), spelling))

    return points, sorted(unplaced.values(), key=lambda entry: -entry["total"]), placed_total


def _locations_in_play(grouped: list[tuple[str, list[Any]]]) -> tuple[dict[str, dict[str, int]], bool]:
    """Which ``Location`` rows the filtered corpus touches, and how many records sit on each."""
    per_location: dict[str, dict[str, int]] = {}
    for bucket, groups in grouped:
        for group in groups:
            location_id = group.get("locationId")
            if not location_id:
                continue
            counts = per_location.setdefault(location_id, _empty_counts())
            counts[bucket] += group["_count"]["_all"]

    truncated = len(per_location) > _MAX_CAPTURE_POINTS
    if truncated:
        # Keep the busiest fixes. A truncated map must still be a map of the main places rather
        # than of whichever ids the database happened to return first, and `truncated` travels to
        # the client so the shortfall is stated rather than inferred.
        busiest = sorted(per_location.items(), key=lambda item: -sum(item[1].values()))
        per_location = dict(busiest[:_MAX_CAPTURE_POINTS])
    return per_location, truncated


def _capture_points(
    rows: list[Any], per_location: dict[str, dict[str, int]]
) -> tuple[dict[str, dict[str, Any]], int]:
    """Cluster the GPS fixes into pins a reader can tell apart."""
    clusters: dict[tuple[int, int], dict[str, Any]] = {}
    captured_total = 0
    for row in rows:
        latitude = getattr(row, "latitude", None)
        longitude = getattr(row, "longitude", None)
        if latitude is None or longitude is None:
            continue
        counts = per_location.get(row.id)
        if counts is None:
            continue
        cluster = clusters.setdefault(
            _cell(latitude, longitude),
            {"fixes": [], "counts": _empty_counts(), "total": 0, "accuracies": []},
        )
        cluster["fixes"].append((latitude, longitude))
        accuracy = getattr(row, "accuracy", None)
        if accuracy is not None:
            cluster["accuracies"].append(float(accuracy))
        for bucket, value in counts.items():
            cluster["counts"][bucket] += value
            cluster["total"] += value
            captured_total += value

    points: dict[str, dict[str, Any]] = {}
    for (cell_latitude, cell_longitude), cluster in clusters.items():
        fixes = cluster["fixes"]
        # The pin sits on the MEAN of the fixes it folds, not on the corner of the grid cell that
        # happened to catch them — a cell corner is an artefact of the clustering, the mean is a
        # place. `spreadMetres` then says how much ground the pin is standing in for.
        latitude = sum(value for value, _ in fixes) / len(fixes)
        longitude = sum(value for _, value in fixes) / len(fixes)
        key = f"capture:{cell_latitude}_{cell_longitude}"
        accuracies = cluster["accuracies"]
        points[key] = {
            "key": key,
            "layer": "CAPTURE",
            "label": "Recorded here",
            "region": f"{latitude:.4f}, {longitude:.4f}",
            "state": None,
            "latitude": latitude,
            "longitude": longitude,
            "precision": "MEASURED",
            "total": cluster["total"],
            "counts": cluster["counts"],
            # How many separate GPS fixes this one pin is standing in for, and how much ground they
            # cover. Without these a pin reading "317 records" looks like a single measurement.
            "fixes": len(fixes),
            "spreadMetres": _metres_across(fixes),
            "medianAccuracy": round(sorted(accuracies)[len(accuracies) // 2], 1) if accuracies else None,
        }

    return points, captured_total


async def _focus_keys(
    focus_type: str, focus_id: str, wheres: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    """The one record a caller asked to see in context, and the point keys it sits on.

    The record is loaded through the SAME visibility-bearing where as everything else, so asking to
    focus a record you may not read is a 404 — the same answer as a record that does not exist,
    which is the only answer that does not confirm it exists.
    """
    if focus_type not in _BUCKETS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unknown record type '{focus_type}'. Valid types are {', '.join(RECORD_TYPES)}.",
        )
    where = {"AND": [wheres[focus_type], {"id": focus_id}]}
    row = await _delegate(focus_type).find_first(where=where)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    keys: list[str] = []
    place = getattr(row, "place", None)
    resolved = resolve_place(place)
    if resolved.place is not None:
        keys.append(f"origin:{resolved.place.key}")

    location_id = getattr(row, "locationId", None)
    if location_id:
        location = await db.location.find_unique(where={"id": location_id})
        if location is not None:
            cell_latitude, cell_longitude = _cell(location.latitude, location.longitude)
            keys.append(f"capture:{cell_latitude}_{cell_longitude}")

    return {
        "type": focus_type,
        "id": row.id,
        "title": _title(row, focus_type),
        "place": place,
        "pointKeys": keys,
    }


@router.get("/points")
async def map_points(
    current_user: Any = Depends(get_current_user),
    # The repository's shared filter vocabulary, spelled exactly as `GET /search` spells it, because
    # the web sends both from one `searchFilterParams()`. A map that answered "Bagru, last 30 days"
    # differently from the search box would leave no way to tell which of the two was lying.
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    types: list[str] | None = Query(None),
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # The single-record scope. The map still draws the whole filtered corpus — "in context" is meant
    # literally — and simply names which points hold this record.
    focusType: str | None = None,
    focusId: str | None = None,
) -> dict[str, Any]:
    selected = resolve_types(types)
    wheres = await build_record_wheres(
        current_user,
        q=q,
        craft_id=craftId,
        place=place,
        artisan_id=artisanId,
        media_type=mediaType,
        date_from=dateFrom,
        date_to=dateTo,
    )

    counted = [bucket for bucket in RECORD_TYPES if bucket in selected]
    # Only these four have a `place` column at all — media inherits the record it belongs to — so
    # asking the other bucket for one would be a query that cannot answer anything.
    placed = [bucket for bucket in PLACED_TYPES if bucket in selected]

    # EVERY read this endpoint needs before it knows anything goes out in ONE wave. The database is
    # in another AWS region and a round trip costs roughly 750ms against queries that execute in
    # well under a millisecond, so what decides whether this page is fast is purely how many reads
    # wait on each other. Three separate waves — totals, then places, then locations — cost the same
    # three round trips whether the corpus holds a hundred rows or a million.
    results = await gather_reads(
        *(_delegate(bucket).count(where=wheres[bucket]) for bucket in counted),
        *(
            _delegate(bucket).group_by(by=["place"], count=True, where=wheres[bucket])
            for bucket in placed
        ),
        *(
            _delegate(bucket).group_by(
                by=["locationId"],
                count=True,
                where={**wheres[bucket], "locationId": {"not": None}},
            )
            for bucket in counted
        ),
    )
    split = len(counted)
    totals = dict(zip(counted, results[:split]))
    origin, unplaced, origin_total = _origin_points(
        list(zip(placed, results[split:split + len(placed)]))
    )
    per_location, truncated = _locations_in_play(
        list(zip(counted, results[split + len(placed):]))
    )

    # The second and last wave. The location rows cannot be asked for until the grouped counts above
    # have said which ones matter, and the focused record's own fix is looked up in the same breath.
    location_rows = (
        await db.location.find_many(where={"id": {"in": list(per_location)}}) if per_location else []
    )
    capture, capture_total = _capture_points(location_rows, per_location)

    focus: dict[str, Any] | None = None
    if focusType and focusId:
        focus = await _focus_keys(focusType, focusId, wheres)

    points = sorted(
        [*origin.values(), *capture.values()],
        key=lambda point: (-point["total"], point["label"]),
    )

    return {
        "scope": "record" if focus else ("filtered" if _is_filtered(q, craftId, place, artisanId, mediaType, types, dateFrom, dateTo) else "all"),
        "types": [bucket for bucket in RECORD_TYPES if bucket in selected],
        "points": points,
        "unplaced": unplaced,
        "focus": focus,
        "summary": {
            "records": sum(totals.values()),
            "byType": totals,
            # Two different denominators, deliberately not added together: one record can appear in
            # both layers (its craft is from Bagru AND it was recorded at Kharagpur), so a single
            # "on the map" number would either double-count it or hide one of its two truths.
            "originRecords": origin_total,
            "captureRecords": capture_total,
            "unplacedRecords": sum(entry["total"] for entry in unplaced),
            # Media has no `place` column of its own — a photograph inherits the record it belongs
            # to — so it can never contribute to the ORIGIN layer. Stated rather than left as an
            # unexplained gap between the two totals.
            "originExcludes": [bucket for bucket in RECORD_TYPES if bucket not in PLACED_TYPES],
            "captureTruncated": truncated,
        },
    }


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
) -> dict[str, Any]:
    """The records behind one pin, so a pin can be navigated FROM rather than only looked at.

    Fetched on demand instead of being carried by ``/points``. The aggregate answer is a couple of
    dozen rows and is what the map draws; the records behind a pin are only wanted for the one pin a
    reader opens, and shipping all of them up front would put the whole corpus in a payload that
    exists to draw thirteen dots.
    """
    selected = resolve_types(types)
    wheres = await build_record_wheres(
        current_user,
        q=q,
        craft_id=craftId,
        place=place,
        artisan_id=artisanId,
        media_type=mediaType,
        date_from=dateFrom,
        date_to=dateTo,
    )

    if point_key.startswith("origin:"):
        narrowed = await _origin_narrowing(point_key, wheres, selected)
    elif point_key.startswith("capture:"):
        narrowed = await _capture_narrowing(point_key, wheres, selected)
    else:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A point key must start with 'origin:' or 'capture:'.",
        )

    buckets = [bucket for bucket in RECORD_TYPES if bucket in selected and bucket in narrowed]
    if not buckets:
        return {"key": point_key, "items": [], "total": 0, "truncated": False}

    rows = await gather_reads(
        *(
            _delegate(bucket).find_many(
                where=narrowed[bucket], take=_STUB_CAP, order={"createdAt": "desc"}
            )
            for bucket in buckets
        )
    )

    items: list[dict[str, Any]] = []
    for bucket, bucket_rows in zip(buckets, rows):
        for row in bucket_rows:
            # Hand-picked columns, never the whole row. The artisan's NAME is safe here because the
            # where this row came through already carried `visibility_where` — but Aadhaar and
            # Pehchan are never in this list whatever the caller's rank, because a map has no
            # business carrying them.
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
        "total": len(items),
        "truncated": any(len(bucket_rows) == _STUB_CAP for bucket_rows in rows),
    }


async def _origin_narrowing(
    point_key: str, wheres: dict[str, dict[str, Any]], selected: set[str]
) -> dict[str, dict[str, Any]]:
    """Narrow each bucket to the exact spellings that resolve to this atlas point.

    The point key cannot be inverted into place strings — three spellings of Bareilly all resolve to
    one key and nothing records which three. So the same grouped read the map itself ran is repeated
    and filtered, which is one cheap round trip and, more importantly, cannot disagree with the
    counts the pin is showing.
    """
    wanted = point_key.split(":", 1)[1]
    buckets = [bucket for bucket in PLACED_TYPES if bucket in selected]
    if not buckets:
        return {}
    grouped = await gather_reads(
        *(
            _delegate(bucket).group_by(by=["place"], count=True, where=wheres[bucket])
            for bucket in buckets
        )
    )
    narrowed: dict[str, dict[str, Any]] = {}
    for bucket, groups in zip(buckets, grouped):
        spellings = [
            group["place"]
            for group in groups
            if (resolved := resolve_place(group.get("place"))).place is not None
            and resolved.place.key == wanted
        ]
        if spellings:
            narrowed[bucket] = {"AND": [wheres[bucket], {"place": {"in": spellings}}]}
    return narrowed


async def _capture_narrowing(
    point_key: str, wheres: dict[str, dict[str, Any]], selected: set[str]
) -> dict[str, dict[str, Any]]:
    """Narrow each bucket to the ``Location`` rows inside this pin's grid cell."""
    try:
        cell_latitude, cell_longitude = (int(part) for part in point_key.split(":", 1)[1].split("_"))
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A capture point key looks like 'capture:<lat cell>_<lon cell>'.",
        ) from error

    # The cell as a coordinate window, so the database does the narrowing rather than a scan of
    # every Location row in the repository.
    window = {
        "latitude": {
            "gte": cell_latitude * _CLUSTER_DEGREES,
            "lt": (cell_latitude + 1) * _CLUSTER_DEGREES,
        },
        "longitude": {
            "gte": cell_longitude * _CLUSTER_DEGREES,
            "lt": (cell_longitude + 1) * _CLUSTER_DEGREES,
        },
    }
    rows = await db.location.find_many(where=window, take=_MAX_CAPTURE_POINTS)
    ids = [row.id for row in rows]
    if not ids:
        return {}
    return {
        bucket: {"AND": [wheres[bucket], {"locationId": {"in": ids}}]}
        for bucket in RECORD_TYPES
        if bucket in selected
    }
