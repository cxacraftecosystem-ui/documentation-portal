"""AI-drafted interview answers from a transcript (C28). Machine-suggested, never machine-written.

── WHAT A PERSON SEES ───────────────────────────────────────────────────────────────────────────

An interview has a transcript (a recording's, or text pasted in). The researcher opens one SECTION
and presses "Draft from transcript". The model reads the transcript against that section's
unanswered questions and drafts what the artisan said, question by question; questions the
transcript does not answer stay empty. The researcher reads the drafts beside the questions, edits
any of them, and accepts the section — or dismisses it. Nothing is an answer until then.

── THE RULES, AND WHERE EACH IS HELD ────────────────────────────────────────────────────────────

1. **A draft is a row of its own** (``QuestionnaireAnswerDraft``) with the model's words, the
   provider and the model. Drafting never writes ``QuestionnaireResponse``; only the accept route
   (``routes/answer_drafts.accept_section``) writes answers, and only the words the person sent.
2. **Machine-suggested in the data.** An accepted answer carries ``answerOrigin`` =
   ``MACHINE_SUGGESTED`` when the person kept the model's words exactly, ``MACHINE_SUGGESTED_EDITED``
   when they changed them, and a later edit through the ordinary save moves the first to the second
   (``routes/questionnaire.upsert_responses``). The UI labels both.
3. **A draft never replaces a person's words.** Only questions with no answer text are drafted, and
   accepting skips a question that has gained an answer since.
4. **The model may only report the transcript.** The prompt forbids inference, and :func:`parse_answers`
   keeps only answers to the questions it was asked, clipped, so a reply cannot write to a question
   outside the section.
5. **Off by default.** ``AI_ANSWER_DRAFTS_ENABLED``; the routes answer 404 while it is off.

The provider call reuses the AI plumbing already here: ``user_ai_keys.resolve`` picks the
researcher's own key (or the deployment's), and ``anthropic_verbs.chat_verb`` / the OpenAI and
Gemini chat endpoints answer, exactly as the text verbs do in the designer portal.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

import requests

from app.services import anthropic_verbs, user_ai_keys
from app.services.ai_providers import AiProvider, AiTask

logger = logging.getLogger(__name__)

ORIGIN_MACHINE = "MACHINE_SUGGESTED"
ORIGIN_MACHINE_EDITED = "MACHINE_SUGGESTED_EDITED"
ORIGINS = (ORIGIN_MACHINE, ORIGIN_MACHINE_EDITED)

STATUS_PENDING = "PENDING"
STATUS_ACCEPTED = "ACCEPTED"
STATUS_EDITED = "EDITED"
STATUS_DISMISSED = "DISMISSED"

#: Longest transcript sent, and longest single drafted answer kept.
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
    """The user message: numbered questions, then the transcript (clipped)."""
    lines = [f"{n}. {prompt}" for n, prompt in questions]
    return (
        "Questions:\n"
        + "\n".join(lines)
        + "\n\nTranscript:\n\n"
        + transcript.strip()[:TRANSCRIPT_MAX_CHARS]
    )


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def parse_answers(text: str | None, asked: int) -> dict[int, str]:
    """``{question number: answer}`` for the non-empty answers to questions 1..asked. Never raises."""
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
    """One completion on the resolved credential. Returns ``{text, provider, model}``."""
    model = credential.model
    if credential.provider is AiProvider.ANTHROPIC:
        result = anthropic_verbs.chat_verb(
            system=system, user=user, api_key=credential.api_key, model=model, effort="medium"
        )
        return {"text": result.get("text"), "provider": "anthropic", "model": model}
    if credential.provider is AiProvider.GEMINI:
        response = requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            headers={"x-goog-api-key": credential.api_key},
            json={
                "contents": [{"role": "user", "parts": [{"text": user}]}],
                "systemInstruction": {"parts": [{"text": system}]},
                "generationConfig": {"temperature": 0.0, "responseMimeType": "application/json"},
            },
            timeout=TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        candidates = response.json().get("candidates") or []
        parts = (candidates[0].get("content") or {}).get("parts") or [] if candidates else []
        return {
            "text": "".join(str(p.get("text", "")) for p in parts),
            "provider": "gemini",
            "model": model,
        }
    # The deployment's OpenAI key runs the deployment's configured chat model, as everywhere else.
    if not credential.is_user_supplied:
        model = settings.openai_chat_model
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {credential.api_key}", "Content-Type": "application/json"},
        json={
            "model": model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.0,
            "response_format": {"type": "json_object"},
        },
        timeout=TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    return {
        "text": str(response.json()["choices"][0]["message"]["content"]),
        "provider": "openai",
        "model": model,
    }


class DraftUnavailable(RuntimeError):
    """No provider could be asked; the message is a sentence for the researcher."""


async def draft_answers(
    questions: list[tuple[int, str]], transcript: str, settings: Any, *, user_id: str | None
) -> tuple[dict[int, str], str, str]:
    """``(answers by number, provider, model)``. Raises :class:`DraftUnavailable` with a sentence."""
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
        # The CLASS only: an exception from a prepared request can carry the key.
        logger.error("answer drafting failed: %s", type(exc).__name__)
        raise DraftUnavailable("Drafting failed. Try again, or type the answers.") from None
    return parse_answers(result.get("text"), len(questions)), result["provider"], result["model"]


def origin_after_edit(current_origin: str | None, old_text: str | None, new_text: str | None) -> str | None:
    """The ``answerOrigin`` a person's edit leaves behind (rule 2)."""
    if not (new_text or "").strip():
        return None
    if current_origin == ORIGIN_MACHINE and (new_text or "") != (old_text or ""):
        return ORIGIN_MACHINE_EDITED
    return current_origin
