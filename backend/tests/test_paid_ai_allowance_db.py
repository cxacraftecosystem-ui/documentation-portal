from __future__ import annotations

import asyncio
import os
import uuid

import pytest

from app.core.db import db
from app.services import paid_ai_allowance

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)


def test_the_counters_live_in_the_database_and_survive_a_restart(monkeypatch):
    bucket = f"test_{uuid.uuid4().hex[:12]}"
    monkeypatch.setitem(paid_ai_allowance._DEFAULTS, bucket, (2, 3))
    monkeypatch.setattr(paid_ai_allowance, "STORE", getattr(paid_ai_allowance, "DATABASE", None), raising=False)

    async def scenario() -> None:
        await db.connect()
        try:
            await paid_ai_allowance.take(bucket, "usr_one")
            await paid_ai_allowance.take(bucket, "usr_one")
            paid_ai_allowance.reset()
            with pytest.raises(paid_ai_allowance.AllowanceSpent) as mine:
                await paid_ai_allowance.take(bucket, "usr_one")
            assert 0 < mine.value.retry_after <= int(paid_ai_allowance.WINDOW_SECONDS)
            await paid_ai_allowance.take(bucket, "usr_two")
            with pytest.raises(paid_ai_allowance.AllowanceSpent) as everyone:
                await paid_ai_allowance.take(bucket, "usr_three")
            assert "portal" in everyone.value.message
            await paid_ai_allowance.refund(bucket, "usr_two")
            await paid_ai_allowance.take(bucket, "usr_three")
            rows = await db.query_raw(
                'SELECT "userId" FROM "PaidAiSpend" WHERE "bucket" = $1 ORDER BY "spentAt", "id"', bucket
            )
            assert sorted(str(row["userId"]) for row in rows) == ["usr_one", "usr_one", "usr_three"]
        finally:
            await db.execute_raw('DELETE FROM "PaidAiSpend" WHERE "bucket" = $1', bucket)
            await db.disconnect()

    asyncio.run(scenario())
