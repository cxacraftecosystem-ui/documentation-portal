"""The place resolver, checked against the strings the live corpus actually holds.

Every ``assert`` below is a spelling a researcher really typed. That is the point: this table only
earns its place by resolving the prose the repository contains, and a synthetic fixture would let it
pass while the real data went unplaced.
"""

from app.services.place_atlas import Precision, resolve_place


def key_of(text: str) -> str | None:
    resolved = resolve_place(text)
    return resolved.place.key if resolved.place else None


def test_town_wins_over_the_state_beside_it():
    # "Bagru, Rajasthan" is Bagru. Answering "Jaipur" because a state is also present would throw
    # away the only precise thing in the string.
    assert key_of("Bagru, Rajasthan") == "bagru"
    assert key_of("Bagru, Jaipur, Rajasthan") == "bagru"
    assert key_of("Bagru") == "bagru"


def test_a_bare_state_stays_a_bare_state():
    # The opposite error, and the one that actually shipped: the town table holds "jammu", so a
    # town-first scan drew a whole union territory as if the record had named Jammu city.
    resolved = resolve_place("Jammu & Kashmir")
    assert resolved.place is not None
    assert resolved.place.precision is Precision.STATE
    assert resolved.seat == "Srinagar"

    resolved = resolve_place("Rajasthan")
    assert resolved.place is not None
    assert resolved.place.precision is Precision.STATE
    assert resolved.seat == "Jaipur"


def test_a_state_is_consumed_but_a_town_beside_it_still_resolves():
    # Exercises both halves at once: the trailing territory is taken out of play, and "Satwari" in
    # what remains still lands on Jammu city.
    resolved = resolve_place("Old Satwari, Jammu, Jammu&Kashmir,")
    assert resolved.place is not None
    assert resolved.place.key == "jammu"
    assert resolved.place.precision is Precision.TOWN


def test_the_spellings_one_place_arrived_under_all_agree():
    assert key_of("Bareilly, Uttar Pradesh") == "bareilly"
    assert key_of("Bareilly, Uttarpradesh") == "bareilly"
    assert key_of("Barreilly, Uttarpradesh") == "bareilly"
    assert key_of("Bareilly, UP") == "bareilly"

    assert key_of("Bageswar, Uttarakhand") == "bageshwar"
    assert key_of("Bagheswar") == "bageshwar"

    assert key_of("Balotra, Rajasthan") == "balotra"
    assert key_of("Balotara") == "balotra"
    assert key_of("Balotra,Rajasthan") == "balotra"

    assert key_of("Almora,Uttarakhand") == "almora"
    assert key_of("Basar, Almora") == "almora"

    assert key_of("Ballupur, Dehradun") == "dehradun"
    assert key_of("Ballupur, Uttrakhand") == "dehradun"


def test_a_pincode_digit_that_fell_into_the_state_name():
    # "Kutch, Gujrat0" — the trailing zero is the head of a pincode, not a different state.
    assert key_of("Kutch, Gujrat0") == "kachchh"
    assert key_of("Kutch, Gujrat") == "kachchh"


def test_akola_is_the_rajasthan_one():
    # The Akola most gazetteers return first is a city in Maharashtra, 900 km from the Dabu printing
    # village this repository documents. Both spellings the corpus uses must land in Rajasthan.
    for text in ("Akola, Chittorgarh", "Akola, Rajasthan"):
        resolved = resolve_place(text)
        assert resolved.place is not None
        assert resolved.place.state == "Rajasthan"


def test_a_town_inside_a_long_postal_address():
    resolved = resolve_place(
        "Centre of Excellence, Handicrafts, Agri Business Incubation Foundation (ABIF) Building, "
        "Indian Institute of Technology, Kharagpur, West Bengal, 721302"
    )
    assert resolved.place is not None
    assert resolved.place.key == "kharagpur"


def test_what_cannot_be_resolved_says_so():
    # Five interviews carry a single replacement character. Guessing at these is exactly the failure
    # this module is built to avoid.
    assert resolve_place("�").place is None
    assert resolve_place("").place is None
    assert resolve_place(None).place is None
    assert resolve_place("   ").place is None
    assert resolve_place("Somewhere Nobody Listed").place is None


def test_precision_is_never_overstated():
    # A village the table cannot place is drawn at its district headquarters and must SAY district.
    for text in ("Kappladoddi", "Kutch, Gujrat"):
        resolved = resolve_place(text)
        assert resolved.place is not None
        assert resolved.place.precision is Precision.DISTRICT
