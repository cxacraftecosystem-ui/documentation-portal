"""Keeps ``SearchDocument`` current: drains ``SearchIndexQueue`` and embeds changed documents.

The ``search_enqueue()`` triggers list every insert and update in the queue (a delete removes the
document at once, in the same transaction as the record). :func:`drain` reads a small batch,
builds each record through ``services/search_index.build``, writes or deletes its document, and
only then deletes the queue rows it read, so a worker that dies mid-batch loses nothing (the next
pass builds those items again; a build is idempotent). One queue worker runs per deployment
(``media_queue.acquire_queue_worker_lock``). An artisan is expanded to the
records whose ``shareable`` depends on their sharing consent. A build that fails puts its item
back on the queue for the next pass. A product, tool or interview is expanded to the files (and a
product to the processes) that take their artisans from it (``search_index.dependents_of``).

While UNIFIED_SEARCH_ENABLED is off nothing is built, and :func:`compact_queue` keeps the queue to
one row per record, so it cannot grow past the number of records however long the switch stays off.

:func:`embed_pending` runs only when meaning ranking is available (``search_embeddings.available``)
and embeds documents whose text changed since they were last embedded. A batch the provider refuses
is retried one document at a time: a document that fails alone while others succeed is marked
(``embeddedHash = 'failed:' || contentHash``) and skipped until its text changes; when every one
fails the provider is down, and the next pass waits (30 seconds, doubling to an hour).

The queue worker (``app/worker.py``) calls both every 15 seconds while UNIFIED_SEARCH_ENABLED is on,
in small batches, because the production box is memory-bound.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from typing import Any

from app.core.db import db
from app.services import search_embeddings, search_index

logger = logging.getLogger(__name__)

#: Characters of the body that go into an embedding.
EMBED_BODY_CHARS = 8_000

#: The wait after a pass in which the provider refused everything, doubling to the ceiling.
EMBED_BACKOFF_SECONDS = 30.0
EMBED_BACKOFF_CEILING_SECONDS = 3600.0
#: The prefix ``embeddedHash`` carries for a document the provider refused on its own.
EMBED_FAILED_PREFIX = "failed:"

_embed_backoff = {"until": 0.0, "wait": 0.0}

_UPSERT = """
INSERT INTO "SearchDocument" (
    "id", "entityType", "entityId", "title", "subtitle", "body", "place",
    "craftIds", "craftCodes", "workshopIds", "artisanIds",
    "designWorkshopId", "audience", "shareable", "openPath", "recordDate", "contentHash", "updatedAt"
) VALUES (
    $1, $2, $3, $4, $5, $6, $7,
    ARRAY(SELECT jsonb_array_elements_text($8::jsonb)),
    ARRAY(SELECT jsonb_array_elements_text($9::jsonb)),
    ARRAY(SELECT jsonb_array_elements_text($10::jsonb)),
    ARRAY(SELECT jsonb_array_elements_text($11::jsonb)),
    $12, $13, $14, $15, $16::timestamptz, $17, now()
)
ON CONFLICT ("entityType", "entityId") DO UPDATE SET
    "title" = EXCLUDED."title", "subtitle" = EXCLUDED."subtitle", "body" = EXCLUDED."body",
    "place" = EXCLUDED."place", "craftIds" = EXCLUDED."craftIds",
    "craftCodes" = EXCLUDED."craftCodes", "workshopIds" = EXCLUDED."workshopIds",
    "artisanIds" = EXCLUDED."artisanIds", "designWorkshopId" = EXCLUDED."designWorkshopId",
    "audience" = EXCLUDED."audience", "shareable" = EXCLUDED."shareable",
    "openPath" = EXCLUDED."openPath", "recordDate" = EXCLUDED."recordDate",
    "contentHash" = EXCLUDED."contentHash", "updatedAt" = now()
"""


async def write(doc: dict[str, Any]) -> None:
    await db.execute_raw(
        _UPSERT,
        uuid.uuid4().hex,
        doc["entityType"],
        doc["entityId"],
        doc["title"],
        doc.get("subtitle"),
        doc.get("body") or "",
        doc.get("place"),
        json.dumps(doc.get("craftIds") or []),
        json.dumps(doc.get("craftCodes") or []),
        json.dumps(doc.get("workshopIds") or []),
        json.dumps(doc.get("artisanIds") or []),
        doc.get("designWorkshopId"),
        doc.get("audience") or search_index.AUDIENCE_ALL,
        bool(doc.get("shareable")),
        doc["openPath"],
        doc.get("recordDate"),
        doc["contentHash"],
    )


async def remove(entity_type: str, entity_id: str) -> None:
    await db.execute_raw(
        'DELETE FROM "SearchDocument" WHERE "entityType" = $1 AND "entityId" = $2',
        entity_type,
        entity_id,
    )


async def index_one(entity_type: str, entity_id: str) -> bool:
    """Build and write (or delete) one document. True when a document now exists."""
    doc = await search_index.build(entity_type, entity_id)
    if doc is None:
        await remove(entity_type, entity_id)
        return False
    await write(doc)
    return True


async def enqueue(entity_type: str, entity_id: str) -> None:
    await db.execute_raw(
        'INSERT INTO "SearchIndexQueue" ("entityType", "entityId") VALUES ($1, $2)',
        entity_type,
        entity_id,
    )


async def drain(batch: int = 100) -> int:
    """Index up to ``batch`` queued items (plus their dependents). Returns items handled."""
    claimed = await db.query_raw(
        """
        SELECT "id", "entityType", "entityId" FROM "SearchIndexQueue"
         ORDER BY "enqueuedAt", "id"
         LIMIT $1
        """,
        batch,
    )
    if not claimed:
        return 0
    items: list[tuple[str, str]] = []
    for row in claimed:
        item = (str(row["entityType"]), str(row["entityId"]))
        if item not in items:
            items.append(item)
    for entity_type, entity_id in list(items):
        for dependent in await search_index.dependents_of(entity_type, entity_id):
            if dependent not in items:
                items.append(dependent)
    failed: list[tuple[str, str]] = []
    for entity_type, entity_id in items:
        try:
            await index_one(entity_type, entity_id)
        except Exception:
            logger.exception("search index: %s %s failed; queued again", entity_type, entity_id)
            failed.append((entity_type, entity_id))
        await asyncio.sleep(0)
    # Only now are the rows read above removed: rows queued while this pass ran stay queued.
    await db.execute_raw(
        """DELETE FROM "SearchIndexQueue"
            WHERE "id" IN (SELECT jsonb_array_elements_text($1::jsonb)::bigint)""",
        json.dumps([int(row["id"]) for row in claimed]),
    )
    for entity_type, entity_id in failed:
        await enqueue(entity_type, entity_id)
    return len(items)


async def compact_queue() -> int:
    """Keep the oldest queue row of each record and delete the rest. Returns rows deleted."""
    return await db.execute_raw(
        """
        DELETE FROM "SearchIndexQueue" q
         USING "SearchIndexQueue" o
         WHERE q."entityType" = o."entityType" AND q."entityId" = o."entityId" AND q."id" > o."id"
        """
    )


async def _store_vector(row: dict[str, Any], vector: list[float]) -> None:
    await db.execute_raw(
        'UPDATE "SearchDocument" SET "embedding" = $1::vector, "embeddedHash" = $2 WHERE "id" = $3',
        search_embeddings.vector_literal(vector),
        row["contentHash"],
        row["id"],
    )


def _back_off() -> None:
    wait = _embed_backoff["wait"] * 2 if _embed_backoff["wait"] else EMBED_BACKOFF_SECONDS
    _embed_backoff["wait"] = min(wait, EMBED_BACKOFF_CEILING_SECONDS)
    _embed_backoff["until"] = time.monotonic() + _embed_backoff["wait"]


def reset_backoff() -> None:
    _embed_backoff["until"] = 0.0
    _embed_backoff["wait"] = 0.0


async def embed_pending(batch: int = 32, *, settings: Any | None = None) -> int:
    """Embed documents whose text changed since their last embedding. No-op unless available."""
    if not await search_embeddings.available(settings):
        return 0
    if time.monotonic() < _embed_backoff["until"]:
        return 0
    rows = await db.query_raw(
        """
        SELECT "id", "contentHash",
               concat_ws(E'\\n', "title", "subtitle", left("body", $2::int)) AS text
          FROM "SearchDocument"
         WHERE "embeddedHash" IS DISTINCT FROM "contentHash"
           AND "embeddedHash" IS DISTINCT FROM ($3 || "contentHash")
         ORDER BY "updatedAt"
         LIMIT $1
        """,
        batch,
        EMBED_BODY_CHARS,
        EMBED_FAILED_PREFIX,
    )
    if not rows:
        return 0
    try:
        vectors = await search_embeddings.embed([str(r["text"]) for r in rows], settings=settings)
    except search_embeddings.EmbeddingError as exc:
        logger.warning("search embeddings: batch refused (%s); trying one at a time", exc)
        vectors = None
    if vectors is not None:
        for row, vector in zip(rows, vectors, strict=True):
            await _store_vector(row, vector)
        reset_backoff()
        return len(rows)

    stored: int = 0
    refused: list[dict[str, Any]] = []
    for row in rows if len(rows) > 1 else []:
        try:
            (vector,) = await search_embeddings.embed([str(row["text"])], settings=settings)
        except search_embeddings.EmbeddingError:
            refused.append(row)
            continue
        await _store_vector(row, vector)
        stored += 1
    if not stored:
        # Nothing went through: the provider is down or refusing the key, not one document.
        logger.warning("search embeddings: provider refused every document; backing off")
        _back_off()
        return 0
    for row in refused:
        await db.execute_raw(
            'UPDATE "SearchDocument" SET "embeddedHash" = $1 WHERE "id" = $2',
            EMBED_FAILED_PREFIX + str(row["contentHash"]),
            row["id"],
        )
    reset_backoff()
    return stored


async def reindex_all(entity_type: str | None = None) -> int:
    """Queue every record (or one type) again. Used by ``scripts/search_reindex.py``."""
    tables = {
        "artisans": '"Artisan"',
        "crafts": '"Craft"',
        "workshops": '"Workshop"',
        "products": '"ProductDocumentation"',
        "tools": '"ToolDocumentation"',
        "processes": '"Process"',
        "media": '"MediaFile"',
        "transcripts": "\"MediaFile\" WHERE btrim(coalesce(\"transcriptText\", '')) <> ''",
        "interviews": '"QuestionnaireInterview"',
    }
    sources = {name: f"SELECT '{name}', \"id\" FROM {table}" for name, table in tables.items()}
    if entity_type is not None and entity_type not in sources:
        raise ValueError(f"unknown search type: {entity_type}")
    total = 0
    for name, select in sources.items():
        if entity_type is None or name == entity_type:
            total += await db.execute_raw(
                f'INSERT INTO "SearchIndexQueue" ("entityType", "entityId") {select}'
            )
    return total
