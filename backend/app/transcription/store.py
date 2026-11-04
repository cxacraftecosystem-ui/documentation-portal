from __future__ import annotations

import asyncio
import logging
from datetime import datetime
from typing import Any, Protocol

from app.transcription.audio import PartPlan
from app.transcription.chain import ChainState, UsageEvent
from app.transcription.contract import SttResult

logger = logging.getLogger("app.transcription")

BRIDGE_TIMEOUT_SECONDS = 30


class TranscriptionStore(Protocol):
    async def load_state(self, *, job_id: str | None, providers: list[str]) -> ChainState: ...

    async def record_usage(
        self,
        event: UsageEvent,
        *,
        media_id: str | None,
        cache_hit: bool = False,
        job_id: str | None = None,
    ) -> None: ...

    async def set_cooldown(
        self, provider: str, key_ref: str, retry_after: float | None
    ) -> datetime | None: ...

    async def clear_cooldown(self, provider: str, key_ref: str) -> None: ...

    async def save_part(
        self, *, job_id: str, provider: str, plan: PartPlan, result: SttResult
    ) -> None: ...


class StoreHooks:
    def __init__(
        self,
        store: TranscriptionStore,
        loop: asyncio.AbstractEventLoop,
        *,
        media_id: str | None = None,
        job_id: str | None = None,
    ) -> None:
        self.store = store
        self.loop = loop
        self.media_id = media_id
        self.job_id = job_id

    def _wait(self, coroutine: Any) -> Any:
        try:
            return asyncio.run_coroutine_threadsafe(coroutine, self.loop).result(
                BRIDGE_TIMEOUT_SECONDS
            )
        except Exception as exc:  # noqa: BLE001 - bookkeeping must never fail a transcription
            logger.warning("transcription store write failed (%s)", type(exc).__name__)
            return None

    def usage(self, event: UsageEvent) -> None:
        self._wait(self.store.record_usage(event, media_id=self.media_id, job_id=self.job_id))

    def throttled(self, provider: str, key_ref: str, retry_after: float | None) -> datetime | None:
        return self._wait(self.store.set_cooldown(provider, key_ref, retry_after))

    def succeeded(self, provider: str, key_ref: str) -> None:
        self._wait(self.store.clear_cooldown(provider, key_ref))

    def part_done(self, provider: str, plan: PartPlan, result: SttResult) -> None:
        if self.job_id:
            self._wait(
                self.store.save_part(
                    job_id=self.job_id, provider=provider, plan=plan, result=result
                )
            )
