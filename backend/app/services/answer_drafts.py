from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

import requests

from app.services import anthropic_verbs, user_ai_keys
from app.services.ai_providers import (
    AiProvider,
    AiTask,
    gemini_reply_text,
    openai_message_text,
    openai_sampling,
    post_gemini,
)

logger = logging.getLogger(__name__)

ORIGIN_MACHINE = "MACHINE_SUGGESTED"
ORIGIN_MACHINE_EDITED = "MACHINE_SUGGESTED_EDITED"
ORIGINS = (ORIGIN_MACHINE, ORIGIN_MACHINE_EDITED)

STATUS_PENDING = "PENDING"
STATUS_ACCEPTED = "ACCEPTED"
STATUS_EDITED = "EDITED"
STATUS_DISMISSED = "DISMISSED"

TRANSCRIPT_MAX_CHARS = 48_000
ANSWER_MAX_CHARS = 4_000
TIMEOUT_SECONDS = 90

SYSTEM_PROMPT = (
    "You help a field researcher fill in an interview questionnaire from the transcript of the "
    "interview with a craft artisan in India.\n"
    "For each numbered question, write what the ARTISAN said in the transcript that answers it.\n"
    "THE MOST IMPORTANT RULE: report only what is in the transcript. Never infer, never supply "
    "typical values, never describe what a craft usually involves. If the transcript does not answer "
    "a question, return an empty string for it.\n"
    "Keep names, places, craft terms, numbers and measurements exactly as spoken. Write in the "
    "language of the transcript; do not translate. Be brief and plain.\n"
    'Reply with JSON only: {"answers": [{"n": 1, "answer": "..."}, ...]}.'
)


def build_prompt(questions: list[tuple[int, str]], transcript: str) -> str:
    lines = [f"{n}. {prompt}" for n, prompt in questions]
    return (
        "Questions:\n"
        + "\n".join(lines)
        + "\n\nTranscript:\n\n"
        + transcript.strip()[:TRANSCRIPT_MAX_CHARS]
    )


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def parse_answers(text: str | None, asked: int) -> dict[int, str]:
    if not text:
        return {}
    cleaned = _FENCE.sub("", text.strip())
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        return {}
    try:
        payload = json.loads(cleaned[start : end + 1])
    except ValueError:
        return {}
    out: dict[int, str] = {}
    for item in payload.get("answers", []) if isinstance(payload, dict) else []:
        if not isinstance(item, dict):
            continue
        try:
            n = int(item.get("n"))
        except (TypeError, ValueError):
            continue
        answer = item.get("answer")
        if 1 <= n <= asked and isinstance(answer, str) and answer.strip():
            out[n] = answer.strip()[:ANSWER_MAX_CHARS]
    return out


def _chat_sync(system: str, user: str, credential: Any, settings: Any) -> dict[str, Any]:
    model = credential.model
    if credential.provider is AiProvider.ANTHROPIC:
        result = anthropic_verbs.chat_verb(
            system=system, user=user, api_key=credential.api_key, model=model, effort="medium"
        )
        return {"text": result.get("text"), "provider": "anthropic", "model": model}
    if credential.provider is AiProvider.GEMINI:
        model, response = post_gemini(
            requests.post,
            model,
            headers={"x-goog-api-key": credential.api_key},
            json={
                "contents": [{"role": "user", "parts": [{"text": user}]}],
                "systemInstruction": {"parts": [{"text": system}]},
                "generationConfig": {"temperature": 0.0, "responseMimeType": "application/json"},
            },
            timeout=TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        return {
            "text": gemini_reply_text(response.json()),
            "provider": "gemini",
            "model": model,
        }
    if not credential.is_user_supplied:
        model = settings.openai_chat_model
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {credential.api_key}", "Content-Type": "application/json"},
        json={
            "model": model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            **openai_sampling(model, 0.0),
            "response_format": {"type": "json_object"},
        },
        timeout=TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    return {
        "text": openai_message_text(response.json()),
        "provider": "openai",
        "model": model,
    }


class DraftUnavailable(RuntimeError):
    pass


async def draft_answers(
    questions: list[tuple[int, str]], transcript: str, settings: Any, *, user_id: str | None
) -> tuple[dict[int, str], str, str]:
    credential = await user_ai_keys.resolve(user_id, AiTask.SUMMARISE)
    if credential is None:
        raise DraftUnavailable("Drafting isn't set up. An administrator can add an AI key in Settings.")
    try:
        result = await asyncio.to_thread(
            _chat_sync, SYSTEM_PROMPT, build_prompt(questions, transcript), credential, settings
        )
    except anthropic_verbs.AnthropicUnavailable:
        raise DraftUnavailable("Drafting isn't available on this server right now.") from None
    except Exception as exc:  # noqa: BLE001 - a provider error must become a sentence, never a 500
        logger.error("answer drafting failed: %s", type(exc).__name__)
        raise DraftUnavailable("Drafting failed. Try again, or type the answers.") from None
    return parse_answers(result.get("text"), len(questions)), result["provider"], result["model"]


ORIGIN_LABELS = {
    ORIGIN_MACHINE: "machine-suggested",
    ORIGIN_MACHINE_EDITED: "machine-suggested, edited",
}


def origin_label(origin: str | None) -> str:
    return ORIGIN_LABELS.get(origin or "", "")


def marked_answer(text: str | None, origin: str | None) -> str:
    label = origin_label(origin)
    body = text or ""
    return f"{body} ({label})" if label and body.strip() else body


def origin_after_edit(current_origin: str | None, old_text: str | None, new_text: str | None) -> str | None:
    if not (new_text or "").strip():
        return None
    if current_origin == ORIGIN_MACHINE and (new_text or "") != (old_text or ""):
        return ORIGIN_MACHINE_EDITED
    return current_origin
