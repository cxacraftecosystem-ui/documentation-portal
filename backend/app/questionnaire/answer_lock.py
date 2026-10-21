"""THE ONE ANSWER-LOCK RULE (owner decision 2, 21 Oct 2026; UNIFY section 2.3 rule 4, U9).

An answer that has text can be changed only by its author (the current revision's ``actorId``), a
LEAD researcher of the session the change is made in, or an admin (a content admin: Admin, Master
Admin, Ministry Admin). Anyone else gets ``LOCKED`` on that item of ``PUT /answers`` (error code
``ANSWER_LOCKED``) while the batch's other items still save; clients show the answer read-only with
P15, "Answered by Nandini. Only she or a lead can change it." The design's pinned rule
(``FORBIDDEN`` below Professor) stays on top of this one.

An EMPTY answer is never locked: whoever is in the session may fill it.

SWITCHED BY ``QUESTIONNAIRE_ANSWER_LOCKS`` (setting ``questionnaire_answer_locks``, default off) so
that Field Repository's live behaviour does not change before its clients can show the lock
(U3/U4). Every client must handle ``LOCKED`` before the switch is turned on in either portal; the
parity registry (``shared/questionnaire-features.json``, A11) fails the build when it is on and a
client lacks the lock.
"""

from __future__ import annotations

from typing import Any

from app.questionnaire.authoring import is_content_admin, user_id

STATUS = "LOCKED"
CODE = "ANSWER_LOCKED"
SETTING = "questionnaire_answer_locks"


def lock_message(author_name: str | None) -> str:
    """P15's inline line."""
    who = (author_name or "").strip() or "someone else"
    return f"Answered by {who}. Only they or a lead can change it."


def answer_locked(current: dict[str, Any] | None, user: Any, *, session_leads: set[str] | frozenset[str] = frozenset()) -> bool:
    """Whether ``user`` may NOT change ``current`` (a current-answer row with ``text`` and
    ``actorId``)."""
    if not current:
        return False
    if not str(current.get("text") or "").strip():
        return False
    me = user_id(user)
    author = current.get("actorId")
    if author and me and str(author) == me:
        return False
    if me and me in {str(x) for x in session_leads}:
        return False
    return not is_content_admin(user)


def locks_enabled(settings: Any) -> bool:
    return bool(getattr(settings, SETTING, False))
