from __future__ import annotations

from typing import Any

TYPE_TOKENS = ("images", "videos", "audios", "documents", "other", "transcripts", "text")

_MEDIA_TOKEN = {
    "IMAGE": "images",
    "VIDEO": "videos",
    "AUDIO": "audios",
    "PDF": "documents",
    "DOCUMENT": "documents",
    "OTHER": "other",
    "TRANSCRIPT": "transcripts",
}


def token_of(entry: dict[str, Any]) -> str:
    media_type = str(entry.get("mediaType") or "").upper()
    if media_type in _MEDIA_TOKEN:
        return _MEDIA_TOKEN[media_type]
    if entry.get("_fill"):
        return "transcripts"
    if entry.get("content") is not None:
        return "transcripts" if entry.get("mediaId") else "text"
    return "other"


def size_of(entry: dict[str, Any]) -> int | None:
    content = entry.get("content")
    if isinstance(content, str):
        return len(content.encode("utf-8"))
    value = entry.get("sizeBytes")
    if value is None:
        return None
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return None


def annotate(files: list[dict[str, Any]]) -> None:
    for entry in files:
        if entry is None:
            continue
        entry.setdefault("typeToken", token_of(entry))


def totals(files: list[dict[str, Any]], *, truncated: bool) -> dict[str, Any]:
    by_type: dict[str, dict[str, int]] = {t: {"files": 0, "bytes": 0} for t in TYPE_TOKENS}
    total_bytes = 0
    unknown = 0
    for entry in files:
        if entry is None:
            continue
        token = entry.get("typeToken") or token_of(entry)
        bucket = by_type.setdefault(token, {"files": 0, "bytes": 0})
        bucket["files"] += 1
        size = size_of(entry)
        if size is None:
            unknown += 1
            continue
        bucket["bytes"] += size
        total_bytes += size
    return {
        "files": sum(b["files"] for b in by_type.values()),
        "bytes": total_bytes,
        "unknownSizeFiles": unknown,
        "byType": by_type,
        "truncated": bool(truncated),
    }


def parse_include(raw: Any) -> set[str] | None:
    if raw is None:
        return None
    items = raw if isinstance(raw, (list, tuple, set)) else str(raw).split(",")
    picked = {str(t).strip().lower() for t in items if str(t).strip()}
    return picked or None
