"""Advisory locks for /complete (DESIGN section 3.3, rule 3).

Flagging a take as CONCURRENT or LATE_ARRIVAL compares it with the prior takes of the same artisans,
so two completes for overlapping subjects must not both read "no prior take". Each takes
``pg_advisory_xact_lock(hash(artisanId, questionnaireId))`` per subject, ALWAYS IN SORTED ORDER, so
twenty concurrent completes over overlapping groups queue instead of deadlocking. The locks are
transaction-scoped: they release on commit or rollback, never leak.
"""

from __future__ import annotations

from typing import Any


def lock_keys(artisan_ids: list[str], questionnaire_id: str) -> list[str]:
    return sorted({f"qv2:{questionnaire_id}:{artisan_id}" for artisan_id in artisan_ids})


async def lock_subjects(tx: Any, artisan_ids: list[str], questionnaire_id: str) -> list[str]:
    keys = lock_keys(artisan_ids, questionnaire_id)
    for key in keys:
        await tx.query_raw("SELECT count(*) AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended($1, 0))) AS l", key)
    return keys


async def lock_key(tx: Any, key: str) -> None:
    await tx.query_raw("SELECT count(*) AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended($1, 0))) AS l", key)
