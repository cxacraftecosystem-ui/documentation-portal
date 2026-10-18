"""``GET /search/unified`` on this portal's own index: ranking, grouping, paging and filters
(services/search_query, routes/search; docs/SEARCH.md).

Against a real database (opt-in, see ``search_support``), because the ranking IS Postgres:
full-text under ``english`` and ``simple``, trigram word similarity, reciprocal-rank fusion. The
route function is called directly with a settings stub, so no HTTP client and no second event loop
are involved. The last few tests need no database.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from search_support import index_world, needs_search_db, new_stamp, seed, user_ns

from app.api.routes import search as routes
from app.services import cross_portal, search_query
from app.services import unified_search as us

pytestmark = pytest.mark.anyio


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
        "unified_search_enabled": True,
        "search_embeddings_enabled": False,
        "search_embedding_provider": "openai",
        "search_embedding_model": "text-embedding-3-small",
        "openai_api_key": None,
        "gemini_api_keys": [],
        "cross_portal_search_enabled": False,
        "cross_portal_token": None,
        "cross_portal_aadhaar_hmac_key": None,
        "designer_portal_api_url": None,
    }
    base.update(over)
    return SimpleNamespace(**base)


@pytest.fixture
def settings(monkeypatch):
    s = _settings()
    monkeypatch.setattr(routes, "get_settings", lambda: s)
    return s


async def _search(user: Any, q: str, **kw: Any) -> dict[str, Any]:
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
    return await routes.unified_search(current_user=user_ns(user), q=q, **args)


def _ids(result: dict[str, Any], kind: str) -> list[str]:
    group = next((g for g in result["groups"] if g["type"] == kind), None)
    return [i["id"] for i in group["items"]] if group else []


def _scope(w, types: list[str]) -> search_query.Scope:
    return search_query.Scope(types=types, craft_ids=[w.craft.id])


@needs_search_db
async def test_an_exact_title_hit_ranks_first(world):
    result = await search_query.run("indigo stole", _scope(world, ["products"]), limit=5)
    ids = [i["id"] for i in result.items["products"]]
    assert ids[0] == world.product_title.id
    assert world.product_body.id in ids


@needs_search_db
async def test_a_typo_finds_the_word_through_trigram(world):
    result = await search_query.run("indgo", _scope(world, ["crafts", "artisans"]), limit=5)
    assert [i["id"] for i in result.items["crafts"]] == [world.craft.id]


@needs_search_db
async def test_a_devanagari_token_matches_through_simple(world):
    result = await search_query.run("नील", _scope(world, ["crafts"]), limit=5)
    assert [i["id"] for i in result.items.get("crafts", [])] == [world.craft.id]


@needs_search_db
async def test_an_english_stem_matches(world):
    result = await search_query.run("dyes", _scope(world, ["crafts"]), limit=5)
    assert world.craft.id in [i["id"] for i in result.items.get("crafts", [])]


@needs_search_db
async def test_snippets_are_plain_text_with_highlight_ranges(world):
    result = await search_query.run("indigo", _scope(world, ["products"]), limit=5)
    item = next(i for i in result.items["products"] if i["id"] == world.product_body.id)
    assert "<" not in item["snippet"]
    start, end = item["highlights"][0]
    assert item["snippet"][start:end].lower() == "indigo"


@needs_search_db
async def test_groups_come_in_vocabulary_order_with_totals(world, settings):
    result = await _search(world.researcher, world.stamp)
    kinds = [g["type"] for g in result["groups"]]
    assert kinds == [t for t in cross_portal.SEARCH_TYPES if t in kinds]
    assert {"artisans", "crafts", "products", "interviews"} <= set(kinds)
    assert not {"clusters", "designWorkshops", "reports"} & set(kinds)
    products = next(g for g in result["groups"] if g["type"] == "products")
    assert products["total"] == products["localTotal"] == 3 and products["remoteTotal"] == 0
    assert all(
        i["local"] is True and i["portal"] == "fieldRepository" for i in products["items"]
    )
    assert result["mode"] == "text"
    assert result["remote"] == {"portal": "designer", "label": "Designer portal", "status": "off"}
    assert result["typesRefused"] == []


@needs_search_db
async def test_every_account_reads_every_record_here(world, settings):
    contributor = await _search(world.contributor, world.stamp, types=["products"])
    assert len(_ids(contributor, "products")) == 3
    # Not shareable is a cross-portal rule only; locally the unconsented artisan is found.
    artisans = await _search(world.contributor, world.stamp, types=["artisans"])
    assert world.unconsented.id in _ids(artisans, "artisans")


@needs_search_db
async def test_limit_caps_each_group_and_type_pages(world, settings):
    first = await _search(world.professor, world.stamp, limit=2)
    products = next(g for g in first["groups"] if g["type"] == "products")
    assert len(products["items"]) == 2 and products["total"] == 3
    page1 = await _search(world.professor, world.stamp, type="products", limit=2, page=1)
    page2 = await _search(world.professor, world.stamp, type="products", limit=2, page=2)
    assert [g["type"] for g in page1["groups"]] == ["products"]
    assert page1["type"] == "products" and page1["page"] == 1
    seen = _ids(page1, "products") + _ids(page2, "products")
    assert len(seen) == 3 and len(set(seen)) == 3


@needs_search_db
async def test_filters_narrow_the_result(world, settings):
    by_craft = await _search(world.professor, world.stamp, craftId=world.other_craft.id)
    assert {g["type"] for g in by_craft["groups"]} == {"crafts"}
    by_workshop = await _search(world.professor, world.stamp, workshopIds=[world.workshop.id])
    assert _ids(by_workshop, "artisans") == [world.unconsented.id]
    by_place = await _search(world.professor, world.stamp, place="Sanganer")
    assert _ids(by_place, "crafts") == [world.other_craft.id]
    future = await _search(world.professor, world.stamp, dateFrom=datetime(2999, 1, 1, tzinfo=UTC))
    assert future["groups"] == []


@needs_search_db
async def test_the_query_is_cleaned_and_bounded(world, settings):
    cleaned = await _search(world.professor, f"\x00{world.stamp}\x00")
    assert cleaned["query"] == world.stamp
    with pytest.raises(HTTPException) as short:
        await _search(world.professor, "a")
    assert short.value.status_code == 422
    with pytest.raises(HTTPException) as unknown:
        await _search(world.professor, world.stamp, types=["nonsense"])
    assert unknown.value.status_code == 422


@needs_search_db
async def test_designer_only_types_ask_nothing_of_this_index(world, settings):
    result = await _search(world.professor, world.stamp, types=["designWorkshops", "clusters"])
    assert result["groups"] == [] and result["typesRefused"] == []


async def test_the_route_is_absent_while_the_flag_is_off(monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings(unified_search_enabled=False))
    with pytest.raises(HTTPException) as off:
        await routes.unified_search(
            current_user=SimpleNamespace(id="u1", email="u@example.org", role="RESEARCHER"),
            q="indigo",
            types=None,
            scope="all",
            limit=5,
            type=None,
            page=1,
            craftId=None,
            place=None,
            dateFrom=None,
            dateTo=None,
            workshopIds=None,
        )
    assert off.value.status_code == 404


def test_build_sql_applies_the_scope_in_every_list():
    sql, params = search_query.build_sql(
        "indigo", search_query.Scope(types=["artisans"]), limit=5, qvec="[0]"
    )
    assert sql.count("FROM base b") >= 4  # fts, trgm, vec and the counts all read the scoped base
    assert "d.\"audience\" = 'ALL'" in sql and "<=> " in sql
    assert params[0] == "indigo"


def test_the_peer_scope_rechecks_consent_in_sql():
    scope = search_query.Scope(types=["products"], shareable_only=True, require_artisan_consent=True)
    sql, _params = search_query.build_sql("indigo", scope, limit=5)
    assert 'd."shareable"' in sql
    assert '"ArtisanSharingConsent"' in sql and 'c."granted" IS NOT TRUE' in sql


def test_local_types_are_the_vocabulary_without_the_designer_types():
    assert us.LOCAL_TYPES == (
        "artisans", "crafts", "workshops", "interviews", "transcripts", "products", "tools",
        "processes", "media",
    )
