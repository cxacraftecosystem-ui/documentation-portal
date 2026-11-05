from __future__ import annotations

from collections import Counter
from typing import Any
from urllib.parse import quote

from app.core.db import db
from app.services.datareport.contract import Banner, Column, Link, ReportContext, Sheet, register
from app.services.datareport.sheets.extras_common import (
    KIND_LABELS,
    Plugin,
    chunks,
    g,
    ident,
    media_facts,
    open_record,
    records,
    say,
)

PLACED_KINDS = ("workshops", "artisans", "products", "tools", "interviews", "media")
NO_DISTRICT = "District not given"
MAPS = "https://www.google.com/maps/search/?api=1&query="


def maps_url(lat: float | None, lng: float | None, place: str | None) -> str | None:
    if lat is not None and lng is not None:
        return f"{MAPS}{lat:.6f},{lng:.6f}"
    if place:
        return MAPS + quote(place, safe="")
    return None


def _coords(location: Any) -> tuple[float | None, float | None]:
    lat, lng = g(location, "subjectLatitude"), g(location, "subjectLongitude")
    if lat is None or lng is None:
        lat, lng = g(location, "latitude"), g(location, "longitude")
    if lat is None or lng is None:
        return None, None
    try:
        lat, lng = float(lat), float(lng)
    except (TypeError, ValueError):
        return None, None
    if lat == 0.0 and lng == 0.0:
        return None, None
    return lat, lng


async def _locations(ids: set[str]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for part in chunks(sorted(i for i in ids if i)):
        for row in await db.location.find_many(where={"id": {"in": part}}):
            out[str(row.id)] = row
    return out


def _place_text(rec: Any, location: Any) -> str | None:
    parts = [g(location, "village"), g(location, "placeName")]
    own = g(rec, "place")
    text = next((str(p).strip() for p in [own, *parts] if p and str(p).strip()), None)
    if text is None:
        address = g(location, "address")
        text = str(address).strip() if address else None
    return text or None


async def points(ctx: ReportContext) -> list[dict[str, Any]]:
    facts = await media_facts(ctx)
    picked: list[tuple[str, Any, str | None]] = []
    for kind in PLACED_KINDS:
        for rec in records(ctx, kind):
            loc_id = g(rec, "locationId")
            if kind == "media" and not loc_id:
                loc_id = (facts.get(ident(rec)) or {}).get("locationId")
            picked.append((kind, rec, str(loc_id) if loc_id else None))
    known = await _locations({loc for _, _, loc in picked if loc})
    out: list[dict[str, Any]] = []
    for kind, rec, loc_id in picked:
        location = g(rec, "location") or (known.get(loc_id) if loc_id else None)
        lat, lng = _coords(location)
        place = _place_text(rec, location)
        url = maps_url(lat, lng, " ".join(p for p in (place, g(location, "district"), g(location, "state")) if p) or None)
        if url is None:
            continue
        out.append(
            {
                "kind": kind,
                "rec": rec,
                "district": str(g(location, "district") or "").strip() or NO_DISTRICT,
                "state": g(location, "state"),
                "village": g(location, "village"),
                "pincode": g(location, "pincode"),
                "place": place,
                "lat": lat,
                "lng": lng,
                "accuracy": g(location, "accuracy"),
                "url": url,
            }
        )
    out.sort(key=lambda p: (p["district"] == NO_DISTRICT, p["district"], p["kind"], str(p["place"] or "")))
    return out


DISTRICT_COLUMNS = [
    Column("district", "District", "text", "District from the record's location.", width=22, source="Location.district"),
    Column("state", "State", "text", "State or union territory.", width=18, source="Location.state"),
    Column("places", "Places", "int", "Records and files with a place in this district.", total="sum"),
    Column("artisans", "Artisans", "int", "Artisans placed here.", total="sum"),
    Column("workshops", "Workshops", "int", "Workshops placed here.", total="sum"),
    Column("files", "Files", "int", "Files captured here.", total="sum"),
    Column("other", "Other records", "int", "Products, tools and sittings placed here.", total="sum"),
    Column("map", "Map", "link", "The district in Google Maps.", width=12),
]
POINT_COLUMNS = [
    Column("district", "District", "text", "District from the record's location.", width=20, source="Location.district"),
    Column("inDistrict", "In district", "int", "How many places share this district."),
    Column("type", "Type", "text", "What the record is.", width=14),
    Column("record", "Record", "link", "Opens the record in the portal.", width=28),
    Column("recordId", "Record ID", "id", "The record's id.", hidden=True, group="ids"),
    Column(
        "place",
        "Place",
        "text",
        "The stated place, village or place name.",
        width=24,
        source="Location.placeName,Location.address,Artisan.place,Workshop.place,QuestionnaireInterview.place",
    ),
    Column("village", "Village", "text", "Village or hamlet.", source="Location.village"),
    Column("pincode", "PIN code", "text", "Postal PIN code.", source="Location.pincode"),
    Column("state", "State", "text", "State or union territory.", source="Location.state"),
    Column(
        "lat", "Latitude", "number", "The subject's latitude, else the device's.", source="Location.subjectLatitude,Location.latitude"
    ),
    Column(
        "lng", "Longitude", "number", "The subject's longitude, else the device's.", source="Location.subjectLongitude,Location.longitude"
    ),
    Column("accuracy", "Accuracy (m)", "number", "Radius of the device fix in metres.", source="Location.accuracy"),
    Column("map", "Map", "link", "Opens the point, or the place by name, in Google Maps.", width=14),
]


async def build(ctx: ReportContext) -> list[Sheet]:
    found = await points(ctx)
    per_district = Counter(p["district"] for p in found)
    per_kind: dict[str, Counter[str]] = {}
    states: dict[str, str] = {}
    for p in found:
        per_kind.setdefault(p["district"], Counter())[p["kind"]] += 1
        if p["state"]:
            states.setdefault(p["district"], str(p["state"]))
    unknown = say(ctx, "text.districtNotGiven", NO_DISTRICT)
    open_map = say(ctx, "link.openMap", "Open map")
    district_rows = []
    for district in sorted(per_district, key=lambda d: (d == NO_DISTRICT, -per_district[d], d)):
        kinds = per_kind[district]
        state = states.get(district)
        query = ", ".join(x for x in (district, state, "India") if x and x != NO_DISTRICT)
        district_rows.append(
            {
                "district": unknown if district == NO_DISTRICT else district,
                "state": state,
                "places": per_district[district],
                "artisans": kinds.get("artisans", 0),
                "workshops": kinds.get("workshops", 0),
                "files": kinds.get("media", 0),
                "other": sum(v for k, v in kinds.items() if k not in ("artisans", "workshops", "media")),
                "map": Link(open_map, MAPS + quote(query, safe="")) if district != NO_DISTRICT else None,
            }
        )
    empty = say(ctx, "banner.noPlaces", "No record in this folder has a place.")
    districts = Sheet(
        key="mapDistricts",
        name="Map by district",
        columns=DISTRICT_COLUMNS,
        rows=district_rows,
        banners=[Banner(say(ctx, "banner.mapGrouped", "Grouped by district, with counts."))],
        note=None if district_rows else empty,
        databar_columns=["places"],
        count=len(district_rows),
    )
    google = say(ctx, "link.googleMaps", "Google Maps")
    point_rows = [
        {
            "district": unknown if p["district"] == NO_DISTRICT else p["district"],
            "inDistrict": per_district[p["district"]],
            "type": KIND_LABELS.get(p["kind"], p["kind"]),
            "record": open_record(ctx, p["kind"], p["rec"]),
            "recordId": ident(p["rec"]),
            "place": p["place"],
            "village": p["village"],
            "pincode": p["pincode"],
            "state": p["state"],
            "lat": p["lat"],
            "lng": p["lng"],
            "accuracy": p["accuracy"],
            "map": Link(google, p["url"]),
        }
        for p in found
    ]
    detail = Sheet(
        key="map",
        name="Map",
        columns=POINT_COLUMNS,
        rows=point_rows,
        banners=[
            Banner(
                say(
                    ctx, "banner.mapPlaces", "{n} places in {d} districts.", n=len(found), d=len(per_district)
                )
            )
        ],
        note=None if point_rows else empty,
        count=len(point_rows),
    )
    return [districts, detail]


register(
    Plugin(
        "extras_map",
        "Map",
        120,
        "map",
        build,
        lambda: [("mapDistricts", DISTRICT_COLUMNS, "Map by district"), ("map", POINT_COLUMNS, "Map")],
    )
)
