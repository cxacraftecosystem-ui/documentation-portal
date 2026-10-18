"""THE E-MAIL OUTBOX: queueing a message, draining the queue, and the send log.

A request never talks to SES. It writes one ``EmailMessage`` row (:func:`enqueue`) and answers; the
queue worker (``app/worker.py``, the same loop that drains the media queue) calls
:func:`process_next_email_jobs` on every pass, which claims due rows with a compare-and-set, renders
them, hands them to :mod:`app.services.mailer`, and records the outcome on the row. That row IS the
send log: status, attempts, ``sentAt``, SES's message id, or the error code that stopped it.

A transient refusal is retried after 1, 2, 4, 8 … minutes (capped at an hour) until ``maxAttempts``
is spent; a permanent one fails at once. A worker that died holding a row leaves it SENDING, and
:func:`recover_stale` hands it back after :data:`STALE_SENDING_AFTER`.

ONE KIND CARRIES A SECRET: ``PASSWORD_LINK``, a single-use set-password link. It is never in
``params``; :func:`enqueue` takes it as ``secret`` and stores it Fernet-sealed in ``EmailMessage.sealed``
(``managed_secrets.encrypt``), :func:`_send_one` opens it only to render, and the seal is cleared the
moment the row reaches SENT or FAILED. A link whose expiry passed while it waited is failed, not sent.
No log line carries an address, a body or a link: the row id, the kind and the recipient's ACCOUNT id
only.

The task notices honour ``UserPreference.emailTaskUpdates`` (opt-out: no row reads as yes). The
access e-mail is sent only when the administrator who admitted the address asked for it.

So do the review and sharing notices (2026-10-20): a reviewer's decision goes to the person who
recorded the record, with the reviewer's note in it; a request to share data or to join a workshop
goes to whoever decides it, and the decision goes back; a comment goes to the person who recorded
the record. Nobody is ever told about their own act, and a bulk approval is one e-mail per person.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services import mailer, managed_secrets
from prisma import Json

logger = logging.getLogger(__name__)

QUEUED = "QUEUED"
SENDING = "SENDING"
SENT = "SENT"
FAILED = "FAILED"

STALE_SENDING_AFTER = timedelta(minutes=15)
MAX_BACKOFF_MINUTES = 60
MAX_ERROR_LENGTH = 300

#: The task states that read as "handed in" and as "approved". Mirrors ``routes/tasks.SUBMITTED``.
SUBMITTED = "SUBMITTED"
DONE = "DONE"
CANCELLED = "CANCELLED"


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


async def wants_task_updates(user_id: str) -> bool:
    row = await db.userpreference.find_unique(where={"userId": user_id})
    if row is None:
        return True
    return bool(getattr(row, "emailTaskUpdates", True))


async def enqueue(
    kind: str,
    *,
    to_address: str,
    recipient_id: str | None,
    params: dict[str, Any],
    secret: str | None = None,
    settings: Settings | None = None,
) -> Any | None:
    """Queue one message. Returns the row, or ``None`` when mail is not configured."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return None
    if kind not in mailer.KINDS:
        raise ValueError(f"Unknown e-mail kind {kind!r}")
    address = (to_address or "").strip()
    if not address:
        return None
    row = await db.emailmessage.create(
        data={
            "kind": kind,
            "toAddress": address,
            "recipientId": recipient_id,
            "subject": mailer.subject_for(kind, params),
            "params": Json(params),
            "sealed": managed_secrets.encrypt(secret) if secret else None,
            "status": QUEUED,
            "maxAttempts": max(int(settings.mail_max_attempts or 1), 1),
        }
    )
    logger.info("mail: queued %s message %s for account %s", kind, row.id, recipient_id or "-")
    return row


async def recover_stale(now: datetime | None = None) -> int:
    cutoff = (now or datetime.now(UTC)) - STALE_SENDING_AFTER
    stale = await db.emailmessage.find_many(
        where={"status": SENDING, "lockedAt": {"lt": cutoff}}, take=100
    )
    for row in stale:
        if int(row.attempts or 0) >= max(int(row.maxAttempts or 1), 1):
            await _finish(row.id, FAILED, error="The worker stopped while sending, on the last attempt.")
        else:
            await db.emailmessage.update_many(
                where={"id": row.id, "status": SENDING},
                data={"status": QUEUED, "lockedAt": None, "lockedBy": None},
            )
    return len(stale)


async def _claim(row: Any, worker_id: str) -> bool:
    claimed = await db.emailmessage.update_many(
        where={"id": row.id, "status": QUEUED},
        data={
            "status": SENDING,
            "lockedAt": datetime.now(UTC),
            "lockedBy": worker_id,
            "attempts": int(row.attempts or 0) + 1,
        },
    )
    return bool(claimed)


async def _finish(row_id: str, status: str, *, error: str | None = None, message_id: str | None = None) -> None:
    data: dict[str, Any] = {
        "status": status,
        "lockedAt": None,
        "lockedBy": None,
        # A finished row keeps no secret: SENT or FAILED, the sealed link is gone.
        "sealed": None,
        "error": (error or None) and error[:MAX_ERROR_LENGTH],
    }
    if status == SENT:
        data["sentAt"] = datetime.now(UTC)
        data["providerMessageId"] = message_id or None
    await db.emailmessage.update(where={"id": row_id}, data=data)


async def _retry_later(row: Any, attempts: int, error: str) -> None:
    delay = min(MAX_BACKOFF_MINUTES, 2 ** max(attempts - 1, 0))
    await db.emailmessage.update(
        where={"id": row.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "runAfter": datetime.now(UTC) + timedelta(minutes=delay),
            "error": error[:MAX_ERROR_LENGTH],
        },
    )


def _expired(params: dict[str, Any]) -> bool:
    raw = params.get("expiresAt")
    if not isinstance(raw, str):
        return False
    try:
        moment = datetime.fromisoformat(raw)
    except ValueError:
        return False
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment <= datetime.now(UTC)


async def _send_one(row: Any, settings: Settings) -> str:
    attempts = int(row.attempts or 0) + 1
    params = dict(row.params or {}) if isinstance(row.params, dict) else {}
    secret: str | None = None
    if getattr(row, "sealed", None):
        secret = managed_secrets.decrypt(row.sealed)
        if secret is None:
            await _finish(row.id, FAILED, error="The sealed link could not be opened (the key changed).")
            return FAILED
    if row.kind == mailer.PASSWORD_LINK and _expired(params):
        await _finish(row.id, FAILED, error="The link expired before it could be sent.")
        return FAILED
    try:
        rendered = mailer.render(row.kind, params, secret=secret, settings=settings)
    except ValueError as exc:
        await _finish(row.id, FAILED, error=f"Could not be rendered: {exc}")
        return FAILED
    try:
        message_id = await asyncio.to_thread(
            mailer.send,
            to=row.toAddress,
            subject=rendered.subject,
            text=rendered.text,
            html_body=rendered.html,
            settings=settings,
        )
    except mailer.SendFailed as exc:
        if exc.permanent or attempts >= max(int(row.maxAttempts or 1), 1):
            await _finish(row.id, FAILED, error=f"SES refused it: {exc.code}")
            logger.warning(
                "mail: %s message %s for account %s failed (%s, attempt %s)",
                row.kind, row.id, row.recipientId or "-", exc.code, attempts,
            )
            return FAILED
        await _retry_later(row, attempts, f"SES did not take it yet: {exc.code}")
        logger.info("mail: %s message %s will be retried (%s, attempt %s)", row.kind, row.id, exc.code, attempts)
        return QUEUED
    await _finish(row.id, SENT, message_id=message_id)
    logger.info("mail: sent %s message %s to account %s", row.kind, row.id, row.recipientId or "-")
    return SENT


async def process_next_email_jobs(
    limit: int | None = None,
    worker_id: str = "queue-service",
    settings: Settings | None = None,
    *,
    recover: bool = True,
) -> dict[str, int]:
    """Drain one batch of due messages. Does nothing at all when mail is not configured."""
    settings = settings or get_settings()
    tally = {"processed": 0, "sent": 0, "retrying": 0, "failed": 0}
    if not mailer.mail_configured(settings):
        return tally
    if recover:
        await recover_stale()
    rows = await db.emailmessage.find_many(
        where={"status": QUEUED, "runAfter": {"lte": datetime.now(UTC)}},
        order={"createdAt": "asc"},
        take=max(1, limit or settings.mail_batch_size),
    )
    for row in rows:
        if not await _claim(row, worker_id):
            continue
        tally["processed"] += 1
        try:
            outcome = await _send_one(row, settings)
        except Exception:  # one bad row must not stop the batch; it is retried
            logger.exception("mail: message %s could not be processed", row.id)
            await _retry_later(row, int(row.attempts or 0) + 1, "Unexpected error while sending.")
            outcome = QUEUED
        key = {SENT: "sent", FAILED: "failed"}.get(outcome, "retrying")
        tally[key] += 1
    return tally


# --------------------------------------------------------------------------------------
# Triggers
# --------------------------------------------------------------------------------------


def task_notice(before: str | None, after: str | None, *, by_manager: bool) -> str | None:
    """Which e-mail a status move earns, if any.

    * into SUBMITTED from anything else -> the approver is asked (``TASK_SUBMITTED``);
    * SUBMITTED -> DONE by a manager -> the assignee hears it was approved (``TASK_APPROVED``);
    * SUBMITTED -> back to work by a manager -> the assignee hears it was sent back
      (``TASK_SENT_BACK``). A cancellation is not a send-back and sends nothing.
    """
    if after is None or before == after:
        return None
    if after == SUBMITTED:
        return mailer.TASK_SUBMITTED
    if before == SUBMITTED and by_manager:
        if after == DONE:
            return mailer.TASK_APPROVED
        if after != CANCELLED:
            return mailer.TASK_SENT_BACK
    return None


async def notify_task_change(
    task: Any, *, before: str | None, after: str | None, actor: Any, by_manager: bool
) -> int:
    """E-mail the other side of a task's status move. NEVER RAISES: the move is already saved."""
    if not mailer.mail_configured():
        return 0
    try:
        kind = task_notice(before, after, by_manager=by_manager)
        if kind is None:
            return 0
        recipient_id = _value(task, "createdById") if kind == mailer.TASK_SUBMITTED else _value(task, "assigneeId")
        if not recipient_id or recipient_id == _value(actor, "id"):
            return 0
        person = await db.user.find_unique(where={"id": str(recipient_id)})
        if person is None or not person.email or not await wants_task_updates(person.id):
            return 0
        params = {
            "recipientName": person.name,
            "taskTitle": _value(task, "title"),
            "actorName": _value(actor, "name"),
        }
        row = await enqueue(kind, to_address=person.email, recipient_id=person.id, params=params)
        return 1 if row is not None else 0
    except Exception:  # see the docstring
        logger.exception("mail: could not queue a task notice for task %s", _value(task, "id"))
        return 0


async def notify_password_event(
    user: Any, kind: str, *, when: datetime | None = None, to_address: str | None = None
) -> bool:
    """Tell an account's own address that a password was added (``mailer.PASSWORD_ADDED``) or
    changed (``mailer.PASSWORD_CHANGED``). A security notice: always sent, no opt-out, a no-op when
    mail is off. Carries no password and no link. NEVER RAISES: the password is already written.

    ``to_address`` sends the same notice somewhere other than the address the account holds now —
    the address it held BEFORE an administrator changed it in the same request (security review N4),
    so the person who owned the account hears of it at the inbox they still read."""
    try:
        row = await enqueue(
            kind,
            to_address=to_address or _value(user, "email") or "",
            recipient_id=_value(user, "id"),
            params={
                "recipientName": _value(user, "name"),
                "changedAt": (when or datetime.now(UTC)).isoformat(),
            },
        )
        return row is not None
    except Exception:  # see the docstring
        logger.exception("mail: could not queue a password notice for %s", _value(user, "id"))
        return False


async def notify_access_granted(email: str, *, name: str | None) -> bool:
    """Tell an address it may now sign in. Asked for explicitly by the admitting administrator.
    NEVER RAISES: the admission is already saved."""
    try:
        account = await db.user.find_unique(where={"email": email})
        row = await enqueue(
            mailer.ACCESS_GRANTED,
            to_address=email,
            recipient_id=account.id if account is not None else None,
            params={"recipientName": name or (account.name if account is not None else None)},
        )
        return row is not None
    except Exception:  # see the docstring
        logger.exception("mail: could not queue an access e-mail")
        return False


# --------------------------------------------------------------------------------------
# Review and sharing notices
# --------------------------------------------------------------------------------------

#: What a record is called in a sentence ("your product record ...").
RECORD_NOUNS = {
    "artisan": "artisan record",
    "workshop": "workshop",
    "product": "product record",
    "tool": "tool record",
    "process": "process",
    "questionnaire": "interview",
    "media": "file",
}

#: Where the record opens read-only in the web app (``frontend/lib/recordView.ts``).
RECORD_PATHS = {
    "artisan": "/artisans/{id}",
    "workshop": "/workshops/{id}",
    "product": "/products/{id}",
    "tool": "/tools/{id}",
    "process": "/processes/{id}",
    "questionnaire": "/questionnaire/interviews/{id}",
    # A file has no page of its own; it is listed, with its review status, on the media page.
    "media": "/media",
}

_DECISION_KINDS = {
    "NEEDS_REVISION": mailer.RECORD_SENT_BACK,
    "REJECTED": mailer.RECORD_REJECTED,
    "APPROVED": mailer.RECORD_APPROVED,
}


def record_path(record_type: str, record_id: str | None) -> str:
    template = RECORD_PATHS.get((record_type or "").lower())
    if not template or not record_id:
        return "/activity"
    return template.format(id=record_id)


async def _reachable(user_id: Any, actor: Any) -> Any | None:
    """The account to tell, or None: no id, the actor themselves, no address, or opted out."""
    if not user_id or str(user_id) == str(_value(actor, "id") or ""):
        return None
    person = await db.user.find_unique(where={"id": str(user_id)})
    if person is None or not person.email or not await wants_task_updates(person.id):
        return None
    return person


async def notify_review_decision(
    *,
    record_type: str,
    record_id: str,
    record_label: str,
    creator_id: str | None,
    decision: str,
    actor: Any,
    note: str | None = None,
    reason_label: str | None = None,
) -> int:
    """Tell a record's creator what a reviewer decided, with the note. NEVER RAISES."""
    if not mailer.mail_configured():
        return 0
    try:
        kind = _DECISION_KINDS.get(decision)
        if kind is None:
            return 0
        person = await _reachable(creator_id, actor)
        if person is None:
            return 0
        key = (record_type or "").lower()
        # "Sent back to you" lists records, not files: a file sent back opens the media page instead.
        path = "/sent-back" if kind == mailer.RECORD_SENT_BACK and key != "media" else record_path(key, record_id)
        params = {
            "recipientName": person.name,
            "recordLabel": record_label,
            "recordNoun": RECORD_NOUNS.get(key, "record"),
            "actorName": _value(actor, "name"),
            "note": note or None,
            "reasonLabel": reason_label or None,
            "path": path,
        }
        row = await enqueue(kind, to_address=person.email, recipient_id=person.id, params=params)
        return 1 if row is not None else 0
    except Exception:  # the decision is already saved
        logger.exception("mail: could not queue a review notice for %s %s", record_type, record_id)
        return 0


async def notify_bulk_approvals(approved: list[dict[str, Any]], *, actor: Any) -> int:
    """One "approved" e-mail per creator for a bulk approval, never one per record. NEVER RAISES.

    ``approved`` items carry ``recordType``, ``id``, ``label`` and ``creatorId``."""
    if not mailer.mail_configured() or not approved:
        return 0
    sent = 0
    by_creator: dict[str, list[dict[str, Any]]] = {}
    for item in approved:
        creator = item.get("creatorId")
        if creator:
            by_creator.setdefault(str(creator), []).append(item)
    for creator_id, items in by_creator.items():
        try:
            person = await _reachable(creator_id, actor)
            if person is None:
                continue
            first = items[0]
            key = str(first.get("recordType") or "").lower()
            params = {
                "recipientName": person.name,
                "recordLabel": first.get("label"),
                "recordNoun": RECORD_NOUNS.get(key, "record"),
                "actorName": _value(actor, "name"),
                "count": len(items),
                "path": record_path(key, first.get("id")) if len(items) == 1 else "/activity",
            }
            row = await enqueue(mailer.RECORD_APPROVED, to_address=person.email, recipient_id=person.id, params=params)
            sent += 1 if row is not None else 0
        except Exception:
            logger.exception("mail: could not queue a bulk approval notice for account %s", creator_id)
    return sent


async def notify_comment(
    *, record_type: str, record_id: str, record_label: str, owner_id: str | None, actor: Any, body: str
) -> int:
    """Tell a record's creator that somebody commented on it. NEVER RAISES."""
    if not mailer.mail_configured():
        return 0
    try:
        person = await _reachable(owner_id, actor)
        if person is None:
            return 0
        key = (record_type or "").lower()
        params = {
            "recipientName": person.name,
            "recordLabel": record_label,
            "recordNoun": RECORD_NOUNS.get(key, "record"),
            "actorName": _value(actor, "name"),
            "note": body,
            "path": record_path(key, record_id),
        }
        row = await enqueue(mailer.RECORD_COMMENT, to_address=person.email, recipient_id=person.id, params=params)
        return 1 if row is not None else 0
    except Exception:
        logger.exception("mail: could not queue a comment notice for %s %s", record_type, record_id)
        return 0


TIER_WORDS = {"DOWNLOAD": "download", "COMMENT": "comment", "EDIT": "edit"}


async def notify_access_request(*, owner_id: str, requester: Any, tier: str, note: str | None) -> int:
    """Tell a data owner that somebody asked for access to their records. NEVER RAISES."""
    if not mailer.mail_configured():
        return 0
    try:
        person = await _reachable(owner_id, requester)
        if person is None:
            return 0
        params = {
            "recipientName": person.name,
            "requesterName": _value(requester, "name"),
            "tierLabel": TIER_WORDS.get(str(tier), "access"),
            "note": note or None,
        }
        row = await enqueue(mailer.ACCESS_REQUESTED, to_address=person.email, recipient_id=person.id, params=params)
        return 1 if row is not None else 0
    except Exception:
        logger.exception("mail: could not queue a data-access request notice")
        return 0


async def notify_access_decision(*, grantee_id: str, owner: Any, decision: str, note: str | None) -> int:
    """Tell the person who asked how their data-access request was decided. NEVER RAISES."""
    if not mailer.mail_configured():
        return 0
    try:
        person = await _reachable(grantee_id, owner)
        if person is None:
            return 0
        params = {
            "recipientName": person.name,
            "ownerName": _value(owner, "name"),
            "decision": decision,
            "note": note or None,
        }
        row = await enqueue(mailer.ACCESS_DECIDED, to_address=person.email, recipient_id=person.id, params=params)
        return 1 if row is not None else 0
    except Exception:
        logger.exception("mail: could not queue a data-access decision notice")
        return 0


ADMIN_ROLES = ("ADMIN", "MASTER_ADMIN")


async def notify_workshop_access_request(*, requester: Any, workshop_titles: list[str], note: str | None) -> int:
    """Tell every administrator (who decides workshop access) about a new request. NEVER RAISES."""
    if not mailer.mail_configured() or not workshop_titles:
        return 0
    sent = 0
    try:
        admins = await db.user.find_many(where={"role": {"in": list(ADMIN_ROLES)}})
    except Exception:
        logger.exception("mail: could not read the administrators for a workshop-access notice")
        return 0
    for admin in admins:
        try:
            person = await _reachable(admin.id, requester)
            if person is None:
                continue
            params = {
                "recipientName": person.name,
                "requesterName": _value(requester, "name"),
                "workshopTitles": workshop_titles[:20],
                "note": note or None,
            }
            row = await enqueue(
                mailer.WORKSHOP_ACCESS_REQUESTED, to_address=person.email, recipient_id=person.id, params=params
            )
            sent += 1 if row is not None else 0
        except Exception:
            logger.exception("mail: could not queue a workshop-access notice for account %s", admin.id)
    return sent


async def notify_workshop_access_decision(
    *, user_id: str, actor: Any, workshop_title: str | None, decision: str, note: str | None
) -> int:
    """Tell the person who asked to join a workshop how it was decided. NEVER RAISES."""
    if not mailer.mail_configured():
        return 0
    try:
        person = await _reachable(user_id, actor)
        if person is None:
            return 0
        params = {
            "recipientName": person.name,
            "workshopTitle": workshop_title,
            "decision": decision,
            "note": note or None,
        }
        row = await enqueue(
            mailer.WORKSHOP_ACCESS_DECIDED, to_address=person.email, recipient_id=person.id, params=params
        )
        return 1 if row is not None else 0
    except Exception:
        logger.exception("mail: could not queue a workshop-access decision notice")
        return 0
