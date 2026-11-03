import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeDb, install
from app.api.router import api_router
from app.api.routes import auth as auth_routes
from app.core import deps
from app.core.config import get_settings
from app.core.security import hash_password

PASSWORD = "correct-horse-battery"
MASTER_EMAIL = "master@example.org"

_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://roster.test") as client:
            return await client.request(method, path, json=body)

    return asyncio.run(run())


def _as(role: str = "ADMIN", user_id: str = "admin-1") -> SimpleNamespace:
    caller = SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.org",
        name="An Admin",
        role=role,
        canManageQuestionnaire=False,
        canManageCrafts=False,
        canManageWorkshops=False,
        canReview=False,
        canViewProvenance=False,
        canDownloadDataset=False,
    )
    _CURRENT["user"] = caller
    return caller


def _account(email: str, role: str = "RESEARCHER", **extra: Any) -> dict[str, Any]:
    return {
        "id": f"id-{email}",
        "email": email,
        "name": "Existing Person",
        "role": role,
        "passwordHash": hash_password(PASSWORD),
        **extra,
    }


def _roster(email: str, status: str, **extra: Any) -> dict[str, Any]:
    return {"id": f"roster-{email}", "email": email, "status": status, **extra}


@pytest.fixture(autouse=True)
def settings_under_test(monkeypatch: pytest.MonkeyPatch) -> Any:
    settings = get_settings()
    monkeypatch.setattr(settings, "master_admin_email", MASTER_EMAIL)
    monkeypatch.setattr(settings, "access_roster_enforced", True)
    monkeypatch.setattr(settings, "access_roster_max_pending", 2000)
    yield settings
    _CURRENT["user"] = None



def test_the_count_counts_only_pending(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            roster=[
                _roster("a@example.org", "PENDING"),
                _roster("b@example.org", "PENDING"),
                _roster("c@example.org", "ACTIVE"),
                _roster("d@example.org", "REJECTED"),
                _roster("e@example.org", "SUSPENDED"),
            ]
        ),
    )
    _as("ADMIN")
    response = _call("GET", "/api/access-roster/pending-count")
    assert response.status_code == 200
    assert response.json() == {"pending": 2}


def test_the_queue_is_admin_only(monkeypatch: pytest.MonkeyPatch) -> None:
    install(monkeypatch, FakeDb(roster=[_roster("a@example.org", "PENDING")]))
    for role in ("PROFESSOR", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"):
        _as(role)
        assert _call("GET", "/api/access-roster/pending-count").status_code == 403
        assert _call("GET", "/api/access-roster").status_code == 403


def test_the_count_route_is_not_swallowed_by_the_id_route(monkeypatch: pytest.MonkeyPatch) -> None:
    install(monkeypatch, FakeDb(roster=[]))
    _as("ADMIN")
    assert _call("GET", "/api/access-roster/pending-count").json() == {"pending": 0}



def test_approving_admits_and_the_person_can_sign_in(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("waiting@example.org")],
            roster=[_roster("waiting@example.org", "PENDING")],
        ),
    )
    _as("ADMIN")

    approved = _call(
        "PATCH", "/api/access-roster/roster-waiting@example.org", {"status": "ACTIVE"}
    )
    assert approved.status_code == 200
    assert approved.json()["status"] == "ACTIVE"
    assert approved.json()["joinedAt"], "Approval is the date of joining the platform."

    _CURRENT["user"] = None
    login = _call(
        "POST", "/api/auth/login", {"email": "waiting@example.org", "password": PASSWORD}
    )
    assert login.status_code == 200
    assert fake.accessroster.rows[0].decidedById == "admin-1"


def test_the_joining_date_survives_a_suspension_and_a_restore(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    joined = datetime(2026, 3, 1, tzinfo=UTC)
    install(
        monkeypatch,
        FakeDb(roster=[_roster("person@example.org", "ACTIVE", joinedAt=joined)]),
    )
    _as("ADMIN")
    _call("DELETE", "/api/access-roster/roster-person@example.org")
    restored = _call(
        "PATCH", "/api/access-roster/roster-person@example.org", {"status": "ACTIVE"}
    )
    assert restored.json()["joinedAt"] == joined.isoformat()


def test_a_request_cannot_be_pushed_back_into_the_queue(monkeypatch: pytest.MonkeyPatch) -> None:
    install(monkeypatch, FakeDb(roster=[_roster("no@example.org", "REJECTED")]))
    _as("ADMIN")
    response = _call("PATCH", "/api/access-roster/roster-no@example.org", {"status": "PENDING"})
    assert response.status_code == 422


def test_delete_suspends_and_keeps_the_original_decision_date(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = install(monkeypatch, FakeDb(roster=[_roster("person@example.org", "ACTIVE")]))
    _as("ADMIN")

    first = _call("DELETE", "/api/access-roster/roster-person@example.org")
    assert first.status_code == 200
    assert first.json()["status"] == "SUSPENDED"
    ended = first.json()["decidedAt"]

    second = _call("DELETE", "/api/access-roster/roster-person@example.org")
    assert second.json()["decidedAt"] == ended
    assert len(fake.accessroster.rows) == 1


def test_the_master_admin_row_cannot_be_taken_off_the_list(monkeypatch: pytest.MonkeyPatch) -> None:
    install(monkeypatch, FakeDb(roster=[_roster(MASTER_EMAIL, "ACTIVE")]))
    _as("MASTER_ADMIN")
    assert _call("DELETE", f"/api/access-roster/roster-{MASTER_EMAIL}").status_code == 403
    assert (
        _call("PATCH", f"/api/access-roster/roster-{MASTER_EMAIL}", {"status": "SUSPENDED"}).status_code
        == 403
    )


def test_an_admin_cannot_admit_somebody_above_their_own_tier(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    install(monkeypatch, FakeDb(roster=[]))
    _as("ADMIN")
    response = _call(
        "POST",
        "/api/access-roster",
        {"email": "someone@example.org", "grantedRole": "MASTER_ADMIN"},
    )
    assert response.status_code == 403


def test_adding_an_address_that_is_already_listed_is_a_named_conflict(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    install(monkeypatch, FakeDb(roster=[_roster("person@example.org", "SUSPENDED", notes="Left in May")]))
    _as("ADMIN")
    response = _call("POST", "/api/access-roster", {"email": "person@example.org"})
    assert response.status_code == 409
    assert "roster-person@example.org" in response.json()["detail"]



def test_creating_an_account_admits_it(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    _as("ADMIN")

    created = _call(
        "POST",
        "/api/users",
        {
            "email": "new.person@example.org",
            "name": "New Person",
            "password": PASSWORD,
            "role": "RESEARCHER",
        },
    )
    assert created.status_code == 201
    assert [row.email for row in fake.accessroster.rows] == ["new.person@example.org"]
    assert fake.accessroster.rows[0].status == "ACTIVE"

    _CURRENT["user"] = None
    login = _call(
        "POST", "/api/auth/login", {"email": "new.person@example.org", "password": PASSWORD}
    )
    assert login.status_code == 200


def test_renaming_an_account_admits_the_new_address(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("old@example.org", role="RESEARCHER")],
            roster=[_roster("old@example.org", "ACTIVE")],
        ),
    )
    _as("ADMIN")

    renamed = _call("PATCH", "/api/users/id-old@example.org", {"email": "new@example.org"})
    assert renamed.status_code == 200

    _CURRENT["user"] = None
    login = _call("POST", "/api/auth/login", {"email": "new@example.org", "password": PASSWORD})
    assert login.status_code == 200, (
        "A renamed account must not be locked out by a roster row still holding the old address."
    )
    assert {row.email for row in fake.accessroster.rows} == {"old@example.org", "new@example.org"}


def test_deleting_an_account_closes_the_door_behind_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("gone@example.org")],
            roster=[_roster("gone@example.org", "ACTIVE")],
        ),
    )
    _as("ADMIN")

    assert _call("DELETE", "/api/users/id-gone@example.org").status_code == 204
    assert fake.accessroster.rows[0].status == "SUSPENDED"

    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda token: {"email": "gone@example.org", "email_verified": True, "name": "Gone"},
    )
    _CURRENT["user"] = None
    response = _call("POST", "/api/auth/login", {"googleIdToken": "a-verified-token"})
    assert response.status_code == 403
    assert fake.user.rows == []


def test_the_roster_shows_the_accounts_live_tier_beside_the_granted_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("person@example.org", role="ADMIN")],
            roster=[_roster("person@example.org", "ACTIVE", grantedRole="CROWDSOURCE_VOLUNTEER")],
        ),
    )
    _as("ADMIN")
    item = _call("GET", "/api/access-roster").json()["items"][0]
    assert item["grantedRole"] == "CROWDSOURCE_VOLUNTEER"
    assert item["accountRole"] == "ADMIN"
    assert item["userId"] == "id-person@example.org"


def test_an_admitted_address_with_no_account_shows_as_such(monkeypatch: pytest.MonkeyPatch) -> None:
    install(monkeypatch, FakeDb(users=[], roster=[_roster("invited@example.org", "ACTIVE")]))
    _as("ADMIN")
    item = _call("GET", "/api/access-roster").json()["items"][0]
    assert item["userId"] is None
    assert item["firstSeenAt"] is None


def test_a_change_of_role_on_manage_users_writes_the_audit_entry(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    from app.services import signin_role

    install(
        monkeypatch,
        FakeDb(
            users=[_account("raised@example.org", role="RESEARCHER")],
            roster=[_roster("raised@example.org", "ACTIVE")],
        ),
    )
    _as("ADMIN")
    caplog.set_level("WARNING", logger=signin_role.logger.name)

    raised = _call("PATCH", "/api/users/id-raised@example.org", {"role": "PROFESSOR"})
    assert raised.status_code == 200, raised.text
    echoed = _call("PATCH", "/api/users/id-raised@example.org", {"role": "PROFESSOR"})
    assert echoed.status_code == 200, echoed.text

    audits = [r.getMessage() for r in caplog.records if r.getMessage().startswith(signin_role.ROLE_AUDIT_PREFIX)]
    assert len(audits) == 1, audits
    assert "user=id-raised@example.org by=admin-1 from=RESEARCHER to=PROFESSOR via=users" in audits[0]
