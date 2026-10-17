"""C27 on the documentation portal: the peer answers, consent, and the switch that keeps it all off.

No database: the route module's ``db`` and ``get_settings`` are replaced by fakes, and the routes are
called as functions with the dependencies they would have been given.
"""

from __future__ import annotations

import json
from datetime import datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import cross_portal as routes
from app.services import cross_portal

pytestmark = pytest.mark.anyio

TOKEN = "t" * 40
KEY = "k" * 40
AADHAAR = "234123412346"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _match(row: dict[str, Any], where: dict[str, Any]) -> bool:
    for key, want in where.items():
        have = row.get(key)
        if isinstance(want, dict):
            if "in" in want and have not in want["in"]:
                return False
            if "not" in want and have == want["not"]:
                return False
        elif have != want:
            return False
    return True


class Table:
    def __init__(self, rows=None, key="id"):
        self.rows = [dict(r) for r in rows or []]
        self.key = key

    async def find_unique(self, where, include=None):
        hit = next((r for r in self.rows if _match(r, where)), None)
        return SimpleNamespace(**hit) if hit else None

    find_first = find_unique

    async def find_many(self, where=None, order=None, include=None):
        return [SimpleNamespace(**r) for r in self.rows if _match(r, where or {})]

    async def create(self, data):
        row = {"id": f"row{len(self.rows) + 1}", **data}
        self.rows.append(row)
        return SimpleNamespace(**row)

    async def upsert(self, where, data):
        for row in self.rows:
            if _match(row, where):
                row.update(data["update"])
                return SimpleNamespace(**row)
        return await self.create(data["create"])

    async def delete_many(self, where):
        before = len(self.rows)
        self.rows = [r for r in self.rows if not _match(r, where)]
        return before - len(self.rows)

    async def count(self, where=None):
        return len([r for r in self.rows if _match(r, where or {})])


def _settings(**over):
    base = {
        "cross_portal_identity_enabled": True,
        "cross_portal_token": TOKEN,
        "cross_portal_aadhaar_hmac_key": KEY,
    }
    base.update(over)
    return SimpleNamespace(**base)


@pytest.fixture
def world(monkeypatch):
    craft = SimpleNamespace(name="Bandhej", craftCode="bandhani")
    artisans = Table(
        [
            {
                "id": "a1",
                "name": "Meena Devi",
                "localName": None,
                "gender": "Female",
                "dateOfBirth": datetime(1980, 5, 1),
                "pehchanCardAvailable": True,
                "pehchanCardNumber": "PEH12345",
                "craftStartDate": None,
                "experienceYears": 20,
                "experienceMonths": None,
                "phone": "9999999999",
                "place": "Bhuj",
                "aadhaarNumber": AADHAAR,
                "status": "APPROVED",
                "craft": craft,
                "location": SimpleNamespace(state="Gujarat", district="Kachchh", village="Nirona"),
                "createdById": "u1",
            },
            {
                "id": "a2",
                "name": "No Consent",
                "pehchanCardNumber": "PEH99999",
                "aadhaarNumber": "987698769876",
                "status": "APPROVED",
                "createdById": "u1",
            },
        ]
    )
    fake = SimpleNamespace(
        artisan=artisans,
        artisansharingconsent=Table([{"artisanId": "a1", "granted": True}], key="artisanId"),
        crossportalartisanlink=Table(),
    )
    monkeypatch.setattr(routes, "db", fake)
    monkeypatch.setattr(routes, "get_settings", lambda: _settings())
    return fake


def _body(response) -> Any:
    assert cross_portal.signature_matches(
        TOKEN, response.body, response.headers.get(cross_portal.SIGNATURE_HEADER)
    ), "every peer answer is signed"
    return json.loads(response.body)


# ── the shared module ──────────────────────────────────────────────────────────────────────────


def test_active_needs_the_flag_and_both_long_secrets():
    assert cross_portal.active(_settings())
    assert not cross_portal.active(_settings(cross_portal_identity_enabled=False))
    assert not cross_portal.active(_settings(cross_portal_token=None))
    assert not cross_portal.active(_settings(cross_portal_token="short"))
    assert not cross_portal.active(_settings(cross_portal_aadhaar_hmac_key=""))
    assert not cross_portal.active(_settings(), needs_url=True)
    assert cross_portal.active(_settings(field_repository_api_url="https://x/api"), needs_url=True)


def test_the_aadhaar_code_is_keyed_stable_and_never_the_number():
    code = cross_portal.aadhaar_code(AADHAAR, KEY)
    assert code == cross_portal.aadhaar_code("2341 2341 2346", KEY)
    assert code != cross_portal.aadhaar_code(AADHAAR, "x" * 40)
    assert AADHAAR not in code and len(code) == 64


def test_signatures_and_bearer_are_checked():
    body = b'{"match":null}'
    assert cross_portal.signature_matches(TOKEN, body, cross_portal.sign(TOKEN, body))
    assert not cross_portal.signature_matches(TOKEN, body + b" ", cross_portal.sign(TOKEN, body))
    assert not cross_portal.signature_matches(TOKEN, body, None)
    assert cross_portal.bearer_matches(TOKEN, f"Bearer {TOKEN}")
    assert not cross_portal.bearer_matches(TOKEN, "Bearer wrong")
    assert not cross_portal.bearer_matches(TOKEN, None)


def test_the_shared_fields_carry_no_aadhaar_and_no_review_state():
    assert "aadhaarNumber" not in cross_portal.SHARED_FIELDS
    assert not {"status", "reviewNotes", "notes"} & set(cross_portal.SHARED_FIELDS)
    assert set(cross_portal.IDENTITY_FIELDS) <= set(cross_portal.SHARED_FIELDS)


# ── the peer ───────────────────────────────────────────────────────────────────────────────────


async def test_the_peer_is_refused_without_the_token_and_404_while_off(world, monkeypatch):
    with pytest.raises(HTTPException) as exc:
        await routes.require_peer(authorization="Bearer nope")
    assert exc.value.status_code == 401
    monkeypatch.setattr(routes, "get_settings", lambda: _settings(cross_portal_identity_enabled=False))
    with pytest.raises(HTTPException) as exc:
        await routes.require_peer(authorization=f"Bearer {TOKEN}")
    assert exc.value.status_code == 404


async def test_lookup_by_pehchan_answers_only_with_consent(world):
    found = _body(await routes.peer_lookup(routes.PeerLookup(pehchanCardNumber="peh-12345"), _settings()))
    assert found["match"]["artisanId"] == "a1"
    assert found["match"]["matchedBy"] == "PEHCHAN"
    assert found["match"]["craft"] == {"code": "bandhani", "name": "Bandhej"}
    assert found["match"]["statedAddress"]["district"] == "Kachchh"
    assert "aadhaarNumber" not in found["match"]["fields"]
    none = _body(await routes.peer_lookup(routes.PeerLookup(pehchanCardNumber="PEH99999"), _settings()))
    assert none == {"match": None}


async def test_lookup_by_aadhaar_code_compares_only_consenting_artisans(world):
    code = cross_portal.aadhaar_code(AADHAAR, KEY)
    found = _body(await routes.peer_lookup(routes.PeerLookup(aadhaarCode=code), _settings()))
    assert found["match"]["matchedBy"] == "AADHAAR"
    other = cross_portal.aadhaar_code("987698769876", KEY)
    assert _body(await routes.peer_lookup(routes.PeerLookup(aadhaarCode=other), _settings())) == {
        "match": None
    }


async def test_links_need_consent_and_withdrawal_removes_them(world, monkeypatch):
    linked = await routes.peer_link("a1", routes.PeerLink(remoteArtisanId="dp1", matchedBy="AADHAAR"), _settings())
    assert _body(linked) == {"linked": True}
    assert len(world.crossportalartisanlink.rows) == 1
    refused = await routes.peer_link("a2", routes.PeerLink(remoteArtisanId="dp2", matchedBy="PEHCHAN"), _settings())
    assert refused.status_code == 409
    refresh = await routes.peer_refresh("a1", "dp1", _settings())
    assert _body(refresh)["match"]["fields"]["name"] == "Meena Devi"
    assert (await routes.peer_refresh("a1", "someone-else", _settings())).status_code == 404

    async def may_edit(*_a, **_k):
        return True

    monkeypatch.setattr(routes, "record_edit_privilege", may_edit)
    out = await routes.set_consent("a1", routes.ConsentIn(granted=False), SimpleNamespace(id="u1"))
    assert out["granted"] is False and out["linkedRecords"] == 0
    assert world.crossportalartisanlink.rows == []


async def test_only_an_editor_records_consent(world, monkeypatch):
    async def may_not(*_a, **_k):
        return False

    monkeypatch.setattr(routes, "record_edit_privilege", may_not)
    with pytest.raises(HTTPException) as exc:
        await routes.set_consent("a2", routes.ConsentIn(granted=True), SimpleNamespace(id="u9"))
    assert exc.value.status_code == 403


async def test_status_says_off_by_default(monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings(cross_portal_identity_enabled=False))
    assert await routes.cross_portal_status(SimpleNamespace(id="u1")) == {"enabled": False}


def test_the_config_defaults_to_off():
    from app.core.config import Settings

    fields = Settings.model_fields
    assert fields["cross_portal_identity_enabled"].default is False
    assert fields["cross_portal_token"].default is None
    assert fields["cross_portal_aadhaar_hmac_key"].default is None
