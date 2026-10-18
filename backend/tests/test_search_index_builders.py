"""What one record becomes in the search index (services/search_index; docs/SEARCH.md).

Against a real database (opt-in, see ``search_support``): the builders read relations, and the
shareable rule reads the artisan's sharing consent. Every type is built from one seeded world;
identity values are seeded on purpose and must reach no title, subtitle, body or place.
"""

from __future__ import annotations

import pytest
from search_support import consent, every_record, needs_search_db, new_stamp, seed

from app.services import search_index, unified_search

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
        w.docs = {}
        for entity_type, entity_id in every_record(w):
            w.docs[(entity_type, entity_id)] = await search_index.build(entity_type, entity_id)
        yield w
    finally:
        await db.disconnect()


@needs_search_db
async def test_every_type_builds_a_document(world):
    built = {key: doc for key, doc in world.docs.items() if doc is not None}
    assert {t for t, _ in built} == set(search_index.BUILDERS)
    for (entity_type, entity_id), doc in built.items():
        assert doc["entityType"] == entity_type and doc["entityId"] == entity_id
        assert doc["title"] and len(doc["contentHash"]) == 64
        assert doc["audience"] == "ALL"
        assert doc["designWorkshopId"] is None


@needs_search_db
async def test_no_identity_value_reaches_the_index(world):
    for key, doc in world.docs.items():
        if doc is None:
            continue
        text = " ".join(str(doc.get(f) or "") for f in ("title", "subtitle", "body", "place"))
        for secret in world.secrets:
            assert secret not in text, (key, secret)


@needs_search_db
async def test_the_identity_answer_is_skipped_and_the_practice_answer_kept(world):
    doc = world.docs[("interviews", world.interview.id)]
    assert "Indigo and madder." in doc["body"]
    assert doc["artisanIds"] == [world.consented.id]


@needs_search_db
async def test_shareable_follows_the_artisans_consent(world):
    docs = world.docs
    assert docs[("artisans", world.consented.id)]["shareable"] is True
    assert docs[("artisans", world.unconsented.id)]["shareable"] is False
    assert docs[("products", world.product_title.id)]["shareable"] is True
    assert docs[("products", world.product_body.id)]["shareable"] is False
    # Named only as free text: never shareable.
    assert docs[("products", world.product_free.id)]["shareable"] is False
    assert docs[("tools", world.tool.id)]["shareable"] is True
    assert docs[("interviews", world.interview.id)]["shareable"] is True
    assert docs[("transcripts", world.transcript_consented.id)]["shareable"] is True
    assert docs[("processes", world.process.id)]["shareable"] is True
    # Names no artisan.
    assert docs[("crafts", world.craft.id)]["shareable"] is True
    assert docs[("workshops", world.workshop.id)]["shareable"] is True
    assert docs[("transcripts", world.transcript_open.id)]["shareable"] is True


@needs_search_db
async def test_withdrawn_consent_unshares_on_the_next_build(world):
    await consent(world.consented.id, False)
    try:
        assert (await search_index.build("artisans", world.consented.id))["shareable"] is False
        assert (await search_index.build("products", world.product_title.id))["shareable"] is False
    finally:
        await consent(world.consented.id, True)


@needs_search_db
async def test_open_paths_are_stable(world):
    assert world.docs[("artisans", world.consented.id)]["openPath"] == (
        f"/artisans/{world.consented.id}"
    )
    assert world.docs[("transcripts", world.transcript_open.id)]["openPath"] == (
        f"/media/{world.transcript_open.id}"
    )
    assert world.docs[("interviews", world.interview.id)]["openPath"] == (
        f"/questionnaire/interviews/{world.interview.id}"
    )


@needs_search_db
async def test_a_transcriptless_file_has_no_transcript_document(world):
    from app.core.db import db

    await db.mediafile.update(where={"id": world.transcript_open.id}, data={"transcriptText": ""})
    assert await search_index.build("transcripts", world.transcript_open.id) is None
    assert await search_index.build("media", world.transcript_open.id) is not None


@needs_search_db
async def test_the_body_is_capped(world):
    from app.core.db import db

    await db.mediafile.update(
        where={"id": world.transcript_consented.id}, data={"transcriptText": "indigo " * 30_000}
    )
    doc = await search_index.build("transcripts", world.transcript_consented.id)
    assert len(doc["body"]) == search_index.BODY_LIMIT


@needs_search_db
async def test_missing_unknown_and_designer_types_build_nothing(world):
    assert await search_index.build("artisans", "does-not-exist") is None
    assert await search_index.build("nonsense", world.consented.id) is None
    for designer_only in ("clusters", "designWorkshops", "reports"):
        assert await search_index.build(designer_only, world.consented.id) is None


def test_scrub_removes_identity_shaped_text():
    text = search_index.scrub("Call 98765 01234 or 2341-2341-2346, mail a.b@c.org, year 2026")
    assert "98765" not in text and "2341" not in text and "@" not in text
    assert "2026" in text


def test_open_path_quotes_the_id_and_covers_every_local_type():
    assert search_index.open_path("artisans", "a b/c") == "/artisans/a%20b%2Fc"
    assert set(search_index.OPEN_PATHS) == set(unified_search.LOCAL_TYPES)
    assert set(search_index.BUILDERS) == set(unified_search.LOCAL_TYPES)


def test_content_hash_covers_title_subtitle_and_body_only():
    base = {"title": "a", "subtitle": "b", "body": "c", "place": "x"}
    same = dict(base, place="y")
    changed = dict(base, body="d")
    assert search_index.content_hash(base) == search_index.content_hash(same)
    assert search_index.content_hash(base) != search_index.content_hash(changed)
