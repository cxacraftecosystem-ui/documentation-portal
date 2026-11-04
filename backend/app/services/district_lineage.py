from __future__ import annotations

DISTRICT_PARENTS: dict[tuple[str, str], tuple[str, ...]] = {
    ("Andhra Pradesh", "Markapuram"): ("Prakasam",),
    ("Andhra Pradesh", "Polavaram"): ("Eluru",),
    ("Arunachal Pradesh", "Bichom"): ("East Kameng", "West Kameng"),
    ("Arunachal Pradesh", "Keyi Panyor"): ("Lower Subansiri",),
    ("Assam", "Tamulpur"): ("Baksa",),
    ("Chhattisgarh", "Khairgarh Chhuikhadan Gandai"): ("Rajnandgaon",),
    ("Chhattisgarh", "Manendragarh Chirimiri Bharatpur"): ("Korea",),
    ("Chhattisgarh", "Mohla Manpur Ambagarh Chouki"): ("Rajnandgaon",),
    ("Chhattisgarh", "Sakti"): ("Janjgir-Champa",),
    ("Chhattisgarh", "Sarangarh Bilaigarh"): ("Raigarh", "Baloda Bazar"),
    ("Goa", "Kushavati"): ("South Goa",),
    ("Gujarat", "Vav-Tharad"): ("Banaskantha",),
    ("Haryana", "Hansi"): ("Hisar",),
    ("Ladakh", "Changthang"): ("Leh",),
    ("Ladakh", "Drass"): ("Kargil",),
    ("Ladakh", "Nubra"): ("Leh",),
    ("Ladakh", "Sham"): ("Leh",),
    ("Ladakh", "Zanskar"): ("Kargil",),
    ("Madhya Pradesh", "Maihar"): ("Satna",),
    ("Madhya Pradesh", "Mauganj"): ("Rewa",),
    ("Madhya Pradesh", "Pandhurna"): ("Chhindwara",),
    ("Nagaland", "Chumoukedima"): ("Dimapur",),
    ("Nagaland", "Meluri"): ("Phek",),
    ("Nagaland", "Niuland"): ("Dimapur",),
    ("Nagaland", "Shamator"): ("Tuensang",),
    ("Nagaland", "Tseminyu"): ("Kohima",),
    ("Rajasthan", "Balotra"): ("Barmer",),
    ("Rajasthan", "Beawar"): ("Ajmer",),
    ("Rajasthan", "Deeg"): ("Bharatpur",),
    ("Rajasthan", "Didwana-Kuchaman"): ("Nagaur",),
    ("Rajasthan", "Khairthal-Tijara"): ("Alwar",),
    ("Rajasthan", "Kotputli-Behror"): ("Jaipur", "Alwar"),
    ("Rajasthan", "Phalodi"): ("Jodhpur",),
    ("Rajasthan", "Salumbar"): ("Udaipur",),
    ("Sikkim", "Pakyong"): ("Gangtok",),
    ("Sikkim", "Soreng"): ("Gyalshing",),
    ("West Bengal", "Arambagh"): ("Hooghly",),
    ("West Bengal", "Basirhat"): ("North 24 Parganas",),
    ("West Bengal", "Jangipur"): ("Murshidabad",),
    ("West Bengal", "Sundarbans"): ("South 24 Parganas",),
}

STATE_FALLBACK: tuple[tuple[str, str], ...] = (
    ("Delhi", "Central North"),
    ("Delhi", "Old Delhi"),
    ("Delhi", "Outer North"),
)


def parent_for(state: str, district: str) -> str | None:
    parents = DISTRICT_PARENTS.get((state, district))
    return parents[0] if parents else None


def all_parents(state: str, district: str) -> tuple[str, ...]:
    return DISTRICT_PARENTS.get((state, district), ())


def draws_state_border(state: str, district: str) -> bool:
    return (state, district) in STATE_FALLBACK


def lineage_reference() -> dict:
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
