"""The lineage that stands in for the 43 districts with no border of their own.

A fallback is only useful if it points at something that can actually be drawn, so these tests assert
exactly that — and they are the reason a border refresh cannot silently break it. If a newer boundary
dataset covers a district, the row here becomes dead weight and the test says so; if a refresh drops a
PARENT, the fallback would draw nothing and the test fails rather than the map going quietly blank.
"""

import json
import pathlib

import pytest

from app.services.address import DISTRICTS_BY_STATE, INDIAN_STATES_AND_UNION_TERRITORIES
from app.services.district_lineage import (
    DISTRICT_PARENTS,
    STATE_FALLBACK,
    all_parents,
    draws_state_border,
    lineage_reference,
    parent_for,
)

MANIFEST = pathlib.Path(__file__).resolve().parents[2] / "frontend" / "public" / "boundaries" / "manifest.json"


def manifest() -> dict:
    if not MANIFEST.exists():
        pytest.skip("boundary assets not built; run scripts/build_boundaries.py")
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def missing_pairs() -> set[tuple[str, str]]:
    return {tuple(entry.split("|", 1)) for entry in manifest()["missing"]}


def covered_pairs() -> set[tuple[str, str]]:
    every = {(state, d) for state, names in DISTRICTS_BY_STATE.items() for d in names}
    return every - missing_pairs()


# --- The table describes real places ---------------------------------------------------------


def test_every_row_names_a_district_the_register_knows():
    for state, district in DISTRICT_PARENTS:
        assert state in INDIAN_STATES_AND_UNION_TERRITORIES, state
        assert district in DISTRICTS_BY_STATE[state], f"{district} is not a district of {state}"


def test_every_parent_is_a_district_of_the_same_state():
    # A parent in another state would be a transcription error, and it would draw a border hundreds of
    # kilometres from the record it is standing in for.
    for (state, district), parents in DISTRICT_PARENTS.items():
        assert parents, f"{state}|{district} has an empty parent list"
        for parent in parents:
            assert parent in DISTRICTS_BY_STATE[state], (
                f"{state}|{district}: parent {parent!r} is not a district of {state}"
            )
        assert parent not in (district,), "a district cannot be its own parent"


def test_no_district_is_its_own_parent():
    for (state, district), parents in DISTRICT_PARENTS.items():
        assert district not in parents, f"{state}|{district} lists itself as a parent"


def test_parents_are_not_repeated_within_a_row():
    for key, parents in DISTRICT_PARENTS.items():
        assert len(parents) == len(set(parents)), f"{key} repeats a parent"


# --- The table lines up with the borders that were actually built -----------------------------


def test_every_borderless_district_has_an_outline_to_borrow():
    """THE test this file exists for.

    Every district the border build could not cover must have SOMETHING to draw — a parent district's
    outline, or failing that its state's. Anything else silently draws nothing, which on a map is
    indistinguishable from a district with no records at all.
    """
    unaccounted = missing_pairs() - set(DISTRICT_PARENTS) - set(STATE_FALLBACK)
    assert unaccounted == set(), f"no border and no fallback: {sorted(unaccounted)}"


def test_every_parent_actually_has_a_border_to_borrow():
    # A parent that is itself borderless makes the fallback a dead end.
    borderless = missing_pairs()
    for (state, district), parents in DISTRICT_PARENTS.items():
        drawn = parents[0]
        assert (state, drawn) not in borderless, (
            f"{state}|{district} falls back to {drawn}, which has no border either"
        )


def test_the_table_carries_no_rows_the_borders_have_made_redundant():
    """A refresh that covers a district should retire its row rather than leave a coarser fallback."""
    covered = covered_pairs()
    stale = [f"{s}|{d}" for (s, d) in DISTRICT_PARENTS if (s, d) in covered]
    assert stale == [], f"these now have their own border and the lineage row should go: {stale}"


def test_a_state_fallback_is_never_also_given_a_parent():
    # The two are alternatives: a district either has a parent whose outline stands in for it, or it
    # falls back to the state. Both would leave the map with two answers and no rule for choosing.
    for key in STATE_FALLBACK:
        assert key not in DISTRICT_PARENTS, f"{key} has both a parent and a state fallback"


def test_the_state_fallbacks_are_real_districts_with_no_border():
    for state, district in STATE_FALLBACK:
        assert district in DISTRICTS_BY_STATE[state], f"{district} is not a district of {state}"
        assert (state, district) in missing_pairs(), (
            f"{state}|{district} has its own border and needs no fallback"
        )


def test_delhis_thirteen_lgd_districts_are_all_in_the_register():
    """The register follows LGD, which lists thirteen districts for Delhi — not the eleven in common
    circulation, and Shahdara is not among them.

    Asserted because this was got WRONG once: the three LGD names the boundary data omits were read as
    register defects, when the register was right and the boundary source simply uses a different
    vocabulary. Shahdara's polygon still contributes borders; it is just not a key.
    """
    lgd_delhi = {
        "Central", "Central North", "East", "New Delhi", "North", "North East", "North West",
        "Old Delhi", "Outer North", "South", "South East", "South West", "West",
    }
    assert set(DISTRICTS_BY_STATE["Delhi"]) == lgd_delhi
    assert "Shahdara" not in DISTRICTS_BY_STATE["Delhi"]


# --- The lookups ------------------------------------------------------------------------------


def test_parent_for_returns_the_drawn_outline_and_nothing_for_a_covered_district():
    assert parent_for("Rajasthan", "Balotra") == "Barmer"
    assert parent_for("Ladakh", "Zanskar") == "Kargil"
    # A district with its own border must not report a parent, or the map would draw the coarser one.
    assert parent_for("Rajasthan", "Barmer") is None
    assert parent_for("Nowhere", "Nothing") is None


def test_a_split_across_two_parents_reports_both_and_draws_the_first():
    # Kotputli came from Jaipur and Behror from Alwar, so the row records both and the map draws the
    # larger contributor. Reporting only one would present a two-parent split as a clean carve-out.
    assert all_parents("Rajasthan", "Kotputli-Behror") == ("Jaipur", "Alwar")
    assert parent_for("Rajasthan", "Kotputli-Behror") == "Jaipur"
    assert all_parents("Rajasthan", "Barmer") == ()


def test_draws_state_border_answers_only_for_the_fallback_set():
    assert draws_state_border("Delhi", "Old Delhi")
    assert not draws_state_border("Delhi", "Central")
    assert not draws_state_border("Rajasthan", "Balotra")  # has a parent instead


def test_the_served_reference_is_json_safe_and_complete():
    payload = lineage_reference()
    assert json.loads(json.dumps(payload)) == payload
    assert len(payload["parents"]) == len(DISTRICT_PARENTS)
    assert len(payload["stateFallback"]) == len(STATE_FALLBACK)
    assert payload["note"]
    # Keyed exactly as the manifest keys its missing list, so a client can join the two directly.
    assert all("|" in key for key in payload["parents"])
