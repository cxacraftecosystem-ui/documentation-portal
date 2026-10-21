"""Count the round trips a piece of code makes to the database, for the tests that pin them.

Every Prisma call, ``query_raw`` and ``execute_raw`` included, reaches the query engine through ONE
method, ``engine.query``, and a transaction's client shares its parent's engine. Wrapping that method
therefore counts every statement batch the code sends, inside transactions or not, without touching
the code under test. Starting and committing an interactive transaction are separate engine calls and
are counted on their own (``transactions``), so a pinned number says exactly what it covers.

WHY ROUND TRIPS. The database sits in another region from the API: a round trip costs hundreds of
milliseconds while the statements behind it cost well under one (services/concurrency.py has the
measurements). The number that moves a page is how many round trips it makes, and the number that
says a list endpoint is N+1 is whether that count grows with the list.
"""

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
    """``with counting(client) as seen: ...`` then ``seen.count``."""
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
    """Append ``name key=value ...`` to ``$QUERY_COUNT_REPORT`` when it is set (before/after tables)."""
    path = os.environ.get("QUERY_COUNT_REPORT", "").strip()
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(name + " " + " ".join(f"{k}={v}" for k, v in numbers.items()) + "\n")
