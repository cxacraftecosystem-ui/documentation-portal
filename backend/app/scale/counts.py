"""Row totals for list pages: exact while a table is small, estimated once it is not.

WHY A LIST PAGE'S ``total`` IS THE EXPENSIVE HALF. Every paged route here runs two queries: the page
(``LIMIT 20``, an index scan that stops early) and ``COUNT(*)`` over the whole filtered set, which
cannot stop early — Postgres has to visit every matching row to say how many there are. The page
stays cheap as the table grows; the count does not. On a cross-region link where each round trip is
already ~250ms, the count becomes the endpoint.

WHY THE DEFAULT THRESHOLD MEANS NOTHING CHANGES TODAY. The largest table in this database holds 925
rows and the threshold defaults to 5000, so with the flag ON every total is still computed exactly
and every number the UI shows is the number it shows now. The flag arms a behaviour for the table
that grows past the point of caring, and it can be turned on long before then without anybody seeing
a difference — which is the only honest way to introduce an approximation.

THE TWO ESTIMATES, and why there are two. An UNFILTERED count has a genuinely free answer:
``pg_class.reltuples``, the row count the planner already keeps and that ANALYZE refreshes. A
FILTERED count has no such shortcut — the planner's estimate for an arbitrary predicate is a guess
built from statistics, and presenting a guess as a total is worse than presenting a bound. So a
filtered count is CAPPED instead: ask for at most ``threshold + 1`` rows, and if that many come back
report "at least threshold" and let the UI say ``5,000+``. A capped count is exact below the cap and
honest above it, and it never scans more than the cap.
"""

from typing import Any

from app.scale.flags import approx_count_enabled, log_once, settings

# reltuples is -1 on a table that has never been analysed (PostgreSQL 14+; older versions report 0
# and a genuinely empty table also reports 0). Either way the number carries no information, so the
# caller falls back to counting.
_UNKNOWN_RELTUPLES = 0


async def total_for(
    delegate: Any,
    *,
    where: dict[str, Any] | None,
    table: str | None = None,
    threshold: int | None = None,
) -> tuple[int, bool]:
    """``(total, is_approximate)`` for a list page.

    With the flag off this is ``await delegate.count(where=where)`` and ``is_approximate`` is always
    False — byte for byte what the route does today.

    ``table`` is the PHYSICAL table name, needed only for the unfiltered estimate. This schema
    declares no ``@@map``, so it is the Prisma model name verbatim and case-sensitively
    (``"Artisan"``, ``"MediaFile"``); it is derived from the delegate when not given.
    """
    if not approx_count_enabled():
        return await delegate.count(where=where), False

    limit = threshold if threshold is not None else settings().scale_approx_count_threshold
    limit = max(1, limit)

    if not where:
        estimate = await _reltuples(table or _table_of(delegate))
        if estimate is not None and estimate > limit:
            return estimate, True
        # The planner says this table is small, so the exact count is cheap by the planner's own
        # reckoning. Fall through rather than return the estimate: for a small table an estimate is
        # both less accurate AND no faster.

    capped = await delegate.count(where=where, take=limit + 1)
    if capped > limit:
        return limit, True
    return capped, False


async def _reltuples(table: str | None) -> int | None:
    """The planner's row estimate for a table, or None when there isn't a usable one.

    ``to_regclass`` returns NULL rather than raising for a name that does not exist, which is what
    makes it safe to hand a table name to: the failure mode is "no estimate", not an error the
    caller has to catch. The name is a bound parameter, never interpolated.
    """
    if not table:
        return None
    from app.core.db import db  # imported here so this module stays free of connection side effects

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
    """The Prisma model name behind a delegate (``db.artisan`` -> ``Artisan``).

    Reaches for a private attribute, and tolerates its absence rather than asserting: this is a
    convenience so call sites need not repeat a name the delegate already knows, and if a future
    prisma-client-py renames the attribute the consequence must be "no free estimate", not a broken
    list endpoint. Pass ``table=`` explicitly anywhere that matters.
    """
    model = getattr(delegate, "_model", None)
    name = getattr(model, "__name__", None)
    return name if isinstance(name, str) else None


def count_payload(payload: dict[str, Any], is_approximate: bool) -> dict[str, Any]:
    """Mark a page payload whose ``total`` is a lower bound rather than a count.

    Additive and only when true, so the response shape is unchanged everywhere the total is exact —
    which, at the default threshold, is everywhere. A client that sees ``totalIsApproximate`` should
    render ``5,000+`` rather than ``5,000``; one that ignores the key shows a number that is correct
    to within "at least this many", which is what a page counter is used for anyway.
    """
    if not is_approximate:
        return payload
    return {**payload, "totalIsApproximate": True}
