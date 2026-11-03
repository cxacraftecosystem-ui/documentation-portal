from __future__ import annotations

import os
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any


class QueryCount:
    def __init__(self) -> None:
        self.queries: list[str] = []
        self.transactions = 0

    @property
    def count(self) -> int:
        return len(self.queries)

    def __repr__(self) -> str:
        return f"QueryCount(queries={self.count}, transactions={self.transactions})"


@contextmanager
def counting(client: Any) -> Iterator[QueryCount]:
    engine = client._engine
    original_query = engine.query
    original_start = engine.start_transaction
    seen = QueryCount()

    async def query(content: str, *, tx_id: Any = None) -> Any:
        seen.queries.append(content)
        return await original_query(content, tx_id=tx_id)

    async def start_transaction(*, content: str) -> Any:
        seen.transactions += 1
        return await original_start(content=content)

    engine.query = query
    engine.start_transaction = start_transaction
    try:
        yield seen
    finally:
        del engine.query
        del engine.start_transaction


def report(name: str, **numbers: int) -> None:
    path = os.environ.get("QUERY_COUNT_REPORT", "").strip()
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(name + " " + " ".join(f"{k}={v}" for k, v in numbers.items()) + "\n")
