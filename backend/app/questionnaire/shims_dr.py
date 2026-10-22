"""Designer-portal's old-build shims (UNIFY_2026-10-21.md 4.5). Shared, and mounted only there.

Every old call keeps its request and response shapes, and NONE EVER RETURNS A NEW ERROR CODE. With
``QUESTIONNAIRE_V2`` off nothing here runs and the old routes behave exactly as before. With it on:

* a FORM SITTING (``/questionnaires/{id}/entries``) is a session on that form, for every form whose
  sections are in the shared instrument tables (``form_is_unified``; U5 copies them, ids kept):
  ``respondentName`` becomes a guest respondent, the typed ``title`` becomes ``legacyTitle``, and
  ``notes`` become the session note (at most 80 characters; anything longer goes on the guest);
* ``PATCH .../entries/{eid}`` edits that guest (name, notes) and the session's ``legacyTitle``;
* ``PUT .../entries/{eid}/answers`` becomes per-item answers, CREATE-ONLY (``baseVersion=None``,
  FR audit H5) except the caller's own answer from this sitting. Never a 403 for the batch: a
  locked item comes back in the old ``rejected`` list under the old code ``ANSWER_OWNED_BY_OTHER``;
* deleting and restoring a sitting is the unified soft delete of its session;
* ``POST /questionnaire/interviews`` (the retired global form) creates a session on the design
  workshop's primary form (the four-step resolution); its clips land as NEEDS_DECISION takes in the
  uploader's inbox (``after_legacy_complete``). With no form to resolve to, the old route runs;
* the build headers, the create cutoff (503 + Retry-After) and the old-clip shim are the same rules
  as documentation-portal's (``shims_fr``), re-exported here so each portal names its own module.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from app.questionnaire import answers, bindings, ports, respondents, sessions
from app.questionnaire.common import enum_text, iso, today_ist
from app.questionnaire.errors import http_error, http_error_type
from app.questionnaire.schemas import (
    NOTE_MAX,
    AnswerItem,
    AnswersPut,
    RespondentInput,
    SessionCreate,
)
from app.questionnaire.shims_fr import (
    LEGACY_TYPES,
    PLATFORM_HEADER,
    PLATFORMS,
    UPDATE_MESSAGE,
    VERSION_CODE_HEADER,
    VERSION_NAME_HEADER,
    after_legacy_complete,
    assert_old_build_may_create,
    client_build,
    enabled,
    legacy_title_change,
    minimum_version_code,
    record_legacy_clip,
)

__all__ = [
    "LEGACY_TYPES", "PLATFORMS", "PLATFORM_HEADER", "UPDATE_MESSAGE", "VERSION_CODE_HEADER", "VERSION_NAME_HEADER",
    "after_legacy_complete", "assert_old_build_may_create", "client_build", "enabled", "legacy_title_change",
    "minimum_version_code", "record_legacy_clip",
]

logger = logging.getLogger(__name__)

#: The old per-answer refusal codes (``services/questionnaire_forms.save_answers``), kept as they are.
OLD_QUESTION_RETIRED = "QUESTION_RETIRED"
OLD_ANSWER_OWNED_BY_OTHER = "ANSWER_OWNED_BY_OTHER"
OLD_OWNED_MESSAGE = "Someone else recorded this answer. Only they or an admin can change it."
OLD_RETIRED_MESSAGE = "This question was retired, so it takes no new answers."
#: What an old sitting with no respondent name is called on the session.
DEFAULT_RESPONDENT = "Respondent"


def _db() -> Any:
    return ports.db()


def _old_error(exc: Exception) -> Exception:
    """A package refusal in the old routes' shape: the same status, a plain sentence, no code."""
    detail = getattr(exc, "detail", None)
    message = detail.get("message") if isinstance(detail, dict) else detail
    return http_error(int(getattr(exc, "status_code", 400) or 400), str(message or "That can't be done here."))


async def form_is_unified(questionnaire_id: str, *, client: Any = None) -> bool:
    """True once the form's sections live in the shared instrument tables (U5 copied them)."""
    if not enabled():
        return False
    rows = await (client or _db()).query_raw(
        'SELECT 1 AS ok FROM "QuestionnaireSection" WHERE "questionnaireId" = $1 LIMIT 1', questionnaire_id
    )
    return bool(rows)


# ── sittings ───────────────────────────────────────────────────────────────────────────────────
async def _form_workshop(db: Any, questionnaire_id: str) -> str | None:
    rows = await db.query_raw(
        'SELECT b."designWorkshopId" FROM "QuestionnaireBinding" b WHERE b."questionnaireId" = $1 AND b."designWorkshopId" IS NOT NULL '
        'ORDER BY b."isPrimary" DESC, b."createdAt" LIMIT 1',
        questionnaire_id,
    )
    return str(rows[0]["designWorkshopId"]) if rows else None


async def entry_out(db: Any, session_id: str) -> dict[str, Any]:
    """One session in the old sitting's shape."""
    rows = await db.query_raw(
        'SELECT i."id", i."questionnaireId", i."title", i."legacyTitle", i."note", i."source"::text AS source, '
        'i."createdById", i."createdAt", i."updatedAt", i."deletedAt", i."deletedById" FROM "QuestionnaireInterview" i WHERE i."id" = $1',
        session_id,
    )
    if not rows:
        raise http_error(404, "Record not found")
    row = rows[0]
    guests = await respondents.list_for(db, session_id)
    guest = guests[0] if guests else None
    notes = (guest or {}).get("notes") or row.get("note")
    return {
        "id": str(row["id"]), "questionnaireId": str(row["questionnaireId"]),
        "title": row.get("legacyTitle") or row.get("title") or "Answers",
        "respondentName": (guest or {}).get("name"), "notes": notes,
        "source": row.get("source") or "APP", "createdById": row.get("createdById"),
        "createdAt": iso(row.get("createdAt")), "updatedAt": iso(row.get("updatedAt")),
        "deletedAt": iso(row.get("deletedAt")), "deletedById": row.get("deletedById"),
    }


async def create_entry(questionnaire_id: str, payload: Any, user: Any, *, headers: Any = None) -> dict[str, Any]:
    """``POST /questionnaires/{id}/entries`` with v2 on: a new session on this form, in the old shape."""
    if headers is not None:
        assert_old_build_may_create(headers)
    db = _db()
    notes = (getattr(payload, "notes", None) or "").strip() or None
    short_note = notes if notes and len(notes) <= NOTE_MAX else None
    name = (getattr(payload, "respondentName", None) or "").strip() or DEFAULT_RESPONDENT
    create = SessionCreate(
        clientKey=f"entry:{uuid4().hex}", questionnaireId=questionnaire_id, designWorkshopId=await _form_workshop(db, questionnaire_id),
        sessionDate=today_ist(), note=short_note,
        respondents=[RespondentInput(name=name[: respondents.NAME_MAX], kind="GUEST", notes=None if short_note else notes)],
    )
    try:
        session, _created = await sessions.create_session(
            create, user, legacy_title=(getattr(payload, "title", None) or "").strip() or None, check_instrument=False,
        )
    except http_error_type() as exc:
        raise _old_error(exc) from None
    return await entry_out(db, str(session["id"]))


async def update_entry(entry_id: str, payload: Any, user: Any) -> dict[str, Any]:
    """``PATCH .../entries/{eid}``: the guest's name and notes (retracting personal data, A17) and the
    typed title (kept as ``legacyTitle``; the generated title never changes)."""
    db = _db()
    sent = payload.model_dump(exclude_unset=True) if hasattr(payload, "model_dump") else dict(payload)
    try:
        session = await sessions.require_session(db, entry_id)
        await sessions.assert_can_work_in(db, user, session)
        guests = await respondents.list_for(db, entry_id)
        if guests and ("respondentName" in sent or "notes" in sent):
            await respondents.patch(entry_id, guests[0]["id"], {
                "name": sent.get("respondentName") if "respondentName" in sent else None,
                "notes": (sent.get("notes") or "") if "notes" in sent else None,
            }, user)
        if "title" in sent:
            await db.execute_raw(
                'UPDATE "QuestionnaireInterview" SET "legacyTitle" = $2, "updatedAt" = now() WHERE "id" = $1',
                entry_id, (sent.get("title") or "").strip() or None,
            )
    except http_error_type() as exc:
        raise _old_error(exc) from None
    return await entry_out(db, entry_id)


async def _lineages(db: Any, questionnaire_id: str, question_ids: list[str]) -> dict[str, dict[str, Any]]:
    rows = await db.query_raw(
        'SELECT "id", "lineageId", "isActive", "retiredAt", "supersededById" FROM "QuestionnaireQuestion" '
        'WHERE "id" = ANY($1::text[]) AND "questionnaireId" = $2',
        question_ids, questionnaire_id,
    )
    return {str(r["id"]): r for r in rows}


async def old_answers(db: Any, session_id: str) -> list[dict[str, Any]]:
    """A session's current answers in the old ``QuestionnaireFormAnswer`` shape (one owner: the
    sitting's guest, else its first participant)."""
    rows = await db.query_raw(
        'SELECT a."id", q."id" AS "questionId", rv."text", rv."note", rv."actorId", a."createdAt", rv."createdAt" AS "updatedAt" '
        'FROM "QuestionnaireAnswer" a JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireQuestion" q ON q."lineageId" = a."questionLineageId" AND q."questionnaireId" = a."questionnaireId" '
        '  AND q."supersededById" IS NULL '
        'WHERE rv."sessionId" = $1 ORDER BY q."sortOrder", a."id"',
        session_id,
    )
    return [
        {"id": str(r["id"]), "entryId": session_id, "questionId": str(r["questionId"]), "answerText": r.get("text"),
         "notes": r.get("note"), "answeredById": r.get("actorId"), "createdAt": iso(r.get("createdAt")),
         "updatedAt": iso(r.get("updatedAt"))}
        for r in rows
    ]


async def record_answers(questionnaire_id: str, entry_id: str, payload: Any, user: Any) -> dict[str, Any]:
    """``PUT .../entries/{eid}/answers``: per-item answers; the old response shape."""
    db = _db()
    batch = list(getattr(payload, "answers", None) or [])
    if not batch:
        raise http_error(422, "There are no answers to save.")
    try:
        session = await sessions.require_session(db, entry_id)
    except http_error_type() as exc:
        raise _old_error(exc) from None
    guests = await respondents.list_for(db, entry_id)
    owner: dict[str, str] = {"respondentId": str(guests[0]["id"])} if guests else {}
    if not owner:
        participants = await sessions.participant_ids(db, entry_id)
        if not participants:
            raise http_error(422, "This sitting has nobody to record answers for.")
        owner = {"artisanId": str(participants[0])}
    questions = await _lineages(db, questionnaire_id, sorted({str(a.questionId) for a in batch}))
    missing = sorted({str(a.questionId) for a in batch} - set(questions))
    if missing:
        raise http_error(422, f"{len(missing)} of those questions are not in this questionnaire.")
    column = '"respondentId"' if "respondentId" in owner else '"artisanId"'
    current = {
        str(r["questionLineageId"]): r for r in await db.query_raw(
            f'SELECT a."questionLineageId", a."version", rv."text", rv."note", rv."sessionId", rv."actorId" FROM "QuestionnaireAnswer" a '
            f'LEFT JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" WHERE a.{column} = $1 '
            'AND a."questionLineageId" = ANY($2::text[])',
            next(iter(owner.values())), sorted({str(q["lineageId"]) for q in questions.values()}),
        )
    }
    rejected: list[dict[str, str]] = []
    items: list[AnswerItem] = []
    unchanged = 0
    for a in batch:
        q = questions[str(a.questionId)]
        if not q.get("isActive") or q.get("retiredAt") or q.get("supersededById"):
            rejected.append({"questionId": str(a.questionId), "code": OLD_QUESTION_RETIRED, "message": OLD_RETIRED_MESSAGE})
            continue
        text = (getattr(a, "answerText", None) or "").strip()
        note = (getattr(a, "notes", None) or "").strip() or None
        now_row = current.get(str(q["lineageId"]))
        if now_row and (now_row.get("text") or "") == text and (now_row.get("note") or None) == note:
            unchanged += 1  # an unchanged answer never asks who wrote it (DR audit H4)
            continue
        if not text and not now_row:
            unchanged += 1
            continue
        own = bool(now_row) and str(now_row.get("sessionId")) == entry_id and str(now_row.get("actorId")) == str(user.id)
        items.append(AnswerItem(
            **owner, questionLineageId=str(q["lineageId"]), text=text, note=note, sessionId=entry_id,
            origin="WORKBOOK" if enum_text(session.get("source")) == "UPLOAD" else "TYPED",
            baseVersion=int(now_row["version"]) if own else None,
        ))
    created = updated = 0
    if items:
        result = await answers.put_answers(AnswersPut(items=items), user)
        lineage_to_question = {str(q["lineageId"]): qid for qid, q in questions.items()}
        for r in result.get("results", []):
            qid = lineage_to_question.get(str(r.get("questionLineageId")), "")
            if r.get("status") == "SAVED":
                if current.get(str(r.get("questionLineageId"))):
                    updated += 1
                else:
                    created += 1
            elif r.get("status") == "UNCHANGED":
                unchanged += 1
            else:
                rejected.append({"questionId": qid, "code": OLD_ANSWER_OWNED_BY_OTHER, "message": OLD_OWNED_MESSAGE})
    return {
        "saved": {"created": created, "updated": updated, "unchanged": unchanged, "rejected": rejected},
        "answers": await old_answers(db, entry_id),
    }


async def delete_entry(entry_id: str, user: Any) -> None:
    try:
        await sessions.delete_session(entry_id, user)
    except http_error_type() as exc:
        raise _old_error(exc) from None


async def restore_entry(entry_id: str, user: Any) -> dict[str, Any]:
    try:
        await sessions.restore_session(entry_id, user)
    except http_error_type() as exc:
        raise _old_error(exc) from None
    return {"id": entry_id, "title": (await entry_out(_db(), entry_id))["title"], "deletedAt": None}


async def entries_for_form(questionnaire_id: str, *, deleted: bool = False) -> list[dict[str, Any]]:
    """The form's sittings (its sessions) in the old shape, newest first; ``deleted`` for the
    content admins' Deleted list."""
    db = _db()
    rows = await db.query_raw(
        'SELECT "id" FROM "QuestionnaireInterview" WHERE "questionnaireId" = $1 AND "mergedIntoId" IS NULL AND '
        + ('"deletedAt" IS NOT NULL ORDER BY "deletedAt" DESC' if deleted else '"deletedAt" IS NULL ORDER BY "createdAt" DESC'),
        questionnaire_id,
    )
    out = []
    for row in rows:
        entry = await entry_out(db, str(row["id"]))
        if not deleted:
            entry["answers"] = await old_answers(db, str(row["id"]))
        out.append(entry)
    return out


# ── the retired global form ────────────────────────────────────────────────────────────────────
async def form_for_design_workshop(design_workshop_id: str | None) -> str | None:
    """The form an old global-form create lands on: the four-step resolution for its design workshop."""
    if not design_workshop_id:
        resolved = await bindings.resolve_for_workshop(client=_db())
    else:
        resolved = await bindings.resolve_for_workshop(design_workshop_id=design_workshop_id, client=_db())
    if resolved.get("questionnaireId"):
        return str(resolved["questionnaireId"])
    bound = resolved.get("bound") or []
    return str(bound[0]["questionnaireId"]) if bound else None


async def create_global_interview(payload: Any, user: Any, *, headers: Any = None) -> Any:
    """``POST /questionnaire/interviews`` (an old build's global form) with v2 on: a session on the
    design workshop's primary form, returned as the stored interview row. None when no form resolves
    (the caller then runs the old route, so no new error code ever reaches the old build)."""
    if not enabled():
        return None
    design_workshop_id = getattr(payload, "designWorkshopId", None) or None
    questionnaire_id = await form_for_design_workshop(design_workshop_id)
    if not questionnaire_id or not await form_is_unified(questionnaire_id):
        return None
    if headers is not None:
        assert_old_build_may_create(headers)
    from app.questionnaire.schemas import ParticipantInput

    artisan_ids = [a for a in dict.fromkeys(getattr(payload, "artisanIds", None) or []) if a]
    recorded = getattr(payload, "interviewDate", None) or getattr(payload, "recordedAt", None)
    day = recorded.astimezone(UTC).date() if isinstance(recorded, datetime) and recorded.tzinfo else today_ist()
    notes = (getattr(payload, "notes", None) or "").strip() or None
    create = SessionCreate(
        clientKey=f"global:{uuid4().hex}", questionnaireId=questionnaire_id, designWorkshopId=design_workshop_id,
        sessionDate=day, place=(getattr(payload, "place", None) or "").strip()[:200] or None,
        note=notes if notes and len(notes) <= NOTE_MAX else None,
        participants=[ParticipantInput(artisanId=a) for a in artisan_ids],
        respondents=[] if artisan_ids else [RespondentInput(name=DEFAULT_RESPONDENT, kind="GUEST", notes=notes)],
    )
    try:
        session, _created = await sessions.create_session(
            create, user, legacy_title=(getattr(payload, "title", None) or "").strip() or None,
            estimated_date=recorded is None, extra={"questionnaireV2": {"shim": "global-form"}}, check_instrument=False,
        )
    except http_error_type() as exc:
        raise _old_error(exc) from None
    return await _db().questionnaireinterview.find_unique(where={"id": str(session["id"])})


async def patch_global_interview(interview: Any, payload: Any, user: Any) -> dict[str, Any]:
    """``PATCH /questionnaire/interviews/{id}`` on a v2 session: the shared edit (title to
    ``legacyTitle``, artisans to participant changes). Returns the column changes left for the route."""
    from app.questionnaire.shims_fr import patch_interview_shim

    return await patch_interview_shim(interview, payload, user)
