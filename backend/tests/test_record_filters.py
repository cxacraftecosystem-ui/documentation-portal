"""The shared filter language, which had no tests at all while it lived inside the search route.

These are the rules the Search page, View Data and the map all depend on agreeing about. The two
that matter most are the ones a refactor would break silently: that row visibility survives a
free-text query, and that every active filter narrows rather than widens.
"""

import asyncio
from datetime import datetime, timezone

import pytest

from fastapi import HTTPException

from app.services.record_filters import (
    PLACED_TYPES,
    RECORD_TYPES,
    build_record_wheres,
    resolve_types,
)


class FakeUser:
    """A user below professor, so ``visibility_where`` returns a real predicate rather than {}."""

    def __init__(self, role: str = "CROWDSOURCE", user_id: str = "u1"):
        self.role = role
        self.id = user_id


ADMIN = FakeUser(role="ADMIN", user_id="admin")
VOLUNTEER = FakeUser()


def test_resolve_types_accepts_both_spellings_clients_use():
    assert resolve_types(None) == set(RECORD_TYPES)
    assert resolve_types([]) == set(RECORD_TYPES)
    assert resolve_types(["   "]) == set(RECORD_TYPES)
    assert resolve_types(["artisans", "media"]) == {"artisans", "media"}
    assert resolve_types(["artisans,media"]) == {"artisans", "media"}


def test_resolve_types_refuses_a_name_it_does_not_know():
    # A plausible typo must not come back as a well-formed empty result.
    with pytest.raises(HTTPException) as error:
        resolve_types(["artisan"])
    assert error.value.status_code == 422
    assert "artisan" in str(error.value.detail)


def test_every_bucket_is_returned_even_when_no_filter_touches_it():
    wheres = asyncio.run(build_record_wheres(ADMIN))
    assert set(wheres) == set(RECORD_TYPES)


def test_row_visibility_survives_a_free_text_query():
    # THE regression this file exists for. The search OR is assigned to `where["OR"]`; if the
    # visibility predicate ever moved out of `where["AND"]` and onto the same key, a plain text
    # search would quietly return every record in the repository.
    wheres = asyncio.run(build_record_wheres(VOLUNTEER, q="bagru"))
    for bucket in RECORD_TYPES:
        where = wheres[bucket]
        assert where["AND"], f"{bucket} lost its visibility predicate"
        assert "OR" in where, f"{bucket} lost its text search"
        assert where["AND"] != where["OR"]


def test_media_visibility_keys_off_the_upload_column():
    wheres = asyncio.run(build_record_wheres(VOLUNTEER))
    assert wheres["media"]["AND"][0]["OR"][0] == {"uploadedById": "u1"}
    assert wheres["artisans"]["AND"][0]["OR"][0] == {"createdById": "u1"}


def test_a_professor_gets_no_visibility_predicate_at_all():
    wheres = asyncio.run(build_record_wheres(ADMIN, q="bagru"))
    for bucket in RECORD_TYPES:
        assert "AND" not in wheres[bucket] or wheres[bucket].get("AND") != [{}]


def test_place_reaches_the_buckets_that_have_the_column_and_no_others():
    wheres = asyncio.run(build_record_wheres(ADMIN, place="Bagru"))
    for bucket in PLACED_TYPES:
        assert wheres[bucket]["place"] == {"contains": "Bagru", "mode": "insensitive"}
    # A photograph has no place of its own; filtering it by one would silently empty the bucket.
    assert "place" not in wheres["media"]


def test_filters_compose_rather_than_replace_one_another():
    when = datetime(2026, 7, 1, tzinfo=timezone.utc)
    wheres = asyncio.run(build_record_wheres(
        ADMIN, q="cane", craft_id="c1", place="Bareilly", date_from=when
    ))
    tools = wheres["tools"]
    assert "OR" in tools and tools["craftId"] == "c1"
    assert tools["place"] == {"contains": "Bareilly", "mode": "insensitive"}
    assert tools["createdAt"] == {"gte": when}


def test_the_workshop_date_range_falls_back_to_the_legacy_column():
    when = datetime(2026, 7, 1, tzinfo=timezone.utc)
    wheres = asyncio.run(build_record_wheres(ADMIN, date_from=when))
    clause = wheres["workshops"]["AND"][-1]
    assert clause == {"OR": [{"startDate": {"gte": when}}, {"startDate": None, "date": {"gte": when}}]}


def test_artisans_are_inside_the_date_range_too():
    # Artisans were the one bucket the range never reached, which read as the filter being broken
    # rather than as artisans being exempt.
    when = datetime(2026, 7, 1, tzinfo=timezone.utc)
    wheres = asyncio.run(build_record_wheres(ADMIN, date_from=when))
    assert wheres["artisans"]["createdAt"] == {"gte": when}


def test_a_nul_byte_in_the_query_is_stripped_not_rejected():
    # Postgres cannot store a NUL; `contains` strips it so a pasted name still searches.
    wheres = asyncio.run(build_record_wheres(ADMIN, q="bag\x00ru"))
    assert wheres["artisans"]["OR"][0]["name"]["contains"] == "bagru"
