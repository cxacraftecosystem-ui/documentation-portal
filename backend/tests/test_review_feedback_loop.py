"""The review feedback loop (A1, A2, B9, C19, C21): reasons, the reopen, the uploader's own list,
the latest decision on a record, bulk approval, and media that inherits its record's approval.

NOTHING HERE TOUCHES A DATABASE: the review module's ``db`` is an in-memory stand-in and the e-mail
calls are recorded instead of queued.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.router import api_router
from app.api.routes import review
from app.core import deps
from app.services import email_outbox
from app.services.records import resubmit_status
from review_tx_fakes import with_tx


def _user(role: str, user_id: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id, email=f"{user_id}@example.test", name=f"{role.title()} {user_id}", role=role,
        canReview=False, canDownloadDataset=False, canViewProvenance=False,
    )


def _matches(row: Any, where: dict[str, Any]) -> bool:
    for key, expected in where.items():
        if key == "OR":
            if not any(_matches(row, clause) for clause in expected):
                return False
            continue
        if key == "AND":  # live_media_where composes its removedAt clause under AND (M19)
            if not all(_matches(row, clause) for clause in expected):
                return False
            continue
        actual = getattr(row, key, None)
        if isinstance(expected, dict):
            if "in" in expected and actual not in expected["in"]:
                return False
            if "equals" in expected and str(actual or "").lower() != str(expected["equals"]).lower():
                return False
        elif actual != expected:
            return False
    return True


class _Table:
    def __init__(self, rows: list[SimpleNamespace] | None = None) -> None:
        self.rows = rows or []
        self.created: list[dict[str, Any]] = []

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return next((row for row in self.rows if row.id == where["id"]), None)

    async def find_first(self, where: dict[str, Any], order: Any = None) -> Any:
        hits = [row for row in self.rows if _matches(row, where)]
        return hits[-1] if hits else None

    async def find_many(self, where: dict[str, Any] | None = None, order: Any = None, take: int | None = None, include: Any = None) -> list:
        return [row for row in self.rows if _matches(row, where or {})][: take or None]

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        row = await self.find_unique(where)
        vars(row).update({k: getattr(v, "data", v) for k, v in data.items()})
        return row

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        hits = [row for row in self.rows if _matches(row, where)]
        for row in hits:
            vars(row).update({k: getattr(v, "data", v) for k, v in data.items()})
        return len(hits)

    async def create(self, data: dict[str, Any]) -> Any:
        self.created.append(data)
        row = SimpleNamespace(id=f"log{len(self.created)}", createdAt=datetime.now(UTC), **data)
        self.rows.append(row)
        return row

    async def count(self, where: dict[str, Any]) -> int:
        return len([row for row in self.rows if _matches(row, where)])


def _record(record_id: str, creator: SimpleNamespace, status: str = "PENDING", **extra: Any) -> SimpleNamespace:
    values = dict(
        id=record_id, name=f"Artisan {record_id}", place="Bagru", status=status, createdBy=creator,
        createdById=creator.id, createdAt=datetime(2026, 10, 1, tzinfo=UTC), submittedAt=datetime(2026, 10, 1, tzinfo=UTC),
        reviewedAt=None, reviewedById=None, reviewNotes=None, workshopId=None, extraMetadata={},
    )
    values.update(extra)
    return SimpleNamespace(**values)


_CURRENT: dict[str, Any] = {"user": None}
_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


def _call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://review.test") as client:
            return await client.request(method, f"/api/review{path}", json=body)

    return asyncio.run(run())


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    creator = _user("RESEARCHER", "creator")
    reviewer = _user("PROFESSOR", "prof")
    admin = _user("ADMIN", "admin")
    artisans = _Table([_record("a1", creator), _record("a2", creator), _record("late", creator, extraMetadata={"workshopSubmission": {"needsAdminApproval": True}})])
    media = _Table(
        [
            SimpleNamespace(id="m1", status="PENDING", linkedRecordType="artisan", linkedRecordId="a1", artisanId="a1", extraMetadata={}),
            SimpleNamespace(id="m2", status="PENDING", linkedRecordType="ARTISAN", linkedRecordId="a1", artisanId="a1",
                            extraMetadata={"workshopSubmission": {"needsAdminApproval": True}}),
            SimpleNamespace(id="m3", status="PENDING", linkedRecordType="product", linkedRecordId="p9", artisanId="a1", extraMetadata={}),
        ]
    )
    users = _Table([creator, reviewer, admin])
    fake = SimpleNamespace(
        artisan=artisans, reviewlog=_Table(), mediafile=media, user=users, processstep=_Table(),
        workshop=_Table(), productdocumentation=_Table(), tooldocumentation=_Table(), process=_Table(),
        questionnaireinterview=_Table(),
    )
    monkeypatch.setattr(review, "db", with_tx(fake))
    empty = _Table()
    monkeypatch.setattr(
        review,
        "_PENDING_SOURCES",
        [("artisan", artisans, ("name",)), ("workshop", empty, ("title",)), ("product", empty, ("productName",)),
         ("tool", empty, ("toolkitName",)), ("process", empty, ("name",)), ("questionnaire", empty, ("title",))],
    )
    monkeypatch.setattr(review, "_MINE_SOURCES", review._PENDING_SOURCES)
    notices: list[dict[str, Any]] = []
    bulk: list[list[dict[str, Any]]] = []

    async def record_notice(**kwargs: Any) -> int:
        notices.append(kwargs)
        return 1

    async def record_bulk(items: list[dict[str, Any]], *, actor: Any) -> int:
        bulk.append(items)
        return 1

    monkeypatch.setattr(email_outbox, "notify_review_decision", record_notice)
    monkeypatch.setattr(email_outbox, "notify_bulk_approvals", record_bulk)
    _CURRENT["user"] = reviewer
    yield SimpleNamespace(db=fake, creator=creator, reviewer=reviewer, admin=admin, notices=notices, bulk=bulk)
    _CURRENT["user"] = None


def test_the_reasons_are_served_with_which_ones_send_a_record_back() -> None:
    _CURRENT["user"] = _user("PROFESSOR", "p")
    body = _call("GET", "/reasons").json()
    codes = {r["code"]: r for r in body}
    assert codes["MISSING_DETAILS"]["fixable"] is True
    assert codes["DUPLICATE"]["fixable"] is False
    assert codes["OTHER"]["needsNote"] is True


def test_a_fixable_reason_sends_the_record_back_instead_of_rejecting_it(world: Any) -> None:
    response = _call("POST", "/artisan/a1/reject", {"reason": "MISSING_DETAILS"})
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "NEEDS_REVISION"
    assert response.json()["reviewNotes"] == "Missing details"
    (log,) = world.db.reviewlog.created
    assert log["reason"] == "MISSING_DETAILS" and log["status"] == "NEEDS_REVISION"
    (notice,) = world.notices
    assert notice["decision"] == "NEEDS_REVISION" and notice["reason_label"] == "Missing details"
    assert notice["creator_id"] == "creator"


def test_a_final_reason_rejects_and_other_needs_words(world: Any) -> None:
    assert _call("POST", "/artisan/a1/reject", {"reason": "OTHER"}).status_code == 422
    assert _call("POST", "/artisan/a1/reject", {"reason": "NOT_A_REASON"}).status_code == 422
    response = _call("POST", "/artisan/a1/reject", {"reason": "DUPLICATE", "notes": "Same as Ramesh in Bagru"})
    assert response.json()["status"] == "REJECTED"
    assert world.db.reviewlog.created[-1]["reason"] == "DUPLICATE"


def test_an_older_client_rejecting_with_no_reason_still_rejects(world: Any) -> None:
    response = _call("POST", "/artisan/a1/reject", {"notes": None})
    assert response.json()["status"] == "REJECTED"
    assert "reason" not in world.db.reviewlog.created[-1]


def test_approving_a_record_approves_its_files_but_a_late_file_waits_for_an_admin(world: Any) -> None:
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    states = {row.id: row.status for row in world.db.mediafile.rows}
    assert states == {"m1": "APPROVED", "m2": "PENDING", "m3": "PENDING"}
    _CURRENT["user"] = world.admin
    world.db.artisan.rows[0].status = "PENDING"
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    m2 = world.db.mediafile.rows[1]
    assert m2.status == "APPROVED"
    assert m2.extraMetadata["workshopSubmission"]["needsAdminApproval"] is False


def test_bulk_approval_reports_each_record_and_mails_once(world: Any) -> None:
    response = _call(
        "POST",
        "/bulk-approve",
        {"items": [{"recordType": "artisan", "id": "a1"}, {"recordType": "artisan", "id": "a2"},
                   {"recordType": "artisan", "id": "late"}, {"recordType": "artisan", "id": "a1"}]},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert (body["approved"], body["failed"]) == (2, 1)
    failed = [r for r in body["results"] if r["outcome"] == "FAILED"]
    assert failed[0]["id"] == "late" and "outside its workshop" in failed[0]["detail"]
    (items,) = world.bulk
    assert [item["id"] for item in items] == ["a1", "a2"]
    assert world.notices == []  # one grouped e-mail, never one per record


def test_bulk_approval_needs_at_least_one_record(world: Any) -> None:
    assert _call("POST", "/bulk-approve", {"items": []}).status_code == 422


def test_the_uploader_sees_what_was_sent_back_with_the_note(world: Any) -> None:
    rows = world.db.artisan.rows
    rows[0].status, rows[0].reviewNotes, rows[0].reviewedById, rows[0].reviewedAt = (
        "NEEDS_REVISION", "Add the village.", "prof", datetime(2026, 10, 5, tzinfo=UTC)
    )
    rows[1].status, rows[1].reviewedById, rows[1].reviewedAt = "REJECTED", "prof", datetime(2026, 10, 6, tzinfo=UTC)
    world.db.reviewlog.rows.append(SimpleNamespace(recordId="a1", status="NEEDS_REVISION", reason="MISSING_DETAILS"))
    _CURRENT["user"] = world.creator
    body = _call("GET", "/mine").json()
    assert body["counts"] == {"NEEDS_REVISION": 1, "REJECTED": 1}
    assert [item["id"] for item in body["items"]] == ["a2", "a1"]  # newest decision first
    sent_back = body["items"][1]
    assert sent_back["reviewNotes"] == "Add the village." and sent_back["reviewerName"] == "Professor prof"
    assert sent_back["reasonLabel"] == "Missing details"
    only = _call("GET", "/mine?status=NEEDS_REVISION").json()
    assert [item["id"] for item in only["items"]] == ["a1"]
    assert _call("GET", "/mine?status=APPROVED").status_code == 422


def test_the_latest_decision_is_for_its_creator_and_reviewers_only(world: Any) -> None:
    row = world.db.artisan.rows[0]
    row.status, row.reviewNotes, row.reviewedById, row.reviewedAt = "NEEDS_REVISION", "Fix it", "prof", datetime(2026, 10, 5, tzinfo=UTC)
    world.db.reviewlog.rows.append(SimpleNamespace(recordType="ARTISAN", recordId="a1", status="NEEDS_REVISION", reason="WRONG_DETAILS"))
    _CURRENT["user"] = world.creator
    body = _call("GET", "/artisan/a1/latest").json()
    assert body["review"]["notes"] == "Fix it" and body["review"]["reviewerName"] == "Professor prof"
    assert body["review"]["reasonLabel"] == "Details are wrong"
    assert body["canReview"] is False
    _CURRENT["user"] = world.reviewer
    assert _call("GET", "/artisan/a1/latest").json()["canReview"] is True
    _CURRENT["user"] = _user("RESEARCHER", "stranger")
    assert _call("GET", "/artisan/a1/latest").status_code == 403
    _CURRENT["user"] = world.creator
    assert _call("GET", "/artisan/a2/latest").json()["review"] is None


def test_the_creators_edit_reopens_a_rejected_record_and_stamps_when() -> None:
    creator = SimpleNamespace(id="c")
    record = SimpleNamespace(status="REJECTED", createdById="c")
    data = resubmit_status(record, creator, {"name": "Fixed"})
    assert data["status"] == "PENDING" and isinstance(data["submittedAt"], datetime)
    other = resubmit_status(record, SimpleNamespace(id="admin"), {"name": "Tidied"})
    assert "status" not in other and "submittedAt" not in other
    sent_back = resubmit_status(SimpleNamespace(status="NEEDS_REVISION", createdById="c"), creator, {})
    assert sent_back["status"] == "PENDING"
    explicit = resubmit_status(SimpleNamespace(status="APPROVED", createdById="c"), creator, {"status": "PENDING"})
    assert "submittedAt" in explicit
    unchanged = resubmit_status(SimpleNamespace(status="PENDING", createdById="c"), creator, {"status": "PENDING"})
    assert "submittedAt" not in unchanged
    approved = resubmit_status(SimpleNamespace(status="APPROVED", createdById="c"), creator, {"name": "x"})
    assert "status" not in approved


def test_a_file_approved_with_its_record_obeys_the_ladder_and_is_logged(world: Any) -> None:
    # m4: an admin's photograph on a researcher's record. A professor approving the record may not
    # approve a file of somebody ranked above them; it stays pending for an admin.
    world.db.mediafile.rows.append(
        SimpleNamespace(id="m4", status="PENDING", linkedRecordType="artisan", linkedRecordId="a1", artisanId="a1",
                        extraMetadata={}, uploadedBy=world.admin)
    )
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    states = {row.id: row.status for row in world.db.mediafile.rows}
    assert states["m1"] == "APPROVED" and states["m4"] == "PENDING"
    media_logs = [log for log in world.db.reviewlog.created if log["recordType"] == "MEDIA"]
    assert [(log["recordId"], log["status"], log["reviewerId"]) for log in media_logs] == [("m1", "APPROVED", "prof")]


def test_the_sent_back_counts_are_true_totals_past_the_list_cap(world: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(review, "PENDING_TAKE", 1)
    for row in world.db.artisan.rows[:2]:
        row.status, row.reviewedAt = "NEEDS_REVISION", datetime(2026, 10, 5, tzinfo=UTC)
    _CURRENT["user"] = world.creator
    body = _call("GET", "/mine").json()
    assert body["counts"]["NEEDS_REVISION"] == 2
    assert len(body["items"]) == 1 and body["truncated"] is True
