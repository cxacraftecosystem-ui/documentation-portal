"""Search embeddings send text to OpenAI or Gemini, so a transcript is embedded only with consent.

No database and no provider: ``search_indexer.db`` is a small in-memory stand-in for the two tables
the embedder reads (``SearchDocument`` and the ``Consent`` ledger's newest row), ``embed`` counts what
it was given, and the consent verdict is replaced per recording.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from app.services import consent_gate, search_embeddings, search_indexer


class _Db:
    def __init__(self) -> None:
        self.docs: list[dict[str, Any]] = []
        self.consent_rows: list[datetime] = []

    def add(self, entity_type: str, entity_id: str, text: str) -> dict[str, Any]:
        row = {"id": f"{entity_type}:{entity_id}", "entityType": entity_type, "entityId": entity_id,
               "contentHash": f"h-{entity_id}", "embeddedHash": None, "text": text,
               "updatedAt": len(self.docs)}
        self.docs.append(row)
        return row

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        if 'FROM "Consent"' in sql:
            latest = max(self.consent_rows) if self.consent_rows else None
            return [{"at": latest, "n": len(self.consent_rows)}]
        batch, _chars, *prefixes = args
        out = [d for d in self.docs
               if d["embeddedHash"] != d["contentHash"]
               and all(d["embeddedHash"] != p + d["contentHash"] for p in prefixes)]
        return [dict(d) for d in sorted(out, key=lambda d: d["updatedAt"])[:batch]]

    async def execute_raw(self, sql: str, *args: Any) -> int:
        if "LIKE" in sql:
            hits = [d for d in self.docs if str(d["embeddedHash"] or "").startswith(args[0].rstrip("%"))]
            for d in hits:
                d["embeddedHash"] = None
            return len(hits)
        if '"embedding"' in sql:
            _vector, content_hash, doc_id = args
        else:
            content_hash, doc_id = args
        for d in self.docs:
            if d["id"] == doc_id:
                d["embeddedHash"] = content_hash
        return 1


@pytest.fixture
def world(monkeypatch):
    db = _Db()
    sent: list[str] = []
    consent: dict[str, str] = {}

    async def available(_settings=None):
        return True

    async def embed(texts, *, settings=None, timeout=30.0):
        sent.extend(texts)
        return [[0.0] * search_embeddings.DIMENSIONS for _ in texts]

    async def verdict(media_id, *, client=None):
        return consent.get(media_id, consent_gate.AWAITING)

    monkeypatch.setattr(search_indexer, "db", db)
    monkeypatch.setattr(search_embeddings, "available", available)
    monkeypatch.setattr(search_embeddings, "embed", embed)
    monkeypatch.setattr(consent_gate, "verdict", verdict)
    monkeypatch.setitem(search_indexer._consent_seen, "mark", None)
    search_indexer.reset_backoff()
    yield db, sent, consent
    search_indexer.reset_backoff()


def _run(coro: Any) -> Any:
    return asyncio.run(coro)


def test_a_transcript_without_consent_is_never_sent_and_does_not_block_the_rest(world):
    db, sent, _consent = world
    held = db.add("transcripts", "m1", "the artisan's words")
    db.add("crafts", "c1", "Dabu printing")
    assert _run(search_indexer.embed_pending(batch=1)) == 1
    assert sent == ["Dabu printing"]
    assert held["embeddedHash"] == search_indexer.EMBED_CONSENT_PREFIX + held["contentHash"]
    assert _run(search_indexer.embed_pending()) == 0
    assert sent == ["Dabu printing"]


def test_a_withdrawn_consent_stops_the_next_embedding_of_a_changed_transcript(world):
    db, sent, consent = world
    doc = db.add("transcripts", "m1", "first words")
    consent["m1"] = consent_gate.ALLOW
    assert _run(search_indexer.embed_pending()) == 1
    consent["m1"] = consent_gate.REFUSED
    db.consent_rows.append(datetime.now(UTC))
    doc["contentHash"], doc["text"] = "h-m1-2", "edited words"
    assert _run(search_indexer.embed_pending()) == 0
    assert sent == ["first words"]


def test_consent_given_later_is_picked_up_by_itself(world):
    db, sent, consent = world
    db.add("transcripts", "m1", "the artisan's words")
    db.consent_rows.append(datetime.now(UTC) - timedelta(days=1))
    assert _run(search_indexer.embed_pending()) == 0
    assert _run(search_indexer.embed_pending()) == 0, "no new consent: the mark holds, nothing is asked"
    consent["m1"] = consent_gate.ALLOW
    db.consent_rows.append(datetime.now(UTC))
    assert _run(search_indexer.embed_pending()) == 1
    assert sent == ["the artisan's words"]


def test_an_unreadable_consent_sends_nothing_and_is_asked_again(world, monkeypatch):
    db, sent, _consent = world
    db.add("transcripts", "m1", "the artisan's words")

    async def broken(_media_id, *, client=None):
        raise RuntimeError("database blinked")

    monkeypatch.setattr(consent_gate, "verdict", broken)
    assert _run(search_indexer.embed_pending()) == 0
    assert sent == [] and db.docs[0]["embeddedHash"] is None
