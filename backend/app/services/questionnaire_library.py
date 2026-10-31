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
* restore a deleted form: its author or a content admin (``authoring.may_restore_form``, one rule in
  both portals);
* create, reuse or import a bundle: questionnaire managers (Professor and above), who become the
  author.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.questionnaire import authoring, kinds
from app.questionnaire.common import fail

SCOPES = ("mine", "shared", "workshop", "deleted")
PORTAL = "documentation-portal"

#: The refusals, worded for the screen.
RESTORE_REFUSAL = "Only the questionnaire's author or an admin can restore it."


def may_restore(user: Any, form: Any) -> bool:
    """Bring a deleted form back: its author or a content admin (the shared ``authoring`` rule)."""
    return authoring.may_restore_form(user, form)


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
            "restore": deleted and may_restore(user, row),
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
    ids: set[str] | None = None,
) -> list[dict[str, Any]]:
    """``GET /questionnaires``. With no ``scope`` the list is what it always was (every live form, or
    every form with ``activeOnly=false``), so older clients keep their picker. A scope narrows it:

    * ``mine``: forms this account wrote;
    * ``shared``: forms shared with everyone;
    * ``workshop``: forms bound to ``workshopId`` (or to any workshop when none is named);
    * ``deleted``: soft-deleted forms: every one for a content admin, your own for an author.
    """
    if scope is not None and scope not in SCOPES:
        fail("VALIDATION_FAILED", f"scope is one of {', '.join(SCOPES)}.")
    where: list[str] = []
    params: list[Any] = []

    def p(value: Any) -> str:
        params.append(value)
        return f"${len(params)}"

    if scope == "deleted":
        where.append('q."deletedAt" IS NOT NULL')
        if not authoring.is_content_admin(user):
            me = p(_uid(user) or "")
            where.append(f'(q."ownerId" = {me} OR (q."ownerId" IS NULL AND q."createdById" = {me}))')
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
    if ids is not None:
        # The shared filters' answer (``app/questionnaire/filters.form_ids_matching``).
        where.append(f'q."id" = ANY({p(sorted(ids))}::text[])')
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
    """Its author or a content admin brings a deleted form back, with everything recorded on it."""
    row = await require_form(questionnaire_id, allow_deleted=True)
    if not may_restore(user, row):
        fail("FORBIDDEN", RESTORE_REFUSAL)
    if _get(row, "deletedAt") is None and _get(row, "isActive"):
        counts = await counts_for([questionnaire_id])
        return form_payload(row, counts, user)
    updated = await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"isActive": True, "deletedAt": None, "deletedById": None}
    )
    counts = await counts_for([questionnaire_id])
    return form_payload(updated, counts, user)


# ── the shared library (app/questionnaire/library.py): one behaviour in both portals ─────────────
from app.questionnaire import library as _shared

BUNDLE_VERSION = _shared.BUNDLE_VERSION
BUNDLE_EXTENSIONS = _shared.BUNDLE_EXTENSIONS
BUNDLE_LIMITS = _shared.BUNDLE_LIMITS
free_title = _shared.free_title
live_structure = _shared.live_structure
bundle_document = _shared.bundle_document
bundle_bytes = _shared.bundle_bytes
read_bundle_bytes = _shared.read_bundle_bytes
normalise_bundle = _shared.normalise_bundle
import_workbook_answers = _shared.import_workbook_answers
annexure_text = _shared.annexure_text
annexure_filename = _shared.annexure_filename
KIND_ORDER = _shared.KIND_ORDER


def bundle_filename(title: str | None) -> str:
    return _shared.bundle_filename(title)


async def _write_structure(form_id: str, sections: list[dict[str, Any]]) -> tuple[int, int]:
    return await _shared.write_structure(form_id, sections)


async def _create_form(user: Any, *, title: str, description: str | None, kind: str | None, extra: dict[str, Any] | None = None) -> Any:
    return await _shared.create_form(user, title=title, description=description, kind=kind, extra=extra)


# ── reuse (A5) ──────────────────────────────────────────────────────────────────────────────────
async def reuse(questionnaire_id: str, user: Any, *, title: str | None = None, description: str | None = None,
                kind: str | None = None, keep_kind: bool = True) -> dict[str, Any]:
    """A new form of your own with this form's questions (the shared ``library.reuse``)."""
    from app.core.deps import can_manage_questionnaire

    if not can_manage_questionnaire(user):
        fail("FORBIDDEN", "Questionnaire management access required.")
    created, _counts = await _shared.reuse(questionnaire_id, user, title=title, description=description, kind=kind, keep_kind=keep_kind)
    counts = await counts_for([created.id])
    return form_payload(created, counts, user)


# ── the handoff bundle (A10; shared/questionnaire-bundle.json) ────────────────────────────────────
async def import_bundle(document: dict[str, Any], user: Any) -> tuple[dict[str, Any], bool]:
    """A new form of your own from a received bundle (the shared ``library.import_bundle``)."""
    row, created = await _shared.import_bundle(document, user)
    counts = await counts_for([row.id])
    return form_payload(row, counts, user), created


# ── the workshop annexure (A12), the shape both portals serve ─────────────────────────────────────
async def annexure(workshop_id: str, *, client: Any = None) -> dict[str, Any]:
    return await _shared.annexure(workshop_id=workshop_id, client=client)
