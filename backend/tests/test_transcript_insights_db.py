"""Transcript insights against a REAL Postgres with the migration applied.

Opt-in: runs only with ``TRANSCRIPT_INSIGHTS_DB_TESTS=1`` and a ``DATABASE_URL`` that is a throwaway
test database (never ``backend/.env``, which points at production). What only a real database can
show is here: the unique ``(mediaFileId, version)`` index, the cascade on media delete, the new job
type being accepted, and enqueue/generate/supersede running through the real client.
"""

from __future__ import annotations

import os
import secrets
from datetime import UTC, datetime

import pytest

pytestmark = [
    pytest.mark.anyio,
    pytest.mark.skipif(
        os.environ.get("TRANSCRIPT_INSIGHTS_DB_TESTS") != "1",
        reason="needs TRANSCRIPT_INSIGHTS_DB_TESTS=1 and a throwaway DATABASE_URL",
    ),
]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture
async def client(monkeypatch):
    url = os.environ.get("DATABASE_URL", "")
    assert "supabase" not in url and "pooler" not in url, "refusing to run against a hosted database"
    from app.services import transcript_insights as ti
    from prisma import Prisma

    prisma = Prisma(datasource={"url": url})
    await prisma.connect()
    monkeypatch.setattr(ti, "db", prisma)
    # The consent gate reads the ledger through the app's client.
    import app.core.db as core_db

    monkeypatch.setattr(core_db, "db", prisma)
    monkeypatch.setattr(ti, "enabled", lambda: True)
    try:
        yield prisma
    finally:
        await prisma.disconnect()


async def _media(client, text: str = "We dye with indigo. It takes three days.") -> str:
    tag = secrets.token_hex(4)
    user = await client.user.create(data={"email": f"ti-{tag}@example.invalid", "name": "Insight test"})
    media = await client.mediafile.create(
        data={
            "originalFilename": "talk.webm",
            "mediaType": "AUDIO",
            "mimeType": "audio/webm",
            "sizeBytes": 10,
            "bucket": "b",
            "objectKey": f"ti/{tag}.webm",
            "uploadedById": user.id,
            "transcriptText": text,
            "transcriptStatus": "COMPLETED",
        }
    )
    return media.id


def _row(media_id: str, version: int) -> dict:
    from prisma import Json

    return {
        "mediaFileId": media_id,
        "version": version,
        "sourceHash": "h",
        "translationStatus": "NOT_NEEDED",
        "provider": "openai",
        "modelId": "gpt-4o-mini",
        "promptVersion": "transcript-insights/1",
        "generated": Json({"summaryLines": [], "quotes": [], "translationText": None}),
        "generatedAt": datetime.now(UTC),
    }


async def test_the_database_rejects_a_duplicate_version(client):
    from prisma.errors import UniqueViolationError

    media_id = await _media(client)
    await client.transcriptinsight.create(data=_row(media_id, 1))
    with pytest.raises(UniqueViolationError):
        await client.transcriptinsight.create(data=_row(media_id, 1))


async def test_deleting_media_cascades_to_its_insights(client):
    media_id = await _media(client)
    await client.transcriptinsight.create(data=_row(media_id, 1))
    await client.transcriptinsight.create(data=_row(media_id, 2))
    await client.mediafile.delete(where={"id": media_id})
    assert await client.transcriptinsight.count(where={"mediaFileId": media_id}) == 0


async def test_enqueue_and_generate_through_the_real_client(client, monkeypatch):
    import json

    from app.services import transcript_insights as ti

    async def resolve(_user_id, _task):
        return object()

    reply = json.dumps({"primaryLanguage": "en", "summary": ["Indigo dyeing takes three days."],
                        "quotes": [{"original": "It takes three days.", "english": "It takes three days.",
                                    "speaker": None}]})
    monkeypatch.setattr(ti.user_ai_keys, "resolve", resolve)
    monkeypatch.setattr(ti, "_chat_sync", lambda *a, **k: {"text": reply, "provider": "openai",
                                                           "model": "gpt-4o-mini", "modelVersion": None})
    from app.services import consent_gate

    media_id = await _media(client)
    uploader = (await client.mediafile.find_unique(where={"id": media_id})).uploadedById
    assert await ti.enqueue(media_id) is None, "no consent recorded: nothing is queued"
    await consent_gate.record_recording_consent(media_id, state="GRANTED", actor_id=uploader, client=client)
    job = await ti.enqueue(media_id)
    assert job is not None and job.jobType == "TRANSCRIPT_INSIGHTS" and job.priority == 80
    assert await ti.enqueue(media_id) is None  # one active job at a time

    media = await client.mediafile.find_unique(where={"id": media_id})
    first = await ti.generate(media, user_id=None)
    second = await ti.generate(media, user_id=None)
    assert (first["version"], second["version"]) == (1, 2)
    rows = await client.transcriptinsight.find_many(where={"mediaFileId": media_id}, order={"version": "asc"})
    assert rows[0].supersededAt is not None and rows[1].supersededAt is None
    assert rows[1].quotes == [{"original": "It takes three days.", "english": "It takes three days.",
                               "speaker": None}]
    assert (await ti.current_insight(media_id)).id == rows[1].id


async def test_a_withdrawn_consent_queues_no_summary_through_the_real_ledger(client):
    from app.services import consent_gate
    from app.services import transcript_insights as ti

    media_id = await _media(client)
    uploader = (await client.mediafile.find_unique(where={"id": media_id})).uploadedById
    await consent_gate.record_recording_consent(media_id, state="GRANTED", actor_id=uploader, client=client)
    await consent_gate.record_recording_consent(media_id, state="REFUSED", actor_id=uploader, client=client)
    assert await ti.consent_refusal(media_id) == ti.CONSENT_REFUSED
    assert await ti.enqueue(media_id) is None
    assert await client.mediaprocessingjob.count(where={"mediaFileId": media_id}) == 0
