"""The admin side of the gate: the queue, the decision, the count, and the two places a ``User``
write has to keep the roster honest.

THE FAILURE THIS FILE IS MOSTLY ABOUT is not a refusal that fires when it should. It is a refusal
that fires for somebody an administrator believes they have already let in. There are three ways to
arrive at that, and each has a test here:

* an admin creates an account through ``POST /users`` and nobody puts the address on the roster;
* an admin changes an account's email and the roster keeps pointing at the old address;
* an admin approves a request and the approval does not actually admit.

The mirror image is the fourth: an admin deletes an account and the ACTIVE roster row survives it,
so the person they just deleted signs in with Google and gets a brand-new account. That one is here
too.
"""

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


# --- The notification -------------------------------------------------------------------------------


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
    """It holds the addresses of people who tried to get in, which is not information the tiers
    below administration have any reason to hold."""
    install(monkeypatch, FakeDb(roster=[_roster("a@example.org", "PENDING")]))
    for role in ("PROFESSOR", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"):
        _as(role)
        assert _call("GET", "/api/access-roster/pending-count").status_code == 403
        assert _call("GET", "/api/access-roster").status_code == 403


def test_the_count_route_is_not_swallowed_by_the_id_route(monkeypatch: pytest.MonkeyPatch) -> None:
    """Route order is load-bearing: declared the other way round, ``/pending-count`` would be read as
    a roster id and 404 forever."""
    install(monkeypatch, FakeDb(roster=[]))
    _as("ADMIN")
    assert _call("GET", "/api/access-roster/pending-count").json() == {"pending": 0}


# --- The decision -------------------------------------------------------------------------------------


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
    """Somebody suspended in June and restored in September joined in March. Re-stamping on restore
    would quietly rewrite that, and the roster is where the answer is meant to live."""
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
    """PENDING is written only by a refused sign-in. An admin able to set it could quietly undo
    another admin's rejection, and on the queue it would look exactly like a fresh request."""
    install(monkeypatch, FakeDb(roster=[_roster("no@example.org", "REJECTED")]))
    _as("ADMIN")
    response = _call("PATCH", "/api/access-roster/roster-no@example.org", {"status": "PENDING"})
    assert response.status_code == 422


def test_delete_suspends_and_keeps_the_original_decision_date(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """SUSPEND, never delete. A deleted row goes straight back into the pending queue on the next
    attempt, and the date access ended is destroyed by a second click on the button."""
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
    """The gate exempts that address anyway, so a suspended row would not lock them out — it would
    display as though the break-glass were suspended, and the next admin would believe it."""
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
    """The roster is a second way to hand out a tier, so it answers to the same rule ``POST /users``
    does — and it answers to it by CALLING that rule, not by re-implementing it."""
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
    """Overwriting would erase the note recording the original decision — the one thing the row
    exists to preserve."""
    install(monkeypatch, FakeDb(roster=[_roster("person@example.org", "SUSPENDED", notes="Left in May")]))
    _as("ADMIN")
    response = _call("POST", "/api/access-roster", {"email": "person@example.org"})
    assert response.status_code == 409
    assert "roster-person@example.org" in response.json()["detail"]


# --- The four ways an admin can accidentally lock somebody out -------------------------------------------


def test_creating_an_account_admits_it(monkeypatch: pytest.MonkeyPatch) -> None:
    """An account created through the users screen that is not on the roster cannot sign in, and the
    admin would find out from the new user, by telephone, a day later."""
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
    """The gate is keyed by email, so a rename walks the account past its own roster row."""
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
    """The ACTIVE row survives the account, and an ACTIVE row is an instruction to PROVISION. Left
    alone, the person the admin just deleted signs in with Google and gets a fresh account."""
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
    """Those two disagree the moment somebody is promoted through the users screen, and an admin
    reading only ``grantedRole`` would be looking at a stale tier and believe it current."""
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
    """The state an admin needs to be able to see: invited, never turned up."""
    install(monkeypatch, FakeDb(users=[], roster=[_roster("invited@example.org", "ACTIVE")]))
    _as("ADMIN")
    item = _call("GET", "/api/access-roster").json()["items"][0]
    assert item["userId"] is None
    assert item["firstSeenAt"] is None
