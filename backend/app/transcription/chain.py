"""Which provider gets the recording, and what a failure means.

The walk, in the admin's order:

1. A provider that is switched off, has no key, or that this job has given up on is passed over.
2. A provider (per key) still cooling down after a throttle is passed over, and the NEXT one is
   tried. Cooldowns are per provider and per key, persisted by the portal, so a 429 from ElevenLabs
   no longer pauses Deepgram and a restart no longer forgets it.
3. A provider whose remaining budget is smaller than this recording's estimated minutes is passed
   over. A job is never failed for budget.
4. A provider that needs parts (Muse always, Whisper over 24 MB) gets the normalised parts; parts
   already transcribed for this job are reused, so a retry sends only the parts that failed.

How it ends when nothing answered with text:

* EMPTY when any provider answered with silence.
* RATE_LIMITED when every failure was a throttle or a cooldown (no attempt is used up).
* BUDGET when every failure was a budget (or a budget and a throttle): deferred to the next window.
* UNAVAILABLE when no provider could be tried at all.
* FAILED otherwise, so a recording that is truly broken still runs out of attempts.
"""

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
#: Up to this fraction is ADDED to every cooldown, so replicas and jobs do not retry in lockstep.
JITTER_FRACTION = 0.25
#: After this many throttles on one job, the job stops asking that provider and moves on.
THROTTLES_PER_JOB = 3
#: When nothing else is known, assume 64 kbit/s compressed audio for the minute estimate.
ASSUMED_BYTES_PER_SECOND = 8_000

DEFERRABLE = frozenset({"throttled", "cooling"})


def cooldown_seconds(
    consecutive: int, retry_after: float | None, rng: Callable[[], float] = random.random
) -> float:
    """A provider's own Retry-After when it sent one (never less than the base), otherwise 30 s
    doubling to 900 s; then 0-25 % jitter on top. Jitter only ever lengthens: never retry early."""
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


@dataclass
class Slot:
    """One rung: a provider and the key it would be called with (None = not configured)."""

    provider: str
    adapter: Any
    key: ApiKey | None


@dataclass
class ChainState:
    """What the portal's database says right now, read before the thread hop."""

    #: (provider, keyRef) -> cooldown deadline (UTC).
    cooldowns: dict[tuple[str, str], datetime] = field(default_factory=dict)
    #: provider -> minutes left in the tightest budget period; absent = no budget set.
    budget_minutes_left: dict[str, float] = field(default_factory=dict)
    #: (provider, part index) -> result already stored for this job.
    done_parts: dict[tuple[str, int], SttResult] = field(default_factory=dict)
    #: Providers switched off by the admin.
    disabled: set[str] = field(default_factory=set)
    #: Providers this job has given up on (throttled THROTTLES_PER_JOB times).
    skip: set[str] = field(default_factory=set)


@dataclass
class Failure:
    provider: str
    kind: str  # throttled | cooling | budget | key | too_large | failed | unreachable
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
    outcome: str  # COMPLETED | EMPTY | or the Failure kind
    cost_usd: float
    part_index: int | None = None


@dataclass
class ChainOutcome:
    status: str  # COMPLETED | EMPTY | RATE_LIMITED | BUDGET | FAILED | UNAVAILABLE
    result: SttResult | None = None
    failures: list[Failure] = field(default_factory=list)
    retry_after: float | None = None
    key_ref: str | None = None


class ChainHooks(Protocol):
    """Written to as things happen, so a crash halfway through loses nothing already paid for."""

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
    """Lazily prepared parts for this recording, shared by every rung that needs them."""

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
        if source is None:
            import tempfile

            with tempfile.NamedTemporaryFile(
                prefix="transcription_source_", delete=False
            ) as handle:
                handle.write(self.audio.content or b"")
            source = self._spill = handle.name
        self.cutter = self.factory()
        try:
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
            cost_usd(slot.adapter.price_per_hour_usd, audio_ms),
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
                    cost_usd(slot.adapter.price_per_hour_usd, audio_ms),
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
            left = state.budget_minutes_left.get(slot.provider)
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
