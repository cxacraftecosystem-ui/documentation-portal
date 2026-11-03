from __future__ import annotations

import pytest

from app.services.address import (
    DISTRICT_AMENDMENTS,
    DISTRICT_COUNT,
    DISTRICTS_BY_STATE,
    INDIAN_STATES_AND_UNION_TERRITORIES,
    DistrictReconciliationError,
    address_reference,
    district_error,
    districts_for_state,
    normalize_district,
    reconcile_geocoded_district,
    validate_district,
)
from app.services.text_format import title_case


def test_every_state_and_union_territory_has_districts() -> None:
    assert set(DISTRICTS_BY_STATE) == set(INDIAN_STATES_AND_UNION_TERRITORIES)
    for state, districts in DISTRICTS_BY_STATE.items():
        assert districts, f"{state} has no districts"


def test_every_district_maps_to_exactly_one_state() -> None:
    for state, districts in DISTRICTS_BY_STATE.items():
        assert len(districts) == len(set(districts)), f"{state} lists a district twice"

    pairs = [(state, name) for state, names in DISTRICTS_BY_STATE.items() for name in names]
    assert len(pairs) == len(set(pairs)) == DISTRICT_COUNT

    for state, name in pairs:
        assert normalize_district(state, name) == name
        assert district_error(state, name) is None


def test_districts_are_sorted_and_title_case_fixed_points() -> None:
    for state, districts in DISTRICTS_BY_STATE.items():
        assert list(districts) == sorted(districts), f"{state} is not in alphabetical order"
        for name in districts:
            assert title_case(name) == name, f"{state} / {name!r} is rewritten by title_case"


def test_amendments_agree_with_the_list() -> None:
    for state, change, district, replaces, effective in DISTRICT_AMENDMENTS:
        assert state in DISTRICTS_BY_STATE, f"amendment names an unknown state: {state}"
        assert effective, f"{state} / {district} has no effective date"
        districts = DISTRICTS_BY_STATE[state]
        if change in {"created", "renamed"}:
            assert district in districts, f"{change} {district!r} is missing from {state}"
        elif change == "dissolved":
            assert district not in districts, f"dissolved {district!r} is still listed under {state}"
        else:
            pytest.fail(f"unknown amendment type {change!r}")
        if change == "renamed":
            assert replaces, f"renamed {district!r} does not say what it replaces"
            assert replaces not in districts, f"{state} still lists the old name {replaces!r}"
            assert normalize_district(state, replaces) == district
        else:
            assert not replaces, f"{change} {district!r} should not name a predecessor"


def test_every_alias_points_at_a_real_district_of_its_state() -> None:
    from app.services.address import _DISTRICT_ALIASES

    for state, aliases in _DISTRICT_ALIASES.items():
        assert state in DISTRICTS_BY_STATE, f"aliases for unknown state {state!r}"
        for spelling, target in aliases.items():
            assert target in DISTRICTS_BY_STATE[state], (
                f"alias {state} / {spelling!r} points at {target!r}, which is not a district there"
            )
            assert normalize_district(state, spelling) == target



@pytest.mark.parametrize(
    ("state", "returned", "expected"),
    [
        ("Jammu and Kashmir", "Jammu district", "Jammu"),
        ("Maharashtra", "Akola District", "Akola"),
        ("Gujarat", "Kutch", "Kachchh"),
        ("Rajasthan", "JAIPUR DISTRICT", "Jaipur"),
        ("Rajasthan", "  balotra  ", "Balotra"),
        ("Uttarakhand", "Rudra Prayag", "Rudraprayag"),
        ("West Bengal", "24 Paraganas North", "North 24 Parganas"),
    ],
)
def test_geocoder_spellings_reconcile(state: str, returned: str, expected: str) -> None:
    assert reconcile_geocoded_district(state, returned) == expected


def test_geocoder_silence_is_not_an_error() -> None:
    assert reconcile_geocoded_district("Gujarat", None) is None
    assert reconcile_geocoded_district("Gujarat", "   ") is None


def test_unreconcilable_geocoder_value_raises_rather_than_writing_free_text() -> None:
    with pytest.raises(DistrictReconciliationError) as caught:
        reconcile_geocoded_district("Gujarat", "Bhuj Taluka")

    assert caught.value.state == "Gujarat"
    assert caught.value.value == "Bhuj Taluka"
    assert "Bhuj Taluka" in str(caught.value)


def test_dissolved_district_is_not_guessed_at() -> None:
    with pytest.raises(DistrictReconciliationError):
        reconcile_geocoded_district("Delhi", "Shahdara")



def test_district_of_the_wrong_state_is_rejected_and_names_the_right_one() -> None:
    message = district_error("Rajasthan", normalize_district("Rajasthan", "Kachchh"))

    assert message is not None
    assert "Gujarat" in message
    assert "Rajasthan" in message

    with pytest.raises(ValueError, match="Gujarat"):
        validate_district("Rajasthan", "Kachchh")


def test_a_name_shared_by_two_states_resolves_per_state() -> None:
    assert validate_district("Chhattisgarh", "bilaspur") == "Bilaspur"
    assert validate_district("Himachal Pradesh", "BILASPUR") == "Bilaspur"
    assert validate_district("Maharashtra", "Raigarh") == "Raigad"
    assert validate_district("Chhattisgarh", "Raigarh") == "Raigarh"


def test_wrong_state_error_lists_every_state_that_has_the_district() -> None:
    message = district_error("Kerala", normalize_district("Kerala", "Hamirpur"))

    assert message is not None
    assert "Himachal Pradesh" in message
    assert "Uttar Pradesh" in message


def test_district_without_a_state_asks_for_the_state_first() -> None:
    message = district_error(None, "Jaipur")

    assert message is not None
    assert "state" in message.lower()


def test_unknown_state_reports_the_state_not_the_district() -> None:
    message = district_error("Atlantis", "Jaipur")

    assert message is not None
    assert "Atlantis" in message


def test_a_state_alias_still_scopes_the_district() -> None:
    assert validate_district("Orissa", "Khurda") == "Khordha"
    assert districts_for_state("orissa") == DISTRICTS_BY_STATE["Odisha"]


def test_unknown_district_names_the_list_vintage() -> None:
    message = district_error("Rajasthan", normalize_district("Rajasthan", "Sanganer"))

    assert message is not None
    assert "2026-07-26" in message


def test_blank_district_is_accepted_here() -> None:
    assert validate_district("Gujarat", None) is None
    assert validate_district("Gujarat", "   ") is None



def test_reference_payload_carries_the_districts_and_their_provenance() -> None:
    payload = address_reference()
    districts = payload["districts"]

    assert districts["count"] == DISTRICT_COUNT
    assert set(districts["byState"]) == set(payload["statesAndUnionTerritories"])
    assert districts["byState"]["Rajasthan"] == list(DISTRICTS_BY_STATE["Rajasthan"])
    assert districts["asOf"]
    assert "Local Government Directory" in districts["source"]
    assert "lgdirectory.gov.in" in districts["sourceUrl"]


def test_reference_payload_stays_small_enough_for_a_field_connection() -> None:
    import gzip
    import json

    encoded = json.dumps(address_reference(), separators=(",", ":")).encode()

    assert len(encoded) < 20_000
    assert len(gzip.compress(encoded, 9)) < 8_000


def test_balotra_is_present() -> None:
    assert "Balotra" in DISTRICTS_BY_STATE["Rajasthan"]
    assert validate_district("Rajasthan", "balotra") == "Balotra"
