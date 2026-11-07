from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from app.core.db import db

KEYS: tuple[str, ...] = (
    "relevance",
    "name",
    "recorded",
    "added",
    "modified",
    "size",
    "duration",
    "type",
    "workshop",
    "artisan",
    "craft",
    "status",
    "folder",
)

DEFAULT_DIR: dict[str, str] = {
    "relevance": "desc",
    "name": "asc",
    "recorded": "desc",
    "added": "desc",
    "modified": "desc",
    "size": "desc",
    "duration": "desc",
    "type": "asc",
    "workshop": "asc",
    "artisan": "asc",
    "craft": "asc",
    "status": "asc",
    "folder": "asc",
}

TREE_KEYS: tuple[str, ...] = (
    "name",
    "recorded",
    "added",
    "modified",
    "size",
    "duration",
    "type",
    "workshop",
    "artisan",
    "craft",
    "status",
)
TRANSCRIPT_KEYS: tuple[str, ...] = (
    "folder",
    "name",
    "recorded",
    "added",
    "duration",
    "type",
    "workshop",
    "artisan",
    "craft",
    "status",
)
BROWSE_KEYS: tuple[str, ...] = ("name", "recorded", "added", "modified")
BROWSE_MEDIA_KEYS: tuple[str, ...] = ("name", "recorded", "added", "modified", "size", "type")

FACETS: tuple[str, ...] = ("workshop", "artisan", "craft")
_FACET_FK = {"workshop": "workshopId", "artisan": "artisanId", "craft": "craftId"}
_TIMED = {"AUDIO", "VIDEO"}

_DURATION_SQL = """
SELECT m."id" AS id,
       coalesce(
         (SELECT v."durationMs" FROM "TranscriptVersion" v
           WHERE v."mediaFileId" = m."id" AND v."supersededAt" IS NULL AND v."durationMs" IS NOT NULL
           ORDER BY v."version" DESC LIMIT 1),
         (SELECT w."durationMs" FROM "MediaWaveform" w
           WHERE w."mediaFileId" = m."id" AND w."durationMs" IS NOT NULL LIMIT 1)
       ) AS ms
  FROM "MediaFile" m
 WHERE m."id" = ANY($1::text[])
"""

_FACET_SQL = """
SELECT m."id" AS id, w."title" AS workshop, a."name" AS artisan, c."name" AS craft
  FROM "MediaFile" m
  LEFT JOIN "Workshop" w ON w."id" = m."workshopId"
  LEFT JOIN "Artisan" a ON a."id" = m."artisanId"
  LEFT JOIN "Craft" c ON c."id" = m."craftId"
 WHERE m."id" = ANY($1::text[])
"""

_CHUNK = 500


@dataclass(frozen=True)
class Sort:
    key: str
    dir: str

    @property
    def text(self) -> str:
        return f"{self.key}:{self.dir}"

    def public(self) -> dict[str, str]:
        return {"key": self.key, "dir": self.dir}


TREE_DEFAULT = Sort("name", "asc")
TRANSCRIPT_DEFAULT = Sort("folder", "asc")
BROWSE_DEFAULT = Sort("added", "desc")


def parse(raw: str | None, allowed: Sequence[str], default: Sort) -> Sort:
    if raw is None or not isinstance(raw, str) or not raw.strip():
        return default
    key, sep, direction = raw.strip().partition(":")
    key = key.strip()
    direction = direction.strip().lower() if sep else DEFAULT_DIR.get(key, "")
    if key not in allowed or direction not in ("asc", "desc"):
        raise ValueError(f"sort takes <key>:<asc|desc> with key one of {', '.join(allowed)}")
    if key == "relevance" and direction != "desc":
        raise ValueError("relevance sorts desc only")
    return Sort(key, direction)


def _get(source: Any, name: str) -> Any:
    if source is None:
        return None
    if isinstance(source, dict):
        return source.get(name)
    return getattr(source, name, None)


def stamp(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        moment = value if value.tzinfo else value.replace(tzinfo=UTC)
        return moment.timestamp()
    if isinstance(value, str) and value:
        try:
            parsed = datetime.fromisoformat(value)
        except ValueError:
            return None
        return stamp(parsed)
    return None


def _enum(value: Any) -> str | None:
    value = getattr(value, "value", value)
    text = str(value or "").strip()
    return text.split(".")[-1].upper() if text else None


def record_dates(record: Any) -> dict[str, Any]:
    if record is None:
        return {}
    recorded = None
    for name in ("startDate", "date", "recordedAt", "createdAt"):
        recorded = _get(record, name)
        if recorded is not None:
            break
    return {
        "_recorded": recorded,
        "_added": _get(record, "createdAt"),
        "_modified": _get(record, "updatedAt"),
    }


def _media(entry: dict[str, Any]) -> Any:
    return entry.get("_media")


def _is_file(entry: dict[str, Any]) -> bool:
    return entry.get("kind") == "file"


def _file_type(entry: dict[str, Any]) -> str | None:
    if not _is_file(entry):
        return str(entry.get("recordType") or "") or None
    kind = entry.get("mediaType") or _enum(_get(_media(entry), "mediaType"))
    if kind:
        return str(kind).upper()
    return "TEXT" if entry.get("content") is not None or not entry.get("mediaId") else None


def _date(entry: dict[str, Any], which: str) -> float | None:
    media = _media(entry)
    if media is not None:
        if which == "recorded":
            return stamp(_get(media, "recordedAt")) or stamp(_get(media, "createdAt"))
        return stamp(_get(media, "createdAt" if which == "added" else "updatedAt"))
    if which == "recorded" and entry.get("capturedAt"):
        return stamp(entry.get("capturedAt"))
    return stamp(entry.get(f"_{which}"))


def _size(entry: dict[str, Any]) -> float | None:
    value = entry.get("sizeBytes") if _is_file(entry) else entry.get("bytes")
    return float(value) if isinstance(value, int | float) else None


def _text(value: Any) -> str | None:
    text = str(value or "").strip()
    return text.casefold() if text else None


def value_of(entry: dict[str, Any], key: str) -> Any:
    if key == "name":
        return _text(entry.get("name")) or ""
    if key in ("recorded", "added", "modified"):
        return _date(entry, key)
    if key == "size":
        return _size(entry)
    if key == "duration":
        value = entry.get("_durationMs")
        return float(value) if isinstance(value, int | float) else None
    if key == "type":
        return _text(_file_type(entry))
    if key in FACETS:
        return _text((entry.get("_facets") or {}).get(key))
    if key == "status":
        return _text(_enum(_get(_media(entry), "status")) or entry.get("_status"))
    if key == "folder":
        path = str(entry.get("path") or "")
        return path.rsplit("/", 1)[0].casefold() if "/" in path else ""
    return None


def _ident(entry: dict[str, Any]) -> str:
    return str(entry.get("mediaId") or entry.get("id") or entry.get("path") or "")


def order(
    entries: list[dict[str, Any]],
    sort: Sort,
    *,
    folders_first: bool = True,
    value: Callable[[dict[str, Any], str], Any] = value_of,
) -> list[dict[str, Any]]:
    out = sorted(entries, key=lambda e: (_text(e.get("name")) or "", _ident(e)))
    if sort.key != "name" or sort.dir == "desc":
        present = [e for e in out if value(e, sort.key) is not None]
        missing = [e for e in out if value(e, sort.key) is None]
        present.sort(key=lambda e: value(e, sort.key), reverse=sort.dir == "desc")
        out = present + missing
    if folders_first:
        out.sort(key=lambda e: 0 if e.get("kind") == "folder" else 1)
    return out


def _distinct(values: list[Any]) -> int:
    return len({v for v in values if v is not None})


def available(entries: list[dict[str, Any]], allowed: Sequence[str], *, sort: Sort | None = None) -> list[str]:
    files = [e for e in entries if _is_file(e)]
    out: list[str] = []
    for key in allowed:
        if key == "name":
            keep = True
        elif key == "folder":
            keep = _distinct([value_of(e, "folder") for e in entries]) > 1
        elif key in ("recorded", "added", "modified", "size"):
            keep = any(value_of(e, key) is not None for e in entries)
        elif key == "duration":
            keep = any(_file_type(e) in _TIMED for e in files)
        elif key == "type":
            keep = _distinct([_file_type(e) for e in files]) > 1 or _distinct(
                [_file_type(e) for e in entries if not _is_file(e)]
            ) > 1
        elif key in FACETS:
            keep = _distinct([_get(_media(e), _FACET_FK[key]) for e in files]) > 1
        elif key == "status":
            keep = _distinct([_enum(_get(_media(e), "status")) for e in files]) > 1
        else:
            keep = False
        if keep or (sort is not None and sort.key == key):
            out.append(key)
    return out


def _media_ids(entries: list[dict[str, Any]], timed: bool = False) -> list[str]:
    ids: list[str] = []
    for entry in entries:
        if not _is_file(entry) or not entry.get("mediaId"):
            continue
        if timed and _file_type(entry) not in _TIMED:
            continue
        ids.append(str(entry["mediaId"]))
    return list(dict.fromkeys(ids))


async def durations(ids: list[str]) -> dict[str, int]:
    out: dict[str, int] = {}
    for start in range(0, len(ids), _CHUNK):
        for row in await db.query_raw(_DURATION_SQL, ids[start : start + _CHUNK]):
            if row.get("ms") is not None:
                out[str(row["id"])] = int(row["ms"])
    return out


async def facets(ids: list[str]) -> dict[str, dict[str, str | None]]:
    out: dict[str, dict[str, str | None]] = {}
    for start in range(0, len(ids), _CHUNK):
        for row in await db.query_raw(_FACET_SQL, ids[start : start + _CHUNK]):
            out[str(row["id"])] = {key: row.get(key) for key in FACETS}
    return out


async def prepare(entries: list[dict[str, Any]], sort: Sort) -> None:
    if sort.key == "duration":
        found = await durations(_media_ids(entries, timed=True))
        for entry in entries:
            if entry.get("mediaId") and str(entry["mediaId"]) in found:
                entry["_durationMs"] = found[str(entry["mediaId"])]
    elif sort.key in FACETS:
        found_facets = await facets(_media_ids(entries))
        for entry in entries:
            if entry.get("mediaId") and str(entry["mediaId"]) in found_facets:
                entry["_facets"] = found_facets[str(entry["mediaId"])]
