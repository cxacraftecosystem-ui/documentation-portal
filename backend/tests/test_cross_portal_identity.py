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
from app.services import aadhaar_fingerprint, cross_portal

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
        "aadhaar_fingerprint_key": KEY,
    }
    base.update(over)
    return SimpleNamespace(**base)


NONCE = "n" * 32


def _req(method="POST", path="/api/cross-portal/peer/artisans/lookup", nonce=NONCE, asked_by="dp-u1"):
    """A stand-in for the FastAPI request: what the signature and the access log read."""
    return SimpleNamespace(
        method=method,
        url=SimpleNamespace(path=path),
        state=SimpleNamespace(cross_portal_nonce=nonce),
        headers={cross_portal.REQUESTED_BY_HEADER: asked_by} if asked_by else {},
    )


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
                # As this release stores it: the fingerprint and the last four, never the number.
                "aadhaarNumber": None,
                "aadhaarFingerprint": aadhaar_fingerprint.fingerprint(AADHAAR, KEY),
                "aadhaarLast4": AADHAAR[-4:],
                "status": "APPROVED",
                "craft": craft,
                "location": SimpleNamespace(state="Gujarat", district="Kachchh", village="Nirona"),
                "createdById": "u1",
            },
            {
                "id": "a2",
                "name": "No Consent",
                "pehchanCardNumber": "PEH99999",
                "aadhaarNumber": None,
                "aadhaarFingerprint": aadhaar_fingerprint.fingerprint("987698769876", KEY),
                "aadhaarLast4": "9876",
                "status": "APPROVED",
                "createdById": "u1",
            },
        ]
    )
    fake = SimpleNamespace(
        artisan=artisans,
        artisansharingconsent=Table([{"artisanId": "a1", "granted": True}], key="artisanId"),
        crossportalartisanlink=Table(),
        crossportalaccesslog=Table(),
    )
    monkeypatch.setattr(routes, "db", fake)
    monkeypatch.setattr(routes, "get_settings", lambda: _settings())
    revisions: list[Any] = []

    async def record_revision(record, user, data, record_type, **_k):
        revisions.append((record, user, data, record_type))

    monkeypatch.setattr(routes, "record_revision", record_revision)
    fake.revisions = revisions
    return fake


def _body(response, request=None) -> Any:
    request = request or _req()
    assert cross_portal.signature_matches(
        TOKEN,
        response.body,
        response.headers.get(cross_portal.SIGNATURE_HEADER),
        method=request.method,
        path=request.url.path,
        nonce=request.state.cross_portal_nonce,
    ), "every peer answer is signed for the request it answers"
    return json.loads(response.body)


# ── the shared module ──────────────────────────────────────────────────────────────────────────


def test_active_needs_the_flag_and_both_long_secrets():
    assert cross_portal.active(_settings())
    assert not cross_portal.active(_settings(cross_portal_identity_enabled=False))
    assert not cross_portal.active(_settings(cross_portal_token=None))
    assert not cross_portal.active(_settings(cross_portal_token="short"))
    assert not cross_portal.active(_settings(aadhaar_fingerprint_key=""))
    assert not cross_portal.active(_settings(aadhaar_fingerprint_key="short"))
    assert not cross_portal.active(_settings(), needs_url=True)
    assert cross_portal.active(_settings(field_repository_api_url="https://x/api"), needs_url=True)


def test_the_aadhaar_code_is_keyed_stable_and_never_the_number():
    code = cross_portal.aadhaar_code(AADHAAR, KEY)
    assert code == cross_portal.aadhaar_code("2341 2341 2346", KEY)
    assert code != cross_portal.aadhaar_code(AADHAAR, "x" * 40)
    assert AADHAAR not in code and len(code) == 64


def test_signatures_are_bound_to_the_request_and_bearer_is_checked():
    body = b'{"match":null}'
    where = {"method": "POST", "path": "/cross-portal/peer/artisans/lookup", "nonce": NONCE}
    signature = cross_portal.sign(TOKEN, body, **where)
    assert cross_portal.signature_matches(TOKEN, body, signature, **where)
    assert not cross_portal.signature_matches(TOKEN, body + b" ", signature, **where)
    assert not cross_portal.signature_matches(TOKEN, body, None, **where)
    # A captured answer replayed against another request: another nonce, path or method fails.
    assert not cross_portal.signature_matches(TOKEN, body, signature, **{**where, "nonce": "m" * 32})
    assert not cross_portal.signature_matches(
        TOKEN, body, signature, **{**where, "path": "/cross-portal/peer/artisans/a1"}
    )
    assert not cross_portal.signature_matches(TOKEN, body, signature, **{**where, "method": "GET"})
    # A proxy prefix in front of the peer path does not change what is signed.
    assert cross_portal.signature_matches(
        TOKEN, body, signature, **{**where, "path": "/api/cross-portal/peer/artisans/lookup"}
    )
    assert cross_portal.bearer_matches(TOKEN, f"Bearer {TOKEN}")
    assert not cross_portal.bearer_matches(TOKEN, "Bearer wrong")
    assert not cross_portal.bearer_matches(TOKEN, None)


def test_the_shared_fields_carry_no_aadhaar_and_no_review_state():
    assert "aadhaarNumber" not in cross_portal.SHARED_FIELDS
    assert not {"status", "reviewNotes", "notes"} & set(cross_portal.SHARED_FIELDS)
    assert set(cross_portal.IDENTITY_FIELDS) <= set(cross_portal.SHARED_FIELDS)


def test_the_consent_names_everything_a_lookup_sends():
    """One list: every key of the answer has words in the consent, and nothing else does."""
    assert set(cross_portal.SHARED_FIELD_LABELS) == set(cross_portal.SHARED_FIELDS) | set(
        cross_portal.ANSWER_EXTRAS
    )
    phrase = cross_portal.consent_fields_phrase()
    for words in ("phone number", "address", "place", "craft", "craft experience", "date of birth"):
        assert words in phrase


async def test_every_answer_key_is_named_in_the_consent(world):
    found = _body(
        await routes.peer_lookup(routes.PeerLookup(pehchanCardNumber="PEH12345"), _req(), _settings())
    )["match"]
    sent = set(found["fields"]) | ({"craft", "statedAddress"} & set(found))
    assert sent <= set(cross_portal.SHARED_FIELD_LABELS)


def test_the_clients_fall_back_to_the_servers_words():
    """The web and Android copy, used before the status answer arrives, is the server's phrase."""
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    phrase = cross_portal.consent_fields_phrase()
    web = (root / "frontend/components/forms/SharingConsentToggle.tsx").read_text(encoding="utf-8")
    kotlin = (
        root / "android/app/src/main/java/com/fieldrepository/app/data/CrossPortalSharing.kt"
    ).read_text(encoding="utf-8")
    assert f'"{phrase}"' in web
    assert f'"{phrase}"' in kotlin


# ── the peer ───────────────────────────────────────────────────────────────────────────────────


async def test_the_peer_is_refused_without_the_token_or_a_nonce_and_404_while_off(world, monkeypatch):
    request = _req()
    with pytest.raises(HTTPException) as exc:
        await routes.require_peer(request, authorization="Bearer nope", x_cross_portal_nonce=NONCE)
    assert exc.value.status_code == 401
    with pytest.raises(HTTPException) as exc:
        await routes.require_peer(request, authorization=f"Bearer {TOKEN}", x_cross_portal_nonce="short")
    assert exc.value.status_code == 401
    assert await routes.require_peer(request, authorization=f"Bearer {TOKEN}", x_cross_portal_nonce=NONCE)
    monkeypatch.setattr(routes, "get_settings", lambda: _settings(cross_portal_identity_enabled=False))
    with pytest.raises(HTTPException) as exc:
        await routes.require_peer(request, authorization=f"Bearer {TOKEN}", x_cross_portal_nonce=NONCE)
    assert exc.value.status_code == 404


async def test_lookup_by_pehchan_answers_only_with_consent(world):
    found = _body(
        await routes.peer_lookup(routes.PeerLookup(pehchanCardNumber="peh-12345"), _req(), _settings())
    )
    assert found["match"]["artisanId"] == "a1"
    assert found["match"]["matchedBy"] == "PEHCHAN"
    assert found["match"]["craft"] == {"code": "bandhani", "name": "Bandhej"}
    assert found["match"]["statedAddress"]["district"] == "Kachchh"
    assert "aadhaarNumber" not in found["match"]["fields"]
    none = _body(
        await routes.peer_lookup(routes.PeerLookup(pehchanCardNumber="PEH99999"), _req(), _settings())
    )
    assert none == {"match": None}


async def test_every_peer_lookup_is_logged_without_the_number(world):
    await routes.peer_lookup(routes.PeerLookup(pehchanCardNumber="PEH12345"), _req(), _settings())
    await routes.peer_lookup(routes.PeerLookup(pehchanCardNumber="PEH00000"), _req(asked_by=None), _settings())
    logs = world.crossportalaccesslog.rows
    assert [(r["action"], r["outcome"], r["artisanId"], r["remoteUserId"]) for r in logs] == [
        ("LOOKUP", "MATCH", "a1", "dp-u1"),
        ("LOOKUP", "NO_MATCH", None, None),
    ]
    assert "PEH12345" not in json.dumps(logs, default=str)


async def test_lookup_by_aadhaar_code_compares_only_consenting_artisans(world):
    code = cross_portal.aadhaar_code(AADHAAR, KEY)
    found = _body(await routes.peer_lookup(routes.PeerLookup(aadhaarCode=code), _req(), _settings()))
    assert found["match"]["matchedBy"] == "AADHAAR"
    other = cross_portal.aadhaar_code("987698769876", KEY)
    assert _body(
        await routes.peer_lookup(routes.PeerLookup(aadhaarCode=other), _req(), _settings())
    ) == {"match": None}
    assert code not in json.dumps(world.crossportalaccesslog.rows, default=str)


async def test_the_code_is_the_stored_fingerprint(world):
    """The register holds no number: the lookup compares the code with the fingerprint column, which
    under the one shared key is exactly what the designer portal stores and sends."""
    stored = world.artisan.rows[0]["aadhaarFingerprint"]
    assert cross_portal.aadhaar_code(AADHAAR, KEY) == stored
    found = _body(await routes.peer_lookup(routes.PeerLookup(aadhaarCode=stored.upper()), _req(), _settings()))
    assert found["match"]["artisanId"] == "a1"


async def test_a_consenting_row_the_backfill_has_not_reached_still_matches(world):
    row = world.artisan.rows[0]
    row.update(aadhaarNumber=AADHAAR, aadhaarFingerprint=None, aadhaarLast4=None)
    code = cross_portal.aadhaar_code(AADHAAR, KEY)
    found = _body(await routes.peer_lookup(routes.PeerLookup(aadhaarCode=code), _req(), _settings()))
    assert found["match"]["artisanId"] == "a1"
    # A legacy row WITHOUT consent is never compared.
    other = world.artisan.rows[1]
    other.update(aadhaarNumber="987698769876", aadhaarFingerprint=None)
    miss = cross_portal.aadhaar_code("987698769876", KEY)
    assert _body(
        await routes.peer_lookup(routes.PeerLookup(aadhaarCode=miss), _req(), _settings())
    ) == {"match": None}


async def test_links_need_consent_and_withdrawal_removes_them(world, monkeypatch):
    link_req = _req(path="/cross-portal/peer/artisans/a1/links")
    linked = await routes.peer_link(
        "a1", routes.PeerLink(remoteArtisanId="dp1", matchedBy="AADHAAR"), link_req, _settings()
    )
    assert _body(linked, link_req) == {"linked": True}
    assert len(world.crossportalartisanlink.rows) == 1
    refused = await routes.peer_link(
        "a2", routes.PeerLink(remoteArtisanId="dp2", matchedBy="PEHCHAN"), _req(), _settings()
    )
    assert refused.status_code == 409
    get_req = _req(method="GET", path="/cross-portal/peer/artisans/a1")
    refresh = await routes.peer_refresh("a1", "dp1", get_req, _settings())
    assert _body(refresh, get_req)["match"]["fields"]["name"] == "Meena Devi"
    assert (await routes.peer_refresh("a1", "someone-else", get_req, _settings())).status_code == 404
    outcomes = [(r["action"], r["outcome"]) for r in world.crossportalaccesslog.rows]
    assert outcomes == [
        ("LINK", "LINKED"),
        ("LINK", "REFUSED"),
        ("REFRESH", "MATCH"),
        ("REFRESH", "NO_MATCH"),
    ]

    async def may_edit(*_a, **_k):
        return True

    monkeypatch.setattr(routes, "record_edit_privilege", may_edit)
    out = await routes.set_consent("a1", routes.ConsentIn(granted=False), SimpleNamespace(id="u1"))
    assert out["granted"] is False and out["linkedRecords"] == 0
    assert world.crossportalartisanlink.rows == []


async def test_a_consent_change_is_written_to_the_audit_ledger(world, monkeypatch):
    async def may_edit(*_a, **_k):
        return True

    monkeypatch.setattr(routes, "record_edit_privilege", may_edit)
    user = SimpleNamespace(id="u1")
    await routes.set_consent("a1", routes.ConsentIn(granted=False), user)
    await routes.set_consent("a1", routes.ConsentIn(granted=True), user)
    trail = [(r.id, r.sharingConsent, d, t) for r, _u, d, t in world.revisions]
    assert trail == [
        ("a1", True, {"sharingConsent": False}, "artisan"),
        ("a1", False, {"sharingConsent": True}, "artisan"),
    ]


async def test_only_an_editor_records_consent(world, monkeypatch):
    async def may_not(*_a, **_k):
        return False

    monkeypatch.setattr(routes, "record_edit_privilege", may_not)
    with pytest.raises(HTTPException) as exc:
        await routes.set_consent("a2", routes.ConsentIn(granted=True), SimpleNamespace(id="u9"))
    assert exc.value.status_code == 403
    assert world.revisions == []


async def test_status_says_off_by_default_and_names_what_is_shared(monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings(cross_portal_identity_enabled=False))
    assert await routes.cross_portal_status(SimpleNamespace(id="u1")) == {
        "enabled": False,
        "sharedFields": cross_portal.consent_fields_phrase(),
    }


def test_the_config_defaults_to_off():
    from app.core.config import Settings

    fields = Settings.model_fields
    assert fields["cross_portal_identity_enabled"].default is False
    assert fields["cross_portal_token"].default is None
