from __future__ import annotations

import re
import unicodedata
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Any

__all__ = [
    "KINDS",
    "PERSONAL_DATA",
    "SCRUB_VERSION",
    "HeldBack",
    "Labels",
    "Scrubbed",
    "held_back",
    "identity_image",
    "label",
    "scrub",
]

SCRUB_VERSION = 2

EMAIL = "EMAIL"
NUMBER = "NUMBER"
KINDS = (EMAIL, NUMBER)
PLACEHOLDERS = {EMAIL: "[email]", NUMBER: "[number]"}

PERSONAL_DATA = {
    "email address": re.compile(r"[^\s@]+@[^\s@]+\.[^\s@]+"),
    "spoken email address": re.compile(r"\b[\w.+-]+\s+at\s+[\w-]+(?:\s+dot\s+[a-z]{2,})+\b", re.IGNORECASE),
    "Aadhaar-like number": re.compile(r"(?<!\d)\d{4}[ .-]?\d{4}[ .-]?\d{4}(?!\d)"),
    "phone-like number": re.compile(r"(?<!\d)(?:\+?\d[ -]?){10,}(?!\d)"),
    "PAN-like code": re.compile(r"(?<![A-Za-z0-9])[A-Za-z]{5}\d{4}[A-Za-z](?![A-Za-z0-9])"),
    "voter ID-like code": re.compile(r"(?<![A-Za-z0-9])[A-Za-z]{3}\d{7}(?![A-Za-z0-9])"),
    "IFSC-like code": re.compile(r"(?<![A-Za-z0-9])[A-Za-z]{4}0[A-Za-z0-9]{6}(?![A-Za-z0-9])"),
}
PATTERN_KINDS = {
    "email address": EMAIL,
    "spoken email address": EMAIL,
    "Aadhaar-like number": NUMBER,
    "phone-like number": NUMBER,
    "PAN-like code": NUMBER,
    "voter ID-like code": NUMBER,
    "IFSC-like code": NUMBER,
}

IDENTITY_IMAGE = re.compile(
    r"aadha+r|pehchan|identity[\s_-]*(?:cards?|documents?|proofs?)|(?<![a-z])id[\s_-]*(?:cards?|proofs?)(?![a-z])"
    r"|voter|(?<![a-z])pan[\s_-]*cards?(?![a-z])|passport|licen[cs]e|signature|consent"
    r"|finger ?print|thumb ?(?:print|impression)|passbook|cheque|bank ?(?:account|details|statement)"
    r"|\bifsc\b|\bupi\b",
    re.IGNORECASE,
)

NUMBER_SEPARATORS = "[ .\\-]?"
MIN_HELD_NUMBER_CHARACTERS = 6
PLACEHOLDER_TEXT = re.compile(r"\[(?:email|number)\]")


@dataclass(frozen=True)
class HeldBack:
    numbers: tuple[str, ...] = ()
    emails: tuple[str, ...] = ()


@dataclass
class Scrubbed:
    text: str
    counts: dict[str, int] = field(default_factory=lambda: dict.fromkeys(KINDS, 0))

    @property
    def kinds(self) -> list[str]:
        return [kind for kind in KINDS if self.counts.get(kind)]


def _digits(value: str | None) -> str:
    return "".join(ch for ch in str(value or "") if ch.isalnum())


def held_back(*, numbers: Iterable[str | None] = (), emails: Iterable[str | None] = ()) -> HeldBack:
    held_numbers: list[str] = []
    for raw in numbers:
        compact = _digits(raw)
        if len(compact) >= MIN_HELD_NUMBER_CHARACTERS and compact not in held_numbers:
            held_numbers.append(compact)
    held_emails = sorted({str(e).strip() for e in emails if e and "@" in str(e)})
    return HeldBack(
        numbers=tuple(sorted(held_numbers, key=lambda n: (-len(n), n))),
        emails=tuple(held_emails),
    )


def _replacement(placeholder: str, counts: dict[str, int], kind: str) -> Callable[[re.Match[str]], str]:
    def replace(found: re.Match[str]) -> str:
        counts[kind] += 1
        return placeholder + (" " if found.group()[-1] in " -" else "")

    return replace


def _number_pattern(number: str) -> re.Pattern[str]:
    body = NUMBER_SEPARATORS.join(re.escape(ch) for ch in number)
    return re.compile(rf"(?<![0-9A-Za-z])(?:\+?91{NUMBER_SEPARATORS})?{body}(?![0-9A-Za-z])", re.IGNORECASE)


def _held_patterns(held: HeldBack) -> list[tuple[re.Pattern[str], str]]:
    patterns: list[tuple[re.Pattern[str], str]] = [(_number_pattern(n), NUMBER) for n in held.numbers]
    patterns += [(re.compile(re.escape(e), re.IGNORECASE), EMAIL) for e in held.emails]
    return patterns


def scrub(text: str | None, held: HeldBack) -> Scrubbed:
    result = Scrubbed(unicodedata.normalize("NFC", str(text or "")).replace("\x00", ""))
    for name, pattern in PERSONAL_DATA.items():
        kind = PATTERN_KINDS[name]
        result.text = pattern.sub(_replacement(PLACEHOLDERS[kind], result.counts, kind), result.text)
    for pattern, kind in _held_patterns(held):
        result.text = pattern.sub(_replacement(PLACEHOLDERS[kind], result.counts, kind), result.text)
    return result


def _has_words(text: str) -> bool:
    return any(ch.isalnum() for ch in PLACEHOLDER_TEXT.sub("", text))


def _label(value: Any, held: HeldBack) -> Scrubbed | None:
    if value is None or not str(value).strip():
        return None
    result = scrub(str(value).strip(), held)
    result.text = result.text.strip()
    return result


def label(value: Any, held: HeldBack) -> str | None:
    result = _label(value, held)
    if result is None or not _has_words(result.text):
        return None
    return result.text


@dataclass
class Labels:
    held: HeldBack
    nulled: list[str] = field(default_factory=list)
    counts: dict[str, int] = field(default_factory=lambda: dict.fromkeys(KINDS, 0))

    def screen(self, path: str, value: Any) -> str | None:
        result = _label(value, self.held)
        if result is None:
            return None
        for kind, n in result.counts.items():
            self.counts[kind] = self.counts.get(kind, 0) + n
        if not _has_words(result.text):
            self.nulled.append(path)
            return None
        return result.text


def identity_image(*texts: str | None) -> bool:
    return any(IDENTITY_IMAGE.search(text or "") for text in texts)
