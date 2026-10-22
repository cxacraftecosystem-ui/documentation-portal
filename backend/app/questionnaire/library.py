"""The forms library's shared writes and documents (UNIFY A2, A5, A10, A12; both portals).

Moved here from Field Repository's ``services/questionnaire_library.py`` (U3) so both portals behave
identically: reuse a form as a template, the handoff bundle (export and import, ``.frwq``/``.dpwq``
and designer-portal Android's v1 file, ``BUNDLE_VERSION`` for a newer major), a hand-filled
workbook's answers as WORKBOOK sessions, and the workshop annexure in one shape. Everything reads and
writes the instrument tables (``QuestionnaireSection``/``QuestionnaireQuestion``) and the sessions;
designer-portal reaches these once a form is unified (``forms.form_unified``).

A portal differs only in what the ports answer: its name (``ports.portal()``, which names the bundle
file and scopes a workshop: ``workshopId`` on Field Repository, ``designWorkshopId`` here), its
database, who manages questionnaires, its naming helper and its cross-portal question codes.
"""

from __future__ import annotations

import gzip
import io
import json
import secrets
from datetime import UTC, date, datetime
from typing import Any

from app.questionnaire import authoring, kinds, ports
from app.questionnaire.common import fail

BUNDLE_VERSION = 2
BUNDLE_EXTENSIONS = (".frwq", ".dpwq")
BUNDLE_LIMITS = {"maxSections": 200, "maxQuestionsPerSection": 500, "maxTextChars": 4000, "maxUncompressedBytes": 5 * 1024 * 1024}
KIND_ORDER = ("WORKSHOP_INTERVIEW", "MARKET_SURVEY")


def _get(obj: Any, name: str) -> Any:
    if obj is None:
        return None
    if isinstance(obj, dict):
        return obj.get(name)
    return getattr(obj, name, None)


def _uid(user: Any) -> str | None:
    value = _get(user, "id")
    return str(value) if value else None


def _fr() -> bool:
    return ports.portal() == "documentation-portal"


def bundle_extension() -> str:
    return "frwq" if _fr() else "dpwq"


def may_restore(user: Any, form: Any) -> bool:
    """Bring a deleted form back: its author or a content admin (one rule in both portals)."""
    return authoring.may_restore_form(user, form)


async def require_form(questionnaire_id: str, *, allow_deleted: bool = False) -> Any:
    row = await ports.db().questionnaire.find_unique(where={"id": questionnaire_id})
    if row is None:
        fail("NOT_FOUND", "No such questionnaire.")
    if not allow_deleted and _get(row, "deletedAt") is not None:
        fail("FORM_DELETED")
    return row


# ── titles and the form row ──────────────────────────────────────────────────────────────────────
async def free_title(wanted: str) -> str:
    """The first free ``Title``, ``Title (2)``, ``Title (3)``… (a copy or an import never takes a
    title another form has)."""
    base = (wanted or "Untitled questionnaire").strip()[:200] or "Untitled questionnaire"
    rows = await ports.db().query_raw('SELECT "title" FROM "Questionnaire" WHERE "title" = $1 OR "title" LIKE $2', base, f"{base} (%)")
    taken = {str(r["title"]) for r in rows}
    if base not in taken:
        return base
    n = 2
    while f"{base} ({n})" in taken:
        n += 1
    return f"{base} ({n})"


async def _next_sort_order() -> int:
    rows = await ports.db().query_raw('SELECT COALESCE(MAX("sortOrder"), 0) AS m FROM "Questionnaire"')
    return int(rows[0]["m"] or 0) + 1


async def create_form(user: Any, *, title: str, description: str | None, kind: str | None, extra: dict[str, Any] | None = None) -> Any:
    """A new form owned by ``user``, at the end of the library, under a free title."""
    from prisma.errors import UniqueViolationError

    me = _uid(user)
    for _attempt in range(3):
        data: dict[str, Any] = {
            "title": await free_title(title),
            "description": description or None,
            "sortOrder": await _next_sort_order(),
            "ownerId": me,
            "kind": kind,
            **(extra or {}),
        }
        if _fr():
            data["createdById"] = me
        try:
            return await ports.db().questionnaire.create(data=data)
        except UniqueViolationError:
            continue
    fail("VALIDATION_FAILED", "Another form took that title just now. Try again.")
    return None


async def live_structure(questionnaire_id: str, *, client: Any = None) -> tuple[list[Any], list[Any]]:
    """The live sections in order and their live, current questions in order."""
    db_ = client if client is not None else ports.db()
    sections = await db_.questionnairesection.find_many(
        where={"questionnaireId": questionnaire_id, "isActive": True}, order={"sortOrder": "asc"}
    )
    questions = await db_.questionnairequestion.find_many(
        where={"questionnaireId": questionnaire_id, "isActive": True, "retiredAt": None, "supersededById": None},
        order={"sortOrder": "asc"},
    )
    return sections, questions


async def write_structure(form_id: str, sections: list[dict[str, Any]], *, tables: Any = None) -> tuple[int, int]:
    """Create ``sections`` (``{code, title, questions: [{prompt, helpText, isRequired, artisanField}]}``)
    on a fresh form, in order. Returns ``(sections, questions)`` written. ``tables`` is the form's
    (``forms.tables``); by default the instrument tables."""
    from app.questionnaire import forms

    db = ports.db()
    T = tables if tables is not None else forms.tables(db, True)
    taken: set[str] = set()
    n_sections = n_questions = 0
    for order, section in enumerate(sections, start=1):
        code = (section.get("code") or "").strip()[:20] or f"S{order}"
        while code.lower() in taken:
            code = f"{code}{order}"
        taken.add(code.lower())
        title = (section.get("title") or code).strip()[:200] or code
        made = await T.sections.create(data={"questionnaireId": form_id, "code": code, "title": title, "sortOrder": order})
        n_sections += 1
        for position, question in enumerate(section.get("questions") or [], start=1):
            prompt = (question.get("prompt") or "").strip()
            if not prompt:
                continue
            help_text = question.get("helpText")
            data: dict[str, Any] = {
                "sectionId": made.id,
                "prompt": prompt[: BUNDLE_LIMITS["maxTextChars"]],
                "helpText": str(help_text)[:1000] if help_text else None,
                "isRequired": bool(question.get("isRequired")),
                "sortOrder": position,
            }
            if T.unified:
                data["artisanField"] = question.get("artisanField") or None
            await T.questions.create(data=data)
            n_questions += 1
    if T.unified:
        await ports.codes.reconcile_question_codes(db, questionnaire_id=form_id)
    else:
        await ports.codes.reconcile_form_question_codes(db, questionnaire_id=form_id)
    return n_sections, n_questions


async def counts_for(form_ids: list[str]) -> dict[str, dict[str, int]]:
    """Live sections and live questions per form: one statement."""
    if not form_ids:
        return {}
    rows = await ports.db().query_raw(
        'SELECT q."id", '
        '(SELECT count(*) FROM "QuestionnaireSection" s WHERE s."questionnaireId" = q."id" AND s."isActive") AS sections, '
        '(SELECT count(*) FROM "QuestionnaireQuestion" x WHERE x."questionnaireId" = q."id" AND x."isActive" '
        '   AND x."retiredAt" IS NULL AND x."supersededById" IS NULL) AS questions '
        'FROM "Questionnaire" q WHERE q."id" = ANY($1::text[])',
        form_ids,
    )
    return {str(r["id"]): {"sections": int(r["sections"]), "questions": int(r["questions"])} for r in rows}


def _plan(sections: list[Any], questions: list[Any]) -> list[dict[str, Any]]:
    by_section: dict[str, list[Any]] = {}
    for q in questions:
        by_section.setdefault(str(_get(q, "sectionId")), []).append(q)
    return [
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


# ── reuse (A5) ──────────────────────────────────────────────────────────────────────────────────
async def reuse(questionnaire_id: str, user: Any, *, title: str | None = None, description: str | None = None,
                kind: str | None = None, keep_kind: bool = True, extra: dict[str, Any] | None = None,
                tables: Any = None) -> tuple[Any, dict[str, int]]:
    """A new form of your own with this form's questions: sections and questions only, never its
    sessions, answers, bindings, sharing or default. Returns the new row and ``{sections, questions}``."""
    source = await require_form(questionnaire_id)
    try:
        wanted_kind = kinds.coerce_kind(kind) if kind is not None else (_get(source, "kind") if keep_kind else None)
    except ValueError as exc:
        fail("VALIDATION_FAILED", str(exc))
    sections, questions = await live_structure(questionnaire_id)
    created = await create_form(
        user, title=(title or "").strip() or f"{_get(source, 'title')} (copy)",
        description=description if description is not None else _get(source, "description"), kind=wanted_kind, extra=extra,
    )
    n_sections, n_questions = await write_structure(created.id, _plan(sections, questions), tables=tables)
    return created, {"sections": n_sections, "questions": n_questions}


# ── the handoff bundle (A10; shared/questionnaire-bundle.json) ────────────────────────────────────
async def bundle_document(questionnaire_id: str) -> dict[str, Any]:
    form = await require_form(questionnaire_id)
    sections, questions = await live_structure(questionnaire_id)
    code_of = {str(_get(s, "id")): str(_get(s, "code")) for s in sections}
    return {
        "v": BUNDLE_VERSION,
        "bundleId": secrets.token_hex(16),
        "createdAt": datetime.now(UTC).isoformat(),
        "portal": ports.portal(),
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


def bundle_filename(title: str | None, *, at: Any = None) -> str:
    """``<Portal>_Questionnaire_<date>_<Title>_Handoff.<frwq|dpwq>`` (the naming golden table's row)."""
    return ports.naming.export_filename("Questionnaire", "Handoff", bundle_extension(), subject=title or "Questionnaire", at=at)


def read_bundle_bytes(raw: bytes) -> dict[str, Any]:
    """A received file: gzipped JSON (either extension), or plain JSON. Untrusted; size-capped."""
    limit = BUNDLE_LIMITS["maxUncompressedBytes"]
    data = raw
    if raw[:2] == b"\x1f\x8b":
        try:
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as handle:
                data = handle.read(limit + 1)
        except (OSError, EOFError):
            fail("VALIDATION_FAILED", "This file isn't a questionnaire bundle.")
    if len(data) > limit:
        fail("VALIDATION_FAILED", "This bundle is too large.")
    try:
        parsed = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("VALIDATION_FAILED", "This file isn't a questionnaire bundle.")
    if not isinstance(parsed, dict):
        fail("VALIDATION_FAILED", "This file isn't a questionnaire bundle.")
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
            fail("VALIDATION_FAILED", "This file isn't a questionnaire bundle.")
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
        fail("VALIDATION_FAILED", "This file isn't a questionnaire bundle.")
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
    if bid and not all(c in "0123456789abcdef-" for c in bid):
        bid = None
    return {
        "bundleId": bid[:64] if bid else None,
        "form": {"title": _text(title, 200), "description": _text(description, 2000), "kind": kind_token},
        "sections": clean_sections,
    }


async def import_bundle(document: dict[str, Any], user: Any, *, tables: Any = None) -> tuple[Any, bool]:
    """A new form of your own from a received bundle. Idempotent on the bundle id per importer: a
    second import of the same file returns the first copy (``created`` False)."""
    from prisma.errors import UniqueViolationError

    if not ports.can_manage_questionnaire(user):
        fail("FORBIDDEN", "Questionnaire management access required.")
    clean = normalise_bundle(document)
    me = _uid(user)
    db = ports.db()
    if clean["bundleId"]:
        existing = await db.questionnaire.find_first(where={"importedBundleId": clean["bundleId"], "ownerId": me})
        if existing is not None:
            return existing, False
    try:
        created = await create_form(
            user, title=clean["form"]["title"], description=clean["form"]["description"], kind=clean["form"]["kind"],
            extra={"importedBundleId": clean["bundleId"]} if clean["bundleId"] else None,
        )
    except UniqueViolationError:
        existing = await db.questionnaire.find_first(where={"importedBundleId": clean["bundleId"], "ownerId": me})
        if existing is None:
            raise
        return existing, False
    await write_structure(created.id, clean["sections"], tables=tables)
    return created, True


# ── a hand-filled workbook's answers as WORKBOOK sessions (A2) ─────────────────────────────────────
async def import_workbook_answers(
    questionnaire_id: str, parsed: Any, user: Any, *, workshop_id: str | None = None, design_workshop_id: str | None = None,
    session_date: date | None = None,
) -> dict[str, int]:
    """Each answer column of a hand-filled pro-forma becomes one UPLOAD session on the form, with the
    column's heading as its guest respondent, and every filled cell an answer with origin WORKBOOK
    (UNIFY U7). Matched by position (section order, question order). Returns ``{sessions, answers}``."""
    from app.questionnaire import answers as v2_answers, sessions as v2_sessions
    from app.questionnaire.common import today_ist
    from app.questionnaire.schemas import AnswersPut, SessionCreate

    labels = [label for label in (_get(parsed, "entryLabels") or []) if str(label or "").strip()]
    if not labels:
        return {"sessions": 0, "answers": 0}
    rows = await ports.db().query_raw(
        'SELECT q."id", COALESCE(q."lineageId", q."id") AS lineage, s."sortOrder" AS ssort, q."sortOrder" AS qsort '
        'FROM "QuestionnaireQuestion" q JOIN "QuestionnaireSection" s ON s."id" = q."sectionId" '
        'WHERE q."questionnaireId" = $1 AND q."isActive" AND q."supersededById" IS NULL ORDER BY s."sortOrder", q."sortOrder"',
        questionnaire_id,
    )
    sections_order = sorted({int(r["ssort"]) for r in rows})
    by_position: dict[tuple[int, int], str] = {}
    for s_index, ssort in enumerate(sections_order, start=1):
        in_section = [r for r in rows if int(r["ssort"]) == ssort]
        for q_index, r in enumerate(in_section, start=1):
            by_position[(s_index, q_index)] = str(r["lineage"])
    day = session_date or today_ist()
    made_sessions = made_answers = 0
    for index, label in enumerate(labels):
        items: list[dict[str, Any]] = []
        for s_index, section in enumerate(_get(parsed, "sections") or [], start=1):
            for q_index, question in enumerate(_get(section, "questions") or [], start=1):
                text = ((_get(question, "answers") or {}).get(label) or "").strip()
                note = ((_get(question, "answerNotes") or {}).get(label) or "").strip() or None
                lineage = by_position.get((s_index, q_index))
                if lineage and (text or note):
                    items.append({"text": text, "note": note[:2000] if note else None, "lineage": lineage})
        if not items:
            continue
        payload = SessionCreate.model_validate({
            "clientKey": f"workbook-{questionnaire_id}-{index}",
            "questionnaireId": questionnaire_id,
            "workshopId": workshop_id,
            "designWorkshopId": design_workshop_id,
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


# ── the workshop annexure (A12), one shape in both portals ─────────────────────────────────────────
async def annexure(*, workshop_id: str | None = None, design_workshop_id: str | None = None, client: Any = None) -> dict[str, Any]:
    """The forms of one workshop (bound to it, or answered in its sessions), grouped by kind, live
    sessions only. Each form carries its questions with the current answers (``sections``) and one
    sitting per respondent (``sittings``: artisan or guest, every question with its answer), so a
    report can print either. ``workshop_id`` scopes a Field Repository workshop, ``design_workshop_id``
    a designer-portal design workshop."""
    db = client if client is not None else ports.db()
    if bool(workshop_id) == bool(design_workshop_id):
        fail("VALIDATION_FAILED", "Name one workshopId or designWorkshopId.")
    column, binding_column, table = (
        ('"workshopId"', '"workshopId"', '"Workshop"') if workshop_id else ('"designWorkshopId"', '"designWorkshopId"', '"DesignWorkshop"')
    )
    scope = workshop_id or design_workshop_id
    found = await db.query_raw(f'SELECT "id", "title" FROM {table} WHERE "id" = $1', scope)
    if not found:
        fail("NOT_FOUND", "No such workshop.")
    live = f'i.{column} = $1 AND i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL'
    form_rows = await db.query_raw(
        'SELECT DISTINCT q."id", q."title", q."kind", q."sortOrder", q."version" FROM "Questionnaire" q WHERE q."deletedAt" IS NULL AND ('
        f'EXISTS (SELECT 1 FROM "QuestionnaireBinding" b WHERE b."questionnaireId" = q."id" AND b.{binding_column} = $1) OR '
        f'EXISTS (SELECT 1 FROM "QuestionnaireInterview" i WHERE i."questionnaireId" = q."id" AND {live})) ORDER BY q."sortOrder", q."id"',
        scope,
    )
    sessions = await db.query_raw(
        'SELECT i."id", i."questionnaireId", i."title", i."legacyTitle", i."source"::text AS source, i."note", i."sessionDate", '
        f'u."name" AS lead FROM "QuestionnaireInterview" i LEFT JOIN "User" u ON u."id" = i."createdById" WHERE {live} '
        'ORDER BY i."sessionDate", i."createdAt"',
        scope,
    ) if form_rows else []
    sids = [str(s["id"]) for s in sessions]
    owners = await db.query_raw(
        'SELECT p."interviewId" AS s, \'A:\' || p."artisanId" AS k, a."name" FROM "QuestionnaireInterviewArtisan" p '
        'JOIN "Artisan" a ON a."id" = p."artisanId" WHERE p."interviewId" = ANY($1::text[]) '
        'UNION ALL SELECT r."sessionId", \'R:\' || r."id", r."name" FROM "QuestionnaireRespondent" r '
        'WHERE r."sessionId" = ANY($1::text[]) AND r."artisanId" IS NULL',
        sids,
    ) if sids else []
    answers = await db.query_raw(
        'SELECT rv."sessionId" AS s, COALESCE(\'A:\' || a."artisanId", \'R:\' || a."respondentId") AS k, a."questionLineageId" AS l, '
        'a."questionnaireId" AS f, rv."text", rv."note", COALESCE(ar."name", r."name") AS who FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'LEFT JOIN "Artisan" ar ON ar."id" = a."artisanId" LEFT JOIN "QuestionnaireRespondent" r ON r."id" = a."respondentId" '
        "WHERE rv.\"sessionId\" = ANY($1::text[]) AND btrim(COALESCE(rv.\"text\", '')) <> ''",
        sids,
    ) if sids else []
    by_cell = {(str(r["s"]), str(r["k"]), str(r["l"])): r for r in answers}
    by_question: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for r in sorted(answers, key=lambda r: str(r.get("who") or "")):
        by_question.setdefault((str(r["f"]), str(r["l"])), []).append({"subject": str(r.get("who") or "Respondent"), "text": str(r["text"])})
    owners_by: dict[str, list[dict[str, Any]]] = {}
    for o in owners:
        owners_by.setdefault(str(o["s"]), []).append(o)
    groups: dict[str | None, list[dict[str, Any]]] = {}
    for form in form_rows:
        fid = str(form["id"])
        sections, questions = await live_structure(fid, client=db)
        code_of = {str(_get(s, "id")): _get(s, "code") for s in sections}
        by_section: dict[str, list[Any]] = {}
        for q in questions:
            by_section.setdefault(str(_get(q, "sectionId")), []).append(q)
        ordered = [q for s in sections for q in by_section.get(str(_get(s, "id")), [])]
        doc_sections = [
            {
                "code": _get(s, "code"), "title": _get(s, "title"),
                "questions": [
                    {"displayCode": _get(q, "displayCode") or str(_get(s, "code")), "prompt": _get(q, "prompt"),
                     "answers": by_question.get((fid, str(_get(q, "lineageId") or _get(q, "id"))), [])}
                    for q in by_section.get(str(_get(s, "id")), [])
                ],
            }
            for s in sections
        ]
        sittings: list[dict[str, Any]] = []
        for session in (s for s in sessions if str(s["questionnaireId"]) == fid):
            day = session.get("sessionDate")
            for owner in owners_by.get(str(session["id"]), []):
                cells = [
                    (q, by_cell.get((str(session["id"]), str(owner["k"]), str(_get(q, "lineageId") or _get(q, "id")))))
                    for q in ordered
                ]
                sittings.append({
                    "id": f"{session['id']}:{owner['k']}",
                    "sessionId": str(session["id"]),
                    "label": str(owner.get("name") or ""),
                    "title": str(session.get("legacyTitle") or session.get("title") or ""),
                    "source": str(session.get("source") or "APP"),
                    "recordedAt": day.isoformat() if hasattr(day, "isoformat") else (str(day) if day else None),
                    "recordedBy": session.get("lead"),
                    "notes": session.get("note"),
                    "answers": [
                        {"prompt": _get(q, "prompt"), "answer": str((cell or {}).get("text") or ""), "notes": (cell or {}).get("note"),
                         "sectionCode": code_of.get(str(_get(q, "sectionId"))), "retired": False}
                        for q, cell in cells
                    ],
                })
        printed = [s for s in sittings if any(a["answer"] for a in s["answers"])]
        kind = form.get("kind") if form.get("kind") in kinds.KIND_TOKENS else None
        groups.setdefault(kind, []).append({
            "id": fid, "questionnaireId": fid, "title": form["title"], "kind": kind, "version": int(form.get("version") or 1),
            "sessionCount": len({str(s["id"]) for s in sessions if str(s["questionnaireId"]) == fid}),
            "questionCount": len(ordered),
            "answeredTotal": sum(1 for s in printed for a in s["answers"] if a["answer"]),
            "emptySittings": len(sittings) - len(printed),
            "sections": doc_sections,
            "sittings": printed,
        })
    keys = [k for k in KIND_ORDER if k in groups] + ([None] if None in groups else [])
    return {
        "workshopId": workshop_id,
        "designWorkshopId": design_workshop_id,
        "workshopTitle": found[0].get("title"),
        "groups": [
            {"kind": k, "label": kinds.label_for(k), "stage": kinds.stage_key_for(k, ports.portal()), "forms": groups[k]} for k in keys
        ],
    }


def annexure_text(doc: dict[str, Any]) -> str:
    """The annexure as plain text, for a workshop's export folder and the web download."""
    lines = [f"Questionnaire annexure: {doc.get('workshopTitle') or doc.get('workshopId') or doc.get('designWorkshopId')}", ""]
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
    return ports.naming.export_filename("Questionnaire", "Annexure", "txt", subject=workshop_title or "Workshop")
