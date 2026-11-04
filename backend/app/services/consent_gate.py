from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime, timedelta, timezone
from typing import Any

from app.transcription import ledger
from app.transcription.ledger import ALLOW, AWAITING, REFUSED, Verdict

__all__ = ["ALLOW", "AWAITING", "REFUSED", "Verdict"]

ATTESTATION_CUTOFF = datetime(2026, 10, 18, 23, 59, 59, 999000, tzinfo=timezone(timedelta(hours=5, minutes=30)))
ATTESTATION_RECORDED_AT = datetime(2026, 10, 18, 0, 0, tzinfo=timezone(timedelta(hours=5, minutes=30)))

PURPOSE = ledger.EXTERNAL_PROCESSING
SOURCE_RESEARCHER = ledger.SOURCE_RESEARCHER
SOURCE_OWNER = ledger.SOURCE_OWNER
TRANSCRIPT_STATUS_FOR = {AWAITING: "AWAITING_CONSENT", REFUSED: "REFUSED_CONSENT"}


def decide(recording: Any, subjects: Iterable[Any]) -> Verdict:
    return ledger.decide(recording, subjects)


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def current_states(
    subject_type: str, subject_ids: list[str], *, purpose: str = PURPOSE, client: Any = None
) -> dict[str, dict[str, Any]]:
    ids = list(dict.fromkeys(str(i) for i in subject_ids if i))
    if not ids:
        return {}
    return await ledger.current_states(_db(client), subject_type, ids, purpose=purpose)


async def _media_subjects(media_id: str, client: Any) -> list[str]:
    db = _db(client)
    rows = await db.query_raw(
        'SELECT s."artisanId" FROM "QuestionnaireRecordingSubject" s '
        'JOIN "QuestionnaireRecording" r ON r."id" = s."recordingId" WHERE r."mediaFileId" = $1',
        media_id,
    )
    if rows:
        return [str(row["artisanId"]) for row in rows]
    has_recording = await db.query_raw('SELECT 1 FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1', media_id)
    if has_recording:
        return []
    rows = await db.query_raw(
        'SELECT m."artisanId" AS a FROM "MediaFile" m WHERE m."id" = $1 AND m."artisanId" IS NOT NULL '
        'UNION SELECT qa."artisanId" FROM "MediaFile" m JOIN "QuestionnaireInterviewArtisan" qa '
        '  ON qa."interviewId" = m."questionnaireInterviewId" WHERE m."id" = $1 '
        'UNION SELECT p."artisanId" FROM "MediaFile" m JOIN "ProductDocumentation" p ON p."id" = m."productId" '
        '  WHERE m."id" = $1 AND p."artisanId" IS NOT NULL '
        'UNION SELECT t."artisanId" FROM "MediaFile" m JOIN "ToolArtisan" t ON t."toolId" = m."toolId" WHERE m."id" = $1',
        media_id,
    )
    return [str(row["a"]) for row in rows if row.get("a")]


async def _media_subjects_many(media_ids: list[str], client: Any) -> dict[str, list[str]]:
    db = _db(client)
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))
    if not ids:
        return {}
    out: dict[str, list[str]] = {}
    rows = await db.query_raw(
        'SELECT r."mediaFileId" AS m, s."artisanId" FROM "QuestionnaireRecording" r '
        'LEFT JOIN "QuestionnaireRecordingSubject" s ON s."recordingId" = r."id" WHERE r."mediaFileId" = ANY($1::text[])',
        ids,
    )
    for row in rows:
        subjects = out.setdefault(str(row["m"]), [])
        if row.get("artisanId"):
            subjects.append(str(row["artisanId"]))
    rest = [media_id for media_id in ids if media_id not in out]
    if rest:
        linked = await db.query_raw(
            'SELECT m."id" AS m, m."artisanId" AS a FROM "MediaFile" m WHERE m."id" = ANY($1::text[]) AND m."artisanId" IS NOT NULL '
            'UNION SELECT m."id", qa."artisanId" FROM "MediaFile" m JOIN "QuestionnaireInterviewArtisan" qa '
            '  ON qa."interviewId" = m."questionnaireInterviewId" WHERE m."id" = ANY($1::text[]) '
            'UNION SELECT m."id", p."artisanId" FROM "MediaFile" m JOIN "ProductDocumentation" p ON p."id" = m."productId" '
            '  WHERE m."id" = ANY($1::text[]) AND p."artisanId" IS NOT NULL '
            'UNION SELECT m."id", t."artisanId" FROM "MediaFile" m JOIN "ToolArtisan" t ON t."toolId" = m."toolId" '
            '  WHERE m."id" = ANY($1::text[])',
            rest,
        )
        for media_id in rest:
            out[media_id] = []
        for row in linked:
            if row.get("a"):
                out[str(row["m"])].append(str(row["a"]))
    return out


async def verdict_details(media_ids: Iterable[str], *, client: Any = None) -> dict[str, dict[str, Any]]:
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))
    if not ids:
        return {}
    grants = await current_states(ledger.RECORDING, ids, client=client)
    subjects = await _media_subjects_many(ids, client)
    everyone = [artisan_id for media_id in ids for artisan_id in subjects.get(media_id, [])]
    states = await current_states(ledger.ARTISAN, everyone, client=client)
    out: dict[str, dict[str, Any]] = {}
    for media_id in ids:
        grant = grants.get(media_id)
        rows = [states.get(artisan_id) for artisan_id in subjects.get(media_id, [])]
        result = decide(grant, rows)
        if result == ALLOW and (grant or {}).get("state") == ledger.GRANTED:
            out[media_id] = {"verdict": ALLOW, "source": (grant or {}).get("source") or SOURCE_OWNER}
        else:
            answered = grant is not None or any(rows)
            out[media_id] = {"verdict": result, "source": SOURCE_RESEARCHER if answered else None}
    return out


async def verdict(media: Any, *, client: Any = None) -> Verdict:
    media_id = media if isinstance(media, str) else str(getattr(media, "id", None) or media["id"])
    return (await _decided(media_id, client))[0]


async def _decided(media_id: str, client: Any) -> tuple[Verdict, dict[str, Any] | None, bool]:
    grant = (await current_states(ledger.RECORDING, [media_id], client=client)).get(media_id)
    subjects = await _media_subjects(media_id, client)
    states = await current_states(ledger.ARTISAN, subjects, client=client)
    rows = [states.get(artisan_id) for artisan_id in subjects]
    return decide(grant, rows), grant, grant is not None or any(rows)


async def artisans_verdict(artisan_ids: Iterable[str], *, client: Any = None) -> Verdict:
    ids = list(dict.fromkeys(str(i) for i in artisan_ids if i))
    states = await current_states(ledger.ARTISAN, ids, client=client)
    return decide(None, [states.get(artisan_id) for artisan_id in ids])


async def interview_verdict(interview_id: str, *, client: Any = None) -> Verdict:
    rows = await _db(client).query_raw(
        'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', interview_id
    )
    return await artisans_verdict([str(row["artisanId"]) for row in rows], client=client)


async def verdict_detail(media_id: str, *, client: Any = None) -> dict[str, Any]:
    result, grant, answered = await _decided(media_id, client)
    if result == ALLOW and (grant or {}).get("state") == ledger.GRANTED:
        return {"verdict": ALLOW, "source": (grant or {}).get("source") or SOURCE_OWNER}
    return {"verdict": result, "source": SOURCE_RESEARCHER if answered else None}


class DocumentationConsentGate:
    def __init__(self, client: Any = None) -> None:
        self._client = client

    async def verdict(self, media_id: str) -> Verdict:
        return await verdict(media_id, client=self._client)


async def record_artisan_consent(
    artisan_id: str,
    *,
    state: str,
    basis: str,
    actor_id: str,
    purpose: str = PURPOSE,
    note: str | None = None,
    evidence_media_id: str | None = None,
    recorded_at: datetime | None = None,
    client: Any = None,
) -> dict[str, Any]:
    written = await ledger.append(
        _db(client), subject_type=ledger.ARTISAN, subject_id=artisan_id, state=state, basis=basis,
        actor_id=actor_id, purpose=purpose, note=note, evidence_media_id=evidence_media_id,
        recorded_at=recorded_at,
    )
    return written or {}


async def record_recording_consent(
    media_id: str,
    *,
    state: str,
    actor_id: str,
    basis: str = ledger.VERBAL_RECORDED,
    note: str | None = None,
    recorded_at: datetime | None = None,
    client: Any = None,
) -> dict[str, Any]:
    written = await ledger.append(
        _db(client), subject_type=ledger.RECORDING, subject_id=media_id, state=state, basis=basis,
        actor_id=actor_id, note=note, recorded_at=recorded_at,
    )
    return written or {}
