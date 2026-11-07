import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx
import pytest
from fastapi import FastAPI
from fastapi.security import HTTPAuthorizationCredentials

from access_roster_fakes import FakeDb, FakeTable, install
from app.api.router import api_router
from app.core import deps
from app.core import security
from app.core.config import get_settings
from app.core.security import create_access_token, hash_password
from app.scale import rate_limit
from app.services import credential_links, mailer

PASSWORD = "correct-horse-battery"
PASSWORD_HASH = hash_password(PASSWORD)
MASTER_EMAIL = "master@example.org"

_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _call(
    method: str, path: str, body: dict[str, Any] | None = None, headers: dict[str, str] | None = None
) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://standing.test") as client:
            return await client.request(method, path, json=body, headers=headers)

    return asyncio.run(run())


def _caller(role: str = "ADMIN", user_id: str = "admin-1") -> SimpleNamespace:
    caller = SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.org",
        name="A Caller",
        role=role,
        passwordHash=PASSWORD_HASH,
        mustChangePassword=False,
        twoFactorEnabled=False,
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
        "passwordHash": PASSWORD_HASH,
        **extra,
    }


def _roster(email: str, status: str, **extra: Any) -> dict[str, Any]:
    return {"id": f"roster-{email}", "email": email, "status": status, **extra}


@pytest.fixture(autouse=True)
def settings_under_test(monkeypatch: pytest.MonkeyPatch) -> Any:
    settings = get_settings()
    monkeypatch.setattr(settings, "master_admin_email", MASTER_EMAIL)
    monkeypatch.setattr(settings, "access_roster_enforced", True)
    monkeypatch.setattr(settings, "auth_user_cache_enabled", False)
    deps.clear_user_cache()
    yield settings
    _CURRENT["user"] = None
    deps.clear_user_cache()


def _user_row(fake: FakeDb, email: str) -> Any:
    return next(row for row in fake.user.rows if row.email == email)


def _roster_row(fake: FakeDb, email: str) -> Any:
    return next(row for row in fake.accessroster.rows if row.email == email)


def test_an_admin_cannot_promote_an_account_to_admin_through_the_users_screen(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(monkeypatch, FakeDb(users=[_account("low@example.org")], roster=[_roster("low@example.org", "ACTIVE")]))
    _caller("ADMIN")
    response = _call("PATCH", "/api/users/id-low@example.org", {"role": "ADMIN"})
    assert response.status_code == 403, response.text
    assert _user_row(fake, "low@example.org").role == "RESEARCHER"


def test_a_professor_cannot_raise_an_account_to_their_own_tier(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(monkeypatch, FakeDb(users=[_account("low@example.org")], roster=[_roster("low@example.org", "ACTIVE")]))
    _caller("PROFESSOR", "prof-1")
    response = _call("PATCH", "/api/users/id-low@example.org", {"role": "PROFESSOR"})
    assert response.status_code == 403, response.text
    assert _user_row(fake, "low@example.org").role == "RESEARCHER"


def test_an_admin_cannot_list_an_address_as_admin_on_the_access_roster(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(monkeypatch, FakeDb(users=[], roster=[_roster("listed@example.org", "PENDING")]))
    _caller("ADMIN")
    added = _call("POST", "/api/access-roster", {"email": "new@example.org", "grantedRole": "ADMIN"})
    assert added.status_code == 403, added.text
    changed = _call("PATCH", "/api/access-roster/roster-listed@example.org", {"grantedRole": "ADMIN"})
    assert changed.status_code == 403, changed.text
    assert all(getattr(row, "grantedRole", None) != "ADMIN" for row in fake.accessroster.rows)


def test_the_master_admin_may_still_list_and_promote_an_admin(monkeypatch: pytest.MonkeyPatch) -> None:
    install(monkeypatch, FakeDb(users=[_account("low@example.org")], roster=[_roster("low@example.org", "ACTIVE")]))
    _caller("MASTER_ADMIN", "master-1")
    assert _call("PATCH", "/api/users/id-low@example.org", {"role": "ADMIN"}).status_code == 200


@pytest.mark.parametrize(
    "method,body",
    [("DELETE", None), ("PATCH", {"status": "REJECTED"}), ("PATCH", {"status": "SUSPENDED"}), ("PATCH", {"email": "elsewhere@example.org"})],
)
def test_an_admin_cannot_bar_or_readdress_a_peer_admins_roster_entry(
    monkeypatch: pytest.MonkeyPatch, method: str, body: dict[str, Any] | None
) -> None:
    fake = install(monkeypatch, FakeDb(users=[_account("peer@example.org", role="ADMIN")], roster=[_roster("peer@example.org", "ACTIVE")]))
    _caller("ADMIN")
    response = _call(method, "/api/access-roster/roster-peer@example.org", body)
    assert response.status_code == 403, response.text
    row = _roster_row(fake, "peer@example.org")
    assert row.status == "ACTIVE"


@pytest.mark.parametrize("method,body", [("DELETE", None), ("PATCH", {"status": "REJECTED"})])
def test_barring_a_roster_entry_ends_the_accounts_live_sessions(
    monkeypatch: pytest.MonkeyPatch, method: str, body: dict[str, Any] | None
) -> None:
    fake = install(monkeypatch, FakeDb(users=[_account("barred@example.org")], roster=[_roster("barred@example.org", "ACTIVE")]))
    _caller("ADMIN")
    response = _call(method, "/api/access-roster/roster-barred@example.org", body)
    assert response.status_code == 200, response.text
    assert _user_row(fake, "barred@example.org").sessionsValidFrom is not None


def test_a_barred_account_cannot_mint_a_session_by_changing_its_password(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(monkeypatch, FakeDb(users=[_account("barred@example.org")], roster=[_roster("barred@example.org", "SUSPENDED")]))
    _CURRENT["user"] = _user_row(fake, "barred@example.org")
    response = _call(
        "POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": "another-horse-battery"}
    )
    assert response.status_code == 403, response.text
    assert "x-session-token" not in response.headers
    assert _user_row(fake, "barred@example.org").passwordHash == PASSWORD_HASH


def test_a_suspended_admin_cannot_mint_a_dataset_token(monkeypatch: pytest.MonkeyPatch) -> None:
    install(monkeypatch, FakeDb(users=[_account("admin@example.org", role="ADMIN")], roster=[_roster("admin@example.org", "SUSPENDED")]))
    response = _call("POST", "/api/datasets/token", {"email": "admin@example.org", "password": PASSWORD})
    assert response.status_code == 403, response.text
    assert "accessToken" not in response.json()


def test_a_dataset_token_stops_working_once_its_admin_is_suspended(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(monkeypatch, FakeDb(users=[_account("admin@example.org", role="ADMIN")], roster=[_roster("admin@example.org", "SUSPENDED")]))
    admin = _user_row(fake, "admin@example.org")
    token = create_access_token(
        subject=admin.id,
        extra_claims={"scope": deps.DATASET_READ_SCOPE, "email": admin.email},
        credential=deps.password_credential(admin),
    )
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)
    with pytest.raises(Exception) as refused:
        asyncio.run(deps.require_dataset_admin(credentials))
    assert getattr(refused.value, "status_code", None) == 403


def test_an_email_change_withdraws_the_links_sent_to_the_old_address(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(users=[_account("typo@example.org", passwordHash=None)], roster=[_roster("typo@example.org", "ACTIVE")]),
    )
    fake.passwordresettoken.rows.append(
        fake.passwordresettoken._materialise(
            {"id": "link-1", "userId": "id-typo@example.org", "tokenHash": "h1", "purpose": "INVITE", "expiresAt": datetime.now(UTC) + timedelta(hours=72), "issuedById": "admin-1"}
        )
    )
    fake.emailmessage.rows.append(
        fake.emailmessage._materialise(
            {"id": "mail-1", "kind": mailer.PASSWORD_LINK, "toAddress": "typo@example.org", "recipientId": "id-typo@example.org", "status": "QUEUED"}
        )
    )
    _caller("ADMIN")
    response = _call("PATCH", "/api/users/id-typo@example.org", {"email": "right@example.org"})
    assert response.status_code == 200, response.text
    assert fake.passwordresettoken.rows[0].revokedAt is not None
    assert fake.emailmessage.rows[0].status != "QUEUED"


def test_an_email_change_withdraws_the_old_address_from_the_roster(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("old@example.org", role="PROFESSOR")],
            roster=[_roster("old@example.org", "ACTIVE", grantedRole="PROFESSOR")],
        ),
    )
    _caller("ADMIN")
    response = _call("PATCH", "/api/users/id-old@example.org", {"email": "new@example.org"})
    assert response.status_code == 200, response.text
    assert _roster_row(fake, "old@example.org").status != "ACTIVE"
    assert _roster_row(fake, "new@example.org").status == "ACTIVE"


def _share_fake(monkeypatch: pytest.MonkeyPatch, owner_status: str) -> tuple[FakeDb, str]:
    from app.services.datareport import share_links

    fake = install(
        monkeypatch,
        FakeDb(users=[_account("owner@example.org", role="PROFESSOR")], roster=[_roster("owner@example.org", owner_status)]),
    )
    secret = str(get_settings().jwt_secret)
    token = share_links.token_for("share-1", secret)
    fake.datareportshare = FakeTable(
        {"revokedAt": None, "snapshot": {"title": "Dummy"}},
        [{"id": "share-1", "ownerId": "id-owner@example.org", "tokenHash": share_links.token_hash(token), "expiresAt": datetime.now(UTC) + timedelta(days=7)}],
    )
    fake.datareportshareview = FakeTable({"viewerId": None})
    return fake, token


def test_a_report_share_link_stops_when_its_owner_is_suspended(monkeypatch: pytest.MonkeyPatch) -> None:
    _fake, token = _share_fake(monkeypatch, "SUSPENDED")
    assert _call("GET", f"/api/report-share/{token}").status_code == 404


def test_a_report_share_link_serves_while_its_owner_is_admitted(monkeypatch: pytest.MonkeyPatch) -> None:
    _fake, token = _share_fake(monkeypatch, "ACTIVE")
    assert _call("GET", f"/api/report-share/{token}").status_code == 200


def _thread_probe(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    seen: list[str] = []

    def probe(_password: str, _hash: str | None) -> bool:
        try:
            asyncio.get_running_loop()
            seen.append("event-loop")
        except RuntimeError:
            seen.append("worker-thread")
        return False

    monkeypatch.setattr(security, "verify_password", probe)
    return seen


@pytest.mark.parametrize("path", ["/api/auth/login", "/api/datasets/token"])
def test_password_checks_run_off_the_event_loop(monkeypatch: pytest.MonkeyPatch, path: str) -> None:
    install(monkeypatch, FakeDb(users=[], roster=[]))
    seen = _thread_probe(monkeypatch)
    response = _call("POST", path, {"email": "nobody@example.org", "password": "dummy-pass-1"})
    assert response.status_code == 401, response.text
    assert seen == ["worker-thread"]


@pytest.mark.parametrize("path", ["/api/auth/login", "/api/datasets/token"])
def test_rotating_the_email_does_not_buy_unlimited_password_checks(monkeypatch: pytest.MonkeyPatch, path: str) -> None:
    install(monkeypatch, FakeDb(users=[], roster=[]))
    monkeypatch.setattr(security, "verify_password", lambda *_args: False)
    codes = [
        _call("POST", path, {"email": f"u{index}@example.org", "password": "dummy-pass-1"}).status_code
        for index in range(60)
    ]
    assert 429 in codes, codes


def _scope(*headers: tuple[bytes, bytes]) -> dict[str, Any]:
    return {"type": "http", "headers": list(headers), "client": ("127.0.0.1", 1)}


def test_the_rate_limit_key_ignores_a_spoofed_leftmost_forwarded_address(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "web_proxy_secret", None)
    monkeypatch.setattr(get_settings(), "trusted_proxy_hops", 2)
    first = _scope((b"x-forwarded-for", b"198.51.100.1, 76.76.21.9, 130.176.1.1"))
    second = _scope((b"x-forwarded-for", b"198.51.100.2, 76.76.21.9, 130.176.1.1"))
    assert rate_limit._identity(first) == rate_limit._identity(second)


def test_the_rate_limit_key_ignores_an_unverified_bearer_string(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "web_proxy_secret", None)
    first = _scope((b"authorization", b"Bearer made-up-1"))
    second = _scope((b"authorization", b"Bearer made-up-2"))
    assert rate_limit._identity(first) == rate_limit._identity(second)


def _issued_link(fake: FakeDb, *, issuer_id: str, target_email: str) -> str:
    target = _user_row(fake, target_email)
    issued = asyncio.run(
        credential_links.issue_link(
            user=target, purpose=credential_links.INVITE, issued_by_id=issuer_id, deliver_by=credential_links.COPY_LINK
        )
    )
    return parse_qs(urlparse(issued.link).query)["token"][0]


def test_a_password_link_dies_when_its_issuer_is_suspended(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("issuer@example.org", role="ADMIN"), _account("target@example.org", passwordHash=None)],
            roster=[_roster("issuer@example.org", "SUSPENDED"), _roster("target@example.org", "ACTIVE")],
        ),
    )
    token = _issued_link(fake, issuer_id="id-issuer@example.org", target_email="target@example.org")
    response = _call("POST", "/api/auth/set-password", {"token": token, "password": "dummy-pass-123"})
    assert response.status_code == 400, response.text
    assert _user_row(fake, "target@example.org").passwordHash is None


def test_a_password_link_dies_when_its_issuer_is_deleted(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("issuer@example.org", role="ADMIN"), _account("target@example.org", passwordHash=None)],
            roster=[_roster("issuer@example.org", "ACTIVE"), _roster("target@example.org", "ACTIVE")],
        ),
    )
    token = _issued_link(fake, issuer_id="id-issuer@example.org", target_email="target@example.org")
    _caller("MASTER_ADMIN", "master-1")
    assert _call("DELETE", "/api/users/id-issuer@example.org").status_code == 204
    response = _call("POST", "/api/auth/set-password", {"token": token, "password": "dummy-pass-123"})
    assert response.status_code == 400, response.text
    assert _user_row(fake, "target@example.org").passwordHash is None


def test_a_password_link_from_an_admitted_issuer_still_works(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("issuer@example.org", role="ADMIN"), _account("target@example.org", passwordHash=None)],
            roster=[_roster("issuer@example.org", "ACTIVE"), _roster("target@example.org", "ACTIVE")],
        ),
    )
    token = _issued_link(fake, issuer_id="id-issuer@example.org", target_email="target@example.org")
    response = _call("POST", "/api/auth/set-password", {"token": token, "password": "dummy-pass-123"})
    assert response.status_code == 200, response.text


def test_the_weekly_digest_skips_a_suspended_admin(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import weekly_digest

    install(
        monkeypatch,
        FakeDb(
            users=[_account("barred@example.org", role="ADMIN"), _account("kept@example.org", role="ADMIN")],
            roster=[_roster("barred@example.org", "SUSPENDED"), _roster("kept@example.org", "ACTIVE")],
        ),
    )
    chosen = asyncio.run(weekly_digest.eligible_users())
    assert [row.email for row in chosen] == ["kept@example.org"]
