"""GUEST RESPONDENTS (owner decision 1, 21 Oct 2026; UNIFY U6): a person who is not a registered
artisan can answer a form, in both portals.

A respondent belongs to one session: a named GUEST (a market-survey shopper, a visitor) or SELF (the
signed-in designer or researcher answering their own form). Their answers are ``QuestionnaireAnswer``
rows owned by ``respondentId`` instead of ``artisanId`` (a CHECK holds exactly one), unique per
question lineage; their place on a take is a ``QuestionnaireRecordingRespondent`` row beside the
artisan subjects. The subject key is ``R:<respondentId>`` (``subjects.py``).

Registering a guest as an artisan (``register``) moves their answers, their take subjects and their
place in the session to that artisan IN ONE TRANSACTION, and refuses with ``RESPONDENT_HAS_ROWS``
(nothing moved) when the artisan already has an answer on one of the same questions. Personal data
is retracted by editing the name and notes (A17); a respondent with rows is never deleted.
"""

from __future__ import annotations

from datetime import timedelta
from typing import Any
from uuid import uuid4

from app.questionnaire import ports, sessions
from app.questionnaire.common import fail, get, iso

KINDS = ("GUEST", "SELF")
NAME_MAX = 120
NOTES_MAX = 2000
#: What a retracted name reads as (A17: "retract personal data").
RETRACTED_NAME = "Respondent"
TX_TIMEOUT = timedelta(seconds=20)
TX_MAX_WAIT = timedelta(seconds=15)


def _db(client: Any) -> Any:
    return client if client is not None else ports.db()


def clean(name: Any, kind: Any, notes: Any, user: Any) -> dict[str, Any]:
    """A respondent's fields, validated: ``{name, kind, notes, userId}``. A SELF respondent is the
    caller (their name when none is given); a GUEST needs a name."""
    kind_t = str(kind or "GUEST").strip().upper()
    if kind_t not in KINDS:
        fail("VALIDATION_FAILED", f"A respondent is one of {', '.join(KINDS)}.")
    text = " ".join(str(name or "").split())
    if kind_t == "SELF" and not text:
        text = " ".join(str(get(user, "name") or "").split())
    if not text:
        fail("VALIDATION_FAILED", "Give the respondent's name.")
    if len(text) > NAME_MAX:
        fail("VALIDATION_FAILED", f"A name is at most {NAME_MAX} characters.")
    note = (str(notes).strip() or None) if notes is not None else None
    if note and len(note) > NOTES_MAX:
        fail("VALIDATION_FAILED", f"Notes are at most {NOTES_MAX} characters.")
    return {"name": text, "kind": kind_t, "notes": note, "userId": str(get(user, "id")) if kind_t == "SELF" else None}


def respondent_out(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": str(row["id"]), "sessionId": str(row["sessionId"]), "name": row.get("name") or "",
        "kind": str(row.get("kind") or "GUEST"), "notes": row.get("notes"), "userId": row.get("userId"),
        "artisanId": row.get("artisanId"), "subjectKey": f"R:{row['id']}", "registeredAt": iso(row.get("registeredAt")),
    }


async def list_for(client: Any, session_id: str) -> list[dict[str, Any]]:
    rows = await client.query_raw(
        'SELECT r.*, r."kind"::text AS kind FROM "QuestionnaireRespondent" r WHERE r."sessionId" = $1 ORDER BY r."createdAt", r."id"',
        session_id,
    )
    return [respondent_out(r) for r in rows]


async def require(client: Any, respondent_id: str, session_id: str | None = None) -> dict[str, Any]:
    rows = await client.query_raw(
        'SELECT r.*, r."kind"::text AS kind FROM "QuestionnaireRespondent" r WHERE r."id" = $1', respondent_id
    )
    if not rows or (session_id and str(rows[0]["sessionId"]) != session_id):
        fail("NOT_FOUND", "No such respondent in this session.")
    return rows[0]


async def insert_many(client: Any, session_id: str, items: list[dict[str, Any]], user: Any) -> list[str]:
    """Write already-cleaned respondents (``clean``) to a session; the caller holds the transaction."""
    ids = []
    for item in items:
        rid = uuid4().hex
        await client.execute_raw(
            'INSERT INTO "QuestionnaireRespondent" ("id", "sessionId", "name", "kind", "notes", "userId", "createdById", "updatedAt") '
            'VALUES ($1, $2, $3, $4::"RespondentKind", $5, $6, $7, now())',
            rid, session_id, item["name"], item["kind"], item["notes"], item["userId"], str(get(user, "id")),
        )
        ids.append(rid)
    return ids


async def add(session_id: str, items: list[Any], user: Any, *, client: Any = None) -> list[dict[str, Any]]:
    """``POST /sessions/{id}/respondents``: whoever may work in the session."""
    db = _db(client)
    session = await sessions.require_session(db, session_id)
    await sessions.assert_can_work_in(db, user, session)
    cleaned = [clean(get(i, "name"), get(i, "kind"), get(i, "notes"), user) for i in items]
    if not cleaned:
        fail("VALIDATION_FAILED", "Name at least one respondent.")
    await insert_many(db, session_id, cleaned, user)
    return await list_for(db, session_id)


async def patch(session_id: str, respondent_id: str, payload: Any, user: Any, *, client: Any = None) -> dict[str, Any]:
    """``PATCH /sessions/{id}/respondents/{rid}`` (A17): the name and the notes. An empty name
    retracts it ("Respondent"); empty notes clear them."""
    db = _db(client)
    session = await sessions.require_session(db, session_id)
    await sessions.assert_can_work_in(db, user, session)
    row = await require(db, respondent_id, session_id)
    name = get(payload, "name")
    notes = get(payload, "notes")
    new_name = row.get("name")
    if name is not None:
        new_name = " ".join(str(name).split()) or RETRACTED_NAME
        if len(new_name) > NAME_MAX:
            fail("VALIDATION_FAILED", f"A name is at most {NAME_MAX} characters.")
    new_notes = row.get("notes")
    if notes is not None:
        new_notes = str(notes).strip() or None
        if new_notes and len(new_notes) > NOTES_MAX:
            fail("VALIDATION_FAILED", f"Notes are at most {NOTES_MAX} characters.")
    await db.execute_raw(
        'UPDATE "QuestionnaireRespondent" SET "name" = $2, "notes" = $3, "updatedAt" = now() WHERE "id" = $1',
        respondent_id, new_name, new_notes,
    )
    return respondent_out(await require(db, respondent_id, session_id))


async def row_counts(client: Any, respondent_id: str) -> dict[str, int]:
    rows = await client.query_raw(
        'SELECT (SELECT count(*) FROM "QuestionnaireAnswer" WHERE "respondentId" = $1) AS answers, '
        '(SELECT count(*) FROM "QuestionnaireRecordingRespondent" WHERE "respondentId" = $1) AS takes',
        respondent_id,
    )
    return {"answers": int(rows[0]["answers"]), "takes": int(rows[0]["takes"])}


async def remove(session_id: str, respondent_id: str, user: Any, *, client: Any = None) -> None:
    """``DELETE /sessions/{id}/respondents/{rid}``: only a respondent with no answers and no takes
    (one added by mistake). Anything else is ``RESPONDENT_HAS_ROWS``: retract the name instead."""
    db = _db(client)
    session = await sessions.require_session(db, session_id)
    await sessions.assert_can_work_in(db, user, session)
    await require(db, respondent_id, session_id)
    counts = await row_counts(db, respondent_id)
    if counts["answers"] or counts["takes"]:
        fail("RESPONDENT_HAS_ROWS", **counts)
    await db.execute_raw('DELETE FROM "QuestionnaireRespondent" WHERE "id" = $1', respondent_id)


async def register(respondent_id: str, artisan_id: str, user: Any, *, client: Any = None) -> dict[str, Any]:
    """``POST /respondents/{rid}/register``: the guest IS this artisan. Their answers, take subjects
    and session place move to the artisan in one transaction; ``RESPONDENT_HAS_ROWS`` (nothing moved)
    when the artisan already answered one of the same questions."""
    db = _db(client)
    row = await require(db, respondent_id)
    session = await sessions.require_session(db, str(row["sessionId"]))
    await sessions.assert_can_work_in(db, user, session)
    if row.get("artisanId"):
        if str(row["artisanId"]) == artisan_id:
            return respondent_out(row)
        fail("RESPONDENT_HAS_ROWS", "This respondent is already registered as another artisan.")
    found = await db.query_raw('SELECT "id" FROM "Artisan" WHERE "id" = $1', artisan_id)
    if not found:
        fail("NOT_FOUND", "No such artisan.")
    async with db.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        clash = await tx.query_raw(
            'SELECT g."questionLineageId" FROM "QuestionnaireAnswer" g JOIN "QuestionnaireAnswer" a '
            'ON a."questionLineageId" = g."questionLineageId" AND a."artisanId" = $2 WHERE g."respondentId" = $1',
            respondent_id, artisan_id,
        )
        if clash:
            fail("RESPONDENT_HAS_ROWS", "The artisan already has answers to some of the same questions.",
                 questionLineageIds=sorted(str(c["questionLineageId"]) for c in clash))
        await tx.execute_raw(
            'UPDATE "QuestionnaireAnswer" SET "artisanId" = $2, "respondentId" = NULL, "version" = "version" + 1, '
            '"updatedAt" = now() WHERE "respondentId" = $1',
            respondent_id, artisan_id,
        )
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named", "repeatReason", "flag") '
            'SELECT g."recordingId", $2, g."role", g."named", g."repeatReason", g."flag" FROM "QuestionnaireRecordingRespondent" g '
            'WHERE g."respondentId" = $1 ON CONFLICT ("recordingId", "artisanId") DO NOTHING',
            respondent_id, artisan_id,
        )
        await tx.execute_raw('DELETE FROM "QuestionnaireRecordingRespondent" WHERE "respondentId" = $1', respondent_id)
        await tx.execute_raw(
            'INSERT INTO "QuestionnaireInterviewArtisan" ("interviewId", "artisanId", "joinedAt") VALUES ($1, $2, now()) '
            'ON CONFLICT ("interviewId", "artisanId") DO NOTHING',
            str(row["sessionId"]), artisan_id,
        )
        await tx.execute_raw(
            'UPDATE "QuestionnaireRespondent" SET "artisanId" = $2, "registeredAt" = now(), "updatedAt" = now() WHERE "id" = $1',
            respondent_id, artisan_id,
        )
    return respondent_out(await require(db, respondent_id))
