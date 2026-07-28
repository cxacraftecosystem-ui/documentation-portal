"""The workshop scope in the shared filter vocabulary.

The workshop is the unit the fieldwork happens in, so "the records from these workshops" is where
every cross-workshop conclusion starts. It lives in ``record_filters`` rather than on whichever screen
needed it first, and these are the rules the map, the search box, the completion matrix and the
consolidated questionnaire all depend on agreeing about.
"""

import asyncio

from app.services.record_filters import (
    RECORD_TYPES,
    UNASSIGNED_WORKSHOP,
    build_record_wheres,
    resolve_workshop_ids,
    workshop_clause,
)


class FakeUser:
    def __init__(self, role: str = "RESEARCHER", user_id: str = "u1"):
        self.role = role
        self.id = user_id


USER = FakeUser()


# --- Parsing --------------------------------------------------------------------------------


def test_absent_means_every_workshop_not_none_of_them():
    # THE distinction this parser exists for. "The control is at its default" and "the user asked for
    # nothing" must not be spelled the same way, or the map opens empty for everybody.
    assert resolve_workshop_ids(None) is None
    assert resolve_workshop_ids([]) is None
    assert resolve_workshop_ids(["  "]) is None
    assert resolve_workshop_ids([""]) is None


def test_both_spellings_clients_build_are_accepted():
    # The web joins with commas; Android repeats the parameter. A scope that quietly covered
    # everything because it was spelled the other way would look exactly like the control not working.
    assert resolve_workshop_ids(["w1", "w2"]) == (["w1", "w2"], False)
    assert resolve_workshop_ids(["w1,w2"]) == (["w1", "w2"], False)
    assert resolve_workshop_ids(["w1, w2", "w3"]) == (["w1", "w2", "w3"], False)


def test_ids_are_deduplicated_in_first_seen_order():
    assert resolve_workshop_ids(["w2", "w1", "w2"]) == (["w2", "w1"], False)


def test_the_unassigned_sentinel_is_split_out_rather_than_treated_as_an_id():
    assert resolve_workshop_ids([UNASSIGNED_WORKSHOP]) == ([], True)
    assert resolve_workshop_ids(["w1", UNASSIGNED_WORKSHOP]) == (["w1"], True)
    assert resolve_workshop_ids([f"w1,{UNASSIGNED_WORKSHOP},w2"]) == (["w1", "w2"], True)


# --- The clause -----------------------------------------------------------------------------


def test_a_foreign_key_table_filters_on_workshop_id():
    assert workshop_clause(["w1", "w2"], False) == {"workshopId": {"in": ["w1", "w2"]}}


def test_the_workshop_table_filters_on_its_own_primary_key():
    # A workshop row IS the workshop. Filtering Workshop.workshopId is filtering a column that does
    # not exist, which Prisma answers with an error rather than an empty result.
    assert workshop_clause(["w1"], False, is_workshop_table=True) == {"id": {"in": ["w1"]}}


def test_unassigned_widens_a_foreign_key_table_and_empties_the_workshop_table():
    assert workshop_clause(["w1"], True) == {
        "OR": [{"workshopId": {"in": ["w1"]}}, {"workshopId": None}]
    }
    assert workshop_clause([], True) == {"workshopId": None}
    # A workshop cannot be unassigned from itself, so "unassigned only" matches no workshop row.
    assert workshop_clause([], True, is_workshop_table=True) is None
    assert workshop_clause([], False) is None


def test_a_single_branch_is_written_flat_rather_than_as_a_one_armed_or():
    # Same query to Postgres, a far more readable one in a log.
    assert "OR" not in workshop_clause(["w1"], False)


# --- Composition into the bucket wheres -----------------------------------------------------


def test_no_workshop_scope_leaves_every_bucket_untouched():
    wheres = asyncio.run(build_record_wheres(USER))
    assert all(wheres[bucket] == {} for bucket in RECORD_TYPES)


def test_the_scope_reaches_every_bucket_with_the_right_column():
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=["w1"]))
    for bucket in ("artisans", "products", "tools", "media"):
        assert wheres[bucket]["AND"] == [{"workshopId": {"in": ["w1"]}}], bucket
    # ...and the workshops bucket on its own id.
    assert wheres["workshops"]["AND"] == [{"id": {"in": ["w1"]}}]


def test_an_impossible_selection_is_written_as_impossible_rather_than_dropped():
    # "Unassigned only" cannot match a workshop row. Leaving that bucket unfiltered would show every
    # workshop in the repository under a scope that excludes them all.
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=[UNASSIGNED_WORKSHOP]))
    assert wheres["workshops"]["AND"] == [{"id": {"in": []}}]
    assert wheres["artisans"]["AND"] == [{"workshopId": None}]


def test_the_scope_composes_with_a_free_text_search_rather_than_replacing_it():
    # THE regression this guards. The search OR is assigned to `where["OR"]`; if the workshop clause
    # ever landed on the same key, a text search inside a workshop would return the whole repository.
    wheres = asyncio.run(build_record_wheres(USER, q="bagru", workshop_ids=["w1"]))
    for bucket in RECORD_TYPES:
        where = wheres[bucket]
        assert "OR" in where, f"{bucket} lost its text search"
        assert where["AND"], f"{bucket} lost its workshop scope"


def test_the_scope_composes_with_the_workshop_date_range():
    # The workshops bucket is the only one that already writes an AND of its own (its startDate
    # fallback), so it is the one where an assignment rather than an append would silently drop a
    # filter. Both clauses have to survive.
    from datetime import datetime, timezone

    wheres = asyncio.run(
        build_record_wheres(
            USER, date_from=datetime(2026, 7, 1, tzinfo=timezone.utc), workshop_ids=["w1"]
        )
    )
    clauses = wheres["workshops"]["AND"]
    assert len(clauses) == 2
    assert {"id": {"in": ["w1"]}} in clauses
    assert any("OR" in clause for clause in clauses), "the startDate fallback went missing"


def test_the_scope_composes_with_every_other_filter_at_once():
    from datetime import datetime, timezone

    wheres = asyncio.run(
        build_record_wheres(
            USER,
            q="cane",
            craft_id="c1",
            place="Bareilly",
            date_from=datetime(2026, 7, 1, tzinfo=timezone.utc),
            workshop_ids=["w1", UNASSIGNED_WORKSHOP],
        )
    )
    tools = wheres["tools"]
    assert tools["craftId"] == "c1"
    assert tools["place"] == {"contains": "Bareilly", "mode": "insensitive"}
    assert tools["createdAt"] == {"gte": datetime(2026, 7, 1, tzinfo=timezone.utc)}
    assert "OR" in tools
    assert tools["AND"] == [
        {"OR": [{"workshopId": {"in": ["w1"]}}, {"workshopId": None}]}
    ]
