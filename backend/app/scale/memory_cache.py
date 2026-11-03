import threading
import time
from collections import OrderedDict

_ENTRY_OVERHEAD_BYTES = 200


class MemoryCache:
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
            self._entries.move_to_end(key)
            self.hits += 1
            return payload

    def set(self, key: str, payload: bytes, ttl_seconds: float) -> bool:
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

    def counter(self, name: str) -> int:
        with self._lock:
            return self._counters.get(name, 0)

    def increment(self, name: str) -> int:
        with self._lock:
            value = self._counters.get(name, 0) + 1
            self._counters[name] = value
            return value

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
