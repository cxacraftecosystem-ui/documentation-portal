"""How a free-text state and district become LGD codes, in the one fixed order ``lgd.resolve`` keeps.

No database: the alias table is the plain mapping the coverage fold builds from ``GeoAlias`` rows.
"""

from __future__ import annotations

from app.services import district_lineage, lgd


def test_register_aliases_resolve():
    orissa = lgd.resolve("Orissa", "Cuttack")
    assert (orissa.state_lgd, orissa.district_lgd, orissa.matched_by) == (21, 350, lgd.ALIAS)
    kutch = lgd.resolve("Gujarat", "Kutch")
    assert kutch.district_lgd == lgd.district_lgd("Gujarat", "Kachchh") and kutch.matched_by == lgd.ALIAS
    akola = lgd.resolve("Maharashtra", "Akola District")
    assert akola.district_lgd == lgd.district_lgd("Maharashtra", "Akola")


def test_canonical_text_is_canonical():
    hit = lgd.resolve("Rajasthan", "Barmer")
    assert (hit.state_lgd, hit.district_lgd, hit.matched_by) == (8, 90, lgd.CANONICAL)
    state_only = lgd.resolve("Rajasthan", None)
    assert (state_only.state_lgd, state_only.district_lgd, state_only.matched_by) == (8, None, lgd.CANONICAL)


def test_a_district_of_another_state_is_state_only_never_the_other_state():
    wrong = lgd.resolve("Rajasthan", "Kachchh")
    assert (wrong.state_lgd, wrong.district_lgd, wrong.matched_by) == (8, None, lgd.STATE_ONLY)


def test_a_geo_alias_row_wins_over_state_only_and_unmatched():
    aliases = {
        lgd.alias_key("Rajasthan", "Kachchh"): (24, lgd.district_lgd("Gujarat", "Kachchh")),
        lgd.alias_key("Rajastan", ""): (8, None),
        lgd.alias_key("Marwar", "Balotara Town"): (8, 775),
    }
    moved = lgd.resolve("Rajasthan", "Kachchh", aliases)
    assert (moved.state_lgd, moved.matched_by) == (24, lgd.GEO_ALIAS)
    state_spelling = lgd.resolve("Rajastan", "Barmer", aliases)
    assert (state_spelling.state_lgd, state_spelling.district_lgd, state_spelling.matched_by) == (
        8,
        90,
        lgd.GEO_ALIAS,
    )
    pair = lgd.resolve("marwar", "BALOTARA  town", aliases)
    assert (pair.district_lgd, pair.matched_by) == (775, lgd.GEO_ALIAS)


def test_place_atlas_is_the_last_resort():
    placed = lgd.resolve(None, None, place="Bagru, Rajasthan")
    assert (placed.state_lgd, placed.district_lgd, placed.matched_by) == (
        8,
        lgd.district_lgd("Rajasthan", "Jaipur"),
        lgd.PLACE_ATLAS,
    )


def test_unknown_text_is_unmatched():
    for raw in (("Atlantis", "Nowhere"), ("", ""), (None, None)):
        assert lgd.resolve(*raw).matched_by == lgd.UNMATCHED


def test_lineage_districts_report_their_parent_and_delhi_its_state():
    parents = 0
    for (state, district), _ in district_lineage.DISTRICT_PARENTS.items():
        unit = lgd.lgd_for_unit(state, district)
        assert unit["polygon"] == lgd.POLYGON_PARENT, (state, district)
        assert unit["parentLgd"] is not None, (state, district)
        parents += 1
    assert parents == 40
    for state, district in district_lineage.STATE_FALLBACK:
        unit = lgd.lgd_for_unit(state, district)
        assert unit["polygon"] == lgd.POLYGON_STATE and unit["parentLgd"] is None
    assert parents + len(district_lineage.STATE_FALLBACK) == 43
    assert lgd.lgd_for_unit("Rajasthan", "Barmer")["polygon"] == lgd.POLYGON_OWN


def test_a_pending_district_resolves_to_its_state_and_says_so():
    hit = lgd.resolve("Ladakh", "Nubra")
    assert hit.state_lgd == lgd.state_lgd("Ladakh") and hit.district_lgd is None and hit.pending
