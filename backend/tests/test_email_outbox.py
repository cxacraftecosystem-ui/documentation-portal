"""The e-mail outbox (``app/services/email_outbox.py``): queueing, the drain, retries, the send log,
which task moves earn an e-mail, the opt-out, and the access e-mail.

No database: the module's ``db`` is a small in-memory stand-in that answers the calls the outbox
makes, and SES is replaced at ``mailer.send``.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.core.config import get_settings
from app.services import email_outbox, mailer


def _matches(row: Any, where: dict[str, Any]) -> bool:
    for key, expected in where.items():
        actual = getattr(row, key)
        if isinstance(expected, dict):
            if "lte" in expected and not actual <= expected["lte"]:
                return False
            if "lt" in expected and not (actual is not None and actual < expected["lt"]):
                return False
        elif actual != expected:
            return False
    return True


class _Messages:
    def __init__(self) -> None:
        self.rows: list[SimpleNamespace] = []

    async def create(self, data: dict[str, Any]) -> SimpleNamespace:
        params = data.get("params")
        values: dict[str, Any] = {
            "id": f"msg_{len(self.rows) + 1}",
            "attempts": 0,
            "runAfter": datetime.now(UTC) - timedelta(seconds=1),
            "lockedAt": None,
            "lockedBy": None,
            "sentAt": None,
            "providerMessageId": None,
            "error": None,
            "recipientId": None,
            "sealed": None,
        }
        values.update({k: v for k, v in data.items() if k != "params"})
        row = SimpleNamespace(**values)
        row.params = getattr(params, "data", params)
        self.rows.append(row)
        return row

    async def find_many(self, where: dict[str, Any], order: Any = None, take: int = 100) -> list[Any]:
        return [SimpleNamespace(**vars(r)) for r in self.rows if _matches(r, where)][:take]

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        hits = [r for r in self.rows if _matches(r, where)]
        for row in hits:
            vars(row).update(data)
        return len(hits)

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        (row,) = [r for r in self.rows if r.id == where["id"]]
        vars(row).update(data)
        return row


class _Lookup:
    def __init__(self, rows: dict[str, Any], key: str) -> None:
        self.rows, self.key = rows, key

    async def find_unique(self, where: dict[str, Any]) -> Any:
        return self.rows.get(where[self.key])


def _person(uid: str) -> SimpleNamespace:
    return SimpleNamespace(id=uid, name=f"Person {uid}", email=f"{uid}@example.org")


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    settings = get_settings().model_copy(update={"mail_from_address": "no-reply@repo.example.org", "mail_max_attempts": 3})
    people = {p.id: p for p in (_person("creator"), _person("assignee"), _person("admin"))}
    fake = SimpleNamespace(
        emailmessage=_Messages(),
        userpreference=_Lookup({}, "userId"),
        user=SimpleNamespace(find_unique=lambda where: _find_user(people, where)),
    )
    monkeypatch.setattr(email_outbox, "db", fake)
    monkeypatch.setattr(email_outbox, "get_settings", lambda: settings)
    monkeypatch.setattr(mailer, "get_settings", lambda: settings)
    sent: list[dict[str, Any]] = []
    outcome: dict[str, Any] = {"error": None}

    def fake_send(**message: Any) -> str:
        sent.append(message)
        if outcome["error"] is not None:
            raise outcome["error"]
        return f"ses-{len(sent)}"

    monkeypatch.setattr(mailer, "send", fake_send)
    return SimpleNamespace(db=fake, sent=sent, outcome=outcome, people=people)


async def _find_user(people: dict[str, Any], where: dict[str, Any]) -> Any:
    if "id" in where:
        return people.get(where["id"])
    return next((p for p in people.values() if p.email == where.get("email")), None)


def _drain() -> dict[str, int]:
    return asyncio.run(email_outbox.process_next_email_jobs(limit=10))


def _queue(kind: str = mailer.TASK_SENT_BACK) -> Any:
    return asyncio.run(
        email_outbox.enqueue(
            kind, to_address="assignee@example.org", recipient_id="assignee",
            params={"taskTitle": "Photograph the looms", "actorName": "Ravi"},
        )
    )


def test_nothing_is_queued_or_drained_without_mail(world: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    off = get_settings().model_copy(update={"mail_from_address": None})
    monkeypatch.setattr(email_outbox, "get_settings", lambda: off)
    monkeypatch.setattr(mailer, "get_settings", lambda: off)
    assert _queue() is None and world.db.emailmessage.rows == []
    assert _drain()["processed"] == 0


def test_a_message_is_sent_and_logged_on_its_row(world: Any) -> None:
    _queue()
    assert _drain() == {"processed": 1, "sent": 1, "retrying": 0, "failed": 0}
    (row,) = world.db.emailmessage.rows
    assert row.status == "SENT" and row.providerMessageId == "ses-1" and row.sentAt is not None
    assert world.sent[0]["to"] == "assignee@example.org"


def test_a_throttle_waits_and_retries_and_a_rejection_fails(world: Any) -> None:
    _queue()
    world.outcome["error"] = mailer.SendFailed("TooManyRequestsException", permanent=False)
    assert _drain()["retrying"] == 1
    (row,) = world.db.emailmessage.rows
    assert row.status == "QUEUED" and row.attempts == 1 and row.runAfter > datetime.now(UTC)
    row.runAfter = datetime.now(UTC) - timedelta(seconds=1)
    world.outcome["error"] = mailer.SendFailed("MessageRejected", permanent=True)
    assert _drain()["failed"] == 1 and row.status == "FAILED"


def test_the_last_attempt_fails_the_message(world: Any) -> None:
    _queue()
    world.outcome["error"] = mailer.SendFailed("InternalFailure", permanent=False)
    (row,) = world.db.emailmessage.rows
    for _ in range(3):
        row.runAfter = datetime.now(UTC) - timedelta(seconds=1)
        _drain()
    assert row.attempts == 3 and row.status == "FAILED" and len(world.sent) == 3


def test_no_log_line_carries_an_address(world: Any, caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG)
    _queue()
    world.outcome["error"] = mailer.SendFailed("MessageRejected", permanent=True)
    _drain()
    joined = "\n".join(r.getMessage() for r in caplog.records)
    assert "mail:" in joined and "assignee@example.org" not in joined


@pytest.mark.parametrize(
    ("before", "after", "by_manager", "expected"),
    [
        ("IN_PROGRESS", "SUBMITTED", False, mailer.TASK_SUBMITTED),
        ("OPEN", "SUBMITTED", False, mailer.TASK_SUBMITTED),
        ("SUBMITTED", "DONE", True, mailer.TASK_APPROVED),
        ("SUBMITTED", "IN_PROGRESS", True, mailer.TASK_SENT_BACK),
        ("SUBMITTED", "OPEN", True, mailer.TASK_SENT_BACK),
        ("SUBMITTED", "CANCELLED", True, None),
        ("SUBMITTED", "SUBMITTED", False, None),
        ("OPEN", "IN_PROGRESS", False, None),
        ("IN_PROGRESS", "DONE", True, None),
        ("SUBMITTED", None, True, None),
    ],
)
def test_which_status_moves_earn_an_e_mail(before: str, after: str | None, by_manager: bool, expected: str | None) -> None:
    assert email_outbox.task_notice(before, after, by_manager=by_manager) == expected


def _task_notice(world: Any, *, before: str, after: str, actor: str, by_manager: bool) -> int:
    task = SimpleNamespace(id="t1", title="Photograph the looms", createdById="creator", assigneeId="assignee")
    return asyncio.run(
        email_outbox.notify_task_change(
            task, before=before, after=after, actor=world.people[actor], by_manager=by_manager
        )
    )


def test_handing_in_e_mails_the_creator_and_a_decision_e_mails_the_assignee(world: Any) -> None:
    assert _task_notice(world, before="IN_PROGRESS", after="SUBMITTED", actor="assignee", by_manager=False) == 1
    assert _task_notice(world, before="SUBMITTED", after="IN_PROGRESS", actor="admin", by_manager=True) == 1
    first, second = world.db.emailmessage.rows
    assert (first.kind, first.recipientId) == (mailer.TASK_SUBMITTED, "creator")
    assert (second.kind, second.recipientId) == (mailer.TASK_SENT_BACK, "assignee")


def test_nobody_is_e_mailed_about_their_own_move_or_after_opting_out(world: Any) -> None:
    assert _task_notice(world, before="SUBMITTED", after="DONE", actor="assignee", by_manager=True) == 0
    world.db.userpreference = _Lookup({"assignee": SimpleNamespace(emailTaskUpdates=False)}, "userId")
    assert _task_notice(world, before="SUBMITTED", after="DONE", actor="admin", by_manager=True) == 0
    assert world.db.emailmessage.rows == []


def test_a_task_notice_failure_never_reaches_the_request(world: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    async def broken(*args: Any, **kwargs: Any) -> Any:
        raise RuntimeError("database went away")

    monkeypatch.setattr(email_outbox, "enqueue", broken)
    assert _task_notice(world, before="IN_PROGRESS", after="SUBMITTED", actor="assignee", by_manager=False) == 0


def test_the_access_e_mail_reaches_an_address_with_no_account_yet(world: Any) -> None:
    assert asyncio.run(email_outbox.notify_access_granted("newcomer@example.org", name="Newcomer"))
    (row,) = world.db.emailmessage.rows
    assert row.kind == mailer.ACCESS_GRANTED and row.toAddress == "newcomer@example.org"
    assert row.recipientId is None
