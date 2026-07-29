"""Where the documented work comes from, as points a map can draw.

THE TWO GEOGRAPHIES. The repository holds two different ones and this endpoint returns both, in two
clearly separated LAYERS, because merging them produces a map that is confidently wrong.

CAPTURE (``layer: "CAPTURE"``) is the DEVICE'S fix: ``Location.latitude``/``longitude``, taken by the
phone at the moment the record was made. It is a measurement. Across the live corpus every fix lands
inside a box about 800 m across at the workshop venue, because the artisans were brought to a workshop
and documented there — so at the scale of India this layer is honestly ONE pin, and it says "this is
where the recording happened", not "this is where the craft lives".

ORIGIN (``layer: "ORIGIN"``) is where the SUBJECT is. This is the geography a reader means by "where
is this from", and it is the layer that changed: it is now built from the STRUCTURED ADDRESS the
records already carry — ``Location.state``, ``Location.district``, and the ``subjectLatitude`` /
``subjectLongitude`` pin a researcher drops on the subject's own place — through
``services/geography``. The free-text ``place`` column and its hand-curated ``services/place_atlas``
table survive only as the LAST fallback, for the legacy rows whose location was never anything but
prose.

WHY THAT MATTERS MORE THAN IT SOUNDS. The atlas holds thirteen towns, entered by hand. While it was
the PRIMARY source, documenting a craft in a fourteenth town meant editing Python and shipping a
deploy before the fourteenth town could appear as a dot — the map was permanently behind the data, and
silently so. Reading the structured address instead means a record plots because its researcher picked
a state and a district from dropdowns that already hold all 36 states and all 795 districts. Nothing
has to be populated by hand ever again. Read the header of ``services/geography`` for the full
resolution ladder and for why district positions are LEARNED from real pins rather than tabulated.

THE DETAIL LEVEL. ``?level=NATION|STATE|DISTRICT`` (default DISTRICT) chooses the administrative unit
both layers are grouped at — the three units an Indian address actually has above a village, each one
a real thing you could draw a border around. It changes the GROUPING and the capture-cluster radius;
it never upgrades a looked-up position into a measured one. Every point carries the ``precision`` and
``source`` that say which it is.

Neither layer is dropped in favour of the other and neither is silently mixed into the other. An
address that cannot be placed at all is returned in ``unplaced`` with its counts, because a place
missing from a map looks exactly like a place with no records.

VISIBILITY. Every count and every record stub here comes out of a ``where`` built by
``services/record_filters``, which folds in the same ``viewable_where`` predicate the list routes
use — media through ``uploadedById``, everything else through ``createdById``. There is no second
rule and no place to forget one: this module never builds a bare ``where`` of its own. So a pin can
show exactly what ``GET /search`` would list for the same filters and never more, which is the whole
reason the two share a filter module — including the workshop scope, so "the records from these two
workshops" means the same set of rows on the map as in the search box.

That predicate is empty today — reading the repository is open to every signed-in account — so the
map draws the whole corpus for everybody, which is the point of a map of where the work comes from.
What the stubs carry is bounded HERE instead, and deliberately: the artisan's NAME travels, because a
pin listing "1 artisan" that cannot be named is not usable; Aadhaar and Pehchan numbers are never
read from the database at all, let alone serialised, whatever the caller's rank.
"""

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


# How far apart two GPS fixes may be and still be drawn as one pin, PER DETAIL LEVEL.
#
# The radius follows the level because the two are the same question asked of the two layers. At
# DISTRICT level a quarter of a degree is about 25 km — town scale, and at the size a whole-country
# map is drawn at, roughly the size of the pin itself, so two closer fixes could not be told apart by
# eye anyway. At STATE level a degree (~110 km) is state scale; at NATION level five degrees folds the
# country. Without this the capture layer would keep drawing town-scale dots under a national grouping,
# and the toggle would look like it only half worked.
#
# See the `fixes` and `spreadMetres` fields on every capture point, which is how a folded pin admits
# what it contains rather than pretending to be one measurement.
_CLUSTER_DEGREES_BY_LEVEL: dict[AdminLevel, float] = {
    AdminLevel.DISTRICT: 0.25,
    AdminLevel.STATE: 1.0,
    AdminLevel.NATION: 5.0,
}

# WHAT A POINT'S DROPDOWN LISTS: the level immediately below the one being drawn.
#
# At NATION level the whole country is ONE dot and therefore one row, and at STATE level a state is one
# dot and one row. A reader who clicks either has nowhere to go: the row they land on is the row they
# already had. So each point carries the finer breakdown of ITSELF — the states inside the nation, the
# districts inside the state — and the list renders it as a disclosure the reader can open. DISTRICT has
# no entry because a district is the finest unit an Indian address names; there is nothing below it to
# list, and inventing "villages" from free text would be a list of spellings, not places.
#
# It costs NO extra database read. Every group is already in hand with its counts, and placing a group is
# a pure function of (location, place text, level) — so the children are the same fold run a second time
# at a second level, over the same rows.
_CHILD_LEVEL: dict[AdminLevel, AdminLevel | None] = {
    AdminLevel.NATION: AdminLevel.STATE,
    AdminLevel.STATE: AdminLevel.DISTRICT,
    AdminLevel.DISTRICT: None,
}

# How many children one point carries. A nation has at most 36 states so it can never reach this; a
# state has at most 795 districts, and a scope that somehow touched hundreds of them would be a
# disclosure nobody scrolls. Truncation is FLAGGED on the point (``childrenTruncated``) rather than
# silent, because a list that quietly stops is indistinguishable from a place with no records.
_MAX_CHILDREN = 60

# A hard ceiling on how many distinct capture points one request will fold. The live corpus has a
# few hundred; this exists so that a repository which one day holds a hundred thousand located media
# files degrades into a truncated map with a flag set, rather than into an out-of-memory kill on a
# 1 GiB box.
_MAX_CAPTURE_POINTS = 4000

# How many ``Location`` rows one request will READ for their stated address. Deliberately far above
# ``_MAX_CAPTURE_POINTS``: see ``_locations_in_play`` for why the two limits must not be the same one.
# A Location row is small — no long text, no blobs — so this bound is about the wire, not memory.
_MAX_LOCATION_ROWS = 20000

# How many ``Location`` rows are read to LEARN where districts are (see
# ``geography.DistrictAnchors``). Only rows that actually carry a subject pin are read, which is a
# small subset — and the cap means a repository of a million locations degrades into anchors learned
# from the first few thousand pins rather than into a request that never returns.
_MAX_ANCHOR_ROWS = 5000

# Record stubs returned when a caller opens one pin. A pin is a way IN to the records, not a second
# list view — past this many the panel links to Browse records with the same filters applied.
_STUB_CAP = 40

# The grouping key for records whose address says nothing placeable. Only ever a dictionary key —
# what reaches the client is the "No place recorded" label beside it.
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
    """A capture pin's key. The CLUSTER SIZE IS IN THE KEY, and it has to be.

    The key is what the client sends back to ask for the records behind a pin, and it is inverted into
    a coordinate window by ``_capture_narrowing``. Two different levels produce two different windows
    for the same cell integers, so a key that omitted the size would be ambiguous the moment the
    toggle moved — the panel would open the records of a 25 km box while the map showed a 110 km one.
    """
    cell_latitude, cell_longitude = _cell(latitude, longitude, degrees)
    # Formatted rather than repr'd so 0.25 is "0.25" and not "0.25000000000000006" on some future
    # value, and so the key is stable across Python versions.
    return f"capture:{degrees:g}:{cell_latitude}_{cell_longitude}"


def _metres_across(points: list[tuple[float, float]]) -> int:
    """How much ground the fixes folded into one pin actually cover — the diagonal of their extent.

    The bounding box rather than the widest pair: it is one pass instead of every pair, which at the
    cluster ceiling is four thousand operations instead of eight million, and for a caption reading
    "these fixes span 813 m" the two answers are the same number.

    Equirectangular rather than haversine because these points are within a few degrees of each other,
    where the two agree closely enough for a caption.
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


# ---------------------------------------------------------------------------------------------
# The ORIGIN layer, built from the structured address
# ---------------------------------------------------------------------------------------------


def _group_geography(
    location: Any | None,
    place_text: str | None,
    level: AdminLevel,
    anchors: DistrictAnchors,
    degrees: float,
) -> tuple[Any, str] | None:
    """Place one (location, place-text) group, and say which source placed it.

    THE LADDER, in this order and for this reason:

    1. The STATED ADDRESS on the record's ``Location`` — state, district, and the subject pin. This is
       structured, closed-list data a person entered about the SUBJECT, so it is both the most precise
       thing available and the thing that grows without code changes.
    2. The free-text ``place`` column through ``place_atlas``. Prose, thirteen hand-entered towns, and
       the only location the legacy rows have. Kept so those rows still appear; never preferred, so a
       record with a real address is never placed by a guess at its prose.

    The atlas result is fed through the SAME ``geography.stated_point`` as the stated address, by
    handing it the atlas town's own (state, district, coordinates). That is what makes the detail level
    work uniformly: at STATE level a Bagru record and a Kachchh record fold into Rajasthan and Gujarat
    without this function knowing anything about levels. ``pin_precision``/``pin_source`` keep the
    result honest — an atlas town reports TOWN precision from a place NAME, not the SUBJECT_PIN
    precision a dropped pin would.
    """
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

    # A DROPPED PIN WITH NO ADMINISTRATIVE NAME. Last in the ladder and easy to overlook, because it is
    # the only rung whose input is MORE precise than the two above it.
    #
    # ``stated_point`` needs a canonical state to build an administrative key, so a Location holding
    # only a subject pin returns None from it — and until this rung existed, the most precisely located
    # record in the repository was reported as UNPLACED while a record that merely named a state got a
    # dot. The CAPTURE layer does not rescue it either: capture draws the DEVICE'S fix, which is a
    # different fact and is very often a desk in another state.
    #
    # It is reachable: the web and Android forms now write the state from a reverse geocode whenever a
    # pin is dropped, but that needs a network, and a researcher pinning a village with no signal saves
    # the coordinate and no names. So the pin is drawn as itself, clustered at the level's own radius,
    # and SAYS it has no administrative name.
    if location is not None:
        latitude = getattr(location, "subjectLatitude", None)
        longitude = getattr(location, "subjectLongitude", None)
        if latitude is not None and longitude is not None:
            return _pin_point(float(latitude), float(longitude), degrees), "PIN"

    return None


def _pin_key(latitude: float, longitude: float, degrees: float) -> str:
    """A subject-pin cluster's key. Same shape and the same reasoning as :func:`_capture_key`."""
    cell_latitude, cell_longitude = _cell(latitude, longitude, degrees)
    return f"pin:{degrees:g}:{cell_latitude}_{cell_longitude}"


def _pin_point(latitude: float, longitude: float, degrees: float) -> StatedPoint:
    """One coordinate-only ORIGIN point: where somebody pointed, with no name to call it."""
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
    """One entry in a point's dropdown: the same identity a real point has, minus what only a pin needs.

    It carries a REAL point key at ``level``, which is the property that makes the dropdown more than
    decoration — a client can hand the key straight back to ``GET /map/points?level=<level>`` and to
    ``/map/points/{key}/records`` and get the same answer it would have got by using the level toggle. So
    opening a state inside the nation and then choosing a district inside it is the same navigation as
    switching the toggle twice, and lands on the same pin.

    No ``places``/``pinnedRecords``/``fromPlaceText``: those explain how a DRAWN pin came to be where it
    is, and a child is not drawn. Its position is still the weighted mean of what folds into it, so it is
    honest if a future version does draw it.
    """
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
    """Attach each point's dropdown, positioned and ordered. Mutates ``points``.

    Ordered by count then label — the SAME order the drawn points use — so the busiest place is first in
    both readings and the list beside the map cannot be sorted differently from the map.

    A point with exactly one child gets an EMPTY list rather than a one-item dropdown. A disclosure whose
    only content restates the row it hangs under is a control that does nothing, and a reader who opens
    one learns to stop opening them.
    """
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
    """Fold every bucket's (locationId, place) groups into ORIGIN points, plus what would not place.

    Takes the grouped counts rather than fetching them, so the route can put every read it needs into
    one wave — see the comment above ``gather_reads`` in :func:`map_points`.

    The counts come from ``group_by`` rather than from reading rows: the question is "how many records
    per address", the answer is a couple of dozen rows whatever the corpus size, and reading the
    records to count them would pull every media transcript in the repository across the wire.

    A POINT'S POSITION IS THE WEIGHTED MEAN of what folds into it, not wherever the first group landed.
    At DISTRICT level a district holding a pinned village and an unpinned record draws between them,
    weighted by record count; at STATE level a state whose work is all in one corner draws in that
    corner rather than at its capital. ``pinnedRecords`` says how much of the position is measurement.
    """
    points: dict[str, dict[str, Any]] = {}
    unplaced: dict[str, dict[str, Any]] = {}
    placed_total = 0
    # (key) -> list of (latitude, longitude, weight), folded into the mean at the end.
    positions: dict[str, list[tuple[float, float, int]]] = {}
    # parentKey -> childKey -> the child shell, plus its own positions to average.
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
                # An address that says nothing placeable. Blank, unreadable and "typed something the
                # atlas cannot resolve and no state was picked" are all real states of this data —
                # five interviews hold a single replacement character — so they are reported as one
                # honest "not recorded" bucket rather than as a pin somewhere plausible.
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
                    # Every free-text spelling and every finer place name that folded in here. At
                    # DISTRICT level a Jaipur pin says "Bagru, Sanganer", so grouping to the district
                    # loses no information — it moves it into the panel.
                    "places": [],
                    # How many of these records are positioned by a real coordinate rather than by a
                    # lookup. The difference between "we measured this" and "we know the district".
                    "pinnedRecords": 0,
                    # How many records reached this pin through the legacy free-text column rather
                    # than through a structured address. A reader chasing "why is this coarse" needs
                    # to know which records to go and fill in.
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

            # THE SAME GROUP, PLACED AGAIN ONE LEVEL DOWN. Pure computation over rows already in hand —
            # no read, no second wave — because ``_group_geography`` is a function of (location, prose,
            # level) and only the level changes here.
            #
            # The child ladder can legitimately land on the SAME key as its parent: at DISTRICT level a
            # group that names a state and no district keys as ``state:<name>``, which is exactly what
            # "in Rajasthan, district not stated" means and is precisely how the drawn map behaves at
            # that level too. It is kept rather than filtered, because dropping it would make a state's
            # dropdown silently undercount the records inside the state.
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
        # Longest first: "Bagru, Jaipur, Rajasthan" tells a reader more about what was typed than
        # "Bagru" does, and the panel shows only the first few.
        point["places"].sort(key=lambda name: (-len(name), name))

    _finish_children(children, child_positions, points)

    return points, sorted(unplaced.values(), key=lambda entry: -entry["total"]), placed_total


def _locations_in_play(
    grouped: list[tuple[str, list[Any]]],
) -> tuple[dict[str, dict[str, int]], list[str], bool]:
    """Which ``Location`` rows the filtered corpus touches, and how many records sit on each.

    Returns three things, and the second is the one worth reading twice:

      ``per_location``  {locationId: {bucket: count}} for the CAPTURE layer, capped at
                        ``_MAX_CAPTURE_POINTS``.
      ``wanted_ids``    which Location rows to READ, capped separately at ``_MAX_LOCATION_ROWS``.
      ``truncated``     whether the capture cap actually bit.

    WHY TWO CAPS RATHER THAN ONE. The Location rows are now read for TWO jobs: their fix feeds the
    capture layer, and their STATED ADDRESS feeds the origin layer. Those jobs want different limits.
    A capture cap exists so a hundred thousand located media files degrade into a truncated map rather
    than an out-of-memory kill; but if the ORIGIN layer read only that capped set, every record whose
    location fell outside it would silently drop down the ladder to its free-text place — or to
    UNPLACED — and be drawn somewhere else entirely. A record moving to a different district because
    an unrelated cap trimmed a list is precisely the kind of quiet wrongness this endpoint is built to
    avoid, so the read is bounded by its own, much larger limit and the two never interfere.
    """
    per_location: dict[str, dict[str, int]] = {}
    for bucket, groups in grouped:
        for group in groups:
            location_id = group.get("locationId")
            if not location_id:
                continue
            counts = per_location.setdefault(location_id, _empty_counts())
            counts[bucket] += group["_count"]["_all"]

    # Busiest first, once, and used for BOTH caps: a truncated map must be a map of the main places
    # rather than of whichever ids the database happened to return first.
    busiest = sorted(per_location.items(), key=lambda item: -sum(item[1].values()))
    wanted_ids = [location_id for location_id, _ in busiest[:_MAX_LOCATION_ROWS]]

    truncated = len(per_location) > _MAX_CAPTURE_POINTS
    if truncated:
        # `truncated` travels to the client so the shortfall is stated rather than inferred.
        per_location = dict(busiest[:_MAX_CAPTURE_POINTS])
    return per_location, wanted_ids, truncated


def _capture_points(
    rows: list[Any],
    per_location: dict[str, dict[str, int]],
    degrees: float,
    child_level: AdminLevel | None = None,
    child_degrees: float = 0.0,
) -> tuple[dict[str, dict[str, Any]], int]:
    """Cluster the GPS fixes into pins a reader can tell apart, at this level's radius.

    A CAPTURE pin's dropdown is the same fixes re-clustered at the FINER radius, which is what makes the
    two layers behave alike under the same control: at NATION level a five-degree cell folds the country
    into one dot, and opening it says "these are the three venues inside it" rather than leaving a reader
    to guess whether one pin means one place. The children are labelled by their coordinate because a
    measured fix has no administrative name to borrow — that is the whole distinction between this layer
    and ORIGIN, and inventing a name here would erase it.
    """
    clusters: dict[tuple[int, int], dict[str, Any]] = {}
    captured_total = 0
    # parent cell -> child cell -> the child's fixes and counts. Built in the SAME pass, so the finer
    # clustering costs one extra integer division per row rather than a second walk over the corpus.
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
        # The pin sits on the MEAN of the fixes it folds, not on the corner of the grid cell that
        # happened to catch them — a cell corner is an artefact of the clustering, the mean is a
        # place. `spreadMetres` then says how much ground the pin is standing in for.
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
            # How many separate GPS fixes this one pin is standing in for, and how much ground they
            # cover. Without these a pin reading "317 records" looks like a single measurement.
            "fixes": len(fixes),
            "spreadMetres": _metres_across(fixes),
            "medianAccuracy": round(sorted(accuracies)[len(accuracies) // 2], 1) if accuracies else None,
        }

        # The finer clusters inside this pin, each keyed as it WOULD be keyed at the child level — the
        # cluster size is part of a capture key (see ``_capture_key``), so these keys are directly usable
        # against ``/map/points/{key}/records?level=<child>`` exactly like the ORIGIN children are.
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
        # One child is the pin restated, so it is not a dropdown — see ``_finish_children`` for why a
        # disclosure that only repeats its own row teaches a reader to stop opening them.
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
    """The one record a caller asked to see in context, and the point keys it sits on.

    THE RECORD IS LOOKED UP BY ID, NOT THROUGH THE CALLER'S FILTERS, and that distinction is the whole
    bug this signature used to carry. It once resolved through ``wheres[focus_type]`` — the fully
    narrowed clause — on the reasoning that focusing a record you may not read should 404. That
    reasoning is spent twice over: reading is open to every signed-in account, so there is nothing to
    conceal; and ``wheres`` now also carries the WORKSHOP SCOPE, which the map applies BY DEFAULT.

    The consequence was severe and easy to hit. "Show this record on the map" from any record older
    than the most recent workshop resolved to no row, raised 404, and took the ENTIRE ``/map/points``
    response down with it — a blank page with an error, rather than a map with one unringed pin. A view
    setting must never be able to fail a request.

    So the record is found by id, and whether it falls inside the current filters is REPORTED as
    ``inScope`` rather than enforced. The page can then say "this record is outside your current
    filters" and offer to widen them, which is the answer a reader can act on.

    The keys are computed by the SAME ladder the pins are, at the SAME level, which is the other
    correctness requirement here: a focus key that did not match any drawn pin would ring nothing, and
    the page would look as though the record had no location.
    """
    if focus_type not in _BUCKETS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unknown record type '{focus_type}'. Valid types are {', '.join(RECORD_TYPES)}.",
        )
    delegate = _delegate(focus_type)
    row = await delegate.find_unique(where={"id": focus_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    # Whether the drawn map actually contains it. One cheap count against the same clause the pins were
    # built from, so "outside your filters" is a fact rather than an inference from a missing ring.
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
        # False when the current filters (or the workshop scope) exclude this record. Its keys are
        # still returned: they are where it WOULD ring, and a client can say so instead of leaving the
        # reader to wonder why nothing lit up.
        "inScope": in_scope,
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
    # The workshop scope, from the same shared vocabulary. Repeated or comma-joined ids, plus the
    # reserved value "none" for records that are not linked to a workshop at all. Absent means every
    # workshop — see ``record_filters.resolve_workshop_ids``.
    workshopIds: list[str] | None = Query(None),
    # NATION | STATE | DISTRICT. The administrative unit both layers are grouped at.
    level: str | None = None,
    # The single-record scope. The map still draws the whole filtered corpus — "in context" is meant
    # literally — and simply names which points hold this record.
    focusType: str | None = None,
    focusId: str | None = None,
) -> dict[str, Any]:
    selected = resolve_types(types)
    admin_level = resolve_admin_level(level)
    degrees = _CLUSTER_DEGREES_BY_LEVEL[admin_level]
    # What every point's dropdown lists, and at what cluster radius. None at DISTRICT level, which is the
    # finest unit an address names. See ``_CHILD_LEVEL``.
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
    # Only these four have a `place` column at all — media inherits the record it belongs to — so
    # asking the other bucket for one would be a query that cannot answer anything.
    placed = [bucket for bucket in PLACED_TYPES if bucket in selected]

    # EVERY read this endpoint needs before it knows anything goes out in ONE wave. The database is
    # in another AWS region and a round trip costs roughly 750ms against queries that execute in
    # well under a millisecond, so what decides whether this page is fast is purely how many reads
    # wait on each other. Three separate waves — totals, then places, then locations — cost the same
    # three round trips whether the corpus holds a hundred rows or a million.
    #
    # THE GROUPING IS BY THE PAIR (locationId, place), not by each separately, and that pair is what
    # makes the resolution ladder possible: only a group that knows BOTH its location and its prose
    # can prefer the structured address and fall back to the atlas. Grouping them separately was two
    # reads per bucket AND lost the correspondence between them, so a record with an address and a
    # contradicting place string would have been drawn twice, once per source.
    results = await gather_reads(
        *(_delegate(bucket).count(where=wheres[bucket]) for bucket in counted),
        *(
            _delegate(bucket).group_by(by=["locationId", "place"], count=True, where=wheres[bucket])
            for bucket in placed
        ),
        # Media has no `place`, so it groups by location alone. Its rows are shaped compatibly (the
        # folding code reads `place` with `.get`, which is simply absent here).
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

    # THE SECOND AND LAST WAVE, two independent reads that cannot be asked for until the grouping
    # above has said which locations matter:
    #
    #   * the Location rows in play — read for their STATED address now, not only for their fix;
    #   * the rows that teach the map where districts ARE (``geography.DistrictAnchors``).
    #
    # The anchor read is deliberately NOT filtered by the caller's filters. An anchor built from only
    # the selected rows would move as filters changed, and a pin that shifts when you tick a date
    # range is a map disagreeing with itself — a reader would read the movement as the craft having
    # moved. So it is the same anchors for every request, learned from every pin in the repository.
    location_rows, anchor_rows = await gather_reads(
        db.location.find_many(where={"id": {"in": wanted_location_ids}}) if wanted_location_ids else _none(),
        db.location.find_many(
            where={"subjectLatitude": {"not": None}, "subjectLongitude": {"not": None}},
            take=_MAX_ANCHOR_ROWS,
            # ORDERED, and the order is the whole point of it being here. A LIMIT with no ORDER BY
            # returns whatever the scan reaches first, and this predicate has no index to serve it (the
            # only Location index is on `state`), so it is a heap scan whose row order changes after any
            # UPDATE, after a VACUUM, or on a plan flip. Once the pinned-location count passes the cap
            # that would make the sampled set arbitrary — and district anchors are MEANS of that
            # sample, so a district pin would move, and its `precision` change, between two
            # byte-identical requests. A map that disagrees with itself is worse than a coarse one.
            # `id` is the primary key, so the order is total and the sample is the same every time.
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
        # The vocabulary the client renders its toggle from, so the two cannot drift out of step.
        "levels": [member.value for member in AdminLevel],
        # Which level every point's ``children`` are keyed at, so a client drilling into a dropdown knows
        # which level to switch the map to rather than having to re-derive the ladder. Null at DISTRICT,
        # where there are no children at all.
        "childLevel": child_level.value if child_level else None,
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
            # to — so it can only ever reach the ORIGIN layer through its Location's stated address.
            # Stated rather than left as an asymmetry a reader has to notice.
            "originExcludes": [bucket for bucket in RECORD_TYPES if bucket not in PLACED_TYPES],
            "captureTruncated": truncated,
            "clusterKilometres": round(degrees * 111.32),
            # How much of the structured address the corpus in play actually holds. The map's quality
            # is now a function of these fields, so "why is this record at the state capital" gets an
            # answer a researcher can act on instead of looking like a bug.
            "address": address_completeness(location_rows),
            # How well the map knows where districts are, and how much of that is measured.
            "anchoredDistricts": anchors.anchored_districts,
            "anchorPins": anchor_votes,
            "anchorsTruncated": len(anchor_rows) >= _MAX_ANCHOR_ROWS,
        },
    }


async def _none() -> None:
    """An awaitable that yields nothing, so a conditional read still occupies its slot in the wave.

    ``gather_reads`` returns positionally, so a skipped read cannot simply be omitted without
    renumbering every unpack below it. This keeps the shape fixed and costs no round trip.
    """
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
    """The records behind one pin, so a pin can be navigated FROM rather than only looked at.

    Fetched on demand instead of being carried by ``/points``. The aggregate answer is a couple of
    dozen rows and is what the map draws; the records behind a pin are only wanted for the one pin a
    reader opens, and shipping all of them up front would put the whole corpus in a payload that
    exists to draw thirteen dots.

    The filters — INCLUDING the level and the workshop scope — must be the same ones the map was drawn
    with. The point key alone does not determine the answer: it names an administrative unit, and which
    records sit in that unit is exactly what the filters decide.
    """
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
        # The same coordinate-window narrowing as capture, against the SUBJECT columns instead of the
        # device ones. One function, two column pairs, because the two layers cluster identically and a
        # second copy would be a second chance to get the window arithmetic wrong.
        narrowed = await _capture_narrowing(
            point_key, wheres, selected, "subjectLatitude", "subjectLongitude"
        )
    elif parse_stated_key(point_key) is not None:
        narrowed = await _stated_narrowing(point_key, wheres, selected, admin_level)
    else:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "A point key must be one of 'nation:india', 'state:<State>', "
                "'district:<State>|<District>', 'pin:<size>:<lat cell>_<lon cell>' or "
                "'capture:<size>:<lat cell>_<lon cell>'."
            ),
        )

    buckets = [bucket for bucket in RECORD_TYPES if bucket in selected and bucket in narrowed]
    if not buckets:
        return {"key": point_key, "items": [], "total": 0, "truncated": False}

    # ONE MORE THAN THE CAP, so "there are more" and "there are exactly this many" are distinguishable.
    # Reading exactly `_STUB_CAP` and reporting `truncated = len(rows) == _STUB_CAP` claims a shortfall
    # for a pin holding precisely forty records — a false alarm on a number a reader is using to decide
    # whether to trust the list. The extra row is dropped below and never shipped.
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
            # Hand-picked columns, never the whole row. Aadhaar and Pehchan are not in this list at
            # any rank, because a map has no business carrying them, and picking columns by hand is
            # what guarantees that rather than trusting a masking pass to catch them.
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
        # ``shown`` and NOT ``total``. The pin in ``/map/points`` also has a ``total`` and it means the
        # corpus count; this one is the length of a list capped per bucket. Two identically-named fields
        # meaning different things is a client comparing 40 against 317 and reporting 277 missing
        # records. ``total`` is kept as an alias so no existing client breaks, and ``truncated`` says
        # which of the two situations this is.
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
    """Narrow each bucket to the records that folded into one administrative pin.

    TWO BRANCHES, ORed, because a pin folds records placed two different ways:

    A. The STATED branch — an indexed relation filter on ``Location.state`` (and ``district``). This is
       the branch the whole redesign is for: it is exact, it is one query, and ``Location`` already
       carries ``@@index([state])`` for precisely this shape. Nothing has to be re-derived.

    B. The ATLAS branch — records whose position came from the legacy free-text ``place`` column. Those
       cannot be recovered from the key: three spellings of Bareilly resolve to one atlas town and
       nothing records which three. So the same grouped read the map itself ran is repeated and
       filtered, which is one cheap round trip and, more importantly, cannot disagree with the counts
       the pin is showing.

    THE ATLAS BRANCH IS RESTRICTED TO ROWS THE STATED BRANCH DID NOT CLAIM, and that is a correctness
    requirement rather than an optimisation. The ladder in ``_group_geography`` prefers the stated
    address, so a record whose Location says Gujarat/Kachchh and whose prose says "Bagru" belongs to
    Kachchh alone. Without the restriction it would also be listed under Jaipur, and the panel's count
    would exceed the pin's.

    The restriction is expressed as "has no Location, or its Location states no state", which is the
    closest SQL predicate to "the stated ladder could not place it". It is an APPROXIMATION in exactly
    one direction: a row whose ``state`` column holds a string this build of ``services.address`` no
    longer recognises (a renamed state, say) has a non-null state, so it is excluded from the atlas
    branch while the stated branch cannot place it either — such a row goes missing from its pin's
    record list while still being counted in it. That is a strictly better failure than showing a
    record under a district it is not in, and it is unreachable for any value the current validator
    accepts on write.
    """
    parsed = parse_stated_key(point_key)
    if parsed is None:
        return {}
    kind, state, district = parsed

    # Which prose spellings, in each bucket, resolve into this same pin. Only the four buckets that
    # HAVE a place column can have an atlas branch at all. Computed before the nation branch because
    # that branch needs it too.
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
        # THE NATION PIN COUNTS ONLY WHAT IT PLACED, so its record list must too. Returning the whole
        # filtered corpus here would list the UNPLACED records as well — the ones reported separately
        # precisely because they have no pin — and the panel's count would exceed the pin's, which is
        # the one number a reader uses to sanity-check the map.
        #
        # At NATION level the stated ladder places a row if it has a subject pin or a state; the atlas
        # places whatever prose it can resolve. So the honest narrowing is exactly that disjunction.
        placeable: dict[str, dict[str, Any]] = {}
        for bucket in RECORD_TYPES:
            if bucket not in selected:
                continue
            branches: list[dict[str, Any]] = [
                {"location": {"is": {"state": {"not": None}}}},
                # BOTH halves of the pin, because that is what ``stated_point`` requires to use one.
                # A `subjectLatitude` with a null `subjectLongitude` is half a pin: the ladder cannot
                # place it, so it is UNPLACED — and listing it under the nation pin would put a record
                # in a panel while `summary.unplacedRecords` still counted it as having no pin.
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

    # WHICH RAW (state, district) COLUMN VALUES BELONG TO THIS PIN.
    #
    # The key holds CANONICAL names, because that is what built it. The columns do not have to: the
    # validator writes canonical values today, but ``Location.state`` is nullable and predates it, so
    # rows exist holding "gujarat", "Orissa", "Kachchh District". Comparing the canonical key to the raw
    # column with Prisma equality would count such a row in a pin and then list it in no record list at
    # all — the pin's number and the panel's number would disagree, silently, on exactly the legacy rows
    # this whole redesign exists to bring onto the map.
    #
    # So the same resolution the map ran is re-run over the DISTINCT pairs actually stored, and the pin
    # is narrowed to the raw pairs that resolve into it. That is one cheap grouped read (the distinct
    # pairs are a couple of dozen rows whatever the corpus size) and, like the atlas branch below, it
    # cannot disagree with the counts the pin is showing because it asks the identical question.
    #
    # The pairs are matched AS PAIRS, never as two independent ``in`` lists: "gujarat" + "Kachchh" and
    # "Gujarat" + "Jaipur" are two real rows, and two separate lists would admit their cross product —
    # a Jaipur record listed under Kachchh.
    pairs = await db.location.group_by(by=["state", "district"], count=True)
    matching = [
        {"state": raw_state, "district": raw_district}
        for raw_state, raw_district in (
            (group.get("state"), group.get("district")) for group in pairs
        )
        if _pair_belongs(raw_state, raw_district, state, district, kind, level)
    ]
    # An impossible predicate rather than an absent one when nothing matches: no stored address resolves
    # into this pin, and leaving the location filter off would widen the branch to every record.
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
                        # "The stated ladder did not claim this row" — see the docstring.
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
    """Does one stored ``(state, district)`` pair resolve into this pin, at this level?

    THE LEVEL IS LOAD-BEARING for a ``state:`` key, and forgetting it over-selects by a whole state.
    ``stated_point`` emits ``state:X`` for two DIFFERENT reasons:

      * at STATE level, because that is the grouping — the pin means "every district in X";
      * at DISTRICT level, because the row named a state and NO usable district — the pin means "in X,
        with no district stated", and its records are a strict subset of the state's.

    The key is identical in both cases, so only the level tells them apart. Without this, opening the
    "Rajasthan" pin at district level listed every Rajasthan record — including all the ones already
    accounted for by the Jaipur and Barmer pins beside it — and the panel's count exceeded the pin's.

    "No usable district" is deliberately wider than "the column is null": a district that does not
    resolve inside its state (a typo, or a name from a different state) is one ``stated_point`` also
    could not use, so it lands on the same pin and must be narrowed onto it.
    """
    from app.services.address import canonical_district, canonical_state

    pair_state = canonical_state(raw_state)
    if kind == "nation":
        # Anything with a resolvable state was placed at nation level; a bare pin is handled by the
        # coordinate branch in the caller.
        return pair_state is not None
    if pair_state != state:
        return False
    pair_district = canonical_district(pair_state, raw_district) if pair_state else None
    if kind == "district":
        return pair_district == district
    # kind == "state"
    return True if level is AdminLevel.STATE else pair_district is None


def _atlas_place_matches(
    place_text: str | None,
    state: str | None,
    district: str | None,
    kind: str,
    level: AdminLevel,
) -> bool:
    """Whether one free-text place resolves, through the atlas, into this administrative pin.

    ``level`` IS NOT OPTIONAL and the reason is the same one :func:`_pair_belongs` documents: a
    ``state:X`` key means two different things depending on the level it was drawn at. At STATE level it
    means "every district in X"; at DISTRICT level it means "in X, with no usable district" — and this
    branch has to agree with the stated branch about which, or the two arms of the same OR admit
    different rows.

    They did not agree. Without the level, a ``state:X`` pin at DISTRICT level admitted every atlas place
    in X — including the ones the atlas resolves to a district and which were therefore drawn on their own
    ``district:X|Y`` pin. On the live corpus every ``Location`` has a NULL state, so the atlas branch is
    the ONLY branch that fires, and one product recorded as "Jammu & Kashmir" draws a ``state:Jammu and
    Kashmir`` pin whose panel then listed the eleven Jammu-district rows as well: a panel with more rows
    in it than the pin it was opened from counts.
    """
    resolved = resolve_place(place_text)
    if resolved.place is None:
        return False
    # The STRICT resolvers, so this comparison asks the same question ``geography`` asked when it built
    # the key. ``normalize_*`` would pass an unrecognised name straight through and could match a key
    # that was never generated — see the docstrings of ``address.canonical_state``.
    from app.services.address import canonical_district, canonical_state

    place_state = canonical_state(resolved.place.state)
    if kind == "nation":
        # THE NATION PIN HAS NO STATE, so there is nothing to compare against and every place that
        # resolves at all belongs to it. Falling through to the equality below compared a real state
        # name to ``None`` and answered False for every input, which made the nation pin's atlas branch
        # dead code — the pin counted the prose-placed records and then listed none of them.
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
    # kind == "state". The same fork ``_pair_belongs`` makes on its last line, for the same reason: at
    # STATE level the pin IS the whole state, so every place in it belongs; at DISTRICT level the pin
    # stands for the rows whose district is not known, so a place the atlas CAN put in a district belongs
    # to that district's pin instead.
    return True if level is AdminLevel.STATE else place_district is None


async def _capture_narrowing(
    point_key: str,
    wheres: dict[str, dict[str, Any]],
    selected: set[str],
    latitude_column: str,
    longitude_column: str,
) -> dict[str, dict[str, Any]]:
    """Narrow each bucket to the ``Location`` rows inside this pin's grid cell.

    Serves BOTH coordinate-clustered layers, which is why the column pair is a parameter: the CAPTURE
    layer clusters ``latitude``/``longitude`` (the device's fix) and the coordinate-only ORIGIN rung
    clusters ``subjectLatitude``/``subjectLongitude`` (a pin somebody dropped). The cell arithmetic is
    identical, and one function is one place for it to be right.

    The cell SIZE is read from the key rather than from the request's level, so an old link or a stale
    panel opens the window it was drawn for instead of a differently-sized one centred on the same
    integers. See :func:`_capture_key`.
    """
    remainder = point_key.split(":", 1)[1]
    try:
        raw_degrees, cell = remainder.split(":", 1)
        degrees = float(raw_degrees)
        cell_latitude, cell_longitude = (int(part) for part in cell.split("_"))
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A capture point key looks like 'capture:<cell size>:<lat cell>_<lon cell>'.",
        ) from error
    if degrees <= 0:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A capture cell size must be greater than zero.",
        )

    # The cell as a coordinate window, so the database does the narrowing rather than a scan of
    # every Location row in the repository.
    window = {
        latitude_column: {"gte": cell_latitude * degrees, "lt": (cell_latitude + 1) * degrees},
        longitude_column: {"gte": cell_longitude * degrees, "lt": (cell_longitude + 1) * degrees},
    }
    # Ordered for the same reason the anchor read is: an unordered LIMIT over an unindexed window
    # returns an arbitrary subset, so the same pin would list different records on two identical
    # requests. At NATION level the cell is five degrees — most of eastern India — which makes hitting
    # the cap plausible rather than theoretical.
    rows = await db.location.find_many(where=window, take=_MAX_CAPTURE_POINTS, order={"id": "asc"})
    ids = [row.id for row in rows]
    if not ids:
        return {}
    return {
        bucket: {"AND": [wheres[bucket], {"locationId": {"in": ids}}]}
        for bucket in RECORD_TYPES
        if bucket in selected
    }
