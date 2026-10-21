"""The designer portal's duplicate scan, answered here: ``POST /cross-portal/peer/artisans/match``.

Ids only, consenting artisans only, every match logged, 100 items a call and 50 calls a day. Also
the peer routes' handling of an artisan merged here: the refresh follows the merge and its undo.
No database: the route module's ``db`` is replaced by a fake, as in ``test_cross_portal_identity``.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import ValidationError

from app.api.routes import cross_portal as routes
from app.services import aadhaar_fingerprint, artisan_merge, cross_portal

pytestmark = pytest.mark.anyio

TOKEN = "t" * 40
KEY = "k" * 40
NONCE = "n" * 32


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _ok(row: dict[str, Any], where: dict[str, Any]) -> bool:
    for key, want in (where or {}).items():
        if key == "OR":
            if not any(_ok(row, clause) for clause in want):
                return False
            continue
        have = row.get(key)
        if isinstance(want, dict):
            if "in" in want and have not in want["in"]:
                return False
            if "gte" in want and (have is None or have < want["gte"]):
                return False
            if "not" in want and have == want["not"]:
                return False
        elif have != want:
            return False
    return True


class Table:
    def __init__(self, rows=None):
        self.rows = [dict(r) for r in rows or []]

    async def find_unique(self, where, include=None):
        hit = next((r for r in self.rows if _ok(r, where)), None)
        return SimpleNamespace(**hit) if hit else None

    find_first = find_unique

    async def find_many(self, where=None, include=None, order=None):
        return [SimpleNamespace(**r) for r in self.rows if _ok(r, where or {})]

    async def create(self, data):
        row = {"id": f"row{len(self.rows) + 1}", "createdAt": datetime.now(UTC), **data}
        self.rows.append(row)
        return SimpleNamespace(**row)

    async def create_many(self, data):
        for item in data:
            await self.create(item)
        return len(data)

    async def delete_many(self, where):
        before = len(self.rows)
        self.rows = [r for r in self.rows if not _ok(r, where)]
        return before - len(self.rows)

    async def count(self, where=None):
        return len([r for r in self.rows if _ok(r, where or {})])


def _settings():
    return SimpleNamespace(cross_portal_identity_enabled=True, cross_portal_token=TOKEN, aadhaar_fingerprint_key=KEY)


def _req(path="/api/cross-portal/peer/artisans/match", method="POST"):
    return SimpleNamespace(
        method=method,
        url=SimpleNamespace(path=path),
        state=SimpleNamespace(cross_portal_nonce=NONCE),
        headers={cross_portal.REQUESTED_BY_HEADER: "system:duplicate-scan"},
    )


def _fp(number: str) -> str:
    return aadhaar_fingerprint.fingerprint(number, KEY)


@pytest.fixture
def world(monkeypatch):
    db = SimpleNamespace(
        artisan=Table([
            {"id": "fr1", "name": "Meena Devi", "pehchanCardNumber": "PEH1", "aadhaarFingerprint": None, "status": "APPROVED"},
            {"id": "fr2", "name": "Kamla", "pehchanCardNumber": None, "aadhaarFingerprint": _fp("234123412346"), "status": "PENDING"},
            {"id": "fr3", "name": "No consent", "pehchanCardNumber": "PEH3", "aadhaarFingerprint": None, "status": "APPROVED"},
            {"id": "fr4", "name": "Rejected", "pehchanCardNumber": "PEH4", "aadhaarFingerprint": None, "status": "REJECTED"},
            {"id": "fr5", "name": "Withdrawn", "pehchanCardNumber": "PEH5", "aadhaarFingerprint": None, "status": "APPROVED"},
        ]),
        artisansharingconsent=Table([
            {"artisanId": "fr1", "granted": True},
            {"artisanId": "fr2", "granted": True},
            {"artisanId": "fr4", "granted": True},
            {"artisanId": "fr5", "granted": False},
        ]),
        crossportalaccesslog=Table(),
        crossportalartisanlink=Table(),
        artisanmerge=Table(),
    )
    monkeypatch.setattr(routes, "db", db)
    monkeypatch.setattr(artisan_merge, "db", db)
    monkeypatch.setattr(aadhaar_fingerprint, "LEGACY_COLUMN_PRESENT", False)
    artisan_merge.forget_redirects()
    yield db
    artisan_merge.forget_redirects()


def _body(response) -> dict[str, Any]:
    return json.loads(response.body)


async def _match(items):
    return await routes.peer_match(routes.PeerMatch(items=items), _req(), _settings())


async def test_only_consenting_non_rejected_artisans_match_and_only_ids_come_back(world):
    response = await _match([
        {"ref": "dp1", "pehchanCardNumber": "peh1"},
        {"ref": "dp2", "aadhaarCode": _fp("234123412346")},
        {"ref": "dp3", "pehchanCardNumber": "PEH3"},
        {"ref": "dp4", "pehchanCardNumber": "PEH4"},
        {"ref": "dp5", "pehchanCardNumber": "PEH5"},
        {"ref": "dp6", "pehchanCardNumber": "NOBODY"},
    ])
    assert response.status_code == 200
    assert response.headers[cross_portal.SIGNATURE_HEADER]
    matches = sorted(_body(response)["matches"], key=lambda m: m["ref"])
    assert matches == [
        {"ref": "dp1", "artisanId": "fr1", "matchedBy": "PEHCHAN"},
        {"ref": "dp2", "artisanId": "fr2", "matchedBy": "AADHAAR"},
    ]
    text = response.body.decode()
    assert "Meena" not in text and "PEH1" not in text


async def test_no_consent_is_the_same_answer_as_not_on_file(world):
    no_consent = _body(await _match([{"ref": "dp3", "pehchanCardNumber": "PEH3"}]))
    absent = _body(await _match([{"ref": "dp3", "pehchanCardNumber": "ZZZ999"}]))
    assert no_consent == absent == {"matches": []}


async def test_every_match_is_logged_and_each_call_once(world):
    await _match([{"ref": "dp1", "pehchanCardNumber": "PEH1"}, {"ref": "dp2", "aadhaarCode": _fp("234123412346")}])
    rows = world.crossportalaccesslog.rows
    matches = [r for r in rows if r["outcome"] == "MATCH"]
    assert {(r["artisanId"], r["remoteArtisanId"], r["action"]) for r in matches} == {
        ("fr1", "dp1", "SCAN"), ("fr2", "dp2", "SCAN")}
    assert [r for r in rows if r["outcome"] == "SUMMARY"] and all(r["remoteUserId"] == "system:duplicate-scan" for r in rows)
    assert "PEH1" not in json.dumps(rows, default=str)


def test_a_batch_holds_at_most_one_hundred_items_and_nothing_extra():
    routes.PeerMatch(items=[{"ref": f"d{i}"} for i in range(100)])
    with pytest.raises(ValidationError):
        routes.PeerMatch(items=[{"ref": f"d{i}"} for i in range(101)])
    with pytest.raises(ValidationError):
        routes.PeerMatch(items=[{"ref": "d", "name": "Meena"}])


async def test_fifty_calls_a_day_then_429(world):
    for _ in range(routes.MATCH_CALLS_PER_DAY):
        assert (await _match([{"ref": "dp1", "pehchanCardNumber": "PEH1"}])).status_code == 200
    limited = await _match([{"ref": "dp1", "pehchanCardNumber": "PEH1"}])
    assert limited.status_code == 429
    assert _body(limited)["matches"] == []
    # Yesterday's calls do not count.
    for row in world.crossportalaccesslog.rows:
        row["createdAt"] = datetime.now(UTC) - timedelta(days=1)
    assert (await _match([{"ref": "dp1", "pehchanCardNumber": "PEH1"}])).status_code == 200


async def test_refresh_follows_a_merge_and_its_undo(world):
    world.crossportalartisanlink.rows.append({"id": "l1", "artisanId": "fr1", "remoteArtisanId": "dp9", "matchedBy": "PEHCHAN"})
    path = "/api/cross-portal/peer/artisans/frOld"
    # frOld was merged into fr1 here; the designer portal still knows frOld.
    world.artisanmerge.rows.append({"id": "m1", "survivorId": "fr1", "mergedId": "frOld", "status": "DONE", "undoneAt": None})
    response = await routes.peer_refresh("frOld", "dp9", _req(path, "GET"), _settings())
    assert response.status_code == 200
    assert _body(response)["match"]["artisanId"] == "fr1"
    # Undone: the link moved back to frOld, and the designer portal now knows fr1.
    world.artisanmerge.rows[0]["undoneAt"] = datetime.now(UTC)
    world.artisanmerge.rows[0]["status"] = "UNDONE"
    artisan_merge.forget_redirects()
    world.crossportalartisanlink.rows[0]["artisanId"] = "frOld"
    world.artisan.rows.append({"id": "frOld", "name": "Meena", "pehchanCardNumber": None, "aadhaarFingerprint": None, "status": "APPROVED"})
    world.artisansharingconsent.rows.append({"artisanId": "frOld", "granted": True})
    response = await routes.peer_refresh("fr1", "dp9", _req("/api/cross-portal/peer/artisans/fr1", "GET"), _settings())
    assert response.status_code == 200
    assert _body(response)["match"]["artisanId"] == "frOld"
    # A link nobody joins to the path id is still refused.
    response = await routes.peer_refresh("fr2", "dp9", _req("/api/cross-portal/peer/artisans/fr2", "GET"), _settings())
    assert response.status_code == 404


async def test_link_and_unlink_resolve_a_merged_id(world):
    world.artisanmerge.rows.append({"id": "m1", "survivorId": "fr1", "mergedId": "frOld", "status": "DONE", "undoneAt": None})
    payload = routes.PeerLink(remoteArtisanId="dp7", matchedBy="PEHCHAN")
    response = await routes.peer_link("frOld", payload, _req("/api/cross-portal/peer/artisans/frOld/links"), _settings())
    assert response.status_code == 200
    assert [r["artisanId"] for r in world.crossportalartisanlink.rows] == ["fr1"]
    await routes.peer_unlink("frOld", "dp7", _req("/api/cross-portal/peer/artisans/frOld/links/dp7", "DELETE"), _settings())
    assert world.crossportalartisanlink.rows == []


def test_the_merge_keeps_the_more_recent_consent_decision():
    older = SimpleNamespace(artisanId="a", granted=True, note=None, recordedById=None,
                            recordedAt=datetime(2026, 3, 1, tzinfo=UTC), updatedAt=datetime(2026, 3, 1, tzinfo=UTC))
    newer = SimpleNamespace(artisanId="b", granted=False, note=None, recordedById=None,
                            recordedAt=datetime(2026, 5, 1, tzinfo=UTC), updatedAt=datetime(2026, 5, 1, tzinfo=UTC))
    took = artisan_merge.consent_plan(newer, older)
    assert took["action"] == "took" and took["grantedAfter"] is False
    kept = artisan_merge.consent_plan(older, newer)
    assert kept["action"] == "kept" and kept["grantedAfter"] is False
    assert artisan_merge.consent_plan(older, None)["action"] == "moved"
    assert artisan_merge.consent_plan(None, older) == {
        "action": "none", "merged": None, "survivor": artisan_merge.snapshot(older), "grantedAfter": True,
    }
