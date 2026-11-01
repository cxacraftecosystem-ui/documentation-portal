"""The questionnaire's ONLY door to speech-to-text (DESIGN_2026-10-18.md section 3.14, decision D19).

``enqueue(media_id, reason)`` is all the questionnaire calls. It never calls a provider. In order:

1. **the pause** (``AppSetting.transcriptionPaused``, shipped TRUE: owner correction (d), 18 Oct
   2026). While paused, a job is still written as QUEUED, so nothing is lost, and the worker never
   picks it up (``services/media_queue.process_next_media_jobs``). No provider is called and nothing
   is retried;
2. **the consent gate** (``services/consent_gate``): REFUSED -> ``REFUSED_CONSENT`` and no job.
   Consent is assumed by default (19 Oct 2026), so no answer is sent; a later grant re-enqueues;
3. **dedup**: a verified byte-identical copy of a file that already has a COMPLETED transcript
   copies that transcript (``SKIPPED_DUPLICATE``) instead of paying for the audio twice;
4. otherwise one QUEUED TRANSCRIPTION job, never two open ones per file.

THE PORTAL SIDE OF THE SHARED PACKAGE. ``app/transcription/`` is the shared transcription service,
byte-identical in both portals, so this door lives here instead: the pause it reads is the same
``AppSetting.transcriptionPaused`` the worker reads (``app_settings.transcription_paused``), and the
job it writes carries the same ``openKey`` (``transcription_store.open_key``), so a clip never has two
open jobs whichever path queued it.
"""

from __future__ import annotations

from typing import Any

from prisma.errors import UniqueViolationError

from app.services import consent_gate
from app.services.transcription_store import open_key

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
        data["openKey"] = open_key(media_id, TRANSCRIPTION)
        try:
            job = await db.mediaprocessingjob.create(data=data)
        except UniqueViolationError:
            # Another path queued it a moment ago: that open job is this file's job.
            job = await db.mediaprocessingjob.find_first(where={"openKey": data["openKey"]})
            if job is None:
                raise
    await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": QUEUED})
    return {"status": QUEUED, "paused": paused, "jobId": job.id}


#: A recording held back by consent: the statuses the gate writes, and the queue's older note.
HELD_STATUSES = ("AWAITING_CONSENT", "REFUSED_CONSENT")


async def requeue_held(*, artisan_id: str | None = None, media_id: str | None = None,
                       requested_by_id: str | None = None, client: Any = None) -> int:
    """After a GRANTED answer, queue again every recording that consent held back (audit M7): the
    artisan's recordings by EVERY link the gate reads (a take's subject, the file's artisan, an
    interview's participant, a product's or tool's artisan), or the one recording. ``enqueue`` asks
    the gate again, so anyone else's refusal still holds, and the pause still applies. Returns how
    many were queued."""
    from app.transcription.consent import CONSENT_REFUSED_MESSAGE

    db = _db(client)
    held = (
        '(m."transcriptStatus" = ANY($2::text[]) OR (m."transcriptStatus" = \'FAILED\' AND m."transcriptError" = $3)) '
        'AND m."removedAt" IS NULL AND m."mediaType" IN (\'AUDIO\', \'VIDEO\')'
    )
    if media_id:
        rows = await db.query_raw(f'SELECT m."id" FROM "MediaFile" m WHERE m."id" = $1 AND {held}', media_id,
                                  list(HELD_STATUSES), CONSENT_REFUSED_MESSAGE)
    elif artisan_id:
        rows = await db.query_raw(
            'SELECT DISTINCT m."id" FROM "MediaFile" m WHERE ' + held + ' AND ('
            'm."artisanId" = $1 '
            'OR EXISTS (SELECT 1 FROM "QuestionnaireRecording" r JOIN "QuestionnaireRecordingSubject" s ON s."recordingId" = r."id" '
            '           WHERE r."mediaFileId" = m."id" AND s."artisanId" = $1) '
            'OR (NOT EXISTS (SELECT 1 FROM "QuestionnaireRecording" r WHERE r."mediaFileId" = m."id") AND ('
            '    EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" qa WHERE qa."interviewId" = m."questionnaireInterviewId" AND qa."artisanId" = $1) '
            '    OR EXISTS (SELECT 1 FROM "ProductDocumentation" p WHERE p."id" = m."productId" AND p."artisanId" = $1) '
            '    OR EXISTS (SELECT 1 FROM "ToolArtisan" t WHERE t."toolId" = m."toolId" AND t."artisanId" = $1))))',
            artisan_id, list(HELD_STATUSES), CONSENT_REFUSED_MESSAGE,
        )
    else:
        return 0
    for row in rows:
        await enqueue(str(row["id"]), "consent recorded", requested_by_id=requested_by_id, client=db)
    return len(rows)


async def previews(markdown_by_id: dict[str, str | None], *, client: Any = None) -> dict[str, Any]:
    """List previews for the questionnaire's recordings (contract ``TranscriptPreview``): plain text
    with speaker labels, from the turns when there are any. Two reads for the whole page."""
    from app.services import transcript_pipeline

    return await transcript_pipeline.previews(markdown_by_id, client=client)
