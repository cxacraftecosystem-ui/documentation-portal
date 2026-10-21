"""Approving a record approves its still-pending files, in one transaction (owner decision 2026-10-18).

What is held here:

* approving a parent (artisan, product, tool, interview, process, workshop) approves its PENDING
  files with the record's reviewer and moment, and writes one audit line per file;
* a REJECTED file, or one already approved, keeps its decision — nothing re-stamps it;
* rejecting or sending a parent back moves no file;
* a single file can still be rejected on its own, also after it was approved with its record;
* the record, its log line and its files land together or not at all;
* the queue, the record page and the bulk approval say how many files go with an approval;
* the review decisions accept an Idempotency-Key, so the Android outbox can replay one safely;
* the backfill script finds, counts and approves exactly what a live approval would, once — and
  only files uploaded at or before their record's approval; later ones stay pending for review.

NOTHING HERE TOUCHES A DATABASE: the review module's ``db`` is an in-memory stand-in whose ``tx()``
really rolls back (``review_tx_fakes.RollbackTx``). ``test_review_media_cascade_sql_db.py`` runs the
same writes against Postgres.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI
from review_tx_fakes import RollbackTx, with_tx

import scripts.approve_media_of_approved_records as backfill
from app.api.router import api_router
from app.api.routes import review
from app.core import deps, idempotency
from app.services import email_outbox

APPROVED_AT = datetime(2026, 10, 2, 9, 30, tzinfo=UTC)


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
            if "gt" in expected and not (actual is not None and str(actual) > str(expected["gt"])):
                return False
        elif actual != expected:
            return False
    return True


class _Table:
    def __init__(self, rows: list[SimpleNamespace] | None = None) -> None:
        self.rows = rows or []
        self.created: list[dict[str, Any]] = []
        self.fail_on_update = False

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return next((row for row in self.rows if row.id == where["id"]), None)

    async def find_first(self, where: dict[str, Any], order: Any = None) -> Any:
        hits = [row for row in self.rows if _matches(row, where)]
        return hits[-1] if hits else None

    async def find_many(self, where: dict[str, Any] | None = None, order: Any = None, take: int | None = None,
                        include: Any = None) -> list:
        hits = [row for row in self.rows if _matches(row, where or {})]
        if order and "id" in order:
            hits.sort(key=lambda row: row.id)
        return hits[: take or None]

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        row = await self.find_unique(where)
        vars(row).update({k: getattr(v, "data", v) for k, v in data.items()})
        return row

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        if self.fail_on_update:
            raise RuntimeError("the database went away")
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
        id=record_id, name=f"Record {record_id}", title=f"Record {record_id}", place="Bagru", status=status,
        createdBy=creator, createdById=creator.id, createdAt=datetime(2026, 10, 1, tzinfo=UTC),
        submittedAt=datetime(2026, 10, 1, tzinfo=UTC), reviewedAt=None, reviewedById=None, reviewNotes=None,
        workshopId=None, extraMetadata={},
    )
    values.update(extra)
    return SimpleNamespace(**values)


def _media(media_id: str, status: str = "PENDING", *, tag: str | None = None, linked: str | None = None,
           uploader: SimpleNamespace | None = None, **columns: Any) -> SimpleNamespace:
    values = dict(
        id=media_id, status=status, linkedRecordType=tag, linkedRecordId=linked, artisanId=None, productId=None,
        toolId=None, questionnaireInterviewId=None, extraMetadata={}, uploadedBy=uploader,
        uploadedById=uploader.id if uploader else "creator", reviewedAt=None, reviewedById=None,
        createdAt=datetime(2026, 10, 1, 12, 0, tzinfo=UTC),
    )
    values.update(columns)
    return SimpleNamespace(**values)


_CURRENT: dict[str, Any] = {"user": None}
_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


def _call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://review.test") as client:
            return await client.request(method, f"/api/review{path}", json=body)

    return asyncio.run(run())


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    creator = _user("RESEARCHER", "creator")
    reviewer = _user("PROFESSOR", "prof")
    admin = _user("ADMIN", "admin")
    earlier = SimpleNamespace(reviewedAt=APPROVED_AT, reviewedById="admin")
    artisans = _Table([_record("a1", creator), _record("a2", creator)])
    products = _Table([_record("p1", creator, productName="Ajrakh stole")])
    processes = _Table([_record("pr1", creator)])
    interviews = _Table([_record("q1", creator)])
    steps = _Table([SimpleNamespace(id="s1", processId="pr1"), SimpleNamespace(id="s2", processId="pr1")])
    media = _Table(
        [
            _media("m1", tag="artisan", linked="a1", uploader=creator, artisanId="a1"),
            _media("m2", tag="ARTISAN", linked="a1", uploader=creator, artisanId="a1"),
            # An older row: no tag pair, only the typed column.
            _media("m3", uploader=creator, artisanId="a1"),
            _media("rejected", "REJECTED", tag="artisan", linked="a1", uploader=creator, **vars(earlier)),
            _media("decided", "APPROVED", tag="artisan", linked="a1", uploader=creator, **vars(earlier)),
            # Not a1's: a product photograph that also names the artisan.
            _media("pm1", tag="product", linked="p1", uploader=creator, artisanId="a1"),
            _media("pm2", uploader=creator, productId="p1", artisanId="a1"),
            _media("prm", tag="process", linked="pr1", uploader=creator),
            _media("stm", tag="processStep", linked="s2", uploader=creator),
            _media("qm", uploader=creator, questionnaireInterviewId="q1"),
            # An admin's photograph on a researcher's record: above a professor on the ladder.
            _media("above", tag="artisan", linked="a2", uploader=admin),
            _media("late", tag="artisan", linked="a2", uploader=creator,
                   extraMetadata={"workshopSubmission": {"needsAdminApproval": True}}),
        ]
    )
    fake = SimpleNamespace(
        artisan=artisans, reviewlog=_Table(), mediafile=media, user=_Table([creator, reviewer, admin]),
        processstep=steps, workshop=_Table(), productdocumentation=products, tooldocumentation=_Table(),
        process=processes, questionnaireinterview=interviews,
    )
    with_tx(fake, RollbackTx)
    monkeypatch.setattr(review, "db", fake)
    monkeypatch.setattr(
        review,
        "_PENDING_SOURCES",
        [("artisan", artisans, ("name",)), ("product", products, ("productName",)), ("process", processes, ("name",)),
         ("questionnaire", interviews, ("title",))],
    )
    notices: list[dict[str, Any]] = []

    async def record_notice(**kwargs: Any) -> int:
        notices.append(kwargs)
        return 1

    async def record_bulk(items: list[dict[str, Any]], *, actor: Any) -> int:
        return 1

    monkeypatch.setattr(email_outbox, "notify_review_decision", record_notice)
    monkeypatch.setattr(email_outbox, "notify_bulk_approvals", record_bulk)
    _CURRENT["user"] = reviewer
    yield SimpleNamespace(db=fake, creator=creator, reviewer=reviewer, admin=admin, notices=notices)
    _CURRENT["user"] = None


def _file(world: Any, media_id: str) -> SimpleNamespace:
    return next(row for row in world.db.mediafile.rows if row.id == media_id)


def _media_logs(world: Any) -> list[dict[str, Any]]:
    return [log for log in world.db.reviewlog.created if log["recordType"] == "MEDIA"]


def test_approving_a_record_approves_its_pending_files_with_the_same_reviewer_and_moment(world: Any) -> None:
    response = _call("POST", "/artisan/a1/approve", {"notes": None})
    assert response.status_code == 200, response.text
    assert response.json()["mediaApproved"] == 3
    record = world.db.artisan.rows[0]
    for media_id in ("m1", "m2", "m3"):
        media = _file(world, media_id)
        assert media.status == "APPROVED"
        assert (media.reviewedById, media.reviewedAt) == ("prof", record.reviewedAt)
    assert {log["recordId"] for log in _media_logs(world)} == {"m1", "m2", "m3"}
    assert {log["notes"] for log in _media_logs(world)} == {"Approved with its artisan record."}
    assert world.db.tx.opened == 1


def test_a_file_uploaded_after_the_approval_stays_pending(world: Any) -> None:
    """The live cascade takes only the files present when the approval runs."""
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    world.db.mediafile.rows.append(
        _media("after", tag="artisan", linked="a1", uploader=world.creator, artisanId="a1", createdAt=datetime.now(UTC))
    )
    assert _file(world, "after").status == "PENDING"
    assert "after" not in {log["recordId"] for log in _media_logs(world)}


def test_a_rejected_or_already_decided_file_is_never_touched(world: Any) -> None:
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    assert (_file(world, "rejected").status, _file(world, "rejected").reviewedAt) == ("REJECTED", APPROVED_AT)
    assert (_file(world, "decided").reviewedById, _file(world, "decided").reviewedAt) == ("admin", APPROVED_AT)
    assert not {"rejected", "decided"} & {log["recordId"] for log in _media_logs(world)}


def test_files_of_other_records_stay_where_they_are(world: Any) -> None:
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    assert _file(world, "pm1").status == "PENDING" and _file(world, "pm2").status == "PENDING"


@pytest.mark.parametrize(
    ("path", "approved"),
    [("/product/p1/approve", {"pm1", "pm2"}), ("/process/pr1/approve", {"prm", "stm"}), ("/questionnaire/q1/approve", {"qm"})],
)
def test_every_parent_with_files_carries_them(world: Any, path: str, approved: set[str]) -> None:
    response = _call("POST", path, {"notes": None})
    assert response.status_code == 200, response.text
    assert {row.id for row in world.db.mediafile.rows if row.status == "APPROVED" and row.reviewedById == "prof"} == approved


def test_the_ladder_and_the_late_rule_hold_for_files_too(world: Any) -> None:
    assert _call("POST", "/artisan/a2/approve", {"notes": None}).json()["mediaApproved"] == 0
    assert _file(world, "above").status == "PENDING" and _file(world, "late").status == "PENDING"
    _CURRENT["user"] = world.admin
    world.db.artisan.rows[1].status = "PENDING"
    assert _call("POST", "/artisan/a2/approve", {"notes": None}).json()["mediaApproved"] == 1
    late = _file(world, "late")
    assert late.status == "APPROVED" and late.extraMetadata["workshopSubmission"]["needsAdminApproval"] is False


@pytest.mark.parametrize("body", [{"reason": "DUPLICATE", "notes": "Same as Ramesh"}, {"notes": None}])
def test_rejecting_a_record_moves_no_file(world: Any, body: dict[str, Any]) -> None:
    response = _call("POST", "/artisan/a1/reject", body)
    assert response.status_code == 200 and response.json()["status"] == "REJECTED"
    assert "mediaApproved" not in response.json()
    assert [_file(world, m).status for m in ("m1", "m2", "m3")] == ["PENDING"] * 3
    assert _media_logs(world) == []


def test_sending_a_record_back_moves_no_file(world: Any) -> None:
    assert _call("POST", "/artisan/a1/revise", {"notes": "Add the village."}).status_code == 200
    assert _file(world, "m1").status == "PENDING"


def test_a_file_approved_with_its_record_can_still_be_rejected_alone(world: Any) -> None:
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    response = _call("POST", "/media/m2/reject", {"reason": "DUPLICATE", "notes": "Blurred copy of m1"})
    assert response.status_code == 200, response.text
    assert _file(world, "m2").status == "REJECTED"
    assert _file(world, "m1").status == "APPROVED" and world.db.artisan.rows[0].status == "APPROVED"
    assert {"recordType": "MEDIA", "recordId": "m2", "status": "REJECTED"}.items() <= world.db.reviewlog.created[-1].items()


def test_a_pending_file_can_be_rejected_alone_before_its_record_is_decided(world: Any) -> None:
    assert _call("POST", "/media/m1/reject", {"reason": "DUPLICATE", "notes": "Duplicate"}).status_code == 200
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).json()["mediaApproved"] == 2
    assert _file(world, "m1").status == "REJECTED"


def test_a_failure_part_way_leaves_the_record_and_every_file_as_they_were(world: Any) -> None:
    world.db.mediafile.fail_on_update = True
    response = _call("POST", "/artisan/a1/approve", {"notes": None})
    assert response.status_code == 500
    assert world.db.tx.rolled_back == 1
    assert world.db.artisan.rows[0].status == "PENDING"
    assert world.db.reviewlog.created == []
    assert all(row.status in {"PENDING", "REJECTED", "APPROVED"} for row in world.db.mediafile.rows)
    assert _file(world, "m1").status == "PENDING"
    assert world.notices == []  # nobody is told about an approval that did not happen


def test_the_queue_says_how_many_files_go_with_each_approval(world: Any) -> None:
    keys = [("artisan", "a1"), ("artisan", "a2"), ("product", "p1"), ("process", "pr1"), ("questionnaire", "q1")]
    counts = asyncio.run(review.pending_media_counts(keys, world.reviewer))
    assert counts == {("artisan", "a1"): 3, ("artisan", "a2"): 0, ("product", "p1"): 2, ("process", "pr1"): 2,
                      ("questionnaire", "q1"): 1}
    # An admin may take the late file; the other admin's photograph is still not theirs to judge.
    assert asyncio.run(review.pending_media_counts(keys[:2], world.admin))[("artisan", "a2")] == 1


def test_the_paged_queue_carries_the_count(world: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    async def keys(filters: Any, *, limit: int, offset: int) -> list[tuple[str, str]]:
        return [("artisan", "a1"), ("product", "p1")]

    monkeypatch.setattr(review, "pending_page_keys", keys)
    body = _call("GET", "/pending?page=1&pageSize=20").json()
    assert {(item["id"], item["pendingMediaCount"]) for item in body["items"]} == {("a1", 3), ("p1", 2)}


def test_the_record_page_learns_the_count_and_which_files_it_may_reject(world: Any) -> None:
    body = _call("GET", "/artisan/a1/media").json()
    assert body["approvesWith"] == 3
    assert set(body["rejectable"]) == {"m1", "m2", "m3", "decided"}  # never one already rejected
    assert _call("POST", "/artisan/a1/approve", {"notes": None}).status_code == 200
    after = _call("GET", "/artisan/a1/media").json()
    assert after["approvesWith"] == 0 and set(after["rejectable"]) == {"m1", "m2", "m3", "decided"}
    # An admin's photograph is not a professor's to reject.
    assert "above" not in _call("GET", "/artisan/a2/media").json()["rejectable"]
    assert _call("GET", "/media/m1/media").status_code == 404


def test_bulk_approval_reports_the_files_it_approved(world: Any) -> None:
    body = _call("POST", "/bulk-approve", {"items": [{"recordType": "artisan", "id": "a1"},
                                                     {"recordType": "product", "id": "p1"}]}).json()
    assert (body["approved"], body["mediaApproved"]) == (2, 5)


def test_review_decisions_take_an_idempotency_key_and_reads_do_not() -> None:
    for path in ("/api/review/artisan/a1/approve", "/api/review/media/m1/reject", "/api/review/process/x/revise",
                 "/api/review/bulk-approve", "/api/review/questionnaire/q1/approve/"):
        assert idempotency.is_create_route(path), path
    for path in ("/api/review/pending", "/api/review/artisan/a1/latest", "/api/review/artisan/a1/media",
                 "/api/review/artisan/a1/edit", "/api/review/craft/c1/approve", "/api/review/artisan/a/b/approve"):
        assert not idempotency.is_create_route(path), path


# ─── The backfill ────────────────────────────────────────────────────────────────────────────────


def _approved(world: Any) -> None:
    """Records approved before files followed their record: approved, files left pending."""
    for table in (world.db.artisan, world.db.productdocumentation, world.db.process, world.db.questionnaireinterview):
        for row in table.rows:
            row.status, row.reviewedById, row.reviewedAt = "APPROVED", "prof", APPROVED_AT
    world.db.artisan.rows[1].reviewedById = None  # a2: approved with no reviewer on file


def test_the_backfill_dry_run_counts_per_type_and_writes_nothing(world: Any) -> None:
    _approved(world)
    report = asyncio.run(backfill.run(world.db, apply=False, batch_size=1))
    assert report.files == 8 and report.uploaded_later == 0
    assert (report.by_type["artisan"].files, report.by_type["artisan"].no_reviewer) == (3, 1)
    assert report.by_type["artisan"].left_pending == 2
    assert (report.by_type["product"].files, report.by_type["process"].files, report.by_type["questionnaire"].files) == (2, 2, 1)
    assert "Will approve 8 files in total." in report.lines()
    assert "Left 0 files for review: uploaded after their record was approved." in report.lines()
    assert world.db.reviewlog.created == [] and world.db.tx.opened == 0
    assert _file(world, "m1").status == "PENDING"


def test_the_backfill_applies_in_batches_with_the_records_reviewer_and_is_idempotent(world: Any) -> None:
    _approved(world)
    report = asyncio.run(backfill.run(world.db, apply=True, batch_size=1))
    assert report.files == 8 and world.db.tx.opened == 4  # one transaction per batch that had work
    m1 = _file(world, "m1")
    assert (m1.status, m1.reviewedById, m1.reviewedAt) == ("APPROVED", "prof", APPROVED_AT)
    assert len(_media_logs(world)) == 8
    assert {log["notes"] for log in _media_logs(world)} >= {"Approved with its artisan record (backfill)."}
    assert _file(world, "rejected").status == "REJECTED" and _file(world, "above").status == "PENDING"
    again = asyncio.run(backfill.run(world.db, apply=True, batch_size=50))
    assert again.files == 0 and len(_media_logs(world)) == 8


def _uploaded_after_approval(world: Any) -> None:
    """m2 and the product's pm1 arrived after their records were approved; m3 at the very moment."""
    _file(world, "m2").createdAt = datetime(2026, 10, 3, tzinfo=UTC)
    _file(world, "pm1").createdAt = datetime(2026, 10, 2, 9, 31, tzinfo=UTC)
    _file(world, "m3").createdAt = APPROVED_AT


def test_the_backfill_leaves_files_uploaded_after_the_approval_for_review(world: Any) -> None:
    _approved(world)
    _uploaded_after_approval(world)
    dry = asyncio.run(backfill.run(world.db, apply=False))
    assert (dry.files, dry.uploaded_later) == (6, 2)
    assert (dry.by_type["artisan"].files, dry.by_type["artisan"].uploaded_later) == (2, 1)
    assert (dry.by_type["product"].files, dry.by_type["product"].uploaded_later) == (1, 1)
    assert "Will approve 6 files in total." in dry.lines()
    assert "Left 2 files for review: uploaded after their record was approved." in dry.lines()
    done = asyncio.run(backfill.run(world.db, apply=True))
    assert (done.files, done.uploaded_later) == (6, 2)
    assert _file(world, "m3").status == "APPROVED"  # uploaded at the approval moment: it was there
    assert (_file(world, "m2").status, _file(world, "pm1").status) == ("PENDING", "PENDING")
    assert not {"m2", "pm1"} & {log["recordId"] for log in _media_logs(world)}
    assert "Approved 6 files in total." in done.lines()
    again = asyncio.run(backfill.run(world.db, apply=True))
    assert (again.files, again.uploaded_later) == (0, 2)


def test_a_file_with_no_upload_time_is_left_for_review(world: Any) -> None:
    _approved(world)
    _file(world, "m1").createdAt = None
    report = asyncio.run(backfill.run(world.db, apply=False, kinds=("artisan",)))
    assert (report.by_type["artisan"].files, report.by_type["artisan"].uploaded_later) == (2, 1)
