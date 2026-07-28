"""Which district a newer district was carved out of, for the 43 that have no border of their own.

WHY THIS EXISTS
---------------
``scripts/build_boundaries.py`` gives 752 of the register's 795 districts a real border, derived from a
published dataset. The remaining 43 were all notified between 2021 and 2024, after that dataset's
vintage, and **no public boundary dataset carries them.** A new district is carved out of a parent
along sub-district lines, so without tehsil or block geometry the split line cannot be derived — only
invented, and inventing an administrative border is exactly what this codebase does not do.

So instead of a fabricated border, each of the 43 names its PARENT. A record in Balotra then draws
inside Barmer's border — the district Balotra was carved from — and the map says which outline it is
looking at. That is the same discipline ``services/geography`` already uses when a district has no
learned anchor: draw at the state seat and SAY so. A coarser answer that admits it beats a precise
answer that is made up.

WHAT A PARENT IS HERE, PRECISELY
--------------------------------
The district whose territory the new one was taken from, as named in the notification that created it.
Where a district was assembled from parts of two or more parents, ALL of them are listed and the FIRST
is the one whose border is drawn — chosen as the parent contributing the larger share, and the rest are
recorded so a reader can see the split was not clean. ``Kotputli-Behror`` is the clearest case: Kotputli
came from Jaipur and Behror from Alwar.

A parent must itself be a district the register knows AND one the border set covers, or the fallback
has nothing to draw. The tests assert both, so a future border refresh that removes a parent — because
the parent was itself dissolved — fails loudly rather than silently drawing nothing.

THREE DELHI ENTRIES FALL BACK TO THE STATE BORDER INSTEAD OF A PARENT
--------------------------------------------------------------------
The Local Government Directory lists thirteen districts for Delhi — Central, Central North, East, New
Delhi, North, North East, North West, Old Delhi, Outer North, South, South East, South West, West — and
the register matches it exactly, because LGD is the register's documented base. Three of those thirteen
have no border: "Central North", "Old Delhi" and "Outer North".

The published boundary data instead carries the eleven names in common circulation, which include
``Shahdara`` — a name LGD does not use. Shahdara's polygon therefore joins to no register district. That
costs nothing: ``build_boundaries.py`` feeds EVERY polygon to the edge classifier regardless of whether
the register can name it, so Shahdara's borders are drawn like any other district's; it simply is not a
key anything looks up.

What the three cannot have is a PARENT, because the notification that would say which district each was
carved from is not something this file can state, and a plausible-looking guess at the parentage of a
Delhi district is exactly the kind of invention the rest of this module exists to avoid. So they fall
back one level further out, to Delhi's own border — see :data:`STATE_FALLBACK`. For Delhi specifically
that is a genuinely useful answer rather than a shrug: the whole National Capital Territory is about
1,500 square kilometres, so "somewhere in Delhi" locates a record more tightly than a district-level
outline does in most of the country.
"""

from __future__ import annotations

#: ``(state, district) -> parents``, most-contributing parent first.
#:
#: Every row is one notification. Grouped by the notifying state and dated in the comment above each
#: group, because that date is what a reader needs to judge whether a border refresh should have
#: superseded the row — if a newer dataset covers the district, delete the row rather than keeping a
#: fallback nobody needs.
DISTRICT_PARENTS: dict[tuple[str, str], tuple[str, ...]] = {
    # Andhra Pradesh reorganised into 26 districts in April 2022; two of the register's names are from
    # the later 2022 revision and are absent from the boundary vintage.
    ("Andhra Pradesh", "Markapuram"): ("Prakasam",),
    ("Andhra Pradesh", "Polavaram"): ("Eluru",),
    # Arunachal Pradesh: Bichom and Keyi Panyor, 2023-2024.
    ("Arunachal Pradesh", "Bichom"): ("East Kameng", "West Kameng"),
    ("Arunachal Pradesh", "Keyi Panyor"): ("Lower Subansiri",),
    # Assam: Tamulpur, carved from Baksa in January 2022.
    ("Assam", "Tamulpur"): ("Baksa",),
    # Chhattisgarh created five districts in 2022.
    ("Chhattisgarh", "Khairgarh Chhuikhadan Gandai"): ("Rajnandgaon",),
    ("Chhattisgarh", "Manendragarh Chirimiri Bharatpur"): ("Korea",),
    ("Chhattisgarh", "Mohla Manpur Ambagarh Chouki"): ("Rajnandgaon",),
    ("Chhattisgarh", "Sakti"): ("Janjgir-Champa",),
    ("Chhattisgarh", "Sarangarh Bilaigarh"): ("Raigarh", "Baloda Bazar"),
    # Goa: Kushavati, from the 2023 three-district reorganisation.
    ("Goa", "Kushavati"): ("South Goa",),
    # Gujarat: Vav-Tharad, split from Banaskantha in 2024.
    ("Gujarat", "Vav-Tharad"): ("Banaskantha",),
    # Haryana: Hansi, notified out of Hisar.
    ("Haryana", "Hansi"): ("Hisar",),
    # Ladakh created five districts in August 2024, out of the two it had.
    ("Ladakh", "Changthang"): ("Leh",),
    ("Ladakh", "Drass"): ("Kargil",),
    ("Ladakh", "Nubra"): ("Leh",),
    ("Ladakh", "Sham"): ("Leh",),
    ("Ladakh", "Zanskar"): ("Kargil",),
    # Madhya Pradesh, 2023.
    ("Madhya Pradesh", "Maihar"): ("Satna",),
    ("Madhya Pradesh", "Mauganj"): ("Rewa",),
    ("Madhya Pradesh", "Pandhurna"): ("Chhindwara",),
    # Nagaland created five districts in 2021-2022.
    ("Nagaland", "Chumoukedima"): ("Dimapur",),
    ("Nagaland", "Meluri"): ("Phek",),
    ("Nagaland", "Niuland"): ("Dimapur",),
    ("Nagaland", "Shamator"): ("Tuensang",),
    ("Nagaland", "Tseminyu"): ("Kohima",),
    # Rajasthan created nineteen districts in August 2023; eight of them are in the register.
    ("Rajasthan", "Balotra"): ("Barmer",),
    ("Rajasthan", "Beawar"): ("Ajmer",),
    ("Rajasthan", "Deeg"): ("Bharatpur",),
    ("Rajasthan", "Didwana-Kuchaman"): ("Nagaur",),
    ("Rajasthan", "Khairthal-Tijara"): ("Alwar",),
    ("Rajasthan", "Kotputli-Behror"): ("Jaipur", "Alwar"),
    ("Rajasthan", "Phalodi"): ("Jodhpur",),
    ("Rajasthan", "Salumbar"): ("Udaipur",),
    # Sikkim renamed its four districts and split two more out of them in 2021. The renames resolve
    # through ``address._DISTRICT_ALIASES``; these two are genuinely new territory.
    ("Sikkim", "Pakyong"): ("Gangtok",),
    ("Sikkim", "Soreng"): ("Gyalshing",),
    # West Bengal announced these in 2022. Their notification status has moved slowly, which is
    # precisely why no boundary dataset carries them.
    ("West Bengal", "Arambagh"): ("Hooghly",),
    ("West Bengal", "Basirhat"): ("North 24 Parganas",),
    ("West Bengal", "Jangipur"): ("Murshidabad",),
    ("West Bengal", "Sundarbans"): ("South 24 Parganas",),
}

#: Districts with no border of their own and no parent this file can name, which therefore borrow their
#: STATE'S border. See the header for why Delhi's three are here rather than in
#: :data:`DISTRICT_PARENTS`: LGD lists them, the boundary source does not carry them, and the
#: notification that would say what each was carved from is not something to guess at.
#:
#: A coarser outline that is CORRECT beats a district-shaped one that is invented, and the map says
#: which of the two it is showing.
STATE_FALLBACK: tuple[tuple[str, str], ...] = (
    ("Delhi", "Central North"),
    ("Delhi", "Old Delhi"),
    ("Delhi", "Outer North"),
)


def parent_for(state: str, district: str) -> str | None:
    """The district whose border stands in for this one, or ``None`` when it has its own.

    ``None`` covers both "this district has a border of its own" and "nothing can be drawn for it" —
    the caller cannot act differently on those two, and the second is reported separately by
    :func:`lineage_reference` rather than inferred from a null here.
    """
    parents = DISTRICT_PARENTS.get((state, district))
    return parents[0] if parents else None


def all_parents(state: str, district: str) -> tuple[str, ...]:
    """Every district this one was carved from, most-contributing first. Empty when it has its own."""
    return DISTRICT_PARENTS.get((state, district), ())


def draws_state_border(state: str, district: str) -> bool:
    """Whether this district borrows its STATE'S border rather than a parent district's."""
    return (state, district) in STATE_FALLBACK


def lineage_reference() -> dict:
    """The lineage as JSON, for the clients' map legend.

    Served rather than duplicated: the web and Android both need to say "shown inside Barmer, which
    Balotra was carved from", and two hand-copied tables of 43 rows is how they drift apart.
    """
    return {
        "parents": {
            f"{state}|{district}": list(parents)
            for (state, district), parents in sorted(DISTRICT_PARENTS.items())
        },
        "stateFallback": [f"{state}|{district}" for state, district in STATE_FALLBACK],
        "note": (
            "These districts have no border of their own in the published boundary data. Their records "
            "still plot from the stated address. Where a parent is named the outline shown is the "
            "district they were carved from; the rest borrow their state's border, because the "
            "parentage is not something to guess at."
        ),
    }
