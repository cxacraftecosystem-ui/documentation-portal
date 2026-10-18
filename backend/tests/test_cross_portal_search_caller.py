"""The asking side of cross-portal search: who may ask, what is asked, and what happens when the
designer portal is slow, missing or untrusted (routes/search, services/unified_search,
services/cross_portal_client; docs/SEARCH.md).

No database and no network: the local ranking, the peer call and ``requests`` are fakes.
"""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any

import pytest
import requests
from fastapi import HTTPException

from app.api.routes import search as routes
from app.services import cross_portal, cross_portal_client, search_query
from app.services import unified_search as us

pytestmark = pytest.mark.anyio

TOKEN = "c" * 40
KEY = "k" * 40
URL = "https://api.designer.example/api"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _settings(**over: Any) -> SimpleNamespace:
    base = {
        "unified_search_enabled": True,
        "search_embeddings_enabled": False,
        "openai_api_key": None,
        "gemini_api_keys": [],
        "cross_portal_identity_enabled": False,
        "cross_portal_search_enabled": True,
        "cross_portal_token": TOKEN,
        "aadhaar_fingerprint_key": KEY,
        "designer_portal_api_url": URL,
    }
    base.update(over)
    return SimpleNamespace(**base)


LOCAL_ITEM = {
    "id": "a1",
    "type": "artisans",
    "title": "Ramesh Kumar",
    "subtitle": "Indigo",
    "snippet": "",
    "highlights": [],
    "place": "Bagru",
    "craftCodes": [],
    "artisanIds": ["a1"],
    "recordDate": None,
    "openPath": "/artisans/a1",
    "score": 0.03,
}


def _peer_item(item_id: str, linked: list[str]) -> dict[str, Any]:
    return {
        "type": "artisans",
        "id": item_id,
        "title": f"Designer {item_id}",
        "subtitle": "Bandhani",
        "snippet": "tie and dye",
        "highlights": [[0, 3]],
        "place": "Bhuj",
        "craftCodes": ["bandhani"],
        "recordDate": None,
        "openUrl": f"https://designer.example/artisans/{item_id}",
        "linkedArtisanIds": linked,
        "phone": "9999999999",
    }


def _peer_answer() -> dict[str, Any]:
    return {
        "mode": "text",
        "groups": [
            {
                "type": "artisans",
                "total": 4,
                "items": [
                    _peer_item("dp9", []),
                    _peer_item("dp-consented", ["fr-yes"]),
                    _peer_item("dp-withdrawn", ["fr-no"]),
                ],
            },
            {"type": "designWorkshops", "total": 1, "items": [
                {**_peer_item("dw1", []), "type": "designWorkshops"},
            ]},
            {"type": "nonsense", "total": 1, "items": [{"id": "x"}]},
        ],
    }


class FakeTable:
    def __init__(self, rows=None):
        self.rows = list(rows or [])
        self.created: list[dict[str, Any]] = []

    async def find_unique(self, where):
        return next((SimpleNamespace(**r) for r in self.rows if r["id"] == where["id"]), None)

    async def find_many(self, where):
        wanted = set(where["artisanId"]["in"])
        return [
            SimpleNamespace(**r)
            for r in self.rows
            if r["artisanId"] in wanted and r["granted"] is where.get("granted", r["granted"])
        ]

    async def create(self, data):
        self.created.append(data)
        return SimpleNamespace(**data)


@pytest.fixture
def world(monkeypatch):
    settings = _settings()
    peer_calls: list[dict[str, Any]] = []
    answers: dict[str, Any] = {"/search": (200, _peer_answer())}

    async def fake_peer(
        s, method, path, *, body=None, requested_by=None, timeout=None, search=False, params=None
    ):
        peer_calls.append({"path": path, "body": body, "timeout": timeout, "search": search})
        answer = answers[path]
        if isinstance(answer, Exception):
            raise answer
        return answer

    async def fake_run(q, scope, *, limit, offset=0, qvec=None):
        world.scope = scope
        return search_query.Result(
            mode="text", items={"artisans": [dict(LOCAL_ITEM)]}, totals={"artisans": 1}
        )

    fake_db = SimpleNamespace(
        craft=FakeTable(
            [{"id": "c-coded", "craftCode": "bandhani"}, {"id": "c-bare", "craftCode": None}]
        ),
        crossportalaccesslog=FakeTable(),
        artisansharingconsent=FakeTable(
            [{"artisanId": "fr-yes", "granted": True}, {"artisanId": "fr-no", "granted": False}]
        ),
    )
    world = SimpleNamespace(
        settings=settings, peer_calls=peer_calls, answers=answers, db=fake_db, scope=None
    )
    monkeypatch.setattr(routes, "get_settings", lambda: world.settings)
    monkeypatch.setattr(routes, "db", fake_db)
    monkeypatch.setattr(us, "db", fake_db)
    monkeypatch.setattr(routes, "peer_call", fake_peer)
    monkeypatch.setattr(us, "peer_call", fake_peer)
    monkeypatch.setattr(search_query, "run", fake_run)
    return world


def _user(role: str = "RESEARCHER") -> SimpleNamespace:
    return SimpleNamespace(id="u1", email="r@example.org", role=role)


async def _search(role: str = "RESEARCHER", q: str = "indigo", **kw: Any) -> dict[str, Any]:
    args = {
        "types": None,
        "scope": "all",
        "limit": 5,
        "type": None,
        "page": 1,
        "craftId": None,
        "place": None,
        "dateFrom": None,
        "dateTo": None,
        "workshopIds": None,
    }
    args.update(kw)
    return await routes.unified_search(current_user=_user(role), q=q, **args)


async def test_remote_items_merge_after_local_on_a_tie(world):
    result = await _search()
    assert result["remote"] == {"portal": "designer", "label": "Designer portal", "status": "ok"}
    group = result["groups"][0]
    assert group["type"] == "artisans"
    local, first_remote = group["items"][:2]
    assert local["local"] is True and local["href"] == "/artisans/a1"
    assert local["portal"] == "fieldRepository"
    assert first_remote["local"] is False and first_remote["portal"] == "designer"
    assert first_remote["href"] == "/remote/artisans/dp9"
    assert first_remote["openUrl"].startswith("https://")
    assert local["score"] == first_remote["score"]
    # Only the projection crosses; unknown types are dropped; designer-only types come through.
    assert "phone" not in first_remote and "linkedArtisanIds" not in first_remote
    assert [g["type"] for g in result["groups"]] == ["artisans", "designWorkshops"]
    sent = world.peer_calls[0]
    assert sent["path"] == "/search" and sent["search"] is True
    assert sent["timeout"] == cross_portal_client.SEARCH_TIMEOUT_SECONDS
    assert sent["body"]["requester"] == {
        "userId": "u1",
        "email": "r@example.org",
        "role": "RESEARCHER",
    }


async def test_designer_items_naming_an_unconsented_artisan_are_dropped(world):
    result = await _search()
    group = result["groups"][0]
    ids = [i["id"] for i in group["items"]]
    assert "dp9" in ids and "dp-consented" in ids
    assert "dp-withdrawn" not in ids
    # The remote total loses what was dropped.
    assert (group["localTotal"], group["remoteTotal"], group["total"]) == (1, 3, 4)


async def test_a_peer_timeout_is_unavailable_and_local_results_still_return(world):
    world.answers["/search"] = cross_portal_client.PeerUnavailable("down")
    result = await _search()
    assert result["remote"]["status"] == "unavailable"
    assert [i["id"] for i in result["groups"][0]["items"]] == ["a1"]


async def test_a_field_contributor_is_not_permitted_and_no_call_is_made(world):
    result = await _search(role="FIELD_CONTRIBUTOR")
    assert result["remote"]["status"] == "notPermitted"
    assert world.peer_calls == []
    volunteer = await _search(role="CROWDSOURCE_VOLUNTEER")
    assert volunteer["remote"]["status"] == "notPermitted"
    assert [i["id"] for i in volunteer["groups"][0]["items"]] == ["a1"]


async def test_local_scope_and_switch_off_make_no_call(world):
    assert (await _search(scope="local"))["remote"]["status"] == "off"
    world.settings = _settings(cross_portal_search_enabled=False)
    assert (await _search())["remote"]["status"] == "off"
    world.settings = _settings(designer_portal_api_url=None)
    assert (await _search())["remote"]["status"] == "off"
    assert world.peer_calls == []


async def test_workshop_ids_are_local_so_the_peer_is_filtered_out(world):
    result = await _search(workshopIds=["w1"])
    assert result["remote"]["status"] == "filteredOut" and world.peer_calls == []
    assert world.scope.workshop_ids == ["w1"]


async def test_a_craft_is_sent_as_its_shared_code(world):
    await _search(craftId="c-coded")
    assert world.peer_calls[0]["body"]["filters"]["craftCodes"] == ["bandhani"]
    assert world.scope.craft_ids == ["c-coded"]
    world.peer_calls.clear()
    bare = await _search(craftId="c-bare")
    assert bare["remote"]["status"] == "filteredOut" and world.peer_calls == []


class _Response:
    def __init__(self, status_code: int, content: bytes, signature: str | None):
        self.status_code = status_code
        self.content = content
        self.headers = {cross_portal.SIGNATURE_HEADER: signature} if signature else {}


async def test_an_unsigned_or_forged_answer_is_unavailable(monkeypatch):
    settings = _settings()
    body = json.dumps({"mode": "text", "groups": []}).encode()
    seen: dict[str, Any] = {}

    def forged(method, url, headers, data, params, timeout):
        seen.update(url=url, timeout=timeout)
        nonce = headers[cross_portal.NONCE_HEADER]
        return _Response(
            200, body, cross_portal.sign("x" * 40, body, method=method, path=url, nonce=nonce)
        )

    monkeypatch.setattr(cross_portal_client.requests, "request", forged)
    status, payload = await us.ask_peer(
        settings, _user(), q="indigo", types=None, limit=5, one_type=None, page=1, filters={}
    )
    assert (status, payload) == ("unavailable", {})
    assert seen["url"] == f"{URL}/cross-portal/peer/search"
    assert seen["timeout"] == cross_portal_client.SEARCH_TIMEOUT_SECONDS

    def timeout(*_a, **_k):
        raise requests.Timeout("slow")

    monkeypatch.setattr(cross_portal_client.requests, "request", timeout)
    assert (
        await us.ask_peer(
            settings, _user(), q="indigo", types=None, limit=5, one_type=None, page=1, filters={}
        )
    )[0] == "unavailable"

    def good(method, url, headers, data, params, timeout):
        nonce = headers[cross_portal.NONCE_HEADER]
        signature = cross_portal.sign(
            TOKEN, body, method=method, path="/api/cross-portal/peer/search", nonce=nonce
        )
        return _Response(200, body, signature)

    monkeypatch.setattr(cross_portal_client.requests, "request", good)
    assert (
        await us.ask_peer(
            settings, _user(), q="indigo", types=None, limit=5, one_type=None, page=1, filters={}
        )
    ) == ("ok", {"mode": "text", "groups": []})


async def test_search_needs_its_own_switch_not_the_identity_one():
    assert cross_portal.search_active(_settings()) is True
    assert cross_portal.search_active(_settings(cross_portal_search_enabled=False)) is False
    assert cross_portal.search_active(_settings(cross_portal_token="short")) is False
    assert cross_portal.search_active(_settings(designer_portal_api_url="")) is False
    assert cross_portal.peer_api_url(_settings()) == URL


def test_the_field_repository_names_the_designer_portal_when_it_cannot_reach_it():
    assert cross_portal_client.unavailable_sentence(_settings()) == (
        "The designer portal can't be reached right now."
    )


async def test_remote_record_maps_404_and_503(world):
    world.answers["/search/record"] = (
        200,
        {
            "record": {
                "type": "products",
                "id": "dp9",
                "title": "Stole",
                "subtitle": None,
                "fields": [{"label": "Place", "value": "Bhuj"}, {"label": "", "value": "x"}],
                "snippet": "…",
                "recordDate": None,
                "openUrl": "https://designer.example/products/dp9",
            }
        },
    )
    record = await routes.remote_record("products", "dp9", current_user=_user())
    assert record["portal"] == "designer"
    assert record["fields"] == [{"label": "Place", "value": "Bhuj"}]
    assert world.peer_calls[-1]["timeout"] == cross_portal_client.RECORD_TIMEOUT_SECONDS
    logged = world.db.crossportalaccesslog.created[-1]
    assert (logged["action"], logged["outcome"], logged["userId"]) == ("RECORD_VIEW", "ok", "u1")

    world.answers["/search/record"] = (404, {"record": None})
    with pytest.raises(HTTPException) as missing:
        await routes.remote_record("products", "dp9", current_user=_user())
    assert missing.value.status_code == 404

    world.answers["/search/record"] = (200, {"record": {"title": "x", "linkedArtisanIds": ["fr-no"]}})
    with pytest.raises(HTTPException) as unconsented:
        await routes.remote_record("products", "dp9", current_user=_user())
    assert unconsented.value.status_code == 404

    world.answers["/search/record"] = cross_portal_client.PeerUnavailable("down")
    with pytest.raises(HTTPException) as down:
        await routes.remote_record("products", "dp9", current_user=_user())
    assert down.value.status_code == 503

    world.answers["/search/record"] = (500, {"detail": "boom"})
    with pytest.raises(HTTPException) as broken:
        await routes.remote_record("products", "dp9", current_user=_user())
    assert broken.value.status_code == 503

    with pytest.raises(HTTPException) as refused:
        await routes.remote_record("products", "dp9", current_user=_user("FIELD_CONTRIBUTOR"))
    assert refused.value.status_code == 404
    with pytest.raises(HTTPException) as unknown:
        await routes.remote_record("nonsense", "dp9", current_user=_user())
    assert unknown.value.status_code == 404


def test_parse_types_keeps_vocabulary_order():
    assert us.parse_types(["media,artisans", "crafts"]) == ["artisans", "crafts", "media"]
    assert us.parse_types(None) is None
    with pytest.raises(ValueError):
        us.parse_types(["artisans,bogus"])


def test_the_shared_vocabulary_matches_the_contract():
    assert cross_portal.SEARCH_TYPES == (
        "artisans", "crafts", "clusters", "workshops", "designWorkshops", "interviews",
        "transcripts", "reports", "products", "tools", "processes", "media",
    )
    assert "FIELD_CONTRIBUTOR" not in cross_portal.CROSS_PORTAL_SEARCH_ROLES
    assert "CROWDSOURCE_VOLUNTEER" not in cross_portal.CROSS_PORTAL_SEARCH_ROLES
    assert cross_portal.PEER_ITEM_KEYS == (
        "type", "id", "title", "subtitle", "snippet", "highlights", "place", "craftCodes",
        "recordDate", "openUrl",
    )
