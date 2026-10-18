"""An in-memory stand-in for the Prisma client, just wide enough for the artisan-card routes.

It keeps the two constraints the card code depends on: ``ArtisanCard.code`` is unique, and at most
one card per artisan has ``revokedAt`` null (the partial index ``ArtisanCard_one_live_per_artisan``).
Both raise an exception named ``UniqueViolationError``, as prisma-client-py's does. ``tx()`` is a
real snapshot-and-restore, so a transaction that fails part-way leaves nothing behind.
"""

from __future__ import annotations

import asyncio
import copy
import itertools
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any


class UniqueViolationError(Exception):
    pass


_ids = itertools.count(1)


def _match_value(have: Any, want: Any) -> bool:
    if isinstance(want, dict):
        for op, arg in want.items():
            if op == "in" and have not in arg:
                return False
            if op == "not" and (
                have == arg if not isinstance(arg, dict) else _match_value(have, arg)
            ):
                return False
            if op == "gte" and (have is None or have < arg):
                return False
            if op == "is":
                return have is not None and _match(have, arg)
            if op == "some":
                return any(_match(item, arg) for item in (have or []))
        return True
    return have == want


def _match(row: Any, where: dict[str, Any] | None) -> bool:
    for key, want in (where or {}).items():
        if key == "AND":
            if not all(_match(row, w) for w in want):
                return False
            continue
        if key == "OR":
            if not any(_match(row, w) for w in want):
                return False
            continue
        if key == "NOT":
            items = want if isinstance(want, list) else [want]
            if any(_match(row, w) for w in items):
                return False
            continue
        have = row.get(key) if isinstance(row, dict) else getattr(row, key, None)
        if not _match_value(have, want):
            return False
    return True


class Table:
    def __init__(self, db: FakeDB, name: str, rows: list[dict[str, Any]] | None = None, **defaults):
        self.db = db
        self.name = name
        self.rows: list[dict[str, Any]] = [dict(r) for r in rows or []]
        self.defaults = defaults

    def _view(self, row: dict[str, Any], include: dict[str, Any] | None = None) -> SimpleNamespace:
        data = dict(row)
        for rel in include or {}:
            data[rel] = self.db.relation(self.name, rel, row)
        return SimpleNamespace(**data)

    def _check(self, candidate: dict[str, Any], skip: dict[str, Any] | None = None) -> None:
        for field in self.db.unique.get(self.name, ()):
            value = candidate.get(field)
            if value is None:
                continue
            for row in self.rows:
                if row is not skip and row.get(field) == value:
                    raise UniqueViolationError(f"Unique constraint failed on {self.name}.{field}")
        if self.name == "artisancard" and candidate.get("revokedAt") is None:
            for row in self.rows:
                if (
                    row is not skip
                    and row["artisanId"] == candidate["artisanId"]
                    and row.get("revokedAt") is None
                ):
                    raise UniqueViolationError(
                        "Unique constraint failed on ArtisanCard_one_live_per_artisan"
                    )

    async def find_unique(self, where, include=None):
        await asyncio.sleep(0)
        hit = next((r for r in self.rows if _match(r, where)), None)
        return self._view(hit, include) if hit else None

    find_first = find_unique

    async def find_many(
        self, where=None, order=None, include=None, take=None, skip=None, cursor=None
    ):
        await asyncio.sleep(0)
        rows = [r for r in self.rows if _match(r, where)]
        if order:
            ((field, direction),) = order.items()
            rows.sort(
                key=lambda r: (r.get(field) is None, r.get(field)), reverse=direction == "desc"
            )
        if cursor:
            ((field, value),) = cursor.items()
            index = next((i for i, r in enumerate(rows) if r.get(field) == value), len(rows))
            rows = rows[index:]
        if skip:
            rows = rows[skip:]
        if take is not None:
            rows = rows[:take]
        return [self._view(r, include) for r in rows]

    async def count(self, where=None):
        await asyncio.sleep(0)
        return len([r for r in self.rows if _match(r, where)])

    async def create(self, data, include=None):
        await asyncio.sleep(0)
        row = {**self.defaults, "id": f"{self.name}-{next(_ids)}", **data}
        if self.name == "artisancard":
            row.setdefault("revokedAt", None)
            row.setdefault("issuedAt", datetime.now(UTC))
        if self.name == "artisancardevent":
            row.setdefault("createdAt", datetime.now(UTC))
        self._check(row)
        self.rows.append(row)
        return self._view(row, include)

    async def create_many(self, data):
        for item in data:
            await self.create(item)
        return len(data)

    async def update(self, where, data, include=None):
        row = next((r for r in self.rows if _match(r, where)), None)
        if row is None:
            raise LookupError("record not found")
        merged = {**row, **data}
        self._check(merged, skip=row)
        row.update(data)
        return self._view(row, include)

    async def update_many(self, where, data):
        hits = [r for r in self.rows if _match(r, where)]
        for row in hits:
            row.update(data)
        return len(hits)

    async def delete_many(self, where):
        before = len(self.rows)
        self.rows = [r for r in self.rows if not _match(r, where)]
        return before - len(self.rows)

    async def upsert(self, where, data):
        for row in self.rows:
            if _match(row, where):
                row.update(data["update"])
                return SimpleNamespace(**row)
        return await self.create(data["create"])


class _Tx:
    def __init__(self, db: FakeDB):
        self.db = db

    async def __aenter__(self):
        self.snapshot = {name: copy.deepcopy(t.rows) for name, t in self.db.tables.items()}
        return self.db

    async def __aexit__(self, exc_type, exc, tb):
        if exc_type is not None:
            for name, rows in self.snapshot.items():
                self.db.tables[name].rows = rows
        return False


class FakeDB:
    unique = {"artisancard": ("code",), "crossportalartisanlink": ("remoteCardCode",)}

    def __init__(self, **tables: list[dict[str, Any]]):
        self.tables: dict[str, Table] = {}
        for name in (
            "artisan",
            "artisancard",
            "artisancardevent",
            "mediafile",
            "workshop",
            "craft",
            "location",
            "artisansharingconsent",
            "crossportalartisanlink",
            "crossportalaccesslog",
            "dataaccessgrant",
        ):
            self.tables[name] = Table(self, name, tables.get(name))

    def __getattr__(self, name: str) -> Table:
        tables = self.__dict__.get("tables", {})
        if name in tables:
            return tables[name]
        raise AttributeError(name)

    def tx(self) -> _Tx:
        return _Tx(self)

    def relation(self, table: str, rel: str, row: dict[str, Any]) -> Any:
        if table == "artisan" and rel == "craft":
            hit = next((c for c in self.craft.rows if c["id"] == row.get("craftId")), None)
            return SimpleNamespace(**hit) if hit else None
        if table == "artisan" and rel == "location":
            hit = next((c for c in self.location.rows if c["id"] == row.get("locationId")), None)
            return SimpleNamespace(**hit) if hit else None
        return row.get(rel)
