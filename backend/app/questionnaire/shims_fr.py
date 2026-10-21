"""Old-build shims (DESIGN 5.6). Frozen behaviour: the request and response shapes of
``POST /questionnaire/interviews`` and ``/media/complete`` with ``linkedRecordType: "questionnaire"``
do not change, and NEITHER EVER RETURNS A NEW ERROR CODE. With QUESTIONNAIRE_V2 off, none of this
runs and both routes behave exactly as before.

* ``POST /interviews`` creates a session (never the old fold). ``sessionDate`` is today in IST,
  estimated. A typed title goes to ``legacyTitle``; the response's ``title`` is the generated one.
  ``responses`` become answer items, CREATE-ONLY (``baseVersion=None``), so an old build's stale
  text never overwrites a newer answer. A contributor or volunteer, who could never create, is
  attached to the matching open session instead (the old fold's v2 meaning), never refused anew.
* ``PATCH /interviews/{id}`` (an old build editing): the typed title goes to ``legacyTitle``, artisan
  changes become participant changes (a participant with takes is never dropped), and responses
  become answers (an old build may revise only its own earlier answer from that session).
* ``/media/complete`` for an old clip creates a recording through ``resolve_legacy_target``; an
  unresolved target is NEEDS_DECISION with up to three candidates for the uploader's inbox.
* After the cutoff, an old build's CREATE gets 503 with ``Retry-After: 86400`` (old builds treat it
  as transient) and the P14 banner. Clips are always accepted. The cutoff compares the build NUMBER
  (``X-App-Version-Code``), per platform (audit C7): see :func:`client_build`.
"""

from __future__ import annotations

import json
import logging
import re
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from app.questionnaire import legacy_resolver, ports, sessions, titles
from app.questionnaire.common import IST, aware, enum_text, naive_utc, now, today_ist
from app.questionnaire.errors import http_error
from app.questionnaire.ports import get_settings

logger = logging.getLogger(__name__)
LEGACY_TYPES = ("questionnaire", "questionnaireinterview")
_STAMP = re.compile(r"_(\d{2})(\d{2})(\d{4})(\d{2})(\d{2})(\d{2})?(?=\.[^.]*$|$)")


def enabled() -> bool:
    return bool(get_settings().questionnaire_v2)


#: THE HEADER CONTRACT (audit C7, H22), the same on every request from both clients:
#:   ``X-App-Platform``     ``android`` | ``web``
#:   ``X-App-Version-Code`` the build number, a positive integer (Android ``BuildConfig.VERSION_CODE``;
#:                          the web's build number). The version NAME was reset to 0.0.1 on 21 Oct,
#:                          so only the number rises.
#:   ``X-App-Version``      the version name, for people to read; never compared.
PLATFORM_HEADER = "x-app-platform"
VERSION_CODE_HEADER = "x-app-version-code"
VERSION_NAME_HEADER = "x-app-version"
PLATFORMS = ("android", "web")
UPDATE_MESSAGE = "Update when you have Wi-Fi. Your recordings are safe."


def client_build(headers: Any) -> tuple[str, int | None, str | None]:
    """``(platform, versionCode, versionName)`` of the calling build.

    A build that sends no ``X-App-Platform`` predates the contract: ``web-...`` names are the web,
    anything else is an Android build. A missing or unreadable code is None (an old build)."""
    name = (headers.get(VERSION_NAME_HEADER) or "").strip() or None
    platform = (headers.get(PLATFORM_HEADER) or "").strip().lower()
    if platform not in PLATFORMS:
        platform = "web" if (name or "").lower().startswith("web-") else "android"
    raw = (headers.get(VERSION_CODE_HEADER) or "").strip()
    code = int(raw) if raw.isdigit() and 0 < len(raw) <= 9 else None
    return platform, code, name


def minimum_version_code(platform: str) -> int | None:
    settings = get_settings()
    if platform == "web":
        return settings.questionnaire_min_web_version_code_for_create
    return settings.questionnaire_min_android_version_code_for_create


def assert_old_build_may_create(headers: Any) -> None:
    """503 + Retry-After for a create from a build below its platform's minimum (decision 5).

    Android: a build that sends no ``X-App-Version-Code`` predates the cutoff and is below any
    minimum. Web: the page ships with the server, so a web request with no code is never refused;
    one that sends a code below the web minimum is (a tab left open across a release)."""
    platform, code, _name = client_build(headers)
    minimum = minimum_version_code(platform)
    if not minimum:
        return
    if code is None and platform == "web":
        return
    if code is None or code < minimum:
        raise http_error(503, UPDATE_MESSAGE, headers={"Retry-After": "86400"})


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


async def matching_session(questionnaire_id: str, workshop_id: str | None, artisan_ids: list[str],
                           *, client: Any = None) -> str | None:
    """The session an old build's create by a contributor or volunteer lands on (audit H5): first an
    OPEN session today (IST) on this instrument and workshop sharing an artisan; else the newest
    session on this instrument holding exactly these artisans (the old fold's row). None when there
    is neither, which is exactly when the old route refused them too."""
    default_db = ports.db()

    db = client or default_db
    ids = sorted({a for a in artisan_ids if a})
    if not ids:
        return None
    rows = await db.query_raw(
        'SELECT i."id" FROM "QuestionnaireInterview" i WHERE i."questionnaireId" = $1 AND i."state" = \'OPEN\' '
        'AND i."mergedIntoId" IS NULL AND i."workshopId" IS NOT DISTINCT FROM $2 AND i."sessionDate" = $3::date '
        'AND EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" x WHERE x."interviewId" = i."id" AND x."artisanId" = ANY($4::text[])) '
        'ORDER BY i."startedAt" DESC NULLS LAST, i."createdAt" DESC LIMIT 1',
        questionnaire_id, workshop_id, today_ist().isoformat(), ids,
    )
    if rows:
        return str(rows[0]["id"])
    rows = await db.query_raw(
        'SELECT i."id" FROM "QuestionnaireInterview" i WHERE i."questionnaireId" = $1 AND i."mergedIntoId" IS NULL '
        'AND (SELECT array_agg(x."artisanId" ORDER BY x."artisanId") FROM "QuestionnaireInterviewArtisan" x '
        '     WHERE x."interviewId" = i."id") = $2::text[] '
        'ORDER BY i."sessionDate" DESC NULLS LAST, i."createdAt" DESC LIMIT 1',
        questionnaire_id, ids,
    )
    return str(rows[0]["id"]) if rows else None


async def attach_to_session(session_id: str, payload: Any, user: Any, questionnaire_id: str) -> Any:
    """A non-creator's old-build create, attached: they join as a researcher and their responses
    become answers on that session. The old response shape, the session's row."""
    db = ports.db()

    role = "VOLUNTEER" if str(enum_text(getattr(user, "role", None))) == "CROWDSOURCE_VOLUNTEER" else "CONTRIBUTOR"
    await sessions.add_researcher(db, session_id, user.id, role)
    if payload.responses:
        await responses_to_answers(session_id, questionnaire_id, payload.responses, user)
    return await db.questionnaireinterview.find_unique(where={"id": session_id})


async def create_interview_shim(payload: Any, user: Any, questionnaire_id: str, check: Any) -> Any:
    """``POST /interviews`` with v2 on: a NEW session every time, in the old response shape."""
    db = ports.db()
    from app.questionnaire.ports import pin_pending_if_late, stamp_workshop_submission

    language_code, language_other = _language_code(payload.language)
    started = aware(payload.recordedAt) or now()
    day = started.astimezone(IST).date() if payload.recordedAt else today_ist()
    data: dict[str, Any] = {"status": "PENDING", "extraMetadata": dict(payload.extraMetadata or {})}
    if payload.workshopId:
        stamp_workshop_submission(data, check=check)
        pin_pending_if_late(data, user, check=check)
    from app.questionnaire.ports import attach_location
    from prisma import Json

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
    """Old ``responses`` as answer items, written per participant; a refusal is logged, never raised.

    CREATE-ONLY (audit H5): an old build does not know the answer's version, so sending the current
    one would let its stale text overwrite a newer answer. The one exception is the build's OWN
    earlier answer from this same session (an old build re-saving its form), which it may revise."""
    db = ports.db()
    from app.questionnaire import answers
    from app.questionnaire.schemas import AnswerItem, AnswersPut

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
            'SELECT a."artisanId", a."questionLineageId", a."version", rv."sessionId", rv."actorId" FROM "QuestionnaireAnswer" a '
            'LEFT JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
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
            own = bool(current) and str(current.get("sessionId")) == session_id and str(current.get("actorId")) == str(user.id)
            items.append(AnswerItem(
                artisanId=artisan_id, questionLineageId=lineage_id, text=text,
                origin="TRANSCRIPT" if getattr(response, "answerSource", None) == "TRANSCRIPT" else "TYPED",
                attribution="GROUP_SHARED" if len(artisans) > 1 else "INDIVIDUAL", sessionId=session_id,
                baseVersion=int(current["version"]) if own else None,
            ))
    if items:
        result = await answers.put_answers(AnswersPut(items=items), user)
        refused = [r for r in result["results"] if r["status"] in ("CONFLICT", "FORBIDDEN")]
        if refused:
            logger.info("old-build answers: %d of %d items not written (%s)", len(refused), len(items), session_id)


async def patch_interview_shim(interview: Any, payload: Any, user: Any) -> dict[str, Any]:
    """``PATCH /interviews/{id}`` from an old build with v2 on (audit H5). Returns the column changes
    the route still applies through its own guards (title moved to ``legacyTitle``); participants and
    answers are written here, through v2."""
    db = ports.db()

    session_id = str(interview.id)
    questionnaire_id = str(getattr(interview, "questionnaireId", "") or "")
    changes: dict[str, Any] = {}
    if payload.artisanIds is not None:
        wanted = [a for a in dict.fromkeys(payload.artisanIds) if a]
        current = await sessions.participant_ids(db, session_id)
        added = [a for a in wanted if a not in current]
        kept: list[str] = []
        async with db.tx(max_wait=sessions.TX_MAX_WAIT, timeout=sessions.TX_TIMEOUT) as tx:
            if added:
                await sessions.add_participants(tx, session_id, added, now())
            for artisan_id in [a for a in current if a not in wanted]:
                if await sessions.takes_for(tx, session_id, artisan_id):
                    kept.append(artisan_id)  # their takes would stop counting for them: never dropped here
                    continue
                await tx.execute_raw(
                    'DELETE FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1 AND "artisanId" = $2', session_id, artisan_id
                )
            await sessions.bump_session(tx, session_id)
            await titles.regenerate_title(tx, session_id)
        changes["participantsKept"] = kept
        if kept:
            logger.info("old-build edit kept %d participant(s) with takes on %s", len(kept), session_id)
    if payload.responses:
        await responses_to_answers(session_id, questionnaire_id, payload.responses, user)
    return changes


def legacy_title_change(data: dict[str, Any]) -> dict[str, Any]:
    """An old build's typed title is kept as ``legacyTitle``; the generated ``title`` is never
    overwritten (audit H5)."""
    if "title" in data:
        typed = (data.pop("title") or "").strip()
        data["legacyTitle"] = typed or None
    return data


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
                             client_clip_key: str | None = None, targets: list[dict[str, Any]] | None = None,
                             subjects: list[str] | None = None) -> str | None:
    """Give an old clip its v2 recording. Returns the recording id (None when it has no session).

    ``targets`` (already resolved) and ``subjects`` are given by "File it" (provenance MANUAL); the
    shim and the backfill's catch-up resolve them from the clip. A NEEDS_DECISION take is PENDING,
    never approved with its session (DESIGN 7.3; audit M1)."""
    default_db = ports.db()

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
    from app.questionnaire import coverage

    manual = targets is not None
    resolution = None if manual else await legacy_resolver.resolve_legacy_target(db, media, str(session["questionnaireId"]))
    if not manual:
        targets = await coverage.resolve_targets(db, str(session["questionnaireId"]), resolution.targets) if resolution.targets else []
    participants = list(subjects) if subjects else await sessions.participant_ids(db, session_id)
    captured = _filename_stamp(getattr(media, "originalFilename", "")) or aware(getattr(media, "recordedAt", None)) or now()
    source = "UPLOAD_TIME"
    recording_id = uuid4().hex
    filing, decision = ("NEEDS_DECISION", "UNRESOLVED_LEGACY") if resolution and resolution.needs_decision else ("FILED", None)
    if not participants and not decision:
        filing, decision = "NEEDS_DECISION", "NO_PARTICIPANTS"
    if manual:
        stored_provenance = "MANUAL"
    elif provenance == "LEGACY":
        stored_provenance = resolution.provenance
    else:
        stored_provenance = "SHIM"
    async with default_db.tx(max_wait=sessions.TX_MAX_WAIT, timeout=sessions.TX_TIMEOUT) if client is None else _nullctx(db) as tx:
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", '
            '"rosterCount", "recordedById", "filing", "decisionKind", "provenance", "languageCode", "updatedAt") VALUES ($1, $2, $3, '
            '$4::timestamp, $5::"CapturedAtSource", $6, $7, $8::"RecordingFiling", $9, $10::"RecordingProvenance", $11, now()) '
            'ON CONFLICT ("clientClipKey") DO NOTHING',
            recording_id, media.id, client_clip_key or f"shim:{media.id}", naive_utc(captured), source, len(participants),
            str(getattr(media, "uploadedById", None) or user.id), filing, decision,
            stored_provenance, session.get("languageCode"),
        )
        if subjects:
            missing = sorted(set(participants) - set(await sessions.participant_ids(tx, session_id)))
            if missing:
                await sessions.add_participants(tx, session_id, missing)
                await titles.regenerate_title(tx, session_id)
        if filing == "NEEDS_DECISION":
            await tx.execute_raw('UPDATE "MediaFile" SET "status" = \'PENDING\' WHERE "id" = $1', media.id)
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
        if resolution and resolution.candidates:
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

    async def __aexit__(self, *exc: object) -> None:
        return None


async def after_legacy_complete(answer: dict[str, Any], payload: Any, user: Any) -> None:
    """Called after an old-shape /media/complete. Never raises: the old response stands."""
    if not enabled() or str(getattr(payload, "linkedRecordType", "") or "").lower() not in LEGACY_TYPES:
        return
    try:
        db = ports.db()

        media = await db.mediafile.find_unique(where={"id": answer.get("id")})
        if media is not None and str(getattr(media, "mediaType", "")) in ("AUDIO", "VIDEO", "MediaType.AUDIO", "MediaType.VIDEO"):
            await record_legacy_clip(media, user)
    except Exception:
        logger.exception("legacy clip shim failed for %s", answer.get("id"))
