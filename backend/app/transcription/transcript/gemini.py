from __future__ import annotations

import json
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import requests

from app.transcription.contract import ApiKey

logger = logging.getLogger("app.transcription")

MODEL = "gemini-3.8-flash"
FALLBACK_MODEL = "gemini-3.7-flash"
MODELS: tuple[str, ...] = (MODEL, FALLBACK_MODEL)
URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}"
PRICES: dict[str, tuple[float, float]] = {
    "gemini-3.8-flash": (0.75, 3.75),
    "gemini-3.7-flash": (0.75, 3.75),
}
DISPLAY_NAMES = {"gemini-3.8-flash": "Gemini 3.8 Flash", "gemini-3.7-flash": "Gemini 3.7 Flash"}
TIMEOUT_SECONDS = 180

THROTTLED = "throttled"
BILLING = "billing"
UNREACHABLE = "unreachable"
FAILED = "failed"
UNREADABLE = "unreadable"
NO_MODEL = "no_model"
REJECTED = "rejected"
KEY = "key"
DECLINED = "declined"
UNAVAILABLE = "unavailable"

_RETRYABLE = {THROTTLED, BILLING, UNREACHABLE, FAILED, UNREADABLE}
HOLD_SECONDS = {THROTTLED: 180, UNREACHABLE: 180, BILLING: 900}

MESSAGES = {
    THROTTLED: "Gemini is busy just now; this will be tried again.",
    BILLING: "Gemini's credit for this project has run out. Translation resumes by itself once it is topped up.",
    UNREACHABLE: "Gemini could not be reached; this will be tried again.",
    FAILED: "Gemini had a problem; this will be tried again.",
    UNREADABLE: "Gemini's answer couldn't be read; this will be tried again.",
    NO_MODEL: "The Gemini model isn't available to this project.",
    REJECTED: "Gemini turned this request down.",
    KEY: "Gemini didn't accept the key. An administrator can check it in Settings.",
    DECLINED: "Gemini declined to work on this transcript.",
    UNAVAILABLE: "Gemini isn't set up on this server.",
}


class GeminiError(RuntimeError):
    def __init__(self, kind: str, *, status: int | None = None) -> None:
        super().__init__(MESSAGES.get(kind, MESSAGES[FAILED]))
        self.kind = kind
        self.status = status
        self.retry = kind in _RETRYABLE
        self.hold_seconds = HOLD_SECONDS.get(kind)


@dataclass
class GeminiReply:
    data: dict[str, Any]
    model: str
    key_ref: str
    input_tokens: int
    output_tokens: int
    cost_usd: float
    latency_ms: int


def cost_usd(model: str, input_tokens: int, output_tokens: int) -> float:
    price_in, price_out = PRICES.get(model, PRICES[FALLBACK_MODEL])
    return round((input_tokens * price_in + output_tokens * price_out) / 1_000_000, 6)


def display_name(model: str | None) -> str:
    if not model:
        return "Gemini"
    return DISPLAY_NAMES.get(model, model)


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def parse_json_text(text: str | None) -> dict[str, Any] | None:
    if not text:
        return None
    cleaned = _FENCE.sub("", text.strip())
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        payload = json.loads(cleaned[start : end + 1])
    except ValueError:
        return None
    return payload if isinstance(payload, dict) else None


def _reply_text(payload: dict[str, Any]) -> tuple[str | None, str | None]:
    candidates = payload.get("candidates") or []
    if not candidates:
        return None, (payload.get("promptFeedback") or {}).get("blockReason") or "NO_CANDIDATE"
    first = candidates[0] or {}
    parts = (first.get("content") or {}).get("parts") or []
    text = "".join(str(part.get("text") or "") for part in parts if not part.get("thought"))
    return text or None, first.get("finishReason")


def _post(url: str, **kwargs: Any) -> Any:
    return requests.post(url, **kwargs)


def request_body(system: str, user: str, schema: dict[str, Any] | None, temperature: float) -> dict[str, Any]:
    config: dict[str, Any] = {"temperature": temperature, "responseMimeType": "application/json"}
    if schema is not None:
        config["responseSchema"] = schema
    return {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": user}]}],
        "generationConfig": config,
    }


def generate_json(
    *,
    system: str,
    user: str,
    keys: list[ApiKey],
    schema: dict[str, Any] | None = None,
    models: tuple[str, ...] = MODELS,
    temperature: float = 0.0,
    timeout: float = TIMEOUT_SECONDS,
    post: Callable[..., Any] | None = None,
) -> GeminiReply:
    if not keys:
        raise GeminiError(UNAVAILABLE)
    send = post or _post
    body = request_body(system, user, schema, temperature)
    kinds: list[str] = []
    last_status: int | None = None
    for model in models:
        next_model = False
        for key in keys:
            started = time.monotonic()
            try:
                response = send(
                    URL.format(model=model),
                    headers={"x-goog-api-key": key.value, "Content-Type": "application/json"},
                    json=body,
                    timeout=timeout,
                )
            except requests.RequestException as exc:
                logger.warning("gemini %s unreachable on %s (%s)", model, key.ref, type(exc).__name__)
                kinds.append(UNREACHABLE)
                continue
            status = getattr(response, "status_code", None)
            last_status = status
            if status in (401, 403):
                kinds.append(KEY)
                logger.warning("gemini %s refused key %s (HTTP %s)", model, key.ref, status)
                continue
            if status == 402:
                kinds.append(BILLING)
                logger.warning("gemini %s refused key %s for billing (HTTP 402)", model, key.ref)
                continue
            if status in (429, 503):
                kinds.append(THROTTLED)
                logger.warning("gemini %s throttled key %s (HTTP %s)", model, key.ref, status)
                continue
            if status == 404:
                kinds.append(NO_MODEL)
                logger.warning("gemini model %s is not available (HTTP 404)", model)
                next_model = True
                break
            if status in (400, 422):
                kinds.append(REJECTED)
                logger.warning("gemini %s rejected the request (HTTP %s)", model, status)
                next_model = True
                break
            if status is None or status >= 300:
                kinds.append(FAILED)
                logger.warning("gemini %s failed (HTTP %s)", model, status)
                continue
            try:
                payload = response.json() or {}
            except ValueError:
                kinds.append(UNREADABLE)
                next_model = True
                break
            text, finish = _reply_text(payload)
            if finish in {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION"} or (
                text is None and finish not in {None, "STOP", "MAX_TOKENS"}
            ):
                kinds.append(DECLINED)
                logger.warning("gemini %s declined (%s)", model, finish)
                next_model = True
                break
            data = parse_json_text(text)
            if data is None:
                kinds.append(UNREADABLE)
                logger.warning("gemini %s answered without the JSON asked for (%s)", model, finish)
                next_model = True
                break
            usage = payload.get("usageMetadata") or {}
            tokens_in = int(usage.get("promptTokenCount") or 0)
            tokens_out = int(usage.get("candidatesTokenCount") or 0) + int(
                usage.get("thoughtsTokenCount") or 0
            )
            return GeminiReply(
                data=data,
                model=model,
                key_ref=key.ref,
                input_tokens=tokens_in,
                output_tokens=tokens_out,
                cost_usd=cost_usd(model, tokens_in, tokens_out),
                latency_ms=int((time.monotonic() - started) * 1000),
            )
        if not next_model and kinds and all(kind == KEY for kind in kinds):
            break
    raise GeminiError(_worst(kinds), status=last_status)


def _worst(kinds: list[str]) -> str:
    if not kinds:
        return FAILED
    for kind in (DECLINED, KEY):
        if kinds and all(k == kind for k in kinds):
            return kind
    if BILLING in kinds:
        return BILLING
    if THROTTLED in kinds:
        return THROTTLED
    if UNREACHABLE in kinds:
        return UNREACHABLE
    for kind in (FAILED, UNREADABLE, DECLINED, REJECTED, NO_MODEL, KEY):
        if kind in kinds:
            return kind
    return FAILED
