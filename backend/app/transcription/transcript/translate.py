from __future__ import annotations

import json
import re
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from app.transcription.contract import ApiKey
from app.transcription.transcript import gemini

BATCH_CHARS = 40_000
SUMMARY_MAX_LINES = 5
SUMMARY_LINE_MAX_CHARS = 400
QUOTES_MAX = 5
QUOTE_MAX_CHARS = 1_000
TERMS_MAX = 60
SUMMARISED_RATIO = 0.3
SUMMARISED_MIN_CHARS = 120

TRANSLATED = "TRANSLATED"
ALREADY_ENGLISH = "ALREADY_ENGLISH"
FAILED = "FAILED"

SYSTEM = (
    "You translate interview transcripts from craft documentation fieldwork in India into English.\n"
    "You are given the transcript as numbered TURNS (id, speaker, language, text). Translate every "
    "turn whose \"translate\" is true, ONE English text PER TURN, keyed by the same id.\n"
    "RULES, all of them mandatory:\n"
    "1. Translate faithfully and completely. NEVER summarise, shorten, merge or skip a turn. Every "
    "sentence in the original has its English.\n"
    "2. Craft terms (techniques, tools, materials, products, e.g. dabu, bandhani, ringal, kalamkari) "
    "stay in the original, transliterated into Latin letters, with a short English gloss in brackets "
    "the FIRST time the term appears in the whole transcript only, e.g. \"bandhani (tie-dye)\". Later "
    "mentions keep the term alone. Terms listed in \"alreadyGlossed\" are never glossed again.\n"
    "3. Keep every personal name, place name, number, quantity, price, date and unit exactly as "
    "stated. Write numbers as digits.\n"
    "4. Where the original is unclear, write [unclear] rather than guessing.\n"
    "5. If a turn you were asked to translate is already in English, return its text unchanged and "
    "set alreadyEnglish to true.\n"
    "6. Translate meaning, not word order; the English must read naturally.\n"
    "ALSO, unless \"translationsOnly\" is true:\n"
    "- primaryLanguage: the main language of the whole transcript as a BCP-47 code (hi, gu, en, ...), "
    "or \"multi\" when none dominates.\n"
    "- summary: at most five short plain-English lines, adding NOTHING that is not in the transcript "
    "and dropping no number, name, material or problem it states.\n"
    "- quotes: at most five key quotes, each copied EXACTLY, character for character, from ONE turn's "
    "original text, with that turn's id and an English rendering.\n"
    "- terms: the craft terms heard, each with its short English gloss.\n"
    "Reply with JSON only, in the schema given."
)

RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "translations": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "english": {"type": "string"},
                    "alreadyEnglish": {"type": "boolean"},
                },
                "required": ["id", "english", "alreadyEnglish"],
            },
        },
        "primaryLanguage": {"type": "string"},
        "summary": {"type": "array", "items": {"type": "string"}},
        "quotes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "original": {"type": "string"},
                    "english": {"type": "string"},
                },
                "required": ["id", "original", "english"],
            },
        },
        "terms": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"term": {"type": "string"}, "gloss": {"type": "string"}},
                "required": ["term", "gloss"],
            },
        },
    },
    "required": ["translations"],
}


@dataclass
class TurnIn:
    id: str
    speaker: str
    language: str
    text: str


@dataclass
class TurnOut:
    id: str
    status: str
    english: str | None


@dataclass
class PassResult:
    translations: dict[str, TurnOut] = field(default_factory=dict)
    primary_language: str | None = None
    summary: list[str] = field(default_factory=list)
    quotes: list[dict[str, Any]] = field(default_factory=list)
    terms: list[dict[str, str]] = field(default_factory=list)
    model: str | None = None
    cost_usd: float = 0.0
    input_tokens: int = 0
    output_tokens: int = 0
    calls: int = 0

    @property
    def failed_ids(self) -> list[str]:
        return [tid for tid, out in self.translations.items() if out.status == FAILED]


def is_english(language: str | None) -> bool:
    return bool(language) and (language == "en" or str(language).startswith("en-"))


_SPACE = re.compile(r"\s+")


def normalise(text: str) -> str:
    value = unicodedata.normalize("NFC", str(text or "")).casefold()
    return _SPACE.sub(" ", value).strip().strip("\"'“”‘’").strip()


_DIGITS = re.compile(r"[0-9]+")
_UNCLEAR = re.compile(r"\[\s*unclear\s*\]", re.IGNORECASE)


def _ascii_digits(text: str) -> str:
    return "".join(str(unicodedata.decimal(ch)) if not ch.isascii() and unicodedata.decimal(ch, None) is not None else ch for ch in text)


def numbers_in(text: str) -> set[str]:
    return set(_DIGITS.findall(_ascii_digits(str(text or ""))))


def flags_unclear(english: str) -> bool:
    return bool(_UNCLEAR.search(english or ""))


def suspicious(original: str, english: str, *, numbers: bool = True) -> bool:
    source = original.strip()
    target = english.strip()
    if not target:
        return bool(source)
    unclear = flags_unclear(target)
    if not unclear and len(source) >= SUMMARISED_MIN_CHARS and len(target) < SUMMARISED_RATIO * len(source):
        return True
    return numbers and not unclear and not numbers_in(source) <= numbers_in(target)


def batches(turns: list[TurnIn], limit: int = BATCH_CHARS) -> list[list[TurnIn]]:
    out: list[list[TurnIn]] = []
    current: list[TurnIn] = []
    size = 0
    for turn in turns:
        length = len(turn.text)
        if current and size + length > limit:
            out.append(current)
            current, size = [], 0
        current.append(turn)
        size += length
    if current:
        out.append(current)
    return out


def user_message(
    turns: list[TurnIn],
    *,
    translate_ids: set[str],
    glossary: list[dict[str, str]],
    already_glossed: list[str],
    translations_only: bool,
    short_ids: dict[str, str],
) -> str:
    payload = {
        "translationsOnly": translations_only,
        "glossary": glossary[:200],
        "alreadyGlossed": already_glossed[:400],
        "turns": [
            {
                "id": short_ids[turn.id],
                "speaker": turn.speaker,
                "language": turn.language,
                "translate": turn.id in translate_ids,
                "text": turn.text,
            }
            for turn in turns
        ],
    }
    return json.dumps(payload, ensure_ascii=False)


def _str(value: Any, limit: int) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    return value.strip()[:limit]


def _gloss_terms(english: str) -> list[str]:
    return [m.group(1) for m in re.finditer(r"\b([A-Za-z][A-Za-z'-]*) \([^()]{2,60}\)", english)]


def parse_reply(
    data: dict[str, Any],
    batch: list[TurnIn],
    *,
    translate_ids: set[str],
    by_short: dict[str, str],
    strict: bool = True,
) -> tuple[dict[str, TurnOut], dict[str, Any]]:
    texts = {turn.id: turn.text for turn in batch}
    out: dict[str, TurnOut] = {}
    for item in data.get("translations") or []:
        if not isinstance(item, dict):
            continue
        turn_id = by_short.get(str(item.get("id") or ""))
        if turn_id is None or turn_id not in translate_ids or turn_id not in texts:
            continue
        english = item.get("english") if isinstance(item.get("english"), str) else ""
        if item.get("alreadyEnglish") is True and normalise(english) in {"", normalise(texts[turn_id])}:
            out[turn_id] = TurnOut(turn_id, ALREADY_ENGLISH, texts[turn_id])
            continue
        if suspicious(texts[turn_id], english, numbers=strict):
            continue
        out[turn_id] = TurnOut(turn_id, TRANSLATED, english.strip())
    summary: list[str] = []
    raw_summary = data.get("summary")
    if isinstance(raw_summary, str):
        raw_summary = raw_summary.splitlines()
    for line in raw_summary if isinstance(raw_summary, list) else []:
        text = _str(line, SUMMARY_LINE_MAX_CHARS)
        if text:
            summary.append(text.lstrip("-•* ").strip())
    quotes: list[dict[str, Any]] = []
    for item in data.get("quotes") or []:
        if not isinstance(item, dict):
            continue
        original = _str(item.get("original"), QUOTE_MAX_CHARS)
        if not original:
            continue
        needle = normalise(original)
        claimed = by_short.get(str(item.get("id") or ""))
        home = None
        if claimed in texts and needle and needle in normalise(texts[claimed]):
            home = claimed
        else:
            home = next((tid for tid, text in texts.items() if needle and needle in normalise(text)), None)
        if home is None:
            continue
        quotes.append(
            {
                "turnId": home,
                "original": original.strip("\"“”"),
                "english": _str(item.get("english"), QUOTE_MAX_CHARS) or "",
            }
        )
    terms: list[dict[str, str]] = []
    for item in data.get("terms") or []:
        if isinstance(item, dict):
            term = _str(item.get("term"), 60)
            gloss = _str(item.get("gloss"), 120)
            if term:
                terms.append({"term": term, "gloss": gloss or ""})
    language = data.get("primaryLanguage")
    extras = {
        "primaryLanguage": language.strip().lower()[:16] if isinstance(language, str) and language.strip() else None,
        "summary": summary,
        "quotes": quotes,
        "terms": terms,
    }
    return out, extras


def run_pass(
    turns: list[TurnIn],
    *,
    keys: list[ApiKey],
    glossary: list[dict[str, str]] | None = None,
    already_glossed: list[str] | None = None,
    translations_only: bool = False,
    post: Callable[..., Any] | None = None,
    models: tuple[str, ...] = gemini.MODELS,
    batch_chars: int = BATCH_CHARS,
) -> PassResult:
    result = PassResult()
    glossary = list(glossary or [])
    short_ids = {turn.id: f"t{index + 1}" for index, turn in enumerate(turns)}
    by_short = {short: turn_id for turn_id, short in short_ids.items()}
    translate_ids: set[str] = set()
    for turn in turns:
        if not turn.text.strip():
            continue
        if is_english(turn.language):
            result.translations[turn.id] = TurnOut(turn.id, ALREADY_ENGLISH, turn.text)
        else:
            translate_ids.add(turn.id)
    if not translate_ids and translations_only:
        return result
    already_glossed = list(already_glossed or [])
    summaries: list[list[str]] = []
    parts = batches(turns, batch_chars)
    for number, batch in enumerate(parts):
        wanted = {turn.id for turn in batch} & translate_ids
        if not wanted and translations_only:
            continue
        try:
            reply = gemini.generate_json(
                system=SYSTEM,
                user=user_message(
                    batch,
                    translate_ids=wanted,
                    glossary=glossary,
                    already_glossed=already_glossed,
                    translations_only=translations_only,
                    short_ids=short_ids,
                ),
                keys=keys,
                schema=RESPONSE_SCHEMA,
                models=models,
                post=post,
            )
        except gemini.GeminiError:
            if number == 0 and not result.calls:
                raise
            continue
        _account(result, reply)
        found, extras = parse_reply(reply.data, batch, translate_ids=wanted, by_short=by_short)
        result.translations.update(found)
        for out in found.values():
            if out.english:
                already_glossed.extend(t for t in _gloss_terms(out.english) if t not in already_glossed)
        if not translations_only:
            if extras["primaryLanguage"] and result.primary_language is None:
                result.primary_language = extras["primaryLanguage"]
            summaries.append(extras["summary"])
            result.quotes.extend(extras["quotes"])
            for term in extras["terms"]:
                if all(normalise(term["term"]) != normalise(t["term"]) for t in result.terms):
                    result.terms.append(term)
    missing = [turn for turn in turns if turn.id in translate_ids and turn.id not in result.translations]
    if missing:
        for batch in batches(missing, batch_chars):
            wanted = {turn.id for turn in batch}
            try:
                reply = gemini.generate_json(
                    system=SYSTEM,
                    user=user_message(
                        batch,
                        translate_ids=wanted,
                        glossary=glossary,
                        already_glossed=already_glossed,
                        translations_only=True,
                        short_ids=short_ids,
                    ),
                    keys=keys,
                    schema=RESPONSE_SCHEMA,
                    models=models,
                    post=post,
                )
            except gemini.GeminiError:
                continue
            _account(result, reply)
            found, _ = parse_reply(
                reply.data, batch, translate_ids=wanted, by_short=by_short, strict=False
            )
            result.translations.update(found)
    for turn in turns:
        if turn.id in translate_ids and turn.id not in result.translations:
            result.translations[turn.id] = TurnOut(turn.id, FAILED, None)
    result.summary = _merge_summaries(summaries)
    result.quotes = result.quotes[:QUOTES_MAX]
    result.terms = result.terms[:TERMS_MAX]
    if result.primary_language is None and not translate_ids and turns:
        result.primary_language = "en"
    return result


def _account(result: PassResult, reply: gemini.GeminiReply) -> None:
    result.calls += 1
    result.model = result.model or reply.model
    result.cost_usd = round(result.cost_usd + reply.cost_usd, 6)
    result.input_tokens += reply.input_tokens
    result.output_tokens += reply.output_tokens


def _merge_summaries(parts: list[list[str]]) -> list[str]:
    parts = [part for part in parts if part]
    if not parts:
        return []
    if len(parts) == 1:
        return parts[0][:SUMMARY_MAX_LINES]
    share = max(1, SUMMARY_MAX_LINES // len(parts))
    merged: list[str] = []
    for part in parts:
        merged.extend(part[:share])
    return merged[:SUMMARY_MAX_LINES]
