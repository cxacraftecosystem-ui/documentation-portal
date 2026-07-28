"""Turning the free-text ``place`` column into something a map can draw — and being honest about it.

WHY THIS EXISTS, and why it is separate from the ``Location`` relation
----------------------------------------------------------------------
This repository holds TWO different geographies, and conflating them produces a map that is
confidently wrong.

``Location`` (latitude/longitude/accuracy) records WHERE THE RECORD WAS MADE. It is measured, it
comes off the phone's GPS, and across the live corpus every single fix lands inside a box roughly
800 m across at the workshop venue in Kharagpur — because that is where the artisans were brought
and where the researchers sat typing. Drawn on a map of India it is one dot, correctly.

``place`` (free text on artisan / workshop / product / tool / interview) records WHERE THE CRAFT IS
FROM: Bagru, Bareilly, Kachchh, Almora, Jammu. That is the geography a reader means by "where does
the documented work come from", and it exists ONLY as prose a researcher typed — sixteen artisans
produced sixteen distinct spellings, three of them variants of "Bareilly, Uttar Pradesh".

This module resolves the second kind. It cannot be as good as the first kind and must never pretend
to be, so every resolution carries a :class:`Precision` saying how much to trust it, and anything it
cannot resolve is returned as UNPLACED rather than dropped — a place quietly missing from a map is
indistinguishable from a place with no records.

WHY A TABLE AND NOT A GEOCODER
------------------------------
A network geocoder would put "Akola, Chittorgarh" in Maharashtra, which is a different Akola 900 km
away, and it would put every unrecognised string somewhere rather than admitting it does not know.
It would also be a per-request outbound call from a 1 GiB box. The corpus names a couple of dozen
craft places; a table of those places, each of which can be checked by eye against the record that
produced it, is both smaller and more truthful.

WHY STATE-ONLY TEXT RESOLVES TO A CAPITAL, NOT A CENTROID
---------------------------------------------------------
"Rajasthan" with no town names a region, not a point, so any single coordinate for it is a
convention. A computed centroid invites the question "centroid of which boundary?", which for Jammu
and Kashmir is a question this codebase has no business answering. A state capital is a real place,
published, and unambiguous — so STATE-precision entries are drawn at the capital and SAY they are.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

from app.services.address import _LOOKUP as _STATE_LOOKUP
from app.services.address import _fold


class Precision(str, Enum):
    """How far the drawn point may be from what the researcher meant.

    Carried through to the API and shown on the pin, because a reader who cannot tell a measured
    fix from a district stand-in will read the second as the first.
    """

    #: A named town or city. Within a few kilometres of the place typed.
    TOWN = "TOWN"
    #: A district, drawn at its headquarters — the record named a village this table cannot place.
    DISTRICT = "DISTRICT"
    #: Only a state was typed. Drawn at the state capital, which may be hundreds of km away.
    STATE = "STATE"


@dataclass(frozen=True)
class Place:
    """One resolvable craft place. ``key`` is stable and is what the API and the URL carry."""

    key: str
    label: str
    #: The administrative line under the label — district and state, or just the state.
    region: str
    state: str
    latitude: float
    longitude: float
    precision: Precision
    #: Folded spellings that resolve here. Matched as a whole token-run, longest first.
    aliases: tuple[str, ...]
    #: The CANONICAL district this place sits in, as ``services.address`` spells it.
    #:
    #: Added so this table can SEED ``geography.DistrictAnchors``: each row is a published town
    #: coordinate that was checked by eye, which is a far better starting position for its district
    #: than the state capital. It is a separate field rather than parsed out of ``region`` because
    #: ``region`` is prose meant for a human ("Kachchh district, Gujarat (shown at Bhuj)") and parsing
    #: prose to recover a canonical name is how the two quietly disagree.
    #:
    #: Optional only for a STATE-precision entry, which by definition names no district.
    district: str | None = None


# Every craft place the live corpus names, plus the workshop venue. Coordinates are the published
# location of the named town or district headquarters — NOT measurements, which is what `precision`
# is for.
#
# Two entries are worth reading twice, because a general-purpose geocoder gets both wrong:
#   * "Akola, Chittorgarh" is a Dabu-printing village in RAJASTHAN. The Akola that most gazetteers
#     return first is a city in Maharashtra, 900 km away.
#   * "Rudraprayag, Dehradun" pairs a town with the wrong district — Rudraprayag is its own district.
#     The town is real and unambiguous, so it resolves; the typed text is preserved beside it.
_PLACES: tuple[Place, ...] = (
    # Rajasthan — block printing
    Place("bagru", "Bagru", "Jaipur district, Rajasthan", "Rajasthan",
          26.8149, 75.5449, Precision.TOWN, ("bagru",), district="Jaipur"),
    Place("sanganer", "Sanganer", "Jaipur district, Rajasthan", "Rajasthan",
          26.8168, 75.7889, Precision.TOWN, ("sanganer", "sanganeri"), district="Jaipur"),
    Place("balotra", "Balotra", "Barmer district, Rajasthan", "Rajasthan",
          25.8318, 72.2400, Precision.TOWN, ("balotra", "balotara"), district="Barmer"),
    Place("akola-chittorgarh", "Akola", "Chittorgarh district, Rajasthan", "Rajasthan",
          24.8887, 74.6269, Precision.DISTRICT, ("akolachittorgarh", "akolarajasthan", "akola"),
          district="Chittorgarh"),
    # Gujarat — Ajrakh
    Place("kachchh", "Kachchh", "Kachchh district, Gujarat (shown at Bhuj)", "Gujarat",
          23.2419, 69.6669, Precision.DISTRICT, ("kachchh", "kutch", "kachchhbhuj", "bhuj"),
          district="Kachchh"),
    # Uttar Pradesh — cane and bamboo
    Place("bareilly", "Bareilly", "Bareilly district, Uttar Pradesh", "Uttar Pradesh",
          28.3670, 79.4304, Precision.TOWN, ("bareilly", "barreilly", "bareily"), district="Bareilly"),
    # Uttarakhand — Ringal
    Place("almora", "Almora", "Almora district, Uttarakhand", "Uttarakhand",
          29.5892, 79.6467, Precision.TOWN, ("almora", "basaralmora", "basar"), district="Almora"),
    Place("bageshwar", "Bageshwar", "Bageshwar district, Uttarakhand", "Uttarakhand",
          29.8373, 79.7710, Precision.TOWN, ("bageshwar", "bageswar", "bagheswar", "bagheshwar"),
          district="Bageshwar"),
    Place("rudraprayag", "Rudraprayag", "Rudraprayag district, Uttarakhand", "Uttarakhand",
          30.2844, 78.9811, Precision.TOWN, ("rudraprayag",), district="Rudraprayag"),
    Place("dehradun", "Dehradun", "Dehradun district, Uttarakhand", "Uttarakhand",
          30.3165, 78.0322, Precision.TOWN, ("dehradun", "ballupur", "ballupurdehradun"),
          district="Dehradun"),
    # Jammu and Kashmir
    Place("jammu", "Jammu", "Jammu district, Jammu and Kashmir", "Jammu and Kashmir",
          32.7266, 74.8570, Precision.TOWN, ("jammu", "satwari", "oldsatwari"), district="Jammu"),
    # Andhra Pradesh — Kalamkari. LGD split the old Krishna district in 2022 and Kappaladoddi fell
    # into the new Bapatla district; the record's own prose still says Krishna, which is why the
    # human-facing `region` keeps it and the machine-facing `district` does not.
    Place("kappaladoddi", "Kappaladoddi", "Krishna district, Andhra Pradesh", "Andhra Pradesh",
          16.1875, 81.1389, Precision.DISTRICT, ("kappaladoddi", "kappladoddi"), district="Krishna"),
    # West Bengal — the workshop venue itself, so the typed address and the GPS fix agree
    Place("kharagpur", "Kharagpur", "Paschim Medinipur district, West Bengal", "West Bengal",
          22.3149, 87.3105, Precision.TOWN, ("kharagpur", "iitkharagpur"), district="Paschim Medinipur"),
)


def atlas_district_anchors() -> tuple[tuple[str, str, float, float], ...]:
    """(state, district, latitude, longitude) for every entry that names a district.

    Read by ``geography.DistrictAnchors.seed_from_atlas``, which is the ONLY consumer and the reason
    :attr:`Place.district` exists. A row whose district this build of ``services.address`` does not
    recognise is dropped there rather than here, so a district rename shows up as a lost seed and not
    as an import-time explosion.
    """
    return tuple(
        (place.state, place.district, place.latitude, place.longitude)
        for place in _PLACES
        if place.district
    )

# Where a STATE-precision point is drawn: the capital, named on the pin. Only the states and union
# territories the corpus can actually reach need an entry — an unlisted state resolves to UNPLACED,
# which is the honest answer, rather than to a coordinate nobody chose.
_STATE_SEATS: dict[str, tuple[str, float, float]] = {
    "Andhra Pradesh": ("Amaravati", 16.5062, 80.6480),
    "Gujarat": ("Gandhinagar", 23.2156, 72.6369),
    "Jammu and Kashmir": ("Srinagar", 34.0837, 74.7973),
    "Ladakh": ("Leh", 34.1526, 77.5771),
    "Maharashtra": ("Mumbai", 19.0760, 72.8777),
    "Rajasthan": ("Jaipur", 26.9124, 75.7873),
    "Uttar Pradesh": ("Lucknow", 26.8467, 80.9462),
    "Uttarakhand": ("Dehradun", 30.3165, 78.0322),
    "West Bengal": ("Kolkata", 22.5726, 88.3639),
}

# Misspellings of state names that appear in the live corpus but that `services.address` will not
# accept. They live HERE rather than there deliberately: address.py governs what may be WRITTEN to
# the `state` column and must stay strict, while this module only decides where to draw a dot, and a
# dot in the right state beats a record missing from the map. Two-letter codes are included for the
# same reason and only for the same reason — never widen address.py with these.
_EXTRA_STATE_ALIASES: dict[str, str] = {
    "gujrat": "Gujarat",
    "uttrakhand": "Uttarakhand",
    "uttaranchal": "Uttarakhand",
    "up": "Uttar Pradesh",
    "jandk": "Jammu and Kashmir",
    "jk": "Jammu and Kashmir",
}

_ALIAS_TO_PLACE: dict[str, Place] = {alias: place for place in _PLACES for alias in place.aliases}

# The longest run of words any name here needs: "Andaman & Nicobar Islands" is four tokens once the
# ampersand becomes one of them, and a couple of the union territory names are longer still.
_MAX_ALIAS_WORDS = 6

# Anything that is not a letter, a digit or an ampersand separates one part of an address from the
# next: commas, slashes, the stray trailing comma in "Jammu&Kashmir,". Digits are kept inside tokens
# so a pincode does not silently merge with the word before it, and stripped again below because
# "Gujrat0" is a typing slip, not a different state.
#
# The AMPERSAND IS ITS OWN TOKEN rather than a separator, and rejoins as the word "and". `_fold`
# already spells "&" out, but it never got the chance: splitting on it first turned "Jammu &
# Kashmir" into "jammukashmir", which matches no state, so an entire union territory was resolved to
# Jammu city instead — the precision overstatement this module exists to prevent.
_SPLIT = re.compile(r"[^A-Za-z0-9&]+")
_TRAILING_DIGITS = re.compile(r"\d+$")


def _tokens(text: str) -> list[str]:
    out: list[str] = []
    for part in _SPLIT.split(text):
        for piece in re.split(r"(&)", part):
            if piece:
                out.append("and" if piece == "&" else piece)
    return out


def _resolve_state(token_run: str) -> str | None:
    """A canonical state name for one run of words, or None."""
    folded = _fold(token_run)
    # "Gujrat0" — a digit that fell in from the pincode beside it. Try the word without it too.
    candidates = (folded, _TRAILING_DIGITS.sub("", folded))
    for candidate in candidates:
        if not candidate:
            continue
        if candidate in _EXTRA_STATE_ALIASES:
            return _EXTRA_STATE_ALIASES[candidate]
        resolved = _STATE_LOOKUP.get(candidate)
        if resolved:
            return resolved
    return None


@dataclass(frozen=True)
class Resolution:
    """Where one typed place string lands. ``place`` is None when nothing could be resolved."""

    place: Place | None
    #: Set only for a STATE-precision resolution — the capital the point is drawn at.
    seat: str | None = None


def resolve_place(text: str | None) -> Resolution:
    """Resolve one ``place`` string to a drawable point.

    A town beats a state — "Bagru, Rajasthan" is Bagru, and answering "Jaipur" because the string
    also names a state would throw away the only precise thing in it. But the state is matched FIRST
    and its words are then taken out of play, which is what stops the opposite error: "Jammu &
    Kashmir" names a territory, and the town table happens to hold a "jammu", so a plain
    town-first scan drew a whole state as if the record had said Jammu city.

    "Old Satwari, Jammu, Jammu&Kashmir" exercises both halves at once — the trailing state is
    consumed, and "Satwari" in what remains still resolves to Jammu city, correctly.
    """
    if not text or not text.strip():
        return Resolution(None)

    words = _tokens(text)
    if not words:
        return Resolution(None)

    state: str | None = None
    consumed: range = range(0)
    for width in range(min(_MAX_ALIAS_WORDS, len(words)), 0, -1):
        for start in range(len(words) - width + 1):
            found = _resolve_state("".join(words[start:start + width]))
            if found and found in _STATE_SEATS:
                state, consumed = found, range(start, start + width)
                break
        if state:
            break

    # Longest runs first, so a two-word town is never missed because its first word matched
    # something shorter; leftmost wins at equal width, which keeps "Rudraprayag, Dehradun" on the
    # town the researcher led with.
    for width in range(min(_MAX_ALIAS_WORDS, len(words)), 0, -1):
        for start in range(len(words) - width + 1):
            if any(index in consumed for index in range(start, start + width)):
                continue
            run = _fold("".join(words[start:start + width]))
            place = _ALIAS_TO_PLACE.get(run) or _ALIAS_TO_PLACE.get(_TRAILING_DIGITS.sub("", run))
            if place:
                return Resolution(place)

    if state:
        name, latitude, longitude = _STATE_SEATS[state]
        return Resolution(
            Place(
                key=f"state-{_fold(state)}",
                label=state,
                region=f"State-level only — drawn at {name}",
                state=state,
                latitude=latitude,
                longitude=longitude,
                precision=Precision.STATE,
                aliases=(),
            ),
            seat=name,
        )

    return Resolution(None)
