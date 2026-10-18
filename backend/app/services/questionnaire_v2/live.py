"""THE live-media filter (DESIGN section 2): one helper, used by every reader.

A soft-removed file (``MediaFile.removedAt`` set) is invisible to the media list, search, the data
browser and dataset exports, the consolidated questionnaire, the review cascade and
``/media/orphans``, and stays restorable. Readers compose ``live_media_where()`` into their Prisma
``where``; raw SQL uses ``LIVE_MEDIA_SQL``.

IT FOLLOWS ``QUESTIONNAIRE_V2``. Only v2 removes anything, and the one bulk removal that runs while
the flag is still off is the legacy backfill (DESIGN 7.3: duplicate copies), whose removals are meant
to take effect with the flip. So while the flag is off every reader sees exactly what it saw before
(and the in-memory test doubles of those readers keep working); ``always=True`` is for v2's own code.
"""

from __future__ import annotations

from typing import Any

LIVE_MEDIA_SQL = '"removedAt" IS NULL'


def _enabled() -> bool:
    from app.core.config import get_settings

    return bool(getattr(get_settings(), "questionnaire_v2", False))


def live_media_where(where: dict[str, Any] | None = None, *, always: bool = False) -> dict[str, Any]:
    """``where`` narrowed to live media. Composes under AND, so it can never be overwritten by a
    caller's own ``OR``."""
    if not (always or _enabled()):
        return where if where is not None else {}
    clause = {"removedAt": None}
    if not where:
        return clause
    return {"AND": [where, clause]}


def is_live(media: Any) -> bool:
    return (media.get("removedAt") if isinstance(media, dict) else getattr(media, "removedAt", None)) is None
