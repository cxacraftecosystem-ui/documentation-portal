"""``db.tx()`` for the in-memory databases the review tests build.

``review.set_review_status`` runs a decision in one interactive transaction (``async with
db.tx() as tx``) and writes through ``tx``. A fake database that has no ``tx`` would fail every
decision, so a fixture hands its fake to :func:`with_tx`, which adds one that yields the same fake.

:class:`RollbackTx` goes further for the tests that are ABOUT the transaction: it snapshots every
table's rows on entry and puts them back when the block raises, so "nothing was written" is a fact
about the rows rather than a mock's say-so.
"""

from __future__ import annotations

import copy
from typing import Any


class PassThroughTx:
    def __init__(self, client: Any) -> None:
        self.client = client
        self.opened = 0

    def __call__(self, **_: Any) -> PassThroughTx:
        return self

    async def __aenter__(self) -> Any:
        self.opened += 1
        return self.client

    async def __aexit__(self, *exc: object) -> bool:
        return False


class RollbackTx(PassThroughTx):
    def __init__(self, client: Any) -> None:
        super().__init__(client)
        self.rolled_back = 0
        self._saved: dict[str, tuple[list[Any], list[Any]]] = {}

    def _tables(self) -> dict[str, Any]:
        return {name: table for name, table in vars(self.client).items() if hasattr(table, "rows")}

    async def __aenter__(self) -> Any:
        self._saved = {
            name: (copy.deepcopy(table.rows), copy.deepcopy(getattr(table, "created", [])))
            for name, table in self._tables().items()
        }
        return await super().__aenter__()

    async def __aexit__(self, exc_type: Any, *rest: object) -> bool:
        if exc_type is not None:
            self.rolled_back += 1
            for name, table in self._tables().items():
                rows, created = self._saved[name]
                table.rows[:] = rows
                if hasattr(table, "created"):
                    table.created[:] = created
        return False


def with_tx(fake: Any, kind: type[PassThroughTx] = PassThroughTx) -> Any:
    fake.tx = kind(fake)
    return fake
