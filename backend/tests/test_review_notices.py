"""The review and sharing e-mails (A1, A5): what each says, who gets it, and the shared opt-out.

No database: the outbox's ``db`` is an in-memory stand-in, as in ``test_email_outbox.py``.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.core.config import get_settings
from app.services import email_outbox, mailer


class _Messages:
    def __init__(self) -> None:
        self.rows: list[SimpleNamespace] = []

    async def create(self, data: dict[str, Any]) -> SimpleNamespace:
        params = data.get("params")
        row = SimpleNamespace(
            id=f"msg_{len(self.rows) + 1}",
            attempts=0,
            runAfter=datetime.now(UTC) - timedelta(seconds=1),
            **{k: v for k, v in data.items() if k != "params"},
        )
        row.params = getattr(params, "data", params)
        self.rows.append(row)
        return row


class _Prefs:
    def __init__(self, rows: dict[str, Any]) -> None:
        self.rows = rows

    async def find_unique(self, where: dict[str, Any]) -> Any:
        return self.rows.get(where["userId"])

    async def find_many(self, where: dict[str, Any]) -> list[Any]:
        return [SimpleNamespace(userId=uid, **vars(row)) for uid, row in self.rows.items() if uid in where["userId"]["in"]]


def _person(uid: str, role: str = "RESEARCHER") -> SimpleNamespace:
    return SimpleNamespace(id=uid, name=f"Person {uid}", email=f"{uid}@example.org", role=role)


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    settings = get_settings().model_copy(update={"mail_from_address": "no-reply@repo.example.org"})
    people = {
        p.id: p
        for p in (_person("creator"), _person("reviewer", "PROFESSOR"), _person("admin1", "ADMIN"), _person("admin2", "MASTER_ADMIN"))
    }

    async def find_unique(where: dict[str, Any]) -> Any:
        return people.get(where["id"])

    async def find_many(where: dict[str, Any]) -> list[Any]:
        if "id" in where:
            return [p for p in people.values() if p.id in where["id"]["in"]]
        roles = where["role"]["in"]
        return [p for p in people.values() if p.role in roles]

    fake = SimpleNamespace(
        emailmessage=_Messages(),
        userpreference=_Prefs({}),
        user=SimpleNamespace(find_unique=find_unique, find_many=find_many),
    )
    monkeypatch.setattr(email_outbox, "db", fake)
    monkeypatch.setattr(email_outbox, "get_settings", lambda: settings)
    monkeypatch.setattr(mailer, "get_settings", lambda: settings)
    return SimpleNamespace(db=fake, people=people)


def _decision(world: Any, decision: str, actor: str = "reviewer", **extra: Any) -> int:
    return asyncio.run(
        email_outbox.notify_review_decision(
            record_type="product",
            record_id="p1",
            record_label="Indigo dupatta",
            creator_id="creator",
            decision=decision,
            actor=world.people[actor],
            **extra,
        )
    )


def test_a_send_back_reaches_the_creator_with_the_note_and_the_reason(world: Any) -> None:
    assert _decision(world, "NEEDS_REVISION", note="Add the dye recipe.", reason_label="Missing details") == 1
    (row,) = world.db.emailmessage.rows
    assert row.kind == mailer.RECORD_SENT_BACK and row.recipientId == "creator"
    assert row.params["note"] == "Add the dye recipe." and row.params["path"] == "/sent-back"
    rendered = mailer.render(row.kind, row.params)
    assert "Add the dye recipe." in rendered.text and "Missing details" in rendered.text
    assert rendered.subject == "“Indigo dupatta” was sent back to you"
    assert "/sent-back" in rendered.text


def test_rejected_and_approved_each_have_their_own_e_mail(world: Any) -> None:
    assert _decision(world, "REJECTED", reason_label="Duplicate of another record") == 1
    assert _decision(world, "APPROVED") == 1
    rejected, approved = world.db.emailmessage.rows
    assert rejected.kind == mailer.RECORD_REJECTED and approved.kind == mailer.RECORD_APPROVED
    assert approved.params["path"] == "/products/p1"
    assert "Duplicate of another record" in mailer.render(rejected.kind, rejected.params).text


def test_nobody_hears_about_their_own_review_and_the_opt_out_holds(world: Any) -> None:
    assert _decision(world, "APPROVED", actor="creator") == 0
    world.db.userpreference = _Prefs({"creator": SimpleNamespace(emailTaskUpdates=False)})
    assert _decision(world, "NEEDS_REVISION", note="x") == 0
    assert world.db.emailmessage.rows == []


def test_a_pending_or_unknown_status_sends_nothing(world: Any) -> None:
    assert _decision(world, "PENDING") == 0


def test_a_bulk_approval_is_one_e_mail_per_creator(world: Any) -> None:
    items = [
        {"recordType": "artisan", "id": "a1", "label": "Ramesh", "creatorId": "creator"},
        {"recordType": "artisan", "id": "a2", "label": "Sita", "creatorId": "creator"},
        {"recordType": "tool", "id": "t1", "label": "Block", "creatorId": "admin1"},
    ]
    assert asyncio.run(email_outbox.notify_bulk_approvals(items, actor=world.people["reviewer"])) == 2
    first, second = world.db.emailmessage.rows
    assert first.params["count"] == 2 and first.params["path"] == "/activity"
    assert mailer.render(first.kind, first.params).subject == "2 of your records were approved"
    assert second.params["count"] == 1 and second.params["path"] == "/tools/t1"


def test_a_comment_reaches_the_owner_not_the_author(world: Any) -> None:
    sent = asyncio.run(
        email_outbox.notify_comment(
            record_type="artisan", record_id="a1", record_label="Ramesh", owner_id="creator",
            actor=world.people["reviewer"], body="Which village is this?",
        )
    )
    assert sent == 1
    (row,) = world.db.emailmessage.rows
    assert row.kind == mailer.RECORD_COMMENT and row.params["path"] == "/artisans/a1"
    own = asyncio.run(
        email_outbox.notify_comment(
            record_type="artisan", record_id="a1", record_label="Ramesh", owner_id="creator",
            actor=world.people["creator"], body="Note to self",
        )
    )
    assert own == 0


def test_data_access_request_and_decision_go_to_the_other_side(world: Any) -> None:
    asked = asyncio.run(
        email_outbox.notify_access_request(owner_id="creator", requester=world.people["reviewer"], tier="COMMENT", note="For the report")
    )
    decided = asyncio.run(
        email_outbox.notify_access_decision(grantee_id="reviewer", owner=world.people["creator"], decision="GRANTED", note=None)
    )
    assert (asked, decided) == (1, 1)
    request, decision = world.db.emailmessage.rows
    assert request.kind == mailer.ACCESS_REQUESTED and request.recipientId == "creator"
    assert "comment access" in mailer.render(request.kind, request.params).text
    assert decision.kind == mailer.ACCESS_DECIDED and decision.recipientId == "reviewer"
    assert mailer.render(decision.kind, decision.params).subject == "Your data request was granted"


def test_a_workshop_request_reaches_every_admin_and_the_answer_reaches_the_asker(world: Any) -> None:
    sent = asyncio.run(
        email_outbox.notify_workshop_access_request(
            requester=world.people["creator"], workshop_titles=["Bagru 2026", "Sanganer 2026"], note=None
        )
    )
    assert sent == 2
    assert {row.recipientId for row in world.db.emailmessage.rows} == {"admin1", "admin2"}
    assert mailer.render(world.db.emailmessage.rows[0].kind, world.db.emailmessage.rows[0].params).subject == (
        "Person creator asked to join 2 workshops"
    )
    answered = asyncio.run(
        email_outbox.notify_workshop_access_decision(
            user_id="creator", actor=world.people["admin1"], workshop_title="Bagru 2026", decision="DENIED", note="Closed"
        )
    )
    assert answered == 1
    last = world.db.emailmessage.rows[-1]
    assert mailer.render(last.kind, last.params).subject == "Workshop access declined: Bagru 2026"


def test_every_new_kind_renders_escaped_and_offers_the_opt_out() -> None:
    params = {
        "recipientName": "<b>Asha</b>",
        "recordLabel": "<script>x</script>",
        "actorName": "Ravi",
        "note": "<i>fix</i>",
        "requesterName": "Ravi",
        "ownerName": "Asha",
        "decision": "GRANTED",
        "workshopTitles": ["W"],
        "workshopTitle": "W",
    }
    for kind in sorted(mailer.REVIEW_KINDS | mailer.SHARING_KINDS):
        rendered = mailer.render(kind, params)
        assert "<script>" not in rendered.html and "<i>" not in rendered.html
        assert "turn these e-mails off" in rendered.text
        assert kind in mailer.TASK_KINDS


def test_a_stored_path_can_only_point_inside_the_app() -> None:
    rendered = mailer.render(mailer.RECORD_APPROVED, {"recordLabel": "x", "path": "//evil.example/steal"})
    assert "evil.example" not in rendered.text
    rendered = mailer.render(mailer.RECORD_APPROVED, {"recordLabel": "x", "path": "https://evil.example"})
    assert "evil.example" not in rendered.text


def test_a_reason_with_no_note_of_its_own_is_said_once(world: Any) -> None:
    # A fixable reason with no note stores the reason's label as the note (review._resolve_refusal).
    assert _decision(world, "NEEDS_REVISION", note="Missing details", reason_label="Missing details") == 1
    (row,) = world.db.emailmessage.rows
    text = mailer.render(row.kind, row.params).text
    assert text.count("Missing details") == 1 and "Their note" not in text


def test_a_file_decision_opens_the_media_page_never_a_missing_route(world: Any) -> None:
    for decision in ("APPROVED", "REJECTED", "NEEDS_REVISION"):
        asyncio.run(
            email_outbox.notify_review_decision(
                record_type="media", record_id="m1", record_label="photo.jpg", creator_id="creator",
                decision=decision, actor=world.people["reviewer"], note="Blurred",
            )
        )
    assert [row.params["path"] for row in world.db.emailmessage.rows] == ["/media", "/media", "/media"]
    assert email_outbox.record_path("media", "m1") == "/media"
