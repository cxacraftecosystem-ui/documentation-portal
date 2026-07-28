"""The cache facade: one function to read through it, one to invalidate it, and the invalidation
story written down in the place a reader of the code will actually find it.

WHAT IS CACHED, AND WHY IT IS BYTES OF JSON. A route hands over the payload it is about to return.
This module encodes that payload with ``pydantic_core.to_json`` — the very serializer FastAPI uses
for a response — stores the bytes, and on a hit decodes them back. Encoding with the same serializer
is not a stylistic preference, it is a correctness requirement discovered by comparing outputs:
``fastapi.encoders.jsonable_encoder`` renders ``Decimal("12.50")`` as the number ``12.5`` while
FastAPI's actual response pipeline renders it as the STRING ``"12.50"``. Every client in this
project types those columns as strings (a mismatch has emptied a dropdown twice), so a cache built
on the wrong encoder would have changed the wire format on hits and left the miss path correct —
the worst possible shape of bug, since it reproduces only when the cache is warm.

WHY BOTH PATHS DECODE. A miss returns ``json.loads`` of what it just encoded rather than the loader's
own objects, so a route's response is byte-identical whether it was served from cache or not. The
alternative saves one decode and buys an entire class of "only fails on a cold cache" reports.

THE INVALIDATION STORY, in full. It is three rules:

1. A key carries the GENERATION of its family (see ``keys.py``). Bumping the counter retires every
   key ever minted under the old one, atomically and in O(1).
2. A mutation calls ``invalidate_record(record_type)`` AFTER the write has committed and BEFORE the
   response is returned. Awaited, not scheduled: a client that saves a record and immediately reloads
   the list is the single most common sequence in this app, and a fire-and-forget invalidation loses
   that race often enough to look like the save did not work.
3. A create, an edit, a delete and a review approval are the same event to this layer — all four
   change which rows a list returns or what they contain, so all four call the same function with the
   same record type. There is no separate "approve" path to forget to wire up.

The read/write race is safe by construction, which is the reason for generations rather than a
delete: a slow reader that started at generation 7 and finishes after a writer moved the family to 8
writes its stale answer under a generation-7 key that nothing will ever ask for again. A cache that
invalidated by deleting keys would have that same reader re-create a deleted key with stale content
and serve it for a full TTL.
"""

import asyncio
import json
from typing import Any, Awaitable, Callable, Mapping

from pydantic_core import to_json

from app.scale import keys
from app.scale.backends import CacheBackend, MemoryBackend
from app.scale.flags import cache_backend_name, cache_enabled, log_once, redis_url, settings
from app.scale.singleflight import SingleFlight

# Built once, on the first cached call, and only when the flag is on. Module state rather than an
# app.state attribute because the invalidation half is called from services that have no request and
# no app object to hand, and a cache with two ways to reach it is a cache with two lifetimes.
_backend: CacheBackend | None = None
_backend_lock = asyncio.Lock()
_singleflight = SingleFlight()


async def _get_backend() -> CacheBackend:
    """The process's cache backend, constructed on first use.

    Always returns a backend once caching is on: a requested Redis that cannot be reached degrades
    to the in-process cache rather than to no cache, because the operator asked for caching and the
    memory backend is the one that cannot fail. What they lose is cross-process invalidation, which
    is logged, reported by ``cache_stats`` and — on a single-web-worker box — currently nothing.
    """
    global _backend
    if _backend is not None:
        return _backend
    async with _backend_lock:
        if _backend is not None:  # another coroutine built it while we waited
            return _backend
        current = settings()
        requested = cache_backend_name()
        backend: CacheBackend | None = None
        if requested == "redis":
            url = redis_url()
            if not url:
                log_once(
                    "cache.redis.url_missing",
                    "SCALE_CACHE_BACKEND=redis but SCALE_REDIS_URL is empty; using the in-process "
                    "cache instead.",
                )
            else:
                # Imported here, not at module scope: redis_backend is written to be import-safe
                # without the package, but keeping the import behind the flag check means the
                # disabled path never even reads the file.
                from app.scale.redis_backend import create_redis_backend

                backend = await create_redis_backend(
                    url, timeout_seconds=current.scale_redis_timeout_seconds
                )
        elif requested != "memory":
            log_once(
                "cache.backend.unknown",
                "SCALE_CACHE_BACKEND=%r is not a backend this build knows (memory, redis); using "
                "the in-process cache.",
                requested,
            )
        if backend is None:
            backend = MemoryBackend(
                max_entries=current.scale_cache_max_entries,
                max_bytes=current.scale_cache_max_bytes,
                max_entry_bytes=current.scale_cache_max_entry_bytes,
            )
        _backend = backend
        return backend


# --- Reading -----------------------------------------------------------------------------------


async def cached_response(
    namespace: str,
    *,
    audience: str,
    params: Mapping[str, Any],
    loader: Callable[[], Awaitable[Any]],
    ttl_seconds: float | None = None,
) -> Any:
    """Return the cached response for these parameters, or run ``loader`` and cache what it returns.

    ``audience`` is not optional and has no default on purpose. Every list in this API is masked per
    viewer (``records.public_encode`` unmasks an artisan's identity numbers only for that artisan's own
    recorder, or for professor-and-above), so an omitted audience would not merely lower the hit rate —
    it would hand one person's Aadhaar number to whoever asked next. ``keys.audience_for(user)`` is the
    answer for anything user-scoped; ``keys.role_audience`` only for responses that provably vary by
    rank alone. Note that per-RANK is not enough for anything carrying an artisan: the unmask test is
    per-individual, so two researchers of equal rank must not share an entry.

    With the flag off this is one boolean read and a direct await of ``loader``, which is exactly
    what the route did before it adopted this.
    """
    if not cache_enabled():
        return await loader()

    try:
        family = keys.key_family(namespace)
        digest = keys.fingerprint(params)
    except (KeyError, TypeError) as exc:
        # Both are programming errors — an unregistered namespace, or a parameter type the key
        # scheme refuses to fingerprint — and both are reported loudly. They do NOT fail the
        # request: the correct behaviour for "I cannot build a safe key" is to not use the cache,
        # and a 500 on a read path would turn a caching mistake into an outage.
        log_once(
            f"cache.key.{type(exc).__name__}.{namespace}",
            "Cannot build a cache key for namespace %s (%s); serving it uncached. %s",
            namespace,
            type(exc).__name__,
            exc,
        )
        return await loader()

    backend = await _get_backend()
    # The generation has to be read before the key can be built, so a Redis hit is two round trips
    # rather than one. On localhost that is a fraction of a millisecond against the ~250ms
    # cross-region query it replaces; over a WAN link to a managed Redis it is worth measuring
    # before assuming the cache is a win. The memory backend has no round trip at all.
    generation = await backend.counter(keys.generation_key(family))
    key = keys.entry_key(namespace, generation, audience, digest)

    hit = await backend.get(key)
    if hit is not None:
        try:
            return json.loads(hit)
        except ValueError:
            # A stored entry that will not parse means the encoding changed under us (a
            # KEY_FORMAT_VERSION that should have been bumped, or a shared Redis holding another
            # build's bytes). Treat it as a miss and move on.
            log_once(
                "cache.decode",
                "A cached entry in namespace %s could not be decoded; serving it fresh. If this "
                "persists, bump KEY_FORMAT_VERSION in app/scale/keys.py.",
                namespace,
            )

    if ttl_seconds is None:
        ttl_seconds = keys.ttl_for(namespace, settings().scale_cache_ttl_seconds)

    async def _load_and_store() -> Any:
        value = await loader()
        try:
            encoded = to_json(value)
        except Exception as exc:  # noqa: BLE001 - an unencodable response is still a valid response
            log_once(
                f"cache.encode.{namespace}",
                "Could not encode the response for namespace %s (%s); it will not be cached. %s",
                namespace,
                type(exc).__name__,
                exc,
            )
            return value
        await backend.set(key, encoded, ttl_seconds)
        return json.loads(encoded)

    # Single-flight is keyed on the full cache key, so two callers only share work when they would
    # have shared the answer. The moment the key differs — a different user, a different filter —
    # they are independent and neither waits on the other.
    return await _singleflight.run(
        key, _load_and_store, timeout=settings().scale_cache_singleflight_timeout_seconds
    )


# --- Invalidation ---------------------------------------------------------------------------------


async def invalidate_record(record_type: str) -> None:
    """Retire every cached view that a write to ``record_type`` could have changed.

    Call it once, after the write commits, for a create, an update, a delete, a status change and a
    review approval alike — they are indistinguishable from a cache's point of view. The record type
    strings are the ones the routes already use ("artisan", "media", "questionnaire", …); the full
    map of type to families is ``keys.RECORD_FAMILIES``.
    """
    if not cache_enabled():
        return
    families = keys.families_for_record(record_type)
    if not families:
        log_once(
            f"cache.invalidate.unknown.{record_type}",
            "invalidate_record(%r) matched no family, so nothing was invalidated. Add the type to "
            "RECORD_FAMILIES in app/scale/keys.py.",
            record_type,
        )
        return
    await invalidate_families(*families)


async def invalidate_families(*families: str) -> None:
    """Bump the generation of each family, and reclaim the bytes its entries were holding.

    The bump is what makes the old entries unreachable; the prefix sweep is only housekeeping for
    the memory backend, which would otherwise carry dead entries against its byte ceiling until
    their TTL. The Redis backend declines the sweep (see ``RedisBackend.drop_prefix``) because
    Redis's own eviction already does that job without an O(keyspace) scan.
    """
    if not cache_enabled() or not families:
        return
    backend = await _get_backend()
    for family in families:
        await backend.increment(keys.generation_key(family))
        for namespace in keys.namespaces_in_family(family):
            await backend.drop_prefix(keys.namespace_prefix(namespace))


async def invalidate_all() -> None:
    """Retire everything. For an admin action whose blast radius is genuinely the whole dataset —
    a bulk import, a restore, a settings change that alters what every list contains."""
    if not cache_enabled():
        return
    await invalidate_families(*sorted(keys.FAMILIES))


# --- Introspection ----------------------------------------------------------------------------------


async def cache_stats() -> dict[str, object]:
    """Counters for an admin/debug endpoint. Safe to expose: no keys, no payloads, no URLs.

    Reports the backend that is actually in use, which is the point — an operator who asked for
    Redis and silently got the memory fallback needs somewhere to see that other than the log they
    have already scrolled past.
    """
    if not cache_enabled():
        return {"enabled": False}
    if _backend is None:
        return {"enabled": True, "backend": cache_backend_name(), "built": False}
    stats = await _backend.stats()
    return {
        "enabled": True,
        "built": True,
        "requestedBackend": cache_backend_name(),
        "inflightLoads": _singleflight.inflight(),
        **stats,
    }


async def shared_window_hit(key: str, window_seconds: float) -> int | None:
    """Count one request in a window shared by every process, or None if there is no shared store.

    This is the rate limiter's only door into the cache backend, and it is deliberately narrow: the
    limiter asks "can you count this for everyone?" and gets an honest no whenever the answer would
    be a per-process count — the cache off, the backend memory, or a Redis that degraded to memory.
    None means "use your own bucket", so the limiter needs no knowledge of which backend is running.
    """
    if not cache_enabled() or cache_backend_name() != "redis":
        return None
    backend = await _get_backend()
    return await backend.window_hit(key, window_seconds)


async def reset_cache() -> None:
    """Drop the backend entirely. For tests, the self-check, and application shutdown."""
    global _backend
    async with _backend_lock:
        if _backend is not None:
            await _backend.close()
        _backend = None
