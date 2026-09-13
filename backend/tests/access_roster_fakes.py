"""An in-memory stand-in for the two tables the sign-in gate touches, shared by the gate tests.

NOT A TEST MODULE (no ``test_`` prefix), so pytest imports it rather than collecting it.

WHY A FAKE AND NOT A DATABASE. There is no local Postgres on this machine and ``backend/.env``
points at the LIVE production pooler; a test suite that connected to it would be running the gate's
migrations against real users. So the tables are modelled here, in about a hundred lines, with the
handful of query shapes the gate actually issues. The rule this file lives by: it implements the
QUERIES, never the DECISIONS. ``find_first(where={"email": ..., "status": "ACTIVE"})`` filters on
both keys here exactly as Postgres would, which is what makes
``test_a_row_that_is_not_active_does_not_admit`` a real test of ``roster_admits`` rather than a test
of a helper that was told the answer.
"""

import sys
from datetime import UTC, datetime
from itertools import count
from types import SimpleNamespace
from typing import Any

import pytest
from prisma.errors import UniqueViolationError

import app.core.db as core_db

_ids = count(1)


def _now() -> datetime:
    return datetime.now(UTC)


#: Column defaults, mirroring prisma/schema.prisma. Kept here rather than sprinkled through the
#: fake's ``create`` so that a column added to the model shows up in one place.
USER_DEFAULTS: dict[str, Any] = {
    "name": "Someone",
    "passwordHash": None,
    "avatarUrl": None,
    "role": "RESEARCHER",
    "authProvider": "LOCAL",
    "canManageQuestionnaire": False,
    "canManageCrafts": False,
    "canManageWorkshops": False,
    "canReview": False,
    "canViewProvenance": False,
    "canDownloadDataset": False,
}

ROSTER_DEFAULTS: dict[str, Any] = {
    "status": "PENDING",
    "grantedRole": "CROWDSOURCE_VOLUNTEER",
    "fullName": None,
    "notes": None,
    "joinedAt": None,
    "firstSeenAt": None,
    "requestCount": 1,
    "decidedAt": None,
    "decidedById": None,
}


def _matches(row: Any, where: dict[str, Any] | None) -> bool:
    """Equality matching, plus the two operator shapes the gate's queries use."""
    for field, expected in (where or {}).items():
        actual = getattr(row, field, None)
        if isinstance(expected, dict):
            if "in" in expected:
                if actual not in expected["in"]:
                    return False
            elif "contains" in expected:
                needle = str(expected["contains"]).lower()
                if needle not in str(actual or "").lower():
                    return False
            else:  # pragma: no cover - a query shape the fake has not been taught
                raise NotImplementedError(f"fake db cannot match {field}={expected!r}")
        elif actual != expected:
            return False
    return True


class FakeTable:
    """One table. Rows are ``SimpleNamespace`` so handler code reads them by attribute, as it does
    with prisma models."""

    def __init__(
        self,
        defaults: dict[str, Any],
        seed: list[dict[str, Any]] | None = None,
        unique_keys: tuple[tuple[str, ...], ...] = (),
    ) -> None:
        self.defaults = defaults
        self.rows: list[SimpleNamespace] = []
        self.writes: list[tuple[str, dict[str, Any]]] = []
        #: Composite unique indexes this table carries, as tuples of column names. EMPTY by default,
        #: so every caller that predates this sees no behaviour change at all. A test that is ABOUT
        #: a unique index must declare it, or it is not testing anything: the fake would otherwise
        #: accept two rows the database refuses and the test goes green over a bug. That is not
        #: hypothetical — the seeder's whole reordering design exists because
        #: `@@unique([questionnaireId, sortOrder])` refuses a mid-corpus collision, and against a
        #: fake with no uniqueness the unsound version of that design passes.
        self.unique_keys = unique_keys
        for values in seed or []:
            self.rows.append(self._materialise(values))

    def _refuse_duplicate(self, values: dict[str, Any], exclude_id: str | None = None) -> None:
        """Raise as Postgres would.

        NULLs are exempt — Postgres treats them as distinct under a unique index, which is exactly
        why `artisanSetKey IS NULL` interviews are not deduped and why
        `@@unique([questionnaireId, artisanSetKey])` still allows several per instrument.
        """
        for key in self.unique_keys:
            probe = tuple(values.get(field) for field in key)
            if any(part is None for part in probe):
                continue
            for other in self.rows:
                if exclude_id is not None and getattr(other, "id", None) == exclude_id:
                    continue
                if tuple(getattr(other, field, None) for field in key) == probe:
                    raise UniqueViolationError(
                        {
                            "user_facing_error": {
                                "message": (
                                    f"duplicate key value violates unique constraint "
                                    f"{key}: {probe}"
                                ),
                                "meta": {},
                            }
                        }
                    )

    def _materialise(self, values: dict[str, Any]) -> SimpleNamespace:
        stamp = values.get("createdAt") or _now()
        row = {
            "id": values.get("id") or f"row-{next(_ids)}",
            **self.defaults,
            "createdAt": stamp,
            "updatedAt": stamp,
            "firstRequestedAt": stamp,
            "lastRequestedAt": stamp,
        }
        row.update(values)
        return SimpleNamespace(**row)

    # --- reads ---------------------------------------------------------------------------------

    async def find_unique(self, where: dict[str, Any], **_: Any) -> Any:
        return next((row for row in self.rows if _matches(row, where)), None)

    async def find_first(self, where: dict[str, Any] | None = None, **_: Any) -> Any:
        return next((row for row in self.rows if _matches(row, where)), None)

    async def find_many(
        self,
        where: dict[str, Any] | None = None,
        skip: int = 0,
        take: int | None = None,
        order: dict[str, str] | None = None,
        **_: Any,
    ) -> list[Any]:
        found = [row for row in self.rows if _matches(row, where)]
        if order:
            # A LIST of order clauses is the multi-key form prisma-client-py accepts
            # (`order=[{"sortOrder": "asc"}, {"createdAt": "asc"}]`) and the resolver's default
            # lookup uses it. Applied last-key-first so the first clause is the primary sort, which
            # is what a stable sort gives for free.
            clauses = order if isinstance(order, list) else [order]
            for clause in reversed(clauses):
                field, direction = next(iter(clause.items()))
                found.sort(key=lambda row: getattr(row, field, None), reverse=direction == "desc")
        found = found[skip:]
        return found[:take] if take is not None else found

    async def count(self, where: dict[str, Any] | None = None, **_: Any) -> int:
        return len([row for row in self.rows if _matches(row, where)])

    # --- writes --------------------------------------------------------------------------------

    async def create(self, data: dict[str, Any], **_: Any) -> Any:
        row = self._materialise(dict(data))
        # BEFORE the append, so a refusal leaves no row behind — which is what makes "raised before
        # the first write" an assertable property rather than a hope.
        self._refuse_duplicate(vars(row))
        self.rows.append(row)
        self.writes.append(("create", dict(data)))
        return row

    async def update(self, where: dict[str, Any], data: dict[str, Any], **_: Any) -> Any:
        row = await self.find_unique(where)
        if row is None:
            return None
        candidate = {**vars(row), **data}
        self._refuse_duplicate(candidate, exclude_id=getattr(row, "id", None))
        for field, value in data.items():
            setattr(row, field, value)
        row.updatedAt = _now()
        self.writes.append(("update", dict(data)))
        return row

    async def update_many(self, where: dict[str, Any], data: dict[str, Any], **_: Any) -> int:
        touched = [row for row in self.rows if _matches(row, where)]
        for row in touched:
            for field, value in data.items():
                setattr(row, field, value)
            row.updatedAt = _now()
        self.writes.append(("update_many", dict(data)))
        return len(touched)

    async def delete(self, where: dict[str, Any], **_: Any) -> Any:
        row = await self.find_unique(where)
        if row is not None:
            self.rows.remove(row)
            self.writes.append(("delete", dict(where)))
        return row


#: Column defaults for the questionnaire container and its children, mirroring prisma/schema.prisma
#: exactly as USER_DEFAULTS does. A column added to a model shows up in one place.
QUESTIONNAIRE_DEFAULTS: dict[str, Any] = {
    "title": "An instrument",
    "description": None,
    "isActive": True,
    "isDefault": False,
    "sortOrder": 1,
    "createdById": None,
}

SECTION_DEFAULTS: dict[str, Any] = {
    "questionnaireId": None,
    "code": "A",
    "title": "A section",
    "sortOrder": 1,
    "isActive": True,
}

QUESTION_DEFAULTS: dict[str, Any] = {
    "questionnaireId": None,
    "sectionId": None,
    "sectionCode": "A",
    "sectionTitle": "A section",
    "prompt": "A prompt",
    "sortOrder": 1,
    "isActive": True,
}

RESPONSE_DEFAULTS: dict[str, Any] = {
    "interviewId": None,
    "questionId": None,
    "answerText": None,
    "notes": None,
    "answeredById": None,
}

INTERVIEW_DEFAULTS: dict[str, Any] = {
    "title": "An interview",
    "questionnaireId": None,
    "artisanSetKey": None,
    "workshopId": None,
    "status": "PENDING",
    "createdById": None,
}


class FakeDb:
    """``db``, with the tables the gate and the questionnaire seeder read. Anything else raises
    rather than returning None — a silent ``None`` from an unmodelled table is how a test passes for
    the wrong reason.

    THE SAME SENTENCE IS THE ARGUMENT FOR THE UNIQUE KEYS BELOW. A fake that models a table but not
    its unique indexes accepts writes the database refuses, so a test about a unique index passes
    over broken code — which is a subtler version of exactly the failure the docstring rule above
    exists to prevent.
    """

    def __init__(
        self,
        users: list[dict[str, Any]] | None = None,
        roster: list[dict[str, Any]] | None = None,
        questionnaires: list[dict[str, Any]] | None = None,
        sections: list[dict[str, Any]] | None = None,
        questions: list[dict[str, Any]] | None = None,
        responses: list[dict[str, Any]] | None = None,
        interviews: list[dict[str, Any]] | None = None,
    ) -> None:
        self.user = FakeTable(USER_DEFAULTS, users)
        self.accessroster = FakeTable(ROSTER_DEFAULTS, roster)
        self.questionnaire = FakeTable(
            QUESTIONNAIRE_DEFAULTS, questionnaires, unique_keys=(("title",),)
        )
        self.questionnairesection = FakeTable(
            SECTION_DEFAULTS,
            sections,
            unique_keys=(("questionnaireId", "code"), ("questionnaireId", "sortOrder")),
        )
        # NO unique index, by design: the (sectionId, sortOrder) one was dropped by
        # 20260616120000_questionnaire_sections/migration.sql:52 so questions could move between
        # sections, and adding it now would force a two-pass into `reorder_questions`.
        self.questionnairequestion = FakeTable(QUESTION_DEFAULTS, questions)
        self.questionnaireresponse = FakeTable(
            RESPONSE_DEFAULTS, responses, unique_keys=(("interviewId", "questionId"),)
        )
        self.questionnaireinterview = FakeTable(
            INTERVIEW_DEFAULTS, interviews, unique_keys=(("questionnaireId", "artisanSetKey"),)
        )

    def __getattr__(self, name: str) -> Any:  # pragma: no cover - a guard, not a path
        raise AssertionError(
            f"The sign-in gate touched db.{name}, which this fake does not model. If that is "
            f"deliberate, teach the fake; if it is not, the gate just grew a dependency."
        )


def install(monkeypatch: pytest.MonkeyPatch, fake: FakeDb) -> FakeDb:
    """Rebind every already-imported ``app.*`` module's ``db`` to *fake*.

    The modules do ``from app.core.db import db``, so each holds its OWN reference and patching the
    source alone would miss all of them. Rebinding BY IDENTITY finds every one already imported and
    keeps finding them when a new module is added — the same technique
    ``tests/test_permission_matrix.py`` uses, and for the same reason.
    """
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", fake)
    return fake
