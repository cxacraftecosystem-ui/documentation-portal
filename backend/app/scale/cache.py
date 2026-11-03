import asyncio
import json
from typing import Any, Awaitable, Callable, Mapping

from pydantic_core import to_json

from app.scale import keys
from app.scale.backends import CacheBackend, MemoryBackend
from app.scale.flags import cache_backend_name, cache_enabled, log_once, redis_url, settings
from app.scale.singleflight import SingleFlight

_backend: CacheBackend | None = None
_backend_lock = asyncio.Lock()
_singleflight = SingleFlight()


async def _get_backend() -> CacheBackend:
    global _backend
    if _backend is not None:
        return _backend
    async with _backend_lock:
        if _backend is not None:
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



async def cached_response(
    namespace: str,
    *,
    audience: str,
    params: Mapping[str, Any],
    loader: Callable[[], Awaitable[Any]],
    ttl_seconds: float | None = None,
) -> Any:
    if not cache_enabled():
        return await loader()

    try:
        family = keys.key_family(namespace)
        digest = keys.fingerprint(params)
    except (KeyError, TypeError) as exc:
        log_once(
            f"cache.key.{type(exc).__name__}.{namespace}",
            "Cannot build a cache key for namespace %s (%s); serving it uncached. %s",
            namespace,
            type(exc).__name__,
            exc,
        )
        return await loader()

    backend = await _get_backend()
    generation = await backend.counter(keys.generation_key(family))
    key = keys.entry_key(namespace, generation, audience, digest)

    hit = await backend.get(key)
    if hit is not None:
        try:
            return json.loads(hit)
        except ValueError:
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
        except Exception as exc:  # noqa: BLE001
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

    return await _singleflight.run(
        key, _load_and_store, timeout=settings().scale_cache_singleflight_timeout_seconds
    )



async def invalidate_record(record_type: str) -> None:
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
    if not cache_enabled() or not families:
        return
    backend = await _get_backend()
    for family in families:
        await backend.increment(keys.generation_key(family))
        for namespace in keys.namespaces_in_family(family):
            await backend.drop_prefix(keys.namespace_prefix(namespace))


async def invalidate_all() -> None:
    if not cache_enabled():
        return
    await invalidate_families(*sorted(keys.FAMILIES))



async def cache_stats() -> dict[str, object]:
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
    if not cache_enabled() or cache_backend_name() != "redis":
        return None
    backend = await _get_backend()
    return await backend.window_hit(key, window_seconds)


async def reset_cache() -> None:
    global _backend
    async with _backend_lock:
        if _backend is not None:
            await _backend.close()
        _backend = None
