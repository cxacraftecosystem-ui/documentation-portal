"""THE E-MAIL OUTBOX: queueing a message, draining the queue, and the send log.

A request never talks to SES. It writes one ``EmailMessage`` row (:func:`enqueue`) and answers; the
queue worker (``app/worker.py``, the same loop that drains the media queue) calls
:func:`process_next_email_jobs` on every pass, which claims due rows with a compare-and-set, renders
them, hands them to :mod:`app.services.mailer`, and records the outcome on the row. That row IS the
send log: status, attempts, ``sentAt``, SES's message id, or the error code that stopped it.

A transient refusal is retried after 1, 2, 4, 8 … minutes (capped at an hour) until ``maxAttempts``
is spent; a permanent one fails at once. A worker that died holding a row leaves it SENDING, and
:func:`recover_stale` hands it back after :data:`STALE_SENDING_AFTER`.

Nothing secret is e-mailed by this product, and no log line carries an address or a body: the row
id, the kind and the recipient's ACCOUNT id only.

The task notices honour ``UserPreference.emailTaskUpdates`` (opt-out: no row reads as yes). The
access e-mail is sent only when the administrator who admitted the address asked for it.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services import mailer
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


async def _send_one(row: Any, settings: Settings) -> str:
    attempts = int(row.attempts or 0) + 1
    params = dict(row.params or {}) if isinstance(row.params, dict) else {}
    try:
        rendered = mailer.render(row.kind, params, settings=settings)
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
