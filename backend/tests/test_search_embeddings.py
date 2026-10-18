"""Meaning ranking behind the AI flag (services/search_embeddings; docs/SEARCH.md).

No provider is ever called: ``embed`` is replaced by a fake that counts its calls. The database
tests are opt-in (see ``search_support``); the vector ones also need the ``embedding`` column, which
exists where pgvector is installed (a local pgvector container), and skip elsewhere.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from search_support import index_world, needs_search_db, new_stamp, seed

from app.services import search_embeddings, search_indexer, search_query

pytestmark = pytest.mark.anyio

DIM = search_embeddings.DIMENSIONS


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
        search_embeddings.reset_cache()
        w.has_vector = await search_embeddings.column_present()
        yield w
    finally:
        search_embeddings.reset_cache()
        await db.disconnect()


def _settings(**over: Any) -> SimpleNamespace:
    base = {
        "search_embeddings_enabled": True,
        "search_embedding_provider": "openai",
        "search_embedding_model": "text-embedding-3-small",
        "openai_api_key": "sk-test-placeholder",
        "gemini_api_keys": [],
    }
    base.update(over)
    return SimpleNamespace(**base)


def _unit(axis: int) -> list[float]:
    vector = [0.0] * DIM
    vector[axis] = 1.0
    return vector


@pytest.fixture
def fake_embed(monkeypatch):
    calls: list[list[str]] = []

    async def embed(texts, *, settings=None, timeout=30.0):
        calls.append(list(texts))
        return [_unit(len(t) % DIM) for t in texts]

    monkeypatch.setattr(search_embeddings, "embed", embed)
    search_embeddings._query_cache.clear()
    return calls


async def test_flag_off_means_text_mode_and_no_call(fake_embed):
    off = _settings(search_embeddings_enabled=False)
    assert await search_embeddings.available(off) is False
    assert await search_indexer.embed_pending(settings=off) == 0
    assert fake_embed == []
    no_key = _settings(openai_api_key=None)
    assert await search_embeddings.available(no_key) is False


async def test_no_vector_column_means_text_mode(fake_embed, monkeypatch):
    monkeypatch.setattr(search_embeddings, "_column_present", False)
    assert await search_embeddings.available(_settings()) is False
    assert await search_indexer.embed_pending(settings=_settings()) == 0
    assert fake_embed == []


async def test_a_provider_failure_falls_back_to_text(monkeypatch):
    async def boom(*_a, **_k):
        raise search_embeddings.EmbeddingError("TimeoutException")

    monkeypatch.setattr(search_embeddings, "_openai", boom)
    search_embeddings._query_cache.clear()
    assert await search_embeddings.embed_query("indigo", settings=_settings()) is None

    async def slow(*_a, **_k):
        await asyncio.sleep(5)

    monkeypatch.setattr(search_embeddings, "_openai", slow)

    async def bounded(settings, texts, timeout):
        return await asyncio.wait_for(slow(), timeout=0.01)

    monkeypatch.setattr(search_embeddings, "_openai", bounded)
    assert await search_embeddings.embed_query("madder", settings=_settings()) is None


async def test_the_wrong_shape_is_refused(monkeypatch):
    async def short(settings, texts, timeout):
        return [[0.1, 0.2]]

    monkeypatch.setattr(search_embeddings, "_openai", short)
    with pytest.raises(search_embeddings.EmbeddingError):
        await search_embeddings.embed(["x"], settings=_settings())


@needs_search_db
async def test_embed_pending_skips_unchanged_documents(world, fake_embed):
    from app.core.db import db

    if not world.has_vector:
        pytest.skip("pgvector is not installed on this database")
    settings = _settings()
    while await search_indexer.embed_pending(batch=200, settings=settings):
        pass
    fake_embed.clear()
    assert await search_indexer.embed_pending(settings=settings) == 0
    assert fake_embed == []
    # A changed document is embedded again, and only it.
    await search_indexer.index_one("crafts", world.other_craft.id)
    await db.craft.update(where={"id": world.other_craft.id}, data={"description": "Changed text."})
    await search_indexer.index_one("crafts", world.other_craft.id)
    assert await search_indexer.embed_pending(settings=settings) == 1
    assert len(fake_embed) == 1 and len(fake_embed[0]) == 1


@needs_search_db
async def test_hybrid_mode_ranks_by_meaning(world, fake_embed):
    from app.core.db import db

    if not world.has_vector:
        pytest.skip("pgvector is not installed on this database")
    target = await db.query_raw(
        'SELECT "id" FROM "SearchDocument" WHERE "entityType" = \'products\' AND "entityId" = $1',
        world.product_free.id,
    )
    await db.execute_raw(
        'UPDATE "SearchDocument" SET "embedding" = $1::vector WHERE "id" = $2',
        search_embeddings.vector_literal(_unit(7)),
        target[0]["id"],
    )
    scope = search_query.Scope(types=["products"], craft_ids=[world.craft.id])
    # Words that match nothing: only the vector list can find it.
    result = await search_query.run("zzqx wvvk", scope, limit=5, qvec=_unit(7))
    assert result.mode == "hybrid"
    assert result.items["products"][0]["id"] == world.product_free.id
    text_only = await search_query.run("zzqx wvvk", scope, limit=5)
    assert text_only.mode == "text" and text_only.items == {}


async def test_the_query_embedding_is_cached(fake_embed):
    first = await search_embeddings.embed_query("Indigo ", settings=_settings())
    again = await search_embeddings.embed_query("indigo", settings=_settings())
    assert first == again and len(fake_embed) == 1


def test_vector_literal_is_pgvector_text():
    assert search_embeddings.vector_literal([1.0, 0.5, 0.0]) == "[1,0.5,0]"
