"""Old-build shims (DESIGN 5.6). Frozen behaviour: the request and response shapes of
``POST /questionnaire/interviews`` and ``/media/complete`` with ``linkedRecordType: "questionnaire"``
do not change, and NEITHER EVER RETURNS A NEW ERROR CODE. With QUESTIONNAIRE_V2 off, none of this
runs and both routes behave exactly as before.

* ``POST /interviews`` creates a session (never the old fold). ``sessionDate`` is today in IST,
  estimated. A typed title goes to ``legacyTitle``; the response's ``title`` is the generated one.
  ``responses`` become answer items, which never produce a 403.
* ``/media/complete`` for an old clip creates a recording through ``resolve_legacy_target``; an
  unresolved target is NEEDS_DECISION with up to three candidates for the uploader's inbox.
* After the cutoff (``QUESTIONNAIRE_MIN_APP_VERSION_FOR_CREATE``), an old build's CREATE gets 503 with
  ``Retry-After: 86400`` (old builds treat it as transient) and the P14 banner. Clips are always
  accepted.
"""

from __future__ import annotations

import json
import logging
import re
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from fastapi import HTTPException

from app.core.config import get_settings
from app.services.questionnaire_v2 import legacy_resolver, sessions, titles
from app.services.questionnaire_v2.common import IST, aware, naive_utc, now, today_ist

logger = logging.getLogger(__name__)
LEGACY_TYPES = ("questionnaire", "questionnaireinterview")
_STAMP = re.compile(r"_(\d{2})(\d{2})(\d{4})(\d{2})(\d{2})(\d{2})?(?=\.[^.]*$|$)")


def enabled() -> bool:
    return bool(get_settings().questionnaire_v2)


def _version_tuple(value: str | None) -> tuple[int, ...]:
    return tuple(int(p) for p in re.findall(r"\d+", value or "")[:4])


def assert_old_build_may_create(app_version: str | None, *, bearer: bool = False) -> None:
    """503 + Retry-After for a create from a build below the configured minimum (decision 5).

    Builds before v2 send no ``X-App-Version``: an app (bearer token) request without one IS such a
    build and counts as below any minimum. The web client signs in by cookie and ships with the
    server, so a header-less cookie request is never refused."""
    minimum = get_settings().questionnaire_min_app_version_for_create
    if not minimum:
        return
    if not app_version:
        if bearer:
            raise HTTPException(
                status_code=503,
                detail="Update when you have Wi-Fi. Your recordings are safe.",
                headers={"Retry-After": "86400"},
            )
        return
    if _version_tuple(app_version) < _version_tuple(minimum):
        raise HTTPException(
            status_code=503,
            detail="Update when you have Wi-Fi. Your recordings are safe.",
            headers={"Retry-After": "86400"},
        )


def _language_code(value: str | None) -> tuple[str | None, str | None]:
    from app import shared_vocab

    text = (value or "").strip()
    if not text:
        return None, None
    alias = shared_vocab.LANGUAGE_ALIASES.get(text) or shared_vocab.LANGUAGE_ALIASES.get(text.lower())
    if alias:
        return alias, None
    for code, label in shared_vocab.LANGUAGE_LABELS.items():
        if text.casefold() in (code, label.casefold()):
            return code, None
    return "other", text[:40]


async def create_interview_shim(payload: Any, user: Any, questionnaire_id: str, check: Any) -> Any:
    """``POST /interviews`` with v2 on: a NEW session every time, in the old response shape."""
    from app.core.db import db
    from app.services.workshop_access import pin_pending_if_late, stamp_workshop_submission

    language_code, language_other = _language_code(payload.language)
    started = aware(payload.recordedAt) or now()
    day = started.astimezone(IST).date() if payload.recordedAt else today_ist()
    data: dict[str, Any] = {"status": "PENDING", "extraMetadata": dict(payload.extraMetadata or {})}
    if payload.workshopId:
        stamp_workshop_submission(data, check=check)
        pin_pending_if_late(data, user, check=check)
    from prisma import Json

    from app.services.records import attach_location

    located = await attach_location({"location": payload.location})
    async with db.tx(max_wait=sessions.TX_MAX_WAIT, timeout=sessions.TX_TIMEOUT) as tx:
        created = await tx.questionnaireinterview.create(
            data={
                "title": "Interview",
                "legacyTitle": (payload.title or "").strip() or None,
                "questionnaireId": questionnaire_id,
                "workshopId": payload.workshopId,
                "joinCode": None,
                "sessionDate": datetime(day.year, day.month, day.day, tzinfo=UTC),
                "sessionDateEstimated": True,
                "interviewDate": payload.interviewDate or started,
                "startedAt": started,
                "place": (payload.place or "").strip() or None,
                "language": payload.language,
                "languageCode": language_code,
                "languageOther": language_other,
                "notes": payload.notes,
                "status": data["status"],
                "createdById": user.id,
                "recordedAt": started,
                "locationId": located.get("locationId"),
                "extraMetadata": Json({**data["extraMetadata"], "questionnaireV2": {"shim": True}}),
            }
        )
        if payload.artisanIds:
            await sessions.add_participants(tx, created.id, list(payload.artisanIds))
        await sessions.add_researcher(tx, created.id, user.id, "LEAD")
        await titles.regenerate_title(tx, created.id)
    if payload.responses:
        await responses_to_answers(created.id, questionnaire_id, payload.responses, user)
    return await db.questionnaireinterview.find_unique(where={"id": created.id})


async def responses_to_answers(session_id: str, questionnaire_id: str, responses: list[Any], user: Any) -> None:
    """Old ``responses`` as answer items, written per participant; a refusal is logged, never raised."""
    from app.core.db import db
    from app.schemas.questionnaire_v2 import AnswerItem, AnswersPut
    from app.services.questionnaire_v2 import answers

    artisans = await sessions.participant_ids(db, session_id)
    # The questions' lineages and every (participant, lineage) answer in two reads for the whole
    # list, not one plus one per participant for every response.
    wanted = [r for r in responses if (getattr(r, "answerText", None) or "").strip()] if artisans else []
    lineage_of: dict[str, str] = {}
    if wanted:
        for row in await db.query_raw(
            'SELECT "id", "lineageId" FROM "QuestionnaireQuestion" WHERE "id" = ANY($1::text[]) AND "questionnaireId" = $2',
            sorted({str(r.questionId) for r in wanted}), questionnaire_id,
        ):
            lineage_of[str(row["id"])] = str(row["lineageId"])
    currents: dict[tuple[str, str], dict[str, Any]] = {}
    if lineage_of:
        for row in await db.query_raw(
            'SELECT a."artisanId", a."questionLineageId", a."version" FROM "QuestionnaireAnswer" a '
            'WHERE a."artisanId" = ANY($1::text[]) AND a."questionLineageId" = ANY($2::text[])',
            list(artisans), sorted(set(lineage_of.values())),
        ):
            currents[(str(row["artisanId"]), str(row["questionLineageId"]))] = row
    items = []
    for response in responses:
        text = (getattr(response, "answerText", None) or "").strip()
        if not text or not artisans:
            continue
        lineage_id = lineage_of.get(str(response.questionId))
        if lineage_id is None:
            continue
        for artisan_id in artisans:
            current = currents.get((str(artisan_id), lineage_id))
            items.append(AnswerItem(
                artisanId=artisan_id, questionLineageId=lineage_id, text=text,
                origin="TRANSCRIPT" if getattr(response, "answerSource", None) == "TRANSCRIPT" else "TYPED",
                attribution="GROUP_SHARED" if len(artisans) > 1 else "INDIVIDUAL", sessionId=session_id,
                baseVersion=int(current["version"]) if current else None,
            ))
    if items:
        result = await answers.put_answers(AnswersPut(items=items), user)
        refused = [r for r in result["results"] if r["status"] in ("CONFLICT", "FORBIDDEN")]
        if refused:
            logger.info("old-build answers: %d of %d items not written (%s)", len(refused), len(items), session_id)


def _filename_stamp(name: str) -> datetime | None:
    match = _STAMP.search(name or "")
    if not match:
        return None
    d, m, y, hh, mm = (int(match.group(i)) for i in range(1, 6))
    try:
        return datetime(y, m, d, hh, mm, tzinfo=IST).astimezone(UTC)
    except ValueError:
        return None


async def record_legacy_clip(media: Any, user: Any, *, client: Any = None, provenance: str = "SHIM",
                             client_clip_key: str | None = None) -> str | None:
    """Give an old clip its v2 recording. Returns the recording id (None when it has no session)."""
    from app.core.db import db as default_db

    db = client or default_db
    session_id = getattr(media, "questionnaireInterviewId", None)
    if not session_id:
        return None
    existing = await db.query_raw('SELECT "id" FROM "QuestionnaireRecording" WHERE "mediaFileId" = $1', media.id)
    if existing:
        return str(existing[0]["id"])
    session = await sessions.load_session_row(db, session_id)
    if session is None:
        return None
    resolution = await legacy_resolver.resolve_legacy_target(db, media, str(session["questionnaireId"]))
    from app.services.questionnaire_v2 import coverage

    targets = await coverage.resolve_targets(db, str(session["questionnaireId"]), resolution.targets) if resolution.targets else []
    participants = await sessions.participant_ids(db, session_id)
    captured = _filename_stamp(getattr(media, "originalFilename", "")) or aware(getattr(media, "recordedAt", None)) or now()
    source = "UPLOAD_TIME"
    recording_id = uuid4().hex
    filing, decision = ("NEEDS_DECISION", "UNRESOLVED_LEGACY") if resolution.needs_decision else ("FILED", None)
    if not participants and not decision:
        filing, decision = "NEEDS_DECISION", "NO_PARTICIPANTS"
    async with default_db.tx(max_wait=sessions.TX_MAX_WAIT, timeout=sessions.TX_TIMEOUT) if client is None else _nullctx(db) as tx:
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", '
            '"rosterCount", "recordedById", "filing", "decisionKind", "provenance", "languageCode", "updatedAt") VALUES ($1, $2, $3, '
            '$4::timestamp, $5::"CapturedAtSource", $6, $7, $8::"RecordingFiling", $9, $10::"RecordingProvenance", $11, now()) '
            'ON CONFLICT ("clientClipKey") DO NOTHING',
            recording_id, media.id, client_clip_key or f"shim:{media.id}", naive_utc(captured), source, len(participants),
            str(getattr(media, "uploadedById", None) or user.id), filing, decision,
            resolution.provenance if provenance == "LEGACY" else "SHIM", session.get("languageCode"),
        )
        for t in targets:
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "questionLineageId", "askedQuestionId") '
                "VALUES ($1, $2, $3, $4, $5) ON CONFLICT DO NOTHING",
                recording_id, t["targetKey"], t["sectionId"], t["questionLineageId"], t["askedQuestionId"],
            )
        for artisan_id in participants:
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named") VALUES ($1, $2, \'ANSWERED\', false) '
                "ON CONFLICT DO NOTHING",
                recording_id, artisan_id,
            )
        if resolution.candidates:
            await tx.execute_raw(
                'UPDATE "MediaFile" SET "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $2::jsonb WHERE "id" = $1',
                media.id, json.dumps({"questionnaireV2": {"candidates": resolution.candidates, "resolverStep": resolution.step}}),
            )
        await tx.execute_raw(
            "INSERT INTO \"MediaProcessingJob\" (\"id\", \"jobType\", \"status\", \"priority\", \"mediaFileId\", \"updatedAt\") "
            "VALUES ($1, 'SHA_VERIFY', 'QUEUED', 60, $2, now())",
            uuid4().hex, media.id,
        )
    return recording_id


class _nullctx:
    def __init__(self, client: Any) -> None:
        self.client = client

    async def __aenter__(self) -> Any:
        return self.client

    async def __aexit__(self, *exc: Any) -> None:
        return None


async def after_legacy_complete(answer: dict[str, Any], payload: Any, user: Any) -> None:
    """Called after an old-shape /media/complete. Never raises: the old response stands."""
    if not enabled() or str(getattr(payload, "linkedRecordType", "") or "").lower() not in LEGACY_TYPES:
        return
    try:
        from app.core.db import db

        media = await db.mediafile.find_unique(where={"id": answer.get("id")})
        if media is not None and str(getattr(media, "mediaType", "")) in ("AUDIO", "VIDEO", "MediaType.AUDIO", "MediaType.VIDEO"):
            await record_legacy_clip(media, user)
    except Exception:  # noqa: BLE001 - the shim never turns an accepted clip into an error
        logger.exception("legacy clip shim failed for %s", answer.get("id"))
