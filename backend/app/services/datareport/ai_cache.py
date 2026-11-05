from __future__ import annotations

import hashlib
import re
from collections.abc import Awaitable, Callable, Iterable
from typing import Any

from app.services.datareport.contract import Link, ReportContext

SHEET_NAME_MAX = 31
_SHEET_BAD = re.compile(r"[\[\]:*?/\\]")
_SPACE = re.compile(r"\s+")
_QUOTES = "\"'“”‘’«»"


def value(obj: Any, key: str, default: Any = None) -> Any:
    if obj is None:
        return default
    found = obj.get(key, default) if isinstance(obj, dict) else getattr(obj, key, default)
    return default if found is None else found


def items(ctx: ReportContext, key: str) -> list[Any]:
    return list(ctx.records(key) or [])


def fingerprint(ids: Iterable[str]) -> str:
    digest = hashlib.sha256("\x00".join(sorted({str(i) for i in ids if i})).encode())
    return digest.hexdigest()[:24]


async def cached(
    ctx: ReportContext, name: str, ids: Iterable[str], factory: Callable[[], Awaitable[Any]]
) -> Any:
    key = f"ai:{name}:{ctx.portal}:{ctx.data_version}:{fingerprint(ids)}"
    if ctx.cache is None:
        return await factory()
    return await ctx.cache(key, factory)


def media_ids(ctx: ReportContext) -> list[str]:
    return list(dict.fromkeys(str(value(m, "id")) for m in items(ctx, "media") if value(m, "id")))


def transcribed(ctx: ReportContext) -> list[Any]:
    from app.services import transcript_export

    return [m for m in items(ctx, "media") if value(m, "id") and transcript_export.has_text(m)]


def normalise(text: str | None) -> str:
    return _SPACE.sub(" ", str(text or "").strip().strip(_QUOTES).casefold()).strip()


def find_start(turns: list[dict[str, Any]], *needles: str | None) -> int | None:
    wanted = [normalise(n) for n in needles if normalise(n)]
    if not wanted:
        return None
    for field in ("text", "english"):
        for needle in wanted:
            for turn in turns:
                if needle in normalise(turn.get(field)):
                    start = turn.get("startMs")
                    return int(start) if start is not None else None
    for needle in wanted:
        probe = needle[:60]
        if len(probe) < 12:
            continue
        for turn in turns:
            if probe in normalise(turn.get("text")) or probe in normalise(turn.get("english")):
                start = turn.get("startMs")
                return int(start) if start is not None else None
    return None


def artisan_names(ctx: ReportContext) -> dict[str, str]:
    return {
        str(value(a, "id")): str(value(a, "name", ""))
        for a in items(ctx, "artisans")
        if value(a, "id")
    }


def media_artisan(media: Any, names: dict[str, str]) -> str:
    artisan = value(media, "artisan")
    if artisan is not None and value(artisan, "name"):
        return str(value(artisan, "name"))
    return names.get(str(value(media, "artisanId", "")), "")


def say(ctx: ReportContext, key: str, default: str, **values: Any) -> str:
    text = ctx.t(key, default) if ctx.t is not None else default
    try:
        return text.format(**values) if values else text
    except KeyError, IndexError, ValueError:
        return default.format(**values) if values else default


def options_of(ctx: ReportContext | None) -> tuple[str, bool]:
    if ctx is None:
        return "both", True
    return ctx.options.answers, bool(ctx.options.links)


def file_label(media: Any) -> str:
    return str(value(media, "originalFilename") or value(media, "file") or value(media, "id") or "")


def play(
    ctx: ReportContext, media_id: str, start_ms: int | None, label: str = "Play from"
) -> Link | None:
    if not ctx.options.links:
        return None
    return Link(label, ctx.links.play_from(media_id, int(start_ms or 0)))


def open_media(ctx: ReportContext, media_id: str, label: str = "Open in portal") -> Link | None:
    if not ctx.options.links:
        return None
    return Link(label, ctx.links.open_media(media_id))


def sheet_name(base: str, taken: set[str], prefix: str = "") -> str:
    clean = _SPACE.sub(" ", _SHEET_BAD.sub(" ", str(base or ""))).strip().strip("'") or "Sheet"
    head = f"{prefix}{clean}"[:SHEET_NAME_MAX].rstrip()
    name = head
    suffix = 2
    while name.casefold() in taken:
        tail = f" ({suffix})"
        name = f"{head[: SHEET_NAME_MAX - len(tail)].rstrip()}{tail}"
        suffix += 1
    taken.add(name.casefold())
    return name


def chunks(ids: list[str], size: int) -> Iterable[list[str]]:
    for start in range(0, len(ids), size):
        yield ids[start : start + size]
