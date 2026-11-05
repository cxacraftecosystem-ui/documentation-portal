from __future__ import annotations

import asyncio
import contextvars
import hashlib
import re
from collections.abc import Awaitable, Callable
from typing import Any

from fastapi import HTTPException

from app.services import transcript_export, transcript_pipeline
from app.services.data_explorer import slim, stats, tree
from app.services.media_naming import unique_name

MAX_MANIFEST_FILES = 20000
MAX_WALK_DEPTH = 16
WALK_PARALLEL = 5
FILL_CHUNK = 100

TYPE_TOKEN = {
    "IMAGE": "images",
    "VIDEO": "videos",
    "AUDIO": "audios",
    "PDF": "documents",
    "DOCUMENT": "documents",
    "OTHER": "other",
}

_COMPOUND_EXTENSIONS = (".transcript.md",)

ListLevel = Callable[[str, Any], Awaitable[tuple[list[dict[str, Any]], dict[str, Any] | None]]]

_memo: contextvars.ContextVar[dict[Any, Any] | None] = contextvars.ContextVar(
    "data_explorer_walk_memo", default=None
)


def begin() -> contextvars.Token[dict[Any, Any] | None]:
    return _memo.set({})


def end(token: contextvars.Token[dict[Any, Any] | None]) -> None:
    _memo.reset(token)


async def memo(key: Any, factory: Callable[[], Awaitable[Any]]) -> Any:
    store = _memo.get()
    if store is None:
        return await factory()
    found = store.get(key)
    if found is None:
        found = asyncio.ensure_future(factory())
        store[key] = found
    try:
        return await asyncio.shield(found)
    except BaseException:
        if store.get(key) is found and found.done():
            store.pop(key, None)
        raise


def join(parent: str, name: str) -> str:
    return f"{parent}/{name}" if parent else name


def split_leaf(name: str) -> tuple[str, str]:
    for compound in _COMPOUND_EXTENSIONS:
        if name.lower().endswith(compound):
            return name[: -len(compound)], name[-len(compound) :]
    stem, dot, ext = name.rpartition(".")
    return (stem, f".{ext}") if dot and stem else (name, "")


class Walk:
    def __init__(self, list_level: ListLevel, scope: Any, include: set[str] | None) -> None:
        self.list_level = list_level
        self.scope = scope
        self.include = include
        self.files: list[dict[str, Any]] = []
        self.seen_media: set[str] = set()
        self.truncated = False
        self._sem = asyncio.Semaphore(WALK_PARALLEL)

    def wants(self, token: str) -> bool:
        return self.include is None or token in self.include

    def _emit(self, entry: dict[str, Any], rel: str, used: set[str], stem: str, ext: str) -> None:
        if len(self.files) < MAX_MANIFEST_FILES:
            entry["path"] = join(rel, unique_name(stem, ext, used))
            self.files.append(entry)
        else:
            self.truncated = True

    def emit_entries(self, entries: list[dict[str, Any]], rel: str, used: set[str]) -> None:
        for e in entries:
            if e.get("kind") == "folder":
                continue
            if "content" in e:
                self._emit_text(e, rel, used)
                continue
            self._emit_media(e, rel, used)

    def _emit_text(self, e: dict[str, Any], rel: str, used: set[str]) -> None:
        if e.get("_txMedia"):
            if not (self.wants("transcripts") or self.wants("text")):
                return
            mid = str(e["_txMedia"])
            entry: dict[str, Any] = {"content": e.get("content"), "mediaId": mid, "_tx": (mid, "legacy")}
            if not e.get("content"):
                entry["_fill"] = mid
            if e.get("originalFilename"):
                entry["originalFilename"] = e["originalFilename"]
            self._emit(entry, rel, used, *split_leaf(e["name"]))
            if e.get("_media") is not None:
                for leaf, file_entry, needs in transcript_export.bundle_entries(
                    mid, transcript_export.bundle_names(e["_media"], e.get("_nameKw"))
                ):
                    file_entry["_tx"] = (mid, "pipeline", needs)
                    self._emit(file_entry, rel, used, *split_leaf(leaf))
            return
        if self.wants("text"):
            entry = {"content": e["content"]}
            if e.get("originalFilename"):
                entry["originalFilename"] = e["originalFilename"]
            self._emit(entry, rel, used, *split_leaf(e["name"]))

    def _emit_media(self, e: dict[str, Any], rel: str, used: set[str]) -> None:
        mid = str(e["mediaId"])
        if mid in self.seen_media:
            return
        self.seen_media.add(mid)
        media_type = e.get("mediaType") or "OTHER"
        token = TYPE_TOKEN.get(media_type, "other")
        name = e["name"]
        stem, extension = split_leaf(name)
        original = e.get("originalFilename")
        size = e.get("sizeBytes")
        if self.wants(token):
            entry: dict[str, Any] = {
                "url": e.get("url"),
                "originalFilename": original,
                "mediaId": mid,
                "mediaType": media_type,
                "sizeBytes": size,
                "capturedAt": tree.captured_at(e.get("_media")),
            }
            if media_type == "AUDIO":
                entry["originalPath"] = join(rel, name)
                entry["convertToMp4"] = True
                self._emit(entry, rel, used, stem, ".mp4")
            else:
                self._emit(entry, rel, used, stem, extension)
        if e.get("transcriptAvailable") and self.wants("transcripts"):
            legacy: dict[str, Any] = {
                "content": e.get("_transcriptText") or None,
                "originalFilename": original,
                "mediaId": mid,
                "_tx": (mid, "legacy"),
            }
            if not legacy["content"]:
                legacy["_fill"] = mid
            self._emit(legacy, rel, used, *split_leaf(e.get("_transcriptName") or f"{stem}.md"))
            if e.get("_media") is not None:
                for leaf, file_entry, needs in transcript_export.bundle_entries(
                    mid, transcript_export.bundle_names(e["_media"], e.get("_nameKw"))
                ):
                    file_entry["originalFilename"] = original
                    file_entry["_tx"] = (mid, "pipeline", needs)
                    self._emit(file_entry, rel, used, *split_leaf(leaf))

    async def run(self, path: str, rel: str = "", depth: int = 0) -> None:
        if depth > MAX_WALK_DEPTH or len(self.files) >= MAX_MANIFEST_FILES:
            self.truncated = True
            return
        try:
            async with self._sem:
                entries, _ = await self.list_level(path, self.scope)
        except HTTPException:
            return
        if len(entries) >= 500:
            self.truncated = True
        used: set[str] = set()
        self.emit_entries(entries, rel, used)
        children = [
            self.run(e["path"], join(rel, e["name"]), depth + 1)
            for e in entries
            if e.get("kind") == "folder"
        ]
        del entries
        if children:
            await asyncio.gather(*children)


async def fill_texts(files: list[dict[str, Any] | None]) -> None:
    wanted = [str(f["_fill"]) for f in files if f is not None and f.get("_fill")]
    if not wanted:
        return
    texts = await slim.transcript_texts(wanted)
    for entry in files:
        if entry is None:
            continue
        mid = entry.pop("_fill", None)
        if mid is not None:
            entry["content"] = texts.get(str(mid), "")


async def settle(files: list[dict[str, Any]], *, fill: bool) -> None:
    await transcript_export.finalize_manifest(files, pipeline_on=transcript_pipeline.enabled())
    if fill:
        for start in range(0, len(files), FILL_CHUNK):
            await fill_texts(files[start : start + FILL_CHUNK])
    stats.annotate(files)


_DIGEST_SQL = 'SELECT "id", "contentSha256", "checksum" FROM "MediaFile" WHERE "id" = ANY($1::text[])'
_HEX64 = re.compile(r"[0-9a-f]{64}")
DIGEST_CHUNK = 500


def _digest(row: dict[str, Any]) -> str | None:
    for key in ("contentSha256", "checksum"):
        value = str(row.get(key) or "").strip().lower()
        if _HEX64.fullmatch(value):
            return value
    return None


def _stored_file(entry: dict[str, Any]) -> bool:
    return bool(entry.get("mediaId")) and entry.get("content") is None and not entry.get("_fill") and (
        entry.get("mediaType") in TYPE_TOKEN
    )


async def fill_digests(files: list[dict[str, Any] | None]) -> None:
    from app.core.db import db

    wanted = list(dict.fromkeys(str(f["mediaId"]) for f in files if f is not None and _stored_file(f)))
    found: dict[str, str | None] = {}
    for start in range(0, len(wanted), DIGEST_CHUNK):
        for row in await db.query_raw(_DIGEST_SQL, wanted[start : start + DIGEST_CHUNK]):
            found[str(row["id"])] = _digest(row)
    for entry in files:
        if entry is None:
            continue
        content = entry.get("content")
        if isinstance(content, str):
            entry["capturedAt"] = None
            raw = content.encode("utf-8")
            entry["sha256"] = hashlib.sha256(raw).hexdigest()
            entry["sizeBytes"] = len(raw)
            continue
        entry.setdefault("capturedAt", None)
        digest = found.get(str(entry.get("mediaId"))) if _stored_file(entry) else None
        if entry.get("convertToMp4"):
            entry["sha256"] = None
            entry["originalSha256"] = digest
        else:
            entry["sha256"] = digest
        entry.setdefault("sizeBytes", None)


def media_count(files: list[dict[str, Any]]) -> int:
    return sum(
        1
        for f in files
        if f is not None
        and f.get("mediaId")
        and f.get("content") is None
        and not f.get("_fill")
        and f.get("mediaType") != "TRANSCRIPT"
    )


async def manifest_files(
    list_level: ListLevel,
    scope: Any,
    paths: list[tuple[str, str]],
    include: set[str] | None,
    *,
    media_entries: list[dict[str, Any]] | None = None,
    fill: bool = True,
) -> tuple[list[dict[str, Any]], bool]:
    token = begin()
    try:
        walker = Walk(list_level, scope, include)
        if media_entries:
            walker.emit_entries(media_entries, "", set())
        for norm, rel in paths:
            await walker.run(norm, rel)
        await settle(walker.files, fill=fill)
        return walker.files, walker.truncated
    finally:
        end(token)
