from __future__ import annotations

import json
from typing import Any

MAX_RICH_CHARS = 200_000


def _spans_text(spans: Any) -> str:
    out: list[str] = []
    for span in spans or ():
        if isinstance(span, str):
            out.append(span)
        elif isinstance(span, dict):
            out.append(str(span.get("text") or ""))
    return "".join(out).replace("\n", " ")


def parse(raw: Any) -> list[dict[str, Any]] | None:
    if raw is None:
        return None
    value = raw
    if isinstance(raw, str):
        stripped = raw.strip()
        if not stripped.startswith(("{", "[")):
            return None
        try:
            value = json.loads(stripped)
        except ValueError:
            return None
    blocks = value.get("blocks") if isinstance(value, dict) else value
    if not isinstance(blocks, list):
        return None
    return [b if isinstance(b, dict) else {"kind": "PARAGRAPH", "spans": [str(b)]} for b in blocks]


def to_plain(raw: Any) -> str:
    blocks = parse(raw)
    if blocks is None:
        return str(raw or "").strip()
    lines: list[str] = []
    ordinal = 0
    for block in blocks:
        kind = str(block.get("kind") or "PARAGRAPH").upper()
        level = block.get("level")
        indent = "  " * (int(level) if isinstance(level, int) and not isinstance(level, bool) and kind.endswith("_ITEM") else 0)
        if kind == "IMAGE":
            ordinal = 0
            caption = _spans_text(block.get("spans")).strip()
            if caption:
                lines.append(caption)
            continue
        if kind == "TABLE":
            ordinal = 0
            for row in block.get("rows") or ():
                lines.append(" | ".join(_spans_text(cell).strip() for cell in (row or ())))
            continue
        text = _spans_text(block.get("spans")).strip()
        if kind == "ORDERED_ITEM":
            ordinal += 1
            lines.append(f"{indent}{ordinal}. {text}")
            continue
        ordinal = 0
        lines.append(f"{indent}• {text}" if kind == "BULLET_ITEM" else text)
    return "\n".join(lines).strip()


def normalise(text: str | None, rich: Any) -> tuple[str, str | None]:
    blocks = parse(rich) if rich is not None else None
    stored = None
    if blocks is not None:
        stored = json.dumps({"blocks": blocks}, separators=(",", ":"), ensure_ascii=False)
        if len(stored) > MAX_RICH_CHARS:
            stored = None
    if (text or "").strip():
        return text or "", stored
    return (to_plain({"blocks": blocks}) if blocks is not None else ""), stored
