"""WHICH FORM A SESSION STARTS ON: bindings, workshop-type defaults and the global default (UNIFY
U5 and section 2.3 rule 5, as amended by the owner on 22 Oct 2026, amendment 2 points 2 and 3).

* ``QuestionnaireBinding`` links a form to a ``Workshop`` (both portals) or a ``DesignWorkshop``
  (designer-portal); several forms per workshop, at most ONE primary (a partial unique index).
* ``QuestionnaireTypeDefault`` is an admin's default form for a workshop TYPE: ``Workshop.workshopType``
  (DESIGN_PROTOTYPE, OTHER) or a design workshop's ``workshopKind``; one per type.
* ``Questionnaire.isDefault`` is the global default.

The start sheet offers, in this order (:func:`resolve`): (1) the form passed explicitly; (2) the
workshop's primary binding; (3) its only binding; (4) the default for the workshop's type or kind;
(5) the global default; (6) otherwise the picker, grouped by kind. A user may always pick any other
form they can see; users create their own forms as before, and only admins bind or set defaults
(``authoring.may_admin_act``). Deleted (``deletedAt``) and switched-off forms are never offered.

The mirrors (FR ``Workshop.questionnaireId``, DR ``Questionnaire.designWorkshopId``) are kept in
step with the bindings by database triggers until the cleanup migration drops them.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any
from uuid import uuid4

from app.questionnaire import authoring, ports
from app.questionnaire.common import fail, get

EXPLICIT = "EXPLICIT"
PRIMARY_BINDING = "PRIMARY_BINDING"
ONLY_BINDING = "ONLY_BINDING"
TYPE_DEFAULT = "TYPE_DEFAULT"
GLOBAL_DEFAULT = "GLOBAL_DEFAULT"
PICKER = "PICKER"
#: The order of :func:`resolve`, for clients and tests.
ORDER = (EXPLICIT, PRIMARY_BINDING, ONLY_BINDING, TYPE_DEFAULT, GLOBAL_DEFAULT, PICKER)

WORKSHOP_TYPES = ("DESIGN_PROTOTYPE", "OTHER")


@dataclass(frozen=True)
class Binding:
    questionnaireId: str
    isPrimary: bool = False
    kind: str | None = None


@dataclass
class Resolution:
    questionnaireId: str | None
    reason: str
    candidates: list[Binding] = field(default_factory=list)

    def out(self) -> dict[str, Any]:
        groups: dict[str, list[str]] = {}
        for b in self.candidates:
            groups.setdefault(b.kind or "", []).append(b.questionnaireId)
        return {
            "questionnaireId": self.questionnaireId,
            "reason": self.reason,
            "bound": [{"questionnaireId": b.questionnaireId, "isPrimary": b.isPrimary, "kind": b.kind} for b in self.candidates],
            "boundByKind": [{"kind": k or None, "questionnaireIds": v} for k, v in groups.items()],
        }


def resolve(
    *, explicit: str | None = None, bindings: list[Binding] | None = None, type_default: str | None = None,
    global_default: str | None = None, live: set[str] | None = None,
) -> Resolution:
    """The pure rule. ``live`` (when given) is the set of forms that may be offered at all."""

    def ok(qid: str | None) -> bool:
        return bool(qid) and (live is None or qid in live)

    bound = [b for b in (bindings or []) if ok(b.questionnaireId)]
    if ok(explicit):
        return Resolution(explicit, EXPLICIT, bound)
    primary = [b for b in bound if b.isPrimary]
    if primary:
        return Resolution(primary[0].questionnaireId, PRIMARY_BINDING, bound)
    if len(bound) == 1:
        return Resolution(bound[0].questionnaireId, ONLY_BINDING, bound)
    if len(bound) > 1:
        # Several forms bound and none primary: the picker, grouped by kind (rule 5).
        return Resolution(None, PICKER, bound)
    if ok(type_default):
        return Resolution(type_default, TYPE_DEFAULT, bound)
    if ok(global_default):
        return Resolution(global_default, GLOBAL_DEFAULT, bound)
    return Resolution(None, PICKER, bound)


# ── reading ────────────────────────────────────────────────────────────────────────────────────
_LIVE_FORM = 'q."isActive" AND q."deletedAt" IS NULL'


async def bindings_for_workshop(client: Any, *, workshop_id: str | None = None, design_workshop_id: str | None = None) -> list[Binding]:
    if not workshop_id and not design_workshop_id:
        return []
    column = '"workshopId"' if workshop_id else '"designWorkshopId"'
    rows = await client.query_raw(
        f'SELECT b."questionnaireId", b."isPrimary", q."kind" FROM "QuestionnaireBinding" b '
        f'JOIN "Questionnaire" q ON q."id" = b."questionnaireId" WHERE b.{column} = $1 AND {_LIVE_FORM} '
        'ORDER BY b."isPrimary" DESC, b."createdAt", b."questionnaireId"',
        workshop_id or design_workshop_id,
    )
    return [Binding(str(r["questionnaireId"]), bool(r["isPrimary"]), r.get("kind")) for r in rows]


async def type_default_for(client: Any, *, workshop_id: str | None = None, design_workshop_id: str | None = None) -> str | None:
    if workshop_id:
        rows = await client.query_raw(
            'SELECT d."questionnaireId" FROM "QuestionnaireTypeDefault" d JOIN "Workshop" w ON w."workshopType" = d."workshopType" '
            f'JOIN "Questionnaire" q ON q."id" = d."questionnaireId" WHERE w."id" = $1 AND {_LIVE_FORM} LIMIT 1',
            workshop_id,
        )
    elif design_workshop_id:
        rows = await client.query_raw(
            'SELECT d."questionnaireId" FROM "QuestionnaireTypeDefault" d JOIN "DesignWorkshop" w ON w."workshopKind" = d."designWorkshopKind" '
            f'JOIN "Questionnaire" q ON q."id" = d."questionnaireId" WHERE w."id" = $1 AND {_LIVE_FORM} LIMIT 1',
            design_workshop_id,
        )
    else:
        return None
    return str(rows[0]["questionnaireId"]) if rows else None


async def global_default(client: Any) -> str | None:
    rows = await client.query_raw(
        f'SELECT q."id" FROM "Questionnaire" q WHERE q."isDefault" AND {_LIVE_FORM} ORDER BY q."sortOrder", q."id" LIMIT 1'
    )
    return str(rows[0]["id"]) if rows else None


async def is_live_form(client: Any, questionnaire_id: str | None) -> bool:
    if not questionnaire_id:
        return False
    rows = await client.query_raw(f'SELECT 1 AS ok FROM "Questionnaire" q WHERE q."id" = $1 AND {_LIVE_FORM}', questionnaire_id)
    return bool(rows)


async def resolve_for_workshop(
    *, workshop_id: str | None = None, design_workshop_id: str | None = None, explicit: str | None = None, client: Any = None,
) -> dict[str, Any]:
    """``GET /questionnaire/form-for``: the start sheet's default form for a workshop."""
    db = client if client is not None else ports.db()
    if workshop_id and design_workshop_id:
        fail("VALIDATION_FAILED", "Give a workshopId or a designWorkshopId, not both.")
    bound = await bindings_for_workshop(db, workshop_id=workshop_id, design_workshop_id=design_workshop_id)
    live = {b.questionnaireId for b in bound}
    if explicit and await is_live_form(db, explicit):
        live.add(explicit)
    type_default = await type_default_for(db, workshop_id=workshop_id, design_workshop_id=design_workshop_id)
    if type_default:
        live.add(type_default)
    default = await global_default(db)
    if default:
        live.add(default)
    return resolve(explicit=explicit, bindings=bound, type_default=type_default, global_default=default, live=live).out()


# ── writing (admins) ───────────────────────────────────────────────────────────────────────────
async def set_bindings(questionnaire_id: str, items: list[Any], user: Any, *, client: Any = None) -> list[dict[str, Any]]:
    """``PUT /questionnaires/{id}/bindings``: the full list of workshops this form is bound to, each
    ``{workshopId | designWorkshopId, isPrimary}``. Admins only. Making a form primary for a workshop
    clears that workshop's previous primary, in the same transaction."""
    if not authoring.may_admin_act(user):
        fail("FORBIDDEN", authoring.ADMIN_ACT_REFUSAL)
    db = client if client is not None else ports.db()
    if not await is_live_form(db, questionnaire_id):
        fail("NOT_FOUND", "No such questionnaire.")
    wanted = []
    for item in items:
        wid, dwid = get(item, "workshopId"), get(item, "designWorkshopId")
        if bool(wid) == bool(dwid):
            fail("VALIDATION_FAILED", "Each binding names a workshopId or a designWorkshopId.")
        wanted.append((wid, dwid, bool(get(item, "isPrimary"))))
    async with db.tx() as tx:
        await tx.execute_raw('DELETE FROM "QuestionnaireBinding" WHERE "questionnaireId" = $1', questionnaire_id)
        for wid, dwid, primary in wanted:
            if primary:
                column = '"workshopId"' if wid else '"designWorkshopId"'
                await tx.execute_raw(
                    f'UPDATE "QuestionnaireBinding" SET "isPrimary" = false WHERE {column} = $1 AND "isPrimary"', wid or dwid
                )
            await tx.execute_raw(
                'INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "workshopId", "designWorkshopId", "isPrimary", "createdById") '
                'VALUES ($1, $2, $3, $4, $5, $6)',
                uuid4().hex, questionnaire_id, wid, dwid, primary, str(get(user, "id")),
            )
    return await bindings_of_form(db, questionnaire_id)


async def bindings_of_form(client: Any, questionnaire_id: str) -> list[dict[str, Any]]:
    rows = await client.query_raw(
        'SELECT "workshopId", "designWorkshopId", "isPrimary" FROM "QuestionnaireBinding" WHERE "questionnaireId" = $1 '
        'ORDER BY "createdAt", "id"',
        questionnaire_id,
    )
    return [{"workshopId": r.get("workshopId"), "designWorkshopId": r.get("designWorkshopId"), "isPrimary": bool(r["isPrimary"])} for r in rows]


async def set_type_default(
    questionnaire_id: str | None, user: Any, *, workshop_type: str | None = None, design_workshop_kind: str | None = None,
    client: Any = None,
) -> dict[str, Any]:
    """An admin's default form for one workshop type (or design-workshop kind); ``None`` clears it."""
    if not authoring.may_admin_act(user):
        fail("FORBIDDEN", authoring.ADMIN_ACT_REFUSAL)
    if bool(workshop_type) == bool(design_workshop_kind):
        fail("VALIDATION_FAILED", "Name a workshopType or a designWorkshopKind.")
    if workshop_type and workshop_type not in WORKSHOP_TYPES:
        fail("VALIDATION_FAILED", f"workshopType is one of {', '.join(WORKSHOP_TYPES)}.")
    db = client if client is not None else ports.db()
    column = '"workshopType"' if workshop_type else '"designWorkshopKind"'
    value = workshop_type or design_workshop_kind
    cast = '::"WorkshopType"' if workshop_type else ""
    async with db.tx() as tx:
        await tx.execute_raw(f'DELETE FROM "QuestionnaireTypeDefault" WHERE {column} = $1{cast}', value)
        if questionnaire_id:
            if not await is_live_form(tx, questionnaire_id):
                fail("NOT_FOUND", "No such questionnaire.")
            await tx.execute_raw(
                f'INSERT INTO "QuestionnaireTypeDefault" ("id", "questionnaireId", {column}, "setById") VALUES ($1, $2, $3{cast}, $4)',
                uuid4().hex, questionnaire_id, value, str(get(user, "id")),
            )
    return {"questionnaireId": questionnaire_id, "workshopType": workshop_type, "designWorkshopKind": design_workshop_kind}
