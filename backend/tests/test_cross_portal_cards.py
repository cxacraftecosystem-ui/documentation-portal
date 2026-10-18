"""Artisan cards across the two portals, on the documentation portal's side.

The designer portal asks this server (signed, ``require_peer``) for the artisan behind one of THIS
portal's cards, tells it its own card code at link time and at reissue, and reads this portal's code
back on link and refresh. This server never calls the designer portal: a designer card resolves here
only through the code it was told (``CrossPortalArtisanLink.remoteCardCode``).

Routes called as functions against ``tests/artisan_card_fakes.FakeDB``; no database.
"""

from __future__ import annotations

import json
import pathlib
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from artisan_card_fakes import FakeDB
from pydantic import ValidationError

from app.api.routes import artisan_cards as card_routes
from app.api.routes import cross_portal as routes
from app.services import artisan_card, cross_portal

pytestmark = pytest.mark.anyio

ROOT = pathlib.Path(__file__).resolve().parents[2]
KEY = json.loads((ROOT / "shared" / "artisan-card-vectors.json").read_text(encoding="utf-8"))[
    "tags"
]["key"]
TOKEN = "t" * 40
NONCE = "n" * 32
DESIGNER_CODE = "7K3QM9XA2F"
NEW_DESIGNER_CODE = "ABCDEFGHJK"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _settings(**over):
    base = {
        "cross_portal_identity_enabled": True,
        "cross_portal_token": TOKEN,
        "aadhaar_fingerprint_key": "k" * 40,
        "artisan_cards_enabled": True,
        "artisan_card_key": KEY,
        "artisan_card_key_version": 1,
        "artisan_card_key_previous": None,
        "artisan_card_issuer": "F",
        "artisan_card_resolve_miss_limit": 20,
    }
    base.update(over)
    return SimpleNamespace(**base)


def _req(method="POST", path="/api/cross-portal/peer/cards/resolve"):
    return SimpleNamespace(
        method=method,
        url=SimpleNamespace(path=path),
        state=SimpleNamespace(cross_portal_nonce=NONCE),
        headers={cross_portal.REQUESTED_BY_HEADER: "dp-u1"},
    )


def _body(response, request) -> Any:
    assert cross_portal.signature_matches(
        TOKEN,
        response.body,
        response.headers.get(cross_portal.SIGNATURE_HEADER),
        method=request.method,
        path=request.url.path,
        nonce=NONCE,
    ), "every peer answer is signed for the request it answers"
    return json.loads(response.body)


def _artisan(i: int, **over) -> dict[str, Any]:
    row = {
        "id": f"a{i}",
        "name": f"Artisan {i}",
        "localName": None,
        "gender": "Female",
        "dateOfBirth": None,
        "pehchanCardAvailable": False,
        "pehchanCardNumber": None,
        "craftStartDate": None,
        "experienceYears": None,
        "experienceMonths": None,
        "phone": None,
        "place": "Bhuj",
        "aadhaarNumber": None,
        "aadhaarFingerprint": None,
        "aadhaarLast4": "9012",
        "status": "APPROVED",
        "craftId": None,
        "locationId": None,
        "createdById": "u1",
    }
    row.update(over)
    return row


@pytest.fixture
def world(monkeypatch):
    fake = FakeDB(
        artisan=[_artisan(1), _artisan(2), _artisan(3, status="REJECTED")],
        artisansharingconsent=[
            {"id": "s1", "artisanId": "a1", "granted": True},
            {"id": "s2", "artisanId": "a2", "granted": False},
            {"id": "s3", "artisanId": "a3", "granted": True},
        ],
    )
    settings = _settings()
    monkeypatch.setattr(routes, "db", fake)
    monkeypatch.setattr(routes, "get_settings", lambda: settings)
    monkeypatch.setattr(card_routes, "db", fake)
    monkeypatch.setattr(card_routes, "get_settings", lambda: settings)
    return SimpleNamespace(db=fake, settings=settings)


async def _issue(fake, artisan_id: str) -> Any:
    from app.services import artisan_card_store as store

    card, _ = await store.ensure_card(fake, artisan_id, settings=_settings(), user_id="u1")
    return card


async def _peer_resolve(world, code: str, tag: str | None = None, version: int | None = None):
    request = _req()
    response = await routes.peer_resolve_card(
        routes.PeerCardResolve(cardCode=code, tag=tag, keyVersion=version), request, world.settings
    )
    return _body(response, request)


async def test_peer_card_resolve_answers_a_consenting_artisan_with_the_card_code(world):
    card = await _issue(world.db, "a1")
    answer = await _peer_resolve(world, card.code)
    assert answer["match"]["artisanId"] == "a1"
    assert answer["match"]["matchedBy"] == "CARD"
    assert answer["match"]["cardCode"] == card.code
    assert "aadhaarNumber" not in answer["match"]["fields"]
    # The QR's own tag, when the designer portal passes it on, is checked here.
    tag = artisan_card.tag(KEY, "F", 1, card.code)
    assert (await _peer_resolve(world, card.code, tag, 1))["match"]["artisanId"] == "a1"
    forged = ("0" if tag[0] != "0" else "1") + tag[1:]
    assert await _peer_resolve(world, card.code, forged, 1) == {"match": None}


async def test_peer_card_resolve_is_null_without_consent_rejected_revoked_or_unknown(world):
    no_consent = await _issue(world.db, "a2")
    rejected = await _issue(world.db, "a3")
    assert await _peer_resolve(world, no_consent.code) == {"match": None}
    assert await _peer_resolve(world, rejected.code) == {"match": None}
    assert await _peer_resolve(world, "ZZZZZZZZZZ") == {"match": None}
    granted = await _issue(world.db, "a1")
    for row in world.db.artisancard.rows:
        if row["code"] == granted.code:
            row["revokedAt"] = datetime.now(UTC)
    assert await _peer_resolve(world, granted.code) == {"match": None}
    # Consent withdrawn after a card was issued: the same null.
    fresh = await _issue(world.db, "a1")
    world.db.artisansharingconsent.rows[0]["granted"] = False
    assert await _peer_resolve(world, fresh.code) == {"match": None}


async def test_peer_card_resolve_is_logged_without_the_code(world):
    card = await _issue(world.db, "a1")
    await _peer_resolve(world, card.code)
    await _peer_resolve(world, "ZZZZZZZZZZ")
    logs = [
        (r["action"], r["outcome"], r.get("artisanId")) for r in world.db.crossportalaccesslog.rows
    ]
    assert logs == [("CARD_RESOLVE", "MATCH", "a1"), ("CARD_RESOLVE", "NO_MATCH", None)]
    events = [e for e in world.db.artisancardevent.rows if e["action"] == "PEER_RESOLVE"]
    assert len(events) == 1 and events[0]["source"] == "PEER" and events[0]["artisanId"] == "a1"


async def test_peer_card_resolve_is_null_while_cards_are_off(world):
    card = await _issue(world.db, "a1")
    off = _settings(artisan_card_key=None)
    request = _req()
    response = await routes.peer_resolve_card(
        routes.PeerCardResolve(cardCode=card.code), request, off
    )
    assert json.loads(response.body) == {"match": None}


async def test_link_stores_the_designer_card_code_and_answers_with_this_portals(world):
    request = _req(path="/api/cross-portal/peer/artisans/a1/links")
    response = await routes.peer_link(
        "a1",
        routes.PeerLink(remoteArtisanId="dp1", matchedBy="CARD", remoteCardCode=DESIGNER_CODE),
        request,
        world.settings,
    )
    answer = _body(response, request)
    assert answer["linked"] is True
    live = next(
        c for c in world.db.artisancard.rows if c["artisanId"] == "a1" and c["revokedAt"] is None
    )
    assert answer["cardCode"] == live["code"]
    (link,) = world.db.crossportalartisanlink.rows
    assert link["remoteCardCode"] == DESIGNER_CODE and link["matchedBy"] == "CARD"
    # The designer card now opens this artisan here, typed or scanned.
    found = await card_routes.resolve_card(
        card_routes.ResolveIn(input=artisan_card.short_code("D", DESIGNER_CODE)),
        current_user=SimpleNamespace(id="u9", role="RESEARCHER"),
    )
    assert found["status"] == "FOUND" and found["via"] == "LINK" and found["artisan"]["id"] == "a1"


async def test_link_refuses_a_malformed_card_code_and_old_bodies_still_link(world):
    with pytest.raises(ValidationError):
        routes.PeerLink(remoteArtisanId="dp1", matchedBy="CARD", remoteCardCode="a1-in-clear")
    with pytest.raises(ValidationError):
        routes.PeerLink(remoteArtisanId="dp1", matchedBy="AADHAAR", somethingElse="x")
    request = _req(path="/api/cross-portal/peer/artisans/a1/links")
    response = await routes.peer_link(
        "a1", routes.PeerLink(remoteArtisanId="dp1", matchedBy="AADHAAR"), request, world.settings
    )
    assert _body(response, request)["linked"] is True
    assert world.db.crossportalartisanlink.rows[0].get("remoteCardCode") is None


async def test_put_replaces_the_designer_code_after_a_reissue_and_404s_without_a_link(world):
    world.db.crossportalartisanlink.rows.append(
        {
            "id": "l1",
            "artisanId": "a1",
            "remoteArtisanId": "dp1",
            "matchedBy": "AADHAAR",
            "remoteCardCode": DESIGNER_CODE,
        }
    )
    request = _req(method="PUT", path="/api/cross-portal/peer/artisans/a1/links/dp1/card")
    response = await routes.peer_update_card(
        "a1",
        "dp1",
        routes.PeerCardUpdate(remoteCardCode=NEW_DESIGNER_CODE),
        request,
        world.settings,
    )
    assert _body(response, request) == {"updated": True}
    assert world.db.crossportalartisanlink.rows[0]["remoteCardCode"] == NEW_DESIGNER_CODE
    missing = await routes.peer_update_card(
        "a1",
        "dp-other",
        routes.PeerCardUpdate(remoteCardCode=NEW_DESIGNER_CODE),
        request,
        world.settings,
    )
    assert missing.status_code == 404
    actions = [(r["action"], r["outcome"]) for r in world.db.crossportalaccesslog.rows]
    assert actions == [("CARD_UPDATE", "UPDATED"), ("CARD_UPDATE", "NO_MATCH")]


async def test_a_code_moved_to_another_link_is_freed_from_the_first(world):
    world.db.crossportalartisanlink.rows.extend(
        [
            {
                "id": "l1",
                "artisanId": "a1",
                "remoteArtisanId": "dp1",
                "matchedBy": "AADHAAR",
                "remoteCardCode": DESIGNER_CODE,
            },
            {
                "id": "l2",
                "artisanId": "a2",
                "remoteArtisanId": "dp2",
                "matchedBy": "AADHAAR",
                "remoteCardCode": None,
            },
        ]
    )
    request = _req(method="PUT", path="/api/cross-portal/peer/artisans/a2/links/dp2/card")
    await routes.peer_update_card(
        "a2", "dp2", routes.PeerCardUpdate(remoteCardCode=DESIGNER_CODE), request, world.settings
    )
    by_id = {r["id"]: r["remoteCardCode"] for r in world.db.crossportalartisanlink.rows}
    assert by_id == {"l1": None, "l2": DESIGNER_CODE}


async def test_refresh_answers_with_this_portals_card_code(world):
    world.db.crossportalartisanlink.rows.append(
        {
            "id": "l1",
            "artisanId": "a1",
            "remoteArtisanId": "dp1",
            "matchedBy": "AADHAAR",
            "remoteCardCode": None,
        }
    )
    request = _req(method="GET", path="/api/cross-portal/peer/artisans/a1")
    answer = _body(await routes.peer_refresh("a1", "dp1", request, world.settings), request)
    assert answer["match"]["artisanId"] == "a1"
    live = next(
        c for c in world.db.artisancard.rows if c["artisanId"] == "a1" and c["revokedAt"] is None
    )
    assert answer["cardCode"] == live["code"]
    # With cards off the key is still there, empty, so the designer portal reads one shape.
    off = _settings(artisan_card_key=None)
    answer = _body(await routes.peer_refresh("a1", "dp1", request, off), request)
    assert answer["cardCode"] is None


def test_matched_by_card_is_shared_and_the_module_matches_the_sibling():
    assert cross_portal.MATCHED_BY_CARD == "CARD"
    assert cross_portal.MATCHED_BY == ("PEHCHAN", "AADHAAR", "CARD")
    sibling = ROOT.parent / "designer-portal" / "backend" / "app" / "services"
    for name in ("artisan_card.py",):
        here = (ROOT / "backend" / "app" / "services" / name).read_bytes().replace(b"\r\n", b"\n")
        there_path = sibling / name
        if there_path.exists():
            assert there_path.read_bytes().replace(b"\r\n", b"\n") == here
