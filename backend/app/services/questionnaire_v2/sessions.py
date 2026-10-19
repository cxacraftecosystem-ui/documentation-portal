"""Sessions: one sitting, a dated event (DESIGN sections 3.7, 3.8, 3.12 and 5.1).

* ``POST /sessions`` ALWAYS creates; a repeated ``clientKey`` returns the existing row (200).
* The title is generated (``titles.regenerate_title``) in the same transaction as any change to its
  inputs, and never accepted from a client (the route answers 422 ``TITLE_IS_GENERATED``).
* The creator and anyone who joins are researchers; volunteers are added through "Add researcher".
* CLOSED only stops the UI from starting new takes; the server never rejects a clip for it.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from typing import Any

from prisma.errors import UniqueViolationError

from app.core.roles import can_create_records, has_rank, is_admin
from app.services import consent_gate
from app.services.questionnaire_v2 import titles
from app.services.questionnaire_v2.common import (
    IST,
    aware,
    as_date,
    enum_text,
    fail,
    get,
    iso,
    naive_utc,
    new_join_code,
    now,
)

TX_TIMEOUT = timedelta(seconds=30)
TX_MAX_WAIT = timedelta(seconds=15)
PAGE_MAX = 100


def _client(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


# ── reading ────────────────────────────────────────────────────────────────────────────────────
async def load_session_row(client: Any, session_id: str) -> dict[str, Any] | None:
    rows = await client.query_raw(
        'SELECT i.*, i."state"::text AS state_t, i."purpose"::text AS purpose_t, i."origin"::text AS origin_t, '
        'i."status"::text AS status_t FROM "QuestionnaireInterview" i WHERE i."id" = $1',
        session_id,
    )
    return rows[0] if rows else None


async def require_session(client: Any, session_id: str) -> dict[str, Any]:
    row = await load_session_row(client, session_id)
    if row is None:
        fail("SESSION_UNKNOWN")
    return row


async def researcher_ids(client: Any, session_id: str) -> set[str]:
    rows = await client.query_raw('SELECT "userId" FROM "QuestionnaireInterviewResearcher" WHERE "interviewId" = $1', session_id)
    return {str(r["userId"]) for r in rows}


async def participant_ids(client: Any, session_id: str) -> list[str]:
    rows = await client.query_raw(
        'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1 ORDER BY "sortOrder", "createdAt"',
        session_id,
    )
    return [str(r["artisanId"]) for r in rows]


async def can_work_in(client: Any, user: Any, session: dict[str, Any]) -> bool:
    """Researchers of the session, its creator, Professor and above."""
    if is_admin(user) or has_rank(user, "PROFESSOR"):
        return True
    if str(session.get("createdById")) == str(user.id):
        return True
    return str(user.id) in await researcher_ids(client, str(session["id"]))


async def assert_can_work_in(client: Any, user: Any, session: dict[str, Any]) -> None:
    if not await can_work_in(client, user, session):
        fail("FORBIDDEN", "Only the session's researchers can change it.")


async def _counts(client: Any, session_ids: list[str]) -> dict[str, dict[str, int]]:
    if not session_ids:
        return {}
    rows = await client.query_raw(
        'SELECT m."questionnaireInterviewId" AS sid, count(DISTINCT r."id") AS takes, '
        'count(DISTINCT r."id") FILTER (WHERE r."filing" = \'NEEDS_DECISION\') AS nd '
        'FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        'WHERE r."removedAt" IS NULL AND m."removedAt" IS NULL AND m."questionnaireInterviewId" = ANY($1::text[]) '
        'GROUP BY 1',
        session_ids,
    )
    sections = await client.query_raw(
        'SELECT tv."sessionId" AS sid, count(DISTINCT COALESCE(tv."sectionId", q."sectionId")) AS n '
        'FROM questionnaire_take_v tv LEFT JOIN "QuestionnaireQuestion" q ON q."id" = tv."questionLineageId" '
        'WHERE tv."sessionId" = ANY($1::text[]) GROUP BY 1',
        session_ids,
    )
    out = {sid: {"sections": 0, "takes": 0, "needsDecision": 0} for sid in session_ids}
    for r in rows:
        out[str(r["sid"])].update(takes=int(r["takes"]), needsDecision=int(r["nd"]))
    for r in sections:
        out[str(r["sid"])]["sections"] = int(r["n"])
    return out


async def serialize_sessions(client: Any, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Contract ``Session`` objects for these raw rows, in four batched reads."""
    if not rows:
        return []
    sids = [str(r["id"]) for r in rows]
    parts = await client.query_raw(
        'SELECT qa."interviewId" AS sid, qa."artisanId", a."name", qa."joinedAt", qa."leftAt" '
        'FROM "QuestionnaireInterviewArtisan" qa JOIN "Artisan" a ON a."id" = qa."artisanId" '
        'WHERE qa."interviewId" = ANY($1::text[]) ORDER BY qa."sortOrder", qa."createdAt", a."id"',
        sids,
    )
    researchers = await client.query_raw(
        'SELECT r."interviewId" AS sid, r."userId", u."name", r."role"::text AS role FROM "QuestionnaireInterviewResearcher" r '
        'JOIN "User" u ON u."id" = r."userId" WHERE r."interviewId" = ANY($1::text[]) '
        "ORDER BY CASE r.\"role\" WHEN 'LEAD' THEN 0 WHEN 'CONTRIBUTOR' THEN 1 ELSE 2 END, r.\"addedAt\"",
        sids,
    )
    meta = await client.query_raw(
        'SELECT i."id" AS sid, q."id" AS qid, q."title" AS qtitle, w."id" AS wid, w."title" AS wtitle '
        'FROM "QuestionnaireInterview" i JOIN "Questionnaire" q ON q."id" = i."questionnaireId" '
        'LEFT JOIN "Workshop" w ON w."id" = i."workshopId" WHERE i."id" = ANY($1::text[])',
        sids,
    )
    reasons = await client.query_raw(
        'SELECT DISTINCT ON ("recordId") "recordId" AS sid, "reason", "status"::text AS status FROM "ReviewLog" '
        "WHERE \"recordType\" = 'QUESTIONNAIRE' AND \"recordId\" = ANY($1::text[]) "
        'ORDER BY "recordId", "createdAt" DESC',
        sids,
    )
    artisan_ids = list({str(p["artisanId"]) for p in parts})
    consent = await consent_gate.current_states("ARTISAN", artisan_ids, client=client)
    counts = await _counts(client, sids)
    meta_by = {str(m["sid"]): m for m in meta}
    reason_by = {str(r["sid"]): r for r in reasons}
    out: list[dict[str, Any]] = []
    for row in rows:
        sid = str(row["id"])
        m = meta_by.get(sid, {})
        participants = [
            {
                "artisanId": str(p["artisanId"]),
                "name": p["name"],
                "joinedAt": iso(p.get("joinedAt")),
                "leftAt": iso(p.get("leftAt")),
                "consent": {
                    "state": (consent.get(str(p["artisanId"])) or {}).get("state") or "NOT_RECORDED",
                    "source": (consent.get(str(p["artisanId"])) or {}).get("source"),
                },
            }
            for p in parts if str(p["sid"]) == sid
        ]
        last_review = reason_by.get(sid)
        session_date = as_date(row.get("sessionDate"))
        qr = {
            "v": 1, "clientKey": row.get("clientKey"), "questionnaireId": str(row["questionnaireId"]),
            "workshopId": row.get("workshopId"), "sessionDate": session_date.isoformat() if session_date else None,
            "participants": [p["artisanId"] for p in participants],
        }
        out.append(
            {
                "id": sid,
                "clientKey": row.get("clientKey"),
                "version": int(row.get("version") or 1),
                "title": row.get("title") or "",
                "note": row.get("note"),
                "legacyTitle": row.get("legacyTitle"),
                "questionnaire": {"id": str(m.get("qid") or row["questionnaireId"]), "title": m.get("qtitle") or ""},
                "workshop": {"id": str(m["wid"]), "name": m.get("wtitle") or ""} if m.get("wid") else None,
                "designWorkshopId": row.get("designWorkshopId"),
                "sessionDate": session_date.isoformat() if session_date else None,
                "sessionDateEstimated": bool(row.get("sessionDateEstimated")),
                "startedAt": iso(row.get("startedAt")),
                "endedAt": iso(row.get("endedAt")),
                "closedAt": iso(row.get("closedAt")),
                "state": row.get("state_t") or enum_text(row.get("state")) or "OPEN",
                "purpose": row.get("purpose_t") or enum_text(row.get("purpose")) or "FIRST_SITTING",
                "origin": row.get("origin_t") or enum_text(row.get("origin")) or "NATIVE",
                "mode": "GROUP" if len(participants) > 1 else "INDIVIDUAL",
                "participantCount": len(participants),
                "place": row.get("place"),
                "languageCode": row.get("languageCode"),
                "languageOther": row.get("languageOther"),
                "joinCode": row.get("joinCode"),
                "joinQr": json.dumps(qr, separators=(",", ":")) if row.get("clientKey") else None,
                "participants": participants,
                "researchers": [
                    {"userId": str(r["userId"]), "name": r["name"], "role": r["role"]}
                    for r in researchers if str(r["sid"]) == sid
                ],
                "status": row.get("status_t") or enum_text(row.get("status")) or "PENDING",
                "submittedAt": iso(row.get("submittedAt")),
                "reviewReasons": [last_review["reason"]] if last_review and last_review.get("reason")
                and last_review.get("status") != "APPROVED" else [],
                "counts": counts.get(sid, {"sections": 0, "takes": 0, "needsDecision": 0}),
            }
        )
    return out


async def session_payload(client: Any, session_id: str) -> dict[str, Any]:
    row = await require_session(client, session_id)
    return (await serialize_sessions(client, [row]))[0]


# ── writing ────────────────────────────────────────────────────────────────────────────────────
async def _assert_exists(client: Any, table: str, ids: list[str], what: str) -> None:
    if not ids:
        return
    rows = await client.query_raw(f'SELECT "id" FROM "{table}" WHERE "id" = ANY($1::text[])', list(set(ids)))
    missing = sorted(set(ids) - {str(r["id"]) for r in rows})
    if missing:
        fail("VALIDATION_FAILED", f"Unknown {what}: {', '.join(missing)}", fields={what: missing})


async def _bump(client: Any, session_id: str) -> None:
    await client.execute_raw(
        'UPDATE "QuestionnaireInterview" SET "version" = "version" + 1, "updatedAt" = now() WHERE "id" = $1', session_id
    )


async def add_researcher(client: Any, session_id: str, user_id: str, role: str) -> None:
    await client.execute_raw(
        'INSERT INTO "QuestionnaireInterviewResearcher" ("interviewId", "userId", "role") VALUES ($1, $2, $3::"ResearcherRole") '
        'ON CONFLICT ("interviewId", "userId") DO NOTHING',
        session_id, user_id, role,
    )


async def add_participants(client: Any, session_id: str, artisan_ids: list[str], joined_at: datetime | None = None) -> int:
    """Insert participants that are not already there; returns how many were added."""
    rows = await client.query_raw(
        'SELECT COALESCE(max("sortOrder"), -1) AS m FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', session_id
    )
    order = int(rows[0]["m"]) + 1 if rows else 0
    added = 0
    for artisan_id in dict.fromkeys(artisan_ids):
        added += await client.execute_raw(
            'INSERT INTO "QuestionnaireInterviewArtisan" ("interviewId", "artisanId", "sortOrder", "joinedAt") '
            'VALUES ($1, $2, $3, $4::timestamp) ON CONFLICT ("interviewId", "artisanId") DO NOTHING',
            session_id, artisan_id, order, naive_utc(joined_at),
        )
        order += 1
    return added


def _volunteer(user: Any) -> bool:
    return str(enum_text(get(user, "role"))) == "CROWDSOURCE_VOLUNTEER"


async def create_session(payload: Any, user: Any, *, client: Any = None, origin: str = "NATIVE",
                         legacy_title: str | None = None, estimated_date: bool = False,
                         extra: dict[str, Any] | None = None) -> tuple[dict[str, Any], bool]:
    """Create a session; returns ``(session, created)``. A repeated clientKey returns the row with
    ``created=False`` (200). Volunteers cannot create (they record into sessions they are on)."""
    from app.core.db import db as default_db
    from app.services.workshop_access import enforce_workshop_submission, pin_pending_if_late, stamp_workshop_submission

    db = _client(client)
    existing = await db.query_raw('SELECT "id" FROM "QuestionnaireInterview" WHERE "clientKey" = $1', payload.clientKey)
    if existing:
        return await session_payload(db, str(existing[0]["id"])), False
    if not can_create_records(user) or _volunteer(user):
        fail("FORBIDDEN", "Volunteers record into sessions they are added to; they can't start one.")
    artisan_ids = list(dict.fromkeys(p.artisanId for p in payload.participants))
    await _assert_exists(db, "Questionnaire", [payload.questionnaireId], "questionnaireId")
    await _assert_exists(db, "Workshop", [payload.workshopId] if payload.workshopId else [], "workshopId")
    await _assert_exists(db, "Artisan", artisan_ids, "artisanIds")
    await _assert_exists(db, "User", [r.userId for r in payload.researchers], "researchers")
    check = await enforce_workshop_submission(user, payload.workshopId) if payload.workshopId else None
    data: dict[str, Any] = {"status": "PENDING", "extraMetadata": dict(extra or {})}
    if payload.workshopId:
        stamp_workshop_submission(data, check=check)
        pin_pending_if_late(data, user, check=check)
    started = aware(payload.startedAt) or now()
    session_date = payload.sessionDate
    language_label = None
    if payload.languageCode:
        from app import shared_vocab

        language_label = payload.languageOther if payload.languageCode == "other" else shared_vocab.LANGUAGE_LABELS.get(payload.languageCode)
    interview_date = datetime(session_date.year, session_date.month, session_date.day, 12, 0, tzinfo=IST) if session_date else None
    tx_owner = db if client is not None else default_db
    for _attempt in range(5):
        session_id = None
        try:
            async with tx_owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
                created = await tx.questionnaireinterview.create(
                    data={
                        "title": "Interview",
                        "questionnaireId": payload.questionnaireId,
                        "workshopId": payload.workshopId,
                        "designWorkshopId": payload.designWorkshopId,
                        "clientKey": payload.clientKey,
                        "joinCode": new_join_code(),
                        "sessionDate": datetime(session_date.year, session_date.month, session_date.day, tzinfo=UTC),
                        "sessionDateEstimated": estimated_date,
                        "interviewDate": interview_date,
                        "startedAt": started,
                        "purpose": payload.purpose,
                        "origin": origin,
                        "place": (payload.place or "").strip() or None,
                        "language": language_label,
                        "languageCode": payload.languageCode,
                        "languageOther": payload.languageOther if payload.languageCode == "other" else None,
                        "note": (payload.note or "").strip() or None,
                        "legacyTitle": legacy_title,
                        "status": data["status"],
                        "createdById": user.id,
                        **({"extraMetadata": _json(data["extraMetadata"])} if data["extraMetadata"] else {}),
                    }
                )
                session_id = created.id
                await add_participants(tx, session_id, artisan_ids)
                await add_researcher(tx, session_id, user.id, "LEAD")
                for r in payload.researchers:
                    if r.userId != user.id:
                        await add_researcher(tx, session_id, r.userId, r.role)
                await titles.regenerate_title(tx, session_id)
            break
        except UniqueViolationError as exc:
            again = await db.query_raw('SELECT "id" FROM "QuestionnaireInterview" WHERE "clientKey" = $1', payload.clientKey)
            if again:  # the same clientKey won a race: that row is this create
                return await session_payload(db, str(again[0]["id"])), False
            if "joinCode" not in str(exc):
                raise
    return await session_payload(db, session_id), True


def _json(value: Any) -> Any:
    from prisma import Json

    return Json(value)


PATCHABLE = ("place", "languageCode", "languageOther", "note", "sessionDate", "workshopId", "designWorkshopId", "purpose")


async def has_content(client: Any, session_id: str) -> int:
    rows = await client.query_raw(
        'SELECT (SELECT count(*) FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        '        WHERE m."questionnaireInterviewId" = $1 AND r."removedAt" IS NULL) + '
        '       (SELECT count(*) FROM "QuestionnaireAnswerRevision" WHERE "sessionId" = $1) AS n',
        session_id,
    )
    return int(rows[0]["n"]) if rows else 0


async def patch_session(session_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.core.db import db as default_db

    db = _client(client)
    row = await require_session(db, session_id)
    await assert_can_work_in(db, user, row)
    sent = payload.model_fields_set
    current = (await serialize_sessions(db, [row]))[0]
    if payload.version != int(row.get("version") or 1):
        diff = {k: {"current": current.get(k), "yours": _plain(getattr(payload, k))} for k in PATCHABLE if k in sent
                and _plain(getattr(payload, k)) != current.get(k)}
        fail("STALE_VERSION", current=current, diff=diff)
    if "questionnaireId" in sent and payload.questionnaireId and payload.questionnaireId != str(row["questionnaireId"]):
        if await has_content(db, session_id):
            fail("INSTRUMENT_LOCKED")
        await _assert_exists(db, "Questionnaire", [payload.questionnaireId], "questionnaireId")
    workshop_id = payload.workshopId if "workshopId" in sent else row.get("workshopId")
    design_id = payload.designWorkshopId if "designWorkshopId" in sent else row.get("designWorkshopId")
    if workshop_id and design_id:
        fail("VALIDATION_FAILED", "A session links to a workshop or a design workshop, not both")
    if "workshopId" in sent and payload.workshopId:
        await _assert_exists(db, "Workshop", [payload.workshopId], "workshopId")
    data: dict[str, Any] = {}
    for key in PATCHABLE:
        if key in sent:
            value = getattr(payload, key)
            if key == "sessionDate" and value is not None:
                data["sessionDate"] = datetime(value.year, value.month, value.day, tzinfo=UTC)
                data["sessionDateEstimated"] = False
                data["interviewDate"] = datetime(value.year, value.month, value.day, 12, 0, tzinfo=IST)
            elif key in ("place", "note"):
                data[key] = (value or "").strip() or None
            else:
                data[key] = value
    if "questionnaireId" in sent and payload.questionnaireId:
        data["questionnaireId"] = payload.questionnaireId
    if data.get("languageCode") and data["languageCode"] != "other":
        data["languageOther"] = None
    owner = db if client is not None else default_db
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        changed = await tx.questionnaireinterview.update_many(
            where={"id": session_id, "version": int(row.get("version") or 1)},
            data={**data, "version": {"increment": 1}},
        )
        if not changed:
            fail("STALE_VERSION", current=current, diff={})
        await titles.regenerate_title(tx, session_id)
    return await session_payload(db, session_id)


def _plain(value: Any) -> Any:
    return value.isoformat() if hasattr(value, "isoformat") else value


async def add_participant(session_id: str, artisan_id: str, joined_at: datetime | None, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.core.db import db as default_db

    db = _client(client)
    row = await require_session(db, session_id)
    await assert_can_work_in(db, user, row)
    await _assert_exists(db, "Artisan", [artisan_id], "artisanId")
    owner = db if client is not None else default_db
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        if await add_participants(tx, session_id, [artisan_id], joined_at or now()):
            await _bump(tx, session_id)
            await titles.regenerate_title(tx, session_id)
    return await session_payload(db, session_id)


async def patch_participant(session_id: str, artisan_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _client(client)
    row = await require_session(db, session_id)
    await assert_can_work_in(db, user, row)
    sent = payload.model_fields_set
    sets, params = [], [session_id, artisan_id]
    for key in ("joinedAt", "leftAt"):
        if key in sent:
            params.append(naive_utc(aware(getattr(payload, key))))
            sets.append(f'"{key}" = ${len(params)}::timestamp')
    if sets:
        changed = await db.execute_raw(
            f'UPDATE "QuestionnaireInterviewArtisan" SET {", ".join(sets)} WHERE "interviewId" = $1 AND "artisanId" = $2', *params
        )
        if not changed:
            fail("NOT_FOUND", "That artisan is not a participant of this session.")
        await _bump(db, session_id)
    return await session_payload(db, session_id)


async def takes_for(client: Any, session_id: str, artisan_id: str) -> list[str]:
    rows = await client.query_raw(
        'SELECT DISTINCT r."id" FROM "QuestionnaireRecordingSubject" s JOIN "QuestionnaireRecording" r ON r."id" = s."recordingId" '
        'JOIN "MediaFile" m ON m."id" = r."mediaFileId" WHERE m."questionnaireInterviewId" = $1 AND s."artisanId" = $2 '
        'AND r."removedAt" IS NULL',
        session_id, artisan_id,
    )
    return [str(r["id"]) for r in rows]


async def remove_participant(session_id: str, artisan_id: str, detach: bool, user: Any, *, client: Any = None) -> dict[str, Any]:
    from app.core.db import db as default_db

    db = _client(client)
    row = await require_session(db, session_id)
    await assert_can_work_in(db, user, row)
    takes = await takes_for(db, session_id, artisan_id)
    if takes and not detach:
        fail("PARTICIPANT_HAS_TAKES", count=len(takes))
    owner = db if client is not None else default_db
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        for recording_id in takes:
            before = await tx.query_raw(
                'SELECT "artisanId", "role"::text AS role FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1', recording_id
            )
            await tx.execute_raw(
                'DELETE FROM "QuestionnaireRecordingSubject" WHERE "recordingId" = $1 AND "artisanId" = $2', recording_id, artisan_id
            )
            await log_event(tx, recording_id, "SUBJECTS", {"subjects": before}, {"detached": artisan_id}, user.id)
            await bump_recording(tx, recording_id)
        await tx.execute_raw(
            'DELETE FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1 AND "artisanId" = $2', session_id, artisan_id
        )
        await _bump(tx, session_id)
        await titles.regenerate_title(tx, session_id)
    return await session_payload(db, session_id)


async def log_event(client: Any, recording_id: str, kind: str, before: Any, after: Any, actor_id: str) -> None:
    from uuid import uuid4

    from app.services.questionnaire_v2.common import jsonable

    await client.execute_raw(
        'INSERT INTO "QuestionnaireRecordingEvent" ("id", "recordingId", "kind", "before", "after", "actorId") '
        "VALUES ($1, $2, $3, $4::jsonb, $5::jsonb, $6)",
        uuid4().hex, recording_id, kind, json.dumps(jsonable(before)), json.dumps(jsonable(after)), actor_id,
    )


async def bump_recording(client: Any, recording_id: str) -> None:
    await client.execute_raw(
        'UPDATE "QuestionnaireRecording" SET "changeSeq" = nextval(\'questionnaire_change_seq\'), '
        '"version" = "version" + 1, "updatedAt" = now() WHERE "id" = $1',
        recording_id,
    )


async def add_researcher_route(session_id: str, user_id: str, role: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _client(client)
    row = await require_session(db, session_id)
    await assert_can_work_in(db, user, row)
    await _assert_exists(db, "User", [user_id], "userId")
    await add_researcher(db, session_id, user_id, role)
    return await session_payload(db, session_id)


async def join(join_code: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _client(client)
    rows = await db.query_raw(
        'SELECT "id" FROM "QuestionnaireInterview" WHERE "joinCode" = $1 AND "state" = \'OPEN\' AND "mergedIntoId" IS NULL',
        join_code.strip().upper(),
    )
    if not rows:
        fail("JOIN_CODE_UNKNOWN")
    session_id = str(rows[0]["id"])
    await add_researcher(db, session_id, user.id, "VOLUNTEER" if _volunteer(user) else "CONTRIBUTOR")
    return await session_payload(db, session_id)


async def finish(session_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    db = _client(client)
    row = await require_session(db, session_id)
    await assert_can_work_in(db, user, row)
    await db.execute_raw(
        'UPDATE "QuestionnaireInterview" SET "state" = \'CLOSED\', "closedAt" = COALESCE("closedAt", now()), '
        '"endedAt" = COALESCE("endedAt", now()), "version" = "version" + 1, "updatedAt" = now() WHERE "id" = $1',
        session_id,
    )
    return await session_payload(db, session_id)


async def merge_into(source_id: str, target_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    """Move recordings, participants, researchers and answers onto the target, in one transaction."""
    from app.core.db import db as default_db

    db = _client(client)
    if source_id == target_id:
        fail("VALIDATION_FAILED", "A session can't be merged into itself.")
    source = await require_session(db, source_id)
    target = await require_session(db, target_id)
    if not is_admin(user):
        if not (await can_work_in(db, user, source) and await can_work_in(db, user, target)):
            fail("FORBIDDEN", "Only researchers of both sessions, or an admin, can merge them.")
    if str(source["questionnaireId"]) != str(target["questionnaireId"]):
        fail("MERGE_INSTRUMENT_MISMATCH")
    owner = db if client is not None else default_db
    async with owner.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        moved = await tx.query_raw(
            'SELECT r."id" FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
            'WHERE m."questionnaireInterviewId" = $1', source_id,
        )
        await tx.execute_raw('UPDATE "MediaFile" SET "questionnaireInterviewId" = $2 WHERE "questionnaireInterviewId" = $1', source_id, target_id)
        await tx.execute_raw(
            'UPDATE "MediaFile" SET "linkedRecordId" = $2 WHERE "linkedRecordId" = $1 '
            "AND lower(\"linkedRecordType\") IN ('questionnaire', 'questionnaireinterview', 'questionnaire_session')",
            source_id, target_id,
        )
        for r in moved:
            await log_event(tx, str(r["id"]), "MOVE", {"sessionId": source_id}, {"sessionId": target_id, "merge": True}, user.id)
            await bump_recording(tx, str(r["id"]))
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireInterviewArtisan" ("interviewId", "artisanId", "sortOrder", "joinedAt", "leftAt") '
            'SELECT $2, "artisanId", "sortOrder" + 1000, "joinedAt", "leftAt" FROM "QuestionnaireInterviewArtisan" '
            'WHERE "interviewId" = $1 ON CONFLICT ("interviewId", "artisanId") DO NOTHING',
            source_id, target_id,
        )
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireInterviewResearcher" ("interviewId", "userId", "role", "addedAt") '
            'SELECT $2, "userId", "role", "addedAt" FROM "QuestionnaireInterviewResearcher" WHERE "interviewId" = $1 '
            'ON CONFLICT ("interviewId", "userId") DO NOTHING',
            source_id, target_id,
        )
        await tx.execute_raw('UPDATE "QuestionnaireAnswerRevision" SET "sessionId" = $2 WHERE "sessionId" = $1', source_id, target_id)
        await tx.execute_raw('UPDATE "MediaUploadIntent" SET "sessionId" = $2 WHERE "sessionId" = $1', source_id, target_id)
        await tx.execute_raw(
            'UPDATE "QuestionnaireInterview" SET "mergedIntoId" = $2, "state" = \'CLOSED\', "closedAt" = COALESCE("closedAt", now()), '
            '"joinCode" = NULL, "version" = "version" + 1, "updatedAt" = now() WHERE "id" = $1',
            source_id, target_id,
        )
        await _bump(tx, target_id)
        await titles.regenerate_title(tx, target_id)
    return await session_payload(db, target_id)


async def delete_session(session_id: str, user: Any, *, client: Any = None) -> None:
    db = _client(client)
    row = await require_session(db, session_id)
    if not (is_admin(user) or str(row.get("createdById")) == str(user.id)):
        fail("FORBIDDEN", "Only the creator or an admin can delete a session.")
    live = await db.query_raw(
        'SELECT count(*) AS n FROM "MediaFile" WHERE "questionnaireInterviewId" = $1 AND "removedAt" IS NULL', session_id
    )
    answers = await db.query_raw('SELECT count(*) AS n FROM "QuestionnaireAnswerRevision" WHERE "sessionId" = $1', session_id)
    count = int(live[0]["n"]) + int(answers[0]["n"])
    if count:
        fail("HAS_RECORDINGS", f"Move or remove its {count} recordings first.", count=count)
    await db.execute_raw('DELETE FROM "MediaUploadIntent" WHERE "sessionId" = $1 AND "closedAt" IS NULL', session_id)
    await db.questionnaireinterview.delete(where={"id": session_id})


# ── lists and lookups ──────────────────────────────────────────────────────────────────────────
async def list_sessions(
    *, client: Any = None, workshopId: str | None = None, questionnaireId: str | None = None,
    artisanId: str | None = None, researcherId: str | None = None, dateFrom: Any = None, dateTo: Any = None,
    search: str | None = None, cursor: str | None = None, limit: int = 20,
) -> dict[str, Any]:
    """Newest ``sessionDate`` first (undated legacy rows last), keyset-paged by an opaque cursor."""
    db = _client(client)
    limit = max(1, min(int(limit or 20), PAGE_MAX))
    where = ['i."mergedIntoId" IS NULL']
    params: list[Any] = []

    def p(value: Any) -> str:
        params.append(value)
        return f"${len(params)}"

    if workshopId:
        where.append(f'i."workshopId" = {p(workshopId)}')
    if questionnaireId:
        where.append(f'i."questionnaireId" = {p(questionnaireId)}')
    if artisanId:
        where.append(f'EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" x WHERE x."interviewId" = i."id" AND x."artisanId" = {p(artisanId)})')
    if researcherId:
        where.append(f'EXISTS (SELECT 1 FROM "QuestionnaireInterviewResearcher" x WHERE x."interviewId" = i."id" AND x."userId" = {p(researcherId)})')
    if dateFrom:
        where.append(f'i."sessionDate" >= {p(str(as_date(dateFrom)))}::date')
    if dateTo:
        where.append(f'i."sessionDate" <= {p(str(as_date(dateTo)))}::date')
    if search and search.strip():
        term = f"%{search.strip()}%"
        ph = p(term)
        where.append(f'(i."title" ILIKE {ph} OR i."note" ILIKE {ph} OR i."legacyTitle" ILIKE {ph})')
    if cursor:
        try:
            day, created, cid = cursor.split("|")
        except ValueError:
            fail("VALIDATION_FAILED", "Bad cursor.")
        key = '(COALESCE(i."sessionDate", DATE \'1900-01-01\'), i."createdAt", i."id")'
        where.append(f"{key} < ({p(day)}::date, {p(created)}::timestamp, {p(cid)})")
    sql = (
        'SELECT i.*, i."state"::text AS state_t, i."purpose"::text AS purpose_t, i."origin"::text AS origin_t, '
        'i."status"::text AS status_t FROM "QuestionnaireInterview" i WHERE ' + " AND ".join(where)
        + ' ORDER BY COALESCE(i."sessionDate", DATE \'1900-01-01\') DESC, i."createdAt" DESC, i."id" DESC'
        + f" LIMIT {limit + 1}"
    )
    rows = await db.query_raw(sql, *params)
    more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = None
    if more and rows:
        last = rows[-1]
        day = as_date(last.get("sessionDate"))
        next_cursor = f"{day.isoformat() if day else '1900-01-01'}|{naive_utc(aware(last['createdAt']))}|{last['id']}"
    return {"items": await serialize_sessions(db, rows), "nextCursor": next_cursor, "capped": False}


async def open_match(*, questionnaire_id: str, workshop_id: str | None, artisan_ids: list[str], day: Any, client: Any = None) -> dict[str, Any]:
    """Same workshop, an overlapping participant, the same day, OPEN, by any researcher (P1)."""
    db = _client(client)
    if not artisan_ids:
        return {"items": []}
    rows = await db.query_raw(
        'SELECT i."id", i."title", i."startedAt", i."createdById", u."name" AS uname FROM "QuestionnaireInterview" i '
        'JOIN "User" u ON u."id" = i."createdById" '
        'WHERE i."questionnaireId" = $1 AND i."state" = \'OPEN\' AND i."mergedIntoId" IS NULL '
        'AND i."workshopId" IS NOT DISTINCT FROM $2 AND i."sessionDate" = $3::date '
        'AND EXISTS (SELECT 1 FROM "QuestionnaireInterviewArtisan" x WHERE x."interviewId" = i."id" AND x."artisanId" = ANY($4::text[])) '
        'ORDER BY i."startedAt" DESC NULLS LAST LIMIT 5',
        questionnaire_id, workshop_id, str(as_date(day) or now().astimezone(IST).date()), artisan_ids,
    )
    items = []
    for r in rows:
        names = await db.query_raw(
            'SELECT a."name" FROM "QuestionnaireInterviewArtisan" x JOIN "Artisan" a ON a."id" = x."artisanId" '
            'WHERE x."interviewId" = $1 ORDER BY x."sortOrder"', str(r["id"]),
        )
        items.append(
            {
                "id": str(r["id"]), "title": r["title"], "startedAt": iso(r.get("startedAt")),
                "researcher": {"id": str(r["createdById"]), "name": r["uname"]},
                "artisanNames": [n["name"] for n in names],
            }
        )
    return {"items": items}


async def continue_sessions(payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    """"Continue with...": one session for all, or one each, on a new day, with each artisan's first gap."""
    from uuid import uuid4

    from app.schemas.questionnaire_v2 import ParticipantInput, SessionCreate
    from app.services.questionnaire_v2 import coverage

    db = _client(client)
    source = await require_session(db, payload.fromSessionId)
    groups = [payload.artisanIds] if payload.split == "ONE" else [[a] for a in payload.artisanIds]
    sessions = []
    for group in groups:
        create = SessionCreate(
            clientKey=f"cont-{uuid4().hex[:20]}", questionnaireId=str(source["questionnaireId"]),
            workshopId=source.get("workshopId"), designWorkshopId=source.get("designWorkshopId"),
            sessionDate=payload.sessionDate, purpose="FOLLOW_UP", place=source.get("place"),
            languageCode=source.get("languageCode"), languageOther=source.get("languageOther"),
            participants=[ParticipantInput(artisanId=a) for a in group],
        )
        session, _created = await create_session(create, user, client=client)
        sessions.append(session)
    gaps = await coverage.first_gaps(db, str(source["questionnaireId"]), payload.artisanIds)
    return {"sessions": sessions, "firstGap": gaps}


async def close_stale_sessions(*, client: Any = None, older_than: timedelta = timedelta(hours=24)) -> int:
    """Nightly: close OPEN sessions whose last take (or start, with no takes) is older than 24 h."""
    db = _client(client)
    return await db.execute_raw(
        'UPDATE "QuestionnaireInterview" i SET "state" = \'CLOSED\', "closedAt" = now(), "endedAt" = COALESCE(i."endedAt", now()), '
        '"version" = i."version" + 1, "updatedAt" = now() '
        'WHERE i."state" = \'OPEN\' AND COALESCE((SELECT max(r."capturedAt") FROM "QuestionnaireRecording" r '
        '  JOIN "MediaFile" m ON m."id" = r."mediaFileId" WHERE m."questionnaireInterviewId" = i."id"), i."startedAt", i."createdAt") '
        "  < now() - make_interval(secs => $1)",
        older_than.total_seconds(),
    )


_LAST_CLOSE_DAY: list[Any] = [None]


async def maybe_close_stale_sessions(*, at: datetime | None = None) -> int | None:
    """The nightly ``close_stale_sessions`` (DESIGN 3.7), run by the queue worker once per IST day
    after 02:00, and only while QUESTIONNAIRE_V2 is on. Never rejects anything: CLOSED only stops
    the UI from starting new takes."""
    from app.core.config import get_settings

    if not get_settings().questionnaire_v2:
        return None
    local = (at or now()).astimezone(IST)
    if local.hour < 2 or _LAST_CLOSE_DAY[0] == local.date():
        return None
    _LAST_CLOSE_DAY[0] = local.date()
    return await close_stale_sessions()
