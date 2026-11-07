import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from review_tx_fakes import with_tx

ALL_ROLES = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "PROFESSOR",
    "ADMIN",
    "MASTER_ADMIN",
)

EDIT_BODY = {"fields": {"notes": "Corrected the village spelling."}, "note": "Typo."}


def _user(role: str, user_id: str = "reviewer") -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.test",
        name=role.title(),
        role=role,
        canReview=False,
        canDownloadDataset=False,
        canViewProvenance=False,
    )


def _record(creator_role: str) -> SimpleNamespace:
    return SimpleNamespace(
        id="a1",
        name="Kalu Ram",
        notes="The village name is misspelt.",
        place="Bagru",
        status="PENDING",
        extraMetadata={},
        createdById="author",
        createdBy=_user(creator_role, user_id="author"),
        workshopId=None,
        aadhaarNumber=None,
        pehchanCardNumber=None,
        pehchanCardAvailable=False,
    )


class _Delegate:
    def __init__(self, row: Any = None) -> None:
        self.row = row
        self.updates: list[dict] = []
        self.creates: list[dict] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> Any:
        return self.row

    async def find_first(self, where: dict, include: dict | None = None) -> Any:
        return None

    async def find_many(self, where: dict, include: dict | None = None, **_: Any) -> list:
        return []

    async def update(self, where: dict, data: dict, include: dict | None = None) -> Any:
        self.updates.append(data)
        return self.row

    async def update_many(self, where: dict, data: dict) -> int:
        self.updates.append(data)
        return 1

    async def create(self, data: dict) -> Any:
        self.creates.append(data)
        return SimpleNamespace(id="new")


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _Queue:
    def __init__(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self.artisan = _Delegate()
        self.reviewlog = _Delegate()
        self.recordrevision = _Delegate()
        fake_db = SimpleNamespace(
            artisan=self.artisan,
            workshop=_Delegate(),
            productdocumentation=_Delegate(),
            tooldocumentation=_Delegate(),
            process=_Delegate(),
            questionnaireinterview=_Delegate(),
            mediafile=_Delegate(),
            reviewlog=self.reviewlog,
            recordrevision=self.recordrevision,
            user=_Delegate(),
        )
        with_tx(fake_db)
        real_db = core_db.db
        monkeypatch.setattr(core_db, "db", fake_db)
        for module in list(sys.modules.values()):
            if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
                monkeypatch.setattr(module, "db", fake_db)

    def holding(self, creator_role: str) -> "_Queue":
        self.artisan.row = _record(creator_role)
        self.artisan.updates.clear()
        return self

    def as_(self, role: str) -> "_Queue":
        _CURRENT["user"] = _user(role)
        return self

    def post(self, path: str, body: dict) -> httpx.Response:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://review.test") as client:
                return await client.post(f"/api/review{path}", json=body)

        return asyncio.run(run())


@pytest.fixture
def queue(monkeypatch: pytest.MonkeyPatch):
    q = _Queue(monkeypatch)
    yield q
    _CURRENT["user"] = None


@pytest.mark.parametrize("creator_role", ALL_ROLES)
@pytest.mark.parametrize("editor_role", ALL_ROLES)
def test_the_edit_route_admits_exactly_who_may_edit_others_work(
    queue: _Queue, editor_role: str, creator_role: str
) -> None:
    allowed = deps.can_edit_others_record(_user(editor_role), creator_role)

    response = queue.holding(creator_role).as_(editor_role).post("/artisan/a1/edit", EDIT_BODY)

    assert (response.status_code == 200) is allowed, (editor_role, creator_role, response.text)
    assert bool(queue.artisan.updates) is allowed, (editor_role, creator_role)


@pytest.mark.parametrize("editor_role", ["FIELD_CONTRIBUTOR", "RESEARCHER"])
def test_a_reviewer_below_professor_is_refused_before_anything_is_written(
    queue: _Queue, editor_role: str
) -> None:
    response = queue.holding("CROWDSOURCE_VOLUNTEER").as_(editor_role).post("/artisan/a1/edit", EDIT_BODY)

    assert response.status_code == 403, response.text
    assert "Professor access or above" in response.json()["detail"]
    assert queue.artisan.updates == []
    assert queue.recordrevision.creates == []
    assert queue.reviewlog.creates == []


@pytest.mark.parametrize("editor_role", ["FIELD_CONTRIBUTOR", "RESEARCHER"])
def test_the_same_reviewer_keeps_every_action_that_is_not_a_rewrite(
    queue: _Queue, editor_role: str
) -> None:
    caller = queue.holding("CROWDSOURCE_VOLUNTEER").as_(editor_role)

    approved = caller.post("/artisan/a1/approve", {"notes": "Looks right."})
    rejected = caller.post("/artisan/a1/reject", {"notes": "Wrong artisan."})
    revised = caller.post("/artisan/a1/revise", {"notes": "Please re-check the village."})

    assert [approved.status_code, rejected.status_code, revised.status_code] == [200, 200, 200]
    assert [update["status"] for update in queue.artisan.updates] == [
        "APPROVED",
        "REJECTED",
        "NEEDS_REVISION",
    ]


def test_a_professor_may_not_rewrite_a_peers_record(queue: _Queue) -> None:
    response = queue.holding("PROFESSOR").as_("PROFESSOR").post("/artisan/a1/edit", EDIT_BODY)

    assert response.status_code == 403, response.text
    assert queue.artisan.updates == []


def test_a_professor_fixes_a_researchers_record_and_leaves_the_audit_trail(queue: _Queue) -> None:
    response = queue.holding("RESEARCHER").as_("PROFESSOR").post("/artisan/a1/edit", EDIT_BODY)

    assert response.status_code == 200, response.text
    assert queue.artisan.updates[0]["notes"] == "Corrected the village spelling."
    assert "status" not in queue.artisan.updates[0]
    changes = queue.recordrevision.creates[0]["changes"].data
    assert changes["notes"]["old"] == "The village name is misspelt."
    assert changes["notes"]["new"] == "Corrected the village spelling."
    log = queue.reviewlog.creates[0]
    assert log["notes"].startswith("EDITED: notes")
    assert log["status"] == "PENDING"


def test_a_review_edit_will_not_write_a_masked_identity_number(queue: _Queue) -> None:
    body = {"fields": {"aadhaarNumber": "XXXX XXXX 9012", "pehchanCardNumber": "XXXX XXXX 1234", "notes": "Seen."}}

    response = queue.holding("RESEARCHER").as_("PROFESSOR").post("/artisan/a1/edit", body)

    assert response.status_code == 200, response.text
    written = queue.artisan.updates[0]
    assert "aadhaarNumber" not in written
    assert "pehchanCardNumber" not in written
    assert written["notes"] == "Seen."
