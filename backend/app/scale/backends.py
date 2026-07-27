"""The one cache interface, and the backend that needs no service to run.

WHY THE INTERFACE IS ASYNC WHEN THE DEFAULT BACKEND IS NOT. ``MemoryCache`` is synchronous and
could not be otherwise — it is a dict behind a lock. Redis cannot be anything but async on an
event loop. If the interface followed the backend, every call site would need two shapes and the
choice of backend would leak into every route that ever adopts this. So the interface is async and
the memory backend simply never awaits anything: an ``async def`` that returns without suspending
costs a coroutine object and no context switch, which is the cheaper of the two mistakes available.

WHAT A BACKEND MUST GUARANTEE, since two of them have to be interchangeable:

* ``get`` returns exactly the bytes ``set`` stored, or None. Never raises. A backend that cannot
  answer returns None — a miss is always a correct answer, because the caller's fallback is to run
  the query it would have run anyway.
* ``set`` never raises and may silently decline (too large, service down). Storing is best-effort;
  reading is not, which is why the asymmetry is written down here rather than discovered later.
* ``counter``/``increment`` implement the generation scheme in ``keys.py``. ``increment`` MUST be
  atomic across every process sharing the backend, or two concurrent writers can produce the same
  generation and leave one of them serving keys that were supposed to be dead.
* ``window_hit`` returns None to mean "I have no shared counter for this" — the signal the rate
  limiter uses to fall back to its in-process bucket, rather than a special case per backend.
"""

from typing import Protocol

from app.scale.memory_cache import MemoryCache

# Expired entries are only reclaimed when something touches them or LRU pressure evicts them, so a
# cache that goes quiet after a burst can sit holding bytes nobody will ever read. Sweeping every
# Nth write costs one pass over a few hundred keys and needs no background task — and a background
# task is exactly what this package promises not to start.
_PURGE_EVERY_N_WRITES = 256


class CacheBackend(Protocol):
    """What the cache facade may ask of a store. See the module docstring for the guarantees."""

    name: str

    async def get(self, key: str) -> bytes | None: ...

    async def set(self, key: str, payload: bytes, ttl_seconds: float) -> None: ...

    async def counter(self, name: str) -> int: ...

    async def increment(self, name: str) -> int: ...

    async def drop_prefix(self, prefix: str) -> int: ...

    async def window_hit(self, key: str, window_seconds: float) -> int | None: ...

    async def stats(self) -> dict[str, object]: ...

    async def close(self) -> None: ...


class MemoryBackend:
    """The default backend: ``MemoryCache`` behind the async interface.

    Its one honest limitation is stated here rather than buried in the README: entries and
    generation counters are PRIVATE TO THIS PROCESS. Two uvicorn workers hold two caches and two
    sets of counters, so a write served by worker A does not invalidate worker B's copy — B serves
    the old answer until its TTL expires. Production runs one web worker (the queue is a separate
    service), so today that window does not exist for API writes; it does exist for rows the queue
    service writes, which is why the media TTLs in ``keys.py`` are the shortest ones there.
    """

    name = "memory"

    def __init__(self, *, max_entries: int, max_bytes: int, max_entry_bytes: int) -> None:
        self._cache = MemoryCache(
            max_entries=max_entries,
            max_bytes=max_bytes,
            # An entry ceiling above the total ceiling would let one response evict the whole cache
            # and still not fit, so the two are reconciled here instead of trusting the operator to.
            max_entry_bytes=min(max_entry_bytes, max_bytes),
        )
        self._writes = 0

    async def get(self, key: str) -> bytes | None:
        return self._cache.get(key)

    async def set(self, key: str, payload: bytes, ttl_seconds: float) -> None:
        self._cache.set(key, payload, ttl_seconds)
        self._writes += 1
        if self._writes % _PURGE_EVERY_N_WRITES == 0:
            self._cache.purge_expired()

    async def counter(self, name: str) -> int:
        return self._cache.counter(name)

    async def increment(self, name: str) -> int:
        return self._cache.increment(name)

    async def drop_prefix(self, prefix: str) -> int:
        return self._cache.delete_prefix(prefix)

    async def window_hit(self, key: str, window_seconds: float) -> int | None:
        # No shared counter to offer; the rate limiter's own bucket is both faster and exactly as
        # correct for a single process.
        return None

    async def stats(self) -> dict[str, object]:
        return self._cache.stats()

    async def close(self) -> None:
        self._cache.clear()
