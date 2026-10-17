"""THE ONE WAY A NOTICE REACHES A PERSON: the in-app activity list AND the e-mail, from one call.

    from app.services.notifications import notify

    await notify(
        person,                      # a User row (or a dict / an account id) — the recipient
        "TASK_SENT_BACK",            # the notice's kind, stored on the row; UPPER_SNAKE, never renamed
        "“Survey Bagru” has been sent back to you",         # the title: one short line
        None,                                                # the body, or None
        "/tasks",                                            # an APP PATH, or None
        email_kind=mailer.TASK_SENT_BACK,                    # the e-mail template, or None for in-app only
        email_params={...},                                  # what the template reads
        preference="emailTaskUpdates",                       # the UserPreference opt-out, or None
    )

WHAT IT DOES, IN ORDER, AND NEVER RAISES:

1. E-mail — only when ``email_kind`` is given, the person has an address, mail is configured, and
   ``preference`` (a boolean ``UserPreference`` column, opt-out: no row reads as yes) is not false.
   It goes through :func:`app.services.email_outbox.enqueue`, so the send log, retries and SES
   handling are the outbox's.
2. In-app — ALWAYS (unless ``in_app=False``), whatever happened to the e-mail: mail off, opted out,
   no address, SES down. That is the point of the list (owner decision C25): nobody misses a notice
   because e-mail is not set up. The row remembers the e-mail it was queued beside.

It never raises because the thing the notice is ABOUT has already been written; failing to tell
somebody must not turn a 201 into a 500. It returns a :class:`Delivery` saying what was written.

ADDING A NOTICE (the other streams of the programme do this): pick a kind, write a short title (the
same words both clients show; the e-mail's subject is a good default), pass a link the web can open,
and — when there is an e-mail for it — add the template to ``mailer`` (``KINDS``, ``subject_for``,
``render``) and pass ``email_kind`` + ``email_params``. Notices about a record link to its read-only
page (``/artisans/<id>``, ``/review`` for a reviewer); task notices link to ``/tasks``. A notice with
no e-mail template passes no ``email_kind`` and is in-app only. An address that has no account yet
is not a notice to a PERSON: queue it with ``email_outbox.enqueue``.

NOT A NOTICE: a password link (``credential_links``) carries a single-use secret and is not written
in-app — the person it is for cannot sign in yet, and the list must never hold a credential.

The list's API is ``app/api/routes/notifications.py``; the designer portal has the same module,
the same model and the same routes, by name.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

from app.core.config import Settings, get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

#: Stored text is clipped: a notice is a line and a short note, never a document.
MAX_TITLE_LENGTH = 200
MAX_BODY_LENGTH = 1000
MAX_LINK_LENGTH = 500


@dataclass(frozen=True)
class Delivery:
    """What one :func:`notify` call wrote. Either half may be ``None``."""

    notification: Any | None = None
    email: Any | None = None

    @property
    def in_app(self) -> bool:
        return self.notification is not None

    @property
    def emailed(self) -> bool:
        return self.email is not None


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


def _clip(text: Any, limit: int) -> str | None:
    value = str(text or "").strip()
    if not value:
        return None
    return value if len(value) <= limit else value[: limit - 1].rstrip() + "…"


def _app_path(link: Any) -> str | None:
    """Only an app path is stored: an absolute URL would send a reader off this site from a bell."""
    value = _clip(link, MAX_LINK_LENGTH)
    if value is None or not value.startswith("/") or value.startswith("//"):
        return None
    return value


async def wants_email(user_id: str, preference: str | None) -> bool:
    """The opt-out. No preference named means always (a security notice); no row means yes."""
    if not preference:
        return True
    row = await db.userpreference.find_unique(where={"userId": user_id})
    if row is None:
        return True
    value = getattr(row, preference, True)
    return True if value is None else bool(value)


async def _recipient(user: Any) -> Any | None:
    if isinstance(user, str):
        return await db.user.find_unique(where={"id": user})
    return user


async def notify(
    user: Any,
    kind: str,
    title: str,
    body: str | None = None,
    link: str | None = None,
    *,
    email_kind: str | None = None,
    email_params: dict[str, Any] | None = None,
    preference: str | None = None,
    to_address: str | None = None,
    in_app: bool = True,
    settings: Settings | None = None,
) -> Delivery:
    """Tell one person about one thing, in-app and (when it has a template) by e-mail.

    ``to_address`` sends the e-mail somewhere other than the account's address (the address it held
    before an administrator changed it); the in-app row still belongs to the account. See the module
    docstring for the rules. NEVER RAISES.
    """
    from app.services import email_outbox

    settings = settings or get_settings()
    try:
        person = await _recipient(user)
    except Exception:  # see the docstring
        logger.exception("notify: could not read the recipient of a %s notice", kind)
        return Delivery()
    user_id = _value(person, "id")
    if not user_id:
        return Delivery()

    email_row: Any | None = None
    if email_kind:
        try:
            address = (to_address or _value(person, "email") or "").strip()
            # Mail being off is ``enqueue``'s to decide (it returns None): one rule, one place.
            if address and await wants_email(str(user_id), preference):
                params = {"recipientName": _value(person, "name"), **(email_params or {})}
                email_row = await email_outbox.enqueue(
                    email_kind,
                    to_address=address,
                    recipient_id=str(user_id),
                    params=params,
                    settings=settings,
                )
        except Exception:  # see the docstring; the in-app half still goes
            logger.exception("notify: could not queue the %s e-mail for account %s", email_kind, user_id)

    row: Any | None = None
    if in_app:
        try:
            row = await db.notification.create(
                data={
                    "userId": str(user_id),
                    "kind": str(kind),
                    "title": _clip(title, MAX_TITLE_LENGTH) or str(kind),
                    "body": _clip(body, MAX_BODY_LENGTH),
                    "link": _app_path(link),
                    "emailMessageId": _value(email_row, "id") if email_row is not None else None,
                }
            )
        except Exception:  # see the docstring
            logger.exception("notify: could not write the %s notice for account %s", kind, user_id)
    return Delivery(notification=row, email=email_row)


def serialize(row: Any) -> dict[str, Any]:
    """One notice as both clients read it. ``read`` rides beside ``readAt`` so a client never has
    to infer a boolean from a nullable timestamp."""
    read_at = _value(row, "readAt")
    created_at = _value(row, "createdAt")
    return {
        "id": _value(row, "id"),
        "kind": _value(row, "kind"),
        "title": _value(row, "title"),
        "body": _value(row, "body"),
        "link": _value(row, "link"),
        "read": read_at is not None,
        "readAt": read_at.isoformat() if hasattr(read_at, "isoformat") else read_at,
        "createdAt": created_at.isoformat() if hasattr(created_at, "isoformat") else created_at,
    }
