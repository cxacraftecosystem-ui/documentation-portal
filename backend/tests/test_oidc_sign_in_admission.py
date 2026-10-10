"""Microsoft and Yahoo sign-in through ``POST /auth/login``: the gate, the account, the refusals.

A PROVED Microsoft or Yahoo identity is gated exactly as a proved Google one is — the roster decides
before anything is written, an unknown address becomes a PENDING row and gets no account, a refused
one is answered in the roster's own words — and an admitted one signs in to the account at its
normalised address or gets a new one at the tier the roster grants. Every request carries a real
signed token from ``oidc_fake_idp``; only the provider's two HTTP answers and the database are fakes
(``access_roster_fakes``), so this runs without Postgres.
"""

import asyncio
from typing import Any

import httpx
import pytest
from access_roster_fakes import FakeDb, install
from fastapi import FastAPI
from oidc_fake_idp import WORK_TENANT, FakeIdP, login_body

from app.api.router import api_router
from app.core.config import get_settings
from app.core.security import hash_password
from app.services.access_roster import ACCESS_PENDING_DETAIL, ACCESS_SUSPENDED_DETAIL

PASSWORD = "correct-horse-battery"
_APP = FastAPI()
_APP.include_router(api_router)


def _post(body: dict[str, Any]) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
            return await client.post("/api/auth/login", json=body)

    return asyncio.run(run())


@pytest.fixture(autouse=True)
def settings_under_test(monkeypatch: pytest.MonkeyPatch) -> Any:
    settings = get_settings()
    monkeypatch.setattr(settings, "master_admin_email", "master@example.org")
    monkeypatch.setattr(settings, "access_roster_enforced", True)
    monkeypatch.setattr(settings, "access_roster_max_pending", 2000)
    monkeypatch.setattr(settings, "default_signup_role", "CROWDSOURCE_VOLUNTEER")
    return settings


def _microsoft(monkeypatch: pytest.MonkeyPatch, email: str, **claims: Any) -> httpx.Response:
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.microsoft_claims(email, **claims)))
    return _post(login_body("MICROSOFT"))


def _yahoo(monkeypatch: pytest.MonkeyPatch, email: str, **claims: Any) -> httpx.Response:
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.yahoo_claims(email, **claims), alg="ES256"))
    return _post(login_body("YAHOO"))


def _roster(email: str, status: str, **extra: Any) -> dict[str, Any]:
    return {"id": f"roster-{email}", "email": email, "status": status, **extra}


def test_an_admitted_microsoft_address_gets_an_account_at_its_granted_tier(monkeypatch):
    fake = install(monkeypatch, FakeDb(users=[], roster=[_roster("meera@outlook.com", "ACTIVE", grantedRole="RESEARCHER")]))
    response = _microsoft(monkeypatch, "Meera@Outlook.com")
    assert response.status_code == 200, response.text
    assert response.json()["accessToken"]
    [account] = fake.user.rows
    assert (account.email, account.role, account.authProvider, account.name) == (
        "meera@outlook.com", "RESEARCHER", "MICROSOFT", "Meera Iyer"
    )


def test_an_admitted_yahoo_address_gets_an_account(monkeypatch):
    fake = install(monkeypatch, FakeDb(users=[], roster=[_roster("ravi@yahoo.com", "ACTIVE")]))
    assert _yahoo(monkeypatch, "ravi@yahoo.com").status_code == 200
    assert fake.user.rows[0].authProvider == "YAHOO"


def test_an_unknown_address_becomes_pending_and_gets_no_account(monkeypatch):
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    response = _yahoo(monkeypatch, "stranger@yahoo.com")
    assert response.status_code == 403
    assert response.json()["detail"] == {"code": "ACCESS_PENDING", "message": ACCESS_PENDING_DETAIL}
    assert fake.user.rows == []
    assert [row.email for row in fake.accessroster.rows] == ["stranger@yahoo.com"]


def test_a_suspended_address_is_refused_and_its_account_untouched(monkeypatch):
    fake = install(monkeypatch, FakeDb(
        users=[{"id": "u1", "email": "stopped@outlook.com", "name": "Real Name", "passwordHash": hash_password(PASSWORD)}],
        roster=[_roster("stopped@outlook.com", "SUSPENDED")],
    ))
    response = _microsoft(monkeypatch, "stopped@outlook.com")
    assert response.status_code == 403
    assert response.json()["detail"]["message"] == ACCESS_SUSPENDED_DETAIL
    assert fake.user.rows[0].name == "Real Name"


def test_an_unverified_address_writes_nothing_at_all(monkeypatch):
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    response = _microsoft(monkeypatch, "ceo@contoso.com", tenant=WORK_TENANT)
    assert response.status_code == 401
    assert "has not confirmed the email address" in response.json()["detail"]
    assert fake.user.rows == [] and fake.accessroster.rows == []
    assert _yahoo(monkeypatch, "r@yahoo.com", email_verified=False).status_code == 401
    assert fake.accessroster.rows == []


def test_an_existing_password_account_is_signed_in_to_and_keeps_its_password_and_provider(monkeypatch):
    fake = install(monkeypatch, FakeDb(
        users=[{"id": "u1", "email": "local@yahoo.com", "passwordHash": hash_password(PASSWORD), "avatarUrl": "https://pic"}],
        roster=[_roster("local@yahoo.com", "ACTIVE")],
    ))
    response = _yahoo(monkeypatch, "local@yahoo.com")
    assert response.status_code == 200
    [account] = fake.user.rows
    assert account.passwordHash is not None
    assert account.avatarUrl == "https://pic"
    assert account.authProvider == "YAHOO"  # it had none of its own: LOCAL


def test_a_google_account_keeps_google_as_its_provider(monkeypatch):
    fake = install(monkeypatch, FakeDb(
        users=[{"id": "u1", "email": "g@outlook.com", "authProvider": "GOOGLE", "avatarUrl": "https://pic"}],
        roster=[_roster("g@outlook.com", "ACTIVE")],
    ))
    assert _microsoft(monkeypatch, "g@outlook.com").status_code == 200
    assert fake.user.rows[0].authProvider == "GOOGLE"
    assert fake.user.rows[0].avatarUrl == "https://pic"


def test_no_gmail_spelling_is_folded(monkeypatch):
    fake = install(monkeypatch, FakeDb(
        users=[{"id": "u1", "email": "sandy.craft@gmail.com", "passwordHash": hash_password(PASSWORD)}],
        roster=[_roster("sandycraft@gmail.com", "ACTIVE")],
    ))
    assert _microsoft(monkeypatch, "sandycraft@gmail.com").status_code == 200
    assert sorted(row.email for row in fake.user.rows) == ["sandy.craft@gmail.com", "sandycraft@gmail.com"]


def test_a_forged_token_is_one_sentence_and_writes_nothing(monkeypatch):
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.microsoft_claims("meera@outlook.com"), key=idp.stranger_key))
    response = _post(login_body("MICROSOFT"))
    assert response.status_code == 401
    assert response.json() == {
        "detail": "Signing in with Microsoft did not complete. Try again, or use another way to sign in."
    }
    assert fake.accessroster.rows == []


def test_an_unconfigured_provider_is_not_available(monkeypatch):
    install(monkeypatch, FakeDb(users=[], roster=[]))
    FakeIdP().install(monkeypatch, yahoo_client_id="")
    response = _post(login_body("YAHOO"))
    assert response.status_code == 400
    assert response.json()["detail"] == "Signing in with Yahoo is not available here. Use another way to sign in."
