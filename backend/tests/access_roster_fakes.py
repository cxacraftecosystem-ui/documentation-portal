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

    def __init__(self, defaults: dict[str, Any], seed: list[dict[str, Any]] | None = None) -> None:
        self.defaults = defaults
        self.rows: list[SimpleNamespace] = []
        self.writes: list[tuple[str, dict[str, Any]]] = []
        for values in seed or []:
            self.rows.append(self._materialise(values))

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
            field, direction = next(iter(order.items()))
            found.sort(key=lambda row: getattr(row, field, None), reverse=direction == "desc")
        found = found[skip:]
        return found[:take] if take is not None else found

    async def count(self, where: dict[str, Any] | None = None, **_: Any) -> int:
        return len([row for row in self.rows if _matches(row, where)])

    # --- writes --------------------------------------------------------------------------------

    async def create(self, data: dict[str, Any], **_: Any) -> Any:
        row = self._materialise(dict(data))
        self.rows.append(row)
        self.writes.append(("create", dict(data)))
        return row

    async def update(self, where: dict[str, Any], data: dict[str, Any], **_: Any) -> Any:
        row = await self.find_unique(where)
        if row is None:
            return None
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


class FakeDb:
    """``db``, with the two tables the gate reads. Anything else raises rather than returning None —
    a silent ``None`` from an unmodelled table is how a test passes for the wrong reason."""

    def __init__(
        self,
        users: list[dict[str, Any]] | None = None,
        roster: list[dict[str, Any]] | None = None,
    ) -> None:
        self.user = FakeTable(USER_DEFAULTS, users)
        self.accessroster = FakeTable(ROSTER_DEFAULTS, roster)

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
