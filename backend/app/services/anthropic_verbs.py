from __future__ import annotations

import base64
import logging
from typing import Any

logger = logging.getLogger(__name__)

_EFFORT_MODELS = frozenset({"claude-sonnet-5-5", "claude-opus-5", "claude-sonnet-5", "claude-opus-4-8"})

_MAX_TOKENS = 16_000

_TIMEOUT_SECONDS = 85.0


class AnthropicUnavailable(RuntimeError):
    pass


def _client(api_key: str) -> Any:
    try:
        import anthropic
    except ImportError as exc:  # pragma: no cover - depends on the deployment's install
        raise AnthropicUnavailable("the anthropic package is not installed") from exc
    return anthropic.Anthropic(api_key=api_key, timeout=_TIMEOUT_SECONDS, max_retries=1)


def _request_kwargs(model: str, effort: str) -> dict[str, Any]:
    if model in _EFFORT_MODELS:
        return {"output_config": {"effort": effort}}
    return {}


def _first_text(message: Any) -> str:
    if getattr(message, "stop_reason", None) == "refusal":
        return ""
    for block in getattr(message, "content", None) or []:
        if getattr(block, "type", None) == "text":
            return (getattr(block, "text", "") or "").strip()
    return ""


def chat_verb(
    *, system: str, user: str, api_key: str, model: str, effort: str = "low"
) -> dict[str, Any]:
    client = _client(api_key)
    message = client.messages.create(
        model=model,
        max_tokens=_MAX_TOKENS,
        system=system,
        messages=[{"role": "user", "content": user}],
        **_request_kwargs(model, effort),
    )
    text = _first_text(message)
    refused = getattr(message, "stop_reason", None) == "refusal"
    return {
        "available": True,
        "status": "REFUSED" if refused else ("COMPLETED" if text else "EMPTY"),
        "text": text or None,
        "provider": "anthropic",
        "model": model,
        "responseModel": getattr(message, "model", None),
    }


def caption_image(
    *, prompt: str, content: bytes, mime_type: str, api_key: str, model: str
) -> dict[str, Any]:
    client = _client(api_key)
    message = client.messages.create(
        model=model,
        max_tokens=_MAX_TOKENS,
        messages=[
            {
                "role": "user",
                "content": [
                    {
                        "type": "image",
                        "source": {
                            "type": "base64",
                            "media_type": mime_type,
                            "data": base64.standard_b64encode(content).decode("ascii"),
                        },
                    },
                    {"type": "text", "text": prompt},
                ],
            }
        ],
        **_request_kwargs(model, "low"),
    )
    text = _first_text(message)
    refused = getattr(message, "stop_reason", None) == "refusal"
    return {
        "available": True,
        "status": "REFUSED" if refused else ("COMPLETED" if text else "EMPTY"),
        "text": text or None,
        "provider": "anthropic",
        "model": model,
    }
