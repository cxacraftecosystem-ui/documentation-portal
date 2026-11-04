from __future__ import annotations

import functools
from typing import Any

from app.core.db import db

HEAVY_COLUMNS = frozenset({"transcriptText", "transcriptSummary", "transcriptSegments"})

HAS_TRANSCRIPT_SQL = "(\"transcriptText\" IS NOT NULL AND \"transcriptText\" <> '')"
_FLAG_SQL = 'SELECT "id" FROM "MediaFile" WHERE "id" = ANY($1::text[]) AND ' + HAS_TRANSCRIPT_SQL
_SIZE_SQL = (
    'SELECT "id", octet_length("transcriptText")::bigint AS "bytes" FROM "MediaFile" '
    'WHERE "id" = ANY($1::text[])'
)
_TEXT_SQL = 'SELECT "id", "transcriptText" FROM "MediaFile" WHERE "id" = ANY($1::text[])'
_CHUNK = 500


@functools.cache
def listing_model() -> Any:
    from pydantic import create_model

    from prisma import bases, models

    source = models.MediaFile
    fields = {
        name: (info.annotation, info)
        for name, info in source.model_fields.items()
        if name not in HEAVY_COLUMNS
    }
    return create_model("MediaListing", __base__=bases.BaseMediaFile, **fields)


def actions() -> Any:
    return listing_model().prisma(db)


def _set(row: Any, name: str, value: Any) -> None:
    try:
        setattr(row, name, value)
    except (AttributeError, TypeError, ValueError):
        object.__setattr__(row, name, value)


async def transcript_flags(ids: list[str]) -> set[str]:
    wanted = list(dict.fromkeys(str(i) for i in ids if i))
    found: set[str] = set()
    for start in range(0, len(wanted), _CHUNK):
        rows = await db.query_raw(_FLAG_SQL, wanted[start : start + _CHUNK])
        found |= {str(row["id"]) for row in rows}
    return found


async def mark_transcripts(rows: list[Any]) -> list[Any]:
    if not rows:
        return rows
    flagged = await transcript_flags([str(r.id) for r in rows])
    for row in rows:
        _set(row, "hasTranscript", str(row.id) in flagged)
    return rows


async def transcript_sizes(ids: list[str]) -> dict[str, int]:
    wanted = list(dict.fromkeys(str(i) for i in ids if i))
    out: dict[str, int] = {}
    for start in range(0, len(wanted), _CHUNK):
        for row in await db.query_raw(_SIZE_SQL, wanted[start : start + _CHUNK]):
            if row.get("bytes") is not None:
                out[str(row["id"])] = int(row["bytes"])
    return out


async def transcript_texts(ids: list[str]) -> dict[str, str]:
    wanted = list(dict.fromkeys(str(i) for i in ids if i))
    out: dict[str, str] = {}
    for start in range(0, len(wanted), _CHUNK):
        for row in await db.query_raw(_TEXT_SQL, wanted[start : start + _CHUNK]):
            text = str(row.get("transcriptText") or "")
            if text.strip():
                out[str(row["id"])] = text
    return out


async def find_media(**kwargs: Any) -> list[Any]:
    rows = await actions().find_many(**kwargs)
    return await mark_transcripts(list(rows))


async def find_one(**kwargs: Any) -> Any | None:
    row = await actions().find_first(**kwargs)
    if row is None:
        return None
    await mark_transcripts([row])
    return row


def has_transcript(row: Any) -> bool:
    flag = getattr(row, "hasTranscript", None)
    if flag is not None:
        return bool(flag)
    return bool(str(getattr(row, "transcriptText", None) or "").strip())


def live(where: dict[str, Any] | None) -> dict[str, Any]:
    clause = {"removedAt": None}
    if not where:
        return clause
    return {"AND": [where, clause]}
