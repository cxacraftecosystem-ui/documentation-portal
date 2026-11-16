from __future__ import annotations

import hashlib
import hmac
import json
import uuid
from datetime import UTC, datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import HTTPException
from search_support import needs_search_db
from starlette.requests import Request

from app.api.routes import cross_portal_crafts as routes
from app.core.config import Settings
from app.core.db import db
from app.services import (
    craft_feed,
    craft_master,
    cross_portal,
    cross_portal_codes,
    cross_portal_peer,
)

pytestmark = pytest.mark.anyio

EXPECTED_PORTAL = "FIELD_REPOSITORY"

TOKEN = "f" * 40
NONCE = "c" * 32
API_BASE = "http://portal.test/api"
ROUTE_PATH = "/api/cross-portal/peer/crafts"
SIGNED_PATH = "/cross-portal/peer/crafts"
REQUESTED_BY = "kalavigyan-craft-sync"

KALAVIGYAN_TOKEN = "kalavigyan-test-token-" + "x" * 20
KALAVIGYAN_NONCE = "0123456789abcdef0123456789abcdef"
KALAVIGYAN_SIGNATURE = "23cad0533be57bffb503168b57ca582581991c4929bec2785a60b9c80d12bdb6"

CRAFT_FIELDS = {
    "id",
    "slug",
    "name",
    "localName",
    "subCategoryId",
    "stateOfOrigin",
    "stateOfPractice",
    "giStatus",
    "isCatchAll",
    "craftCode",
    "status",
    "mergedIntoId",
    "aliases",
    "updatedAt",
}
CRAFT_STATUSES = {"ACTIVE", "LEGACY", "MERGED"}
GI_STATUSES = {"GI", "NON_GI", None}
ALIASES = ["Ajrak", "अजरख", "AJRAKH Print (Kutch)"]


def kalavigyan_signature(token: str, body: bytes, nonce: str) -> str:
    head = "\n".join(("GET", SIGNED_PATH, nonce, "")).encode("utf-8")
    return hmac.new(token.strip().encode("utf-8"), head + body, hashlib.sha256).hexdigest()


def _zoned(value: Any) -> None:
    assert isinstance(value, str), value
    assert datetime.fromisoformat(value).tzinfo is not None, value


def _unique(what: str, values: list[str]) -> None:
    seen: set[str] = set()
    for value in values:
        assert value not in seen, f"{what} {value!r} appears twice"
        seen.add(value)


def assert_kalavigyan_accepts(feed: dict[str, Any], portal: str) -> None:
    assert feed["schemaVersion"] == 1
    assert feed["portal"] == portal
    _zoned(feed["generatedAt"])
    subs = [sub for category in feed["categories"] for sub in category["subCategories"]]
    _unique("category slug", [category["slug"] for category in feed["categories"]])
    _unique("sub-category id", [sub["id"] for sub in subs])
    _unique("sub-category slug", [sub["slug"] for sub in subs])
    _unique("craft id", [craft["id"] for craft in feed["crafts"]])
    _unique("craft slug", [craft["slug"] for craft in feed["crafts"]])
    _unique("code", [code["code"] for code in feed["codes"]])
    sub_ids = {sub["id"] for sub in subs}
    craft_ids = {craft["id"] for craft in feed["crafts"]}
    codes = {code["code"] for code in feed["codes"]}
    for craft in feed["crafts"]:
        assert craft["status"] in CRAFT_STATUSES, craft
        assert (craft["status"] == "MERGED") == (craft["mergedIntoId"] is not None), craft
        assert craft["mergedIntoId"] != craft["id"], craft
        assert craft["subCategoryId"] is None or craft["subCategoryId"] in sub_ids, craft
        assert craft["mergedIntoId"] is None or craft["mergedIntoId"] in craft_ids, craft
        assert craft["craftCode"] is None or craft["craftCode"] in codes, craft
        assert craft["giStatus"] in GI_STATUSES, craft
        assert isinstance(craft["isCatchAll"], bool), craft
        assert all(isinstance(alias, str) for alias in craft["aliases"]), craft
        if craft["updatedAt"] is not None:
            _zoned(craft["updatedAt"])
    for spelling in feed["spellings"]:
        assert spelling["craftCode"] in codes, spelling
        assert spelling["source"], spelling


def assert_fits_the_reader(craft: dict[str, Any]) -> None:
    assert set(craft) == CRAFT_FIELDS
    assert 1 <= len(craft["id"]) <= 64
    assert 1 <= len(craft["slug"]) <= 200
    assert 1 <= len(craft["name"]) <= 300
    assert craft["localName"] is None or len(craft["localName"]) <= 300
    assert craft["craftCode"] is None or len(craft["craftCode"]) <= 40
    assert all(len(alias) <= 300 for alias in craft["aliases"])
    assert isinstance(craft["stateOfOrigin"], list) and isinstance(craft["stateOfPractice"], list)


def _settings(**over: Any) -> SimpleNamespace:
    base = {
        "cross_portal_craft_feed_enabled": True,
        "cross_portal_token": TOKEN,
        "cross_portal_identity_enabled": False,
        "cross_portal_search_enabled": False,
        "aadhaar_fingerprint_key": None,
        "field_repository_api_url": None,
        "designer_portal_api_url": None,
    }
    base.update(over)
    return SimpleNamespace(**base)


def _request(path: str = ROUTE_PATH) -> Request:
    scope = {
        "type": "http",
        "method": "GET",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "headers": [],
        "server": ("testserver", 80),
        "scheme": "http",
    }
    return Request(scope)


class _Rows:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = list(rows)

    async def find_many(self, **_kw: Any) -> list[Any]:
        return list(self.rows)

    async def group_by(self, **_kw: Any) -> list[Any]:
        return []


WHEN = datetime(2026, 11, 10, 8, 0, tzinfo=UTC)


def _craft(craft_id: str, slug: str, **over: Any) -> SimpleNamespace:
    base = {
        "id": craft_id,
        "slug": slug,
        "name": slug.replace("-", " "),
        "localName": None,
        "subCategoryId": "sub-1",
        "stateOfOrigin": ["Gujarat"],
        "stateOfPractice": ["Gujarat"],
        "giStatus": "GI",
        "isCatchAll": False,
        "craftCode": "ajrakh",
        "status": "ACTIVE",
        "mergedIntoId": None,
        "extraMetadata": None,
        "sourceSerial": None,
        "updatedAt": WHEN,
    }
    base.update(over)
    return SimpleNamespace(**base)


def _fake_client(crafts: list[Any], spellings: list[Any] | None = None) -> SimpleNamespace:
    category = SimpleNamespace(
        id="cat-1",
        slug="Textiles-Based-Craft-Category",
        name="Textiles based Craft Category",
        sortOrder=1,
        subCategories=[
            SimpleNamespace(
                id="sub-1", slug="Hand-Block-Printing", name="Hand Block Printing", sortOrder=3
            )
        ],
    )
    codes = [SimpleNamespace(code="ajrakh", name="Ajrakh")]
    return SimpleNamespace(
        craft=_Rows(crafts),
        craftcategory=_Rows([category]),
        craftcode=_Rows(codes),
        craftspelling=_Rows(spellings or []),
    )


def _by_slug(feed: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {craft["slug"]: craft for craft in feed["crafts"]}


async def test_this_portal_names_itself():
    assert routes.PORTAL == EXPECTED_PORTAL
    assert craft_feed.PORTALS == ("DESIGNER", "FIELD_REPOSITORY")


async def test_the_switch_is_off_by_default():
    field = Settings.model_fields["cross_portal_craft_feed_enabled"]
    assert field.default is False
    assert field.alias == "CROSS_PORTAL_CRAFT_FEED_ENABLED"


async def test_the_gate_needs_the_switch_and_a_long_token_and_nothing_else():
    assert cross_portal.craft_feed_active(_settings()) is True
    assert cross_portal.craft_feed_active(_settings(cross_portal_craft_feed_enabled=False)) is False
    assert cross_portal.craft_feed_active(_settings(cross_portal_token="t" * 31)) is False
    assert cross_portal.craft_feed_active(_settings(cross_portal_token=None)) is False
    assert (
        cross_portal.craft_feed_active(_settings(cross_portal_token="  " + "t" * 31 + "  "))
        is False
    )
    assert cross_portal.active(_settings()) is False
    assert cross_portal.search_active(_settings()) is False


async def test_the_identity_or_search_switch_does_not_open_the_feed():
    on_elsewhere = _settings(
        cross_portal_craft_feed_enabled=False,
        cross_portal_identity_enabled=True,
        cross_portal_search_enabled=True,
        aadhaar_fingerprint_key="k" * 40,
    )
    assert cross_portal.craft_feed_active(on_elsewhere) is False


async def test_off_is_404_and_a_bad_bearer_or_nonce_is_401(monkeypatch):
    monkeypatch.setattr(
        cross_portal_peer, "get_settings", lambda: _settings(cross_portal_craft_feed_enabled=False)
    )
    with pytest.raises(HTTPException) as off:
        await cross_portal_peer.require_craft_feed_peer(_request(), f"Bearer {TOKEN}", NONCE)
    assert (off.value.status_code, off.value.detail) == (404, "Not Found")

    monkeypatch.setattr(cross_portal_peer, "get_settings", _settings)
    for authorization, nonce in (
        (None, NONCE),
        ("Bearer " + "x" * 40, NONCE),
        (TOKEN, NONCE),
        (f"Bearer {TOKEN}", None),
        (f"Bearer {TOKEN}", "n" * 15),
        (f"Bearer {TOKEN}", "n" * 129),
    ):
        with pytest.raises(HTTPException) as refused:
            await cross_portal_peer.require_craft_feed_peer(_request(), authorization, nonce)
        assert (refused.value.status_code, refused.value.detail) == (401, "Not authorised.")

    request = _request()
    admitted = await cross_portal_peer.require_craft_feed_peer(request, f"Bearer {TOKEN}", NONCE)
    assert admitted.cross_portal_token == TOKEN
    assert request.state.cross_portal_nonce == NONCE


async def test_the_signature_matches_kalavigyans_own_vector():
    assert cross_portal.peer_path(ROUTE_PATH) == SIGNED_PATH
    signature = cross_portal.sign(
        KALAVIGYAN_TOKEN,
        b'{"schemaVersion":1}',
        method="GET",
        path=ROUTE_PATH,
        nonce=KALAVIGYAN_NONCE,
    )
    assert signature == KALAVIGYAN_SIGNATURE
    assert (
        kalavigyan_signature(KALAVIGYAN_TOKEN, b'{"schemaVersion":1}', KALAVIGYAN_NONCE)
        == signature
    )


async def test_the_route_is_published_as_a_get():
    from app.main import app as application

    paths = application.openapi()["paths"]
    assert set(paths[ROUTE_PATH]) == {"get"}


async def test_the_feed_has_the_contract_shape():
    client = _fake_client(
        [_craft("c1", "Kutch-Ajrakh", localName="अजरख", extraMetadata={"aliases": ALIASES})],
        [SimpleNamespace(spelling="ajrak", label="Ajrak", craftCode="ajrakh", source="SHARED")],
    )
    feed = await craft_feed.build(client, EXPECTED_PORTAL)
    assert set(feed) == {
        "schemaVersion",
        "portal",
        "generatedAt",
        "categories",
        "crafts",
        "codes",
        "spellings",
    }
    assert feed["categories"] == [
        {
            "id": "cat-1",
            "slug": "Textiles-Based-Craft-Category",
            "name": "Textiles based Craft Category",
            "sortOrder": 1,
            "subCategories": [
                {
                    "id": "sub-1",
                    "slug": "Hand-Block-Printing",
                    "name": "Hand Block Printing",
                    "sortOrder": 3,
                }
            ],
        }
    ]
    assert feed["crafts"] == [
        {
            "id": "c1",
            "slug": "Kutch-Ajrakh",
            "name": "Kutch Ajrakh",
            "localName": "अजरख",
            "subCategoryId": "sub-1",
            "stateOfOrigin": ["Gujarat"],
            "stateOfPractice": ["Gujarat"],
            "giStatus": "GI",
            "isCatchAll": False,
            "craftCode": "ajrakh",
            "status": "ACTIVE",
            "mergedIntoId": None,
            "aliases": ALIASES,
            "updatedAt": "2026-11-10T08:00:00Z",
        }
    ]
    assert feed["codes"] == [{"code": "ajrakh", "name": "Ajrakh"}]
    assert feed["spellings"] == [
        {"spelling": "ajrak", "label": "Ajrak", "craftCode": "ajrakh", "source": "SHARED"}
    ]
    assert_kalavigyan_accepts(feed, EXPECTED_PORTAL)
    assert_fits_the_reader(feed["crafts"][0])


async def test_every_status_is_sent_and_a_merge_only_names_a_target_in_the_feed():
    client = _fake_client(
        [
            _craft("c1", "Dabu-Print"),
            _craft("c2", "Dabu-Printing", status="MERGED", mergedIntoId="c1"),
            _craft("c3", "Old-Dabu", status="LEGACY", subCategoryId=None),
            _craft("c4", "Target-Deleted", status="MERGED", mergedIntoId=None),
            _craft("c5", "Into-Itself", status="MERGED", mergedIntoId="c5"),
            _craft("c6", "Into-Nowhere", status="MERGED", mergedIntoId="gone"),
            _craft("c7", "Stray-Target", mergedIntoId="c1"),
        ]
    )
    feed = await craft_feed.build(client, EXPECTED_PORTAL)
    crafts = _by_slug(feed)
    assert (crafts["Dabu-Printing"]["status"], crafts["Dabu-Printing"]["mergedIntoId"]) == (
        "MERGED",
        "c1",
    )
    assert (crafts["Old-Dabu"]["status"], crafts["Old-Dabu"]["subCategoryId"]) == ("LEGACY", None)
    for slug in ("Target-Deleted", "Into-Itself", "Into-Nowhere"):
        assert (crafts[slug]["status"], crafts[slug]["mergedIntoId"]) == ("LEGACY", None), slug
    assert (crafts["Stray-Target"]["status"], crafts["Stray-Target"]["mergedIntoId"]) == (
        "ACTIVE",
        None,
    )
    assert [craft["status"] for craft in feed["crafts"]][:2] == ["ACTIVE", "ACTIVE"]
    assert_kalavigyan_accepts(feed, EXPECTED_PORTAL)


async def test_a_reference_outside_the_feed_is_sent_as_null():
    client = _fake_client(
        [_craft("c1", "Moved", subCategoryId="sub-gone", craftCode="code-gone")],
        [
            SimpleNamespace(spelling="ajrak", label="Ajrak", craftCode="ajrakh", source="SHARED"),
            SimpleNamespace(spelling="gone", label="Gone", craftCode="code-gone", source="ADMIN"),
        ],
    )
    feed = await craft_feed.build(client, EXPECTED_PORTAL)
    assert (feed["crafts"][0]["subCategoryId"], feed["crafts"][0]["craftCode"]) == (None, None)
    assert [spelling["spelling"] for spelling in feed["spellings"]] == ["ajrak"]
    assert_kalavigyan_accepts(feed, EXPECTED_PORTAL)


async def test_aliases_are_the_stored_strings_not_normalised():
    stored = [*ALIASES, "", "   "]
    client = _fake_client(
        [
            _craft("c1", "As-Dict", extraMetadata={"aliases": stored}),
            _craft("c2", "As-Text", extraMetadata=json.dumps({"aliases": stored})),
            _craft("c3", "None-Kept", extraMetadata={"other": 1}),
        ]
    )
    crafts = _by_slug(await craft_feed.build(client, EXPECTED_PORTAL))
    assert crafts["As-Dict"]["aliases"] == ALIASES
    assert crafts["As-Text"]["aliases"] == ALIASES
    assert crafts["None-Kept"]["aliases"] == []
    assert [cross_portal_codes.normalise(alias) for alias in ALIASES] != ALIASES


async def test_every_time_carries_a_zone():
    ist = timezone(timedelta(hours=5, minutes=30))
    assert craft_feed.stamp(WHEN.replace(tzinfo=None)) == "2026-11-10T08:00:00Z"
    assert craft_feed.stamp(datetime(2026, 11, 10, 13, 30, tzinfo=ist)) == "2026-11-10T08:00:00Z"
    assert craft_feed.stamp(None) is None
    naive = _craft("c1", "Naive", updatedAt=WHEN.replace(tzinfo=None))
    feed = await craft_feed.build(_fake_client([naive]), EXPECTED_PORTAL)
    assert feed["crafts"][0]["updatedAt"] == "2026-11-10T08:00:00Z"
    _zoned(feed["generatedAt"])


async def test_an_unknown_portal_is_refused():
    with pytest.raises(ValueError, match="unknown portal"):
        await craft_feed.build(_fake_client([]), "SOMEWHERE")


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    await db.connect()
    stamp = uuid.uuid4().hex[:10]
    try:
        yield await _seed(stamp)
    finally:
        await _cleanup(stamp)
        await db.disconnect()


async def _seed(stamp: str) -> SimpleNamespace:
    category = await db.craftcategory.create(
        data={"slug": f"Feed-Category-{stamp}", "name": f"Feed Category {stamp}", "sortOrder": 900}
    )
    sub = await db.craftsubcategory.create(
        data={
            "slug": f"Feed-Sub-{stamp}",
            "name": f"Feed Sub {stamp}",
            "categoryId": category.id,
            "sortOrder": 1,
        }
    )
    code = await db.craftcode.create(data={"code": f"feed{stamp}", "name": f"Feed Code {stamp}"})
    spelling = await db.craftspelling.create(
        data={
            "spelling": f"feed spelling {stamp}",
            "label": f"Feed Spelling {stamp}",
            "craftCode": code.code,
            "source": "ADMIN",
        }
    )
    target = await db.craft.create(
        data={
            "name": f"Feed Target {stamp}",
            "slug": f"Feed-Target-{stamp}",
            "localName": "अजरख",
            "subCategoryId": sub.id,
            "craftCode": code.code,
            "giStatus": "GI",
            "stateOfOrigin": ["Gujarat"],
            "stateOfPractice": ["Gujarat", "Rajasthan"],
            "status": "ACTIVE",
            "extraMetadata": craft_master.as_json({"aliases": ALIASES}),
        }
    )
    merged = await db.craft.create(
        data={
            "name": f"Feed Merged {stamp}",
            "slug": f"Feed-Merged-{stamp}",
            "subCategoryId": sub.id,
            "giStatus": "NON_GI",
            "status": "MERGED",
            "mergedIntoId": target.id,
        }
    )
    legacy = await db.craft.create(
        data={"name": f"Feed Legacy {stamp}", "slug": f"Feed-Legacy-{stamp}", "status": "LEGACY"}
    )
    orphan = await db.craft.create(
        data={"name": f"Feed Orphan {stamp}", "slug": f"Feed-Orphan-{stamp}", "status": "MERGED"}
    )
    return SimpleNamespace(
        stamp=stamp,
        category=category,
        sub=sub,
        code=code,
        spelling=spelling,
        target=target,
        merged=merged,
        legacy=legacy,
        orphan=orphan,
    )


async def _cleanup(stamp: str) -> None:
    mine = {"slug": {"contains": stamp}}
    await db.craft.update_many(where=mine, data={"mergedIntoId": None})
    await db.craft.delete_many(where=mine)
    await db.craftspelling.delete_many(where={"craftCode": f"feed{stamp}"})
    await db.craftcode.delete_many(where={"code": f"feed{stamp}"})
    await db.craftsubcategory.delete_many(where=mine)
    await db.craftcategory.delete_many(where=mine)


async def _get(
    monkeypatch,
    *,
    settings: SimpleNamespace | None = None,
    token: str | None = TOKEN,
    nonce: str | None = NONCE,
) -> httpx.Response:
    from app.main import app as application

    chosen = settings or _settings()
    monkeypatch.setattr(cross_portal_peer, "get_settings", lambda: chosen)
    headers = {"Accept": "application/json", cross_portal.REQUESTED_BY_HEADER: REQUESTED_BY}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if nonce is not None:
        headers[cross_portal.NONCE_HEADER] = nonce
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport) as client:
        return await client.get(f"{API_BASE}{SIGNED_PATH}", headers=headers)


@needs_search_db
async def test_off_answers_404_through_the_app(world, monkeypatch):
    response = await _get(monkeypatch, settings=_settings(cross_portal_craft_feed_enabled=False))
    assert response.status_code == 404
    assert response.json() == {"detail": "Not Found"}
    short = await _get(monkeypatch, settings=_settings(cross_portal_token="t" * 31), token="t" * 31)
    assert short.status_code == 404


@needs_search_db
async def test_a_missing_or_wrong_bearer_or_nonce_is_401_through_the_app(world, monkeypatch):
    for token, nonce in ((None, NONCE), ("x" * 40, NONCE), (TOKEN, None), (TOKEN, "short")):
        response = await _get(monkeypatch, token=token, nonce=nonce)
        assert response.status_code == 401, (token, nonce)
        assert response.json() == {"detail": "Not authorised."}
        assert cross_portal.SIGNATURE_HEADER not in response.headers


@needs_search_db
async def test_the_answer_is_signed_the_way_kalavigyan_checks_it(world, monkeypatch):
    response = await _get(monkeypatch)
    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("application/json")
    expected = kalavigyan_signature(TOKEN, response.content, NONCE)
    assert hmac.compare_digest(expected, response.headers[cross_portal.SIGNATURE_HEADER])
    assert response.headers[cross_portal.SIGNATURE_HEADER] != kalavigyan_signature(
        TOKEN, response.content, "d" * 32
    )


@needs_search_db
async def test_the_feed_needs_neither_the_identity_switch_nor_the_aadhaar_key(world, monkeypatch):
    response = await _get(
        monkeypatch,
        settings=_settings(cross_portal_identity_enabled=False, aadhaar_fingerprint_key=None),
    )
    assert response.status_code == 200, response.text


@needs_search_db
async def test_the_whole_feed_is_one_kalavigyan_accepts(world, monkeypatch):
    feed = json.loads((await _get(monkeypatch)).content)
    assert_kalavigyan_accepts(feed, EXPECTED_PORTAL)
    crafts = _by_slug(feed)
    for row in (world.target, world.merged, world.legacy, world.orphan):
        assert_fits_the_reader(crafts[row.slug])
    assert len(feed["crafts"]) == await db.craft.count()


@needs_search_db
async def test_every_status_is_in_the_feed_and_a_merge_target_is_too(world, monkeypatch):
    crafts = _by_slug(json.loads((await _get(monkeypatch)).content))
    target = crafts[world.target.slug]
    merged = crafts[world.merged.slug]
    legacy = crafts[world.legacy.slug]
    assert (target["status"], target["mergedIntoId"]) == ("ACTIVE", None)
    assert (merged["status"], merged["mergedIntoId"]) == ("MERGED", world.target.id)
    assert merged["mergedIntoId"] in {craft["id"] for craft in crafts.values()}
    assert (legacy["status"], legacy["subCategoryId"]) == ("LEGACY", None)
    orphan = crafts[world.orphan.slug]
    assert (orphan["status"], orphan["mergedIntoId"]) == ("LEGACY", None)


@needs_search_db
async def test_a_craft_is_sent_with_its_own_columns_and_raw_aliases(world, monkeypatch):
    target = _by_slug(json.loads((await _get(monkeypatch)).content))[world.target.slug]
    assert target["id"] == world.target.id
    assert target["name"] == world.target.name
    assert target["localName"] == "अजरख"
    assert target["subCategoryId"] == world.sub.id
    assert target["craftCode"] == world.code.code
    assert target["giStatus"] == "GI"
    assert target["stateOfOrigin"] == ["Gujarat"]
    assert target["stateOfPractice"] == ["Gujarat", "Rajasthan"]
    assert target["isCatchAll"] is False
    assert target["aliases"] == ALIASES
    assert datetime.fromisoformat(target["updatedAt"]) == world.target.updatedAt


@needs_search_db
async def test_categories_codes_and_spellings_are_all_sent(world, monkeypatch):
    feed = json.loads((await _get(monkeypatch)).content)
    category = next(c for c in feed["categories"] if c["id"] == world.category.id)
    assert category == {
        "id": world.category.id,
        "slug": world.category.slug,
        "name": world.category.name,
        "sortOrder": 900,
        "subCategories": [
            {"id": world.sub.id, "slug": world.sub.slug, "name": world.sub.name, "sortOrder": 1}
        ],
    }
    assert len(feed["categories"]) == await db.craftcategory.count()
    assert len(feed["codes"]) == await db.craftcode.count()
    assert len(feed["spellings"]) == await db.craftspelling.count()
    assert {"code": world.code.code, "name": world.code.name} in feed["codes"]
    assert {
        "spelling": world.spelling.spelling,
        "label": world.spelling.label,
        "craftCode": world.code.code,
        "source": "ADMIN",
    } in feed["spellings"]


@needs_search_db
async def test_each_call_writes_one_access_log_row(world, monkeypatch):
    before = await db.crossportalaccesslog.count(where={"action": routes.ACTION_CRAFT_FEED})
    response = await _get(monkeypatch)
    assert response.status_code == 200
    assert (
        await db.crossportalaccesslog.count(where={"action": routes.ACTION_CRAFT_FEED})
        == before + 1
    )
    row = await db.crossportalaccesslog.find_first(
        where={"action": "CRAFT_FEED"}, order={"createdAt": "desc"}
    )
    assert (row.action, row.outcome, row.remoteUserId) == ("CRAFT_FEED", "OK", REQUESTED_BY)
    assert row.resultCount == len(json.loads(response.content)["crafts"])
    refused = await _get(monkeypatch, token="x" * 40)
    assert refused.status_code == 401
    assert await db.crossportalaccesslog.count(where={"action": "CRAFT_FEED"}) == before + 1
