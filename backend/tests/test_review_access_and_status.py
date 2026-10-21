"""Reviewers' comment and history access (C20), the sharing notices (A5), the dashboard's pending and
sent-back counts (A2, A13), a task's pending records (B12), and the review-status filter on the
data surfaces (C21).

NOTHING HERE TOUCHES A DATABASE: every module's ``db`` is an in-memory stand-in.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, HTTPException

from app.api.router import api_router
from app.api.routes import dashboard, data_access, data_browser, datasets, export, tasks
from app.core import deps
from app.services import email_outbox


def _user(role: str, user_id: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id, email=f"{user_id}@example.test", name=f"{role.title()} {user_id}", role=role,
        canReview=False, canDownloadDataset=False, canViewProvenance=False,
    )


_CURRENT: dict[str, Any] = {"user": None}
_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


def _call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://access.test") as client:
            return await client.request(method, f"/api{path}", json=body)

    return asyncio.run(run())


class _Lookup:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.created: list[dict[str, Any]] = []

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return next((r for r in self.rows if r.id == where.get("id")), None)

    async def find_many(self, **kwargs: Any) -> list[Any]:
        return list(self.rows)

    async def create(self, data: dict[str, Any], include: Any = None) -> Any:
        self.created.append(data)
        return SimpleNamespace(id="new", author=None, createdAt=datetime.now(UTC), **data)


@pytest.fixture
def sharing(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    owner = _user("RESEARCHER", "owner")
    professor = _user("PROFESSOR", "prof")
    peer = _user("RESEARCHER", "peer")
    record = SimpleNamespace(id="a1", name="Ramesh", createdById="owner")
    fake = SimpleNamespace(
        artisan=_Lookup([record]),
        user=_Lookup([owner, professor, peer]),
        entrycomment=_Lookup([]),
        recordrevision=_Lookup([SimpleNamespace(id="rev1", changes={})]),
    )
    monkeypatch.setattr(data_access, "db", fake)
    monkeypatch.setattr(data_access, "RECORD_DELEGATES", {"artisan": (fake.artisan, "createdById")})
    monkeypatch.setattr(data_access.mailer, "mail_configured", lambda *a, **k: True)

    async def no_grant(*args: Any, **kwargs: Any) -> None:
        return None

    monkeypatch.setattr(data_access, "effective_tier_for_record", no_grant)
    comments: list[dict[str, Any]] = []

    async def record_comment(**kwargs: Any) -> int:
        comments.append(kwargs)
        return 1

    monkeypatch.setattr(email_outbox, "notify_comment", record_comment)
    yield SimpleNamespace(db=fake, owner=owner, professor=professor, peer=peer, comments=comments)
    _CURRENT["user"] = None


def test_a_reviewer_may_comment_without_asking_for_a_grant_and_the_owner_hears(sharing: Any) -> None:
    _CURRENT["user"] = sharing.professor
    response = _call("POST", "/data-access/comments", {"recordType": "artisan", "recordId": "a1", "body": "Which village?"})
    assert response.status_code == 201, response.text
    (notice,) = sharing.comments
    assert notice["owner_id"] == "owner" and notice["record_label"] == "Ramesh"


def test_a_peer_without_a_grant_still_needs_to_ask(sharing: Any) -> None:
    _CURRENT["user"] = sharing.peer
    response = _call("POST", "/data-access/comments", {"recordType": "artisan", "recordId": "a1", "body": "Hi"})
    assert response.status_code == 403
    assert sharing.comments == []


def test_a_reviewer_may_read_the_edit_history_and_a_peer_may_not(sharing: Any) -> None:
    _CURRENT["user"] = sharing.professor
    assert _call("GET", "/data-access/revisions?recordType=artisan&recordId=a1").status_code == 200
    _CURRENT["user"] = sharing.peer
    assert _call("GET", "/data-access/revisions?recordType=artisan&recordId=a1").status_code == 403


def test_asking_for_access_and_deciding_it_each_notify_the_other_side(monkeypatch: pytest.MonkeyPatch) -> None:
    owner = _user("RESEARCHER", "owner")
    asker = _user("RESEARCHER", "asker")
    calls: list[tuple[str, dict[str, Any]]] = []

    async def recorder_request(**kwargs: Any) -> int:
        calls.append(("request", kwargs))
        return 1

    async def recorder_decision(**kwargs: Any) -> int:
        calls.append(("decision", kwargs))
        return 1

    grant = SimpleNamespace(id="g1", ownerId="owner", granteeId="asker", status="PENDING", tier="COMMENT", allData=True, scopeItems=[])

    class _Grants:
        async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
            return grant if where.get("id") == "g1" else None

    async def upsert(owner_id: str, grantee_id: str, **kwargs: Any) -> Any:
        return SimpleNamespace(id="g1", ownerId=owner_id, granteeId=grantee_id, status=kwargs["status_value"])

    monkeypatch.setattr(data_access, "db", SimpleNamespace(user=_Lookup([owner, asker]), dataaccessgrant=_Grants()))
    monkeypatch.setattr(data_access, "_upsert_grant", upsert)
    monkeypatch.setattr(email_outbox, "notify_access_request", recorder_request)
    monkeypatch.setattr(email_outbox, "notify_access_decision", recorder_decision)

    _CURRENT["user"] = asker
    assert _call("POST", "/data-access/requests", {"ownerId": "owner", "tier": "COMMENT", "requestNote": "For the report"}).status_code == 201
    _CURRENT["user"] = owner
    assert _call("POST", "/data-access/grants/g1/decide", {"status": "GRANTED"}).status_code == 200
    _CURRENT["user"] = None
    assert [kind for kind, _ in calls] == ["request", "decision"]
    assert calls[0][1]["owner_id"] == "owner" and calls[0][1]["note"] == "For the report"
    assert calls[1][1]["grantee_id"] == "asker" and calls[1][1]["decision"] == "GRANTED"


# --- The dashboard -----------------------------------------------------------------------------


class _Grouped:
    def __init__(self, counts: dict[str, int]) -> None:
        self.counts = counts

    async def group_by(self, **kwargs: Any) -> list[dict[str, Any]]:
        return [{"status": state, "_count": {"_all": n}} for state, n in self.counts.items()]

    async def count(self, where: dict[str, Any]) -> int:
        return self.counts.get(where.get("status", ""), 0) if "status" in where else sum(self.counts.values())

    async def find_many(self, **kwargs: Any) -> list[Any]:
        return []


def test_the_dashboard_counts_pending_processes_and_what_was_sent_back(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = SimpleNamespace(
        artisan=_Grouped({"PENDING": 1, "NEEDS_REVISION": 2}),
        workshop=_Grouped({}),
        productdocumentation=_Grouped({"REJECTED": 1}),
        tooldocumentation=_Grouped({}),
        process=_Grouped({"PENDING": 3, "NEEDS_REVISION": 1}),
        questionnaireinterview=_Grouped({"PENDING": 1}),
        mediafile=_Grouped({}),
    )
    monkeypatch.setattr(dashboard, "db", fake)

    async def nowhere(*args: Any, **kwargs: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(dashboard, "viewable_where", nowhere)
    monkeypatch.setattr(dashboard, "own_rows_where", nowhere)
    _CURRENT["user"] = _user("RESEARCHER", "me")
    body = _call("GET", "/dashboard/stats").json()
    _CURRENT["user"] = None
    assert body["pendingSubmissions"] == 1 + 3 + 1
    assert body["mine"]["pendingSubmissions"] == 1 + 3 + 1
    assert body["mine"]["sentBack"] == 3 and body["mine"]["rejected"] == 1


# --- A task's pending records ------------------------------------------------------------------


class _Pending:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.wheres: list[dict[str, Any]] = []

    async def count(self, where: dict[str, Any]) -> int:
        self.wheres.append(where)
        return len(self.rows)

    async def find_many(self, where: dict[str, Any], order: Any = None, take: int | None = None) -> list[Any]:
        return self.rows[:take]


def test_a_task_lists_its_records_waiting_for_review_and_the_queue_filter(monkeypatch: pytest.MonkeyPatch) -> None:
    task = SimpleNamespace(
        id="t1", assigneeId="u1", createdById="boss", workshopId="w1", recordTypes=["product", "media"], artisanIds=["a1"]
    )
    products = _Pending(
        [SimpleNamespace(id="p1", productName="Dupatta", submittedAt=datetime(2026, 10, 2, tzinfo=UTC), createdAt=None)]
    )

    async def require(task_id: str) -> Any:
        return task

    monkeypatch.setattr(tasks, "require_task", require)
    monkeypatch.setattr(tasks, "db", SimpleNamespace(productdocumentation=products))
    _CURRENT["user"] = _user("RESEARCHER", "u1")
    body = _call("GET", "/tasks/t1/pending-records").json()
    assert body["total"] == 1 and body["byType"] == {"product": 1}
    assert body["items"][0]["label"] == "Dupatta"
    assert body["reviewQuery"] == {"createdById": "u1", "workshopId": "w1", "recordType": "product", "artisanId": "a1"}
    assert products.wheres[0] == {"createdById": "u1", "workshopId": "w1", "artisanId": {"in": ["a1"]}, "status": "PENDING"}
    _CURRENT["user"] = _user("RESEARCHER", "stranger")
    assert _call("GET", "/tasks/t1/pending-records").status_code == 403
    _CURRENT["user"] = None


def test_a_task_of_every_kind_still_names_its_kinds_so_the_queue_leaves_workshops_out(monkeypatch: pytest.MonkeyPatch) -> None:
    task = SimpleNamespace(id="t2", assigneeId="u1", createdById="boss", workshopId=None, recordTypes=[], artisanIds=[])

    async def require(task_id: str) -> Any:
        return task

    empty = _Pending([])
    monkeypatch.setattr(tasks, "require_task", require)
    monkeypatch.setattr(
        tasks,
        "db",
        SimpleNamespace(artisan=empty, productdocumentation=empty, tooldocumentation=empty, process=empty, questionnaireinterview=empty),
    )
    _CURRENT["user"] = _user("RESEARCHER", "u1")
    body = _call("GET", "/tasks/t2/pending-records").json()
    _CURRENT["user"] = None
    assert body["reviewQuery"] == {"createdById": "u1", "recordType": "artisan,product,tool,process,questionnaire"}


def test_one_request_answers_every_card_with_what_the_single_task_route_says(monkeypatch: pytest.MonkeyPatch) -> None:
    """``GET /tasks/pending-records?ids=`` (the task cards' one request): per visible task the same
    total, byType and reviewQuery as ``GET /tasks/{id}/pending-records``; an unseen task is absent;
    two tasks with one scope share one COUNT."""
    mine = SimpleNamespace(
        id="t1", assigneeId="u1", createdById="boss", workshopId="w1", recordTypes=["product", "media"], artisanIds=["a1"]
    )
    twin = SimpleNamespace(
        id="t3", assigneeId="u1", createdById="boss", workshopId="w1", recordTypes=["product"], artisanIds=["a1"]
    )
    theirs = SimpleNamespace(id="t9", assigneeId="u2", createdById="boss", workshopId="w1", recordTypes=["product"], artisanIds=[])
    products = _Pending(
        [SimpleNamespace(id="p1", productName="Dupatta", submittedAt=datetime(2026, 10, 2, tzinfo=UTC), createdAt=None)]
    )

    class _Tasks:
        async def find_many(self, where: dict[str, Any]) -> list[Any]:
            return [t for t in (mine, twin, theirs) if t.id in where["id"]["in"]]

    async def require(task_id: str) -> Any:
        return {"t1": mine, "t3": twin}[task_id]

    monkeypatch.setattr(tasks, "require_task", require)
    monkeypatch.setattr(tasks, "db", SimpleNamespace(productdocumentation=products, assignedtask=_Tasks()))
    _CURRENT["user"] = _user("RESEARCHER", "u1")
    batch = _call("GET", "/tasks/pending-records?ids=t1,t3,t9,missing").json()["tasks"]
    assert set(batch) == {"t1", "t3"}
    assert len(products.wheres) == 1, "one COUNT for the scope both tasks share"
    for task_id in ("t1", "t3"):
        single = _call("GET", f"/tasks/{task_id}/pending-records").json()
        assert batch[task_id] == {key: single[key] for key in ("total", "byType", "reviewQuery")}
    too_many = ",".join(f"t{i}" for i in range(tasks.PENDING_BATCH_LIMIT + 1))
    assert _call("GET", f"/tasks/pending-records?ids={too_many}").status_code == 422
    _CURRENT["user"] = None


# --- The review-status filter on the data surfaces -----------------------------------------------


def test_the_dataset_filter_narrows_by_status_and_leaves_crafts_whole() -> None:
    where = datasets._build_where(
        datasets.DATASETS["products"], workshop_ids=None, created_by=None, updated_since=None,
        created_since=None, status_filter="APPROVED",
    )
    assert where == {"AND": [{"status": "APPROVED"}]}
    crafts = datasets._build_where(
        datasets.DATASETS["crafts"], workshop_ids=None, created_by=None, updated_since=None,
        created_since=None, status_filter="APPROVED",
    )
    assert crafts == {}
    assert datasets._status_or_422("all") is None and datasets._status_or_422("approved") == "APPROVED"
    with pytest.raises(HTTPException):
        datasets._status_or_422("SHIPPED")


def test_the_data_page_scope_carries_the_status_to_records_and_media(monkeypatch: pytest.MonkeyPatch) -> None:
    async def visible(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
        return {owner_field: "me"}

    monkeypatch.setattr(data_browser, "owned_or_granted_where", visible)
    scope = asyncio.run(data_browser._scope_for(SimpleNamespace(id="me"), "APPROVED"))
    assert scope.records == {"AND": [{"createdById": "me"}, {"status": "APPROVED"}]}
    assert scope.media == {"AND": [{"uploadedById": "me"}, {"status": "APPROVED"}]}
    whole = asyncio.run(data_browser._scope_for(SimpleNamespace(id="me")))
    assert whole.records == {"createdById": "me"}
    assert data_browser.data_status(None) is None and data_browser.data_status("ALL") is None
    with pytest.raises(HTTPException):
        data_browser.data_status("DRAFTY")


def test_the_csv_export_asks_for_a_status_only_when_told() -> None:
    assert export._with_status({}, None) == {}
    assert export._with_status({}, "approved") == {"status": "APPROVED"}
    assert export._with_status({"createdById": "me"}, "APPROVED") == {"AND": [{"createdById": "me"}, {"status": "APPROVED"}]}
