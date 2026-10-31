"""Transcripts in View data / Download data (0.0.6) against a real, migrated Postgres.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (``shared/transcription.sha256``).
It runs in each portal's database CI job and skips anywhere else. Nothing calls a provider: turns
are stored from the recorded Scribe fixture, and the summary and glossary rows are written directly.

What it holds: a batch is read in a FIXED number of statements whatever it holds (2 recordings or
10); turns carry mapped speaker names, times and English; a recording whose consent was refused
exports no words; the list preview uses the mapped names; the filters narrow by recording fields;
the streamed CSV has every turn and never the cost columns for a non-administrator.
"""

from __future__ import annotations

import asyncio
import csv
import io
import json
import os
import socket
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import pytest

_DSN = os.environ.get("DATABASE_URL", "")
_PARTS = urlsplit(_DSN) if _DSN else None
_LOCAL = bool(_PARTS and _PARTS.hostname in {"localhost", "127.0.0.1", "::1"})


def _listening() -> bool:
    if not _LOCAL or _PARTS is None:
        return False
    try:
        with socket.create_connection((_PARTS.hostname, _PARTS.port or 5432), timeout=1.0):
            return True
    except OSError:
        return False


pytestmark = pytest.mark.skipif(
    not _listening(),
    reason="needs an exported DATABASE_URL naming a running Postgres on this machine (never backend/.env)",
)

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "transcript"


class Counting:
    """The Prisma client, counting the raw statements sent through it."""

    def __init__(self, inner: Any) -> None:
        self._inner = inner
        self.reads = 0

    async def query_raw(self, *args: Any, **kwargs: Any) -> Any:
        self.reads += 1
        return await self._inner.query_raw(*args, **kwargs)

    async def execute_raw(self, *args: Any, **kwargs: Any) -> Any:
        self.reads += 1
        return await self._inner.execute_raw(*args, **kwargs)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._inner, name)


def _scribe_result() -> dict[str, Any]:
    from app.transcription.providers import elevenlabs

    payload = json.loads((FIXTURES / "scribe_v2.json").read_text(encoding="utf-8"))
    return {
        "status": "COMPLETED", "text": payload["text"], "provider": "elevenlabs", "model": "scribe_v2",
        "languageCode": payload["language_code"], "audioMs": 25_000, "words": elevenlabs.parse_words(payload),
    }


def _run(body: Any) -> Any:
    from app.core.db import db

    async def wrapper() -> Any:
        await db.connect()
        try:
            return await body()
        finally:
            await db.disconnect()

    return asyncio.run(wrapper())


async def _seed(count: int, tag: str) -> dict[str, Any]:
    """``count`` transcribed recordings by one researcher: turns from the fixture, the first speaker
    named, a summary, a quote and a glossary term on each."""
    from app.core.db import db
    from app.services import transcript_pipeline as pipeline
    from prisma import Json

    researcher = await db.user.create(data={"email": f"tx-{tag}@example.org", "name": "Nandini", "role": "RESEARCHER"})
    term = await db.transcriptglossaryterm.create(
        data={"scopeKey": f"t-{tag}", "termKey": f"dabu-{tag}", "term": "dabu", "gloss": "mud-resist printing", "occurrences": count}
    )
    media = []
    for n in range(count):
        m = await db.mediafile.create(
            data={
                "originalFilename": f"interview-{n}.m4a", "mediaType": "AUDIO", "mimeType": "audio/mp4", "sizeBytes": 1024,
                "bucket": "ci", "objectKey": f"media/{researcher.id}/{tag}{n}/interview.m4a",
                "transcriptText": "**Speaker 1:** words", "transcriptStatus": "COMPLETED",
                "uploadedBy": {"connect": {"id": researcher.id}},
            }
        )
        version = await pipeline.store_transcription(m, _scribe_result(), keyterm_count=0)
        assert version is not None
        await db.transcriptspeaker.create(data={"mediaFileId": m.id, "speakerKey": "speaker_0", "name": "Asha", "role": "ARTISAN"})
        await db.transcriptinsight.create(
            data={
                "mediaFileId": m.id, "version": 1, "sourceHash": "x", "summaryLines": [f"Summary {n}"],
                "quotes": Json([{"original": "मिट्टी और गोंद", "english": "clay and gum", "speaker": "Asha"}]),
                "translationStatus": "COMPLETED", "provider": "gemini", "modelId": "gemini-3.8-flash", "promptVersion": "p",
                "generated": Json({}), "generatedAt": version.createdAt,
            }
        )
        await db.transcriptglossarysighting.create(data={"termId": term.id, "mediaFileId": m.id})
        media.append(m)
    return {"researcher": researcher, "media": media, "term": term}


async def _cleanup(seed: dict[str, Any]) -> None:
    from app.core.db import db

    ids = [m.id for m in seed["media"]]
    await db.execute_raw('DELETE FROM "Consent" WHERE "subjectId" = ANY($1::text[])', ids)
    await db.mediafile.delete_many(where={"id": {"in": ids}})
    await db.transcriptglossaryterm.delete_many(where={"id": seed["term"].id})
    await db.user.delete_many(where={"id": seed["researcher"].id})


async def _refuse(media_id: str, actor_id: str) -> None:
    from app.core.db import db
    from app.transcription import ledger

    await ledger.append(db, subject_type=ledger.RECORDING, subject_id=media_id, state=ledger.REFUSED,
                        basis=ledger.VERBAL_RECORDED, actor_id=actor_id)


def test_a_batch_costs_the_same_reads_for_two_recordings_or_ten() -> None:
    from app.core.db import db
    from app.services import transcript_export as tx

    async def body() -> None:
        tag = uuid4().hex[:8]
        seed = await _seed(10, tag)
        try:
            ids = [m.id for m in seed["media"]]
            few, many = Counting(db), Counting(db)
            small = await tx.load(ids[:2], client=few)
            large = await tx.load(ids, client=many)
            assert len(small) == 2 and len(large) == 10
            assert few.reads == many.reads, f"{few.reads} reads for 2 recordings, {many.reads} for 10: load is batched"
            assert many.reads <= 20, many.reads

            rec = large[ids[0]]
            assert rec["source"] == "TURNS" and rec["withheld"] is None and rec["timed"]
            assert rec["turns"] and all(t["start"] and t["speaker"] for t in rec["turns"])
            assert {s["label"] for s in rec["speakers"]} >= {"Asha"}, "the mapped name is the label"
            assert rec["turns"][0]["colorKey"].startswith("s")
            assert rec["summary"] == ["Summary 0"] and rec["quotes"][0]["english"] == "clay and gum"
            assert rec["glossary"] == [{"term": "dabu", "gloss": "mud-resist printing", "craft": None}]
            assert "costUsd" not in rec and "models" not in rec, "cost and models are for administrators"
            admin = await tx.load(ids[:1], sees_spend=True)
            assert admin[ids[0]]["models"]["stt"] == "scribe_v2" and "costUsd" in admin[ids[0]]
        finally:
            await _cleanup(seed)

    _run(body)


def test_a_refused_recording_exports_no_words_anywhere() -> None:
    from app.services import transcript_export as tx, transcript_pipeline

    async def body() -> None:
        tag = uuid4().hex[:8]
        seed = await _seed(2, tag)
        try:
            kept, refused = (m.id for m in seed["media"])
            await _refuse(refused, seed["researcher"].id)
            loaded = await tx.load([kept, refused])
            assert loaded[kept]["withheld"] is None and loaded[kept]["turns"]
            gone = loaded[refused]
            assert gone["withheld"] == tx.WITHHELD
            assert gone["turns"] == gone["summary"] == gone["quotes"] == gone["glossary"] == []

            plan = await tx.bundle_plan([kept, refused])
            assert plan[kept] == {"withheld": False, "turns": True, "timed": True}
            assert plan[refused]["withheld"] is True

            files = [{"path": "a.md", "content": "x", "mediaId": refused, "_tx": (refused, "legacy")},
                     {"path": "a.docx", "url": "/api/x", "mediaId": refused, "_tx": (refused, "pipeline", False)}]
            await tx.finalize_manifest(files, pipeline_on=True)
            assert files == []

            rows = [SimpleNamespace(id=kept, transcriptText="t"), SimpleNamespace(id=refused, transcriptText="t")]
            assert await tx.withhold_refused(rows) == {refused}
            assert rows[1].transcriptText is None and rows[0].transcriptText == "t"

            # The list preview: plain text under the MAPPED name.
            previews = await transcript_pipeline.previews({kept: "**Speaker 1:** words"})
            assert previews[kept]["lines"][0]["speaker"] in {"Asha", "Speaker 2"}
            assert "*" not in previews[kept]["text"]
        finally:
            await _cleanup(seed)

    _run(body)


def test_the_filters_and_the_streamed_csv() -> None:
    from app.services import transcript_export as tx

    async def body() -> None:
        tag = uuid4().hex[:8]
        seed = await _seed(3, tag)
        try:
            ids = {m.id for m in seed["media"]}
            mine = tx.Filters(researcher=[seed["researcher"].id])
            got = [r["mediaId"] async for r in tx.iter_recordings(mine, sees_spend=False)]
            assert set(got) == ids and got == sorted(got), "keyset order, every recording once"
            by_speaker = tx.Filters(researcher=[seed["researcher"].id], speaker="ash")
            assert {r["mediaId"] async for r in tx.iter_recordings(by_speaker, sees_spend=False)} == ids
            nobody = tx.Filters(researcher=[seed["researcher"].id], speaker="zz-nobody")
            assert [r async for r in tx.iter_recordings(nobody, sees_spend=False)] == []
            hindi = tx.Filters(researcher=[seed["researcher"].id], language=["hi"])
            assert {r["mediaId"] async for r in tx.iter_recordings(hindi, sees_spend=False)} == ids
            tamil = tx.Filters(researcher=[seed["researcher"].id], language=["ta"])
            assert [r async for r in tx.iter_recordings(tamil, sees_spend=False)] == []

            async def visible(page: list[str]) -> set[str]:
                return set(page[:1])

            assert len([r async for r in tx.iter_recordings(mine, sees_spend=False, visible=visible)]) == 1

            out = io.StringIO()
            writer = csv.writer(out)
            writer.writerow(tx.TURN_COLUMNS)
            count = 0
            async for rec in tx.iter_recordings(mine, sees_spend=False):
                for row in tx.turn_rows(rec):
                    writer.writerow([row[c] for c in tx.TURN_COLUMNS])
                    count += 1
            lines = list(csv.reader(io.StringIO(out.getvalue())))
            assert len(lines) == count + 1 and count >= 3
            assert "Cost (USD)" not in tx.recording_columns(False)
        finally:
            await _cleanup(seed)

    _run(body)
