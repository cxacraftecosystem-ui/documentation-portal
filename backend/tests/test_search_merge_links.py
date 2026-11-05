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
