"""Scribe's words as speaker turns: the unit every view, copy, subtitle and translation works on.

A TURN is one uninterrupted stretch of one voice, split further only where a long monologue pauses
(so a ten-minute answer is not one block nobody can seek inside). Each turn keeps:

* its speaker key (the provider's own id, ``speaker_0``), start and end in milliseconds;
* its language, from the script its letters are written in (code-switching happens turn by turn
  far more than word by word in these interviews), falling back to what the provider detected;
* its text, and every word with its timing and its code-point offset into that text (karaoke);
* the spans Scribe was unsure of (``logprob`` under :data:`LOW_CONFIDENCE_LOGPROB`), as
  ``[start, end)`` code-point offsets, so a reviewer knows where to listen.

A provider that returns no words (Deepgram through the cache, Whisper) still gives turns: one per
segment, with no words and no low-confidence spans.
"""

from __future__ import annotations

import math
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

#: A word under this log-probability (about 37 % confidence) is marked for a listen.
LOW_CONFIDENCE_LOGPROB = -1.0
#: A pause this long inside a turn that has already run this long starts a new turn.
SPLIT_PAUSE_MS = 2_500
SPLIT_AFTER_MS = 30_000
#: A turn this long is split at the next sentence end even without a pause.
SPLIT_HARD_MS = 120_000
_SENTENCE_END = ("।", "॥", ".", "?", "!", "؟", "|")

#: ISO 639-3 (what Scribe reports) to the short codes both portals store (shared/languages.json).
ISO3_TO_SHORT = {
    "asm": "as",
    "ben": "bn",
    "eng": "en",
    "guj": "gu",
    "hin": "hi",
    "kan": "kn",
    "mal": "ml",
    "mar": "mr",
    "nep": "ne",
    "npi": "ne",
    "ori": "or",
    "ory": "or",
    "pan": "pa",
    "sat": "sat",
    "tam": "ta",
    "tel": "te",
    "urd": "ur",
    "raj": "raj",
    "mwr": "raj",
    "bho": "bho",
    "mai": "mai",
    "kok": "kok",
    "gom": "kok",
    "snd": "sd",
    "kas": "ks",
    "doi": "doi",
    "mni": "mni",
}

LANGUAGE_LABELS = {
    "as": "Assamese",
    "bn": "Bengali",
    "bho": "Bhojpuri",
    "doi": "Dogri",
    "en": "English",
    "gu": "Gujarati",
    "hi": "Hindi",
    "kn": "Kannada",
    "kok": "Konkani",
    "ks": "Kashmiri",
    "mai": "Maithili",
    "ml": "Malayalam",
    "mni": "Manipuri",
    "mr": "Marathi",
    "ne": "Nepali",
    "or": "Odia",
    "pa": "Punjabi",
    "raj": "Rajasthani",
    "sat": "Santali",
    "sd": "Sindhi",
    "ta": "Tamil",
    "te": "Telugu",
    "ur": "Urdu",
}

#: Unicode blocks to the language a turn in that script is most likely in.
_SCRIPTS: tuple[tuple[int, int, str], ...] = (
    (0x0900, 0x097F, "Devanagari"),
    (0x0980, 0x09FF, "Bengali"),
    (0x0A00, 0x0A7F, "Gurmukhi"),
    (0x0A80, 0x0AFF, "Gujarati"),
    (0x0B00, 0x0B7F, "Oriya"),
    (0x0B80, 0x0BFF, "Tamil"),
    (0x0C00, 0x0C7F, "Telugu"),
    (0x0C80, 0x0CFF, "Kannada"),
    (0x0D00, 0x0D7F, "Malayalam"),
    (0x0600, 0x06FF, "Arabic"),
    (0x1C50, 0x1C7F, "OlChiki"),
    (0xA8E0, 0xA8FF, "Devanagari"),
)
#: A script and the languages that are written in it; the first is the default.
SCRIPT_LANGUAGES = {
    "Devanagari": ("hi", "mr", "ne", "raj", "bho", "mai", "kok", "doi", "sat"),
    "Bengali": ("bn", "as", "mni"),
    "Gurmukhi": ("pa",),
    "Gujarati": ("gu",),
    "Oriya": ("or",),
    "Tamil": ("ta",),
    "Telugu": ("te",),
    "Kannada": ("kn",),
    "Malayalam": ("ml",),
    "Arabic": ("ur", "ks", "sd"),
    "OlChiki": ("sat",),
    "Latin": ("en",),
}


def short_language(code: Any) -> str | None:
    """A provider's language code as the short code, or None. ``hin`` -> ``hi``, ``en-IN`` -> ``en``."""
    if not isinstance(code, str) or not code.strip():
        return None
    value = code.strip().lower().replace("_", "-").split("-", 1)[0]
    if value in ISO3_TO_SHORT:
        return ISO3_TO_SHORT[value]
    if len(value) in (2, 3) and value.isalpha():
        return value
    return None


def language_label(code: str | None) -> str:
    if not code:
        return "Unknown"
    return LANGUAGE_LABELS.get(code, code)


def _script_of(char: str) -> str | None:
    point = ord(char)
    if ("a" <= char <= "z") or ("A" <= char <= "Z") or 0x00C0 <= point <= 0x024F:
        return "Latin"
    for low, high, name in _SCRIPTS:
        if low <= point <= high:
            return name
    return None


def dominant_script(text: str) -> str | None:
    counts: Counter[str] = Counter()
    for char in text:
        if unicodedata.category(char)[0] in {"L", "M"}:
            script = _script_of(char)
            if script:
                counts[script] += 1
    if not counts:
        return None
    return counts.most_common(1)[0][0]


def turn_language(text: str, detected: str | None) -> str:
    """The language of one turn: the detected language when the turn's script fits it, else the
    script's default language. A turn with no letters at all takes the detected language."""
    script = dominant_script(text)
    detected = detected or None
    if script is None:
        return detected or "und"
    options = SCRIPT_LANGUAGES.get(script, ())
    if detected in options:
        return str(detected)
    return options[0] if options else (detected or "und")


@dataclass
class Word:
    text: str
    start_ms: int
    end_ms: int
    offset: int
    logprob: float | None = None

    def as_json(self) -> dict[str, Any]:
        return {"text": self.text, "startMs": self.start_ms, "endMs": self.end_ms, "offset": self.offset}


@dataclass
class Turn:
    index: int
    speaker_key: str | None
    start_ms: int
    end_ms: int
    language: str
    text: str
    words: list[Word] = field(default_factory=list)
    low_confidence: list[list[int]] = field(default_factory=list)

    def words_json(self) -> list[dict[str, Any]]:
        return [word.as_json() for word in self.words]


def _ms(value: Any) -> int:
    try:
        number = float(value)
    except TypeError, ValueError:
        return 0
    if math.isnan(number) or number < 0:
        return 0
    return round(number * 1000)


def _logprob(value: Any) -> float | None:
    try:
        number = float(value)
    except TypeError, ValueError:
        return None
    return None if math.isnan(number) else number


def _spans(words: list[Word], text: str) -> list[list[int]]:
    """Low-confidence words merged into spans; two unsure words with only a space between are one."""
    spans: list[list[int]] = []
    for word in words:
        if word.logprob is None or word.logprob >= LOW_CONFIDENCE_LOGPROB:
            continue
        start, end = word.offset, word.offset + len(word.text)
        if spans and not text[spans[-1][1] : start].strip():
            spans[-1][1] = end
        else:
            spans.append([start, end])
    return spans


def _should_split(current: _Building, start_ms: int) -> bool:
    duration = current.end_ms - current.start_ms
    pause = start_ms - current.end_ms
    if duration >= SPLIT_AFTER_MS and pause >= SPLIT_PAUSE_MS:
        return True
    return duration >= SPLIT_HARD_MS and current.text.rstrip().endswith(_SENTENCE_END)


@dataclass
class _Building:
    speaker: str | None
    start_ms: int
    end_ms: int
    text: str = ""
    words: list[Word] = field(default_factory=list)


def turns_from_scribe(payload: dict[str, Any], *, detected: str | None = None) -> list[Turn]:
    """Turns from a Scribe (v1 or v2) response: ``{language_code, words: [...]}``."""
    detected = short_language(detected or payload.get("language_code"))
    tokens = [w for w in (payload.get("words") or []) if isinstance(w, dict)]
    has_spacing = any(t.get("type") == "spacing" for t in tokens)
    built: list[_Building] = []
    pending_space = False
    for token in tokens:
        kind = token.get("type") or "word"
        if kind == "audio_event":
            continue
        if kind == "spacing":
            pending_space = True
            continue
        text = str(token.get("text") or "").strip()
        if not text:
            continue
        speaker = token.get("speaker_id")
        speaker = str(speaker) if speaker is not None else None
        start = _ms(token.get("start"))
        end = max(start, _ms(token.get("end") if token.get("end") is not None else token.get("start")))
        current = built[-1] if built else None
        if current is None or current.speaker != speaker or _should_split(current, start):
            current = _Building(speaker, start, end)
            built.append(current)
        elif current.text and (pending_space or not has_spacing):
            current.text += " "
        current.words.append(Word(text, start, end, len(current.text), _logprob(token.get("logprob"))))
        current.text += text
        current.end_ms = max(current.end_ms, end)
        pending_space = False
    turns: list[Turn] = []
    for index, item in enumerate(built):
        turns.append(
            Turn(
                index=index,
                speaker_key=item.speaker,
                start_ms=item.start_ms,
                end_ms=item.end_ms,
                language=turn_language(item.text, detected),
                text=item.text,
                words=item.words,
                low_confidence=_spans(item.words, item.text),
            )
        )
    return turns


def turns_from_segments(segments: list[dict[str, Any]], *, detected: str | None = None) -> list[Turn]:
    """Turns from stored segments (``[{startMs, endMs, speaker, text}]``): no words, no spans."""
    detected = short_language(detected)
    turns: list[Turn] = []
    for segment in segments or []:
        if not isinstance(segment, dict):
            continue
        text = " ".join(str(segment.get("text") or "").split())
        if not text:
            continue
        speaker = segment.get("speaker")
        start = int(segment.get("startMs") or 0)
        end = max(start, int(segment.get("endMs") or 0))
        turns.append(
            Turn(
                index=len(turns),
                speaker_key=str(speaker) if speaker is not None else None,
                start_ms=start,
                end_ms=end,
                language=turn_language(text, detected),
                text=text,
            )
        )
    return turns


def turns_from_text(text: str, *, detected: str | None = None, duration_ms: int | None = None) -> list[Turn]:
    """One turn per paragraph of an untimed transcript (Whisper, or an old transcript). Every turn
    starts at 0 so no timestamp claims a moment nobody measured."""
    detected = short_language(detected)
    turns: list[Turn] = []
    for paragraph in str(text or "").split("\n\n"):
        body = " ".join(paragraph.split())
        if not body:
            continue
        turns.append(
            Turn(len(turns), None, 0, int(duration_ms or 0), turn_language(body, detected), body)
        )
    return turns


def languages_by_share(turns: list[Any]) -> list[str]:
    """Languages in order of how much was said in each (characters), most first."""
    share: Counter[str] = Counter()
    for turn in turns:
        language = getattr(turn, "language", None) or (turn.get("language") if isinstance(turn, dict) else None)
        text = getattr(turn, "text", None) or (turn.get("text") if isinstance(turn, dict) else "")
        if language and language != "und":
            share[language] += len(text or "")
    return [code for code, _ in share.most_common()]


def speaker_order(turns: list[Any]) -> list[str]:
    """Speaker keys in order of first appearance: what numbers "Speaker 1", "Speaker 2"."""
    order: list[str] = []
    for turn in turns:
        key = getattr(turn, "speaker_key", None)
        if key is None and isinstance(turn, dict):
            key = turn.get("speakerKey")
        if key is not None and key not in order:
            order.append(str(key))
    return order
