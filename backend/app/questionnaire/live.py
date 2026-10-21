"""THE live-media filter (DESIGN section 2): one helper, used by every reader.

A soft-removed file (``MediaFile.removedAt`` set) is invisible to the media list, search, the data
browser and dataset exports, the consolidated questionnaire, the review cascade and
``/media/orphans``, and stays restorable. Readers compose ``live_media_where()`` into their Prisma
``where``; raw SQL uses ``LIVE_MEDIA_SQL``.

IT APPLIES WHATEVER ``QUESTIONNAIRE_V2`` SAYS (audit M19, 21 Oct 2026). It used to follow the flag,
on the theory that only v2 removes anything. The backfill's 245 duplicate copies were removed while
the flag was off, so a flag-gated filter left them in the legacy readers, in "use transcripts as
answers" and, worst, in the approve cascade, which approved removed copies alongside their record.
``always`` is kept so v2 call sites need not change; it no longer alters the result.
"""

from __future__ import annotations

from typing import Any

LIVE_MEDIA_SQL = '"removedAt" IS NULL'


def live_media_where(where: dict[str, Any] | None = None, *, always: bool = True) -> dict[str, Any]:
    """``where`` narrowed to live media. Composes under AND, so it can never be overwritten by a
    caller's own ``OR``."""
    del always  # kept for the call sites; the filter is unconditional
    clause = {"removedAt": None}
    if not where:
        return clause
    return {"AND": [where, clause]}


def is_live(media: Any) -> bool:
    return (media.get("removedAt") if isinstance(media, dict) else getattr(media, "removedAt", None)) is None


#: THE live-session filter (owner decision 3, 21 Oct 2026): a soft-deleted session (``deletedAt``)
#: hides itself, its takes (``questionnaire_take_v`` joins only live sessions) and its answers from
#: every reader until a content admin restores it. Raw SQL over an alias ``i`` uses this; Prisma
#: readers compose :func:`live_session_where`.
LIVE_SESSION_SQL = 'i."deletedAt" IS NULL'


def live_session_where(where: dict[str, Any] | None = None) -> dict[str, Any]:
    """``where`` narrowed to live (not soft-deleted) sessions, under AND."""
    clause = {"deletedAt": None}
    if not where:
        return clause
    return {"AND": [where, clause]}
