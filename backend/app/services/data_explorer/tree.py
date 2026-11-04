from __future__ import annotations

from typing import Any

from app.services.data_explorer import downloads, slim

TREE_PAGE_MAX = 500


def transcript_path(media_id: str) -> str:
    return f"/data/media/{media_id}/transcript"


def _links(entry: dict[str, Any]) -> None:
    mid = entry.get("mediaId")
    if not mid or entry.get("kind") != "file":
        return
    if entry.get("_txMedia"):
        entry["transcriptPath"] = transcript_path(str(mid))
        return
    media = entry.get("_media")
    mime = getattr(media, "mimeType", None) if media is not None else None
    entry.update(downloads.entry_links(str(mid), str(entry.get("mediaType") or "OTHER"), mime))
    if entry.get("transcriptAvailable"):
        entry["transcriptPath"] = transcript_path(str(mid))


def _cursor(raw: str | None) -> int:
    if not raw:
        return 0
    try:
        return max(0, int(raw))
    except (TypeError, ValueError):
        return 0


def _limit(raw: int | None) -> int:
    if raw is None:
        return TREE_PAGE_MAX
    return max(1, min(TREE_PAGE_MAX, int(raw)))


async def shape(
    entries: list[dict[str, Any]],
    *,
    lean: bool,
    cursor: str | None = None,
    limit: int | None = None,
) -> tuple[list[dict[str, Any]], int, str | None]:
    for entry in entries:
        _links(entry)
    if not lean:
        wanted = [str(e["_txMedia"]) for e in entries if e.get("_txMedia") and not e.get("content")]
        if wanted:
            texts = await slim.transcript_texts(wanted)
            for entry in entries:
                if entry.get("_txMedia") and not entry.get("content"):
                    entry["content"] = texts.get(str(entry["_txMedia"]), "")
    public = [{k: v for k, v in e.items() if not k.startswith("_")} for e in entries]
    if lean:
        for entry in public:
            if entry.get("transcriptPath") and "content" in entry:
                entry.pop("content", None)
    public.sort(key=lambda e: (0 if e["kind"] == "folder" else 1, e["name"].lower()))
    total = len(public)
    start = _cursor(cursor)
    size = _limit(limit)
    page = public[start : start + size]
    following = start + size
    return page, total, (str(following) if following < total else None)
