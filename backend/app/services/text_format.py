from __future__ import annotations

import re
from typing import Any

SMALL_WORDS: frozenset[str] = frozenset(
    {
        "a",
        "an",
        "and",
        "as",
        "at",
        "but",
        "by",
        "for",
        "from",
        "in",
        "into",
        "nor",
        "of",
        "on",
        "or",
        "over",
        "per",
        "the",
        "to",
        "up",
        "via",
        "vs",
        "with",
    }
)

APOSTROPHES = "'’"

_LATIN = "A-Za-zÀ-ɏ"
_WORD_RE = re.compile(f"[0-9{_LATIN}{APOSTROPHES}]+")
_LATIN_RE = re.compile(f"[{_LATIN}]")

_MAX_ACRONYM_LENGTH = 4


def _capitalise(word: str) -> str:
    if len(word) > 2 and word[1] in APOSTROPHES:
        return word[0].upper() + word[1] + word[2].upper() + word[3:]
    return word[0].upper() + word[1:]


def _case_word(word: str, *, is_first: bool, is_last: bool, shouting: bool = False) -> str:
    if not _LATIN_RE.search(word):
        return word
    if any(character.isdigit() for character in word):
        return word
    if not shouting and any(character.isupper() for character in word[1:]):
        return word
    lowered = word.lower()
    if lowered in SMALL_WORDS and not is_first and not is_last:
        return lowered
    return _capitalise(lowered)


def _is_shouting(text: str) -> bool:
    if any(character.islower() for character in text):
        return False
    if not any(character.isupper() for character in text):
        return False
    words = _WORD_RE.findall(text)
    if len(words) > 1:
        return True
    return bool(words) and len(words[0]) > _MAX_ACRONYM_LENGTH


def title_case(value: str) -> str:
    if not isinstance(value, str):
        return value
    text = value.strip()
    if not text or not _LATIN_RE.search(text):
        return text

    matches = list(_WORD_RE.finditer(text))
    if not matches:
        return text

    shouting = _is_shouting(text)

    pieces: list[str] = []
    cursor = 0
    last_index = len(matches) - 1
    for index, match in enumerate(matches):
        pieces.append(text[cursor : match.start()])
        pieces.append(
            _case_word(
                match.group(0), is_first=index == 0, is_last=index == last_index, shouting=shouting
            )
        )
        cursor = match.end()
    pieces.append(text[cursor:])
    return "".join(pieces)


def title_case_fields(data: dict[str, Any], fields: frozenset[str] | set[str]) -> dict[str, Any]:
    for key in fields:
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            data[key] = title_case(value)
    return data


def title_case_rule() -> dict[str, Any]:
    return {
        "version": 1,
        "smallWords": sorted(SMALL_WORDS),
        "wordSeparators": [" ", "-", "/", ",", ".", "(", ")", "&", ":", ";"],
        "wordCharacters": "0-9A-Za-zÀ-ɏ plus apostrophes (' and ’)",
        "smallWordsCapitalisedWhen": ["first", "last"],
        "preserveWordWhen": [
            "contains a digit (identifier such as PMV001)",
            "contains an uppercase letter after the first character (ABC, McDonald, O'Brien)",
            "contains no Latin letters (Devanagari, Gujarati, and other scripts)",
        ],
        "trimsSurroundingWhitespace": True,
        "idempotent": True,
    }
