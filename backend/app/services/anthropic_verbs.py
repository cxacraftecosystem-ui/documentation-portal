"""The Claude provider for the text verbs and photo captions. Official SDK, called in a thread.

WHY THIS IS ITS OWN MODULE AND NOT A BRANCH IN ``services/ai``
--------------------------------------------------------------
``services/ai`` is hand-written HTTP against OpenAI, ElevenLabs, Deepgram and Gemini, and every
request body in it is a literal dict. That is a reasonable shape for those four. It is a bad shape
for this one: the Claude request surface has changed twice in the past year in ways that return a
**400 rather than degrading** — sampling parameters removed, the fixed thinking budget replaced by
adaptive thinking, assistant prefill refused — so a literal body pins this repository to whichever
shape was current on the day somebody typed it, and the failure arrives as a dead verb rather than a
worse answer. The official SDK carries those rules. Keeping it in a file of its own also keeps
``services/ai`` a file about the providers it already speaks to.

THREE MODEL RULES THAT ARE 400s IF BROKEN, NOT STYLE
-----------------------------------------------------
1. **No ``temperature``, ever.** The existing text verbs each pass a per-verb temperature — a
   proofread wants determinism, an expansion does not. Claude Opus 5, Sonnet 5 and Opus 4.8 REMOVED
   the parameter and reject any request carrying it. The per-verb intent survives as ``effort``
   below and in the system prompts, which is where it belongs for these models anyway.
2. **``effort`` is not universal.** It is supported on Opus 5, Sonnet 5 and Opus 4.8 and **errors on
   Haiku 4.5**, which the catalogue also offers. :data:`_EFFORT_MODELS` is the allow-list; a model
   outside it is sent no ``output_config`` at all.
3. **Thinking is left ON.** On Claude Opus 5 thinking is the default and omitting the parameter runs
   it adaptively. Turning it off would be the obvious way to shave latency off a verb a designer is
   waiting on, and it is the wrong lever twice over: disabled thinking on this model can leak
   ``<thinking>`` tags into the visible response — which here means into a designer's proofread
   paragraph — and ``effort`` already buys back the latency without that risk. So depth is
   controlled by effort and the thinking parameter is never sent.

REFUSALS ARE A NORMAL OUTCOME, NOT AN ERROR
--------------------------------------------
A Claude response can arrive as HTTP 200 with ``stop_reason == "refusal"`` and an EMPTY ``content``
list. Reading ``content[0]`` without checking is an IndexError on a successful request, so
:func:`_first_text` checks the stop reason first and the callers turn it into a sentence. It is rare
on this material — field notes about craft — but "rare" is not "never", and the failure mode without
the check is a 500 on a designer's proofread.

EVERY FAILURE BECOMES A SENTENCE, AND NONE OF THEM CARRIES THE KEY
------------------------------------------------------------------
This module raises nothing at its boundary. ``services/ai`` already established that a provider
error must never surface the provider's own words, because an exception message built from a
prepared request can contain the credential; the same rule holds here and is why the error paths
report an exception CLASS and a status code and nothing else.
"""

from __future__ import annotations

import base64
import logging
from typing import Any

logger = logging.getLogger(__name__)

#: Models that accept ``output_config.effort``. Haiku 4.5 is deliberately absent — it errors.
#: Keyed on the exact catalogue ids in ``services/ai_providers``; a model not listed here simply
#: runs at its own default, which is correct behaviour rather than a degraded one.
_EFFORT_MODELS = frozenset({"claude-opus-5", "claude-sonnet-5", "claude-opus-4-8"})

#: Generous enough that a long translation is not truncated mid-sentence, and well inside the point
#: at which the SDK requires streaming. The verbs clip their input to 48,000 characters upstream, so
#: an output this size is already several times the largest plausible answer.
_MAX_TOKENS = 16_000

#: Roughly the latency budget the text verbs run under (``VERB_TIMEOUT_SECONDS`` is 90). Set on the
#: client rather than left at the SDK's ten-minute default, because a designer is standing in front
#: of the screen: a call that has not answered in a minute and a half has already failed as far as
#: they are concerned, and holding the connection open only delays the sentence telling them so.
_TIMEOUT_SECONDS = 85.0


class AnthropicUnavailable(RuntimeError):
    """The SDK is not installed on this deployment. Distinct from a call that failed."""


def _client(api_key: str) -> Any:
    """A client bound to ONE key — the caller's own, or the deployment's.

    IMPORTED INSIDE THE FUNCTION, NOT AT MODULE SCOPE, and that is load-bearing rather than lazy: the
    API router imports the routes that import this, so a top-level import would turn a missing
    optional dependency into a server that does not boot. Here it becomes one provider answering
    "unavailable" while every other verb in the app keeps working.

    ``api_key`` is always passed explicitly. A bare ``Anthropic()`` resolves a key from the process
    environment, which on a box that happens to export ``ANTHROPIC_API_KEY`` would silently bill the
    deployment for a call a designer's own key was chosen to pay for — the one mistake this whole
    feature exists to avoid.
    """
    try:
        import anthropic
    except ImportError as exc:  # pragma: no cover - depends on the deployment's install
        raise AnthropicUnavailable("the anthropic package is not installed") from exc
    return anthropic.Anthropic(api_key=api_key, timeout=_TIMEOUT_SECONDS, max_retries=1)


def _request_kwargs(model: str, effort: str) -> dict[str, Any]:
    """The parameters that vary by model. See rules 2 and 3 in the module docstring."""
    if model in _EFFORT_MODELS:
        return {"output_config": {"effort": effort}}
    return {}


def _first_text(message: Any) -> str:
    """The response's text, or "" — checking the stop reason BEFORE indexing into content.

    A refusal is an HTTP 200 whose ``content`` is empty, so this order is what stands between a rare
    safety decline and an IndexError on a designer's screen.
    """
    if getattr(message, "stop_reason", None) == "refusal":
        return ""
    for block in getattr(message, "content", None) or []:
        if getattr(block, "type", None) == "text":
            return (getattr(block, "text", "") or "").strip()
    return ""


def chat_verb(
    *, system: str, user: str, api_key: str, model: str, effort: str = "low"
) -> dict[str, Any]:
    """One text transform — proofread, expand, summarise or translate — against Claude.

    Returns the same dict shape ``services/ai``'s own verbs return, so a caller can dispatch on
    provider without learning a second result format. ``effort`` defaults to ``low`` because these
    are bounded rewriting tasks a person is waiting on, not open-ended reasoning; the callers raise
    it where the job genuinely needs more.
    """
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
        # A refusal is reported as its own status rather than as EMPTY: "the model returned nothing"
        # and "the model declined" are different facts, and only one of them is worth retrying.
        "status": "REFUSED" if refused else ("COMPLETED" if text else "EMPTY"),
        "text": text or None,
        # The provenance the layer service refuses a layer without — see `_chat_verb_sync` in
        # services/ai, which returns the same two keys for the same reason.
        "provider": "anthropic",
        "model": model,
    }


def caption_image(
    *, prompt: str, content: bytes, mime_type: str, api_key: str, model: str
) -> dict[str, Any]:
    """A caption for one photograph. Vision is standard on every Claude model in the catalogue.

    The image goes as base64 in a ``document``-shaped ``image`` block. ``media_type`` must be the
    real type of the bytes — Claude rejects a mismatch rather than sniffing, which is stricter than
    the Gemini path this sits beside and is the reason the caller passes the stored MIME type rather
    than guessing from a file extension.
    """
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
                            # No newlines: the API rejects a wrapped base64 payload.
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
