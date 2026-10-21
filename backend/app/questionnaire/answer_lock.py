"""THE ONE ANSWER-LOCK RULE (owner decision 2, 21 Oct 2026; UNIFY section 2.3 rule 4, U9).

An answer that has text can be changed only by its author (the current revision's ``actorId``), a
LEAD researcher of the session the change is made in, or an admin (a content admin: Admin, Master
Admin, Ministry Admin). Anyone else gets that item of ``PUT /answers`` refused while the batch's
other items still save: status ``FORBIDDEN`` (which every client already reads as "not yours to
change", so builds that predate the lock refuse it too) with ``code: ANSWER_LOCKED``; clients show
the answer read-only with P15, "Answered by Nandini. Only they or a lead can change it."

Not locked: an empty answer (whoever is in the session may fill it); the identical text sent again
(that is UNCHANGED); a PREFILL copied from the artisan's profile, which is nobody's account (the
prefill rule already keeps a PREFILL from overwriting anything typed). The design's pinned rule
(``FORBIDDEN`` below Professor) stays on top of this one.

This module is the rule; ``answers.py`` asks it per item and asks whether the caller leads the
session only when an item meets the lock.
"""

from __future__ import annotations

from typing import Any

from app.questionnaire.authoring import is_content_admin, user_id

#: The per-item status of a locked answer, and its ``code``.
STATUS = "FORBIDDEN"
CODE = "ANSWER_LOCKED"


def lock_message(author_name: str | None) -> str:
    """P15's inline line."""
    who = (author_name or "").strip() or "someone else"
    return f"Answered by {who}. Only they or a lead can change it."


def locked_for(current: dict[str, Any] | None, user: Any, *, text: str | None = None, origin: str | None = None) -> bool:
    """Whether ``user`` meets the lock on ``current`` (a current-answer row with ``text``, ``origin``
    and ``actorId``) when sending ``text``/``origin``, before asking whether they lead the session."""
    if not current or not str(current.get("text") or "").strip():
        return False
    if current.get("origin") == "PREFILL":
        return False
    me = user_id(user)
    if me and str(current.get("actorId") or "") == me:
        return False
    if is_content_admin(user):
        return False
    # The identical text sent again is UNCHANGED, never a lock.
    return not (text is not None and (current.get("text") or "") == text and (current.get("origin") or "") == (origin or ""))


def answer_locked(current: dict[str, Any] | None, user: Any, *, session_leads: set[str] | frozenset[str] = frozenset(),
                  text: str | None = None, origin: str | None = None) -> bool:
    """The whole rule, with the session's LEAD researchers given."""
    if not locked_for(current, user, text=text, origin=origin):
        return False
    me = user_id(user)
    return not (me and me in {str(x) for x in session_leads})
