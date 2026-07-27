"""In-process TTL + LRU cache, bounded by BOTH entry count and bytes held.

WHY BYTES AND NOT JUST ENTRIES. The production box is a t3.micro with 1 GiB of RAM shared between
uvicorn, the Prisma query engine and the OS. An entry-count bound alone says nothing about memory:
512 dashboard payloads are a few megabytes, 512 search responses over 925 media rows are not. So
this cache holds encoded BYTES and accounts for them, and a set that would breach the byte ceiling
evicts until it fits. The worst case is therefore a number an operator chose, not a number that
depends on which endpoints happened to be popular.

WHY IT STORES BYTES AT ALL, rather than the Python object. Three reasons, in order of importance:
an object handed back from a cache is shared mutable state, and one caller mutating a cached list
poisons it for everyone; a decoded object's true heap cost is unmeasurable from here, while
``len(payload)`` is exact; and the Redis backend can only store bytes, so storing bytes in both
means one set of semantics to reason about instead of two. The cost is a JSON round trip per hit,
which on the payloads here is a fraction of a millisecond against the 750ms cross-region round trip
a hit avoids.

CONCURRENCY. Every operation is synchronous — no ``await`` inside — so it is already atomic with
respect to the event loop. The lock exists for the other caller: uvicorn runs sync route handlers
in a worker thread, and a threadpool caller must not interleave with the eviction loop.
"""

import threading
import time
from collections import OrderedDict

# Rough per-entry bookkeeping cost (the OrderedDict node, the key string object, the tuple). Not a
# measurement of the interpreter's real overhead — it is a deliberate over-estimate so that a cache
# full of tiny entries still trips the byte ceiling before it trips anyone's memory alarm.
_ENTRY_OVERHEAD_BYTES = 200


class MemoryCache:
    """TTL + LRU byte store. Not shared between processes — see the README on multi-worker staleness."""

    def __init__(self, *, max_entries: int, max_bytes: int, max_entry_bytes: int) -> None:
        self._entries: OrderedDict[str, tuple[float, bytes]] = OrderedDict()
        self._counters: dict[str, int] = {}
        self._lock = threading.RLock()
        self._max_entries = max(1, max_entries)
        self._max_bytes = max(1, max_bytes)
        self._max_entry_bytes = max(1, max_entry_bytes)
        self._bytes = 0
        self.hits = 0
        self.misses = 0
        self.evictions = 0
        self.rejections = 0

    # --- entries -------------------------------------------------------------------------------

    def get(self, key: str) -> bytes | None:
        now = time.monotonic()
        with self._lock:
            found = self._entries.get(key)
            if found is None:
                self.misses += 1
                return None
            expires_at, payload = found
            if expires_at <= now:
                self._drop(key)
                self.misses += 1
                return None
            self._entries.move_to_end(key)  # LRU: a read is a use
            self.hits += 1
            return payload

    def set(self, key: str, payload: bytes, ttl_seconds: float) -> bool:
        """Store ``payload``; returns False when it was refused for being too large.

        Refusing an oversized entry rather than evicting to fit it is deliberate: one multi-megabyte
        search response would otherwise clear out several hundred small pages that are each far more
        likely to be read again. The big response still costs one query, exactly as it does today.
        """
        size = len(payload) + len(key.encode("utf-8")) + _ENTRY_OVERHEAD_BYTES
        if size > self._max_entry_bytes:
            with self._lock:
                self.rejections += 1
            return False
        with self._lock:
            if key in self._entries:
                self._drop(key)
            self._entries[key] = (time.monotonic() + max(0.001, ttl_seconds), payload)
            self._bytes += size
            self._evict_to_fit()
            return True

    def _drop(self, key: str) -> None:
        entry = self._entries.pop(key, None)
        if entry is not None:
            self._bytes -= len(entry[1]) + len(key.encode("utf-8")) + _ENTRY_OVERHEAD_BYTES

    def _evict_to_fit(self) -> None:
        while self._entries and (
            len(self._entries) > self._max_entries or self._bytes > self._max_bytes
        ):
            oldest, _ = next(iter(self._entries.items()))
            self._drop(oldest)
            self.evictions += 1

    def delete_prefix(self, prefix: str) -> int:
        """Drop every entry under a key prefix, returning how many went.

        Generations alone would make those entries unreachable, but unreachable is not the same as
        gone: they would keep occupying the byte budget until their TTL. With at most a few hundred
        entries the sweep is trivial, so reclaiming the memory immediately is free.
        """
        with self._lock:
            doomed = [key for key in self._entries if key.startswith(prefix)]
            for key in doomed:
                self._drop(key)
            return len(doomed)

    def purge_expired(self) -> int:
        now = time.monotonic()
        with self._lock:
            doomed = [key for key, (expires, _) in self._entries.items() if expires <= now]
            for key in doomed:
                self._drop(key)
            return len(doomed)

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()
            self._counters.clear()
            self._bytes = 0

    # --- generation counters -------------------------------------------------------------------
    # Bounded by the number of families in keys.FAMILIES (about a dozen), so they are never evicted
    # and never expire. A counter that vanished would silently resurrect keys minted before it did.

    def counter(self, name: str) -> int:
        with self._lock:
            return self._counters.get(name, 0)

    def increment(self, name: str) -> int:
        with self._lock:
            value = self._counters.get(name, 0) + 1
            self._counters[name] = value
            return value

    # --- introspection ---------------------------------------------------------------------------

    def stats(self) -> dict[str, object]:
        with self._lock:
            lookups = self.hits + self.misses
            return {
                "backend": "memory",
                "entries": len(self._entries),
                "bytes": self._bytes,
                "maxEntries": self._max_entries,
                "maxBytes": self._max_bytes,
                "hits": self.hits,
                "misses": self.misses,
                "hitRate": round(self.hits / lookups, 3) if lookups else None,
                "evictions": self.evictions,
                "rejectedOversize": self.rejections,
                "generations": dict(self._counters),
            }
