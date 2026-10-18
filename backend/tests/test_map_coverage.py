"""The coverage map: the fold, the filters, the gaps, the unit records and the administrator's aliases.

No database: ``coverage.reader`` is handed a fake client holding a small corpus, and the routes are
called as functions with the dependencies they would have been given.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import map_coverage as routes
from app.core import deps
from app.services import access, address, coverage, lgd

pytestmark = pytest.mark.anyio

BARMER = lgd.district_lgd("Rajasthan", "Barmer")
JAIPUR = lgd.district_lgd("Rajasthan", "Jaipur")
KACHCHH = lgd.district_lgd("Gujarat", "Kachchh")
RAJASTHAN = lgd.state_lgd("Rajasthan")
GUJARAT = lgd.state_lgd("Gujarat")


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def ns(**kw: Any) -> SimpleNamespace:
    return SimpleNamespace(**kw)


def loc(state=None, district=None, lat=None, lon=None) -> SimpleNamespace:
    return ns(
        state=state,
        district=district,
        subjectLatitude=lat,
        subjectLongitude=lon,
        address="12 Secret Lane",
        village="Hidden",
        pincode="344001",
    )


AJRAKH = ns(id="cr1", name="Ajrakh", craftCode="ajrakh")
BLOCK = ns(id="cr2", name="Some Local Print", craftCode=None)
DPD = ns(workshopType="DESIGN_PROTOTYPE")


class Table:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = list(rows or [])
        self.created: list[dict[str, Any]] = []

    async def find_many(self, where=None, include=None, take=None, order=None):
        rows = self.rows
        if where and "source" in where:
            rows = [r for r in rows if getattr(r, "source", None) == where["source"]]
        return rows[:take] if take else rows

    async def find_unique(self, where):
        if "id" in where:
            return next((r for r in self.rows if r.id == where["id"]), None)
        key = where["rawStateKey_rawDistrictKey"]
        return next(
            (
                r
                for r in self.rows
                if r.rawStateKey == key["rawStateKey"] and r.rawDistrictKey == key["rawDistrictKey"]
            ),
            None,
        )

    async def create(self, data):
        row = ns(id=f"row{len(self.rows) + 1}", createdAt=datetime.now(UTC), **data)
        self.rows.append(row)
        self.created.append(data)
        return row

    async def delete(self, where):
        self.rows = [r for r in self.rows if r.id != where["id"]]


def corpus() -> SimpleNamespace:
    workshops = Table(
        [
            ns(
                id="w1",
                title="Ajrakh at Barmer",
                workshopType="DESIGN_PROTOTYPE",
                startDate=datetime(2026, 3, 31, 18, 29, tzinfo=UTC),  # 31 Mar 23:59 IST -> FY 2025
                date=datetime(2026, 3, 31, tzinfo=UTC),
                place="Barmer",
                location=loc("Rajasthan", "Barmer", 25.75, 71.39),
                crafts=[ns(craft=AJRAKH)],
            ),
            ns(
                id="w2",
                title="Kutch visit",
                workshopType="OTHER",
                startDate=datetime(2026, 3, 31, 18, 30, tzinfo=UTC),  # 1 Apr 00:00 IST -> FY 2026
                date=datetime(2026, 4, 1, tzinfo=UTC),
                place="Bhuj",
                location=loc("Gujarat", "Kutch"),
                crafts=[ns(craft=BLOCK)],
            ),
            ns(
                id="w3",
                title="Legacy prose workshop",
                workshopType="OTHER",
                startDate=None,
                date=datetime(2024, 6, 1, tzinfo=UTC),
                place="Somewhere Unmapped",
                location=None,
                crafts=[],
            ),
        ]
    )
    artisans = Table(
        [
            ns(
                id="a1",
                name="Meena Devi",
                aadhaarNumber="234123412346",
                pehchanCardNumber="PEH1",
                phone="9999999999",
                place="Barmer",
                createdAt=datetime(2025, 5, 1, tzinfo=UTC),
                craft=AJRAKH,
                workshop=DPD,
                location=loc("Rajasthan", "Barmer"),
            ),
            ns(
                id="a2",
                name="Ravi",
                aadhaarNumber=None,
                pehchanCardNumber=None,
                phone=None,
                place="Bagru",
                createdAt=datetime(2025, 5, 1, tzinfo=UTC),
                craft=None,
                workshop=None,
                location=loc("Rajasthan", "Kachchh"),  # a district of another state
            ),
        ]
    )
    products = Table(
        [
            ns(
                id="p1",
                productName="Stole",
                craftName="Ajrakh",
                place="Barmer",
                createdAt=datetime(2025, 7, 1, tzinfo=UTC),
                craft=None,
                workshop=DPD,
                location=loc("Rajasthan", "Barmer"),
            )
        ]
    )
    tools = Table([])
    media = Table(
        [
            ns(
                id="m1",
                originalFilename="IMG_1.jpg",
                createdAt=datetime(2025, 7, 1, tzinfo=UTC),
                craft=AJRAKH,
                workshop=DPD,
                location=loc("Atlantis", "Nowhere"),
            )
        ]
    )
    return ns(
        workshop=workshops,
        artisan=artisans,
        productdocumentation=products,
        tooldocumentation=tools,
        mediafile=media,
        geoalias=Table([]),
        craftspelling=Table([]),
        recordrevision=Table([]),
    )


@pytest.fixture
def world(monkeypatch):
    client = corpus()
    coverage.clear_cache()

    async def reader(run):
        return await run(client)

    monkeypatch.setattr(coverage, "reader", reader)
    monkeypatch.setattr(routes, "db", client)
    monkeypatch.setattr(access, "db", client)
    yield client
    coverage.clear_cache()


USER = ns(id="u1", role="VOLUNTEER")
ADMIN = ns(id="admin1", role="ADMIN")


async def _coverage(user=USER, **kw) -> dict[str, Any]:
    params = {"level": None, "craftCode": None, "scheme": None, "fy": None, "stateLgd": None}
    params.update(kw)
    return await routes.map_coverage(current_user=user, **params)


def _unit(body, district) -> dict[str, Any]:
    return next(u for u in body["units"] if u["districtLgd"] == district)


async def test_counts_per_unit_for_each_bucket(world):
    body = await _coverage()
    barmer = _unit(body, BARMER)
    assert barmer["counts"] == {
        "workshops": 1,
        "artisans": 1,
        "crafts": 1,
        "products": 1,
        "tools": 0,
        "media": 0,
    }
    assert barmer["craftCodes"] == ["ajrakh"]
    assert barmer["polygon"] == "OWN" and barmer["state"] == "Rajasthan" and barmer["district"] == "Barmer"
    kachchh = _unit(body, KACHCHH)
    assert kachchh["counts"]["workshops"] == 1 and kachchh["stateLgd"] == GUJARAT
    # The Rajasthan artisan with a Gujarat district lands at state level only.
    state_only = _unit(body, None)
    assert state_only["stateLgd"] == RAJASTHAN and state_only["counts"]["artisans"] == 1


async def test_units_are_sparse(world):
    body = await _coverage()
    assert len(body["units"]) == 3
    assert all(any(u["counts"].values()) for u in body["units"])


async def test_state_level_folds_districts_into_their_state(world):
    body = await _coverage(level="STATE")
    rajasthan = next(u for u in body["units"] if u["stateLgd"] == RAJASTHAN)
    assert rajasthan["districtLgd"] is None and rajasthan["polygon"] == "STATE"
    assert rajasthan["counts"]["artisans"] == 2 and rajasthan["counts"]["workshops"] == 1


async def test_craft_filter(world):
    body = await _coverage(craftCode=["ajrakh"])
    assert [u["districtLgd"] for u in body["units"]] == [BARMER]
    assert body["filters"]["craftCode"] == ["ajrakh"]
    unknown = await _coverage(craftCode=["not-a-code"])
    assert unknown["filters"]["craftCode"] == []


async def test_scheme_filter_reaches_records_through_their_workshop(world):
    body = await _coverage(scheme=["DPD"])
    barmer = _unit(body, BARMER)
    assert barmer["counts"]["workshops"] == 1 and barmer["counts"]["artisans"] == 1
    assert all(u["districtLgd"] != KACHCHH for u in body["units"])
    assert body["filters"]["scheme"] == ["DPD"]
    other = await _coverage(scheme=["OTHER"])
    assert _unit(other, KACHCHH)["counts"]["workshops"] == 1


async def test_fy_boundary_is_1_april_ist(world):
    assert coverage.fy_of(datetime(2026, 3, 31, 18, 29, tzinfo=UTC)) == 2025
    assert coverage.fy_of(datetime(2026, 3, 31, 18, 30, tzinfo=UTC)) == 2026
    fy25 = await _coverage(fy=["2025"])
    assert _unit(fy25, BARMER)["counts"]["workshops"] == 1
    assert all(u["districtLgd"] != KACHCHH for u in fy25["units"])
    fy26 = await _coverage(fy=["2026"])
    assert [u["districtLgd"] for u in fy26["units"]] == [KACHCHH]
    assert coverage.fy_label(2025) == "2025-26" and coverage.fy_label(2099) == "2099-00"


async def test_catalogue_lists_the_shared_crafts_the_schemes_and_the_years(world):
    body = await _coverage()
    assert len(body["catalogue"]["crafts"]) >= 40
    assert [s["value"] for s in body["catalogue"]["schemes"]] == ["DPD", "OTHER"]
    assert body["catalogue"]["years"][0] == {"fy": 2026, "label": "2026-27"}


async def test_gaps(world):
    body = await _coverage()
    never = {c["code"] for c in body["gaps"]["craftsNeverDocumented"]}
    assert "ajrakh" not in never
    assert len(never) == len(coverage.cross_portal_codes.catalogue()) - 1
    without = body["gaps"]["districtsWithoutWorkshop"]
    assert without["count"] == address.DISTRICT_COUNT - 2
    rajasthan = next(s for s in without["byState"] if s["stateLgd"] == RAJASTHAN)
    assert rajasthan["total"] == len(address.DISTRICTS_BY_STATE["Rajasthan"])
    assert all(d["lgd"] != BARMER for d in rajasthan["without"])
    states = {s["name"] for s in body["gaps"]["statesWithoutWorkshop"]}
    assert "Rajasthan" not in states and "Gujarat" not in states and len(states) == 34


async def test_gaps_narrow_to_one_state(world):
    body = await _coverage(stateLgd=RAJASTHAN)
    assert [s["stateLgd"] for s in body["gaps"]["districtsWithoutWorkshop"]["byState"]] == [RAJASTHAN]
    assert body["gaps"]["statesWithoutWorkshop"] == []
    with pytest.raises(HTTPException) as err:
        await _coverage(stateLgd=999)
    assert err.value.status_code == 422


async def test_markers_use_the_pin_then_the_district_centroid(world, monkeypatch):
    monkeypatch.setattr(coverage, "centroids", lambda: {KACHCHH: (69.6, 23.4)})
    body = await _coverage()
    by_id = {m["id"]: m for m in body["markers"]}
    assert by_id["w1"]["precision"] == "SUBJECT_PIN" and by_id["w1"]["lat"] == 25.75
    assert by_id["w2"]["precision"] == "DISTRICT_CENTROID" and by_id["w2"]["lon"] == 69.6
    assert by_id["w1"]["href"] == "/workshops/w1" and by_id["w1"]["kind"] == "WORKSHOP"
    assert "w3" not in by_id
    assert body["markersTruncated"] is False


async def test_truncation_flags(world, monkeypatch):
    monkeypatch.setattr(coverage, "ROW_CAP", 1)
    monkeypatch.setattr(coverage, "MARKER_CAP", 0)
    body = await _coverage()
    assert body["truncated"] is True
    assert body["markersTruncated"] is True and body["markers"] == []


async def test_unmatched_count_and_the_admin_sample(world):
    body = await _coverage()
    # Atlantis/Nowhere, the Rajasthan+Kachchh pair, and the legacy workshop's prose place.
    assert body["unmatched"] == {"count": 3}
    admin = await _coverage(user=ADMIN)
    sample = admin["unmatched"]["sample"]
    assert {(i["model"], i["rawState"], i["rawDistrict"]) for i in sample} == {
        ("Location", "Atlantis", "Nowhere"),
        ("Location", "Rajasthan", "Kachchh"),
        ("Workshop", "Somewhere Unmapped", None),
    }
    pair = next(i for i in sample if i["rawDistrict"] == "Kachchh")
    assert pair["suggestion"]["stateLgd"] == GUJARAT and pair["suggestion"]["districtLgd"] == KACHCHH


async def test_unit_records_page_with_links_and_names_only(world):
    body = await routes.coverage_unit_records(
        RAJASTHAN, BARMER, current_user=USER, level=None, craftCode=None, scheme=None, fy=None,
        bucket=None, cursor=None,
    )
    assert body["unit"]["district"] == "Barmer"
    assert body["buckets"]["artisans"]["items"] == [
        {"id": "a1", "title": "Meena Devi", "href": "/artisans/a1", "craftCode": "ajrakh", "fy": 2025}
    ]
    assert body["buckets"]["products"]["items"][0]["craftCode"] == "ajrakh"
    assert body["peerHref"] is None
    state_only = await routes.coverage_unit_records(
        RAJASTHAN, 0, current_user=USER, level=None, craftCode=None, scheme=None, fy=None,
        bucket="artisans", cursor=None,
    )
    assert [i["id"] for i in state_only["buckets"]["artisans"]["items"]] == ["a2"]
    with pytest.raises(HTTPException):
        await routes.coverage_unit_records(
            RAJASTHAN, KACHCHH, current_user=USER, level=None, craftCode=None, scheme=None,
            fy=None, bucket=None, cursor=None,
        )


async def test_unit_records_cursor(world, monkeypatch):
    monkeypatch.setattr(coverage, "PAGE_SIZE", 1)
    first = await routes.coverage_unit_records(
        RAJASTHAN, 0, current_user=USER, level="STATE", craftCode=None, scheme=None, fy=None,
        bucket="artisans", cursor=None,
    )
    assert first["buckets"]["artisans"]["total"] == 2 and first["buckets"]["artisans"]["nextCursor"] == "1"
    second = await routes.coverage_unit_records(
        RAJASTHAN, 0, current_user=USER, level="STATE", craftCode=None, scheme=None, fy=None,
        bucket="artisans", cursor="1",
    )
    assert second["buckets"]["artisans"]["nextCursor"] is None
    assert first["buckets"]["artisans"]["items"] != second["buckets"]["artisans"]["items"]


def _keys(value: Any) -> set[str]:
    if isinstance(value, dict):
        return set(value) | {k for v in value.values() for k in _keys(v)}
    if isinstance(value, list):
        return {k for v in value for k in _keys(v)}
    return set()


async def test_no_identity_or_address_field_ever_leaves(world):
    body = await _coverage(user=ADMIN)
    records = await routes.coverage_unit_records(
        RAJASTHAN, BARMER, current_user=ADMIN, level=None, craftCode=None, scheme=None, fy=None,
        bucket=None, cursor=None,
    )
    text = json.dumps([body, records], default=str)
    for prefix in coverage.FORBIDDEN_KEY_PREFIXES:
        assert not [k for k in _keys(json.loads(text)) if k.lower().startswith(prefix)], prefix
    assert "234123412346" not in text and "PEH1" not in text and "9999999999" not in text
    assert "Secret Lane" not in text and "344001" not in text


async def test_the_fold_is_cached_per_scope(world):
    await _coverage()
    world.artisan.rows.clear()
    again = await _coverage()
    assert _unit(again, BARMER)["counts"]["artisans"] == 1
    coverage.clear_cache()
    fresh = await _coverage()
    assert _unit(fresh, BARMER)["counts"]["artisans"] == 0


# ── The administrator's side ───────────────────────────────────────────────────────────────────


async def test_unmatched_and_aliases_are_admin_only():
    with pytest.raises(HTTPException) as err:
        await deps.require_admin(USER)
    assert err.value.status_code == 403
    assert await deps.require_admin(ADMIN) is ADMIN


async def test_the_unmatched_report(world):
    body = await routes.coverage_unmatched(current_user=ADMIN)
    assert body["total"] == 3
    assert {i["rows"] for i in body["items"]} == {1}


async def test_an_alias_moves_the_rows_and_is_audited(world):
    created = await routes.create_alias(
        routes.AliasIn(rawState="Atlantis", rawDistrict="Nowhere", stateLgd=RAJASTHAN, districtLgd=JAIPUR),
        current_user=ADMIN,
    )
    assert created["state"] == "Rajasthan" and created["district"] == "Jaipur"
    assert world.geoalias.created[0]["rawStateKey"] == "atlantis"
    revision = world.recordrevision.created[0]
    assert revision["recordType"] == "geoalias" and revision["editedById"] == "admin1"
    body = await _coverage()
    assert _unit(body, JAIPUR)["counts"]["media"] == 1
    assert body["unmatched"]["count"] == 2

    with pytest.raises(HTTPException) as err:
        await routes.create_alias(
            routes.AliasIn(rawState="atlantis", rawDistrict="NOWHERE", stateLgd=RAJASTHAN, districtLgd=None),
            current_user=ADMIN,
        )
    assert err.value.status_code == 409

    response = await routes.delete_alias(created["id"], current_user=ADMIN)
    assert response.status_code == 204
    assert len(world.recordrevision.created) == 2
    assert (await _coverage())["unmatched"]["count"] == 3


async def test_an_alias_to_a_district_of_another_state_is_refused(world):
    with pytest.raises(HTTPException) as err:
        await routes.create_alias(
            routes.AliasIn(rawState="Kutchh", rawDistrict="", stateLgd=RAJASTHAN, districtLgd=KACHCHH),
            current_user=ADMIN,
        )
    assert err.value.status_code == 422
    assert world.geoalias.rows == [] and world.recordrevision.created == []
