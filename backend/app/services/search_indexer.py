"""Keeps ``SearchDocument`` current: drains ``SearchIndexQueue`` and embeds changed documents.

The ``search_enqueue()`` triggers list every insert and update in the queue (a delete removes the
document at once, in the same transaction as the record). :func:`drain` claims a small batch with
``FOR UPDATE SKIP LOCKED`` (so two drains never build the same item), builds each record through
``services/search_index.build`` and writes or deletes its document. An artisan is expanded to the
records whose ``shareable`` depends on their sharing consent. A build that fails puts its item
back on the queue for the next pass.

:func:`embed_pending` runs only when meaning ranking is available (``search_embeddings.available``)
and embeds documents whose text changed since they were last embedded.

The queue worker (``app/worker.py``) calls both every 15 seconds while UNIFIED_SEARCH_ENABLED is on,
in small batches, because the production box is memory-bound.
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any

from app.core.db import db
from app.services import search_embeddings, search_index

logger = logging.getLogger(__name__)

#: Characters of the body that go into an embedding.
EMBED_BODY_CHARS = 8_000

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
    """Index up to ``batch`` queued items (plus an artisan's dependents). Returns items handled."""
    claimed = await db.query_raw(
        """
        DELETE FROM "SearchIndexQueue" WHERE "id" IN (
            SELECT "id" FROM "SearchIndexQueue" ORDER BY "enqueuedAt", "id"
             LIMIT $1 FOR UPDATE SKIP LOCKED
        ) RETURNING "entityType", "entityId"
        """,
        batch,
    )
    items: list[tuple[str, str]] = []
    for row in claimed:
        item = (str(row["entityType"]), str(row["entityId"]))
        if item not in items:
            items.append(item)
    for entity_type, entity_id in list(items):
        if entity_type == "artisans":
            for dependent in await search_index.dependents_of_artisan(entity_id):
                if dependent not in items:
                    items.append(dependent)
    for entity_type, entity_id in items:
        try:
            await index_one(entity_type, entity_id)
        except Exception:
            logger.exception("search index: %s %s failed; queued again", entity_type, entity_id)
            await enqueue(entity_type, entity_id)
        await asyncio.sleep(0)
    return len(items)


async def embed_pending(batch: int = 32, *, settings: Any | None = None) -> int:
    """Embed documents whose text changed since their last embedding. No-op unless available."""
    if not await search_embeddings.available(settings):
        return 0
    rows = await db.query_raw(
        """
        SELECT "id", "contentHash",
               concat_ws(E'\\n', "title", "subtitle", left("body", $2::int)) AS text
          FROM "SearchDocument"
         WHERE "embeddedHash" IS DISTINCT FROM "contentHash"
         ORDER BY "updatedAt"
         LIMIT $1
        """,
        batch,
        EMBED_BODY_CHARS,
    )
    if not rows:
        return 0
    try:
        vectors = await search_embeddings.embed([str(r["text"]) for r in rows], settings=settings)
    except search_embeddings.EmbeddingError as exc:
        logger.warning("search embeddings: batch skipped (%s)", exc)
        return 0
    for row, vector in zip(rows, vectors, strict=True):
        await db.execute_raw(
            'UPDATE "SearchDocument" SET "embedding" = $1::vector, "embeddedHash" = $2 WHERE "id" = $3',
            search_embeddings.vector_literal(vector),
            row["contentHash"],
            row["id"],
        )
    return len(rows)


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
        "transcripts": '"MediaFile" WHERE btrim(coalesce("transcriptText", \'\')) <> \'\'',
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
