import asyncio
from datetime import datetime
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeDb, install
from app.api.router import api_router
from app.api.routes import auth as auth_routes
from app.core.config import get_settings
from app.core.security import hash_password
from app.services.access_roster import (
    ACCESS_PENDING_DETAIL,
    ACCESS_REJECTED_DETAIL,
    ACCESS_SUSPENDED_DETAIL,
    INVALID_CREDENTIALS_DETAIL,
)

PASSWORD = "correct-horse-battery"
MASTER_EMAIL = "master@example.org"

_APP = FastAPI()
_APP.include_router(api_router)


def _post(path: str, body: dict[str, Any]) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
            return await client.post(path, json=body)

    return asyncio.run(run())


def _login(email: str, password: str = PASSWORD) -> httpx.Response:
    return _post("/api/auth/login", {"email": email, "password": password})


def _google(token: str = "a-verified-token") -> httpx.Response:
    return _post("/api/auth/login", {"googleIdToken": token})


def _detail(response: httpx.Response) -> Any:
    return response.json().get("detail")


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
    monkeypatch.setattr(settings, "default_signup_role", "CROWDSOURCE_VOLUNTEER")
    return settings


@pytest.fixture
def google(monkeypatch: pytest.MonkeyPatch):
    def issue(email: str, name: str = "Google Display Name", picture: str = "https://pic") -> None:
        monkeypatch.setattr(
            auth_routes,
            "verify_google_token",
            lambda token: {
                "email": email,
                "email_verified": True,
                "name": name,
                "picture": picture,
            },
        )

    return issue



def test_pending_and_wrong_password_are_different_answers(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("waiting@example.org")],
            roster=[_roster("waiting@example.org", "PENDING")],
        ),
    )

    pending = _login("waiting@example.org")
    wrong = _login("waiting@example.org", "not-the-password")

    assert pending.status_code == 403
    assert _detail(pending) == {"code": "ACCESS_PENDING", "message": ACCESS_PENDING_DETAIL}
    assert wrong.status_code == 401
    assert _detail(wrong) == INVALID_CREDENTIALS_DETAIL
    assert _detail(pending) != _detail(wrong)


def test_the_pending_refusal_leaks_nothing_beyond_the_wait(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("waiting@example.org", role="ADMIN")],
            roster=[
                _roster("waiting@example.org", "PENDING", fullName="Priya Sharma", grantedRole="ADMIN")
            ],
        ),
    )
    body = _login("waiting@example.org").text
    for leak in ("Priya", "Sharma", "ADMIN", "password hash", "waiting@example.org"):
        assert leak not in body, f"The pending refusal leaked {leak!r}: {body}"


def test_rejected_and_suspended_each_get_their_own_words(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("no@example.org"), _account("stopped@example.org")],
            roster=[
                _roster("no@example.org", "REJECTED"),
                _roster("stopped@example.org", "SUSPENDED"),
            ],
        ),
    )
    rejected = _login("no@example.org")
    suspended = _login("stopped@example.org")

    assert rejected.status_code == 403
    assert _detail(rejected) == {"code": "ACCESS_REJECTED", "message": ACCESS_REJECTED_DETAIL}
    assert suspended.status_code == 403
    assert _detail(suspended) == {"code": "ACCESS_SUSPENDED", "message": ACCESS_SUSPENDED_DETAIL}
    assert ACCESS_REJECTED_DETAIL != ACCESS_SUSPENDED_DETAIL != ACCESS_PENDING_DETAIL



@pytest.mark.parametrize("refused_status", ["PENDING", "REJECTED", "SUSPENDED"])
def test_only_an_active_row_admits(monkeypatch: pytest.MonkeyPatch, refused_status: str) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("person@example.org")],
            roster=[_roster("person@example.org", refused_status)],
        ),
    )
    assert _login("person@example.org").status_code == 403


def test_an_active_row_admits(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("person@example.org")],
            roster=[_roster("person@example.org", "ACTIVE")],
        ),
    )
    response = _login("person@example.org")
    assert response.status_code == 200
    assert response.json()["accessToken"]


def test_the_first_sign_in_is_stamped_once(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("person@example.org")],
            roster=[_roster("person@example.org", "ACTIVE")],
        ),
    )
    assert _login("person@example.org").status_code == 200
    first = fake.accessroster.rows[0].firstSeenAt
    assert isinstance(first, datetime)
    assert _login("person@example.org").status_code == 200
    assert fake.accessroster.rows[0].firstSeenAt == first



def test_the_master_admin_signs_in_with_no_roster_row_at_all(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(users=[_account(MASTER_EMAIL, role="MASTER_ADMIN")], roster=[]),
    )
    assert _login(MASTER_EMAIL).status_code == 200


def test_the_master_admin_signs_in_through_a_suspended_row(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account(MASTER_EMAIL, role="MASTER_ADMIN")],
            roster=[_roster(MASTER_EMAIL, "SUSPENDED")],
        ),
    )
    assert _login(MASTER_EMAIL).status_code == 200


def test_a_second_master_admin_is_exempt_by_role(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("second.master@example.org", role="MASTER_ADMIN")],
            roster=[_roster("second.master@example.org", "REJECTED")],
        ),
    )
    assert _login("second.master@example.org").status_code == 200



def test_an_unknown_google_address_gets_no_account_and_no_token(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org")

    response = _google()

    assert response.status_code == 403
    assert _detail(response) == {"code": "ACCESS_PENDING", "message": ACCESS_PENDING_DETAIL}
    assert fake.user.rows == [], "Google self-provisioning must be dead: no account may be created."
    assert [row.email for row in fake.accessroster.rows] == ["stranger@example.org"]
    assert fake.accessroster.rows[0].status == "PENDING"


def test_the_pending_row_stores_the_address_and_nothing_else(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org", name="<script>alert(1)</script>", picture="https://evil/x.png")

    _google()

    row = fake.accessroster.rows[0]
    assert row.fullName is None
    assert row.notes is None
    assert "script" not in str(row.__dict__)
    assert row.grantedRole == "CROWDSOURCE_VOLUNTEER", (
        "An unapproved address must sit at the lowest rung until an admin promotes it on the roster."
    )


def test_an_admitted_google_address_is_provisioned_at_its_granted_tier(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    fake = install(
        monkeypatch,
        FakeDb(users=[], roster=[_roster("invited@example.org", "ACTIVE", grantedRole="RESEARCHER")]),
    )
    google("invited@example.org")

    response = _google()

    assert response.status_code == 200
    assert len(fake.user.rows) == 1
    assert fake.user.rows[0].role == "RESEARCHER"


def test_a_stale_roster_tier_never_demotes_an_existing_account(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("promoted@example.org", role="ADMIN")],
            roster=[
                _roster("promoted@example.org", "ACTIVE", grantedRole="CROWDSOURCE_VOLUNTEER")
            ],
        ),
    )
    google("promoted@example.org")

    assert _google().status_code == 200
    assert fake.user.rows[0].role == "ADMIN"


def test_a_refused_google_sign_in_does_not_touch_the_existing_account(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("stopped@example.org", name="Real Name", avatarUrl=None)],
            roster=[_roster("stopped@example.org", "SUSPENDED")],
        ),
    )
    google("stopped@example.org", name="Rewritten", picture="https://evil/x.png")

    assert _google().status_code == 403
    assert fake.user.rows[0].name == "Real Name"
    assert fake.user.rows[0].avatarUrl is None


def test_the_google_wrapper_endpoint_is_gated_too(monkeypatch: pytest.MonkeyPatch, google) -> None:
    install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org")
    response = _post("/api/auth/google", {"googleIdToken": "a-verified-token"})
    assert response.status_code == 403



def test_a_wrong_password_writes_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(monkeypatch, FakeDb(users=[_account("real@example.org")], roster=[]))

    assert _login("real@example.org", "wrong-password-entirely").status_code == 401
    assert _login("nobody@example.org", "anything-at-all").status_code == 401
    assert fake.accessroster.rows == []


def test_one_person_hammering_the_form_is_one_row(monkeypatch: pytest.MonkeyPatch, google) -> None:
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org")

    for _ in range(10):
        assert _google().status_code == 403

    assert len(fake.accessroster.rows) == 1
    row = fake.accessroster.rows[0]
    assert row.requestCount == 10
    assert row.lastRequestedAt >= row.firstRequestedAt


def test_a_rejected_person_does_not_re_queue(monkeypatch: pytest.MonkeyPatch, google) -> None:
    fake = install(
        monkeypatch,
        FakeDb(users=[], roster=[_roster("no@example.org", "REJECTED", requestCount=3)]),
    )
    google("no@example.org")

    response = _google()

    assert _detail(response)["code"] == "ACCESS_REJECTED"
    row = fake.accessroster.rows[0]
    assert row.status == "REJECTED"
    assert row.requestCount == 4


def test_at_the_ceiling_a_new_address_is_refused_without_a_row(
    monkeypatch: pytest.MonkeyPatch, google, settings_under_test
) -> None:
    monkeypatch.setattr(settings_under_test, "access_roster_max_pending", 1)
    fake = install(monkeypatch, FakeDb(users=[], roster=[_roster("first@example.org", "PENDING")]))
    google("second@example.org")

    response = _google()

    assert response.status_code == 403
    assert _detail(response) == {"code": "ACCESS_PENDING", "message": ACCESS_PENDING_DETAIL}
    assert [row.email for row in fake.accessroster.rows] == ["first@example.org"]


def test_the_ceiling_does_not_stop_an_existing_row_from_counting(
    monkeypatch: pytest.MonkeyPatch, google, settings_under_test
) -> None:
    monkeypatch.setattr(settings_under_test, "access_roster_max_pending", 1)
    fake = install(monkeypatch, FakeDb(users=[], roster=[_roster("first@example.org", "PENDING")]))
    google("first@example.org")

    assert _google().status_code == 403
    assert fake.accessroster.rows[0].requestCount == 2



def test_the_kill_switch_restores_the_previous_behaviour(
    monkeypatch: pytest.MonkeyPatch, google, settings_under_test
) -> None:
    monkeypatch.setattr(settings_under_test, "access_roster_enforced", False)
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("locked.out@example.org")],
            roster=[_roster("locked.out@example.org", "PENDING")],
        ),
    )

    assert _login("locked.out@example.org").status_code == 200

    google("stranger@example.org")
    assert _google().status_code == 200
    assert len(fake.user.rows) == 2
