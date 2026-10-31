"""Ask this interview, and fill the questionnaire from it: answers that cite the turns they came from.

THE INTEGRITY RULE, enforced here and not left to the model: a citation names a turn that EXISTS in
the transcript it was asked about, and when it quotes, the quote is a verbatim span (casefolded,
whitespace collapsed) of that turn's original or English. A citation that fails either test is
dropped. An answer left with no valid citation is not an answer: Ask says the interview does not
cover the question, and a questionnaire draft is not made.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from app.transcription.contract import ApiKey
from app.transcription.transcript import gemini
from app.transcription.transcript.render import timestamp
from app.transcription.transcript.translate import normalise

ANSWER_MAX_CHARS = 2_000
QUESTION_MAX_CHARS = 500
CITATIONS_MAX = 8
#: Characters of transcript sent to Ask / auto-draft. Flash reads far more; this bounds the bill.
CONTEXT_MAX_CHARS = 400_000
NOT_COVERED = "The interview doesn't cover this."

ASK_SYSTEM = (
    "You answer a researcher's question about ONE interview, using ONLY the transcript given as "
    "numbered turns (id, time, speaker, original text, English).\n"
    "THE MOST IMPORTANT RULE: answer only from the transcript. Never use outside knowledge, never "
    "infer what a craft usually involves, never guess. If the transcript does not answer the "
    "question, set covered to false and say so in one sentence.\n"
    "When it does: answer in plain English in at most five sentences, keep names, numbers and craft "
    "terms exactly, and cite every turn you used by its id. For each citation give a short quote "
    "copied EXACTLY, character for character, from that turn's original or its English.\n"
    "Reply with JSON only, in the schema given."
)

ASK_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "covered": {"type": "boolean"},
        "answer": {"type": "string"},
        "citations": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"id": {"type": "string"}, "quote": {"type": "string"}},
                "required": ["id"],
            },
        },
    },
    "required": ["covered", "answer", "citations"],
}

DRAFT_SYSTEM = (
    "You help a field researcher fill in an interview questionnaire from the recording of the "
    "interview with a craft artisan in India. You are given the questions (numbered) and the "
    "transcript as numbered turns (id, time, speaker, original text, English).\n"
    "For each question the ARTISAN answers in the transcript, draft the answer twice: in the "
    "language of the transcript (original) and in English. Cite the turn ids the answer comes from, "
    "each with a short quote copied EXACTLY from that turn's original or English.\n"
    "THE MOST IMPORTANT RULE: report only what is in the transcript. Never infer, never supply "
    "typical values. Leave out a question the transcript does not answer.\n"
    "Keep names, places, craft terms, numbers and measurements exactly as spoken. Be brief and plain.\n"
    "Reply with JSON only, in the schema given."
)

DRAFT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "answers": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "n": {"type": "integer"},
                    "original": {"type": "string"},
                    "english": {"type": "string"},
                    "citations": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {"id": {"type": "string"}, "quote": {"type": "string"}},
                            "required": ["id"],
                        },
                    },
                },
                "required": ["n", "original", "english", "citations"],
            },
        }
    },
    "required": ["answers"],
}


@dataclass
class CiteTurn:
    id: str
    start_ms: int
    speaker: str
    text: str
    english: str | None = None


@dataclass
class Citation:
    turn_id: str
    start_ms: int
    quote: str | None

    def as_json(self) -> dict[str, Any]:
        return {"turnId": self.turn_id, "startMs": self.start_ms, "quote": self.quote}


@dataclass
class AskResult:
    covered: bool
    answer: str
    citations: list[Citation] = field(default_factory=list)
    model: str | None = None
    cost_usd: float = 0.0


@dataclass
class DraftAnswer:
    n: int
    original: str
    english: str
    citations: list[Citation]


def context_message(turns: list[CiteTurn], short_ids: dict[str, str]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    size = 0
    for turn in turns:
        size += len(turn.text) + len(turn.english or "")
        if size > CONTEXT_MAX_CHARS:
            break
        out.append(
            {
                "id": short_ids[turn.id],
                "time": timestamp(turn.start_ms),
                "speaker": turn.speaker,
                "original": turn.text,
                "english": turn.english or turn.text,
            }
        )
    return out


def short_ids_for(turns: list[CiteTurn]) -> dict[str, str]:
    return {turn.id: f"t{index + 1}" for index, turn in enumerate(turns)}


def verify_citations(raw: Any, turns: list[CiteTurn], by_short: dict[str, str]) -> list[Citation]:
    """Only citations of turns that exist, and only quotes that are verbatim spans of that turn."""
    by_id = {turn.id: turn for turn in turns}
    out: list[Citation] = []
    seen: set[tuple[str, str | None]] = set()
    for item in raw if isinstance(raw, list) else []:
        if not isinstance(item, dict):
            continue
        turn_id = by_short.get(str(item.get("id") or "").strip())
        turn = by_id.get(turn_id or "")
        if turn is None:
            continue
        quote = item.get("quote")
        quote = quote.strip().strip("\"“”") if isinstance(quote, str) and quote.strip() else None
        if quote is not None:
            needle = normalise(quote)
            haystacks = [normalise(turn.text), normalise(turn.english or "")]
            if not needle or not any(needle in hay for hay in haystacks if hay):
                continue
        key = (turn.id, quote)
        if key in seen:
            continue
        seen.add(key)
        out.append(Citation(turn.id, turn.start_ms, quote))
        if len(out) >= CITATIONS_MAX:
            break
    return out


def parse_ask(data: dict[str, Any], turns: list[CiteTurn], by_short: dict[str, str]) -> AskResult:
    citations = verify_citations(data.get("citations"), turns, by_short)
    answer = data.get("answer") if isinstance(data.get("answer"), str) else ""
    answer = answer.strip()[:ANSWER_MAX_CHARS]
    if data.get("covered") is not True or not citations or not answer:
        return AskResult(False, NOT_COVERED, [])
    return AskResult(True, answer, citations)


def ask(
    question: str,
    turns: list[CiteTurn],
    *,
    keys: list[ApiKey],
    post: Callable[..., Any] | None = None,
    models: tuple[str, ...] = gemini.MODELS,
) -> AskResult:
    question = " ".join(str(question or "").split())[:QUESTION_MAX_CHARS]
    if not turns:
        return AskResult(False, NOT_COVERED)
    short = short_ids_for(turns)
    by_short = {v: k for k, v in short.items()}
    reply = gemini.generate_json(
        system=ASK_SYSTEM,
        user=json.dumps({"question": question, "turns": context_message(turns, short)}, ensure_ascii=False),
        keys=keys,
        schema=ASK_SCHEMA,
        models=models,
        post=post,
    )
    result = parse_ask(reply.data, turns, by_short)
    result.model = reply.model
    result.cost_usd = reply.cost_usd
    return result


def parse_drafts(
    data: dict[str, Any], asked: int, turns: list[CiteTurn], by_short: dict[str, str]
) -> list[DraftAnswer]:
    out: dict[int, DraftAnswer] = {}
    for item in data.get("answers") or []:
        if not isinstance(item, dict):
            continue
        try:
            n = int(item.get("n"))
        except TypeError, ValueError:
            continue
        if not 1 <= n <= asked or n in out:
            continue
        original = item.get("original") if isinstance(item.get("original"), str) else ""
        english = item.get("english") if isinstance(item.get("english"), str) else ""
        citations = verify_citations(item.get("citations"), turns, by_short)
        if not original.strip() or not citations:
            continue  # an answer that cannot point at the recording is not drafted
        out[n] = DraftAnswer(n, original.strip()[:ANSWER_MAX_CHARS], english.strip()[:ANSWER_MAX_CHARS], citations)
    return [out[n] for n in sorted(out)]


def draft(
    questions: list[tuple[int, str]],
    turns: list[CiteTurn],
    *,
    keys: list[ApiKey],
    post: Callable[..., Any] | None = None,
    models: tuple[str, ...] = gemini.MODELS,
) -> tuple[list[DraftAnswer], gemini.GeminiReply | None]:
    if not questions or not turns:
        return [], None
    short = short_ids_for(turns)
    by_short = {v: k for k, v in short.items()}
    reply = gemini.generate_json(
        system=DRAFT_SYSTEM,
        user=json.dumps(
            {
                "questions": [{"n": n, "question": prompt} for n, prompt in questions],
                "turns": context_message(turns, short),
            },
            ensure_ascii=False,
        ),
        keys=keys,
        schema=DRAFT_SCHEMA,
        models=models,
        post=post,
    )
    return parse_drafts(reply.data, len(questions), turns, by_short), reply
