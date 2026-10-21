"""Recordings: server-known uploads, the atomic /complete, and every later change (DESIGN 3.3,
3.8, 3.9, 5.3 and 5.5).

* Presign writes a ``MediaUploadIntent`` keyed on ``clientClipKey``. A re-presign signs a fresh URL
  for the SAME ``objectKey`` and reuses an open multipart upload; a CLOSED intent signs nothing and
  answers ``{"closed": true}`` (the clip is already filed). An upload S3 has dropped is restarted on
  the same key. The server mints the S3 leaf (``naming.recording_filename``); the client's file name
  is never used.
* ``/complete`` is ONE transaction under sorted advisory locks, idempotent on ``clientClipKey`` then
  ``objectKey``. It never refuses a recording because of coverage: it files it, and flags it only
  when it is truly ambiguous (CONCURRENT -> NEEDS_DECISION). A subject who is not a participant is
  added as one. Inside the same transaction it queues the sha check and the (paused) transcription
  job, so a crash after commit can never leave a take that is never transcribed.
* A session merged away is followed to the session it was merged into, by presign and /complete.
* Nothing is deleted implicitly: removal is soft and restorable, content admins only (owner,
  21 Oct 2026), and every change is an event. A change of primary take writes PRIMARY_CHANGED.
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
from app.core.roles import CONTENT_DELETE_REFUSAL, can_review, has_rank, is_admin, is_content_admin
from app.services import consent_gate, naming
from app.services import transcription_enqueue as transcription
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
from app.services.questionnaire_v2.locks import lock_key, lock_subjects

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
#: ``detail.reason`` of a /complete whose object is not in S3 (404 NOT_FOUND, ``retry: true``).
UPLOAD_MISSING = "UPLOAD_MISSING"


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


def _owner(client: Any) -> Any:
    """Who opens a transaction: the given client, else the app's."""
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


def _volunteer(user: Any) -> bool:
    return str(enum_text(get(user, "role"))) == "CROWDSOURCE_VOLUNTEER"


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


def offset_age(measured_at: Any, server_now: datetime | None = None) -> timedelta | None:
    """How old the device's clock offset is (M5): the client says when it measured it."""
    measured = aware(measured_at)
    if measured is None:
        return None
    return max((server_now or now()) - measured, timedelta(0))


# ── names ──────────────────────────────────────────────────────────────────────────────────────
def target_ref(*, section_code: str | None, display_code: str | None, is_question: bool) -> naming.TargetRef:
    """A file-name target. A question's display code is FROZEN for its lineage, so after a section
    rename or a re-parent its own prefix (``D`` of ``D3``), not the section's current code, names it:
    otherwise ``D3`` under section ``E`` would read as ``E-All`` (audit M16)."""
    if not is_question:
        return naming.TargetRef(sectionCode=section_code or "")
    code = display_code or ""
    section = section_code or ""
    if section and code.upper().startswith(section.upper()) and code[len(section):].isdigit():
        return naming.TargetRef(sectionCode=section, displayCode=display_code)
    digits = len(code) - len(code.rstrip("0123456789"))
    prefix, number = (code[:-digits], int(code[-digits:])) if digits and digits < len(code) else (section_code or "", None)
    return naming.TargetRef(sectionCode=prefix or section_code or "", displayCode=display_code, questionNumber=number)


async def _target_refs(client: Any, rows: list[dict[str, Any]]) -> list[naming.TargetRef]:
    return [
        target_ref(section_code=t.get("sectionCode"), display_code=t.get("displayCode"), is_question=bool(t.get("questionLineageId")))
        for t in rows
    ]


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


async def _next_part(client: Any, continuation_of: str | None) -> int | None:
    """The part number of a new take that continues ``continuation_of`` (Part 2 is ``-P2``)."""
    if not continuation_of:
        return None
    return (await _part_number(client, continuation_of) or 1) + 1


# ── presign, multipart, resume ─────────────────────────────────────────────────────────────────
async def _session_for_upload(client: Any, session_id: str, user: Any) -> dict[str, Any]:
    """The session a clip lands on, following merges (audit H4). Somebody who is not one of its
    researchers passes the volunteer and workshop-submission rules first (audit Low, /complete)."""
    row = await sessions.follow_merges(client, session_id)
    if row is None:
        fail("SESSION_UNKNOWN")
    if not await sessions.can_work_in(client, user, row):
        if _volunteer(user):
            fail("FORBIDDEN", "Volunteers record into sessions they are added to.")
        if row.get("workshopId"):
            from app.services.workshop_access import enforce_workshop_submission

            await enforce_workshop_submission(user, row.get("workshopId"))
    return row


async def _mint_object_key(client: Any, payload: Any, user: Any, session: dict[str, Any]) -> str:
    questionnaire_id = str(session["questionnaireId"])
    targets = await coverage.resolve_targets(client, questionnaire_id, payload.targets)
    captured, source, _clamped = correct_capture_time(
        device_captured_at=payload.deviceCapturedAt, clock_offset_ms=payload.clockOffsetMs,
        source="DEVICE" if payload.deviceCapturedAt else "UPLOAD_TIME", session_date=session.get("sessionDate"),
        offset_age=offset_age(getattr(payload, "clockOffsetMeasuredAt", None)),
    )
    roster = len(payload.subjectArtisanIds) or len(await sessions.participant_ids(client, str(session["id"])))
    part = await _next_part(client, getattr(payload, "continuationOfId", None))
    position = None
    if roster == 1 and targets and not part:
        subject = payload.subjectArtisanIds[0] if payload.subjectArtisanIds else (await sessions.participant_ids(client, str(session["id"])) or [None])[0]
        if subject:
            prior = await coverage.take_rows(client, questionnaire_id, artisan_ids=[subject], target_keys=[targets[0]["targetKey"]])
            position = len({str(p["rootRecordingId"]) for p in prior}) + 1
    leaf = naming.recording_filename(
        await titles.title_input(client, str(session["id"])), captured_at=captured, captured_at_source=source,
        targets=await _target_refs(client, targets), ext=payload.extension, position=position, roster_count=roster, part=part,
    )
    return f"media/{user.id}/{uuid4().hex}/{leaf}"


async def _intent_row(client: Any, client_clip_key: str) -> dict[str, Any] | None:
    rows = await client.query_raw('SELECT * FROM "MediaUploadIntent" WHERE "clientClipKey" = $1', client_clip_key)
    return rows[0] if rows else None


async def _rearm_if_expired(client: Any, intent: dict[str, Any]) -> dict[str, Any]:
    """An open intent past ``expiresAt`` (audit Low): its multipart upload is aborted (S3 may have
    dropped it already) and it gets a fresh window. It is never closed here: a phone that was
    offline for a month still holds the clip, and closing would tell it the clip is filed."""
    from app.services import s3

    expires = aware(intent.get("expiresAt"))
    if intent.get("closedAt") or expires is None or expires > now():
        return intent
    upload_id = intent.get("multipartUploadId")
    if upload_id:
        try:
            await asyncio.to_thread(s3.abort_multipart_upload, str(intent["objectKey"]), str(upload_id))
        except Exception:  # noqa: BLE001 - already gone is the expected case
            pass
    await client.execute_raw(
        'UPDATE "MediaUploadIntent" SET "expiresAt" = $2::timestamp, "multipartUploadId" = NULL WHERE "id" = $1',
        str(intent["id"]), naive_utc(now() + INTENT_TTL),
    )
    return await _intent_row(client, str(intent["clientClipKey"])) or intent


async def _intent_for(client: Any, payload: Any, user: Any) -> dict[str, Any]:
    """Create or reuse the intent for this clientClipKey (the idempotency of presign)."""
    intent = await _intent_row(client, payload.clientClipKey)
    if intent:
        if str(intent["uploaderId"]) != str(user.id):
            fail("FORBIDDEN", "This upload belongs to another user.")
        return await _rearm_if_expired(client, intent)
    session = await _session_for_upload(client, payload.sessionId, user)
    object_key = await _mint_object_key(client, payload, user, session)
    meta = {
        "targets": [t.model_dump() for t in payload.targets],
        "subjectArtisanIds": payload.subjectArtisanIds,
        "deviceCapturedAt": iso(payload.deviceCapturedAt),
        "clockOffsetMs": payload.clockOffsetMs,
        "extension": payload.extension,
        **({"continuationOfId": payload.continuationOfId} if getattr(payload, "continuationOfId", None) else {}),
    }
    await client.execute_raw(
        'INSERT INTO "MediaUploadIntent" ("id", "objectKey", "clientClipKey", "sessionId", "uploaderId", "sizeBytes", '
        '"contentType", "contentSha256", "partSize", "expiresAt", "meta") VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, '
        '$10::timestamp, $11::jsonb) ON CONFLICT ("clientClipKey") DO NOTHING',
        uuid4().hex, object_key, payload.clientClipKey, str(session["id"]), user.id, int(payload.sizeBytes),
        _ext_mime(payload.extension, payload.contentType), (payload.contentSha256 or "").lower() or None,
        PART_SIZE if payload.sizeBytes > MULTIPART_THRESHOLD else None, naive_utc(now() + INTENT_TTL), json.dumps(meta),
    )
    return await _intent_row(client, payload.clientClipKey) or {}


def _closed(intent: dict[str, Any]) -> dict[str, Any] | None:
    """A closed intent signs nothing (audit H9): a replayed outbox step must never overwrite the
    object of a recording that is already filed. The client reads ``closed`` and calls /complete,
    which answers with the filed recording."""
    if intent.get("closedAt"):
        return {"objectKey": str(intent["objectKey"]), "closed": True}
    return None


async def presign(payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.services import s3

    db = _db(client)
    intent = await _intent_for(db, payload, user)
    closed = _closed(intent)
    if closed:
        return closed
    content_type = str(intent["contentType"])
    sha_b64 = _b64(intent.get("contentSha256"))
    url = await asyncio.to_thread(s3.presign_put_url_checksum, str(intent["objectKey"]), content_type, sha_b64, int(URL_TTL.total_seconds()))
    headers = {"Content-Type": content_type}
    if sha_b64:
        headers["x-amz-checksum-sha256"] = sha_b64
    return {"objectKey": str(intent["objectKey"]), "url": url, "headers": headers, "expiresAt": iso(now() + URL_TTL), "closed": False}


async def _live_parts(db: Any, intent: dict[str, Any]) -> tuple[str | None, list[dict[str, Any]]]:
    """``(uploadId, parts)`` of the intent's open multipart upload; ``(None, [])`` when S3 has dropped
    it (audit H8), after clearing the dead id so it is never asked about again."""
    from app.services import s3

    upload_id = intent.get("multipartUploadId")
    if not upload_id or intent.get("closedAt"):
        return (str(upload_id) if upload_id else None), []
    try:
        return str(upload_id), await asyncio.to_thread(s3.list_uploaded_parts, str(intent["objectKey"]), str(upload_id))
    except s3.NoSuchUpload:
        await db.execute_raw(
            'UPDATE "MediaUploadIntent" SET "multipartUploadId" = NULL WHERE "id" = $1 AND "multipartUploadId" = $2',
            str(intent["id"]), str(upload_id),
        )
        return None, []


async def multipart_create(payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.services import s3

    db = _db(client)
    intent = await _intent_for(db, payload, user)
    closed = _closed(intent)
    if closed:
        return closed
    upload_id, parts = await _live_parts(db, intent)
    if not upload_id:
        # A new upload on the SAME key: the name, the intent and the client's clip key all stay.
        upload_id = await asyncio.to_thread(s3.create_multipart_upload, str(intent["objectKey"]), str(intent["contentType"]))
        await db.execute_raw(
            'UPDATE "MediaUploadIntent" SET "multipartUploadId" = $1, "partSize" = $2 WHERE "id" = $3',
            upload_id, PART_SIZE, str(intent["id"]),
        )
    return {"objectKey": str(intent["objectKey"]), "uploadId": str(upload_id), "partSize": int(intent.get("partSize") or PART_SIZE),
            "partsDone": parts, "closed": False}


async def upload_state(client_clip_key: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    intent = await _intent_row(db, client_clip_key)
    if not intent:
        fail("NOT_FOUND", "No upload is known for that clip.")
    if str(intent["uploaderId"]) != str(user.id) and not is_admin(user):
        fail("FORBIDDEN", "This upload belongs to another user.")
    upload_id, parts = await _live_parts(db, intent)
    return {
        "objectKey": str(intent["objectKey"]), "multipartUploadId": upload_id,
        "partSize": int(intent.get("partSize") or PART_SIZE), "partsDone": parts, "closed": intent.get("closedAt") is not None,
    }


async def sweep_expired_intents(*, client: Any = None, limit: int = 200) -> int:
    """Abort the multipart uploads of open intents past ``expiresAt`` so their parts stop costing
    storage (audit Low). The intent stays open (see :func:`_rearm_if_expired`)."""
    from app.services import s3

    db = _db(client)
    rows = await db.query_raw(
        'SELECT "id", "objectKey", "multipartUploadId" FROM "MediaUploadIntent" WHERE "closedAt" IS NULL '
        "AND \"multipartUploadId\" IS NOT NULL AND \"expiresAt\" < (now() AT TIME ZONE 'UTC') ORDER BY \"expiresAt\" LIMIT $1",
        limit,
    )
    for r in rows:
        try:
            await asyncio.to_thread(s3.abort_multipart_upload, str(r["objectKey"]), str(r["multipartUploadId"]))
        except Exception:  # noqa: BLE001 - S3 already dropped it
            pass
        await db.execute_raw('UPDATE "MediaUploadIntent" SET "multipartUploadId" = NULL WHERE "id" = $1', str(r["id"]))
    return len(rows)


# ── primary takes (DESIGN 3.5; audit M14) ──────────────────────────────────────────────────────
async def primary_snapshot(client: Any, questionnaire_id: str, artisan_ids: list[str], target_keys: list[str]) -> dict[tuple[str, str], str | None]:
    """``{(artisanId, targetKey): primary recordingId}`` for these artisans and targets, now."""
    out: dict[tuple[str, str], str | None] = {}
    if not artisan_ids or not target_keys:
        return out
    rows = await coverage.take_rows(client, questionnaire_id, artisan_ids=sorted(set(artisan_ids)), target_keys=sorted(set(target_keys)))
    by_artisan: dict[str, list] = defaultdict(list)
    for r in rows:
        by_artisan[str(r["artisanId"])].append(r)
    for artisan_id in set(artisan_ids):
        chosen = await coverage.primaries(client, artisan_id, by_artisan.get(artisan_id, [])) if by_artisan.get(artisan_id) else {}
        for key in target_keys:
            out[(artisan_id, key)] = chosen.get(key)
    return out


async def note_primary_changes(client: Any, before: dict[tuple[str, str], str | None], after: dict[tuple[str, str], str | None],
                               actor_id: str) -> int:
    """One PRIMARY_CHANGED event per (artisan, target) whose primary moved from one take to another,
    on the take that is primary now (or the one that stopped being primary)."""
    written = 0
    for key, was in before.items():
        is_now = after.get(key)
        if was and was != is_now:
            artisan_id, target_key = key
            await sessions.log_event(
                client, is_now or was, "PRIMARY_CHANGED", {"recordingId": was},
                {"artisanId": artisan_id, "targetKey": target_key, "from": was, "to": is_now}, actor_id,
            )
            written += 1
    return written


async def _subject_target_keys(client: Any, recording_ids: list[str]) -> tuple[list[str], list[str], str | None]:
    """``(ANSWERED artisans, target keys, questionnaireId)`` of these recordings."""
    rows = await client.query_raw(
        'SELECT DISTINCT s."artisanId", t."targetKey", i."questionnaireId" FROM "QuestionnaireRecording" r '
        'JOIN "MediaFile" m ON m."id" = r."mediaFileId" JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        'LEFT JOIN "QuestionnaireRecordingSubject" s ON s."recordingId" = r."id" AND s."role" = \'ANSWERED\' '
        'LEFT JOIN "QuestionnaireRecordingTarget" t ON t."recordingId" = r."id" WHERE r."id" = ANY($1::text[])',
        recording_ids,
    )
    artisans = sorted({str(r["artisanId"]) for r in rows if r.get("artisanId")})
    keys = sorted({str(r["targetKey"]) for r in rows if r.get("targetKey")})
    qid = next((str(r["questionnaireId"]) for r in rows if r.get("questionnaireId")), None)
    return artisans, keys, qid


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


async def _may_replace(client: Any, replaces_id: str | None, session_id: str, user: Any) -> bool:
    """``replacesRecordingId`` removes the named take, and removing a take is for content admins
    only (owner, 21 Oct 2026; audit H1). The take must also be live and in the same session."""
    if not replaces_id or not is_content_admin(user):
        return False
    rows = await recording_rows(client, recording_ids=[replaces_id])
    return bool(rows) and str(rows[0].get("sessionId")) == session_id


async def _ensure_transcription(client: Any, media_id: str, user_id: str) -> None:
    """A replayed /complete re-queues a take whose transcription was never queued (audit M6)."""
    rows = await client.query_raw(
        'SELECT m."transcriptStatus", EXISTS (SELECT 1 FROM "MediaProcessingJob" j WHERE j."mediaFileId" = m."id" '
        "AND j.\"jobType\" = 'TRANSCRIPTION') AS queued FROM \"MediaFile\" m WHERE m.\"id\" = $1",
        media_id,
    )
    if rows and rows[0].get("transcriptStatus") is None and not rows[0].get("queued"):
        await transcription.enqueue(media_id, "complete (replay)", requested_by_id=user_id, client=client)


async def _submission_stamp(user: Any, session: dict[str, Any]) -> dict[str, Any]:
    """The workshop-submission stamp a take carries, like every other upload into a workshop."""
    if not session.get("workshopId"):
        return {}
    from app.services.workshop_access import describe_workshop_submission

    try:
        check = await describe_workshop_submission(user, str(session["workshopId"]))
    except Exception:  # noqa: BLE001 - the stamp is bookkeeping; it never refuses a take
        return {}
    return dict(check.metadata or {})


async def complete(payload: Any, user: Any, *, client: Any = None, provenance: str = "NATIVE",
                   fault_after_insert: Any = None) -> tuple[dict[str, Any], bool]:
    """``(recording, created)``. ``fault_after_insert`` is a test hook (DESIGN test 5)."""
    from app.services import s3

    db = _db(client)
    existing = await _existing_by_clip(db, payload.clientClipKey)
    if existing:
        row = await require_recording(db, existing)
        await _ensure_transcription(db, str(row["mediaFileId"]), user.id)
        return await recording_payload(db, existing, user), False
    by_key = await db.query_raw(
        'SELECT r."id" FROM "MediaFile" m JOIN "QuestionnaireRecording" r ON r."mediaFileId" = m."id" WHERE m."objectKey" = $1',
        payload.objectKey,
    )
    if by_key:
        return await recording_payload(db, str(by_key[0]["id"]), user), False
    if not payload.objectKey.startswith(f"media/{user.id}/"):
        fail("FORBIDDEN", "You can only complete your own uploads.")
    intent = await _intent_row(db, payload.clientClipKey)
    if intent and str(intent["objectKey"]) != payload.objectKey:
        fail("VALIDATION_FAILED", "That object is not the one this clip was signed for.", fields={"objectKey": [payload.objectKey]})
    session = await _session_for_upload(db, payload.linkedRecordId, user)
    if not await asyncio.to_thread(s3.object_exists, payload.objectKey):
        # The bytes are not in S3 yet (audit Low): nothing is filed. ``reason`` tells a client to
        # upload the clip again and then complete again; it is never a reason to drop the clip.
        fail("NOT_FOUND", "The file has not arrived yet. Upload it again.", reason=UPLOAD_MISSING, retry=True)
    session_id = str(session["id"])
    questionnaire_id = str(session["questionnaireId"])
    rec = payload.recording
    targets = await coverage.resolve_targets(db, questionnaire_id, rec.targets)
    content_type = str(intent["contentType"]) if intent else _ext_mime(payload.objectKey.rsplit(".", 1)[-1], None)
    size = int(intent["sizeBytes"]) if intent else 1
    captured, source, clamped = correct_capture_time(
        device_captured_at=rec.deviceCapturedAt, clock_offset_ms=rec.clockOffsetMs, source=rec.capturedAtSource,
        session_date=session.get("sessionDate"), offset_age=offset_age(rec.clockOffsetMeasuredAt),
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
    approved_session = session.get("status_t") == "APPROVED"
    replace_ok = await _may_replace(db, rec.replacesRecordingId, session_id, user)
    review_notes = []
    if newcomers:
        review_notes.append(f"Added to the session by a recording: {', '.join(newcomers)}")
    if rec.replacesRecordingId and not replace_ok:
        # Never a refusal: both takes are kept, the new one is a redo, and the primary rule picks it.
        review_notes.append(f"Asked to replace take {rec.replacesRecordingId}; only an admin removes a take, so both are kept.")
        for s in answered:
            s["repeatReason"] = s.get("repeatReason") or "REDO"
    extra = {
        "questionnaireV2": {"clientClipKey": payload.clientClipKey},
        **({"clockClamped": True} if clamped else {}),
        **({"reviewNotes": review_notes} if review_notes else {}),
        **({"reviewItem": "New take on an approved session"} if approved_session else {}),
        **(await _submission_stamp(user, session)),
    }
    target_keys = [t["targetKey"] for t in targets]
    recording_id = uuid4().hex
    media_id = uuid4().hex
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        await lock_subjects(tx, [s["artisanId"] for s in subjects], questionnaire_id)
        again = await _existing_by_clip(tx, payload.clientClipKey)
        if again:
            recording_id = again
        else:
            before = await primary_snapshot(tx, questionnaire_id, [s["artisanId"] for s in answered], target_keys)
            if newcomers:
                await sessions.add_participants(tx, session_id, newcomers, captured)
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
                naive_utc(captured), source, rec.durationMs, roster, user.id, rec.continuationOfId,
                rec.replacesRecordingId if replace_ok else None,
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
            if replace_ok:
                await soft_remove(tx, str(rec.replacesRecordingId), user.id, reason=f"replaced by {recording_id}")
            await tx.execute_raw(
                'UPDATE "MediaUploadIntent" SET "closedAt" = now(), "mediaFileId" = $2 WHERE "clientClipKey" = $1',
                payload.clientClipKey, media_id,
            )
            await sessions.add_researcher(tx, session_id, user.id, "VOLUNTEER" if _volunteer(user) else "CONTRIBUTOR")
            await tx.execute_raw(
                "INSERT INTO \"MediaProcessingJob\" (\"id\", \"jobType\", \"status\", \"priority\", \"mediaFileId\", \"requestedById\", \"updatedAt\") "
                "VALUES ($1, 'SHA_VERIFY', 'QUEUED', 60, $2, $3, now())",
                uuid4().hex, media_id, user.id,
            )
            await tx.execute_raw(
                'UPDATE "QuestionnaireInterview" SET "version" = "version" + 1, "updatedAt" = now()'
                + (', "pendingChangesAt" = COALESCE("pendingChangesAt", now())' if approved_session else "")
                + ' WHERE "id" = $1',
                session_id,
            )
            if newcomers:
                await titles.regenerate_title(tx, session_id)
            after = await primary_snapshot(tx, questionnaire_id, [s["artisanId"] for s in answered], target_keys)
            await note_primary_changes(tx, before, after, user.id)
            # Inside the transaction (audit M6), and paused by default: it writes a QUEUED job and
            # calls no provider. A crash after commit can no longer leave the take un-queued.
            await transcription.enqueue(media_id, "complete", requested_by_id=user.id, client=tx)
    return await recording_payload(db, recording_id, user), not again


def _bucket() -> str:
    from app.core.config import get_settings

    return get_settings().aws_s3_bucket


# ── reading ────────────────────────────────────────────────────────────────────────────────────
async def recording_rows(client: Any, *, recording_ids: list[str] | None = None, session_id: str | None = None,
                         include_removed: bool = False, removed_only: bool = False, artisan_id: str | None = None) -> list[dict[str, Any]]:
    where, params = [], []
    if recording_ids is not None:
        params.append(recording_ids)
        where.append(f'r."id" = ANY(${len(params)}::text[])')
    if session_id:
        params.append(session_id)
        where.append(f'm."questionnaireInterviewId" = ${len(params)}')
    if artisan_id:
        params.append(artisan_id)
        where.append(f'EXISTS (SELECT 1 FROM "QuestionnaireRecordingSubject" x WHERE x."recordingId" = r."id" AND x."artisanId" = ${len(params)})')
    if removed_only:
        where.append('(r."removedAt" IS NOT NULL OR m."removedAt" IS NOT NULL)')
    elif not include_removed:
        where.append('r."removedAt" IS NULL AND m."removedAt" IS NULL')
    return await client.query_raw(
        'SELECT r.*, r."capturedAtSource"::text AS source, r."filing"::text AS filing_t, r."provenance"::text AS provenance_t, '
        'm."questionnaireInterviewId" AS "sessionId", m."objectKey", m."mimeType", m."transcriptStatus", m."transcriptText", '
        'm."originalFilename", m."createdAt" AS "mediaCreatedAt", m."contentSha256", m."shaVerifiedAt", m."extraMetadata" AS "mediaExtra", '
        'u."name" AS "recordedByName", i."questionnaireId" '
        'FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" JOIN "User" u ON u."id" = r."recordedById" '
        'LEFT JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        + ("WHERE " + " AND ".join(where) if where else "")
        + ' ORDER BY r."capturedAt", m."createdAt", r."id"',
        *params,
    )


async def serialize_recordings(client: Any, rows: list[dict[str, Any]], user: Any = None) -> list[dict[str, Any]]:
    """Contract ``Recording`` objects. A fixed number of reads however many takes (audit M26): the
    consent verdicts come from one batched read, not four queries per take."""
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
    consents = await consent_gate.verdict_details([str(r["mediaFileId"]) for r in rows], client=client)
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
            targets=[target_ref(section_code=t["_sectionCode"], display_code=t["displayCode"], is_question=bool(t["questionLineageId"]))
                     for t in tlist],
            ext=ext, part=part, position=min(answered_positions) if answered_positions else None,
            roster_count=int(r.get("rosterCount") or 0),
        )
        for t in tlist:
            t.pop("_sectionCode", None)
        status = r.get("transcriptStatus")
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
            "consent": consents.get(str(r["mediaFileId"])) or {"verdict": consent_gate.ALLOW, "source": None},
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


async def removed_takes(user: Any, *, session_id: str | None = None, artisan_id: str | None = None,
                        client: Any = None) -> dict[str, Any]:
    """``GET /questionnaire/recordings/removed`` (audit M12): the removed takes of one session or one
    artisan, newest first, for "Show removed" and "Removed takes". Restore stays open to researchers."""
    db = _db(client)
    if not session_id and not artisan_id:
        fail("VALIDATION_FAILED", "Give a sessionId or an artisanId.")
    rows = await recording_rows(db, session_id=session_id, artisan_id=artisan_id, removed_only=True)
    items = await serialize_recordings(db, rows, user)
    items.sort(key=lambda r: (r["removedAt"] or "", r["id"]), reverse=True)
    return {"items": items}


async def _assert_can_change(client: Any, user: Any, row: dict[str, Any], *, reviewers: bool = False) -> None:
    if is_admin(user) or str(row["recordedById"]) == str(user.id):
        return
    if reviewers and can_review(user):
        return
    session = await sessions.load_session_row(client, str(row["sessionId"])) if row.get("sessionId") else None
    if session and await sessions.can_work_in(client, user, session):
        return
    fail("FORBIDDEN", "Only the uploader, the session's researchers or an admin can change this take.")


assert_can_change = _assert_can_change


# ── changes ────────────────────────────────────────────────────────────────────────────────────
async def move_to_session(tx: Any, row: dict[str, Any], target_session: str, actor_id: str, *, merge: bool = False) -> None:
    """Move one take to another session: its file follows, its subjects become participants there
    (audit M11), the title is regenerated, and the move is an event. The one path for every move
    (PATCH, relink; audit M10)."""
    recording_id = str(row["id"])
    await tx.execute_raw(
        'UPDATE "MediaFile" SET "questionnaireInterviewId" = $2, "linkedRecordId" = $2, "linkedRecordType" = \'questionnaire_session\' '
        'WHERE "id" = $1',
        str(row["mediaFileId"]), target_session,
    )
    subjects = await tx.query_raw('SELECT "artisanId" FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1', recording_id)
    missing = sorted({str(s["artisanId"]) for s in subjects} - set(await sessions.participant_ids(tx, target_session)))
    if missing:
        await sessions.add_participants(tx, target_session, missing)
    await sessions.log_event(tx, recording_id, "MOVE", {"sessionId": row.get("sessionId")},
                             {"sessionId": target_session, **({"merge": True} if merge else {}), **({"participantsAdded": missing} if missing else {})},
                             actor_id)
    await titles.regenerate_title(tx, target_session)
    if row.get("sessionId"):
        await titles.regenerate_title(tx, str(row["sessionId"]))
    await sessions.bump_session(tx, target_session)


async def relink_take(recording_id: str, session_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    """``POST /api/media/{id}/relink`` of a take (audit M10): the same move as a PATCH of its
    session, on the same instrument, through :func:`move_to_session`."""
    db = _db(client)
    row = await require_recording(db, recording_id)
    dest = await sessions.follow_merges(db, session_id)
    if dest is None:
        fail("SESSION_UNKNOWN")
    if str(dest["questionnaireId"]) != str(row.get("questionnaireId")):
        fail("MERGE_INSTRUMENT_MISMATCH", "That session is on another questionnaire.")
    if str(dest["id"]) != str(row.get("sessionId")):
        async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
            await move_to_session(tx, row, str(dest["id"]), user.id)
            await tx.execute_raw('UPDATE "MediaFile" SET "workshopId" = COALESCE($2, "workshopId") WHERE "id" = $1',
                                 str(row["mediaFileId"]), dest.get("workshopId"))
            await sessions.bump_recording(tx, recording_id)
    return await recording_payload(db, recording_id, user)


async def patch(recording_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row)
    if payload.version != int(row.get("version") or 1):
        fail("STALE_VERSION", current=await recording_payload(db, recording_id, user), diff={})
    sent = payload.model_fields_set
    target_session = row.get("sessionId")
    if "sessionId" in sent and payload.sessionId:
        dest = await sessions.follow_merges(db, payload.sessionId)
        if dest is None:
            fail("SESSION_UNKNOWN")
        target_session = str(dest["id"])
    if target_session != row.get("sessionId"):
        dest = await sessions.require_session(db, target_session)
        if not (is_admin(user) or str(row["recordedById"]) == str(user.id)):
            fail("FORBIDDEN", "Moving a take to another session is for its uploader or an admin.")
        if str(dest["questionnaireId"]) != str(row.get("questionnaireId")):
            fail("MERGE_INSTRUMENT_MISMATCH", "That session is on another questionnaire.")
    questionnaire_id = str(row.get("questionnaireId"))
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        if "subjects" in sent and payload.subjects is not None:
            await _write_subjects(tx, recording_id, payload.subjects, row.get("sessionId"), user.id)
        if target_session != row.get("sessionId"):
            await move_to_session(tx, row, str(target_session), user.id)
        if "targets" in sent and payload.targets is not None:
            await _write_targets(tx, recording_id, questionnaire_id, payload.targets, user.id)
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


async def _write_targets(tx: Any, recording_id: str, questionnaire_id: str, targets: list[Any], actor_id: str) -> None:
    resolved = await coverage.resolve_targets(tx, questionnaire_id, targets)
    before = await tx.query_raw('SELECT "targetKey" FROM "QuestionnaireRecordingTarget" WHERE "recordingId" = $1', recording_id)
    await tx.execute_raw('DELETE FROM "QuestionnaireRecordingTarget" WHERE "recordingId" = $1', recording_id)
    for t in resolved:
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "questionLineageId", "askedQuestionId") '
            "VALUES ($1, $2, $3, $4, $5)", recording_id, t["targetKey"], t["sectionId"], t["questionLineageId"], t["askedQuestionId"],
        )
    await sessions.log_event(tx, recording_id, "REFILE", [b["targetKey"] for b in before], [t["targetKey"] for t in resolved], actor_id)


async def _write_subjects(tx: Any, recording_id: str, subjects: list[Any], session_id: str | None, actor_id: str) -> None:
    before = await tx.query_raw(
        'SELECT "artisanId", "role"::text AS role FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1', recording_id
    )
    keep = {get(s, "artisanId"): get(s, "role") for s in subjects}
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
    if session_id:
        missing = sorted(set(keep) - set(await sessions.participant_ids(tx, session_id)))
        if missing:
            await sessions.add_participants(tx, session_id, missing)
            await titles.regenerate_title(tx, session_id)
    await sessions.log_event(tx, recording_id, "SUBJECTS", before, [{"artisanId": a, "role": r} for a, r in keep.items()], actor_id)


async def _shares_target(client: Any, a: str, b: str) -> bool:
    rows = await client.query_raw(
        'SELECT 1 FROM "QuestionnaireRecordingTarget" x JOIN "QuestionnaireRecordingTarget" y ON y."targetKey" = x."targetKey" '
        'WHERE x."recordingId" = $1 AND y."recordingId" = $2 LIMIT 1',
        a, b,
    )
    return bool(rows)


async def _assert_other_take(client: Any, user: Any, row: dict[str, Any], other: str) -> dict[str, Any]:
    """The take a decision names (audit H2): not this take, live, and in the same session sharing a
    target, or the verified twin of a "same file on two sessions" take. The caller may change it."""
    if other == str(row["id"]):
        fail("VALIDATION_FAILED", "A take can't be decided against itself.")
    rows = await recording_rows(client, recording_ids=[other])
    if not rows:
        fail("VALIDATION_FAILED", "The other take is not a live take.")
    orow = rows[0]
    same_file = (row.get("decisionKind") == "SAME_FILE_OTHER_SESSION" and row.get("contentSha256")
                 and row.get("contentSha256") == orow.get("contentSha256") and orow.get("shaVerifiedAt"))
    same_session = str(orow.get("sessionId")) == str(row.get("sessionId")) and await _shares_target(client, str(row["id"]), other)
    if not (same_file or same_session):
        fail("VALIDATION_FAILED", "The other take must be in this session and on the same question or section.")
    await _assert_can_change(client, user, orow, reviewers=True)
    return orow


async def decide(recording_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row, reviewers=True)
    other = payload.otherRecordingId
    if payload.action in ("KEEP_THIS", "CONTINUATION") and not other:
        fail("VALIDATION_FAILED", "Name the other take.")
    if other:
        await _assert_other_take(db, user, row, other)
    if payload.action == "KEEP_THIS" and not is_content_admin(user):
        # "Keep one" removes the other take, and removing a take is for content admins only.
        fail("FORBIDDEN", CONTENT_DELETE_REFUSAL)
    artisans, keys, qid = await _subject_target_keys(db, [recording_id] + ([other] if other else []))
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        before = await primary_snapshot(tx, qid, artisans, keys) if qid else {}
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
        if qid:
            await note_primary_changes(tx, before, await primary_snapshot(tx, qid, artisans, keys), user.id)
    return await recording_payload(db, recording_id, user)


async def file_recording(recording_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    """``POST /recordings/{id}/file`` (audit H14): "File it" for a take the server could not place
    (UNRESOLVED_LEGACY, NO_PARTICIPANTS): its targets, optionally its subjects and session, then
    FILED. One transaction; each change is an event."""
    db = _db(client)
    row = await require_recording(db, recording_id)
    await _assert_can_change(db, user, row, reviewers=True)
    target_session = row.get("sessionId")
    if payload.sessionId:
        dest = await sessions.follow_merges(db, payload.sessionId)
        if dest is None:
            fail("SESSION_UNKNOWN")
        if str(dest["questionnaireId"]) != str(row.get("questionnaireId")):
            fail("MERGE_INSTRUMENT_MISMATCH", "That session is on another questionnaire.")
        target_session = str(dest["id"])
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        if payload.subjects is not None:
            await _write_subjects(tx, recording_id, payload.subjects, row.get("sessionId"), user.id)
        if target_session and target_session != row.get("sessionId"):
            await move_to_session(tx, row, str(target_session), user.id)
        await _write_targets(tx, recording_id, str(row.get("questionnaireId")), payload.targets, user.id)
        await tx.execute_raw(
            'UPDATE "QuestionnaireRecording" SET "filing" = \'FILED\', "decisionKind" = NULL, "provenance" = \'MANUAL\' WHERE "id" = $1',
            recording_id,
        )
        await sessions.log_event(tx, recording_id, "FILE", {"filing": row.get("filing_t"), "decisionKind": row.get("decisionKind")},
                                 {"filing": "FILED"}, user.id)
        await sessions.bump_recording(tx, recording_id)
    return await recording_payload(db, recording_id, user)


async def file_media(media_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    """``POST /questionnaire/media/{id}/file`` (audit H14): "File it" for an inbox "To file" clip,
    which has no recording yet. It joins the session and becomes a recording with the chosen
    targets (provenance MANUAL); the session's participants are its subjects unless named."""
    from app.services.questionnaire_v2 import shims

    db = _db(client)
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None or getattr(media, "removedAt", None):
        fail("NOT_FOUND", "No such file.")
    existing = await db.query_raw('SELECT "id" FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1', media_id)
    if existing:
        return await recording_payload(db, str(existing[0]["id"]), user)
    session = await sessions.follow_merges(db, payload.sessionId) if payload.sessionId else None
    if session is None:
        fail("SESSION_UNKNOWN")
    if not (is_admin(user) or str(getattr(media, "uploadedById", "")) == str(user.id) or await sessions.can_work_in(db, user, session)):
        fail("FORBIDDEN", "Only the uploader, the session's researchers or an admin can file this.")
    if str(enum_text(getattr(media, "mediaType", ""))) not in ("AUDIO", "VIDEO", "MediaType.AUDIO", "MediaType.VIDEO"):
        fail("VALIDATION_FAILED", "Only a recording can be filed into a session.")
    session_id = str(session["id"])
    targets = await coverage.resolve_targets(db, str(session["questionnaireId"]), payload.targets)
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        await tx.execute_raw(
            'UPDATE "MediaFile" SET "questionnaireInterviewId" = $2, "linkedRecordId" = $2, "linkedRecordType" = \'questionnaire_session\', '
            '"workshopId" = COALESCE("workshopId", $3) WHERE "id" = $1',
            media_id, session_id, session.get("workshopId"),
        )
        moved = await tx.mediafile.find_unique(where={"id": media_id})
        recording_id = await shims.record_legacy_clip(moved, user, client=tx, provenance="MANUAL", targets=targets,
                                                      subjects=[get(s, "artisanId") for s in (payload.subjects or [])] or None)
        if recording_id:
            await sessions.log_event(tx, recording_id, "FILE", None, {"sessionId": session_id, "targets": [t["targetKey"] for t in targets]}, user.id)
            await sessions.add_researcher(tx, session_id, user.id, "VOLUNTEER" if _volunteer(user) else "CONTRIBUTOR")
    if not recording_id:
        fail("SESSION_UNKNOWN")
    await transcription.enqueue(media_id, "filed", requested_by_id=user.id, client=db)
    return await recording_payload(db, recording_id, user)


async def not_needed(media_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    """``POST /questionnaire/media/{id}/not-needed``: "Not needed" on an inbox "To file" clip, kept on
    the server so every device agrees (audit M22, H14). It hides the clip from "To file" and from
    the backfill's catch-up; it deletes nothing (deleting stays an admin's act) and "File it" still
    works on it. The same people as "File it": the uploader, the session's researchers, an admin.
    Idempotent: the first mark's who and when are kept."""
    db = _db(client)
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None or getattr(media, "removedAt", None):
        fail("NOT_FOUND", "No such file.")
    allowed = is_admin(user) or str(getattr(media, "uploadedById", "")) == str(user.id)
    session_id = getattr(media, "questionnaireInterviewId", None)
    if not allowed and session_id:
        session = await sessions.load_session_row(db, str(session_id))
        allowed = bool(session) and await sessions.can_work_in(db, user, session)
    if not allowed:
        fail("FORBIDDEN", "Only the uploader, the session's researchers or an admin can set this aside.")
    await db.execute_raw(
        'UPDATE "MediaFile" SET "extraMetadata" = jsonb_set(COALESCE("extraMetadata", \'{}\'::jsonb), \'{questionnaireV2}\', '
        'COALESCE("extraMetadata"->\'questionnaireV2\', \'{}\'::jsonb) || $2::jsonb) '
        'WHERE "id" = $1 AND "extraMetadata"->\'questionnaireV2\'->\'notNeeded\' IS NULL',
        media_id, json.dumps({"notNeeded": {"byId": str(user.id), "at": iso(now())}}),
    )
    rows = await db.query_raw(
        'SELECT "extraMetadata"->\'questionnaireV2\'->\'notNeeded\' AS mark FROM "MediaFile" WHERE "id" = $1', media_id
    )
    mark = rows[0].get("mark") if rows else None
    if isinstance(mark, str):
        mark = json.loads(mark)
    return {"mediaId": media_id, "notNeeded": True, "by": (mark or {}).get("byId"), "at": (mark or {}).get("at")}


async def make_primary(recording_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _db(client)
    if not has_rank(user, "PROFESSOR"):
        fail("FORBIDDEN", "Make primary is for Professor and above.")
    row = await require_recording(db, recording_id)
    rows = await db.query_raw(
        'SELECT 1 FROM "QuestionnaireRecordingSubject" s JOIN "QuestionnaireRecordingTarget" t ON t."recordingId" = s."recordingId" '
        'WHERE s."recordingId" = $1 AND s."artisanId" = $2 AND t."targetKey" = $3 AND s."role" = \'ANSWERED\' LIMIT 1',
        recording_id, payload.artisanId, payload.targetKey,
    )
    if not rows:
        fail("VALIDATION_FAILED", "That artisan answered no such target on this take.")
    qid = str(row.get("questionnaireId"))
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        before = await primary_snapshot(tx, qid, [payload.artisanId], [payload.targetKey])
        await sessions.log_event(tx, recording_id, "MAKE_PRIMARY", None, {"artisanId": payload.artisanId, "targetKey": payload.targetKey}, user.id)
        await sessions.bump_recording(tx, recording_id)
        await note_primary_changes(tx, before, await primary_snapshot(tx, qid, [payload.artisanId], [payload.targetKey]), user.id)
    return await recording_payload(db, recording_id, user)


async def remove(recording_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    # Removing a take is a delete: content admins only (roles.CONTENT_ADMIN_ROLES, 2026-10-20).
    if not is_content_admin(user):
        fail("FORBIDDEN", CONTENT_DELETE_REFUSAL)
    db = _db(client)
    await require_recording(db, recording_id)
    artisans, keys, qid = await _subject_target_keys(db, [recording_id])
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        before = await primary_snapshot(tx, qid, artisans, keys) if qid else {}
        await soft_remove(tx, recording_id, user.id)
        if qid:
            await note_primary_changes(tx, before, await primary_snapshot(tx, qid, artisans, keys), user.id)
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


# ── the sha worker (DESIGN 3.3 rule 4, test 30; audit H10) ─────────────────────────────────────
async def _merge_copy_into(tx: Any, copy_rid: str, keeper_rid: str) -> None:
    """A same-session copy's subjects AND targets join the kept recording, so nothing is lost when
    the copy is purged (audit Low: purged copies lost their targets)."""
    subs = await tx.query_raw(
        'SELECT "artisanId", "role"::text AS role, "named" FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1', copy_rid
    )
    for s in subs:
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named") VALUES ($1, $2, $3::"SubjectRole", $4) '
            "ON CONFLICT (\"recordingId\", \"artisanId\") DO UPDATE SET \"role\" = CASE WHEN EXCLUDED.\"role\" = 'ANSWERED' "
            'THEN EXCLUDED."role" ELSE "QuestionnaireRecordingSubject"."role" END',
            keeper_rid, str(s["artisanId"]), s["role"], bool(s["named"]),
        )
    await tx.execute_raw(
        'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "questionLineageId", "askedQuestionId") '
        'SELECT $2, "targetKey", "sectionId", "questionLineageId", "askedQuestionId" FROM "QuestionnaireRecordingTarget" '
        'WHERE "recordingId" = $1 ON CONFLICT DO NOTHING',
        copy_rid, keeper_rid,
    )


async def _close_transcription(tx: Any, media_id: str, reason: str) -> None:
    await tx.execute_raw(
        "UPDATE \"MediaProcessingJob\" SET \"status\" = 'CANCELLED', \"error\" = $2, \"completedAt\" = now(), \"openKey\" = NULL "
        "WHERE \"mediaFileId\" = $1 AND \"jobType\" = 'TRANSCRIPTION' AND \"status\" IN ('QUEUED', 'PROCESSING')",
        media_id, reason,
    )


async def verify_sha(media_id: str, *, client: Any = None, hasher: Any = None) -> dict[str, Any]:
    """Hash the stored object, then, in ONE transaction locked on the hash (so two copies verified at
    the same moment cannot each remove the other):

    * a verified twin in the SAME session -> the LATER copy is soft-removed and its subjects and
      targets merge into the EARLIEST, which is kept; the copy is never transcribed;
    * in ANOTHER session -> the later copy is NEEDS_DECISION ("Same file on two sessions") with a
      pointer to its twin; its transcription is closed only when the twin's transcript exists."""
    from app.services import s3

    db = _db(client)
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None:
        return {"status": "MISSING"}
    sha, size = await asyncio.to_thread(hasher or s3.stream_sha256, media.objectKey)
    async with _owner(client).tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        await lock_key(tx, f"qv2-sha:{sha}")
        await tx.execute_raw('UPDATE "MediaFile" SET "contentSha256" = $2, "shaVerifiedAt" = now() WHERE "id" = $1', media_id, sha)
        rows = await tx.query_raw(
            'SELECT m."id", m."questionnaireInterviewId" AS sid, m."createdAt", r."id" AS rid, m."transcriptStatus", m."transcriptText", '
            'm."transcriptSummary" FROM "MediaFile" m JOIN "QuestionnaireRecording" r ON r."mediaFileId" = m."id" '
            'WHERE m."contentSha256" = $1 AND m."shaVerifiedAt" IS NOT NULL AND m."removedAt" IS NULL AND r."removedAt" IS NULL '
            'ORDER BY m."createdAt", m."id"',
            sha,
        )
        mine = next((r for r in rows if str(r["id"]) == media_id), None)
        others = [r for r in rows if str(r["id"]) != media_id]
        if mine is None or not others:
            return {"status": "VERIFIED", "sha256": sha, "sizeBytes": size, "duplicateOf": None}
        same = [r for r in others if r.get("sid") == mine.get("sid")]
        actor = str(media.uploadedById)
        if same:
            keeper = min([mine, *same], key=lambda r: (aware(r["createdAt"]), str(r["id"])))
            copies = [r for r in [mine, *same] if r is not keeper]
            for copy in copies:
                await _merge_copy_into(tx, str(copy["rid"]), str(keeper["rid"]))
                await soft_remove(tx, str(copy["rid"]), actor, reason="VERIFIED_DUPLICATE")
                await tx.execute_raw(
                    'UPDATE "MediaFile" SET "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $2::jsonb WHERE "id" = $1',
                    str(copy["id"]), json.dumps({"removedAs": "VERIFIED_DUPLICATE", "keptMediaId": str(keeper["id"])}),
                )
                await _close_transcription(tx, str(copy["id"]), "Verified copy of another recording")
                if keeper.get("transcriptStatus") == "COMPLETED":
                    await tx.execute_raw(
                        'UPDATE "MediaFile" SET "transcriptStatus" = \'SKIPPED_DUPLICATE\', "transcriptText" = $2, "transcriptSummary" = $3 WHERE "id" = $1',
                        str(copy["id"]), keeper.get("transcriptText"), keeper.get("transcriptSummary"),
                    )
                elif copy.get("transcriptStatus") == "COMPLETED":
                    # The copy is the one with words: the kept take takes them, and needs no job of its own.
                    await tx.execute_raw(
                        'UPDATE "MediaFile" SET "transcriptStatus" = \'COMPLETED\', "transcriptText" = $2, "transcriptSummary" = $3 WHERE "id" = $1',
                        str(keeper["id"]), copy.get("transcriptText"), copy.get("transcriptSummary"),
                    )
                    await _close_transcription(tx, str(keeper["id"]), "Transcript copied from a verified copy")
                    await tx.execute_raw('UPDATE "MediaFile" SET "transcriptStatus" = \'SKIPPED_DUPLICATE\' WHERE "id" = $1', str(copy["id"]))
                else:
                    await tx.execute_raw('UPDATE "MediaFile" SET "transcriptStatus" = \'SKIPPED_DUPLICATE\' WHERE "id" = $1', str(copy["id"]))
            await sessions.bump_recording(tx, str(keeper["rid"]))
            outcome = "MERGED_INTO_TWIN" if keeper is not mine else "KEPT_EARLIEST"
            twin = keeper if keeper is not mine else copies[0]
        else:
            keeper = min([mine, *others], key=lambda r: (aware(r["createdAt"]), str(r["id"])))
            flagged = mine if keeper is not mine else others[0]
            await tx.execute_raw(
                'UPDATE "QuestionnaireRecording" SET "filing" = \'NEEDS_DECISION\', "decisionKind" = \'SAME_FILE_OTHER_SESSION\' WHERE "id" = $1',
                str(flagged["rid"]),
            )
            # A take waiting for a decision is never approved with its session (DESIGN 7.3; audit M1).
            await tx.execute_raw(
                'UPDATE "MediaFile" SET "status" = \'PENDING\', "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $2::jsonb WHERE "id" = $1',
                str(flagged["id"]), json.dumps({"questionnaireV2": {"twinRecordingId": str(keeper["rid"]), "twinMediaId": str(keeper["id"])}}),
            )
            await sessions.bump_recording(tx, str(flagged["rid"]))
            if keeper.get("transcriptStatus") == "COMPLETED":
                await _close_transcription(tx, str(flagged["id"]), "Verified copy of another recording")
                await tx.execute_raw(
                    'UPDATE "MediaFile" SET "transcriptStatus" = \'SKIPPED_DUPLICATE\', "transcriptText" = $2, "transcriptSummary" = $3 WHERE "id" = $1',
                    str(flagged["id"]), keeper.get("transcriptText"), keeper.get("transcriptSummary"),
                )
            outcome = "SAME_FILE_OTHER_SESSION"
            twin = keeper if keeper is not mine else flagged
    return {"status": outcome, "sha256": sha, "sizeBytes": size, "duplicateOf": str(twin["id"])}
