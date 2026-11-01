"""List previews and speaker colours: the two small things every list and timeline draws.

PREVIEW. A list row (the recordings list, a media card, a session's takes) shows the first few
things said, as PLAIN TEXT with the speaker as a separate label: never markdown, so no client has to
render (or forget to strip) ``**Speaker 1:**``. The server builds it once from the stored turns
(mapped names when someone named the speakers) or, for a recording transcribed before the turn
pipeline, from the stored markdown transcript. ``shared/transcript-contract.json`` ``fixtures.preview``
fixes the exact output; a client that previews offline reproduces it.

COLOUR. Each diarized speaker gets one colour, used for their timeline marks and their label in the
transcript. The colour is a function of the speaker KEY only (``speaker_0`` ...), so it never moves
when someone renames a speaker, re-orders a list or opens a different version.
``fixtures.speakerColor`` fixes the rule; ``speakerPalette`` holds the light and dark values.

Pure: no Prisma, no FastAPI. Part of the shared transcription package (``shared/transcription.sha256``).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

#: How many palette slots there are (``s0`` .. ``s7``); the contract's ``speakerPalette`` has one each.
PALETTE_SIZE = 8
#: The key of a turn with no speaker (a solo recording, or text before diarization).
NEUTRAL = "neutral"

#: At most this many speaker lines in a preview, and this many characters in each.
PREVIEW_LINES = 3
LINE_CHARS = 160
ELLIPSIS = "…"

_NUMBERED_KEY = re.compile(r"^speaker_(\d+)$")
_NUMBERED_LABEL = re.compile(r"^Speaker (\d+)$")


def color_key(speaker_key: str | None, label: str | None = None) -> str:
    """``s0`` .. ``s7`` for a speaker, ``neutral`` for none.

    * a provider key ``speaker_<n>`` -> ``s<n mod 8>``;
    * no key but a default label ``Speaker <n>`` (a pre-pipeline transcript) -> ``s<(n-1) mod 8>``,
      so "Speaker 1" there matches ``speaker_0`` here;
    * any other key, or a label alone -> FNV-1a (32-bit) over its UTF-8 bytes, mod 8;
    * nothing at all -> ``neutral``.
    """
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
    """32-bit FNV-1a over UTF-8: trivial to reproduce in TypeScript and Kotlin."""
    value = 0x811C9DC5
    for byte in text.encode("utf-8"):
        value ^= byte
        value = (value * 0x01000193) & 0xFFFFFFFF
    return value


# --- Preview ----------------------------------------------------------------------------------


@dataclass(frozen=True)
class PreviewTurn:
    speaker_key: str | None
    speaker: str | None  # the label: mapped name, else "Speaker 1"; None for an unlabelled line
    text: str


_HEADING = re.compile(r"^\s*#{1,6}\s+")
_RULE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
_TABLE = re.compile(r"^\s*\|")
_QUOTE = re.compile(r"^\s*>")
#: ``**Name:** text`` / ``**Name**: text`` (the legacy shape, format.py) on one line.
_INLINE_LABEL = re.compile(r"^\s*\*\*\s*([^*\n]{1,80}?)\s*(?::\s*\*\*|\*\*\s*:)\s*(.*)$")
#: ``**Name** · `00:00:04``` or ``**Name**`` alone (render.py's shape): the text is on the next lines.
_BLOCK_LABEL = re.compile(r"^\s*\*\*\s*([^*\n]{1,80}?)\s*\*\*\s*(?:·\s*`[^`]*`)?\s*$")
#: A leading "Transcript" word the old exports put before the first label.
_LEAD_WORD = re.compile(r"^\s*transcript\s*[:\-–—]?\s*(?=\*\*|$)", re.IGNORECASE)
_BULLET = re.compile(r"^\s*[-*+]\s+(?=\S)")
_CODE = re.compile(r"`([^`\n]*)`")
_EMPHASIS = re.compile(r"\*\*([^*\n]+?)\*\*|\*([^*\n]+?)\*")
_STRAY = re.compile(r"[*`]+")


def plain(text: str) -> str:
    """One line of text with markdown emphasis, code ticks and runs of whitespace removed."""
    line = _CODE.sub(r"\1", str(text or ""))
    line = _EMPHASIS.sub(lambda m: m.group(1) or m.group(2) or "", line)
    line = _STRAY.sub("", line)
    return " ".join(line.split())


def clip(text: str, limit: int = LINE_CHARS) -> str:
    """``text`` cut to ``limit`` code points at a word boundary, with an ellipsis when cut."""
    if len(text) <= limit:
        return text
    cut = text[: limit - 1]
    space = cut.rfind(" ")
    if space >= limit // 2:
        cut = cut[:space]
    return cut.rstrip(" ,;:.-–—") + ELLIPSIS


def turns_from_markdown(markdown: str) -> list[PreviewTurn]:
    """A stored markdown transcript (legacy ``**Speaker 1:** ...`` or render.py's blocks) as turns.

    Headings, tables (render.py's header row), rules and ``>`` quote lines (the English under a
    bilingual copy) are not speech and are skipped. Lines with no label join the turn above.
    """
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
    """``{text, lines:[{speaker, speakerKey, colorKey, text}]}`` or None when nothing was said.

    ``text`` is the same lines on one line, ``"Speaker: words"`` joined by a space: for a client
    that shows one plain string (an accessibility label, a notification).
    """
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
