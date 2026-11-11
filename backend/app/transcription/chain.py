from __future__ import annotations

import logging
import random
import time
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from app.transcription import merge
from app.transcription.audio import PartCutter, PartPlan
from app.transcription.contract import (
    ApiKey,
    AudioInput,
    KeyRejected,
    OptionRejected,
    SttError,
    SttOptions,
    SttResult,
    Throttled,
    TooLarge,
    Unreachable,
)

logger = logging.getLogger("app.transcription")

RATE_LIMIT_BASE_SECONDS = 30
RATE_LIMIT_MAX_SECONDS = 900
JITTER_FRACTION = 0.25
THROTTLES_PER_JOB = 3
ASSUMED_BYTES_PER_SECOND = 8_000

DEFERRABLE = frozenset({"throttled", "cooling"})


def cooldown_seconds(
    consecutive: int, retry_after: float | None, rng: Callable[[], float] = random.random
) -> float:
    if retry_after and retry_after > 0:
        delay = max(float(retry_after), RATE_LIMIT_BASE_SECONDS)
    else:
        delay = min(
            RATE_LIMIT_BASE_SECONDS * (2 ** max(consecutive - 1, 0)), RATE_LIMIT_MAX_SECONDS
        )
    return delay * (1 + JITTER_FRACTION * rng())


def estimate_minutes(audio: AudioInput) -> float:
    if audio.duration_ms:
        return audio.duration_ms / 60_000
    return audio.size_bytes / ASSUMED_BYTES_PER_SECOND / 60


def cost_usd(price_per_hour: float, audio_ms: int | None) -> float:
    return round(price_per_hour * (audio_ms or 0) / 3_600_000, 6)


def hourly_rate(adapter: Any, *, keyterms: bool) -> float:
    rate = float(getattr(adapter, "price_per_hour_usd", 0.0) or 0.0)
    if keyterms:
        rate += float(getattr(adapter, "keyterm_price_per_hour_usd", 0.0) or 0.0)
    return round(rate, 6)


def billed_rate(adapter: Any, opts: SttOptions, result: SttResult) -> float:
    return hourly_rate(adapter, keyterms=bool(opts.keyterms) and not result.degraded)


@dataclass
class Slot:
    provider: str
    adapter: Any
    key: ApiKey | None


@dataclass
class ChainState:
    cooldowns: dict[tuple[str, str], datetime] = field(default_factory=dict)
    budget_minutes_left: dict[str, float] = field(default_factory=dict)
    budget_usd_left: dict[str, float] = field(default_factory=dict)
    done_parts: dict[tuple[str, int], SttResult] = field(default_factory=dict)
    disabled: set[str] = field(default_factory=set)
    skip: set[str] = field(default_factory=set)


def budget_minutes(state: ChainState, slot: Slot, opts: SttOptions) -> float | None:
    left = state.budget_minutes_left.get(slot.provider)
    usd = state.budget_usd_left.get(slot.provider)
    rate = hourly_rate(slot.adapter, keyterms=bool(opts.keyterms))
    if usd is None or not rate:
        return left
    by_spend = max(0.0, usd) / rate * 60
    return by_spend if left is None else min(left, by_spend)


@dataclass
class Failure:
    provider: str
    kind: str
    status: int | None = None
    retry_after: float | None = None


@dataclass
class UsageEvent:
    provider: str
    model: str | None
    key_ref: str | None
    audio_ms: int | None
    latency_ms: int
    http_status: int | None
    outcome: str
    cost_usd: float
    part_index: int | None = None


@dataclass
class ChainOutcome:
    status: str
    result: SttResult | None = None
    failures: list[Failure] = field(default_factory=list)
    retry_after: float | None = None
    key_ref: str | None = None


class ChainHooks(Protocol):
    def usage(self, event: UsageEvent) -> None: ...

    def throttled(
        self, provider: str, key_ref: str, retry_after: float | None
    ) -> datetime | None: ...

    def succeeded(self, provider: str, key_ref: str) -> None: ...

    def part_done(self, provider: str, plan: PartPlan, result: SttResult) -> None: ...


class NullHooks:
    def usage(self, event: UsageEvent) -> None:
        return None

    def throttled(self, provider: str, key_ref: str, retry_after: float | None) -> datetime | None:
        return None

    def succeeded(self, provider: str, key_ref: str) -> None:
        return None

    def part_done(self, provider: str, plan: PartPlan, result: SttResult) -> None:
        return None


def _needs_parts(adapter: Any, audio: AudioInput) -> bool:
    if getattr(adapter, "needs_wav", False):
        return True
    if not getattr(adapter, "chunkable", False):
        return False
    if adapter.max_bytes is not None and audio.size_bytes > adapter.max_bytes:
        return True
    return (
        adapter.max_duration_ms is not None and (audio.duration_ms or 0) > adapter.max_duration_ms
    )


def _kind(exc: BaseException) -> str:
    if isinstance(exc, Throttled):
        return "throttled"
    if isinstance(exc, KeyRejected):
        return "key"
    if isinstance(exc, TooLarge):
        return "too_large"
    if isinstance(exc, Unreachable):
        return "unreachable"
    return "failed"


def _status(exc: BaseException) -> int | None:
    return getattr(exc, "status", None) or getattr(
        getattr(exc, "response", None), "status_code", None
    )


class _Parts:
    def __init__(self, audio: AudioInput, cutter_factory: Callable[[], PartCutter] | None) -> None:
        self.audio = audio
        self.factory = cutter_factory
        self.cutter: PartCutter | None = None
        self.plans: list[PartPlan] | None = None
        self.tried = False
        self._spill: str | None = None

    def get(self) -> list[PartPlan] | None:
        if self.tried:
            return self.plans
        self.tried = True
        if self.factory is None:
            return None
        source = self.audio.source_path
        try:
            if source is None:
                from app.transcription import workdir

                with workdir.named_file(prefix="transcription_source_") as handle:
                    self._spill = handle.name
                    handle.write(self.audio.content or b"")
                source = self._spill
            self.cutter = self.factory()
            self.plans = self.cutter.prepare(source)
        except Exception as exc:  # noqa: BLE001 - ffmpeg missing or unreadable audio: no parts
            logger.warning("transcription: could not prepare parts (%s)", type(exc).__name__)
            self.plans = None
        return self.plans

    def close(self) -> None:
        if self.cutter is not None:
            self.cutter.close()
        if self._spill:
            import os

            try:
                os.remove(self._spill)
            except OSError:
                pass


def _call_whole(slot: Slot, audio: AudioInput, opts: SttOptions, hooks: ChainHooks) -> SttResult:
    started = time.monotonic()
    try:
        result = slot.adapter.transcribe(audio, opts, slot.key)
    except Exception as exc:
        hooks.usage(
            UsageEvent(
                slot.provider,
                None,
                slot.key.ref if slot.key else None,
                None,
                int((time.monotonic() - started) * 1000),
                _status(exc),
                _kind(exc),
                0.0,
            )
        )
        raise
    audio_ms = result.audio_ms or audio.duration_ms
    hooks.usage(
        UsageEvent(
            slot.provider,
            result.model,
            slot.key.ref if slot.key else None,
            audio_ms,
            int((time.monotonic() - started) * 1000),
            200,
            result.status,
            cost_usd(billed_rate(slot.adapter, opts, result), audio_ms),
        )
    )
    return result


def _call_parts(
    slot: Slot,
    audio: AudioInput,
    opts: SttOptions,
    state: ChainState,
    hooks: ChainHooks,
    parts: _Parts,
) -> SttResult:
    plans = parts.get()
    if not plans or parts.cutter is None:
        raise TooLarge(slot.provider, None)
    done: list[tuple[PartPlan, SttResult]] = []
    base_session = opts.session_id
    for plan in plans:
        reused = state.done_parts.get((slot.provider, plan.index))
        if reused is not None:
            done.append((plan, reused))
            continue
        path = parts.cutter.cut(plan)
        if path is None:
            raise TooLarge(slot.provider, None)
        try:
            piece = AudioInput(None, path, plan.file_name, "audio/wav", plan.end_ms - plan.start_ms)
            part_opts = replace(
                opts, session_id=f"{base_session}-{plan.index}" if base_session else None
            )
            started = time.monotonic()
            try:
                result = slot.adapter.transcribe(piece, part_opts, slot.key)
            except Exception as exc:
                hooks.usage(
                    UsageEvent(
                        slot.provider,
                        None,
                        slot.key.ref if slot.key else None,
                        None,
                        int((time.monotonic() - started) * 1000),
                        _status(exc),
                        _kind(exc),
                        0.0,
                        plan.index,
                    )
                )
                raise
            audio_ms = result.audio_ms or piece.duration_ms
            hooks.usage(
                UsageEvent(
                    slot.provider,
                    result.model,
                    slot.key.ref if slot.key else None,
                    audio_ms,
                    int((time.monotonic() - started) * 1000),
                    200,
                    result.status,
                    cost_usd(billed_rate(slot.adapter, part_opts, result), audio_ms),
                    plan.index,
                )
            )
            hooks.part_done(slot.provider, plan, result)
            done.append((plan, result))
        finally:
            parts.cutter.discard(path)
    return merge.stitch(done)


def run_chain(
    audio: AudioInput,
    slots: list[Slot],
    opts: SttOptions,
    state: ChainState | None = None,
    hooks: ChainHooks | None = None,
    *,
    now: datetime | None = None,
    cutter_factory: Callable[[], PartCutter] | None = None,
) -> ChainOutcome:
    state = state or ChainState()
    hooks = hooks or NullHooks()
    now = now or datetime.now(UTC)
    failures: list[Failure] = []
    empty: tuple[SttResult, str | None] | None = None
    tried_any = False
    parts = _Parts(audio, cutter_factory)
    estimate = estimate_minutes(audio)
    try:
        for slot in slots:
            if slot.provider in state.disabled or slot.provider in state.skip or slot.key is None:
                continue
            until = state.cooldowns.get((slot.provider, slot.key.ref))
            if until is not None and until > now:
                failures.append(
                    Failure(slot.provider, "cooling", retry_after=(until - now).total_seconds())
                )
                continue
            left = budget_minutes(state, slot, opts)
            if left is not None and estimate > left:
                failures.append(Failure(slot.provider, "budget"))
                continue
            adapter = slot.adapter
            needs_parts = _needs_parts(adapter, audio)
            if (
                not needs_parts
                and adapter.max_bytes is not None
                and audio.size_bytes > adapter.max_bytes
            ):
                failures.append(Failure(slot.provider, "too_large"))
                continue
            tried_any = True
            try:
                if needs_parts:
                    result = _call_parts(slot, audio, opts, state, hooks, parts)
                else:
                    result = _call_whole(slot, audio, opts, hooks)
            except Throttled as exc:
                hooks.throttled(slot.provider, slot.key.ref, exc.retry_after)
                failures.append(Failure(slot.provider, "throttled", exc.status, exc.retry_after))
                logger.warning(
                    "%s transcription throttled (HTTP %s); trying next provider",
                    slot.provider,
                    exc.status,
                )
                continue
            except (KeyRejected, TooLarge, OptionRejected, Unreachable, SttError) as exc:
                failures.append(Failure(slot.provider, _kind(exc), _status(exc)))
                logger.warning(
                    "%s transcription failed (%s); trying next provider", slot.provider, _kind(exc)
                )
                continue
            except Exception as exc:  # noqa: BLE001 - an adapter bug must not take the chain down
                failures.append(Failure(slot.provider, "failed", _status(exc)))
                logger.warning(
                    "%s transcription raised %s; trying next provider",
                    slot.provider,
                    type(exc).__name__,
                )
                continue
            if result.status == "COMPLETED":
                hooks.succeeded(slot.provider, slot.key.ref)
                return ChainOutcome("COMPLETED", result, failures, key_ref=slot.key.ref)
            if empty is None:
                empty = (result, slot.key.ref)
    finally:
        parts.close()

    if empty is not None:
        return ChainOutcome("EMPTY", empty[0], failures, key_ref=empty[1])
    if not failures and not tried_any:
        return ChainOutcome("UNAVAILABLE", None, failures)
    kinds = {failure.kind for failure in failures}
    retry_candidates = [f.retry_after for f in failures if f.kind in DEFERRABLE and f.retry_after]
    retry_after = retry_candidates[0] if retry_candidates else None
    if kinds and kinds <= DEFERRABLE:
        return ChainOutcome("RATE_LIMITED", None, failures, retry_after)
    if kinds and kinds <= DEFERRABLE | {"budget"}:
        status = "RATE_LIMITED" if kinds & DEFERRABLE else "BUDGET"
        return ChainOutcome(status, None, failures, retry_after)
    return ChainOutcome("FAILED", None, failures, retry_after)


def cooldown_until(
    now: datetime,
    consecutive: int,
    retry_after: float | None,
    rng: Callable[[], float] = random.random,
) -> datetime:
    return now + timedelta(seconds=cooldown_seconds(consecutive, retry_after, rng))
