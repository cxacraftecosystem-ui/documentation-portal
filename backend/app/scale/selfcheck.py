"""``python -m app.scale.selfcheck`` — prove what this package is doing in the current environment.

WHY A SCRIPT AND NOT ONLY TESTS. A test suite proves the code is right. This proves the DEPLOYMENT is
what somebody thinks it is, which is a different question and the one that actually goes wrong: a
variable set in the wrong file, a flag on in staging and off in production, a Redis that answered at
boot and has not since. Run it on the box, read the lines, and there is no ambiguity about which
paths are live.

It touches NO database and opens NO socket unless the Redis backend is configured — which is also
what makes it safe to run against a checkout whose ``.env`` points at production.

Run it twice to see the whole story:

    python -m app.scale.selfcheck                          # a fresh clone: everything off
    SCALE_CACHE_ENABLED=true python -m app.scale.selfcheck # the memory cache doing its job
"""

import asyncio
import sys

from app.scale import cache, flags, keyset, rate_limit, replica

_PASSED = 0
_FAILED = 0


def _check(label: str, condition: bool, detail: str = "") -> None:
    global _PASSED, _FAILED
    if condition:
        _PASSED += 1
        print(f"  ok    {label}" + (f"  ({detail})" if detail else ""))
    else:
        _FAILED += 1
        print(f"  FAIL  {label}" + (f"  ({detail})" if detail else ""))


async def _cache_checks() -> None:
    enabled = flags.cache_enabled()
    # Plain ASCII in the output: this is meant to be read over SSH on a box whose console encoding
    # is whatever it is, and a mojibake dash in a verification tool undermines the verification.
    print(f"\ncache  [{'ON - ' + flags.cache_backend_name() if enabled else 'off'}]")
    await cache.reset_cache()

    calls = {"n": 0}

    async def loader() -> dict[str, object]:
        calls["n"] += 1
        return {"items": [{"id": "a1", "name": "Meera"}], "total": 1}

    params = {"page": 1, "pageSize": 20, "search": None}
    first = await cache.cached_response(
        "artisan.list", audience="u:selfcheck", params=params, loader=loader
    )
    second = await cache.cached_response(
        "artisan.list", audience="u:selfcheck", params=params, loader=loader
    )
    _check("the response is unchanged by the cache layer", first == second == await loader())
    calls["n"] -= 1  # the assertion above called the loader itself; do not count it

    if enabled:
        _check("a repeated read is served from the cache", calls["n"] == 1, f"loader ran {calls['n']}x")
    else:
        _check("every read runs the query", calls["n"] == 2, f"loader ran {calls['n']}x")

    # A different viewer must never share an entry: the audience is part of the key precisely so
    # that one researcher's filtered rows cannot be handed to another.
    before = calls["n"]
    await cache.cached_response(
        "artisan.list", audience="u:someone-else", params=params, loader=loader
    )
    _check("a different viewer gets their own entry", calls["n"] == before + 1)

    # Invalidation: a write to an artisan must retire the artisan list AND the cross-type views.
    before = calls["n"]
    await cache.invalidate_record("artisan")
    await cache.cached_response(
        "artisan.list", audience="u:selfcheck", params=params, loader=loader
    )
    _check(
        "a write invalidates the cached list",
        calls["n"] == before + 1,
        "generation bumped" if enabled else "nothing was cached to invalidate",
    )

    # Single-flight: N concurrent misses on one key must produce ONE query, not N.
    slow_calls = {"n": 0}

    async def slow_loader() -> dict[str, object]:
        slow_calls["n"] += 1
        await asyncio.sleep(0.05)
        return {"total": 10_567}

    await asyncio.gather(
        *(
            cache.cached_response(
                "dashboard.stats", audience="u:selfcheck", params={}, loader=slow_loader
            )
            for _ in range(8)
        )
    )
    if enabled:
        _check(
            "8 concurrent misses collapse to one query",
            slow_calls["n"] == 1,
            f"query ran {slow_calls['n']}x",
        )
    else:
        _check(
            "no stampede guard when the cache is off",
            slow_calls["n"] == 8,
            f"query ran {slow_calls['n']}x",
        )

    stats = await cache.cache_stats()
    print(f"  stats {stats}")
    await cache.reset_cache()


def _keyset_checks() -> None:
    on = flags.keyset_enabled()
    print(f"\nkeyset pagination  [{'ON' if on else 'off'}]")
    token = keyset.encode_cursor(sort_field="createdAt", value="2026-07-26T10:00:00", record_id="c1")
    decoded = keyset.decode_cursor(token, sort_field="createdAt")
    if on:
        _check("a cursor round-trips", decoded is not None and decoded.record_id == "c1")
        _check(
            "a tampered cursor is refused",
            keyset.decode_cursor(token[:-1] + ("0" if token[-1] != "0" else "1"),
                                 sort_field="createdAt") is None,
        )
        _check(
            "a cursor for another ordering is refused",
            keyset.decode_cursor(token, sort_field="name") is None,
        )
        _check("the filter is a tuple comparison", "OR" in keyset.after_where(decoded))
    else:
        _check("cursors are ignored, routes page by offset", decoded is None)


def _rate_limit_checks() -> None:
    on = flags.rate_limit_enabled()
    print(f"\nrate limit  [{'ON' if on else 'off'}]")

    class _FakeApp:
        def __init__(self) -> None:
            self.installed: list[object] = []

        def add_middleware(self, cls: object, **_: object) -> None:
            self.installed.append(cls)

    app = _FakeApp()
    installed = rate_limit.install_rate_limit(app)
    _check(
        "middleware is installed only when enabled",
        installed == on and len(app.installed) == (1 if on else 0),
        f"{len(app.installed)} middleware added",
    )

    # The bucket itself, exercised directly so the arithmetic is verified whether or not the
    # middleware is installed in this environment.
    buckets = rate_limit._TokenBuckets(capacity=3, refill_per_second=3 / 60)
    verdicts = [buckets.take("t:selfcheck")[0] for _ in range(5)]
    _check("a burst is allowed up to the limit, then refused", verdicts == [True, True, True, False, False])


def _replica_checks() -> None:
    print(f"\nread replica  [{'configured' if replica.replica_configured() else 'unset'}]")
    _check(
        "unset means every query uses the primary",
        replica.replica_configured() or flags.read_replica_url() is None,
    )


async def _main() -> int:
    print("app.scale self-check")
    print(f"\nflags  {flags.snapshot()}")
    flags.reset_log_once()
    await _cache_checks()
    _keyset_checks()
    _rate_limit_checks()
    _replica_checks()
    print(f"\n{_PASSED} passed, {_FAILED} failed")
    return 1 if _FAILED else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(_main()))
