"""An in-memory stand-in for the three Prisma delegates transcript insights touch.

Deliberately small: equality, ``None``, ``in``, ``not_in``, ``not``, ``lt``/``lte``/``gte`` and
ordering by one or more columns. Every create and update is appended to ``writes`` so a test can
assert that a dry run wrote nothing as a fact about the calls made.
"""

from __future__ import annotations

import copy
import itertools
from datetime import UTC, datetime
from typing import Any

_ids = itertools.count(1)


class Row:
    def __init__(self, **columns: Any) -> None:
        self.__dict__.update(columns)

    def __getattr__(self, name: str) -> Any:  # unknown columns read as NULL, like Prisma's
        return None


def _unwrap(value: Any) -> Any:
    # prisma.Json wraps a value in .data
    return getattr(value, "data", value) if type(value).__name__ == "Json" else value


def _matches(row: Row, where: dict[str, Any]) -> bool:
    for key, cond in (where or {}).items():
        value = getattr(row, key)
        if isinstance(cond, dict):
            for op, arg in cond.items():
                if op == "in" and value not in arg:
                    return False
                if op == "not_in" and value in arg:
                    return False
                if op == "not" and value == arg:
                    return False
                if op == "lt" and not (value is not None and value < arg):
                    return False
                if op == "lte" and not (value is not None and value <= arg):
                    return False
                if op == "gte" and not (value is not None and value >= arg):
                    return False
        elif value != cond:
            return False
    return True


def _sorted(rows: list[Row], order: Any) -> list[Row]:
    orders = order if isinstance(order, list) else ([order] if order else [])
    for clause in reversed(orders):
        ((column, direction),) = clause.items()
        rows = sorted(rows, key=lambda r: (getattr(r, column) is None, getattr(r, column)),
                      reverse=direction == "desc")
    return rows


class Delegate:
    def __init__(self, store: FakeDb, name: str, defaults: dict[str, Any] | None = None) -> None:
        self.store = store
        self.name = name
        self.rows: list[Row] = []
        self.defaults = defaults or {}

    def _include(self, row: Row, include: dict[str, Any] | None) -> Row:
        if include and include.get("mediaFile"):
            out = Row(**row.__dict__)
            out.mediaFile = self.store.mediafile.get(row.mediaFileId)
            return out
        return row

    def get(self, row_id: str) -> Row | None:
        return next((r for r in self.rows if r.id == row_id), None)

    async def find_unique(self, where: dict[str, Any], include: dict[str, Any] | None = None):
        row = next((r for r in self.rows if _matches(r, where)), None)
        return self._include(row, include) if row else None

    async def find_first(self, where: dict[str, Any] | None = None, order: Any = None,
                         include: dict[str, Any] | None = None):
        rows = _sorted([r for r in self.rows if _matches(r, where or {})], order)
        return self._include(rows[0], include) if rows else None

    async def find_many(self, where: dict[str, Any] | None = None, order: Any = None,
                        take: int | None = None, skip: int = 0, cursor: Any = None,
                        include: dict[str, Any] | None = None):
        rows = _sorted([r for r in self.rows if _matches(r, where or {})], order)
        if cursor:
            index = next(i for i, r in enumerate(rows) if r.id == cursor["id"])
            rows = rows[index:]
        rows = rows[skip:]
        if take is not None:
            rows = rows[:take]
        return [self._include(r, include) for r in rows]

    async def count(self, where: dict[str, Any] | None = None) -> int:
        return len([r for r in self.rows if _matches(r, where or {})])

    async def create(self, data: dict[str, Any]):
        columns = {**self.defaults, **{k: _unwrap(v) for k, v in data.items()}}
        columns.setdefault("id", f"{self.name}_{next(_ids)}")
        columns.setdefault("createdAt", datetime.now(UTC))
        if self.name == "transcriptinsight":
            for other in self.rows:
                if other.mediaFileId == columns["mediaFileId"] and other.version == columns["version"]:
                    raise RuntimeError("unique violation (mediaFileId, version)")
        row = Row(**columns)
        self.rows.append(row)
        self.store.writes.append((self.name, "create", copy.deepcopy(columns)))
        return row

    async def update(self, where: dict[str, Any], data: dict[str, Any], include: Any = None):
        row = next(r for r in self.rows if _matches(r, where))
        row.__dict__.update({k: _unwrap(v) for k, v in data.items()})
        self.store.writes.append((self.name, "update", dict(where), dict(data)))
        return self._include(row, include)

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        hit = [r for r in self.rows if _matches(r, where)]
        for row in hit:
            row.__dict__.update({k: _unwrap(v) for k, v in data.items()})
        self.store.writes.append((self.name, "update_many", dict(where), dict(data)))
        return len(hit)


class FakeDb:
    def __init__(self) -> None:
        self.writes: list[tuple] = []
        self.mediafile = Delegate(self, "mediafile")
        self.transcriptinsight = Delegate(
            self, "transcriptinsight",
            {"editedAt": None, "editedById": None, "supersededAt": None, "modelVersion": None},
        )
        self.mediaprocessingjob = Delegate(
            self, "mediaprocessingjob",
            {"attempts": 0, "error": None, "requestedById": None, "runAfter": datetime(2000, 1, 1, tzinfo=UTC)},
        )

    def add_media(self, **columns: Any) -> Row:
        base = {
            "id": f"med_{next(_ids)}",
            "mediaType": "AUDIO",
            "uploadedById": "usr_owner",
            "transcriptText": None,
            "transcriptSummary": None,
            "transcriptStatus": None,
            "transcriptEditedAt": None,
            "objectKey": "k",
            "createdAt": datetime.now(UTC),
        }
        base.update(columns)
        row = Row(**base)
        self.mediafile.rows.append(row)
        return row
