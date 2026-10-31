"""The entry points a portal's ``services/ai.py`` calls: build the rungs, run them, and turn the
outcome into the result dictionary every caller and client already reads.

The dictionary shape is unchanged from before the package existed (``available``, ``status``,
``text``, ``formattedTranscript``, ``message``, ``retryAfter``, ``provider``), so no client changes.
What each portal SAYS about a failure stays the portal's own copy, passed in as :class:`Messages`.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.transcription.chain import ChainOutcome, Failure, Slot
from app.transcription.contract import PROVIDER_IDS, ApiKey, SttResult
from app.transcription.providers import ADAPTERS, DISPLAY_NAMES, KEY_NAMES


@dataclass(frozen=True)
class Messages:
    """A portal's sentences for each way a chain can end. ``name`` is the provider's display name."""

    key_rejected: Callable[[str], str]
    failed: Callable[[str, int | None], str]
    unreachable: Callable[[str], str]
    too_large: Callable[[str], str]
    busy: Callable[[str], str]
    rate_limited: Callable[[str | None], str]
    over_budget: Callable[[str], str]
    all_failed: Callable[[list[str]], str]
    unavailable: str
    #: Said when every provider's budget is used up and the job waits for the next window.
    budget_deferred: str


def build_slots(order: list[str], key_value: Callable[[str], str]) -> list[Slot]:
    """One rung per known provider in *order*; unknown ids are dropped, a missing key is a None key."""
    slots: list[Slot] = []
    for provider in order:
        if provider not in ADAPTERS or any(slot.provider == provider for slot in slots):
            continue
        name = KEY_NAMES[provider]
        value = key_value(name)
        slots.append(Slot(provider, ADAPTERS[provider], ApiKey(name, value) if value else None))
    return slots


def configured(
    order: list[str], key_value: Callable[[str], str], disabled: set[str] | frozenset[str]
) -> list[str]:
    """Providers that would actually be tried: known, switched on, and holding a key."""
    return [
        slot.provider
        for slot in build_slots(order, key_value)
        if slot.key and slot.provider not in disabled
    ]


def sanitise_order(stored: list[str] | None) -> list[str]:
    """The stored ranking against the known set; anything it omits is appended in default order,
    so a ranking saved before Muse existed demotes Muse instead of disabling it."""
    ordered: list[str] = []
    for provider in stored or []:
        if provider in PROVIDER_IDS and provider not in ordered:
            ordered.append(provider)
    ordered.extend(provider for provider in PROVIDER_IDS if provider not in ordered)
    return ordered


def result_dict(result: SttResult) -> dict[str, Any]:
    text = result.text
    out: dict[str, Any] = {
        "available": True,
        "status": result.status,
        "text": text,
        "formattedTranscript": f"Transcript\n\n{text}" if text else "",
        "raw": None,
        "provider": result.provider,
        "model": result.model,
        "speakers": result.speakers,
        "segments": [segment.as_json() for segment in result.segments],
    }
    if result.language:
        out["languageCode"] = result.language
    if result.chunks > 1:
        out["chunks"] = result.chunks
    if result.note:
        out["note"] = result.note
    if result.audio_ms:
        out["audioMs"] = result.audio_ms
    if result.words:
        out["words"] = result.words
    return out


def _failure_sentence(failure: Failure, messages: Messages) -> str | None:
    name = DISPLAY_NAMES.get(failure.provider, failure.provider)
    if failure.kind == "key":
        return messages.key_rejected(name)
    if failure.kind == "too_large":
        return messages.too_large(name)
    if failure.kind == "unreachable":
        return messages.unreachable(name)
    if failure.kind == "failed":
        return messages.failed(name, failure.status)
    if failure.kind in {"throttled", "cooling"}:
        return messages.busy(name)
    if failure.kind == "budget":
        return messages.over_budget(name)
    return None


def outcome_dict(outcome: ChainOutcome, messages: Messages) -> dict[str, Any]:
    if outcome.result is not None and outcome.status in {"COMPLETED", "EMPTY"}:
        return result_dict(outcome.result)
    if outcome.status == "UNAVAILABLE":
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "text": None,
            "formattedTranscript": None,
            "message": messages.unavailable,
        }
    first_busy = next(
        (f.provider for f in outcome.failures if f.kind in {"throttled", "cooling"}), None
    )
    if outcome.status in {"RATE_LIMITED", "BUDGET"}:
        return {
            "available": True,
            "status": "RATE_LIMITED",
            "text": None,
            "formattedTranscript": None,
            "retryAfter": outcome.retry_after,
            "provider": first_busy,
            "reason": "budget" if outcome.status == "BUDGET" else "throttled",
            "message": messages.budget_deferred
            if outcome.status == "BUDGET"
            else messages.rate_limited(
                DISPLAY_NAMES.get(first_busy, first_busy) if first_busy else None
            ),
        }
    sentences: list[str] = []
    for failure in outcome.failures:
        if failure.kind in {"throttled", "cooling"}:
            continue
        sentence = _failure_sentence(failure, messages)
        if sentence:
            sentences.append(sentence)
    if first_busy:
        sentences.append(messages.busy(DISPLAY_NAMES.get(first_busy, first_busy)))
    return {
        "available": True,
        "status": "FAILED",
        "text": None,
        "formattedTranscript": None,
        "message": messages.all_failed(sentences),
    }
