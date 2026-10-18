"""The coverage feed the designer portal reads: off by default, token-gated, signed, logged, aggregate.

No database: the route module's ``db`` and ``get_settings`` are replaced by fakes, ``coverage.reader``
is handed a fake client, and the routes are called as functions.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import cross_portal as routes
from app.services import coverage, cross_portal, lgd

pytestmark = pytest.mark.anyio

TOKEN = "t" * 40
NONCE = "n" * 32
PATH = "/api/cross-portal/peer/coverage"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def ns(**kw: Any) -> SimpleNamespace:
    return SimpleNamespace(**kw)


class Rows:
    def __init__(self, rows=None):
        self.rows = list(rows or [])
        self.created: list[dict[str, Any]] = []

    async def find_many(self, where=None, include=None, take=None, order=None):
        return self.rows[:take] if take else self.rows

    async def create(self, data):
        self.created.append(data)
        return ns(id=f"r{len(self.created)}", **data)


def _client():
    location = ns(state="Rajasthan", district="Barmer", subjectLatitude=None, subjectLongitude=None)
    craft = ns(id="c1", name="Ajrakh", craftCode="ajrakh")
    return ns(
        workshop=Rows(
            [
                ns(
                    id="w1",
                    title="Barmer workshop",
                    workshopType="OTHER",
                    startDate=datetime(2025, 6, 1, tzinfo=UTC),
                    date=datetime(2025, 6, 1, tzinfo=UTC),
                    place="Barmer",
                    location=location,
                    crafts=[ns(craft=craft)],
                )
            ]
        ),
        artisan=Rows(
            [
                ns(
                    id="a1",
                    name="Meena Devi",
                    aadhaarNumber="234123412346",
                    pehchanCardNumber="PEH1",
                    phone="9999999999",
                    place="Barmer",
                    createdAt=datetime(2025, 6, 1, tzinfo=UTC),
                    craft=craft,
                    workshop=None,
                    location=location,
                )
            ]
        ),
        productdocumentation=Rows([]),
        tooldocumentation=Rows([]),
        mediafile=Rows([]),
        geoalias=Rows([]),
        craftspelling=Rows([]),
        crossportalaccesslog=Rows([]),
    )


def _settings(**over):
    base = {"cross_portal_coverage_enabled": True, "cross_portal_token": TOKEN}
    base.update(over)
    return ns(**base)


def _req(asked_by="dp-u1"):
    return ns(
        method="GET",
        url=ns(path=PATH),
        state=ns(cross_portal_nonce=None),
        headers={cross_portal.REQUESTED_BY_HEADER: asked_by},
    )


@pytest.fixture
def world(monkeypatch):
    client = _client()
    coverage.clear_cache()

    async def reader(run):
        return await run(client)

    monkeypatch.setattr(coverage, "reader", reader)
    monkeypatch.setattr(routes, "db", client)
    monkeypatch.setattr(routes, "get_settings", lambda: _settings())
    yield client
    coverage.clear_cache()


async def test_the_feed_is_off_until_its_flag_and_token_are_set(monkeypatch, world):
    for settings in (
        _settings(cross_portal_coverage_enabled=False),
        _settings(cross_portal_token=None),
        _settings(cross_portal_token="short"),
    ):
        monkeypatch.setattr(routes, "get_settings", lambda s=settings: s)
        with pytest.raises(HTTPException) as err:
            await routes.require_coverage_peer(_req(), f"Bearer {TOKEN}", NONCE)
        assert err.value.status_code == 404


async def test_the_feed_does_not_need_the_identity_flag(world):
    settings = await routes.require_coverage_peer(_req(), f"Bearer {TOKEN}", NONCE)
    assert not getattr(settings, "cross_portal_identity_enabled", False)


async def test_401_without_the_bearer_token_or_a_nonce(world):
    for authorization, nonce in ((None, NONCE), ("Bearer wrong" + "x" * 40, NONCE), (f"Bearer {TOKEN}", "")):
        with pytest.raises(HTTPException) as err:
            await routes.require_coverage_peer(_req(), authorization, nonce)
        assert err.value.status_code == 401


async def _call(world, **params):
    request = _req()
    settings = await routes.require_coverage_peer(request, f"Bearer {TOKEN}", NONCE)
    query = {"craftCode": None, "fy": None, "scheme": None}
    query.update(params)
    return request, await routes.peer_coverage(request, settings=settings, **query)


async def test_the_body_is_signed_over_the_request(world):
    _, response = await _call(world)
    signature = response.headers[cross_portal.SIGNATURE_HEADER]
    assert cross_portal.signature_matches(
        TOKEN, response.body, signature, method="GET", path=PATH, nonce=NONCE
    )


async def test_every_request_is_logged_with_the_asking_account(world):
    await _call(world)
    assert world.crossportalaccesslog.created == [
        {
            "action": "COVERAGE",
            "outcome": "OK",
            "artisanId": None,
            "remoteUserId": "dp-u1",
            "remoteArtisanId": None,
            "matchedBy": None,
        }
    ]


async def test_the_payload_is_aggregate_counts_only(world):
    _, response = await _call(world)
    body = json.loads(response.body)
    assert set(body) == {"asOf", "listVersion", "units", "craftCodesDocumented", "craftCodesByState"}
    assert body["listVersion"] == lgd.register().list_version
    assert body["craftCodesDocumented"] == ["ajrakh"]
    assert body["craftCodesByState"] == {str(lgd.state_lgd("Rajasthan")): ["ajrakh"]}
    for unit in body["units"]:
        assert set(unit) == {"stateLgd", "districtLgd", "counts"}
        assert set(unit["counts"]) == {"workshops", "artisans", "products", "tools", "media"}
        assert all(isinstance(v, int) for v in unit["counts"].values())
    barmer = body["units"][0]
    assert barmer["districtLgd"] == lgd.district_lgd("Rajasthan", "Barmer")
    assert barmer["counts"]["workshops"] == 1 and barmer["counts"]["artisans"] == 1
    text = response.body.decode()
    for leaked in ("Meena", "Barmer workshop", "a1", "w1", "PEH1", "2341234"):
        assert leaked not in text


async def test_filters_apply_and_scheme_is_ignored(world):
    _, response = await _call(world, fy=["2024"], scheme=["DPD"])
    assert json.loads(response.body)["units"] == []
    _, response = await _call(world, craftCode=["ajrakh"], scheme=["ANYTHING"])
    assert len(json.loads(response.body)["units"]) == 1


async def test_this_portals_status_is_unchanged_by_the_coverage_flag(monkeypatch, world):
    # The toggle that asks for these figures lives on the designer portal and reads ITS status.
    body = await routes.cross_portal_status(None)
    assert "coverageEnabled" not in body
