"""The ``search_enqueue()`` triggers and the indexer that drains them (docs/SEARCH.md).

Against a real database (opt-in, see ``search_support``), because the behaviour under test is
Postgres's: insert and update list the record in ``SearchIndexQueue``, a delete removes its document
in the same statement, a step lists its process, a consent row lists its artisan, and the drain
expands an artisan to the records whose ``shareable`` depends on it.

The queue is shared by every module that writes records, and nothing drains it while tests run, so
each test empties it first; it holds nothing a test database needs.
"""

from __future__ import annotations

import pytest
from search_support import consent, document, index_world, needs_search_db, new_stamp, seed

from app.services import search_indexer

pytestmark = [needs_search_db, pytest.mark.anyio]


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


async def _clear_queue() -> None:
    from app.core.db import db

    await db.execute_raw('DELETE FROM "SearchIndexQueue"')


async def _queued() -> set[tuple[str, str]]:
    from app.core.db import db

    rows = await db.query_raw('SELECT "entityType", "entityId" FROM "SearchIndexQueue"')
    return {(r["entityType"], r["entityId"]) for r in rows}


async def _drain_all() -> None:
    while await search_indexer.drain(batch=500):
        pass


async def test_insert_and_update_enqueue_the_record(world):
    from app.core.db import db

    await _clear_queue()
    artisan = await db.artisan.create(
        data={
            "name": f"New artisan {world.stamp}",
            "place": "Bagru",
            "createdById": world.professor.id,
        }
    )
    assert ("artisans", artisan.id) in await _queued()
    await _clear_queue()
    await db.artisan.update(where={"id": artisan.id}, data={"name": f"Renamed {world.stamp}"})
    assert ("artisans", artisan.id) in await _queued()
    await _drain_all()
    doc = await document("artisans", artisan.id)
    assert doc is not None and doc["title"] == f"Renamed {world.stamp}"


async def test_a_delete_removes_the_document_at_once(world):
    from app.core.db import db

    craft = await db.craft.create(data={"name": f"Short lived {world.stamp}"})
    await search_indexer.index_one("crafts", craft.id)
    assert await document("crafts", craft.id) is not None
    await _clear_queue()
    await db.craft.delete(where={"id": craft.id})
    assert await document("crafts", craft.id) is None
    assert ("crafts", craft.id) not in await _queued()


async def test_a_media_delete_removes_both_its_documents(world):
    from app.core.db import db

    media = await db.mediafile.create(
        data={
            "originalFilename": f"gone-{world.stamp}.mp3",
            "objectKey": f"search/{world.stamp}/gone.mp3",
            "mediaType": "AUDIO",
            "mimeType": "audio/mpeg",
            "sizeBytes": 1,
            "bucket": "test-bucket",
            "uploadedById": world.professor.id,
            "transcriptText": "Short lived words.",
        }
    )
    await search_indexer.index_one("media", media.id)
    await search_indexer.index_one("transcripts", media.id)
    await db.mediafile.delete(where={"id": media.id})
    assert await document("media", media.id) is None
    assert await document("transcripts", media.id) is None


async def test_a_step_change_enqueues_its_process(world):
    from app.core.db import db

    await _clear_queue()
    await db.processstep.create(
        data={"processId": world.process.id, "name": f"Rinse {world.stamp}", "sortOrder": 2}
    )
    assert ("processes", world.process.id) in await _queued()
    await _drain_all()
    assert f"Rinse {world.stamp}" in (await document("processes", world.process.id))["body"]


async def test_an_answer_change_enqueues_its_interview(world):
    from app.core.db import db

    await _clear_queue()
    await db.questionnaireresponse.update_many(
        where={"interviewId": world.interview.id, "questionId": world.question_dyes.id},
        data={"answerText": f"Pomegranate {world.stamp}"},
    )
    assert ("interviews", world.interview.id) in await _queued()


async def test_a_consent_change_reaches_the_artisan_and_its_dependents(world):
    assert (await document("products", world.product_body.id))["shareable"] is False
    await _clear_queue()
    await consent(world.unconsented.id, True)
    assert ("artisans", world.unconsented.id) in await _queued()
    await _drain_all()
    assert (await document("artisans", world.unconsented.id))["shareable"] is True
    assert (await document("products", world.product_body.id))["shareable"] is True

    await consent(world.unconsented.id, False)
    await _drain_all()
    assert (await document("artisans", world.unconsented.id))["shareable"] is False
    assert (await document("products", world.product_body.id))["shareable"] is False


async def test_a_failed_build_goes_back_on_the_queue(world, monkeypatch):
    from app.services import search_index

    async def boom(_entity_id):
        raise RuntimeError("builder failed")

    await _clear_queue()
    await search_indexer.enqueue("crafts", world.craft.id)
    monkeypatch.setitem(search_index.BUILDERS, "crafts", boom)
    assert await search_indexer.drain(batch=10) == 1
    assert ("crafts", world.craft.id) in await _queued()
    await _clear_queue()


async def test_reindex_all_queues_one_type(world):
    await _clear_queue()
    queued = await search_indexer.reindex_all("crafts")
    assert queued >= 2
    assert {t for t, _ in await _queued()} == {"crafts"}
    with pytest.raises(ValueError):
        await search_indexer.reindex_all("designWorkshops")
    await _clear_queue()
