"""The district list's invariants, and the geocoder spellings it has to absorb.

These tests exist because the district list is DATA, and data rots quietly. A district that silently
belongs to two states, an alias pointing at a name that no longer exists, or a name that
``title_case`` rewrites on write are all failures that produce a wrong export rather than an
exception, so each one is asserted here rather than left to be noticed in a spreadsheet months later.
"""

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
    """The two lists have to cover each other, or a valid state offers an empty district dropdown."""
    assert set(DISTRICTS_BY_STATE) == set(INDIAN_STATES_AND_UNION_TERRITORIES)
    for state, districts in DISTRICTS_BY_STATE.items():
        assert districts, f"{state} has no districts"


def test_every_district_maps_to_exactly_one_state() -> None:
    """No district name may appear under two states.

    Duplicates across states are real — Bilaspur, Hamirpur, Aurangabad, Pratapgarh, Bijapur — and
    they are precisely why the list is keyed by state. What must NOT happen is the same
    ``(state, district)`` pair being reachable twice, or a state listing a district twice, because
    then a group-by silently splits one place in two.
    """
    for state, districts in DISTRICTS_BY_STATE.items():
        assert len(districts) == len(set(districts)), f"{state} lists a district twice"

    pairs = [(state, name) for state, names in DISTRICTS_BY_STATE.items() for name in names]
    assert len(pairs) == len(set(pairs)) == DISTRICT_COUNT

    # And every one of them resolves, from its own state, back to exactly itself.
    for state, name in pairs:
        assert normalize_district(state, name) == name
        assert district_error(state, name) is None


def test_districts_are_sorted_and_title_case_fixed_points() -> None:
    """``records.clean_data`` title-cases on write, so a name that changes under that rule would be
    stored differently from the value the dropdown offered."""
    for state, districts in DISTRICTS_BY_STATE.items():
        assert list(districts) == sorted(districts), f"{state} is not in alphabetical order"
        for name in districts:
            assert title_case(name) == name, f"{state} / {name!r} is rewritten by title_case"


def test_amendments_agree_with_the_list() -> None:
    """The provenance table has to describe the list it claims to describe.

    Without this the amendment rows become decoration: someone adds a district and forgets the row,
    or removes a row and leaves the district, and the next refresh cannot tell a deliberate addition
    from an export that has fallen behind.
    """
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
            # The old name has to stay reachable, or every record written before the rename fails.
            assert normalize_district(state, replaces) == district
        else:
            assert not replaces, f"{change} {district!r} should not name a predecessor"


def test_every_alias_points_at_a_real_district_of_its_state() -> None:
    """A mis-typed alias TARGET fails silently — it simply never matches — so it is checked here.

    Reached through the public normaliser rather than the private table, which also proves the
    folding and the per-state scoping are wired up the way the table assumes.
    """
    from app.services.address import _DISTRICT_ALIASES

    for state, aliases in _DISTRICT_ALIASES.items():
        assert state in DISTRICTS_BY_STATE, f"aliases for unknown state {state!r}"
        for spelling, target in aliases.items():
            assert target in DISTRICTS_BY_STATE[state], (
                f"alias {state} / {spelling!r} points at {target!r}, which is not a district there"
            )
            assert normalize_district(state, spelling) == target


# ---------------------------------------------------------------------------------------------
# Reconciling the geocoder


@pytest.mark.parametrize(
    ("state", "returned", "expected"),
    [
        # The three spellings MapTiler actually returned for real coordinates in this corpus.
        ("Jammu and Kashmir", "Jammu district", "Jammu"),
        ("Maharashtra", "Akola District", "Akola"),
        ("Gujarat", "Kutch", "Kachchh"),
        # The suffix rule is about the WORD, not the case, so both halves are exercised together.
        ("Rajasthan", "JAIPUR DISTRICT", "Jaipur"),
        ("Rajasthan", "  balotra  ", "Balotra"),
        ("Uttarakhand", "Rudra Prayag", "Rudraprayag"),
        # An LGD export's own spelling, so a payload built from the raw source still resolves.
        ("West Bengal", "24 Paraganas North", "North 24 Parganas"),
    ],
)
def test_geocoder_spellings_reconcile(state: str, returned: str, expected: str) -> None:
    assert reconcile_geocoded_district(state, returned) == expected


def test_geocoder_silence_is_not_an_error() -> None:
    """``subregion`` is legitimately absent at sea and at some rural points; declining to guess is
    correct behaviour and must not fail the write."""
    assert reconcile_geocoded_district("Gujarat", None) is None
    assert reconcile_geocoded_district("Gujarat", "   ") is None


def test_unreconcilable_geocoder_value_raises_rather_than_writing_free_text() -> None:
    """The loud failure. An automatic source is the one caller that can write hundreds of unreviewed
    spellings into a closed column, so a value nobody can place has to stop."""
    with pytest.raises(DistrictReconciliationError) as caught:
        reconcile_geocoded_district("Gujarat", "Bhuj Taluka")

    assert caught.value.state == "Gujarat"
    assert caught.value.value == "Bhuj Taluka"
    assert "Bhuj Taluka" in str(caught.value)


def test_dissolved_district_is_not_guessed_at() -> None:
    """Delhi's Shahdara was split across several neighbours; any single target would be invented."""
    with pytest.raises(DistrictReconciliationError):
        reconcile_geocoded_district("Delhi", "Shahdara")


# ---------------------------------------------------------------------------------------------
# The cross-field rule


def test_district_of_the_wrong_state_is_rejected_and_names_the_right_one() -> None:
    """The whole reason the list is keyed by state."""
    message = district_error("Rajasthan", normalize_district("Rajasthan", "Kachchh"))

    assert message is not None
    assert "Gujarat" in message
    assert "Rajasthan" in message

    with pytest.raises(ValueError, match="Gujarat"):
        validate_district("Rajasthan", "Kachchh")


def test_a_name_shared_by_two_states_resolves_per_state() -> None:
    """Bilaspur is a real district of both, and neither answer may leak into the other."""
    assert validate_district("Chhattisgarh", "bilaspur") == "Bilaspur"
    assert validate_district("Himachal Pradesh", "BILASPUR") == "Bilaspur"
    # Raigad (Maharashtra) and Raigarh (Chhattisgarh) are a spelling apart and 1,000 km apart.
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
    """The district cannot be judged, so complaining about it would send the researcher to the wrong
    field."""
    message = district_error("Atlantis", "Jaipur")

    assert message is not None
    assert "Atlantis" in message


def test_a_state_alias_still_scopes_the_district() -> None:
    """Records arrive from imports that predate the dropdown and say "Orissa"."""
    assert validate_district("Orissa", "Khurda") == "Khordha"
    assert districts_for_state("orissa") == DISTRICTS_BY_STATE["Odisha"]


def test_unknown_district_names_the_list_vintage() -> None:
    """A rejection has to be actionable: the list has a date, and a district created after it is the
    likeliest reason a real name is missing."""
    message = district_error("Rajasthan", normalize_district("Rajasthan", "Sanganer"))

    assert message is not None
    assert "2026-07-26" in message


def test_blank_district_is_accepted_here() -> None:
    """Requiredness belongs to the schema; this module only rules on values that were supplied."""
    assert validate_district("Gujarat", None) is None
    assert validate_district("Gujarat", "   ") is None


# ---------------------------------------------------------------------------------------------
# What the clients are served


def test_reference_payload_carries_the_districts_and_their_provenance() -> None:
    payload = address_reference()
    districts = payload["districts"]

    assert districts["count"] == DISTRICT_COUNT
    assert set(districts["byState"]) == set(payload["statesAndUnionTerritories"])
    assert districts["byState"]["Rajasthan"] == list(DISTRICTS_BY_STATE["Rajasthan"])
    # Undated reference data is unusable in a research dataset — this is the point of the block.
    assert districts["asOf"]
    assert "Local Government Directory" in districts["source"]
    assert "lgdirectory.gov.in" in districts["sourceUrl"]


def test_reference_payload_stays_small_enough_for_a_field_connection() -> None:
    """A guard rather than a measurement: if the district block ever triples because somebody changed
    its shape, the form's first paint on a rural connection pays for it."""
    import gzip
    import json

    encoded = json.dumps(address_reference(), separators=(",", ":")).encode()

    assert len(encoded) < 20_000
    assert len(gzip.compress(encoded, 9)) < 8_000


def test_balotra_is_present() -> None:
    """The district that motivated the provenance work: created in 2023, in this repo's own records,
    and absent from any list compiled before then."""
    assert "Balotra" in DISTRICTS_BY_STATE["Rajasthan"]
    assert validate_district("Rajasthan", "balotra") == "Balotra"
