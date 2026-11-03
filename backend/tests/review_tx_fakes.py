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
