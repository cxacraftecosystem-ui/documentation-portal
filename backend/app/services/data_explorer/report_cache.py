from __future__ import annotations

import asyncio
import hashlib
import json
import pickle
import time
import zlib
from collections import OrderedDict
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import Any

QUEUE_SLOTS = 1
QUEUE_DEPTH = 8
QUEUE_WAIT_SECONDS = 90.0
RETRY_AFTER_SECONDS = 15
CACHE_TTL_SECONDS = 15 * 60
CACHE_BYTES = 96 * 1024 * 1024
CACHE_ENTRIES = 24

REPORT_TABLES = (
    "Artisan",
    "Craft",
    "CraftCategory",
    "CraftSubCategory",
    "Location",
    "Workshop",
    "WorkshopArtisan",
    "WorkshopCraft",
    "ProductDocumentation",
    "ToolDocumentation",
    "ToolArtisan",
    "ToolCraft",
    "Process",
    "ProcessStep",
    "MediaFile",
    "TranscriptInsight",
    "TranscriptVersion",
    "TranscriptTurn",
    "TranscriptTurnEdit",
    "TranscriptSpeaker",
    "TranscriptSpend",
    "QuestionnaireInterview",
    "QuestionnaireInterviewArtisan",
    "QuestionnaireResponse",
    "QuestionnaireRecording",
    "QuestionnaireAnswer",
    "Consent",
    "DataAccessGrant",
    "DataAccessScopeItem",
    "DesignWorkshop",
    "DesignWorkshopViewer",
    "DwStageEntry",
    "DwCustomSection",
    "DwCustomField",
    "DwWorkshopConsentDecision",
    "ReviewLog",
)

_VERSION_SQL = (
    "SELECT coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0)::bigint AS writes "
    "FROM pg_stat_user_tables WHERE relname = ANY($1::text[])"
)
_QUEUE_SEQ_SQL = 'SELECT last_value::bigint AS seq FROM "SearchIndexQueue_id_seq"'

REPORT_BUSY = "Reports are busy. Yours starts as soon as one finishes."


class ReportBusy(Exception):
    def __init__(self, retry_after: int = RETRY_AFTER_SECONDS) -> None:
        super().__init__(REPORT_BUSY)
        self.retry_after = retry_after


class ReportQueue:
    def __init__(
        self,
        slots: int = QUEUE_SLOTS,
        depth: int = QUEUE_DEPTH,
        wait: float = QUEUE_WAIT_SECONDS,
    ) -> None:
        self.slots = slots
        self.depth = depth
        self.wait = wait
        self._semaphore = asyncio.Semaphore(slots)
        self.waiting = 0
        self.running = 0

    def locked(self) -> bool:
        return self.running >= self.slots

    @asynccontextmanager
    async def slot(self) -> AsyncIterator[None]:
        if self.locked() and self.waiting >= self.depth:
            raise ReportBusy()
        self.waiting += 1
        try:
            await asyncio.wait_for(self._semaphore.acquire(), self.wait)
        except TimeoutError:
            raise ReportBusy() from None
        finally:
            self.waiting -= 1
        self.running += 1
        try:
            yield
        finally:
            self.running -= 1
            self._semaphore.release()


@dataclass
class _Entry:
    at: float
    blob: bytes
    files: dict[str, bytes] = field(default_factory=dict)

    @property
    def size(self) -> int:
        return len(self.blob) + sum(len(b) for b in self.files.values())


class ReportCache:
    def __init__(
        self,
        ttl: float = CACHE_TTL_SECONDS,
        max_bytes: int = CACHE_BYTES,
        max_entries: int = CACHE_ENTRIES,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.ttl = ttl
        self.max_bytes = max_bytes
        self.max_entries = max_entries
        self.clock = clock
        self._entries: OrderedDict[str, _Entry] = OrderedDict()
        self._building: dict[str, asyncio.Future[Any]] = {}
        self.builds = 0
        self.hits = 0

    @staticmethod
    def key(*parts: Any) -> str:
        text = json.dumps(parts, sort_keys=True, default=str, separators=(",", ":"))
        return hashlib.sha256(text.encode("utf-8")).hexdigest()

    def clear(self) -> None:
        self._entries.clear()
        self._building.clear()

    def __len__(self) -> int:
        return len(self._entries)

    @property
    def bytes(self) -> int:
        return sum(entry.size for entry in self._entries.values())

    def _live(self, key: str) -> _Entry | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        if self.clock() - entry.at > self.ttl:
            del self._entries[key]
            return None
        self._entries.move_to_end(key)
        return entry

    def get(self, key: str) -> Any | None:
        entry = self._live(key)
        if entry is None:
            return None
        self.hits += 1
        return pickle.loads(zlib.decompress(entry.blob))

    def put(self, key: str, value: Any) -> None:
        blob = zlib.compress(pickle.dumps(value, protocol=pickle.HIGHEST_PROTOCOL), 1)
        self._entries.pop(key, None)
        if len(blob) > self.max_bytes:
            return
        self._entries[key] = _Entry(at=self.clock(), blob=blob)
        self._shrink()

    def file(self, key: str, name: str) -> bytes | None:
        entry = self._live(key)
        return entry.files.get(name) if entry else None

    def put_file(self, key: str, name: str, payload: bytes) -> None:
        entry = self._entries.get(key)
        if entry is None or entry.size + len(payload) > self.max_bytes:
            return
        entry.files[name] = payload
        self._entries.move_to_end(key)
        self._shrink(keep=key)

    def _shrink(self, keep: str | None = None) -> None:
        while self._entries and (
            len(self._entries) > self.max_entries or self.bytes > self.max_bytes
        ):
            oldest = next(iter(self._entries))
            if oldest == keep and len(self._entries) == 1:
                break
            if oldest == keep:
                self._entries.move_to_end(keep)
                oldest = next(iter(self._entries))
            del self._entries[oldest]

    async def once(self, key: str, build: Callable[[], Awaitable[Any]]) -> Any:
        running = self._building.get(key)
        if running is not None:
            return await asyncio.shield(running)
        loop = asyncio.get_running_loop()
        future: asyncio.Future[Any] = loop.create_future()
        self._building[key] = future
        try:
            value = await build()
        except BaseException as exc:
            if not future.done():
                future.set_exception(exc)
                future.exception()
            raise
        else:
            if not future.done():
                future.set_result(value)
            return value
        finally:
            if self._building.get(key) is future:
                del self._building[key]


async def data_version(database: Any) -> str:
    writes = 0
    seq = 0
    try:
        rows = await database.query_raw(_VERSION_SQL, list(REPORT_TABLES))
        writes = int((rows[0] if rows else {}).get("writes") or 0)
    except Exception:  # noqa: BLE001
        return f"t{int(time.time() // 60)}"
    try:
        rows = await database.query_raw(_QUEUE_SEQ_SQL)
        seq = int((rows[0] if rows else {}).get("seq") or 0)
    except Exception:  # noqa: BLE001
        seq = 0
    return f"{seq}:{writes}"


QUEUE = ReportQueue()
CACHE = ReportCache()
