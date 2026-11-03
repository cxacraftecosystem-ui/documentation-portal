from typing import Protocol

from app.scale.memory_cache import MemoryCache

_PURGE_EVERY_N_WRITES = 256


class CacheBackend(Protocol):
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
    name = "memory"

    def __init__(self, *, max_entries: int, max_bytes: int, max_entry_bytes: int) -> None:
        self._cache = MemoryCache(
            max_entries=max_entries,
            max_bytes=max_bytes,
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
        return None

    async def stats(self) -> dict[str, object]:
        return self._cache.stats()

    async def close(self) -> None:
        self._cache.clear()
