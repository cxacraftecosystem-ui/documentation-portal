"""The artisan-card store against a REAL Postgres, with the real migration applied.

Opt-in: runs only with ``ARTISAN_CARD_DB_TESTS=1`` and a ``DATABASE_URL`` that is a throwaway test
database (never ``backend/.env``, which points at production). CI's placeholder DSN and an ordinary
``pytest`` skip it; the in-memory routes suite covers the same behaviour against a fake. What only a
real database can show is here: the partial unique index lets exactly one of many racing first reads
issue a card, and the backfill issues nothing the second time.
"""

from __future__ import annotations

import asyncio
import os
import secrets
from types import SimpleNamespace

import pytest

pytestmark = [
    pytest.mark.anyio,
    pytest.mark.skipif(
        os.environ.get("ARTISAN_CARD_DB_TESTS") != "1",
        reason="needs ARTISAN_CARD_DB_TESTS=1 and a throwaway DATABASE_URL",
    ),
]

SETTINGS = SimpleNamespace(
    artisan_cards_enabled=True,
    artisan_card_key="x" * 44,
    artisan_card_key_version=1,
    artisan_card_key_previous=None,
    artisan_card_issuer="F",
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture
async def client():
    url = os.environ.get("DATABASE_URL", "")
    assert "supabase" not in url and "pooler" not in url, (
        "refusing to run against a hosted database"
    )
    from prisma import Prisma

    prisma = Prisma(datasource={"url": url})
    await prisma.connect()
    try:
        yield prisma
    finally:
        await prisma.disconnect()


async def _artisans(client, n: int) -> list[str]:
    tag = secrets.token_hex(4)
    user = await client.user.create(
        data={"email": f"cards-{tag}@example.invalid", "name": "Card test"}
    )
    ids = []
    for i in range(n):
        row = await client.artisan.create(
            data={"name": f"Card {tag} {i}", "place": "Bhuj", "createdById": user.id}
        )
        ids.append(row.id)
    return ids


async def test_racing_first_reads_issue_exactly_one_live_card(client):
    from app.services import artisan_card_store as store

    (artisan_id,) = await _artisans(client, 1)
    results = await asyncio.gather(
        *(store.ensure_card(client, artisan_id, settings=SETTINGS, user_id=None) for _ in range(8))
    )
    assert len({card.code for card, _ in results}) == 1
    assert sum(1 for _, issued in results if issued) == 1
    assert await client.artisancard.count(where={"artisanId": artisan_id, "revokedAt": None}) == 1


async def test_the_partial_index_refuses_a_second_live_card_but_not_a_revoked_one(client):
    from app.services import artisan_card_store as store

    (artisan_id,) = await _artisans(client, 1)
    first, _ = await store.ensure_card(client, artisan_id, settings=SETTINGS, user_id=None)
    with pytest.raises(Exception) as caught:
        await client.artisancard.create(data={"artisanId": artisan_id, "code": "ZZZZZZZZZZ"})
    assert "nique" in str(caught.value)
    second = await store.reissue(client, artisan_id, settings=SETTINGS, reason="LOST", user_id=None)
    assert second.code != first.code
    assert await client.artisancard.count(where={"artisanId": artisan_id}) == 2
    assert await client.artisancard.count(where={"artisanId": artisan_id, "revokedAt": None}) == 1


async def test_bulk_issue_and_the_backfill_are_idempotent(client):
    from app.services import artisan_card_store as store
    from scripts import backfill_artisan_cards as backfill

    ids = await _artisans(client, 7)
    cards = await store.ensure_cards(client, ids[:3], settings=SETTINGS, user_id=None)
    assert set(cards) == set(ids[:3])
    first = await backfill.run(client, SETTINGS, apply=True, batch=2)
    assert first.missing == 0
    second = await backfill.run(client, SETTINGS, apply=True, batch=2)
    assert second.issued == 0 and second.missing == 0
    for artisan_id in ids:
        assert (
            await client.artisancard.count(where={"artisanId": artisan_id, "revokedAt": None}) == 1
        )
