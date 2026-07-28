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
    """A user below professor — the rank the two visibility policies actually differ on."""

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


def test_reading_the_repository_is_open_to_every_rank():
    # The policy this module now composes: a signed-in account may READ every row, whatever its rank.
    # A volunteer's clauses must therefore carry NO owner predicate — the bug this replaced was a
    # researcher's search, map and dashboard all silently narrowing to their own uploads.
    for user in (VOLUNTEER, ADMIN):
        wheres = asyncio.run(build_record_wheres(user))
        for bucket in RECORD_TYPES:
            assert wheres[bucket] == {}, f"{bucket} narrowed a plain read for {user.role}"


def test_a_free_text_query_is_the_only_thing_a_plain_search_adds():
    wheres = asyncio.run(build_record_wheres(VOLUNTEER, q="bagru"))
    for bucket in RECORD_TYPES:
        where = wheres[bucket]
        assert "OR" in where, f"{bucket} lost its text search"
        # No leftover empty AND: composing an empty predicate must not litter the clause with `[{}]`,
        # which Prisma accepts but which makes every query log unreadable.
        assert "AND" not in where, f"{bucket} carries an empty AND"


def test_the_download_predicate_still_narrows_below_professor():
    # Reading opened up; TAKING DATA OUT did not. This is the predicate every /export and /data query
    # still rides, asserted directly because no read-side clause carries it any more.
    from app.services.records import owned_or_granted_where

    records = asyncio.run(owned_or_granted_where(VOLUNTEER))
    assert records["OR"][0] == {"createdById": "u1"}
    assert records["OR"][1]["createdBy"]["is"]["dataAccessAsOwner"]["some"] == {
        "granteeId": "u1",
        "status": "GRANTED",
    }

    media = asyncio.run(owned_or_granted_where(VOLUNTEER, owner_field="uploadedById"))
    assert media["OR"][0] == {"uploadedById": "u1"}
    assert "uploadedBy" in media["OR"][1]

    # Professor and above take everything, as before.
    assert asyncio.run(owned_or_granted_where(ADMIN)) == {}


def test_mine_means_mine_at_every_rank():
    # The dashboard's "your contribution" figure. It used to fall out of the read filter by accident,
    # which is exactly why it was wrong for a professor — who saw the repository total labelled as
    # their own work.
    from app.services.records import own_rows_where

    assert asyncio.run(own_rows_where(VOLUNTEER)) == {"createdById": "u1"}
    assert asyncio.run(own_rows_where(ADMIN)) == {"createdById": "admin"}
    assert asyncio.run(own_rows_where(ADMIN, owner_field="uploadedById")) == {"uploadedById": "admin"}


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
