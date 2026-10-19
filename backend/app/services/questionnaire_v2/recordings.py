"""Recordings: server-known uploads, the atomic /complete, and every later change (DESIGN 3.3,
3.8, 3.9, 5.3 and 5.5).

* Presign writes a ``MediaUploadIntent`` keyed on ``clientClipKey``. A re-presign signs a fresh URL
  for the SAME ``objectKey`` and reuses an open multipart upload. The server mints the S3 leaf
  (``naming.recording_filename``); the client's file name is never used.
* ``/complete`` is ONE transaction under sorted advisory locks, idempotent on ``clientClipKey`` then
  ``objectKey``. It never refuses a recording because of coverage: it files it, and flags it only
  when it is truly ambiguous (CONCURRENT -> NEEDS_DECISION). A subject who is not a participant is
  added as one. After commit it enqueues the sha check and calls ``transcription.enqueue``, which
  writes a paused QUEUED job and calls no provider.
* Nothing is deleted implicitly: removal is soft and restorable, and every change is an event.
"""

from __future__ import annotations

import asyncio
import base64
import json
import mimetypes
from collections import defaultdict
from datetime import UTC, datetime, time, timedelta
from typing import Any
from uuid import uuid4

from app import shared_vocab
from app.services import transcription_enqueue as transcription
from app.core.deps import can_review, has_rank, is_admin
from app.services import consent_gate, naming
from app.services.questionnaire_v2 import coverage, sessions, titles
from app.services.questionnaire_v2.common import (
    IST,
    aware,
    as_date,
    enum_text,
    fail,
    get,
    iso,
    naive_utc,
    now,
)
from app.services.questionnaire_v2.locks import lock_subjects

MULTIPART_THRESHOLD = 5 * 1024 * 1024
PART_SIZE = 8 * 1024 * 1024
URL_TTL = timedelta(minutes=15)
INTENT_TTL = timedelta(days=30)
CONCURRENT_WINDOW = timedelta(hours=2)
FUTURE_LIMIT = timedelta(hours=24)
FRESH_OFFSET = timedelta(days=7)
ESTIMATED = set(shared_vocab.ESTIMATED_CAPTURED_AT_SOURCES)
TX_TIMEOUT = timedelta(seconds=45)
TX_MAX_WAIT = timedelta(seconds=15)


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


def _media_type(content_type: str) -> str:
    base = (content_type or "").split(";")[0].strip().lower()
    return "VIDEO" if base.startswith("video/") else "AUDIO"


def _ext_mime(extension: str, content_type: str | None) -> str:
    return content_type or mimetypes.types_map.get(f".{extension.lower()}") or "application/octet-stream"


def _b64(sha_hex: str | None) -> str | None:
    if not sha_hex:
        return None
    return base64.b64encode(bytes.fromhex(sha_hex)).decode()


# ── time (DESIGN 3.9) ──────────────────────────────────────────────────────────────────────────
def correct_capture_time(
    *, device_captured_at: Any, clock_offset_ms: int | None, source: str | None, session_date: Any,
    server_now: datetime | None = None, offset_age: timedelta | None = None,
) -> tuple[datetime, str, bool]:
    """``(capturedAt, capturedAtSource, clamped)``.

    * DEVICE with an offset: ``deviceCapturedAt + clockOffsetMs``. An offset older than 7 days still
      corrects the time, but the source becomes UPLOAD_TIME (estimated).
    * DEVICE with no offset reported: the device time, estimated (UPLOAD_TIME).
    * FILE_METADATA: the file's own time.
    * SESSION_DATE, or no time at all for a picked file: the session date at 12:00 IST.
    * Nothing usable: the upload time.
    * More than 24 h in the future: clamped to server now and flagged (never rejected).
    """
    current = server_now or now()
    device = aware(device_captured_at)
    source = (source or "DEVICE").upper()
    if source in ("DEVICE", "UPLOAD_TIME") and device is not None:
        if clock_offset_ms is not None:
            captured = device + timedelta(milliseconds=int(clock_offset_ms))
            stale = offset_age is not None and offset_age > FRESH_OFFSET
            source = "UPLOAD_TIME" if stale else "DEVICE"
        else:
            captured, source = device, "UPLOAD_TIME"
    elif source == "FILE_METADATA" and device is not None:
        captured = device + timedelta(milliseconds=int(clock_offset_ms or 0))
    elif source == "SESSION_DATE" or (device is None and session_date is not None):
        day = as_date(session_date) or current.astimezone(IST).date()
        captured, source = datetime.combine(day, time(12, 0), tzinfo=IST).astimezone(UTC), "SESSION_DATE"
    else:
        captured, source = current, "UPLOAD_TIME"
    if captured > current + FUTURE_LIMIT:
        return current, "UPLOAD_TIME", True
    return captured, source, False


# ── names ──────────────────────────────────────────────────────────────────────────────────────
async def _target_refs(client: Any, rows: list[dict[str, Any]]) -> list[naming.TargetRef]:
    refs = []
    for t in rows:
        if t.get("questionLineageId"):
            refs.append(naming.TargetRef(sectionCode=t.get("sectionCode") or "", displayCode=t.get("displayCode")))
        else:
            refs.append(naming.TargetRef(sectionCode=t.get("sectionCode") or ""))
    return refs


async def _part_number(client: Any, recording_id: str | None) -> int | None:
    depth = 1
    seen = set()
    current = recording_id
    while current and current not in seen:
        seen.add(current)
        rows = await client.query_raw('SELECT "continuationOfId" FROM "QuestionnaireRecording" WHERE "id" = $1', current)
        current = rows[0]["continuationOfId"] if rows else None
        if current:
            depth += 1
    return depth if depth >= 2 else None


# ── presign, multipart, resume ─────────────────────────────────────────────────────────────────
async def _session_for_upload(client: Any, session_id: str, user: Any) -> dict[str, Any]:
    row = await sessions.load_session_row(client, session_id)
    if row is None:
        fail("SESSION_UNKNOWN")
    if str(enum_text(get(user, "role"))) == "CROWDSOURCE_VOLUNTEER" and not await sessions.can_work_in(client, user, row):
        fail("FORBIDDEN", "Volunteers record into sessions they are added to.")
    return row


async def _mint_object_key(client: Any, payload: Any, user: Any, session: dict[str, Any]) -> str:
    questionnaire_id = str(session["questionnaireId"])
    targets = await coverage.resolve_targets(client, questionnaire_id, payload.targets)
    captured, source, _clamped = correct_capture_time(
        device_captured_at=payload.deviceCapturedAt, clock_offset_ms=payload.clockOffsetMs,
        source="DEVICE" if payload.deviceCapturedAt else "UPLOAD_TIME", session_date=session.get("sessionDate"),
    )
    roster = len(payload.subjectArtisanIds) or len(await sessions.participant_ids(client, str(session["id"])))
    position = None
    if roster == 1 and targets:
        subject = payload.subjectArtisanIds[0] if payload.subjectArtisanIds else (await sessions.participant_ids(client, str(session["id"])) or [None])[0]
        if subject:
            prior = await coverage.take_rows(client, questionnaire_id, artisan_ids=[subject], target_keys=[targets[0]["targetKey"]])
            position = len({str(p["rootRecordingId"]) for p in prior}) + 1
    leaf = naming.recording_filename(
        await titles.title_input(client, str(session["id"])), captured_at=captured, captured_at_source=source,
        targets=await _target_refs(client, targets), ext=payload.extension, position=position, roster_count=roster,
    )
    return f"media/{user.id}/{uuid4().hex}/{leaf}"


async def _intent_for(client: Any, payload: Any, user: Any) -> dict[str, Any]:
    """Create or reuse the intent for this clientClipKey (the idempotency of presign)."""
    session = await _session_for_upload(client, payload.sessionId, user)
    rows = await client.query_raw('SELECT * FROM "MediaUploadIntent" WHERE "clientClipKey" = $1', payload.clientClipKey)
    if rows:
        intent = rows[0]
        if str(intent["uploaderId"]) != str(user.id):
            fail("FORBIDDEN", "This upload belongs to another user.")
        return intent
    object_key = await _mint_object_key(client, payload, user, session)
    meta = {
        "targets": [t.model_dump() for t in payload.targets],
        "subjectArtisanIds": payload.subjectArtisanIds,
        "deviceCapturedAt": iso(payload.deviceCapturedAt),
        "clockOffsetMs": payload.clockOffsetMs,
        "extension": payload.extension,
    }
    await client.execute_raw(
        'INSERT INTO "MediaUploadIntent" ("id", "objectKey", "clientClipKey", "sessionId", "uploaderId", "sizeBytes", '
        '"contentType", "contentSha256", "partSize", "expiresAt", "meta") VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, '
        '$10::timestamp, $11::jsonb) ON CONFLICT ("clientClipKey") DO NOTHING',
        uuid4().hex, object_key, payload.clientClipKey, payload.sessionId, user.id, int(payload.sizeBytes),
        _ext_mime(payload.extension, payload.contentType), (payload.contentSha256 or "").lower() or None,
        PART_SIZE if payload.sizeBytes > MULTIPART_THRESHOLD else None, naive_utc(now() + INTENT_TTL), json.dumps(meta),
    )
    rows = await client.query_raw('SELECT * FROM "MediaUploadIntent" WHERE "clientClipKey" = $1', payload.clientClipKey)
    return rows[0]


async def presign(payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.services import s3

    db = _db(client)
    intent = await _intent_for(db, payload, user)
    content_type = str(intent["contentType"])
    sha_b64 = _b64(intent.get("contentSha256"))
    url = await asyncio.to_thread(s3.presign_put_url_checksum, str(intent["objectKey"]), content_type, sha_b64, int(URL_TTL.total_seconds()))
    headers = {"Content-Type": content_type}
    if sha_b64:
        headers["x-amz-checksum-sha256"] = sha_b64
    return {"objectKey": str(intent["objectKey"]), "url": url, "headers": headers, "expiresAt": iso(now() + URL_TTL)}


async def multipart_create(payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.services import s3

    db = _db(client)
    intent = await _intent_for(db, payload, user)
    upload_id = intent.get("multipartUploadId")
    parts: list[dict[str, Any]] = []
    if upload_id and not intent.get("closedAt"):
        parts = await asyncio.to_thread(s3.list_uploaded_parts, str(intent["objectKey"]), str(upload_id))
    else:
        upload_id = await asyncio.to_thread(s3.create_multipart_upload, str(intent["objectKey"]), str(intent["contentType"]))
        await db.execute_raw(
            'UPDATE "MediaUploadIntent" SET "multipartUploadId" = $1, "partSize" = $2 WHERE "id" = $3',
            upload_id, PART_SIZE, str(intent["id"]),
        )
    return {"objectKey": str(intent["objectKey"]), "uploadId": str(upload_id), "partSize": int(intent.get("partSize") or PART_SIZE), "partsDone": parts}


async def upload_state(client_clip_key: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.services import s3

    db = _db(client)
    rows = await db.query_raw('SELECT * FROM "MediaUploadIntent" WHERE "clientClipKey" = $1', client_clip_key)
    if not rows:
        fail("NOT_FOUND", "No upload is known for that clip.")
    intent = rows[0]
    if str(intent["uploaderId"]) != str(user.id) and not is_admin(user):
        fail("FORBIDDEN", "This upload belongs to another user.")
    parts: list[dict[str, Any]] = []
    if intent.get("multipartUploadId") and not intent.get("closedAt"):
        parts = await asyncio.to_thread(s3.list_uploaded_parts, str(intent["objectKey"]), str(intent["multipartUploadId"]))
    return {
        "objectKey": str(intent["objectKey"]), "multipartUploadId": intent.get("multipartUploadId"),
        "partSize": int(intent.get("partSize") or PART_SIZE), "partsDone": parts, "closed": intent.get("closedAt") is not None,
    }


# ── /complete ──────────────────────────────────────────────────────────────────────────────────
async def _existing_by_clip(client: Any, client_clip_key: str) -> str | None:
    rows = await client.query_raw('SELECT "id" FROM "QuestionnaireRecording" WHERE "clientClipKey" = $1', client_clip_key)
    return str(rows[0]["id"]) if rows else None


async def _flag_subjects(
    tx: Any, *, questionnaire_id: str, session: dict[str, Any], recording_id: str, captured_at: datetime,
    targets: list[dict[str, Any]], answered: list[dict[str, Any]], acked: set[str], continuation_of: str | None,
    user_id: str, roster: int,
) -> tuple[str, str | None, dict[str, dict[str, Any]]]:
    """DESIGN 3.3 rule 3, per ANSWERED subject: ``(filing, decisionKind, {artisanId: {flag, repeatReason}})``."""
    keys = {t["targetKey"] for t in targets} | {coverage.section_key(t["parentSectionId"]) for t in targets if t.get("parentSectionId")}
    rows = await coverage.take_rows(tx, questionnaire_id, artisan_ids=[s["artisanId"] for s in answered], target_keys=sorted(keys))
    rows = [r for r in rows if str(r["recordingId"]) != recording_id]
    session_id = str(session["id"])
    session_day = as_date(session.get("sessionDate"))
    participants = set(await sessions.participant_ids(tx, session_id))
    filing, decision = "FILED", None
    out: dict[str, dict[str, Any]] = {}
    for subject in answered:
        artisan_id = subject["artisanId"]
        prior = [r for r in rows if str(r["artisanId"]) == artisan_id
                 and str(r["recordingId"]) != continuation_of and str(r["rootRecordingId"]) != continuation_of]
        unseen = [r for r in prior if str(r["recordingId"]) not in acked]
        reason = subject.get("repeatReason")
        flag = None
        if unseen:
            concurrent = []
            for r in unseen:
                same_target = str(r["targetKey"]) in {t["targetKey"] for t in targets}
                same_sitting = str(r["sessionId"]) == session_id
                if not same_sitting and session_day and as_date(r.get("sessionDate")) == session_day:
                    other = set(await sessions.participant_ids(tx, str(r["sessionId"])))
                    same_sitting = bool(other & participants)
                close = abs(aware(r["capturedAt"]) - captured_at) <= CONCURRENT_WINDOW
                if same_target and same_sitting and close:
                    concurrent.append(r)
            if concurrent:
                flag, filing, decision = "CONCURRENT", "NEEDS_DECISION", "CONCURRENT"
            else:
                flag = "LATE_ARRIVAL"
                if not reason:
                    reason = coverage.suggest_reason(prior, purpose=session.get("purpose_t"), roster=roster, user_id=user_id)
        elif prior and not reason:
            reason = coverage.suggest_reason(prior, purpose=session.get("purpose_t"), roster=roster, user_id=user_id)
        out[artisan_id] = {"flag": flag, "repeatReason": reason if prior else None}
    return filing, decision, out


async def soft_remove(tx: Any, recording_id: str, actor_id: str, *, reason: str = "REMOVE") -> None:
    rows = await tx.query_raw('SELECT "mediaFileId", "removedAt" FROM "QuestionnaireRecording" WHERE "id" = $1', recording_id)
    if not rows or rows[0].get("removedAt"):
        return
    await tx.execute_raw(
        'UPDATE "QuestionnaireRecording" SET "removedAt" = now(), "removedById" = $2, "changeSeq" = nextval(\'questionnaire_change_seq\'), '
        '"version" = "version" + 1, "updatedAt" = now() WHERE "id" = $1',
        recording_id, actor_id,
    )
    await tx.execute_raw('UPDATE "MediaFile" SET "removedAt" = now(), "removedById" = $2 WHERE "id" = $1', str(rows[0]["mediaFileId"]), actor_id)
    await sessions.log_event(tx, recording_id, "REMOVE", None, {"reason": reason}, actor_id)


async def complete(payload: Any, user: Any, *, client: Any = None, provenance: str = "NATIVE",
                   fault_after_insert: Any = None) -> tuple[dict[str, Any], bool]:
    """``(recording, created)``. ``fault_after_insert`` is a test hook (DESIGN test 5)."""
    from app.core.db import db as default_db
    from app.services import s3

    db = _db(client)
    existing = await _existing_by_clip(db, payload.clientClipKey)
    if existing:
        return await recording_payload(db, existing, user), False
    by_key = await db.query_raw(
        'SELECT r."id" FROM "MediaFile" m JOIN "QuestionnaireRecording" r ON r."mediaFileId" = m."id" WHERE m."objectKey" = $1',
        payload.objectKey,
    )
    if by_key:
        return await recording_payload(db, str(by_key[0]["id"]), user), False
    if not payload.objectKey.startswith(f"media/{user.id}/"):
        fail("FORBIDDEN", "You can only complete your own uploads.")
    session = await sessions.load_session_row(db, payload.linkedRecordId)
    if session is None:
        fail("SESSION_UNKNOWN")
    session_id = str(session["id"])
    questionnaire_id = str(session["questionnaireId"])
    rec = payload.recording
    targets = await coverage.resolve_targets(db, questionnaire_id, rec.targets)
    intent_rows = await db.query_raw('SELECT * FROM "MediaUploadIntent" WHERE "clientClipKey" = $1', payload.clientClipKey)
    intent = intent_rows[0] if intent_rows else None
    content_type = str(intent["contentType"]) if intent else _ext_mime(payload.objectKey.rsplit(".", 1)[-1], None)
    size = int(intent["sizeBytes"]) if intent else 1
    captured, source, clamped = correct_capture_time(
        device_captured_at=rec.deviceCapturedAt, clock_offset_ms=rec.clockOffsetMs, source=rec.capturedAtSource,
        session_date=session.get("sessionDate"),
    )
    participants = await db.query_raw(
        'SELECT "artisanId", "joinedAt", "leftAt" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', session_id
    )
    present = [str(p["artisanId"]) for p in participants if not p.get("leftAt")] or [str(p["artisanId"]) for p in participants]
    subjects = [s.model_dump() for s in rec.subjects] or [
        {"artisanId": a, "role": "ANSWERED", "named": False, "repeatReason": None} for a in present
    ]
    newcomers = sorted({s["artisanId"] for s in subjects} - {str(p["artisanId"]) for p in participants})
    if newcomers:
        await sessions._assert_exists(db, "Artisan", newcomers, "subjects")
    roster = rec.rosterCount if rec.rosterCount is not None else (len(present) or len(subjects))
    answered = [s for s in subjects if s["role"] == "ANSWERED"]
    closed_at = aware(session.get("closedAt"))
    after_close = session.get("state_t") == "CLOSED" and closed_at is not None and captured > closed_at
    owner = db if client is not None else default_db
    recording_id = uuid4().hex
    media_id = uuid4().hex
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        await lock_subjects(tx, [s["artisanId"] for s in subjects], questionnaire_id)
        again = await _existing_by_clip(tx, payload.clientClipKey)
        if again:
            recording_id = again
        else:
            if newcomers:
                await sessions.add_participants(tx, session_id, newcomers, captured)
            extra = {
                "questionnaireV2": {"clientClipKey": payload.clientClipKey},
                **({"clockClamped": True} if clamped else {}),
                **({"reviewNotes": [f"Added to the session by a recording: {', '.join(newcomers)}"]} if newcomers else {}),
                **({"reviewItem": "New take on an approved session"} if session.get("status_t") == "APPROVED" else {}),
            }
            await tx.execute_raw(
                'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", "url", '
                '"linkedRecordType", "linkedRecordId", "questionnaireInterviewId", "workshopId", "status", "uploadedById", '
                '"contentSha256", "recordedAt", "extraMetadata", "updatedAt") VALUES ($1, $2, $3::"MediaType", $4, $5, $6, $7, $8, '
                "'questionnaire_session', $9, $9, $10, 'PENDING', $11, $12, $13::timestamp, $14::jsonb, now())",
                media_id, payload.objectKey.rsplit("/", 1)[-1], _media_type(content_type), content_type, size,
                _bucket(), payload.objectKey, s3.public_url_for_key(payload.objectKey), session_id, session.get("workshopId"),
                user.id, (rec.contentSha256 or (intent or {}).get("contentSha256") or "").lower() or None, naive_utc(captured),
                json.dumps(extra),
            )
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "deviceCapturedAt", "clockOffsetMs", '
                '"capturedAt", "capturedAtSource", "durationMs", "rosterCount", "recordedById", "continuationOfId", '
                '"replacesRecordingId", "ackedRecordingIds", "provenance", "languageCode", "updatedAt") VALUES ($1, $2, $3, '
                '$4::timestamp, $5, $6::timestamp, $7::"CapturedAtSource", $8, $9, $10, $11, $12, $13::text[], '
                '$14::"RecordingProvenance", $15, now())',
                recording_id, media_id, payload.clientClipKey, naive_utc(aware(rec.deviceCapturedAt)), rec.clockOffsetMs,
                naive_utc(captured), source, rec.durationMs, roster, user.id, rec.continuationOfId, rec.replacesRecordingId,
                list(rec.ackedRecordingIds), provenance, rec.languageCode or session.get("languageCode"),
            )
            if fault_after_insert is not None:
                await fault_after_insert()
            for t in targets:
                await tx.execute_raw(
                    'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "questionLineageId", "askedQuestionId") '
                    "VALUES ($1, $2, $3, $4, $5)",
                    recording_id, t["targetKey"], t["sectionId"], t["questionLineageId"], t["askedQuestionId"],
                )
            for s in subjects:
                await tx.execute_raw(
                    'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named", "repeatReason") '
                    'VALUES ($1, $2, $3::"SubjectRole", $4, $5::"RepeatReason") ON CONFLICT DO NOTHING',
                    recording_id, s["artisanId"], s["role"], bool(s.get("named")), s.get("repeatReason"),
                )
            filing, decision, flags = await _flag_subjects(
                tx, questionnaire_id=questionnaire_id, session=session, recording_id=recording_id, captured_at=captured,
                targets=targets, answered=answered, acked=set(rec.ackedRecordingIds), continuation_of=rec.continuationOfId,
                user_id=user.id, roster=roster,
            )
            if after_close:
                for artisan_id in flags:
                    flags[artisan_id]["flag"] = flags[artisan_id]["flag"] or "AFTER_CLOSE"
            for artisan_id, f in flags.items():
                await tx.execute_raw(
                    'UPDATE "QuestionnaireRecordingSubject" SET "flag" = $3::"TakeFlag", "repeatReason" = $4::"RepeatReason" '
                    'WHERE "recordingId" = $1 AND "artisanId" = $2',
                    recording_id, artisan_id, f["flag"], f["repeatReason"],
                )
            if not subjects and not decision:
                filing, decision = "NEEDS_DECISION", "NO_PARTICIPANTS"
            await tx.execute_raw(
                'UPDATE "QuestionnaireRecording" SET "filing" = $2::"RecordingFiling", "decisionKind" = $3 WHERE "id" = $1',
                recording_id, filing, decision,
            )
            if rec.replacesRecordingId:
                await soft_remove(tx, rec.replacesRecordingId, user.id, reason=f"replaced by {recording_id}")
            await tx.execute_raw(
                'UPDATE "MediaUploadIntent" SET "closedAt" = now(), "mediaFileId" = $2 WHERE "clientClipKey" = $1',
                payload.clientClipKey, media_id,
            )
            await sessions.add_researcher(tx, session_id, user.id, "VOLUNTEER" if str(enum_text(get(user, "role"))) == "CROWDSOURCE_VOLUNTEER" else "CONTRIBUTOR")
            await tx.execute_raw(
                "INSERT INTO \"MediaProcessingJob\" (\"id\", \"jobType\", \"status\", \"priority\", \"mediaFileId\", \"requestedById\", \"updatedAt\") "
                "VALUES ($1, 'SHA_VERIFY', 'QUEUED', 60, $2, $3, now())",
                uuid4().hex, media_id, user.id,
            )
            await tx.execute_raw('UPDATE "QuestionnaireInterview" SET "version" = "version" + 1, "updatedAt" = now() WHERE "id" = $1', session_id)
            if newcomers:
                await titles.regenerate_title(tx, session_id)
    if not again:
        # After commit, and paused by default: writes a QUEUED job and calls no provider.
        await transcription.enqueue(media_id, "complete", requested_by_id=user.id, client=db)
    return await recording_payload(db, recording_id, user), not again


def _bucket() -> str:
    from app.core.config import get_settings

    return get_settings().aws_s3_bucket


# ── reading ────────────────────────────────────────────────────────────────────────────────────
async def recording_rows(client: Any, *, recording_ids: list[str] | None = None, session_id: str | None = None,
                         include_removed: bool = False) -> list[dict[str, Any]]:
    where, params = [], []
    if recording_ids is not None:
        params.append(recording_ids)
        where.append(f'r."id" = ANY(${len(params)}::text[])')
    if session_id:
        params.append(session_id)
        where.append(f'm."questionnaireInterviewId" = ${len(params)}')
    if not include_removed:
        where.append('r."removedAt" IS NULL AND m."removedAt" IS NULL')
    return await client.query_raw(
        'SELECT r.*, r."capturedAtSource"::text AS source, r."filing"::text AS filing_t, r."provenance"::text AS provenance_t, '
        'm."questionnaireInterviewId" AS "sessionId", m."objectKey", m."mimeType", m."transcriptStatus", m."transcriptText", '
        'm."originalFilename", m."createdAt" AS "mediaCreatedAt", u."name" AS "recordedByName", i."questionnaireId" '
        'FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" JOIN "User" u ON u."id" = r."recordedById" '
        'LEFT JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        + ("WHERE " + " AND ".join(where) if where else "")
        + ' ORDER BY r."capturedAt", m."createdAt", r."id"',
        *params,
    )


async def serialize_recordings(client: Any, rows: list[dict[str, Any]], user: Any = None) -> list[dict[str, Any]]:
    if not rows:
        return []
    rids = [str(r["id"]) for r in rows]
    targets = await client.query_raw(
        'SELECT t.*, q."displayCode", q."prompt", q."sectionCode" AS qsec, s."code" AS scode FROM "QuestionnaireRecordingTarget" t '
        'LEFT JOIN "QuestionnaireQuestion" q ON q."id" = COALESCE(t."askedQuestionId", t."questionLineageId") '
        'LEFT JOIN "QuestionnaireSection" s ON s."id" = t."sectionId" WHERE t."recordingId" = ANY($1::text[]) ORDER BY t."targetKey"',
        rids,
    )
    subjects = await client.query_raw(
        'SELECT s."recordingId", s."artisanId", s."role"::text AS role, s."named", s."repeatReason"::text AS reason, s."flag"::text AS flag '
        'FROM "QuestionnaireRecordingSubject" s WHERE s."recordingId" = ANY($1::text[]) ORDER BY s."role", s."artisanId"',
        rids,
    )
    positions = await client.query_raw(
        'SELECT "recordingId", "artisanId", min("position") AS position FROM questionnaire_take_v WHERE "recordingId" = ANY($1::text[]) GROUP BY 1, 2',
        rids,
    )
    pos = {(str(p["recordingId"]), str(p["artisanId"])): int(p["position"]) for p in positions if p.get("position") is not None}
    paused = await transcription.is_paused(client)
    t_by, s_by = defaultdict(list), defaultdict(list)
    for t in targets:
        t_by[str(t["recordingId"])].append(t)
    for s in subjects:
        s_by[str(s["recordingId"])].append(s)
    title_inputs: dict[str, naming.TitleInput] = {}
    out = []
    for r in rows:
        rid = str(r["id"])
        sid = r.get("sessionId")
        if sid and sid not in title_inputs:
            title_inputs[sid] = await titles.title_input(client, sid)
        tlist = [
            {
                "targetKey": t["targetKey"], "sectionId": t.get("sectionId"), "questionLineageId": t.get("questionLineageId"),
                "askedQuestionId": t.get("askedQuestionId"),
                "displayCode": t.get("displayCode") if t.get("questionLineageId") else None,
                "label": (f"{t.get('displayCode') or ''} {t.get('prompt') or ''}".strip() if t.get("questionLineageId")
                          else f"Section {t.get('scode') or ''}".strip()),
                "_sectionCode": t.get("qsec") if t.get("questionLineageId") else t.get("scode"),
            }
            for t in t_by.get(rid, [])
        ]
        slist = [
            {"artisanId": str(s["artisanId"]), "role": s["role"], "named": bool(s["named"]), "repeatReason": s.get("reason"),
             "flag": s.get("flag"), "position": pos.get((rid, str(s["artisanId"])))}
            for s in s_by.get(rid, [])
        ]
        answered_positions = [s["position"] for s in slist if s["role"] == "ANSWERED" and s["position"]]
        part = await _part_number(client, rid) if r.get("continuationOfId") else None
        ext = (str(r.get("objectKey") or "").rsplit(".", 1)[-1] or "m4a") if "." in str(r.get("objectKey") or "") else "m4a"
        download = naming.recording_filename(
            title_inputs.get(sid) or naming.TitleInput(), captured_at=r["capturedAt"], captured_at_source=r.get("source"),
            targets=[naming.TargetRef(sectionCode=t["_sectionCode"] or "", displayCode=t["displayCode"]) for t in tlist],
            ext=ext, part=part, position=min(answered_positions) if answered_positions else None,
            roster_count=int(r.get("rosterCount") or 0),
        )
        for t in tlist:
            t.pop("_sectionCode", None)
        status = r.get("transcriptStatus")
        consent = await consent_gate.verdict_detail(str(r["mediaFileId"]), client=client)
        out.append({
            "id": rid, "mediaFileId": str(r["mediaFileId"]), "clientClipKey": r["clientClipKey"], "sessionId": sid,
            "version": int(r.get("version") or 1), "capturedAt": iso(r["capturedAt"]), "capturedAtSource": r.get("source"),
            "estimated": r.get("source") in ESTIMATED, "durationMs": r.get("durationMs"), "rosterCount": int(r.get("rosterCount") or 0),
            "recordedBy": {"id": str(r["recordedById"]), "name": r.get("recordedByName") or ""},
            "targets": tlist, "subjects": slist, "continuationOfId": r.get("continuationOfId"),
            "filing": r.get("filing_t") or "FILED", "decisionKind": r.get("decisionKind"), "provenance": r.get("provenance_t") or "NATIVE",
            "languageCode": r.get("languageCode"), "removedAt": iso(r.get("removedAt")), "downloadName": download,
            "transcript": {
                "status": status, "paused": paused and status in (None, "QUEUED", "RATE_LIMITED", "FAILED"),
                "text": r.get("transcriptText") if status in ("COMPLETED", "SKIPPED_DUPLICATE") else None, "language": None,
            },
            "consent": consent,
        })
    return out


async def recording_payload(client: Any, recording_id: str, user: Any = None) -> dict[str, Any]:
    rows = await recording_rows(client, recording_ids=[recording_id], include_removed=True)
    if not rows:
        fail("NOT_FOUND")
    return (await serialize_recordings(client, rows, user))[0]


async def require_recording(client: Any, recording_id: str) -> dict[str, Any]:
    rows = await recording_rows(client, recording_ids=[recording_id], include_removed=True)
    if not rows:
        fail("NOT_FOUND")
    return rows[0]


async def _assert_can_change(client: Any, user: Any, row: dict[str, Any], *, reviewers: bool = False) -> None:
    if is_admin(user) or str(row["recordedById"]) == str(user.id):
        return
    if reviewers and can_review(user):
        return
    session = await sessions.load_session_row(client, str(row["sessionId"])) if row.get("sessionId") else None
    if session and await sessions.can_work_in(client, user, session):
        return
    fail("FORBIDDEN", "Only the uploader, the session's researchers or an admin can change this take.")


# ── changes ────────────────────────────────────────────────────────────────────────────────────
async def patch(recording_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.core.db import db as default_db

    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row)
    if payload.version != int(row.get("version") or 1):
        fail("STALE_VERSION", current=await recording_payload(db, recording_id, user), diff={})
    sent = payload.model_fields_set
    target_session = payload.sessionId if "sessionId" in sent and payload.sessionId else row.get("sessionId")
    if target_session != row.get("sessionId"):
        dest = await sessions.require_session(db, target_session)
        if not (is_admin(user) or str(row["recordedById"]) == str(user.id)):
            fail("FORBIDDEN", "Moving a take to another session is for its uploader or an admin.")
        if str(dest["questionnaireId"]) != str(row.get("questionnaireId")):
            fail("MERGE_INSTRUMENT_MISMATCH", "That session is on another questionnaire.")
    questionnaire_id = str(row.get("questionnaireId"))
    owner = db if client is not None else default_db
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        if target_session != row.get("sessionId"):
            await tx.execute_raw(
                'UPDATE "MediaFile" SET "questionnaireInterviewId" = $2, "linkedRecordId" = $2 WHERE "id" = $1', str(row["mediaFileId"]), target_session
            )
            await sessions.log_event(tx, recording_id, "MOVE", {"sessionId": row.get("sessionId")}, {"sessionId": target_session}, user.id)
        if "targets" in sent and payload.targets is not None:
            resolved = await coverage.resolve_targets(tx, questionnaire_id, payload.targets)
            before = await tx.query_raw('SELECT "targetKey" FROM "QuestionnaireRecordingTarget" WHERE "recordingId" = $1', recording_id)
            await tx.execute_raw('DELETE FROM "QuestionnaireRecordingTarget" WHERE "recordingId" = $1', recording_id)
            for t in resolved:
                await tx.execute_raw(
                    'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "questionLineageId", "askedQuestionId") '
                    "VALUES ($1, $2, $3, $4, $5)", recording_id, t["targetKey"], t["sectionId"], t["questionLineageId"], t["askedQuestionId"],
                )
            await sessions.log_event(tx, recording_id, "REFILE", [b["targetKey"] for b in before], [t["targetKey"] for t in resolved], user.id)
        if "subjects" in sent and payload.subjects is not None:
            before = await tx.query_raw(
                'SELECT "artisanId", "role"::text AS role FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1', recording_id
            )
            keep = {s.artisanId: s.role for s in payload.subjects}
            await tx.execute_raw(
                'DELETE FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1 AND NOT ("artisanId" = ANY($2::text[]))',
                recording_id, list(keep),
            )
            for artisan_id, role in keep.items():
                await tx.execute_raw(
                    'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named") VALUES ($1, $2, $3::"SubjectRole", true) '
                    'ON CONFLICT ("recordingId", "artisanId") DO UPDATE SET "role" = EXCLUDED."role", "named" = true',
                    recording_id, artisan_id, role,
                )
            missing = sorted(set(keep) - set(await sessions.participant_ids(tx, target_session)))
            if missing:
                await sessions.add_participants(tx, target_session, missing)
                await titles.regenerate_title(tx, target_session)
            await sessions.log_event(tx, recording_id, "SUBJECTS", before, [{"artisanId": a, "role": r} for a, r in keep.items()], user.id)
        if "repeatReasons" in sent and payload.repeatReasons:
            for artisan_id, reason in payload.repeatReasons.items():
                await tx.execute_raw(
                    'UPDATE "QuestionnaireRecordingSubject" SET "repeatReason" = $3::"RepeatReason" WHERE "recordingId" = $1 AND "artisanId" = $2',
                    recording_id, artisan_id, reason,
                )
        if "capturedAt" in sent and payload.capturedAt is not None:
            await tx.execute_raw(
                'UPDATE "QuestionnaireRecording" SET "capturedAt" = $2::timestamp WHERE "id" = $1', recording_id, naive_utc(aware(payload.capturedAt))
            )
            await sessions.log_event(tx, recording_id, "DATE_EDIT", {"capturedAt": iso(row["capturedAt"])}, {"capturedAt": iso(payload.capturedAt)}, user.id)
        await sessions.bump_recording(tx, recording_id)
    return await recording_payload(db, recording_id, user)


async def decide(recording_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.core.db import db as default_db

    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row, reviewers=True)
    other = payload.otherRecordingId
    if payload.action in ("KEEP_THIS", "CONTINUATION") and not other:
        fail("VALIDATION_FAILED", "Name the other take.")
    owner = db if client is not None else default_db
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        if payload.action == "KEEP_THIS":
            await soft_remove(tx, other, user.id, reason=f"kept {recording_id}")
        if payload.action == "CONTINUATION":
            await tx.execute_raw('UPDATE "QuestionnaireRecording" SET "continuationOfId" = $2 WHERE "id" = $1', recording_id, other)
        await tx.execute_raw(
            'UPDATE "QuestionnaireRecording" SET "filing" = \'FILED\', "decisionKind" = NULL WHERE "id" = ANY($1::text[])',
            [recording_id] + ([other] if other and payload.action == "KEEP_BOTH" else []),
        )
        await sessions.log_event(tx, recording_id, "DECIDE", {"filing": row.get("filing_t")}, {"action": payload.action, "other": other}, user.id)
        await sessions.bump_recording(tx, recording_id)
        if other:
            await sessions.bump_recording(tx, other)
    return await recording_payload(db, recording_id, user)


async def make_primary(recording_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    if not has_rank(user, "PROFESSOR"):
        fail("FORBIDDEN", "Make primary is for Professor and above.")
    await require_recording(db, recording_id)
    await sessions.log_event(db, recording_id, "MAKE_PRIMARY", None, {"artisanId": payload.artisanId, "targetKey": payload.targetKey}, user.id)
    await sessions.bump_recording(db, recording_id)
    return await recording_payload(db, recording_id, user)


async def remove(recording_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row)
    await soft_remove(db, recording_id, user.id)
    return await recording_payload(db, recording_id, user)


async def restore(recording_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row)
    if row.get("removedAt"):
        await db.execute_raw('UPDATE "QuestionnaireRecording" SET "removedAt" = NULL, "removedById" = NULL WHERE "id" = $1', recording_id)
        await db.execute_raw('UPDATE "MediaFile" SET "removedAt" = NULL, "removedById" = NULL WHERE "id" = $1', str(row["mediaFileId"]))
        await sessions.log_event(db, recording_id, "RESTORE", None, None, user.id)
        await sessions.bump_recording(db, recording_id)
    return await recording_payload(db, recording_id, user)


async def transcribe(recording_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    """Take-level Retry. While paused: 202 ``{"status":"QUEUED","paused":true}``, never an error."""
    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row, reviewers=True)
    result = await transcription.enqueue(str(row["mediaFileId"]), "retry", requested_by_id=user.id, client=db)
    return {"status": result["status"], "paused": bool(result["paused"])}


async def download_url(recording_id: str, user: Any, *, client: Any = None) -> str:
    from app.services import s3

    db = _db(client)
    payload = await recording_payload(db, recording_id, user)
    row = await require_recording(db, recording_id)
    return await asyncio.to_thread(
        s3.presign_get_url, str(row["objectKey"]), filename=payload["downloadName"], mime_type=str(row.get("mimeType") or "audio/mp4")
    )


# ── the sha worker (DESIGN 3.3 rule 4, test 30) ────────────────────────────────────────────────
async def verify_sha(media_id: str, *, client: Any = None, hasher: Any = None) -> dict[str, Any]:
    """Hash the stored object, then: a verified twin in the SAME session -> this copy is soft-removed
    and its subjects merge into the kept recording; in ANOTHER session -> NEEDS_DECISION ("Same
    file on two sessions"). Transcription is skipped on a verified match."""
    from app.services import s3

    db = _db(client)
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None:
        return {"status": "MISSING"}
    sha, size = await asyncio.to_thread(hasher or s3.stream_sha256, media.objectKey)
    await db.execute_raw(
        'UPDATE "MediaFile" SET "contentSha256" = $2, "shaVerifiedAt" = now() WHERE "id" = $1', media_id, sha
    )
    twin_rows = await db.query_raw(
        'SELECT m."id", m."questionnaireInterviewId" AS sid, r."id" AS rid, m."transcriptStatus", m."transcriptText", m."transcriptSummary" '
        'FROM "MediaFile" m JOIN "QuestionnaireRecording" r ON r."mediaFileId" = m."id" '
        'WHERE m."contentSha256" = $1 AND m."shaVerifiedAt" IS NOT NULL AND m."removedAt" IS NULL AND r."removedAt" IS NULL '
        'AND m."id" <> $2 ORDER BY m."createdAt", m."id" LIMIT 1',
        sha, media_id,
    )
    mine = await db.query_raw('SELECT "id" FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1', media_id)
    if not twin_rows or not mine:
        return {"status": "VERIFIED", "sha256": sha, "sizeBytes": size, "duplicateOf": None}
    twin = twin_rows[0]
    rid = str(mine[0]["id"])
    actor = str(media.uploadedById)
    if twin.get("sid") == media.questionnaireInterviewId:
        subs = await db.query_raw(
            'SELECT "artisanId", "role"::text AS role, "named" FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1', rid
        )
        for s in subs:
            await db.execute_raw(
                'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named") VALUES ($1, $2, $3::"SubjectRole", $4) '
                "ON CONFLICT (\"recordingId\", \"artisanId\") DO UPDATE SET \"role\" = CASE WHEN EXCLUDED.\"role\" = 'ANSWERED' "
                'THEN EXCLUDED."role" ELSE "QuestionnaireRecordingSubject"."role" END',
                str(twin["rid"]), str(s["artisanId"]), s["role"], bool(s["named"]),
            )
        await soft_remove(db, rid, actor, reason="VERIFIED_DUPLICATE")
        await db.execute_raw(
            'UPDATE "MediaFile" SET "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $2::jsonb WHERE "id" = $1',
            media_id, json.dumps({"removedAs": "VERIFIED_DUPLICATE", "keptMediaId": str(twin["id"])}),
        )
        await sessions.bump_recording(db, str(twin["rid"]))
        outcome = "MERGED_INTO_TWIN"
    else:
        await db.execute_raw(
            'UPDATE "QuestionnaireRecording" SET "filing" = \'NEEDS_DECISION\', "decisionKind" = \'SAME_FILE_OTHER_SESSION\' WHERE "id" = $1', rid
        )
        await sessions.bump_recording(db, rid)
        outcome = "SAME_FILE_OTHER_SESSION"
    # Never transcribe a verified copy twice: close its open job and copy the twin's transcript.
    await db.execute_raw(
        "UPDATE \"MediaProcessingJob\" SET \"status\" = 'CANCELLED', \"error\" = 'Verified copy of another recording', \"completedAt\" = now() "
        "WHERE \"mediaFileId\" = $1 AND \"jobType\" = 'TRANSCRIPTION' AND \"status\" IN ('QUEUED', 'PROCESSING')",
        media_id,
    )
    if twin.get("transcriptStatus") == "COMPLETED":
        await db.execute_raw(
            'UPDATE "MediaFile" SET "transcriptStatus" = \'SKIPPED_DUPLICATE\', "transcriptText" = $2, "transcriptSummary" = $3 WHERE "id" = $1',
            media_id, twin.get("transcriptText"), twin.get("transcriptSummary"),
        )
    else:
        await db.execute_raw('UPDATE "MediaFile" SET "transcriptStatus" = \'SKIPPED_DUPLICATE\' WHERE "id" = $1', media_id)
    return {"status": outcome, "sha256": sha, "sizeBytes": size, "duplicateOf": str(twin["id"])}
