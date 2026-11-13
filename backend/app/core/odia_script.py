from __future__ import annotations

import re
from typing import Any

ODIA_CODES = frozenset({"or", "ori", "ory"})
DANDA = "।"
DOUBLE_DANDA = "॥"
NUKTA = "़"
OFFSET = 0x0200

ODIA_ASSIGNED: frozenset[int] = frozenset(
    cp
    for lo, hi in (
        (0x0B01, 0x0B03),
        (0x0B05, 0x0B0C),
        (0x0B0F, 0x0B10),
        (0x0B13, 0x0B28),
        (0x0B2A, 0x0B30),
        (0x0B32, 0x0B33),
        (0x0B35, 0x0B39),
        (0x0B3C, 0x0B44),
        (0x0B47, 0x0B48),
        (0x0B4B, 0x0B4D),
        (0x0B55, 0x0B57),
        (0x0B5C, 0x0B5D),
        (0x0B5F, 0x0B63),
        (0x0B66, 0x0B77),
    )
    for cp in range(lo, hi + 1)
)

EXCEPTIONS: dict[int, str] = {
    0x0900: "ଁ",
    0x0904: "ଅ",
    0x090D: "ଏ",
    0x090E: "ଏ",
    0x0911: "ଅ",
    0x0912: "ଓ",
    0x0929: "ନ",
    0x092F: "ୟ",
    0x0931: "ର",
    0x0934: "ଳ",
    0x0935: "ବ",
    0x093A: "େ",
    0x093B: "ୀ",
    0x0945: "େ",
    0x0946: "େ",
    0x0949: "",
    0x094A: "ୋ",
    0x094E: "େ",
    0x094F: "ୌ",
    0x0950: "ଓଁ",
    0x0951: "",
    0x0952: "",
    0x0953: "",
    0x0954: "",
    0x0955: "େ",
    0x0956: "ୁ",
    0x0957: "ୂ",
    0x0958: "କ",
    0x0959: "ଖ",
    0x095A: "ଗ",
    0x095B: "ଜ",
    0x095C: "ଡ଼",
    0x095D: "ଢ଼",
    0x095E: "ଫ",
    0x095F: "ୟ",
    0x0964: DANDA,
    0x0965: DOUBLE_DANDA,
    0x0970: ".",
    0x0971: "",
    0x0972: "ଅ",
    0x0973: "ଏ",
    0x0974: "ଏ",
    0x0975: "ଔ",
    0x0976: "ଉ",
    0x0977: "ଊ",
    0x0978: "ଡ",
    0x0979: "ଜ",
    0x097A: "ୟ",
    0x097B: "ଗ",
    0x097C: "ଜ",
    0x097D: "",
    0x097E: "ଡ",
    0x097F: "ବ",
}

NUKTA_PAIRS: dict[str, str] = {
    "क": "କ",
    "ख": "ଖ",
    "ग": "ଗ",
    "ज": "ଜ",
    "ड": "ଡ଼",
    "ढ": "ଢ଼",
    "न": "ନ",
    "फ": "ଫ",
    "य": "ୟ",
    "र": "ର",
    "ळ": "ଳ",
}


def _build() -> dict[str, str]:
    table: dict[str, str] = {}
    for cp in range(0x0900, 0x0980):
        if cp in EXCEPTIONS:
            table[chr(cp)] = EXCEPTIONS[cp]
        elif cp + OFFSET in ODIA_ASSIGNED:
            table[chr(cp)] = chr(cp + OFFSET)
        else:
            raise ValueError(f"U+{cp:04X} has no Odia mapping")
    return table


TABLE: dict[str, str] = _build()
DEVANAGARI_PATTERN = "[\u0900-\u0963\u0966-\u097f]"
DEVANAGARI = re.compile(DEVANAGARI_PATTERN)


def is_odia(code: Any) -> bool:
    if not isinstance(code, str):
        return False
    value = code.strip().lower().replace("_", "-").split("-", 1)[0]
    return value in ODIA_CODES


def has_devanagari(text: Any) -> bool:
    return isinstance(text, str) and DEVANAGARI.search(text) is not None


def to_odia_mapped(text: str) -> tuple[str, list[int]]:
    out: list[str] = []
    positions: list[int] = []
    length = 0
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in NUKTA_PAIRS and i + 1 < n and text[i + 1] == NUKTA:
            positions.append(length)
            positions.append(length)
            piece = NUKTA_PAIRS[ch]
            i += 2
        else:
            positions.append(length)
            piece = TABLE.get(ch, ch)
            i += 1
        out.append(piece)
        length += len(piece)
    positions.append(length)
    return "".join(out), positions


def to_odia(text: str) -> str:
    if not has_devanagari(text):
        return text
    return to_odia_mapped(text)[0]


def remap_words(words: Any, positions: list[int]) -> Any:
    if not isinstance(words, list):
        return words
    last = len(positions) - 1
    result: list[Any] = []
    for word in words:
        if not isinstance(word, dict):
            result.append(word)
            continue
        item = dict(word)
        if isinstance(item.get("text"), str):
            item["text"] = to_odia(item["text"])
        offset = item.get("offset")
        if isinstance(offset, int) and not isinstance(offset, bool):
            item["offset"] = positions[min(max(offset, 0), last)]
        result.append(item)
    return result


def remap_spans(spans: Any, positions: list[int]) -> Any:
    if not isinstance(spans, list):
        return spans
    last = len(positions) - 1
    result: list[Any] = []
    for span in spans:
        if (
            isinstance(span, list | tuple)
            and len(span) == 2
            and all(isinstance(v, int) and not isinstance(v, bool) for v in span)
        ):
            result.append([positions[min(max(span[0], 0), last)], positions[min(max(span[1], 0), last)]])
        else:
            result.append(span)
    return result


def result_is_odia(result: dict[str, Any], hint: Any = None) -> bool:
    return is_odia(hint) or is_odia(result.get("spokenLanguage")) or is_odia(result.get("languageCode"))


def apply_to_result(result: dict[str, Any], hint: Any = None) -> bool:
    if not isinstance(result, dict) or not result_is_odia(result, hint):
        return False
    changed = False
    for key in ("text", "formattedTranscript"):
        value = result.get(key)
        if has_devanagari(value):
            result[key] = to_odia(value)
            changed = True
    for key in ("segments", "words"):
        items = result.get(key)
        if not isinstance(items, list):
            continue
        for item in items:
            if isinstance(item, dict) and has_devanagari(item.get("text")):
                item["text"] = to_odia(item["text"])
                changed = True
    return changed
