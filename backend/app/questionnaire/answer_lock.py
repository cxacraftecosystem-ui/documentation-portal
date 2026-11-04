from __future__ import annotations

from typing import Any

from app.questionnaire.authoring import is_content_admin, user_id

STATUS = "FORBIDDEN"
CODE = "ANSWER_LOCKED"


def lock_message(author_name: str | None) -> str:
    who = (author_name or "").strip() or "someone else"
    return f"Answered by {who}. Only they or a lead can change it."


def locked_for(current: dict[str, Any] | None, user: Any, *, text: str | None = None, origin: str | None = None) -> bool:
    if not current or not str(current.get("text") or "").strip():
        return False
    if current.get("origin") == "PREFILL":
        return False
    me = user_id(user)
    if me and str(current.get("actorId") or "") == me:
        return False
    if is_content_admin(user):
        return False
    return not (text is not None and (current.get("text") or "") == text and (current.get("origin") or "") == (origin or ""))


def answer_locked(current: dict[str, Any] | None, user: Any, *, session_leads: set[str] | frozenset[str] = frozenset(),
                  text: str | None = None, origin: str | None = None) -> bool:
    if not locked_for(current, user, text=text, origin=origin):
        return False
    me = user_id(user)
    return not (me and me in {str(x) for x in session_leads})
