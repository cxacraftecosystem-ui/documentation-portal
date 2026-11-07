from __future__ import annotations

from typing import Any

LIVE_MEDIA_SQL = '"removedAt" IS NULL'


def live_media_where(where: dict[str, Any] | None = None, *, always: bool = True) -> dict[str, Any]:
    del always
    clause = {"removedAt": None}
    if not where:
        return clause
    return {"AND": [where, clause]}


def is_live(media: Any) -> bool:
    return (media.get("removedAt") if isinstance(media, dict) else getattr(media, "removedAt", None)) is None


LIVE_SESSION_SQL = 'i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL'


def live_session_where(where: dict[str, Any] | None = None) -> dict[str, Any]:
    clause = {"deletedAt": None, "mergedIntoId": None}
    if not where:
        return clause
    return {"AND": [where, clause]}
