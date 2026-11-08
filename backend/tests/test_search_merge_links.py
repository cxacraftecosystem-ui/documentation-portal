from __future__ import annotations

import asyncio
from typing import Any

from app.services import cross_portal, search_query, unified_search


def _local(kind: str, item_id: str, **extra: Any) -> dict[str, Any]:
    return {"type": kind, "id": item_id, "title": item_id, "openPath": f"/{kind}/{item_id}", **extra}


def _remote(item_id: str) -> dict[str, Any]:
    return {"id": item_id, "title": f"Peer {item_id}", "subtitle": "Bagru", "openUrl": f"https://peer/{item_id}"}


def test_the_same_artisan_in_both_portals_is_one_result_with_also_in():
    groups = unified_search.merge(
        {"artisans": [_local("artisans", "a1"), _local("artisans", "a2")]},
        {"artisans": 2},
        [{"type": "artisans", "total": 3, "items": [_remote("r1"), _remote("r9")]}],
        limit=10,
        links={"a1": "r1"},
    )
    (artisans,) = groups
    ids = [item["id"] for item in artisans["items"]]
    assert ids.count("r1") == 0 and "r9" in ids
    first = next(item for item in artisans["items"] if item["id"] == "a1")
    assert first["alsoIn"]["id"] == "r1" and first["alsoIn"]["openUrl"] == "https://peer/r1"
    assert first["alsoIn"]["portal"] == unified_search.PEER_PORTAL
    assert artisans["remoteTotal"] == 2 and artisans["total"] == 4


def test_transcript_items_carry_their_moment_and_kind():
    groups = unified_search.merge(
        {"transcripts": [_local("transcripts", "m1", startMs=61000, mediaType="VIDEO"), _local("transcripts", "m2")]},
        {"transcripts": 2},
        [],
        limit=10,
    )
    (transcripts,) = groups
    first, second = transcripts["items"]
    assert first["startMs"] == 61000 and first["mediaType"] == "VIDEO"
    assert "startMs" not in second and "mediaType" not in second


def test_the_peer_may_send_the_moment_and_kind():
    assert {"startMs", "mediaType"} <= set(cross_portal.PEER_ITEM_KEYS)


def test_query_terms_drop_single_letters():
    assert search_query.query_terms("A clay pot, b") == ["clay", "pot"]


def test_no_moment_lookup_without_items():
    asyncio.run(search_query.transcript_moments([], "clay"))


def _dated(kind: str, item_id: str, title: str, when: str | None) -> dict[str, Any]:
    return {**_local(kind, item_id), "title": title, "recordDate": when}


def _peer(item_id: str, title: str, when: str | None) -> dict[str, Any]:
    return {**_remote(item_id), "title": title, "recordDate": when}


def _sorted_ids(sort: tuple[str, str]) -> list[str]:
    (group,) = unified_search.merge(
        {
            "products": [
                _dated("products", "l1", "banana", "2026-03-01T00:00:00+00:00"),
                _dated("products", "l2", "Apple", None),
                _dated("products", "l3", "cherry", "2026-01-01T00:00:00+00:00"),
                _dated("products", "l4", "apple", "2026-03-01T00:00:00+00:00"),
            ]
        },
        {"products": 4},
        [
            {
                "type": "products",
                "total": 3,
                "items": [
                    _peer("r1", "apple", "2026-03-01T05:30:00+05:30"),
                    _peer("r2", "Date", "2026-02-01T00:00:00Z"),
                    _peer("r3", "elder", None),
                ],
            }
        ],
        limit=20,
        sort=sort,
    )
    return [item["id"] for item in group["items"]]


def test_a_name_sort_interleaves_both_portals_with_ties_local_first():
    assert _sorted_ids(("name", "asc")) == ["l2", "l4", "r1", "l1", "l3", "r2", "r3"]
    assert _sorted_ids(("name", "desc")) == ["r3", "r2", "l3", "l1", "l2", "l4", "r1"]


def test_a_recorded_sort_puts_undated_records_last_in_either_direction():
    assert _sorted_ids(("recorded", "desc")) == ["l4", "l1", "r1", "r2", "l3", "l2", "r3"]
    assert _sorted_ids(("recorded", "asc")) == ["l3", "r2", "l4", "l1", "r1", "l2", "r3"]


def test_relevance_keeps_the_fused_rank_order():
    assert _sorted_ids(("relevance", "desc")) == ["l1", "r1", "l2", "r2", "l3", "r3", "l4"]
    assert _sorted_ids(search_query.DEFAULT_SORT) == _sorted_ids(("relevance", "desc"))


def test_parse_sort_allows_only_the_listed_keys_and_directions():
    assert search_query.parse_sort(None) == ("relevance", "desc")
    assert search_query.parse_sort("relevance:desc") == ("relevance", "desc")
    assert search_query.parse_sort("name:desc") == ("name", "desc")
    assert search_query.parse_sort("recorded:asc") == ("recorded", "asc")
    for bad in ("relevance:asc", "size:desc", "added:desc", "name", "name:", ":asc", "name:up", "", 3):
        try:
            search_query.parse_sort(bad)
        except ValueError:
            continue
        raise AssertionError(bad)


ORDERS = {
    ("name", "asc"): 'lower(h."title") COLLATE "C" ASC NULLS LAST, h."entityId" COLLATE "C" ASC',
    ("name", "desc"): 'lower(h."title") COLLATE "C" DESC NULLS LAST, h."entityId" COLLATE "C" ASC',
    ("recorded", "asc"): (
        'h."recordDate" ASC NULLS LAST, lower(h."title") COLLATE "C" ASC, h."entityId" COLLATE "C" ASC'
    ),
    ("recorded", "desc"): (
        'h."recordDate" DESC NULLS LAST, lower(h."title") COLLATE "C" ASC, h."entityId" COLLATE "C" ASC'
    ),
}


def test_build_sql_orders_the_whole_match_set_by_each_key():
    scope = search_query.Scope(types=["products"])
    for sort, order in ORDERS.items():
        text = "x'); DROP TABLE \"SearchDocument\"; --"
        sql, params = search_query.build_sql(text, scope, limit=5, offset=10, sort=sort)
        assert f"ORDER BY {order}) AS pos" in sql, sort
        assert "fused AS" not in sql and "cap AS" not in sql
        assert 'b."tsv" @@ q.tsq OR word_similarity' in sql
        assert text not in sql and params[0] == text
        assert 10 in params and 15 in params


def test_a_sorted_query_keeps_vector_hits_in_the_ordered_set():
    sql, _params = search_query.build_sql(
        "indigo", search_query.Scope(types=["products"]), limit=5, qvec="[0]", sort=("name", "asc")
    )
    assert '<=> $' in sql and f"<= {search_query.MAX_VECTOR_DISTANCE}" in sql


def test_the_default_sort_builds_the_relevance_query_unchanged():
    scope = search_query.Scope(types=["products"])
    plain_sql = search_query.build_sql("indigo", scope, limit=5, offset=5, qvec="[0]")
    assert search_query.build_sql("indigo", scope, limit=5, offset=5, qvec="[0]", sort=None) == plain_sql
    assert (
        search_query.build_sql(
            "indigo", scope, limit=5, offset=5, qvec="[0]", sort=search_query.DEFAULT_SORT
        )
        == plain_sql
    )
    assert "ORDER BY f.score DESC, b.id" in plain_sql[0] and "hits AS" not in plain_sql[0]


def test_an_unlisted_order_never_reaches_sql():
    for bad in (("size", "asc"), ("relevance", "desc"), ("name", "sideways")):
        try:
            search_query.order_by(bad)
        except ValueError:
            continue
        raise AssertionError(bad)
