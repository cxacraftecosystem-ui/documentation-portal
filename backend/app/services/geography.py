"""Where a record IS, derived from the address fields the record already carries.

WHY THIS EXISTS
---------------
The map used to be drawn entirely from ``place`` — one free-text box a researcher typed — resolved
through a hand-curated table of thirteen towns in ``services/place_atlas``. That table works, and it
is honest about its precision, but it has a fatal property as the primary source: **a place nobody
has hand-entered into the table cannot appear on the map at all.** Documenting a craft in a
fourteenth town means editing Python and shipping a deploy before the fourteenth town exists as a
dot. A map that has to be populated by hand is a map that is always behind the data.

Meanwhile the repository has been collecting a STRUCTURED address on every record for months, and
the map read none of it. ``Location`` carries:

    state              canonical, from the closed list of 36 in ``services/address``
    district           canonical within that state, from the closed list of 795
    village            free text
    pincode            six digits, validated
    subjectLatitude    the pin a researcher dropped on the SUBJECT'S place
    subjectLongitude
    latitude/longitude the DEVICE'S own fix — where the recording happened, not where the craft is

Those are the "various parameters for address that we have", and this module turns them into points.
Nothing in here has to be hand-fed a place name. Adding a fourteenth town needs no code: the
researcher picks its state and district from dropdowns that already hold every district in India, and
the record plots.

THE RESOLUTION LADDER, most trustworthy first
----------------------------------------------
1. ``SUBJECT_PIN``  — ``subjectLatitude``/``subjectLongitude``. A person pointed at the place on a
                      map. Nothing beats it and nothing needs to be looked up.
2. ``DISTRICT``     — ``state`` + ``district``, placed at that district's ANCHOR (below).
3. ``STATE``        — ``state`` alone, placed at the state's administrative seat.
4. (caller's choice) — the free-text ``place`` through ``place_atlas``, which the map still uses as a
                      LAST resort so the legacy records that only ever had prose keep their dots.

WHERE DISTRICT COORDINATES COME FROM, AND WHY THERE IS NO GAZETTEER FILE
------------------------------------------------------------------------
India has ~795 districts. Hand-authoring a coordinate for each would be re-creating exactly the
problem this module exists to solve — a table somebody has to maintain, wrong in ways nobody can see,
and 782 rows of it dead weight for a corpus that touches a dozen districts.

So district positions are **LEARNED FROM THE DATA**:

  * Every ``Location`` that has BOTH a stated ``state``/``district`` AND a subject pin votes for where
    that district is. The anchor is the mean of its votes, and it gets better every time a researcher
    drops a pin. This is measured data about real places, not an estimate.
  * ``place_atlas``'s thirteen towns each already name their district, and their coordinates are
    published town coordinates that were checked by eye. Those seed the anchors so the corpus is not
    starting from nothing.
  * A district with no votes and no seed is drawn at its STATE'S seat, at ``STATE`` precision, and
    SAYS SO. That is the honest answer — "somewhere in Rajasthan" — rather than a district centroid
    nobody chose, and it is exactly the behaviour the old atlas had for an unknown place except that
    the record now appears at all.

STATE SEATS ARE PUBLISHED FACTS, ALL 36 OF THEM
------------------------------------------------
A state names a region, not a point, so any single coordinate for it is a convention — and a computed
centroid invites "centroid of WHICH boundary?", which for Jammu and Kashmir is a question this
codebase has no business answering. An administrative seat is a real, published, unambiguous place.
:data:`STATE_SEATS` therefore holds the seat of every one of the 36 states and union territories in
``address.INDIAN_STATES_AND_UNION_TERRITORIES`` — all of them, not the nine the corpus happens to
touch, because the whole point is that a record in a new state plots without a code change. The tests
assert the two lists agree exactly, so neither can drift alone.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Iterable, Mapping

from app.services.address import (
    INDIAN_STATES_AND_UNION_TERRITORIES,
    canonical_district,
    canonical_state,
)

# ---------------------------------------------------------------------------------------------
# The administrative ladder the map's detail control walks
# ---------------------------------------------------------------------------------------------


class AdminLevel(str, Enum):
    """How coarsely the map groups what it draws.

    Three levels, because that is what an Indian administrative address HAS above a village: the
    country, the state, and the district. Each level is a real unit somebody could draw a border
    around, which is why the control says "Nation / State / District" rather than "zoom".

    DISTRICT is the default: it is the finest unit every record can honestly be placed in, and it is
    what somebody asking "where does this craft come from" means.
    """

    NATION = "NATION"
    STATE = "STATE"
    DISTRICT = "DISTRICT"


#: What the map falls back to when a caller sends nothing.
DEFAULT_ADMIN_LEVEL = AdminLevel.DISTRICT


def resolve_admin_level(raw: str | None) -> AdminLevel:
    """Parse the ``level`` query parameter, case-insensitively, defaulting rather than failing.

    A level is a VIEW SETTING, not a filter: getting it wrong shows the same records grouped
    differently, so an unrecognised value is answered with the default rather than a 422. Compare
    ``resolve_types``, which does raise — there, a wrong value silently changes WHICH RECORDS come
    back, and answering a typo with a well-formed empty list is a wrong answer dressed as a right one.
    """
    if not raw:
        return DEFAULT_ADMIN_LEVEL
    try:
        return AdminLevel(str(raw).strip().upper())
    except ValueError:
        return DEFAULT_ADMIN_LEVEL


# ---------------------------------------------------------------------------------------------
# How far a drawn point may be from what the record meant
# ---------------------------------------------------------------------------------------------

#: A person pointed at the subject's place on a map. The only origin-side precision that is a
#: measurement rather than a lookup.
PRECISION_SUBJECT_PIN = "SUBJECT_PIN"
#: Placed at a district anchor learned from real pins (or an atlas town inside that district).
PRECISION_DISTRICT = "DISTRICT"
#: Only the state is known, or the district has no anchor yet. Drawn at the state seat.
PRECISION_STATE = "STATE"
#: A named town from ``place_atlas``, resolved out of free text.
PRECISION_TOWN = "TOWN"
#: The device's own GPS fix — the CAPTURE layer.
PRECISION_MEASURED = "MEASURED"
#: Every record in the country, folded into one point at NATION level.
PRECISION_NATION = "NATION"

#: Where a resolved point's coordinates came from. Travels to the client so a reader can tell a
#: dropped pin from a district anchor from a line of prose.
SOURCE_SUBJECT_PIN = "SUBJECT_PIN"
SOURCE_STATED_ADDRESS = "STATED_ADDRESS"
SOURCE_PLACE_TEXT = "PLACE_TEXT"
SOURCE_DEVICE_FIX = "DEVICE_FIX"


@dataclass(frozen=True)
class StatedPoint:
    """One record group's position, derived from its stated address.

    ``key`` is stable across requests at a given :class:`AdminLevel` and is what the client sends
    back to ask for the records behind the pin, so it has to be invertible — see
    ``map_points._stated_narrowing``.
    """

    key: str
    label: str
    #: The administrative line under the label.
    region: str
    state: str | None
    district: str | None
    latitude: float
    longitude: float
    precision: str
    source: str


# ---------------------------------------------------------------------------------------------
# State seats — all 36, so a record in any state plots without a code change
# ---------------------------------------------------------------------------------------------

#: state or union territory -> (seat name, latitude, longitude).
#:
#: The seat is the ADMINISTRATIVE capital as published. Three notes worth reading, because each is a
#: place where a plausible guess is wrong:
#:   * Haryana and Punjab SHARE Chandigarh, which is itself a union territory with its own row. Three
#:     rows therefore carry the same coordinate, correctly — this is not a copy-paste slip.
#:   * Andhra Pradesh's seat is Amaravati, not Hyderabad, which became Telangana's alone in 2024.
#:   * Jammu and Kashmir has two capitals by season (Srinagar in summer, Jammu in winter). Srinagar is
#:     used because it is the larger and the one in force for most of the year; the choice is stated
#:     here rather than left to be inferred from a coordinate.
STATE_SEATS: dict[str, tuple[str, float, float]] = {
    # States, in the order ``address.INDIAN_STATES`` lists them.
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
    # Union territories, in the order ``address.INDIAN_UNION_TERRITORIES`` lists them.
    "Andaman and Nicobar Islands": ("Port Blair", 11.6234, 92.7265),
    "Chandigarh": ("Chandigarh", 30.7333, 76.7794),
    "Dadra and Nagar Haveli and Daman and Diu": ("Daman", 20.3974, 72.8328),
    "Delhi": ("New Delhi", 28.6139, 77.2090),
    "Jammu and Kashmir": ("Srinagar", 34.0837, 74.7973),
    "Ladakh": ("Leh", 34.1526, 77.5771),
    "Lakshadweep": ("Kavaratti", 10.5669, 72.6420),
    "Puducherry": ("Puducherry", 11.9416, 79.8083),
}

#: The centre of the drawn country, used only for the single NATION-level point when there is nothing
#: to average (every record placed by state, none with a coordinate). Chosen as the midpoint of the
#: outline's own bounds rather than as a "centre of India", which is a contested claim this codebase
#: does not need to make.
NATION_FALLBACK = ("India", 22.5937, 78.9629)


def state_seat(state: str | None) -> tuple[str, float, float] | None:
    """The seat of ``state``, accepting any spelling the closed list accepts.

    Resolving here rather than requiring the canonical name is what makes this usable against LEGACY
    rows: ``Location.state`` is written through the validator today, but the column is nullable and
    predates it, so rows exist holding "gujarat" and "Orissa". ``canonical_state`` accepts both and
    returns ``None`` for a name that is not a state at all — the strict resolver, not the normaliser,
    because this is a read path and an unrecognised name here must be an absent seat rather than a
    lookup miss that happens to look the same.
    """
    canonical = canonical_state(state)
    if not canonical:
        return None
    return STATE_SEATS.get(canonical)


# ---------------------------------------------------------------------------------------------
# District anchors, learned rather than tabulated
# ---------------------------------------------------------------------------------------------


def district_key(state: str, district: str) -> str:
    """The identity of one district, state included.

    THE STATE IS PART OF THE KEY, always. "Bilaspur" is a district of Chhattisgarh and a different
    district of Himachal Pradesh; "Hamirpur" is in both Himachal Pradesh and Uttar Pradesh. A flat
    district key would average two places 900 km apart into one anchor and draw both records in the
    field between them.
    """
    return f"{state}|{district}"


@dataclass
class _Votes:
    """Running mean of the pins that have voted for one district's position."""

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
    """Where each district IS, as far as this repository can tell.

    Built once per request from two sources, in this order of authority:

    1. ``place_atlas``'s towns, as SEEDS. Published town coordinates, checked by eye against the
       records that produced them, each tagged with the district it sits in. They are seeds rather
       than the answer so that real pins can move an anchor off a town and onto the villages actually
       being documented.
    2. Real ``subjectLatitude``/``subjectLongitude`` pins on ``Location`` rows that also state a
       district. Each pin is a vote; the anchor is the mean.

    An anchor is deliberately built from the WHOLE Location table rather than from the rows the
    current filters select. A pin that moved when you ticked a date range would be a map that
    disagrees with itself, and a reader comparing two filtered views would read the movement as the
    craft having moved.
    """

    def __init__(self) -> None:
        self._votes: dict[str, _Votes] = {}
        self._seeded: set[str] = set()

    # -- construction -------------------------------------------------------------------------

    def seed_from_atlas(self) -> None:
        """Take a starting position for every district ``place_atlas`` can name.

        Imported lazily: ``place_atlas`` imports ``address``, this module imports ``address``, and
        keeping the atlas out of this module's import graph means the atlas can be deleted one day
        without touching the ladder above it.
        """
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
        """Fold every ``Location`` row that has a stated district AND a subject pin into the anchors.

        Returns how many rows actually voted, which the route reports so a reader can tell an anchor
        backed by forty pins from one backed by none.
        """
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

    # -- reading ------------------------------------------------------------------------------

    def anchor(self, state: str, district: str) -> tuple[float, float] | None:
        """The learned position of one district, or None when nothing has voted for it."""
        votes = self._votes.get(district_key(state, district))
        return votes.point if votes and votes.count else None

    def vote_count(self, state: str, district: str) -> int:
        return (self._votes.get(district_key(state, district)) or _Votes()).count

    @property
    def anchored_districts(self) -> int:
        return len(self._votes)


# ---------------------------------------------------------------------------------------------
# Turning one stated address into a point at a given level
# ---------------------------------------------------------------------------------------------


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
    """Place one record group from its stated address, at ``level``.

    ``pin_precision`` / ``pin_source`` describe WHAT the passed coordinate pair actually is, and they
    exist so the legacy free-text path can reuse this whole function. ``place_atlas`` hands over a
    published TOWN coordinate rather than a dropped pin, so it passes its own precision and
    ``SOURCE_PLACE_TEXT`` — otherwise a name looked up in a thirteen-row table would be reported to the
    client as a measurement somebody took, which is the one overstatement this subsystem exists to
    prevent. The defaults describe a real subject pin, which is the common case.

    Returns ``None`` when the address says nothing placeable — no state, no district, no pin. The
    caller then falls back to the free-text ``place`` atlas, and reports the group as UNPLACED if that
    fails too. Nothing is ever dropped silently: a place missing from a map is indistinguishable from
    a place with no records, which is the one thing this whole subsystem is built not to do.

    HOW THE LEVEL CHANGES THE ANSWER, and what it deliberately does not change:

    * ``DISTRICT`` — the finest unit. A subject pin is used AS the position, so a record whose pin is
      in a village 60 km from the district headquarters draws in the village. The key is still the
      district, so all of that district's records fold into one pin and the pin sits at the mean of
      the real pins inside it.
    * ``STATE`` — the district is dropped from the key and every district in the state folds together.
      The position is still driven by real coordinates where they exist (the mean of the pins in that
      state), falling back to the seat, so a state whose work is all in one corner does not draw in
      its capital.
    * ``NATION`` — one key for the whole country.

    In every case the position prefers MEASURED coordinates to looked-up ones, and ``precision`` and
    ``source`` say which was used. The level changes the GROUPING; it never upgrades a lookup into a
    measurement.
    """
    state_name = canonical_state(state)
    district_name = (
        canonical_district(state_name, district) if state_name and district else None
    )
    pin = (
        (float(subject_latitude), float(subject_longitude))
        if subject_latitude is not None and subject_longitude is not None
        else None
    )

    # Whether the coordinate below is a real pin or a looked-up stand-in. Tracked rather than
    # re-derived from ``subject_latitude`` because a pin needs BOTH halves, and half a pin is a row
    # shape that exists.
    used_pin = pin is not None

    if level is AdminLevel.NATION:
        # One point for the country. It still needs a coordinate, and the honest one is whatever the
        # records themselves average to — which the caller computes, because only it has every group.
        # A pin here is a real coordinate to average; a state-only address contributes its seat.
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
            # `used_pin`, NOT "the caller passed a latitude". A `subjectLatitude` with a NULL
            # `subjectLongitude` is a real row shape (half a pin), and it leaves `pin` unset above, so
            # the coordinate here is the STATE SEAT. Reporting that as SUBJECT_PIN would call a lookup
            # a measurement and inflate the map's `pinnedRecords` — the precision overstatement this
            # module's header says it exists to prevent.
            source=pin_source if used_pin else SOURCE_STATED_ADDRESS,
        )

    if not state_name:
        # No state means nothing this ladder can place: a district without its state is ambiguous by
        # construction (see ``district_key``) and a bare pin is handled by the CAPTURE layer.
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

    # DISTRICT level with a district in hand.
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
        # The district is named and valid but nothing has ever pinned it. Drawn at the state seat and
        # SAYING so, which is the difference between an approximation and a fabrication.
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
    """A state-level position: a real coordinate if there is one, else the seat.

    ``state`` is unused in the body and kept in the signature because every caller has it and a future
    per-state rule (a state whose seat is disputed, say) belongs here rather than at the call site.
    """
    if pin is not None:
        return pin[0], pin[1], pin_precision, pin_source
    if seat is not None:
        return seat[1], seat[2], PRECISION_STATE, SOURCE_STATED_ADDRESS
    return None, None, PRECISION_STATE, SOURCE_STATED_ADDRESS


def parse_stated_key(key: str) -> tuple[str, str | None, str | None] | None:
    """Invert a :attr:`StatedPoint.key` back into (kind, state, district).

    Every stated key is invertible, which is the property the "records behind this pin" route needs
    and which the free-text atlas keys DO NOT have (three spellings of Bareilly resolve to one key and
    nothing records which three, so that route has to re-run the grouping — see
    ``map_points._origin_narrowing``). Building the stated keys out of the canonical names means the
    same route can answer a stated pin with one indexed query instead.
    """
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
    """How much of the structured address the corpus in play actually holds.

    Reported to the client because the map's quality is now a function of the address fields, so
    "why is this record at the state capital" has an answer a researcher can act on: go and fill in
    the district, or drop a pin. Without this the reader can see a coarse pin and not know whether it
    is the map's fault or the record's.
    """
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
    """Weighted mean of (latitude, longitude, weight) triples, or None when there are none.

    Used to move an aggregated pin onto the centre of gravity of what it folds, rather than leaving it
    wherever the first contributing group happened to be.
    """
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
    """The canonical state list this module has a seat for — asserted equal to ``address``'s."""
    return INDIAN_STATES_AND_UNION_TERRITORIES


def seat_coverage() -> Mapping[str, tuple[str, float, float]]:
    """The seat table, for the reference endpoint and the tests."""
    return STATE_SEATS
