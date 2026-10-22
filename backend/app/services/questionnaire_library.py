"""The forms library on Field Repository (UNIFY U3): scopes, sharing, kinds, soft delete and restore,
reuse, the handoff bundle, the workbook answer import and the workshop annexure.

Portal code, not the shared package: designer-portal serves the same contract from its own
``questionnaire_forms`` service, and both read the shared rules in ``app/questionnaire``
(``authoring`` for who may change what, ``kinds`` for the vocabulary, ``bindings`` for the form
resolution). When the unified package grows ``forms``/``reuse``/``bundle``/``annexure`` modules
(UNIFY 2.4), these functions move there unchanged.

Who may do what (owner, 21 and 22 Oct 2026):

* list a scope: ``mine``, ``shared`` and ``workshop`` for everyone signed in; ``deleted`` for content
  admins;
* change a form (title, description, kind, its sections and questions, re-upload, delete): its author
  or a content admin (``authoring.may_change_form``);
* share with everyone, the default form, type defaults, bindings: admins (``authoring.may_admin_act``);
* restore a deleted form: content admins;
* create, reuse or import a bundle: questionnaire managers (Professor and above), who become the
  author.
"""

from __future__ import annotations

import gzip
import io
import json
import secrets
from datetime import UTC, date, datetime
from typing import Any

from prisma.errors import UniqueViolationError

from app.core.db import db
from app.questionnaire import authoring, kinds
from app.questionnaire.common import fail

SCOPES = ("mine", "shared", "workshop", "deleted")
BUNDLE_VERSION = 2
BUNDLE_EXTENSIONS = (".frwq", ".dpwq")
BUNDLE_LIMITS = {"maxSections": 200, "maxQuestionsPerSection": 500, "maxTextChars": 4000, "maxUncompressedBytes": 5 * 1024 * 1024}
PORTAL = "documentation-portal"

#: The refusals, worded for the screen.
DELETED_LIST_REFUSAL = "Only an admin can see deleted questionnaires."
RESTORE_REFUSAL = "Only an admin can restore a questionnaire."


def _get(obj: Any, name: str) -> Any:
    if obj is None:
        return None
    if isinstance(obj, dict):
        return obj.get(name)
    return getattr(obj, name, None)


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=UTC)).isoformat()
    return str(value)


def _uid(user: Any) -> str | None:
    value = _get(user, "id")
    return str(value) if value else None


# ── the payload ────────────────────────────────────────────────────────────────────────────────
def form_payload(row: Any, counts: dict[str, dict[str, int]] | None = None, user: Any = None) -> dict[str, Any]:
    """One form, as every list and detail answers it. ``user`` adds what this account may do with
    it, so the client draws only the controls the server will accept."""
    rid = str(_get(row, "id"))
    tally = (counts or {}).get(rid, {})
    kind = _get(row, "kind")
    author = authoring.form_author_id(row)
    payload: dict[str, Any] = {
        "id": rid,
        "title": _get(row, "title"),
        "description": _get(row, "description"),
        "isActive": bool(_get(row, "isActive")) if _get(row, "isActive") is not None else True,
        "isDefault": bool(_get(row, "isDefault")),
        "sortOrder": _get(row, "sortOrder"),
        "version": _get(row, "version"),
        "sourceFilename": _get(row, "sourceFilename"),
        "sectionCount": tally.get("sections", 0),
        "questionCount": tally.get("questions", 0),
        "workshopCount": tally.get("workshops", 0),
        "createdAt": _iso(_get(row, "createdAt")),
        "createdById": _get(row, "createdById"),
        "ownerId": author,
        "isShared": bool(_get(row, "isShared")),
        "kind": kind,
        "kindLabel": kinds.label_for(kind),
        "deletedAt": _iso(_get(row, "deletedAt")),
        "deletedById": _get(row, "deletedById"),
    }
    if user is not None:
        deleted = _get(row, "deletedAt") is not None
        payload["can"] = {
            "edit": not deleted and authoring.may_change_form(user, row),
            "delete": not deleted and authoring.may_change_form(user, row),
            "restore": deleted and authoring.may_restore_form(user),
            "share": not deleted and authoring.may_admin_act(user),
            "bind": not deleted and authoring.may_admin_act(user),
            "setDefault": not deleted and authoring.may_admin_act(user),
        }
    return payload


async def counts_for(form_ids: list[str]) -> dict[str, dict[str, int]]:
    """Live sections, live questions and bound workshops per form: one statement."""
    if not form_ids:
        return {}
    rows = await db.query_raw(
        'SELECT q."id", '
        '(SELECT count(*) FROM "QuestionnaireSection" s WHERE s."questionnaireId" = q."id" AND s."isActive") AS sections, '
        '(SELECT count(*) FROM "QuestionnaireQuestion" x WHERE x."questionnaireId" = q."id" AND x."isActive") AS questions, '
        '(SELECT count(*) FROM "QuestionnaireBinding" b WHERE b."questionnaireId" = q."id") AS workshops '
        'FROM "Questionnaire" q WHERE q."id" = ANY($1::text[])',
        form_ids,
    )
    return {str(r["id"]): {"sections": int(r["sections"]), "questions": int(r["questions"]), "workshops": int(r["workshops"])} for r in rows}


async def list_forms(
    user: Any, *, scope: str | None = None, workshop_id: str | None = None, kind: str | None = None, active_only: bool = True,
) -> list[dict[str, Any]]:
    """``GET /questionnaires``. With no ``scope`` the list is what it always was (every live form, or
    every form with ``activeOnly=false``), so older clients keep their picker. A scope narrows it:

    * ``mine``: forms this account wrote;
    * ``shared``: forms shared with everyone;
    * ``workshop``: forms bound to ``workshopId`` (or to any workshop when none is named);
    * ``deleted``: soft-deleted forms, content admins only.
    """
    if scope is not None and scope not in SCOPES:
        fail("VALIDATION_FAILED", f"scope is one of {', '.join(SCOPES)}.")
    where: list[str] = []
    params: list[Any] = []

    def p(value: Any) -> str:
        params.append(value)
        return f"${len(params)}"

    if scope == "deleted":
        if not authoring.is_content_admin(user):
            fail("FORBIDDEN", DELETED_LIST_REFUSAL)
        where.append('q."deletedAt" IS NOT NULL')
    else:
        if scope is not None:
            where.append('q."deletedAt" IS NULL')
        if active_only or scope is not None:
            where.append('q."isActive"')
    if scope == "mine":
        me = p(_uid(user) or "")
        where.append(f'(q."ownerId" = {me} OR (q."ownerId" IS NULL AND q."createdById" = {me}))')
    elif scope == "shared":
        where.append('q."isShared"')
    elif scope == "workshop":
        if workshop_id:
            where.append(f'EXISTS (SELECT 1 FROM "QuestionnaireBinding" b WHERE b."questionnaireId" = q."id" AND b."workshopId" = {p(workshop_id)})')
        else:
            where.append('EXISTS (SELECT 1 FROM "QuestionnaireBinding" b WHERE b."questionnaireId" = q."id" AND b."workshopId" IS NOT NULL)')
    if kind:
        if kind.lower() == "none":
            where.append('q."kind" IS NULL')
        else:
            try:
                token = kinds.coerce_kind(kind)
            except ValueError as exc:
                fail("VALIDATION_FAILED", str(exc))
            where.append(f'q."kind" = {p(token)}')
    sql = 'SELECT q.* FROM "Questionnaire" q' + (" WHERE " + " AND ".join(where) if where else "") + ' ORDER BY q."sortOrder" ASC, q."createdAt" ASC'
    rows = await db.query_raw(sql, *params)
    counts = await counts_for([str(r["id"]) for r in rows])
    return [form_payload(r, counts, user) for r in rows]


async def require_form(questionnaire_id: str, *, allow_deleted: bool = False) -> Any:
    row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        fail("NOT_FOUND", "No such questionnaire.")
    if not allow_deleted and _get(row, "deletedAt") is not None:
        fail("FORM_DELETED")
    return row


# ── restore (owner decision 3, amended 22 Oct; the soft delete is the route's) ─────────────────
async def restore(questionnaire_id: str, user: Any) -> dict[str, Any]:
    """Content admins bring a deleted form back, with everything recorded on it."""
    if not authoring.may_restore_form(user):
        fail("FORBIDDEN", RESTORE_REFUSAL)
    row = await require_form(questionnaire_id, allow_deleted=True)
    if _get(row, "deletedAt") is None and _get(row, "isActive"):
        counts = await counts_for([questionnaire_id])
        return form_payload(row, counts, user)
    updated = await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"isActive": True, "deletedAt": None, "deletedById": None}
    )
    counts = await counts_for([questionnaire_id])
    return form_payload(updated, counts, user)


# ── titles ──────────────────────────────────────────────────────────────────────────────────────
async def free_title(wanted: str) -> str:
    """``Questionnaire.title`` is unique here, so a copy or an import takes the first free
    ``Title (2)``, ``Title (3)``…"""
    base = (wanted or "Untitled questionnaire").strip()[:200] or "Untitled questionnaire"
    rows = await db.query_raw('SELECT "title" FROM "Questionnaire" WHERE "title" = $1 OR "title" LIKE $2', base, f"{base} (%)")
    taken = {str(r["title"]) for r in rows}
    if base not in taken:
        return base
    n = 2
    while f"{base} ({n})" in taken:
        n += 1
    return f"{base} ({n})"


async def _next_sort_order() -> int:
    rows = await db.query_raw('SELECT COALESCE(MAX("sortOrder"), 0) AS m FROM "Questionnaire"')
    return int(rows[0]["m"] or 0) + 1


async def live_structure(questionnaire_id: str, *, client: Any = None) -> tuple[list[Any], list[Any]]:
    """The live sections in order and their live, current questions in order."""
    db_ = client if client is not None else db
    sections = await db_.questionnairesection.find_many(
        where={"questionnaireId": questionnaire_id, "isActive": True}, order={"sortOrder": "asc"}
    )
    questions = await db_.questionnairequestion.find_many(
        where={"questionnaireId": questionnaire_id, "isActive": True, "retiredAt": None, "supersededById": None},
        order={"sortOrder": "asc"},
    )
    return sections, questions


async def _write_structure(form_id: str, sections: list[dict[str, Any]]) -> tuple[int, int]:
    """Create ``sections`` (``{code, title, questions: [{prompt, helpText, isRequired, artisanField}]}``)
    on a fresh form. Returns ``(sections, questions)`` written."""
    from app.services import cross_portal_codes

    taken: set[str] = set()
    n_sections = n_questions = 0
    for order, section in enumerate(sections, start=1):
        code = (section.get("code") or "").strip()[:20] or f"S{order}"
        while code.lower() in taken:
            code = f"{code}{order}"
        taken.add(code.lower())
        title = (section.get("title") or code).strip()[:200] or code
        made = await db.questionnairesection.create(
            data={"questionnaireId": form_id, "code": code, "title": title, "sortOrder": order}
        )
        n_sections += 1
        for position, question in enumerate(section.get("questions") or [], start=1):
            prompt = (question.get("prompt") or "").strip()
            if not prompt:
                continue
            await db.questionnairequestion.create(
                data={
                    "questionnaireId": form_id, "sectionId": made.id, "sectionCode": made.code, "sectionTitle": made.title,
                    "prompt": prompt[: BUNDLE_LIMITS["maxTextChars"]],
                    "helpText": (question.get("helpText") or None) and str(question.get("helpText"))[:1000],
                    "isRequired": bool(question.get("isRequired")),
                    "artisanField": question.get("artisanField") or None,
                    "sortOrder": position,
                }
            )
            n_questions += 1
    await cross_portal_codes.reconcile_question_codes(db, questionnaire_id=form_id)
    return n_sections, n_questions


async def _create_form(user: Any, *, title: str, description: str | None, kind: str | None, extra: dict[str, Any] | None = None) -> Any:
    me = _uid(user)
    for _attempt in range(3):
        try:
            return await db.questionnaire.create(
                data={
                    "title": await free_title(title),
                    "description": (description or None),
                    "sortOrder": await _next_sort_order(),
                    "createdById": me,
                    "ownerId": me,
                    "kind": kind,
                    **(extra or {}),
                }
            )
        except UniqueViolationError:
            continue
    fail("VALIDATION_FAILED", "Another form took that title just now. Try again.")


# ── reuse (A5) ──────────────────────────────────────────────────────────────────────────────────
async def reuse(questionnaire_id: str, user: Any, *, title: str | None = None, description: str | None = None,
                kind: str | None = None, keep_kind: bool = True) -> dict[str, Any]:
    """A new form of your own with this form's questions: sections and questions only, never its
    sessions, answers, bindings, sharing or default."""
    from app.core.deps import can_manage_questionnaire

    if not can_manage_questionnaire(user):
        fail("FORBIDDEN", "Questionnaire management access required.")
    source = await require_form(questionnaire_id)
    try:
        wanted_kind = kinds.coerce_kind(kind) if kind is not None else (_get(source, "kind") if keep_kind else None)
    except ValueError as exc:
        fail("VALIDATION_FAILED", str(exc))
    sections, questions = await live_structure(questionnaire_id)
    by_section: dict[str, list[Any]] = {}
    for q in questions:
        by_section.setdefault(str(_get(q, "sectionId")), []).append(q)
    plan = [
        {
            "code": _get(s, "code"), "title": _get(s, "title"),
            "questions": [
                {"prompt": _get(q, "prompt"), "helpText": _get(q, "helpText"), "isRequired": _get(q, "isRequired"),
                 "artisanField": _get(q, "artisanField")}
                for q in by_section.get(str(_get(s, "id")), [])
            ],
        }
        for s in sections
    ]
    created = await _create_form(
        user, title=(title or "").strip() or f"{_get(source, 'title')} (copy)",
        description=description if description is not None else _get(source, "description"), kind=wanted_kind,
    )
    await _write_structure(created.id, plan)
    counts = await counts_for([created.id])
    return form_payload(created, counts, user)


# ── the handoff bundle (A10; shared/questionnaire-bundle.json) ────────────────────────────────────
async def bundle_document(questionnaire_id: str) -> dict[str, Any]:
    form = await require_form(questionnaire_id)
    sections, questions = await live_structure(questionnaire_id)
    code_of = {str(_get(s, "id")): str(_get(s, "code")) for s in sections}
    return {
        "v": BUNDLE_VERSION,
        "bundleId": secrets.token_hex(16),
        "createdAt": datetime.now(UTC).isoformat(),
        "portal": PORTAL,
        "form": {"title": _get(form, "title"), "description": _get(form, "description"), "kind": _get(form, "kind")},
        "sections": [{"code": str(_get(s, "code")), "title": _get(s, "title"), "sortOrder": i} for i, s in enumerate(sections, start=1)],
        "questions": [
            {
                "sectionCode": code_of[str(_get(q, "sectionId"))], "prompt": _get(q, "prompt"), "helpText": _get(q, "helpText"),
                "isRequired": bool(_get(q, "isRequired")), "artisanField": _get(q, "artisanField"),
                "questionCode": _get(q, "questionCode"), "sortOrder": int(_get(q, "sortOrder") or 0),
            }
            for q in questions
            if str(_get(q, "sectionId")) in code_of
        ],
    }


def bundle_bytes(document: dict[str, Any]) -> bytes:
    return gzip.compress(json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), mtime=0)


def bundle_filename(title: str | None) -> str:
    from app.services import naming

    return naming.export_filename("Questionnaire", "Handoff", "frwq", subject=title or "Questionnaire")


def read_bundle_bytes(raw: bytes) -> dict[str, Any]:
    """A received file: gzipped JSON (either extension), or plain JSON. Untrusted; size-capped."""
    limit = BUNDLE_LIMITS["maxUncompressedBytes"]
    data = raw
    if raw[:2] == b"\x1f\x8b":
        try:
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as handle:
                data = handle.read(limit + 1)
        except (OSError, EOFError):
            fail("VALIDATION_FAILED", "This file is not a questionnaire bundle.")
    if len(data) > limit:
        fail("VALIDATION_FAILED", "This bundle is too large.")
    try:
        parsed = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("VALIDATION_FAILED", "This file is not a questionnaire bundle.")
    if not isinstance(parsed, dict):
        fail("VALIDATION_FAILED", "This file is not a questionnaire bundle.")
    return parsed


def _text(value: Any, limit: int = BUNDLE_LIMITS["maxTextChars"]) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text[:limit] if text else None


def normalise_bundle(document: dict[str, Any]) -> dict[str, Any]:
    """``{bundleId, form: {title, description, kind}, sections: [{code, title, questions: [...]}]}``
    from a v2 envelope or designer-portal Android's v1 file. A newer major version is
    ``BUNDLE_VERSION``; anything malformed is ``VALIDATION_FAILED``."""
    version = document.get("v")
    if version is not None:
        try:
            major = int(str(version).split(".")[0])
        except ValueError:
            fail("VALIDATION_FAILED", "This file is not a questionnaire bundle.")
        if major > BUNDLE_VERSION:
            fail("BUNDLE_VERSION")
        if not isinstance(document.get("form"), dict):
            fail("VALIDATION_FAILED", "This bundle has no form.")
        form = document["form"]
        sections_in = document.get("sections") or []
        questions_in = document.get("questions") or []
        if not isinstance(sections_in, list) or not isinstance(questions_in, list):
            fail("VALIDATION_FAILED", "This bundle is damaged.")
        ordered = sorted((s for s in sections_in if isinstance(s, dict)), key=lambda s: int(s.get("sortOrder") or 0))
        by_code: dict[str, dict[str, Any]] = {}
        sections: list[dict[str, Any]] = []
        for s in ordered:
            code = _text(s.get("code"), 20)
            if not code or code in by_code:
                fail("VALIDATION_FAILED", "This bundle is damaged: a section code is missing or repeated.")
            entry = {"code": code, "title": _text(s.get("title"), 200) or code, "questions": []}
            by_code[code] = entry
            sections.append(entry)
        for q in sorted((q for q in questions_in if isinstance(q, dict)), key=lambda q: int(q.get("sortOrder") or 0)):
            target = by_code.get(str(q.get("sectionCode") or ""))
            if target is None:
                fail("VALIDATION_FAILED", "This bundle is damaged: a question names no section.")
            target["questions"].append(q)
        title, description, kind, bundle_id = form.get("title"), form.get("description"), form.get("kind"), document.get("bundleId")
    elif document.get("format") or "sections" in document:
        # designer-portal Android v1: {format, schemaVersion, title, description, sections: [{code, title, sortOrder, questions}]}
        sections = []
        for s in sorted((s for s in (document.get("sections") or []) if isinstance(s, dict)), key=lambda s: int(s.get("sortOrder") or 0)):
            code = _text(s.get("code"), 20) or f"S{len(sections) + 1}"
            questions = sorted((q for q in (s.get("questions") or []) if isinstance(q, dict)), key=lambda q: int(q.get("sortOrder") or 0))
            sections.append({"code": code, "title": _text(s.get("title"), 200) or code, "questions": questions})
        title, description, kind, bundle_id = document.get("title"), document.get("description"), None, None
    else:
        fail("VALIDATION_FAILED", "This file is not a questionnaire bundle.")
    if not _text(title, 200):
        fail("VALIDATION_FAILED", "This bundle has no title.")
    if len(sections) > BUNDLE_LIMITS["maxSections"] or any(len(s["questions"]) > BUNDLE_LIMITS["maxQuestionsPerSection"] for s in sections):
        fail("VALIDATION_FAILED", "This bundle is too large.")
    try:
        kind_token = kinds.coerce_kind(kind) if isinstance(kind, str) else None
    except ValueError:
        kind_token = None  # a newer deployment's kind: imported as "not stated"
    clean_sections = [
        {
            "code": s["code"], "title": s["title"],
            "questions": [
                {"prompt": _text(q.get("prompt")), "helpText": _text(q.get("helpText"), 1000), "isRequired": bool(q.get("isRequired")),
                 "artisanField": _text(q.get("artisanField"), 64)}
                for q in s["questions"] if _text(q.get("prompt"))
            ],
        }
        for s in sections
    ]
    bid = str(bundle_id).strip().lower() if bundle_id else None
    if bid and not all(c in "0123456789abcdef" for c in bid):
        bid = None
    return {
        "bundleId": bid[:64] if bid else None,
        "form": {"title": _text(title, 200), "description": _text(description, 2000), "kind": kind_token},
        "sections": clean_sections,
    }


async def import_bundle(document: dict[str, Any], user: Any) -> tuple[dict[str, Any], bool]:
    """A new form of your own from a received bundle. Idempotent on the bundle id per importer:
    a second import of the same file returns the first copy (``created`` False)."""
    from app.core.deps import can_manage_questionnaire

    if not can_manage_questionnaire(user):
        fail("FORBIDDEN", "Questionnaire management access required.")
    clean = normalise_bundle(document)
    me = _uid(user)
    if clean["bundleId"]:
        existing = await db.questionnaire.find_first(where={"importedBundleId": clean["bundleId"], "ownerId": me})
        if existing is not None:
            counts = await counts_for([existing.id])
            return form_payload(existing, counts, user), False
    try:
        created = await _create_form(
            user, title=clean["form"]["title"], description=clean["form"]["description"], kind=clean["form"]["kind"],
            extra={"importedBundleId": clean["bundleId"]} if clean["bundleId"] else None,
        )
    except UniqueViolationError:
        existing = await db.questionnaire.find_first(where={"importedBundleId": clean["bundleId"], "ownerId": me})
        if existing is None:
            raise
        counts = await counts_for([existing.id])
        return form_payload(existing, counts, user), False
    await _write_structure(created.id, clean["sections"])
    counts = await counts_for([created.id])
    return form_payload(created, counts, user), True


# ── workbook answers as UPLOAD sessions (A2) ─────────────────────────────────────────────────────
async def import_workbook_answers(
    questionnaire_id: str, parsed: Any, user: Any, *, workshop_id: str | None = None, session_date: date | None = None,
) -> dict[str, int]:
    """Each answer column of a hand-filled pro-forma becomes one UPLOAD session on the new form, with
    the column's heading as its guest respondent, and every filled cell an answer with origin
    WORKBOOK (UNIFY U7). Returns ``{sessions, answers}``."""
    from app.questionnaire import answers as v2_answers
    from app.questionnaire import sessions as v2_sessions
    from app.questionnaire.common import today_ist
    from app.questionnaire.schemas import AnswersPut, SessionCreate

    labels = [label for label in (parsed.entryLabels or []) if str(label or "").strip()]
    if not labels:
        return {"sessions": 0, "answers": 0}
    rows = await db.query_raw(
        'SELECT q."id", COALESCE(q."lineageId", q."id") AS lineage, s."sortOrder" AS ssort, q."sortOrder" AS qsort '
        'FROM "QuestionnaireQuestion" q JOIN "QuestionnaireSection" s ON s."id" = q."sectionId" '
        'WHERE q."questionnaireId" = $1 ORDER BY s."sortOrder", q."sortOrder"',
        questionnaire_id,
    )
    by_position: dict[tuple[int, int], str] = {(int(r["ssort"]), int(r["qsort"])): str(r["lineage"]) for r in rows}
    day = session_date or today_ist()
    made_sessions = made_answers = 0
    for index, label in enumerate(labels):
        items: list[dict[str, Any]] = []
        for s_index, section in enumerate(parsed.sections, start=1):
            for q_index, question in enumerate(section.questions, start=1):
                text = (question.answers.get(label) or "").strip()
                note = (question.answerNotes.get(label) or "").strip() or None
                lineage = by_position.get((s_index, q_index))
                if lineage and (text or note):
                    items.append({"text": text, "note": note[:2000] if note else None, "lineage": lineage})
        if not items:
            continue
        payload = SessionCreate.model_validate({
            "clientKey": f"workbook-{questionnaire_id}-{index}",
            "questionnaireId": questionnaire_id,
            "workshopId": workshop_id,
            "sessionDate": day.isoformat(),
            "purpose": "FIRST_SITTING",
            "respondents": [{"name": str(label).strip()[:120], "kind": "GUEST"}],
            "source": "UPLOAD",
        })
        session, _created = await v2_sessions.create_session(payload, user, check_instrument=False)
        made_sessions += 1
        respondent_id = session["respondents"][0]["id"]
        for start in range(0, len(items), 400):
            chunk = items[start:start + 400]
            put = AnswersPut.model_validate({"items": [
                {"respondentId": respondent_id, "questionLineageId": it["lineage"], "text": it["text"], "origin": "WORKBOOK",
                 "sessionId": session["id"], "note": it["note"]}
                for it in chunk
            ]})
            result = await v2_answers.put_answers(put, user)
            made_answers += sum(1 for r in result["results"] if r.get("status") == "SAVED")
    return {"sessions": made_sessions, "answers": made_answers}


# ── the workshop annexure (A12; kinds route to the export annexure) ──────────────────────────────
KIND_ORDER = ("WORKSHOP_INTERVIEW", "MARKET_SURVEY")


async def annexure(workshop_id: str, *, client: Any = None) -> dict[str, Any]:
    """The forms of one workshop (bound to it, or answered in its sessions), grouped by kind, with
    each question's current answers from the workshop's LIVE sessions only."""
    db_ = client if client is not None else db
    workshop = await db_.workshop.find_unique(where={"id": workshop_id})
    if workshop is None:
        fail("NOT_FOUND", "No such workshop.")
    form_rows = await db_.query_raw(
        'SELECT DISTINCT q."id", q."title", q."kind", q."sortOrder" FROM "Questionnaire" q WHERE q."deletedAt" IS NULL AND ('
        'EXISTS (SELECT 1 FROM "QuestionnaireBinding" b WHERE b."questionnaireId" = q."id" AND b."workshopId" = $1) OR '
        'EXISTS (SELECT 1 FROM "QuestionnaireInterview" i WHERE i."questionnaireId" = q."id" AND i."workshopId" = $1 '
        'AND i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL)) ORDER BY q."sortOrder", q."id"',
        workshop_id,
    )
    form_ids = [str(r["id"]) for r in form_rows]
    session_rows = await db_.query_raw(
        'SELECT i."questionnaireId", count(*) AS n FROM "QuestionnaireInterview" i WHERE i."workshopId" = $1 '
        'AND i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL GROUP BY i."questionnaireId"',
        workshop_id,
    )
    sessions_per_form = {str(r["questionnaireId"]): int(r["n"]) for r in session_rows}
    answer_rows = await db_.query_raw(
        'SELECT a."questionnaireId", a."questionLineageId", rv."text", ar."name" AS artisan, r."name" AS respondent '
        'FROM "QuestionnaireAnswer" a JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" '
        'LEFT JOIN "Artisan" ar ON ar."id" = a."artisanId" LEFT JOIN "QuestionnaireRespondent" r ON r."id" = a."respondentId" '
        'WHERE i."workshopId" = $1 AND i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL AND COALESCE(rv."text", \'\') <> \'\' '
        'ORDER BY ar."name" NULLS LAST, r."name"',
        workshop_id,
    ) if form_ids else []
    answers_by: dict[tuple[str, str], list[dict[str, str]]] = {}
    for r in answer_rows:
        who = r.get("artisan") or r.get("respondent") or "Respondent"
        answers_by.setdefault((str(r["questionnaireId"]), str(r["questionLineageId"])), []).append({"subject": str(who), "text": str(r["text"])})
    groups: dict[str | None, list[dict[str, Any]]] = {}
    for form in form_rows:
        fid = str(form["id"])
        sections, questions = await live_structure(fid, client=db_)
        by_section: dict[str, list[Any]] = {}
        for q in questions:
            by_section.setdefault(str(_get(q, "sectionId")), []).append(q)
        doc_sections = []
        for s in sections:
            items = []
            for q in by_section.get(str(_get(s, "id")), []):
                lineage = str(_get(q, "lineageId") or _get(q, "id"))
                items.append({
                    "displayCode": _get(q, "displayCode") or str(_get(s, "code")),
                    "prompt": _get(q, "prompt"),
                    "answers": answers_by.get((fid, lineage), []),
                })
            doc_sections.append({"code": _get(s, "code"), "title": _get(s, "title"), "questions": items})
        kind = form.get("kind") if form.get("kind") in kinds.KIND_TOKENS else None
        groups.setdefault(kind, []).append({
            "id": fid, "title": form["title"], "kind": kind, "sessionCount": sessions_per_form.get(fid, 0), "sections": doc_sections,
        })
    ordered = [k for k in KIND_ORDER if k in groups] + ([None] if None in groups else [])
    return {
        "workshopId": workshop_id,
        "workshopTitle": _get(workshop, "title"),
        "groups": [
            {"kind": k, "label": kinds.label_for(k), "stage": kinds.stage_key_for(k, PORTAL), "forms": groups[k]} for k in ordered
        ],
    }


def annexure_text(doc: dict[str, Any]) -> str:
    """The annexure as plain text, for the workshop's export folder and the web download."""
    lines = [f"Questionnaire annexure: {doc.get('workshopTitle') or doc.get('workshopId')}", ""]
    if not doc["groups"]:
        lines.append("No questionnaires for this workshop yet.")
    for group in doc["groups"]:
        lines.append(f"== {group['label']} ==")
        for form in group["forms"]:
            sessions = form["sessionCount"]
            lines.append(f"-- {form['title']} ({sessions} session{'s' if sessions != 1 else ''})")
            for section in form["sections"]:
                lines.append(f"[{section['code']}] {section['title']}")
                for question in section["questions"]:
                    lines.append(f"  {question['displayCode']}. {question['prompt']}")
                    for answer in question["answers"]:
                        lines.append(f"     {answer['subject']}: {answer['text']}")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def annexure_filename(workshop_title: str | None) -> str:
    from app.services import naming

    return naming.export_filename("Questionnaire", "Annexure", "txt", subject=workshop_title or "Workshop")
