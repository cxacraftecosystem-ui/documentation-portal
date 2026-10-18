"""The questionnaire's ONLY door to speech-to-text (DESIGN_2026-10-18.md section 3.14, decision D19).

``enqueue(media_id, reason)`` is all the questionnaire calls. It never calls a provider. In order:

1. **the pause** (``AppSetting.transcriptionPaused``, shipped TRUE: owner correction (d), 18 Oct
   2026). While paused, a job is still written as QUEUED, so nothing is lost, and the worker never
   picks it up (``services/media_queue.process_next_media_jobs``). No provider is called and nothing
   is retried;
2. **the consent gate** (``services/consent_gate``): REFUSED -> ``REFUSED_CONSENT``, AWAITING ->
   ``AWAITING_CONSENT``, and no job. Recording that consent later re-enqueues;
3. **dedup**: a verified byte-identical copy of a file that already has a COMPLETED transcript
   copies that transcript (``SKIPPED_DUPLICATE``) instead of paying for the audio twice;
4. otherwise one QUEUED TRANSCRIPTION job, never two open ones per file.

THIS IS THE SHIM THE BUILD PLAN NAMES ("the full package is the transcription design's build; this
stream adds only ``enqueue`` and the pause check"). The shared package from
``feature/s-transcribe-hotfix`` replaces this file and keeps the same two functions.
"""

from __future__ import annotations

from typing import Any

from app.services import consent_gate

QUEUED = "QUEUED"
TRANSCRIPTION = "TRANSCRIPTION"
PAUSED_REASON = "Transcription is paused."


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def is_paused(client: Any = None) -> bool:
    """True unless the settings row exists AND says false. A missing row, a missing column or a
    failed read all read as PAUSED: the safe answer while the owner's hold stands."""
    try:
        rows = await _db(client).query_raw('SELECT "transcriptionPaused" FROM "AppSetting" WHERE "id" = $1', "singleton")
    except Exception:  # noqa: BLE001 - a settings failure must never start a provider call
        return True
    if not rows:
        return True
    return bool(rows[0].get("transcriptionPaused", True))


async def _open_job(media_id: str, client: Any) -> Any:
    return await _db(client).mediaprocessingjob.find_first(
        where={"mediaFileId": media_id, "jobType": TRANSCRIPTION, "status": {"in": ["QUEUED", "PROCESSING"]}}
    )


async def _verified_twin(media: Any, client: Any) -> Any:
    sha = getattr(media, "contentSha256", None)
    if not sha or not getattr(media, "shaVerifiedAt", None):
        return None
    return await _db(client).mediafile.find_first(
        where={
            "contentSha256": sha,
            "shaVerifiedAt": {"not": None},
            "transcriptStatus": "COMPLETED",
            "id": {"not": str(media.id)},
        },
        order={"createdAt": "asc"},
    )


async def enqueue(
    media_id: str, reason: str, *, requested_by_id: str | None = None, client: Any = None
) -> dict[str, Any]:
    """Queue one file for transcription; returns ``{"status", "paused", "jobId"}``. Never raises for
    consent or the pause, and never calls a provider."""
    db = _db(client)
    paused = await is_paused(db)
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None:
        return {"status": "FAILED", "paused": paused, "jobId": None, "reason": "media not found"}
    gate = await consent_gate.verdict(media_id, client=db)
    if gate != consent_gate.ALLOW:
        status = consent_gate.TRANSCRIPT_STATUS_FOR[gate]
        await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": status})
        return {"status": status, "paused": paused, "jobId": None}
    twin = await _verified_twin(media, db)
    if twin is not None:
        await db.mediafile.update(
            where={"id": media_id},
            data={
                "transcriptStatus": "SKIPPED_DUPLICATE",
                "transcriptText": getattr(twin, "transcriptText", None),
                "transcriptSummary": getattr(twin, "transcriptSummary", None),
            },
        )
        return {"status": "SKIPPED_DUPLICATE", "paused": paused, "jobId": None, "copiedFrom": twin.id}
    job = await _open_job(media_id, db)
    if job is None:
        data: dict[str, Any] = {
            "jobType": TRANSCRIPTION,
            "status": QUEUED,
            "priority": 50,
            "mediaFileId": media_id,
        }
        if requested_by_id:
            data["requestedById"] = requested_by_id
        job = await db.mediaprocessingjob.create(data=data)
    await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": QUEUED})
    return {"status": QUEUED, "paused": paused, "jobId": job.id}
