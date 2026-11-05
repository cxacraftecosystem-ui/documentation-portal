from __future__ import annotations

from typing import Any

from app.core.db import db

TYPE_SLUGS: dict[str, tuple[str, ...]] = {
    "images": ("IMAGE",),
    "videos": ("VIDEO",),
    "audios": ("AUDIO",),
    "documents": ("PDF", "DOCUMENT"),
    "other": ("OTHER",),
}
TEXT_ONLY_BRANCHES = frozenset({"artisans", "products", "tools", "questionnaire"})
TYPE_HEADS = frozenset({"by-type", "media-types"})

Totals = dict[str, tuple[int, int]]


def _number(value: Any, key: str) -> int:
    if isinstance(value, dict):
        value = value.get(key)
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


async def grouped(column: str, where: dict[str, Any], scope_media: dict[str, Any] | None) -> Totals:
    parts: list[dict[str, Any]] = [{"removedAt": None}, where]
    if scope_media:
        parts.append(scope_media)
    rows = await db.mediafile.group_by(
        by=[column], where={"AND": parts}, count=True, sum={"sizeBytes": True}
    )
    out: Totals = {}
    for row in rows:
        key = str(row.get(column))
        out[key] = (_number(row.get("_count"), "_all"), _number(row.get("_sum"), "sizeBytes"))
    return out


def _by_type(totals: Totals) -> Totals:
    out: Totals = {}
    for slug, kinds in TYPE_SLUGS.items():
        out[slug] = (
            sum(totals.get(k, (0, 0))[0] for k in kinds),
            sum(totals.get(k, (0, 0))[1] for k in kinds),
        )
    return out


def _leaf(entry: dict[str, Any]) -> str:
    return str(entry.get("path") or "").rstrip("/").rsplit("/", 1)[-1]


async def level_totals(segs: list[str], keys: list[str], scope_media: dict[str, Any] | None) -> Totals | None:
    if not segs or not keys:
        return None
    head = segs[0]
    if head in TYPE_HEADS and len(segs) == 1:
        return _by_type(await grouped("mediaType", {}, scope_media))
    if head != "by-uploader":
        return None
    if len(segs) == 1:
        return await grouped("workshopId", {"workshopId": {"in": keys}}, scope_media)
    wid = segs[1]
    if len(segs) == 2:
        return await grouped(
            "uploadedById", {"AND": [{"workshopId": wid}, {"uploadedById": {"in": keys}}]}, scope_media
        )
    uid = segs[2]
    owned = {"AND": [{"workshopId": wid}, {"uploadedById": uid}]}
    if len(segs) == 3:
        mine = (await grouped("uploadedById", owned, scope_media)).get(uid, (0, 0))
        return {key: mine if key == "media" else (0, 0) for key in keys}
    if len(segs) == 4 and segs[3] == "media":
        return _by_type(await grouped("mediaType", owned, scope_media))
    return None


async def fill(norm: str, entries: list[dict[str, Any]], scope: Any) -> None:
    folders = [e for e in entries if e.get("kind") == "folder"]
    if not folders:
        return
    segs = [s for s in norm.split("/") if s]
    keys = [_leaf(e) for e in folders]
    totals = await level_totals(segs, keys, getattr(scope, "media", None))
    for entry, key in zip(folders, keys, strict=True):
        if totals is None or (key not in totals and key == "transcripts"):
            entry["count"] = None
            entry["bytes"] = None
            continue
        count, size = totals.get(key, (0, 0))
        entry["count"] = count
        entry["bytes"] = size
