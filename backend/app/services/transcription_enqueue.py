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
    try:
        rows = await _db(client).query_raw('SELECT "transcriptionPaused" FROM "AppSetting" WHERE "id" = $1', "singleton")
    except Exception:  # noqa: BLE001
        return True
    if not rows:
        return True
    return bool(rows[0].get("transcriptionPaused", True))


async def _open_job(media_id: str, client: Any) -> Any:
    return await _db(client).mediaprocessingjob.find_first(
        where={"mediaFileId": media_id, "jobType": TRANSCRIPTION, "status": {"in": ["QUEUED", "PROCESSING"]}}
    )


_MACHINE_ONLY = ("removedAt", "transcriptEditedAt", "transcriptEditedById")


async def _verified_twin(media: Any, client: Any) -> Any:
    sha = getattr(media, "contentSha256", None)
    if not sha or not getattr(media, "shaVerifiedAt", None):
        return None
    return await _db(client).mediafile.find_first(
        where={
            "contentSha256": sha,
            "shaVerifiedAt": {"not": None},
            "transcriptStatus": "COMPLETED",
            **dict.fromkeys(_MACHINE_ONLY),
            "id": {"not": str(media.id)},
        },
        order={"createdAt": "asc"},
    )


async def enqueue(
    media_id: str, reason: str, *, requested_by_id: str | None = None, client: Any = None
) -> dict[str, Any]:
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
            job = await db.mediaprocessingjob.find_first(where={"openKey": data["openKey"]})
            if job is None:
                raise
    await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": QUEUED})
    return {"status": QUEUED, "paused": paused, "jobId": job.id}


HELD_STATUSES = ("AWAITING_CONSENT", "REFUSED_CONSENT")


async def requeue_held(*, artisan_id: str | None = None, media_id: str | None = None,
                       requested_by_id: str | None = None, client: Any = None) -> int:
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
    from app.services import transcript_pipeline

    return await transcript_pipeline.previews(markdown_by_id, client=client)
