from typing import Any

from app.scale.flags import approx_count_enabled, log_once, settings

_UNKNOWN_RELTUPLES = 0


async def total_for(
    delegate: Any,
    *,
    where: dict[str, Any] | None,
    table: str | None = None,
    threshold: int | None = None,
) -> tuple[int, bool]:
    if not approx_count_enabled():
        return await delegate.count(where=where), False

    limit = threshold if threshold is not None else settings().scale_approx_count_threshold
    limit = max(1, limit)

    if not where:
        estimate = await _reltuples(table or _table_of(delegate))
        if estimate is not None and estimate > limit:
            return estimate, True

    capped = await delegate.count(where=where, take=limit + 1)
    if capped > limit:
        return limit, True
    return capped, False


async def _reltuples(table: str | None) -> int | None:
    if not table:
        return None
    from app.core.db import db

    try:
        rows = await db.query_raw(
            "SELECT reltuples::bigint AS estimate FROM pg_class WHERE oid = to_regclass($1)",
            f'"{table}"',
        )
    except Exception as exc:  # noqa: BLE001 - an estimate is an optimisation; failing it is not fatal
        log_once(
            f"counts.reltuples.{type(exc).__name__}",
            "Could not read a row estimate from pg_class (%s: %s); totals will be counted exactly.",
            type(exc).__name__,
            exc,
        )
        return None
    if not rows:
        return None
    estimate = rows[0].get("estimate")
    if estimate is None or int(estimate) <= _UNKNOWN_RELTUPLES:
        return None
    return int(estimate)


def _table_of(delegate: Any) -> str | None:
    model = getattr(delegate, "_model", None)
    name = getattr(model, "__name__", None)
    return name if isinstance(name, str) else None


def count_payload(payload: dict[str, Any], is_approximate: bool) -> dict[str, Any]:
    if not is_approximate:
        return payload
    return {**payload, "totalIsApproximate": True}
