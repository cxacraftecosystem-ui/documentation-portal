"""The answering side of cross-portal search: ``POST /cross-portal/peer/search`` and ``.../record``
(routes/cross_portal_search, services/cross_portal_peer; docs/SEARCH.md).

The admission dependency needs no database and runs everywhere. The searches themselves run against
a real database (opt-in, see ``search_support``), with a TestClient-free request object, so the
module keeps one event loop.
"""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from search_support import consent, document, index_world, needs_search_db, new_stamp, seed
from starlette.requests import Request

from app.api.routes import cross_portal_search as routes
from app.services import cross_portal, cross_portal_peer

pytestmark = pytest.mark.anyio

TOKEN = "p" * 40
KEY = "k" * 40
NONCE = "n" * 32
SEARCH_PATH = "/api/cross-portal/peer/search"
RECORD_PATH = "/api/cross-portal/peer/search/record"
OPEN_BASE = "https://docs.example"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from app.core.db import db

    await db.connect()
    try:
        w = await seed(new_stamp())
        await index_world(w)
        yield w
    finally:
        await db.disconnect()


def _settings(**over: Any) -> SimpleNamespace:
    base = {
        "cross_portal_identity_enabled": False,
        "cross_portal_search_enabled": True,
        "cross_portal_token": TOKEN,
        "cross_portal_aadhaar_hmac_key": KEY,
        "designer_portal_api_url": "https://api.designer.example/api",
        "next_public_app_url": OPEN_BASE,
        "search_embeddings_enabled": False,
        "openai_api_key": None,
        "gemini_api_keys": [],
    }
    base.update(over)
    return SimpleNamespace(**base)


def _request(path: str, *, token: str = TOKEN, nonce: str = NONCE, by: str = "dp-user") -> Request:
    headers = [
        (b"authorization", f"Bearer {token}".encode()),
        (b"x-cross-portal-nonce", nonce.encode()),
        (cross_portal.REQUESTED_BY_HEADER.lower().encode(), by.encode()),
    ]
    scope = {
        "type": "http",
        "method": "POST",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "headers": headers,
        "server": ("testserver", 80),
        "scheme": "http",
    }
    return Request(scope)


async def _admitted(path: str, monkeypatch, **kw: Any) -> tuple[Request, Any]:
    settings = _settings()
    monkeypatch.setattr(cross_portal_peer, "get_settings", lambda: settings)
    request = _request(path, **kw)
    admitted = await cross_portal_peer.require_search_peer(
        request,
        authorization=request.headers.get("authorization"),
        x_cross_portal_nonce=request.headers.get("x-cross-portal-nonce"),
    )
    return request, admitted


def _body(response) -> dict[str, Any]:
    return json.loads(response.body)


def _requester(w, user=None, email: str | None = None) -> dict[str, str]:
    return {
        "userId": f"dp-{w.stamp}",
        "email": email if email is not None else (user.email if user else ""),
        "role": "RESEARCHER",
    }


async def _peer_search(w, monkeypatch, **body: Any):
    request, settings = await _admitted(SEARCH_PATH, monkeypatch)
    payload = routes.PeerSearch(**{"q": w.stamp, **body})
    return request, await routes.peer_search(payload, request, settings)


def _items(body: dict[str, Any]) -> list[dict[str, Any]]:
    return [i for g in body["groups"] for i in g["items"]]


async def test_off_is_404_and_a_bad_token_or_nonce_is_401(monkeypatch):
    monkeypatch.setattr(
        cross_portal_peer, "get_settings", lambda: _settings(cross_portal_search_enabled=False)
    )
    request = _request(SEARCH_PATH)
    with pytest.raises(HTTPException) as off:
        await cross_portal_peer.require_search_peer(request, f"Bearer {TOKEN}", NONCE)
    assert off.value.status_code == 404
    monkeypatch.setattr(cross_portal_peer, "get_settings", _settings)
    with pytest.raises(HTTPException) as bad:
        await cross_portal_peer.require_search_peer(request, "Bearer " + "x" * 40, NONCE)
    assert bad.value.status_code == 401
    with pytest.raises(HTTPException) as short:
        await cross_portal_peer.require_search_peer(request, f"Bearer {TOKEN}", "abc")
    assert short.value.status_code == 401
    # No designer URL configured: search is not active, whatever the flag says.
    monkeypatch.setattr(
        cross_portal_peer, "get_settings", lambda: _settings(designer_portal_api_url=None)
    )
    with pytest.raises(HTTPException) as no_url:
        await cross_portal_peer.require_search_peer(request, f"Bearer {TOKEN}", NONCE)
    assert no_url.value.status_code == 404


async def test_the_identity_switch_does_not_open_search(monkeypatch):
    monkeypatch.setattr(
        cross_portal_peer,
        "get_settings",
        lambda: _settings(cross_portal_identity_enabled=True, cross_portal_search_enabled=False),
    )
    with pytest.raises(HTTPException) as off:
        await cross_portal_peer.require_search_peer(_request(SEARCH_PATH), f"Bearer {TOKEN}", NONCE)
    assert off.value.status_code == 404


@needs_search_db
async def test_the_answer_is_signed_over_the_request(world, monkeypatch):
    _request_, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
    )
    assert cross_portal.signature_matches(
        TOKEN,
        response.body,
        response.headers[cross_portal.SIGNATURE_HEADER],
        method="POST",
        path=SEARCH_PATH,
        nonce=NONCE,
    )


@needs_search_db
async def test_an_unknown_requester_gets_the_floor_of_shareable_records(world, monkeypatch):
    _r, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
    )
    ids = {i["id"] for i in _items(_body(response))}
    assert world.consented.id in ids and world.craft.id in ids and world.product_title.id in ids
    # Not shareable: an artisan without consent, a record naming them, a free-text artisan.
    for hidden in (world.unconsented.id, world.product_body.id, world.product_free.id):
        assert hidden not in ids


@needs_search_db
async def test_a_matching_account_brings_its_own_rules_but_still_only_shareable(
    world, monkeypatch
):
    _r, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, world.professor)
    )
    ids = {i["id"] for i in _items(_body(response))}
    assert world.consented.id in ids and world.unconsented.id not in ids


@needs_search_db
async def test_a_withdrawal_is_honoured_before_the_indexer_runs(world, monkeypatch):
    assert (await document("products", world.product_title.id))["shareable"] is True
    await consent(world.consented.id, False)
    try:
        # The document still says shareable: nothing was drained.
        assert (await document("products", world.product_title.id))["shareable"] is True
        _r, response = await _peer_search(
            world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
        )
        ids = {i["id"] for i in _items(_body(response))}
        assert world.product_title.id not in ids and world.consented.id not in ids
        assert world.craft.id in ids
    finally:
        await consent(world.consented.id, True)


@needs_search_db
async def test_items_carry_only_the_projection(world, monkeypatch):
    _r, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
    )
    raw = response.body.decode()
    for secret in world.secrets:
        assert secret not in raw
    for forbidden in ("objectKey", "bucket", "aadhaar", "phone", '"email"', "dateOfBirth"):
        assert forbidden not in raw
    for item in _items(_body(response)):
        assert set(item) <= set(cross_portal.PEER_ITEM_KEYS)
        assert item["openUrl"].startswith(f"{OPEN_BASE}/")
    product = next(i for i in _items(_body(response)) if i["id"] == world.product_title.id)
    assert product["openUrl"] == f"{OPEN_BASE}/products/{world.product_title.id}"


@needs_search_db
async def test_designer_only_types_answer_empty(world, monkeypatch):
    _r, response = await _peer_search(
        world, monkeypatch, types=["designWorkshops", "reports"], requester=_requester(world)
    )
    assert _body(response) == {"mode": "text", "groups": []}


@needs_search_db
async def test_each_search_is_logged_without_the_query(world, monkeypatch):
    from app.core.db import db

    asker = f"dp-{world.stamp}"
    await _peer_search(world, monkeypatch, requester=_requester(world, email="x@example.invalid"))
    rows = await db.crossportalaccesslog.find_many(
        where={"remoteUserId": asker, "action": "SEARCH"}, order={"createdAt": "desc"}
    )
    assert rows and rows[0].outcome == "ok" and rows[0].resultCount > 0
    for row in rows:
        assert world.stamp not in json.dumps(row.model_dump(), default=str).replace(asker, "")


@needs_search_db
async def test_the_rate_limit_answers_a_signed_429(world, monkeypatch):
    monkeypatch.setattr(routes, "RATE_LIMIT", 1)
    requester = {"userId": f"rl-{world.stamp}", "email": "", "role": "RESEARCHER"}
    _r, first = await _peer_search(world, monkeypatch, requester=requester)
    assert first.status_code == 200
    _r2, second = await _peer_search(world, monkeypatch, requester=requester)
    assert second.status_code == 429
    assert cross_portal.signature_matches(
        TOKEN,
        second.body,
        second.headers[cross_portal.SIGNATURE_HEADER],
        method="POST",
        path=SEARCH_PATH,
        nonce=NONCE,
    )


@needs_search_db
async def test_a_record_is_a_read_only_card_or_404(world, monkeypatch):
    from app.core.db import db

    request, settings = await _admitted(RECORD_PATH, monkeypatch)
    ok = await routes.peer_search_record(
        routes.PeerRecord(type="products", id=world.product_title.id, requester=_requester(world)),
        request,
        settings,
    )
    record = _body(ok)["record"]
    assert record["title"] == f"Indigo stole {world.stamp}"
    assert record["openUrl"] == f"{OPEN_BASE}/products/{world.product_title.id}"
    assert {f["label"] for f in record["fields"]} >= {"Type", "Place"}
    for secret in world.secrets:
        assert secret not in ok.body.decode()

    for kind, entity_id in (
        ("products", world.product_body.id),  # names an artisan without consent
        ("artisans", world.unconsented.id),
        ("products", "missing"),
        ("designWorkshops", world.product_title.id),  # not a type this portal holds
        ("nonsense", world.product_title.id),
    ):
        missing = await routes.peer_search_record(
            routes.PeerRecord(type=kind, id=entity_id, requester=_requester(world)),
            request,
            settings,
        )
        assert missing.status_code == 404 and _body(missing) == {"record": None}
    rows = await db.crossportalaccesslog.find_many(
        where={"remoteUserId": f"dp-{world.stamp}", "action": "RECORD_VIEW"}
    )
    assert {r.outcome for r in rows} == {"ok", "not_found"}
