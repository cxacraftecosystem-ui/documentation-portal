from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

PALETTE_SIZE = 8
NEUTRAL = "neutral"

PREVIEW_LINES = 3
LINE_CHARS = 160
ELLIPSIS = "…"

_NUMBERED_KEY = re.compile(r"^speaker_(\d+)$")
_NUMBERED_LABEL = re.compile(r"^Speaker (\d+)$")


def color_key(speaker_key: str | None, label: str | None = None) -> str:
    key = (speaker_key or "").strip()
    if key:
        numbered = _NUMBERED_KEY.match(key)
        if numbered:
            return f"s{int(numbered.group(1)) % PALETTE_SIZE}"
        return f"s{fnv1a(key) % PALETTE_SIZE}"
    name = " ".join(str(label or "").split())
    if not name:
        return NEUTRAL
    numbered = _NUMBERED_LABEL.match(name)
    if numbered and int(numbered.group(1)) >= 1:
        return f"s{(int(numbered.group(1)) - 1) % PALETTE_SIZE}"
    return f"s{fnv1a(name) % PALETTE_SIZE}"


def fnv1a(text: str) -> int:
    value = 0x811C9DC5
    for byte in text.encode("utf-8"):
        value ^= byte
        value = (value * 0x01000193) & 0xFFFFFFFF
    return value



@dataclass(frozen=True)
class PreviewTurn:
    speaker_key: str | None
    speaker: str | None
    text: str


_HEADING = re.compile(r"^\s*#{1,6}\s+")
_RULE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
_TABLE = re.compile(r"^\s*\|")
_QUOTE = re.compile(r"^\s*>")
_INLINE_LABEL = re.compile(r"^\s*\*\*\s*([^*\n]{1,80}?)\s*(?::\s*\*\*|\*\*\s*:)\s*(.*)$")
_BLOCK_LABEL = re.compile(r"^\s*\*\*\s*([^*\n]{1,80}?)\s*\*\*\s*(?:·\s*`[^`]*`)?\s*$")
_LEAD_WORD = re.compile(r"^\s*transcript\s*[:\-–—]?\s*(?=\*\*|$)", re.IGNORECASE)
_BULLET = re.compile(r"^\s*[-*+]\s+(?=\S)")
_CODE = re.compile(r"`([^`\n]*)`")
_EMPHASIS = re.compile(r"\*\*([^*\n]+?)\*\*|\*([^*\n]+?)\*")
_STRAY = re.compile(r"[*`]+")


def plain(text: str) -> str:
    line = _CODE.sub(r"\1", str(text or ""))
    line = _EMPHASIS.sub(lambda m: m.group(1) or m.group(2) or "", line)
    line = _STRAY.sub("", line)
    return " ".join(line.split())


def clip(text: str, limit: int = LINE_CHARS) -> str:
    if len(text) <= limit:
        return text
    cut = text[: limit - 1]
    space = cut.rfind(" ")
    if space >= limit // 2:
        cut = cut[:space]
    return cut.rstrip(" ,;:.-–—") + ELLIPSIS


def turns_from_markdown(markdown: str) -> list[PreviewTurn]:
    out: list[PreviewTurn] = []
    current_speaker: str | None = None
    current: list[str] = []
    started = False

    def flush() -> None:
        nonlocal current
        text = plain(" ".join(current))
        if text:
            out.append(PreviewTurn(None, current_speaker, text))
        current = []

    for raw in str(markdown or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw if started else _LEAD_WORD.sub("", raw)
        if not line.strip():
            continue
        if _HEADING.match(line) or _RULE.match(line) or _TABLE.match(line) or _QUOTE.match(line):
            continue
        started = True
        block = _BLOCK_LABEL.match(line)
        inline = None if block else _INLINE_LABEL.match(line)
        if block or inline:
            flush()
            label = plain((block or inline).group(1)).rstrip(":").strip()
            current_speaker = label or None
            if inline and inline.group(2).strip():
                current.append(inline.group(2))
            continue
        current.append(_BULLET.sub("", line))
    flush()
    return out


def preview(turns: list[PreviewTurn], *, max_lines: int = PREVIEW_LINES) -> dict[str, Any] | None:
    lines = []
    for turn in turns:
        text = plain(turn.text)
        if not text:
            continue
        speaker = plain(turn.speaker or "") or None
        lines.append({
            "speaker": speaker,
            "speakerKey": turn.speaker_key,
            "colorKey": color_key(turn.speaker_key, speaker),
            "text": clip(text),
        })
        if len(lines) >= max_lines:
            break
    if not lines:
        return None
    return {
        "text": " ".join(f"{line['speaker']}: {line['text']}" if line["speaker"] else line["text"] for line in lines),
        "lines": lines,
    }


def preview_markdown(markdown: str | None) -> dict[str, Any] | None:
    return preview(turns_from_markdown(markdown or ""))
