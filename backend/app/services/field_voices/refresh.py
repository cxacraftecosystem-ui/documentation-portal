from __future__ import annotations

import asyncio
import json
import logging
from collections import Counter
from collections.abc import Callable, Iterable
from contextlib import suppress
from dataclasses import dataclass, field
from typing import Any

from app.services.field_voices import contract, gone, images, ledger, transcripts
from app.services.field_voices.items import Outcome
from app.services.field_voices.sources import load_crafts
from app.services.field_voices.stages import StagePlacement

__all__ = ["Refresher", "rights_holder", "run_lane"]

logger = logging.getLogger(__name__)

START_SQL = (
    'INSERT INTO "FieldVoiceRefresh" ("id", "startedAt", "counts") VALUES (1, now(), \'{}\'::jsonb) '
    'ON CONFLICT ("id") DO UPDATE SET "startedAt" = now()'
)
FINISH_SQL = (
    'INSERT INTO "FieldVoiceRefresh" ("id", "startedAt", "finishedAt", "counts") '
    'VALUES (1, now(), now(), $1::jsonb) ON CONFLICT ("id") DO UPDATE SET "finishedAt" = now(), '
    '"counts" = EXCLUDED."counts"'
)


def rights_holder(settings: Any) -> str | None:
    from app.services import cross_portal

    return cross_portal.field_voices_rights_holder(settings)


def _chunks(values: list[Any], size: int) -> Iterable[list[Any]]:
    for start in range(0, len(values), size):
        yield values[start : start + size]


def _by_media(placements: Iterable[StagePlacement]) -> dict[str, list[StagePlacement]]:
    out: dict[str, list[StagePlacement]] = {}
    for placement in placements:
        out.setdefault(placement.media_id, []).append(placement)
    return out


@dataclass
class StreamCounts:
    listed: int = 0
    changed: int = 0
    gone: int = 0
    withheld: Counter[str] = field(default_factory=Counter)

    def add(self, outcomes: list[Outcome]) -> None:
        for outcome in outcomes:
            if outcome.live:
                self.listed += 1
            elif outcome.withheld:
                self.withheld[outcome.withheld] += 1

    def as_dict(self) -> dict[str, Any]:
        return {
            "listed": self.listed,
            "changed": self.changed,
            "gone": self.gone,
            "withheld": dict(sorted(self.withheld.items())),
        }


def _force_unlisted(outcomes: list[Outcome], candidates: set[str]) -> list[Outcome]:
    out: list[Outcome] = []
    for outcome in outcomes:
        if outcome.live and outcome.item_id not in candidates:
            out.append(Outcome(item_id=outcome.item_id, reason=contract.UNLISTED, withheld="notCandidate"))
        else:
            out.append(outcome)
    return out


def _tombstones(outcomes: list[Outcome], live: set[str]) -> int:
    return sum(1 for o in outcomes if o.reason and not o.live and o.item_id in live)


@dataclass
class Refresher:
    client: Any
    adapter: Any
    settings: Any
    dry_run: bool = False
    undecodable: set[str] = field(default_factory=set)
    fetch: Callable[[str], bytes] | None = None
    store: Callable[[str, bytes, str], None] | None = None
    delete: Callable[[str], None] | None = None

    async def run(self) -> dict[str, Any]:
        if not self.dry_run:
            await self.client.execute_raw(START_SQL)
        crafts = await load_crafts(self.client)
        counts: dict[str, Any] = {"transcripts": (await self._transcripts(crafts)).as_dict()}
        holder = rights_holder(self.settings)
        if holder:
            image_counts, copier = await self._images(crafts, holder)
            counts["images"] = {
                **image_counts.as_dict(),
                "copiesMade": copier.stored,
                "copyFailures": copier.failed,
                "undecodable": copier.refused,
                "copiesDeferred": copier.skipped,
            }
        if not self.dry_run:
            swept = await gone.gone_pass(self.client, self.adapter, self._streams(holder))
            counts["gonePass"] = swept
            counts["copiesReleased"] = await self._release()
            await self.client.execute_raw(FINISH_SQL, json.dumps(counts))
        return counts

    async def _release(self) -> dict[str, int]:
        try:
            if self.delete is None:
                return await ledger.release_copies(self.client)
            return await ledger.release_copies(self.client, delete=self.delete)
        except ledger.Busy:
            logger.info("field voices refresh: the ledger is busy; queued copies are deleted at the next refresh")
            return {"deleted": 0, "failed": 0}

    def _streams(self, holder: str | None) -> tuple[str, ...]:
        return contract.STREAMS if holder else (contract.TRANSCRIPTS,)

    async def _transcripts(self, crafts: dict[str, dict[str, Any]]) -> StreamCounts:
        stream = contract.TRANSCRIPTS
        placements = _by_media(await self.adapter.stage_media(self.client))
        wanted = await transcripts.candidates(self.client, [p for ps in placements.values() for p in ps])
        live = set(await ledger.live_rows(self.client, stream))
        counts = StreamCounts()
        candidates = set(wanted)
        for chunk in _chunks(sorted(candidates | live), contract.WRITE_BATCH):
            since = await ledger.clock(self.client)
            outcomes = await transcripts.evaluate(
                self.client, self.adapter, chunk, crafts=crafts, placements=placements
            )
            outcomes = _force_unlisted(outcomes, candidates)
            counts.add(outcomes)
            counts.gone += _tombstones(outcomes, live)
            counts.changed += await ledger.write_outcomes(
                self.client, stream, outcomes, dry_run=self.dry_run, since=since
            )
        return counts

    async def _images(self, crafts: dict[str, dict[str, Any]], holder: str) -> tuple[StreamCounts, images.Copier]:
        stream = contract.IMAGES
        placements = _by_media(await self.adapter.stage_images(self.client))
        rows = await images.candidates(self.client)
        live_rows = await ledger.live_rows(self.client, stream)
        candidates = {str(r["id"]) for r in rows}
        grouped = images.groups(rows)
        grouped += [[i] for i in sorted(set(live_rows) - candidates)]
        copier = images.Copier(budget=0 if self.dry_run else contract.COPIES_PER_REFRESH,
                               undecodable=self.undecodable)
        if self.fetch is not None:
            copier.fetch = self.fetch
        if self.store is not None:
            copier.store = self.store
        counts = StreamCounts()
        batch: list[list[str]] = []
        size = 0
        for group in grouped:
            batch.append(group)
            size += len(group)
            if size >= contract.WRITE_BATCH:
                await self._image_batch(batch, crafts, placements, live_rows, copier, holder, counts, candidates)
                batch, size = [], 0
        if batch:
            await self._image_batch(batch, crafts, placements, live_rows, copier, holder, counts, candidates)
        return counts, copier

    async def _image_batch(
        self, batch: list[list[str]], crafts: dict[str, dict[str, Any]], placements: dict[str, list[StagePlacement]],
        live_rows: dict[str, dict[str, Any]], copier: images.Copier, holder: str, counts: StreamCounts,
        candidates: set[str],
    ) -> None:
        since = await ledger.clock(self.client)
        outcomes = await images.evaluate(
            self.client, self.adapter, batch, crafts=crafts, placements=placements, ledger_rows=live_rows,
            copier=copier, rights_holder=holder,
        )
        outcomes = _force_unlisted(outcomes, candidates)
        counts.add(outcomes)
        counts.gone += _tombstones(outcomes, set(live_rows))
        counts.changed += await ledger.write_outcomes(
            self.client, contract.IMAGES, outcomes, dry_run=self.dry_run, since=since
        )


def lane_active(settings: Any) -> bool:
    from app.services import cross_portal

    return cross_portal.field_voices_active(settings)


async def _wait(stop: asyncio.Event, seconds: float) -> None:
    with suppress(TimeoutError):
        await asyncio.wait_for(stop.wait(), timeout=seconds)


async def run_lane(stop: asyncio.Event, settings: Any, *, client: Any = None, adapter: Any = None) -> None:
    if client is None:
        from app.core.db import db as client
    if adapter is None:
        from app.services import field_voices_portal as adapter
    undecodable: set[str] = set()
    while not stop.is_set():
        if lane_active(settings):
            try:
                counts = await Refresher(client, adapter, settings, undecodable=undecodable).run()
                logger.info("field voices refresh: %s", json.dumps(counts, sort_keys=True))
            except ledger.Busy:
                logger.info("field voices refresh: the ledger is busy; it runs again later")
            except Exception:
                logger.exception("field voices refresh failed; it runs again later")
        await _wait(stop, contract.REFRESH_INTERVAL_SECONDS)
