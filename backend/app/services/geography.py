from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Iterable, Mapping

from app.services.address import (
    INDIAN_STATES_AND_UNION_TERRITORIES,
    canonical_district,
    canonical_state,
)


class AdminLevel(str, Enum):
    NATION = "NATION"
    STATE = "STATE"
    DISTRICT = "DISTRICT"


DEFAULT_ADMIN_LEVEL = AdminLevel.DISTRICT


def resolve_admin_level(raw: str | None) -> AdminLevel:
    if not raw:
        return DEFAULT_ADMIN_LEVEL
    try:
        return AdminLevel(str(raw).strip().upper())
    except ValueError:
        return DEFAULT_ADMIN_LEVEL


PRECISION_SUBJECT_PIN = "SUBJECT_PIN"
PRECISION_DISTRICT = "DISTRICT"
PRECISION_STATE = "STATE"
PRECISION_TOWN = "TOWN"
PRECISION_MEASURED = "MEASURED"
PRECISION_NATION = "NATION"

SOURCE_SUBJECT_PIN = "SUBJECT_PIN"
SOURCE_STATED_ADDRESS = "STATED_ADDRESS"
SOURCE_PLACE_TEXT = "PLACE_TEXT"
SOURCE_DEVICE_FIX = "DEVICE_FIX"


@dataclass(frozen=True)
class StatedPoint:
    key: str
    label: str
    region: str
    state: str | None
    district: str | None
    latitude: float
    longitude: float
    precision: str
    source: str


STATE_SEATS: dict[str, tuple[str, float, float]] = {
    "Andhra Pradesh": ("Amaravati", 16.5062, 80.6480),
    "Arunachal Pradesh": ("Itanagar", 27.0844, 93.6053),
    "Assam": ("Dispur", 26.1433, 91.7898),
    "Bihar": ("Patna", 25.5941, 85.1376),
    "Chhattisgarh": ("Raipur", 21.2514, 81.6296),
    "Goa": ("Panaji", 15.4909, 73.8278),
    "Gujarat": ("Gandhinagar", 23.2156, 72.6369),
    "Haryana": ("Chandigarh", 30.7333, 76.7794),
    "Himachal Pradesh": ("Shimla", 31.1048, 77.1734),
    "Jharkhand": ("Ranchi", 23.3441, 85.3096),
    "Karnataka": ("Bengaluru", 12.9716, 77.5946),
    "Kerala": ("Thiruvananthapuram", 8.5241, 76.9366),
    "Madhya Pradesh": ("Bhopal", 23.2599, 77.4126),
    "Maharashtra": ("Mumbai", 19.0760, 72.8777),
    "Manipur": ("Imphal", 24.8170, 93.9368),
    "Meghalaya": ("Shillong", 25.5788, 91.8933),
    "Mizoram": ("Aizawl", 23.7271, 92.7176),
    "Nagaland": ("Kohima", 25.6751, 94.1086),
    "Odisha": ("Bhubaneswar", 20.2961, 85.8245),
    "Punjab": ("Chandigarh", 30.7333, 76.7794),
    "Rajasthan": ("Jaipur", 26.9124, 75.7873),
    "Sikkim": ("Gangtok", 27.3314, 88.6138),
    "Tamil Nadu": ("Chennai", 13.0827, 80.2707),
    "Telangana": ("Hyderabad", 17.3850, 78.4867),
    "Tripura": ("Agartala", 23.8315, 91.2868),
    "Uttar Pradesh": ("Lucknow", 26.8467, 80.9462),
    "Uttarakhand": ("Dehradun", 30.3165, 78.0322),
    "West Bengal": ("Kolkata", 22.5726, 88.3639),
    "Andaman and Nicobar Islands": ("Port Blair", 11.6234, 92.7265),
    "Chandigarh": ("Chandigarh", 30.7333, 76.7794),
    "Dadra and Nagar Haveli and Daman and Diu": ("Daman", 20.3974, 72.8328),
    "Delhi": ("New Delhi", 28.6139, 77.2090),
    "Jammu and Kashmir": ("Srinagar", 34.0837, 74.7973),
    "Ladakh": ("Leh", 34.1526, 77.5771),
    "Lakshadweep": ("Kavaratti", 10.5669, 72.6420),
    "Puducherry": ("Puducherry", 11.9416, 79.8083),
}

NATION_FALLBACK = ("India", 22.5937, 78.9629)


def state_seat(state: str | None) -> tuple[str, float, float] | None:
    canonical = canonical_state(state)
    if not canonical:
        return None
    return STATE_SEATS.get(canonical)



def district_key(state: str, district: str) -> str:
    return f"{state}|{district}"


@dataclass
class _Votes:
    latitude: float = 0.0
    longitude: float = 0.0
    count: int = 0

    def add(self, latitude: float, longitude: float, weight: int = 1) -> None:
        self.latitude += latitude * weight
        self.longitude += longitude * weight
        self.count += weight

    @property
    def point(self) -> tuple[float, float]:
        return (self.latitude / self.count, self.longitude / self.count)


class DistrictAnchors:
    def __init__(self) -> None:
        self._votes: dict[str, _Votes] = {}
        self._seeded: set[str] = set()

    def seed_from_atlas(self) -> None:
        from app.services.place_atlas import atlas_district_anchors

        for raw_state, raw_district, latitude, longitude in atlas_district_anchors():
            state = canonical_state(raw_state)
            if not state:
                continue
            district = canonical_district(state, raw_district)
            if not district:
                continue
            key = district_key(state, district)
            self._votes.setdefault(key, _Votes()).add(latitude, longitude)
            self._seeded.add(key)

    def learn(self, rows: Iterable[Any]) -> int:
        learned = 0
        for row in rows:
            latitude = getattr(row, "subjectLatitude", None)
            longitude = getattr(row, "subjectLongitude", None)
            if latitude is None or longitude is None:
                continue
            state = canonical_state(getattr(row, "state", None))
            if not state:
                continue
            district = canonical_district(state, getattr(row, "district", None))
            if not district:
                continue
            self._votes.setdefault(district_key(state, district), _Votes()).add(
                float(latitude), float(longitude)
            )
            learned += 1
        return learned

    def anchor(self, state: str, district: str) -> tuple[float, float] | None:
        votes = self._votes.get(district_key(state, district))
        return votes.point if votes and votes.count else None

    def vote_count(self, state: str, district: str) -> int:
        return (self._votes.get(district_key(state, district)) or _Votes()).count

    @property
    def anchored_districts(self) -> int:
        return len(self._votes)



def stated_point(
    *,
    level: AdminLevel,
    state: str | None,
    district: str | None,
    subject_latitude: float | None,
    subject_longitude: float | None,
    anchors: DistrictAnchors,
    pin_precision: str = PRECISION_SUBJECT_PIN,
    pin_source: str = SOURCE_SUBJECT_PIN,
) -> StatedPoint | None:
    state_name = canonical_state(state)
    district_name = (
        canonical_district(state_name, district) if state_name and district else None
    )
    pin = (
        (float(subject_latitude), float(subject_longitude))
        if subject_latitude is not None and subject_longitude is not None
        else None
    )

    used_pin = pin is not None

    if level is AdminLevel.NATION:
        if pin is None and state_name:
            seat = STATE_SEATS.get(state_name)
            pin = (seat[1], seat[2]) if seat else None
        if pin is None:
            return None
        name, _fallback_latitude, _fallback_longitude = NATION_FALLBACK
        return StatedPoint(
            key="nation:india",
            label=name,
            region="Every placed record in the country",
            state=None,
            district=None,
            latitude=pin[0],
            longitude=pin[1],
            precision=PRECISION_NATION,
            source=pin_source if used_pin else SOURCE_STATED_ADDRESS,
        )

    if not state_name:
        return None

    seat = STATE_SEATS.get(state_name)

    if level is AdminLevel.STATE or not district_name:
        latitude, longitude, precision, source = _state_position(
            state_name, pin, seat, pin_precision, pin_source
        )
        if latitude is None or longitude is None:
            return None
        return StatedPoint(
            key=f"state:{state_name}",
            label=state_name,
            region=(
                f"Every district in {state_name}"
                if level is AdminLevel.STATE
                else f"{state_name} — no district stated"
            ),
            state=state_name,
            district=None,
            latitude=latitude,
            longitude=longitude,
            precision=precision,
            source=source,
        )

    anchored = anchors.anchor(state_name, district_name)
    if pin is not None:
        latitude, longitude = pin
        precision, source = pin_precision, pin_source
        region = f"{district_name} district, {state_name}"
    elif anchored is not None:
        latitude, longitude = anchored
        precision, source = PRECISION_DISTRICT, SOURCE_STATED_ADDRESS
        region = f"{district_name} district, {state_name}"
    elif seat is not None:
        latitude, longitude = seat[1], seat[2]
        precision, source = PRECISION_STATE, SOURCE_STATED_ADDRESS
        region = f"{district_name} district, {state_name} — shown at {seat[0]}"
    else:
        return None

    return StatedPoint(
        key=f"district:{state_name}|{district_name}",
        label=district_name,
        region=region,
        state=state_name,
        district=district_name,
        latitude=latitude,
        longitude=longitude,
        precision=precision,
        source=source,
    )


def _state_position(
    state: str,
    pin: tuple[float, float] | None,
    seat: tuple[str, float, float] | None,
    pin_precision: str,
    pin_source: str,
) -> tuple[float | None, float | None, str, str]:
    if pin is not None:
        return pin[0], pin[1], pin_precision, pin_source
    if seat is not None:
        return seat[1], seat[2], PRECISION_STATE, SOURCE_STATED_ADDRESS
    return None, None, PRECISION_STATE, SOURCE_STATED_ADDRESS


def parse_stated_key(key: str) -> tuple[str, str | None, str | None] | None:
    if key == "nation:india":
        return ("nation", None, None)
    if key.startswith("state:"):
        state = canonical_state(key.split(":", 1)[1])
        return ("state", state, None) if state else None
    if key.startswith("district:"):
        remainder = key.split(":", 1)[1]
        if "|" not in remainder:
            return None
        raw_state, raw_district = remainder.split("|", 1)
        state = canonical_state(raw_state)
        if not state:
            return None
        district = canonical_district(state, raw_district)
        return ("district", state, district) if district else None
    return None


def address_completeness(rows: Iterable[Any]) -> dict[str, int]:
    counts = {"locations": 0, "withState": 0, "withDistrict": 0, "withPincode": 0, "withSubjectPin": 0}
    for row in rows:
        counts["locations"] += 1
        state = canonical_state(getattr(row, "state", None))
        if state:
            counts["withState"] += 1
            if canonical_district(state, getattr(row, "district", None)):
                counts["withDistrict"] += 1
        if (getattr(row, "pincode", None) or "").strip():
            counts["withPincode"] += 1
        if getattr(row, "subjectLatitude", None) is not None and getattr(row, "subjectLongitude", None) is not None:
            counts["withSubjectPin"] += 1
    return counts


def mean_point(points: Iterable[tuple[float, float, int]]) -> tuple[float, float] | None:
    total_latitude = 0.0
    total_longitude = 0.0
    total_weight = 0
    for latitude, longitude, weight in points:
        if weight <= 0:
            continue
        total_latitude += latitude * weight
        total_longitude += longitude * weight
        total_weight += weight
    if not total_weight:
        return None
    return (total_latitude / total_weight, total_longitude / total_weight)


def known_states() -> tuple[str, ...]:
    return INDIAN_STATES_AND_UNION_TERRITORIES


def seat_coverage() -> Mapping[str, tuple[str, float, float]]:
    return STATE_SEATS
