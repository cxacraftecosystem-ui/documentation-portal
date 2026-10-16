"""Passwords and sessions, ported from the Design Prototype Workshop (2026-10-16).

Password links (made by an administrator and copied, or e-mailed), their redemption, "Forgot your
password?", changing a password, setting a first one, the forced change after a temporary password,
"Sign out of every device", the access list's "with a password", and the two session checks every
request now makes (the ``sessionsValidFrom`` watermark and the ``cred`` password fingerprint).

Driven over HTTP through the real routers against ``access_roster_fakes`` (no database), like
``test_two_factor.py``, so the assertions are about the bytes the web app and the handset receive.
"""

import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx
import jwt
import pytest
from access_roster_fakes import FakeDb, install
from fastapi import FastAPI
from fastapi.routing import iter_route_contexts

from app.api.router import api_router
from app.core import security
from app.core.config import get_settings
from app.core.deps import (
    PASSWORD_CHANGE_ALLOWED_ROUTES,
    PASSWORD_CHANGE_REQUIRED_HEADER,
    clear_user_cache,
    password_credential,
)
from app.core.security import CREDENTIAL_CLAIM, create_access_token, hash_password, verify_password
from app.api.routes import auth as auth_routes
from app.scale.rate_limit import (
    FORGOT_PASSWORD_REQUESTS,
    reset_first_password_code_budget,
    reset_forgot_password_budget,
)
from app.services import credential_links, email_outbox, mailer, password_reset

PASSWORD = "correct-horse-battery"
NEW_PASSWORD = "a-brand-new-password"
APP_URL = "https://repository.example.org"

_APP = FastAPI()
_APP.include_router(api_router)


@pytest.fixture(autouse=True)
def environment(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "master_admin_email", "master@example.org")
    monkeypatch.setattr(settings, "access_roster_enforced", True)
    monkeypatch.setattr(settings, "access_roster_max_pending", 2000)
    monkeypatch.setattr(settings, "default_signup_role", "CROWDSOURCE_VOLUNTEER")
    monkeypatch.setattr(settings, "next_public_app_url", APP_URL)
    monkeypatch.setattr(settings, "mail_from_address", None)
    monkeypatch.setattr(security, "BCRYPT_ROUNDS", 4)
    clear_user_cache()
    reset_forgot_password_budget()
    reset_first_password_code_budget()


def _call(
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    token: str | None = None,
    headers: dict[str, str] | None = None,
) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        sent = {"Authorization": f"Bearer {token}"} if token else {}
        sent.update(headers or {})
        async with httpx.AsyncClient(transport=transport, base_url="http://accounts.test") as client:
            return await client.request(method, path, json=body, headers=sent)

    return asyncio.run(run())


def _account(email: str, role: str = "RESEARCHER", **extra: Any) -> dict[str, Any]:
    return {
        "id": f"id-{email}",
        "email": email,
        "name": "Somebody",
        "role": role,
        "passwordHash": hash_password(PASSWORD),
        **extra,
    }


def _admitted(*emails: str) -> list[dict[str, Any]]:
    return [{"id": f"roster-{e}", "email": e, "status": "ACTIVE"} for e in emails]


def _session(user_id: str, *, minutes_ago: float = 0, user: Any = None) -> str:
    """A session token as the sign-in mints it (with the password fingerprint when *user* is given),
    optionally back-dated."""
    settings = get_settings()
    issued = datetime.now(UTC) - timedelta(minutes=minutes_ago)
    payload: dict[str, Any] = {"sub": user_id, "iat": int(issued.timestamp()), "exp": issued + timedelta(days=7)}
    if user is not None:
        payload[CREDENTIAL_CLAIM] = password_credential(user)
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def _fake(monkeypatch: pytest.MonkeyPatch, users: list[dict[str, Any]], roster: list[dict[str, Any]] | None = None) -> FakeDb:
    return install(monkeypatch, FakeDb(users=users, roster=roster or []))


def _row(fake: FakeDb, email: str) -> Any:
    return next(row for row in fake.user.rows if row.email == email)


def _params(message: Any) -> dict[str, Any]:
    """An outbox row's template parameters: the fake stores prisma's ``Json`` wrapper as written."""
    return getattr(message.params, "data", message.params)


def _token_of(link: str) -> str:
    return parse_qs(urlparse(link).query)["token"][0]


def _google_proof(
    monkeypatch: pytest.MonkeyPatch,
    email: str,
    *,
    seconds_ago: float = 0,
    sub: str | None = "google-subject-1",
    verified: bool = True,
) -> dict[str, Any]:
    """A Google sign-in run again ``seconds_ago``: what ``verify_google_token`` answers for it, and
    the body fields a client sends with the first password."""
    issued = int(datetime.now(UTC).timestamp() - seconds_ago)
    claims: dict[str, Any] = {"email": email, "email_verified": verified, "iat": issued, "exp": issued + 3600}
    if sub is not None:
        claims["sub"] = sub
    monkeypatch.setattr(auth_routes, "verify_google_token", lambda token: dict(claims))
    return {"googleIdToken": "a-google-id-token"}


# --- the user payload -------------------------------------------------------------------------------


def test_the_account_says_whether_it_has_a_password_and_never_the_hash(monkeypatch) -> None:
    _fake(
        monkeypatch,
        [_account("pw@example.org"), _account("google@example.org", passwordHash=None, authProvider="GOOGLE")],
        _admitted("pw@example.org", "google@example.org"),
    )
    with_password = _call("GET", "/api/auth/me", token=_session("id-pw@example.org")).json()
    google = _call("GET", "/api/auth/me", token=_session("id-google@example.org")).json()
    assert with_password["hasPassword"] is True and google["hasPassword"] is False
    assert "passwordHash" not in with_password and "passwordHash" not in google


def test_one_account_reads_as_its_list_row_and_only_for_the_list_s_readers(monkeypatch) -> None:
    """``GET /users/{id}`` backs the read-only view of a person: the list's gate, the list's shape."""
    _fake(
        monkeypatch,
        [_account("prof@example.org", "PROFESSOR"), _account("r@example.org"), _account("v@example.org", "CROWDSOURCE_VOLUNTEER")],
        _admitted("prof@example.org", "r@example.org", "v@example.org"),
    )
    prof = _session("id-prof@example.org")
    one = _call("GET", "/api/users/id-r@example.org", token=prof)
    assert one.status_code == 200, one.text
    listed = next(row for row in _call("GET", "/api/users", token=prof).json()["items"] if row["id"] == "id-r@example.org")
    assert one.json() == listed
    assert "passwordHash" not in one.json()
    assert _call("GET", "/api/users/no-such-id", token=prof).status_code == 404
    assert _call("GET", "/api/users/id-prof@example.org", token=_session("id-r@example.org")).status_code == 403
    # "/directory" is still the directory, not an account called "directory".
    assert isinstance(_call("GET", "/api/users/directory", token=_session("id-v@example.org")).json(), list)


# --- administrator password links ----------------------------------------------------------------


def test_a_copied_link_sets_a_password_ends_every_session_and_signs_nobody_in(monkeypatch) -> None:
    fake = _fake(
        monkeypatch,
        [_account("admin@example.org", "ADMIN"), _account("r@example.org")],
        _admitted("admin@example.org", "r@example.org"),
    )
    old_session = _session("id-r@example.org")
    assert _call("GET", "/api/auth/me", token=old_session).status_code == 200

    issued = _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, _session("id-admin@example.org"))
    assert issued.status_code == 201, issued.text
    body = issued.json()
    assert body["deliveredBy"] == "COPY_LINK" and "token" not in body
    # Built from the configured origin, never from the request's Host.
    assert body["link"].startswith(f"{APP_URL}/set-password?token=")
    token = _token_of(body["link"])

    check = _call("POST", "/api/auth/set-password/check", {"token": token})
    assert check.json() == {"valid": True, "reason": None, "purpose": body["purpose"]}

    redeemed = _call("POST", "/api/auth/set-password", {"token": token, "password": NEW_PASSWORD})
    assert redeemed.status_code == 200 and redeemed.json() == {"ok": True}
    assert "accessToken" not in redeemed.text  # no session from a link
    account = _row(fake, "r@example.org")
    assert verify_password(NEW_PASSWORD, account.passwordHash)
    assert account.sessionsValidFrom is not None and account.passwordSetAt is not None
    assert account.mustChangePassword is False

    ended = _call("GET", "/api/auth/me", token=old_session)
    assert ended.status_code == 401 and "no longer valid" in ended.json()["detail"]

    again = _call("POST", "/api/auth/set-password", {"token": token, "password": "yet-another-password"})
    assert again.status_code == 400 and "already been used" in again.json()["detail"]


def test_a_link_never_skips_the_second_step(monkeypatch) -> None:
    fake = _fake(
        monkeypatch,
        [_account("admin@example.org", "ADMIN"), _account("r@example.org", twoFactorEnabled=True)],
        _admitted("admin@example.org", "r@example.org"),
    )
    link = _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, _session("id-admin@example.org")).json()["link"]
    assert _call("POST", "/api/auth/set-password", {"token": _token_of(link), "password": NEW_PASSWORD}).status_code == 200
    signed_in = _call("POST", "/api/auth/login", {"email": "r@example.org", "password": NEW_PASSWORD})
    assert signed_in.status_code == 200
    assert signed_in.json()["two_factor_required"] is True and "accessToken" not in signed_in.json()
    assert _row(fake, "r@example.org").twoFactorEnabled is True


@pytest.mark.parametrize(
    "actor,target,expected",
    [
        ("admin@example.org", "admin@example.org", 403),  # your own account
        ("admin@example.org", "peer@example.org", 403),  # a peer
        ("prof@example.org", "r@example.org", 403),  # not an administrator
        ("admin@example.org", "google@example.org", 422),  # nothing to reset
    ],
)
def test_who_may_issue_a_link(monkeypatch, actor, target, expected) -> None:
    _fake(
        monkeypatch,
        [
            _account("admin@example.org", "ADMIN"),
            _account("peer@example.org", "ADMIN"),
            _account("prof@example.org", "PROFESSOR"),
            _account("r@example.org"),
            _account("google@example.org", passwordHash=None, authProvider="GOOGLE"),
        ],
        _admitted("admin@example.org", "peer@example.org", "prof@example.org", "r@example.org", "google@example.org"),
    )
    answer = _call("POST", "/api/auth/password-links", {"userId": f"id-{target}"}, _session(f"id-{actor}"))
    assert answer.status_code == expected, answer.text


def test_a_withdrawn_link_says_so(monkeypatch) -> None:
    _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    admin = _session("id-admin@example.org")
    issued = _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, admin).json()
    assert _call("POST", f"/api/auth/password-links/{issued['id']}/revoke", token=admin).json() == {"ok": True}
    check = _call("GET", "/api/auth/set-password", None).json()
    assert check["valid"] is False and check["reason"] == "missing"
    withdrawn = _call("POST", "/api/auth/set-password/check", {"token": _token_of(issued["link"])}).json()
    assert withdrawn == {"valid": False, "reason": "revoked", "purpose": None}


def test_an_emailed_link_is_sealed_in_the_outbox_and_never_handed_to_the_administrator(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    answer = _call(
        "POST", "/api/auth/password-links", {"userId": "id-r@example.org", "delivery": "EMAIL"}, _session("id-admin@example.org")
    )
    assert answer.status_code == 201, answer.text
    assert answer.json()["deliveredBy"] == "EMAIL" and answer.json()["link"] is None
    [message] = fake.emailmessage.rows
    assert message.kind == mailer.PASSWORD_LINK and message.toAddress == "r@example.org"
    assert message.sealed and APP_URL not in str(_params(message))


def test_email_delivery_without_mail_is_refused_and_spends_nothing(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    answer = _call(
        "POST", "/api/auth/password-links", {"userId": "id-r@example.org", "delivery": "EMAIL"}, _session("id-admin@example.org")
    )
    assert answer.status_code == 422
    assert fake.passwordresettoken.rows == []


def test_a_promoted_account_cannot_use_a_link_its_issuer_can_no_longer_manage(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    link = _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, _session("id-admin@example.org")).json()["link"]
    _row(fake, "r@example.org").role = "ADMIN"
    clear_user_cache()
    check = _call("POST", "/api/auth/set-password/check", {"token": _token_of(link)}).json()
    assert check["valid"] is False and check["reason"] == "revoked"


# --- the e-mail itself --------------------------------------------------------------------------


def test_the_password_link_mail_says_who_asked_and_carries_the_link() -> None:
    expires = (datetime.now(UTC) + timedelta(hours=2)).isoformat()
    by_admin = mailer.render(mailer.PASSWORD_LINK, {"purpose": "RESET", "expiresAt": expires}, secret=f"{APP_URL}/set-password?token=x")
    by_self = mailer.render(
        mailer.PASSWORD_LINK, {"purpose": "RESET", "expiresAt": expires, "requestedBy": "self"}, secret=f"{APP_URL}/set-password?token=x"
    )
    assert "administrator" in by_admin.text and "administrator" not in by_self.text.split("\n\n")[1]
    assert f"{APP_URL}/set-password?token=x" in by_self.text and f"{APP_URL}/set-password?token=x" in by_self.html
    with pytest.raises(ValueError):
        mailer.render(mailer.PASSWORD_LINK, {"purpose": "RESET"})


def test_the_outbox_opens_the_seal_to_send_and_then_forgets_it(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")
    fake = install(monkeypatch, FakeDb())
    sent: list[str] = []
    monkeypatch.setattr(mailer, "send", lambda **kwargs: sent.append(kwargs["text"]) or "ses-1")
    link = f"{APP_URL}/set-password?token=abc"
    expires = (datetime.now(UTC) + timedelta(hours=2)).isoformat()
    asyncio.run(email_outbox.enqueue(mailer.PASSWORD_LINK, to_address="r@example.org", recipient_id=None, params={"purpose": "RESET", "expiresAt": expires}, secret=link))
    tally = asyncio.run(email_outbox.process_next_email_jobs(recover=False))
    assert tally["sent"] == 1 and link in sent[0]
    assert fake.emailmessage.rows[0].sealed is None and fake.emailmessage.rows[0].status == "SENT"


# --- "Forgot your password?" ------------------------------------------------------------------------


def test_forgot_password_answers_every_address_identically(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")
    fake = _fake(
        monkeypatch,
        [
            _account("r@example.org"),
            _account("google@example.org", passwordHash=None, authProvider="GOOGLE"),
            _account("barred@example.org"),
        ],
        _admitted("r@example.org", "google@example.org") + [{"id": "roster-barred", "email": "barred@example.org", "status": "SUSPENDED"}],
    )
    answers = [
        _call("POST", "/api/auth/forgot-password", {"email": email}, headers={"Host": "evil.example.com"})
        for email in ("r@example.org", "nobody@example.org", "google@example.org", "barred@example.org")
    ]
    assert {a.status_code for a in answers} == {200}
    assert len({a.text for a in answers}) == 1
    assert answers[0].json() == {"message": password_reset.FORGOT_PASSWORD_MESSAGE}
    assert all(a.headers["cache-control"] == "no-store" for a in answers)
    assert "two-step" not in password_reset.FORGOT_PASSWORD_MESSAGE.lower()

    outcomes = {row.email: row.outcome for row in fake.passwordresetrequest.rows}
    assert outcomes == {
        "r@example.org": password_reset.EMAILED,
        "nobody@example.org": password_reset.UNKNOWN_ADDRESS,
        "google@example.org": password_reset.NO_PASSWORD,
        "barred@example.org": password_reset.NOT_ADMITTED,
    }
    # One mail, to the one admitted password account, through the outbox, sealed.
    [message] = fake.emailmessage.rows
    assert message.toAddress == "r@example.org" and message.sealed
    assert _params(message)["requestedBy"] == "self"
    [link_row] = fake.passwordresettoken.rows
    assert link_row.issuedById is None and link_row.purpose == "RESET"


def test_forgot_password_ends_no_session_and_touches_no_second_factor(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")
    fake = _fake(monkeypatch, [_account("r@example.org", twoFactorEnabled=True)], _admitted("r@example.org"))
    session = _session("id-r@example.org")
    assert _call("POST", "/api/auth/forgot-password", {"email": "r@example.org"}).status_code == 200
    account = _row(fake, "r@example.org")
    assert account.sessionsValidFrom is None and account.twoFactorEnabled is True
    assert _call("GET", "/api/auth/me", token=session).status_code == 200


def test_forgot_password_link_comes_from_the_configured_origin(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    _call(
        "POST",
        "/api/auth/forgot-password",
        {"email": "R@Example.org"},
        headers={"Host": "evil.example.com", "X-Forwarded-Host": "evil.example.com", "Origin": "https://evil.example.com"},
    )
    from app.services import managed_secrets

    link = managed_secrets.decrypt(fake.emailmessage.rows[0].sealed)
    assert link.startswith(f"{APP_URL}/set-password?token=") and "evil" not in link


def test_forgot_password_caps_each_address_silently(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    for n in range(password_reset.ADDRESS_BUDGET + 1):
        answer = _call("POST", "/api/auth/forgot-password", {"email": "r@example.org"}, headers={"X-Forwarded-For": f"10.0.0.{n}"})
        assert answer.status_code == 200
    assert len(fake.emailmessage.rows) == password_reset.ADDRESS_BUDGET
    assert fake.passwordresetrequest.rows[-1].outcome == password_reset.ADDRESS_RATE_LIMITED


def test_forgot_password_limits_each_connection(monkeypatch) -> None:
    _fake(monkeypatch, [], [])
    for _ in range(FORGOT_PASSWORD_REQUESTS):
        assert _call("POST", "/api/auth/forgot-password", {"email": "x@example.org"}).status_code == 200
    refused = _call("POST", "/api/auth/forgot-password", {"email": "y@example.org"})
    assert refused.status_code == 429 and int(refused.headers["retry-after"]) > 0
    assert refused.json()["detail"] == password_reset.TOO_MANY_REQUESTS_DETAIL


def test_forgot_password_refuses_a_malformed_address(monkeypatch) -> None:
    fake = _fake(monkeypatch, [], [])
    assert _call("POST", "/api/auth/forgot-password", {"email": "not an address"}).status_code == 422
    assert fake.passwordresetrequest.rows == []


# --- changing a password, and setting a first one ----------------------------------------------


def test_changing_a_password_keeps_this_session_and_ends_the_others(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    user = _row(fake, "r@example.org")
    mine, other = _session(user.id, user=user), _session(user.id, user=user)

    wrong = _call("POST", "/api/auth/change-password", {"currentPassword": "nope-nope-nope", "newPassword": NEW_PASSWORD}, mine)
    assert wrong.status_code == 400
    same = _call("POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": PASSWORD}, mine)
    assert same.status_code == 400

    changed = _call("POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD}, mine)
    assert changed.status_code == 200 and changed.json() == {"ok": True}
    fresh = changed.headers["x-session-token"]
    clear_user_cache()
    assert _call("GET", "/api/auth/me", token=fresh).status_code == 200
    assert _call("GET", "/api/auth/me", token=other).status_code == 401


def test_a_provider_only_account_sets_a_first_password_with_its_provider_sign_in_run_again(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("g@example.org", passwordHash=None, authProvider="GOOGLE")], _admitted("g@example.org"))
    # A brand-new session is not enough on its own any more: the provider is asked again.
    bare = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD}, _session("id-g@example.org"))
    assert bare.status_code == 400 and bare.headers["x-confirm-sign-in"] == "GOOGLE"
    assert "sign in with Google again" in bare.json()["detail"]
    assert _row(fake, "g@example.org").passwordHash is None

    proof = _google_proof(monkeypatch, "g@example.org")
    fresh = _call(
        "POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **proof}, _session("id-g@example.org", minutes_ago=600)
    )
    assert fresh.status_code == 200 and fresh.headers["x-session-token"]
    account = _row(fake, "g@example.org")
    assert verify_password(NEW_PASSWORD, account.passwordHash)
    assert str(account.authProvider) == "GOOGLE"  # both ways in
    assert _call("POST", "/api/auth/login", {"email": "g@example.org", "password": NEW_PASSWORD}).status_code == 200


# --- the forced change after a temporary password -----------------------------------------------


def test_a_temporary_password_holds_the_account_at_the_change_screen(monkeypatch) -> None:
    fake = _fake(
        monkeypatch,
        [_account("admin@example.org", "ADMIN"), _account("r@example.org")],
        _admitted("admin@example.org", "r@example.org"),
    )
    old_session = _session("id-r@example.org")
    patched = _call("PATCH", "/api/users/id-r@example.org", {"password": "temporary-1234"}, _session("id-admin@example.org"))
    assert patched.status_code == 200, patched.text
    assert patched.json()["mustChangePassword"] is True
    account = _row(fake, "r@example.org")
    assert account.sessionsValidFrom is not None
    assert _call("GET", "/api/auth/me", token=old_session).status_code == 401

    signed_in = _call("POST", "/api/auth/login", {"email": "r@example.org", "password": "temporary-1234"})
    assert signed_in.status_code == 200
    # The fresh token may be minted in the same wall second as the watermark; move it back a second.
    account.sessionsValidFrom = datetime.now(UTC) - timedelta(seconds=5)
    clear_user_cache()
    session = signed_in.json()["accessToken"]
    held = _call("GET", "/api/users/directory", token=session)
    assert held.status_code == 401 and held.headers[PASSWORD_CHANGE_REQUIRED_HEADER] == "1"
    assert _call("GET", "/api/auth/me", token=session).status_code == 200
    again = _call("POST", "/api/auth/change-password", {"currentPassword": "temporary-1234", "newPassword": "temporary-1234"}, session)
    assert again.status_code == 400
    done = _call("POST", "/api/auth/change-password", {"currentPassword": "temporary-1234", "newPassword": NEW_PASSWORD}, session)
    assert done.status_code == 200
    clear_user_cache()
    assert _call("GET", "/api/users/directory", token=done.headers["x-session-token"]).status_code == 200


def test_the_temporary_password_door_is_closed_to_your_own_account(monkeypatch) -> None:
    _fake(monkeypatch, [_account("admin@example.org", "ADMIN")], _admitted("admin@example.org"))
    mine = _call("PATCH", "/api/users/id-admin@example.org", {"password": "temporary-1234"}, _session("id-admin@example.org"))
    assert mine.status_code == 403


def test_a_password_set_by_an_administrator_can_be_final(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    _call("PATCH", "/api/users/id-r@example.org", {"password": "final-password-1", "mustChangePassword": False}, _session("id-admin@example.org"))
    assert _row(fake, "r@example.org").mustChangePassword is False


def test_every_allowed_route_is_a_route_this_api_publishes() -> None:
    published = {
        (method, context.path)
        for context in iter_route_contexts(api_router.routes)
        for method in (getattr(context, "methods", None) or ())
    }
    assert PASSWORD_CHANGE_ALLOWED_ROUTES <= published, PASSWORD_CHANGE_ALLOWED_ROUTES - published


# --- signing somebody out of every device -------------------------------------------------------


def test_an_administrator_signs_somebody_out_of_every_device(monkeypatch) -> None:
    fake = _fake(
        monkeypatch,
        [_account("admin@example.org", "ADMIN"), _account("peer@example.org", "ADMIN"), _account("r@example.org")],
        _admitted("admin@example.org", "peer@example.org", "r@example.org"),
    )
    admin = _session("id-admin@example.org")
    theirs = _session("id-r@example.org")
    done = _call("POST", "/api/users/id-r@example.org/sign-out-everywhere", token=admin)
    assert done.status_code == 200 and done.json()["id"] == "id-r@example.org"
    assert _call("GET", "/api/auth/me", token=theirs).status_code == 401
    # The password and the second factor are untouched.
    assert verify_password(PASSWORD, _row(fake, "r@example.org").passwordHash)
    assert _call("POST", "/api/users/id-admin@example.org/sign-out-everywhere", token=admin).status_code == 403
    assert _call("POST", "/api/users/id-peer@example.org/sign-out-everywhere", token=admin).status_code == 403


def test_turning_off_two_step_also_ends_every_session(monkeypatch) -> None:
    fake = _fake(
        monkeypatch,
        [_account("admin@example.org", "ADMIN"), _account("r@example.org", twoFactorEnabled=True)],
        _admitted("admin@example.org", "r@example.org"),
    )
    theirs = _session("id-r@example.org")
    assert _call("DELETE", "/api/users/id-r@example.org/two-factor", token=_session("id-admin@example.org")).status_code == 200
    assert _row(fake, "r@example.org").sessionsValidFrom is not None
    assert _call("GET", "/api/auth/me", token=theirs).status_code == 401


# --- the access list, with a password ------------------------------------------------------------


def test_the_access_list_can_create_the_account_with_a_password(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN")], _admitted("admin@example.org"))
    added = _call(
        "POST",
        "/api/access-roster",
        {"email": "New@Example.org", "fullName": "New Person", "grantedRole": "RESEARCHER", "password": "temporary-1234"},
        _session("id-admin@example.org"),
    )
    assert added.status_code == 201, added.text
    body = added.json()
    assert body["accountCreated"] is True and body["status"] == "ACTIVE" and body["userId"]
    account = _row(fake, "new@example.org")
    assert account.mustChangePassword is True and account.name == "New Person"
    assert str(account.role) == "RESEARCHER" and verify_password("temporary-1234", account.passwordHash)
    signed_in = _call("POST", "/api/auth/login", {"email": "new@example.org", "password": "temporary-1234"})
    assert signed_in.status_code == 200 and signed_in.json()["user"]["mustChangePassword"] is True


def test_the_access_list_without_a_password_creates_no_account(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN")], _admitted("admin@example.org"))
    added = _call("POST", "/api/access-roster", {"email": "g@example.org"}, _session("id-admin@example.org"))
    assert added.status_code == 201 and "accountCreated" not in added.json()
    assert [row.email for row in fake.user.rows] == ["admin@example.org"]


def test_users_create_defaults_to_a_temporary_password(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN")], _admitted("admin@example.org"))
    made = _call(
        "POST", "/api/users", {"email": "t@example.org", "name": "T", "password": "temporary-1234"}, _session("id-admin@example.org")
    )
    assert made.status_code == 201, made.text
    assert made.json()["mustChangePassword"] is True and made.json()["hasPassword"] is True
    assert _row(fake, "t@example.org").passwordSetAt is not None


# --- the session checks themselves ---------------------------------------------------------------


def test_a_token_without_the_fingerprint_still_works_and_a_forged_one_does_not(monkeypatch) -> None:
    _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    assert _call("GET", "/api/auth/me", token=create_access_token(subject="id-r@example.org")).status_code == 200
    forged = create_access_token(subject="id-r@example.org", credential="0123456789abcdef")
    assert _call("GET", "/api/auth/me", token=forged).status_code == 401


def test_a_sign_in_mints_a_bound_session_and_stamps_the_first_login(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    signed_in = _call("POST", "/api/auth/login", {"email": "r@example.org", "password": PASSWORD})
    claims = jwt.decode(signed_in.json()["accessToken"], options={"verify_signature": False})
    assert claims[CREDENTIAL_CLAIM] == credential_links.credential_fingerprint(_row(fake, "r@example.org").passwordHash)
    assert _row(fake, "r@example.org").firstLoginAt is not None


# --- adding a person in one step (section F) ------------------------------------------------------


def _add(actor: str, body: dict[str, Any]) -> httpx.Response:
    return _call("POST", "/api/users", {"name": "New Person", "role": "RESEARCHER", **body}, _session(f"id-{actor}"))


def _owners(monkeypatch) -> FakeDb:
    return _fake(
        monkeypatch,
        [_account("master@example.org", "MASTER_ADMIN"), _account("admin@example.org", "ADMIN")],
        _admitted("admin@example.org"),
    )


def test_a_generated_temporary_password_is_answered_once_and_never_logged(monkeypatch, caplog) -> None:
    fake = _owners(monkeypatch)
    with caplog.at_level("INFO"):
        made = _add("admin@example.org", {"email": "gen@example.org", "passwordMode": "GENERATE", "mustChangePassword": False})
    assert made.status_code == 201, made.text
    temporary = made.json()["temporaryPassword"]
    assert len(temporary) == 16 and not set(temporary) & set("0O1lI")
    assert temporary not in caplog.text
    account = _row(fake, "gen@example.org")
    assert account.mustChangePassword is True and verify_password(temporary, account.passwordHash)
    assert temporary not in str(vars(account))
    assert next(r for r in fake.accessroster.rows if r.email == "gen@example.org").status == "ACTIVE"


def test_a_provider_only_account_has_no_password_and_is_admitted(monkeypatch) -> None:
    fake = _owners(monkeypatch)
    made = _add("admin@example.org", {"email": "g@example.org", "passwordMode": "NONE"})
    assert made.status_code == 201, made.text
    assert made.json()["hasPassword"] is False and "passwordLink" not in made.json()
    assert _row(fake, "g@example.org").passwordHash is None
    assert fake.passwordresettoken.rows == [] and fake.emailmessage.rows == []


def test_a_provider_only_account_can_be_emailed_a_link_after_it_exists(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")
    fake = _owners(monkeypatch)
    made = _add("admin@example.org", {"email": "g@example.org", "passwordMode": "NONE", "sendPasswordLink": True})
    assert made.status_code == 201, made.text
    link = made.json()["passwordLink"]
    assert link["purpose"] == "INVITE" and link["deliveredBy"] == "EMAIL" and "link" not in link
    [message] = fake.emailmessage.rows
    assert message.toAddress == "g@example.org" and message.sealed
    assert fake.passwordresettoken.rows[0].userId == _row(fake, "g@example.org").id


def test_a_link_that_cannot_be_mailed_leaves_the_account_standing(monkeypatch) -> None:
    fake = _owners(monkeypatch)
    made = _add("admin@example.org", {"email": "g@example.org", "passwordMode": "NONE", "sendPasswordLink": True})
    assert made.status_code == 201
    assert "could not be e-mailed" in made.json()["passwordLinkError"]
    assert _row(fake, "g@example.org") is not None


def test_a_request_that_does_not_say_how_they_sign_in_is_refused(monkeypatch) -> None:
    fake = _owners(monkeypatch)
    assert _add("admin@example.org", {"email": "x@example.org"}).status_code == 422
    assert _add("admin@example.org", {"email": "x@example.org", "passwordMode": "SET"}).status_code == 422
    assert _add("admin@example.org", {"email": "x@example.org", "passwordMode": "GENERATE", "password": PASSWORD}).status_code == 422
    assert [row.email for row in fake.user.rows] == ["master@example.org", "admin@example.org"]


@pytest.mark.parametrize(
    "actor,role,expected",
    [
        ("admin@example.org", "PROFESSOR", 201),
        ("admin@example.org", "ADMIN", 403),
        ("admin@example.org", "MASTER_ADMIN", 403),
        ("master@example.org", "ADMIN", 201),
        ("prof@example.org", "RESEARCHER", 403),
    ],
)
def test_who_may_add_whom(monkeypatch, actor, role, expected) -> None:
    fake = _fake(
        monkeypatch,
        [
            _account("master@example.org", "MASTER_ADMIN"),
            _account("admin@example.org", "ADMIN"),
            _account("prof@example.org", "PROFESSOR"),
        ],
        _admitted("admin@example.org", "prof@example.org"),
    )
    made = _add(actor, {"email": "n@example.org", "role": role, "passwordMode": "GENERATE"})
    assert made.status_code == expected, made.text
    assert any(row.email == "n@example.org" for row in fake.user.rows) is (expected == 201)


def test_an_address_with_an_account_is_refused_naming_it(monkeypatch) -> None:
    fake = _owners(monkeypatch)
    fake.user.rows.append(fake.user._materialise(_account("taken@example.org", name="Asha Rao")))
    refused = _add("admin@example.org", {"email": "Taken@Example.org", "passwordMode": "GENERATE"})
    assert refused.status_code == 409
    assert refused.headers["x-existing-account-id"] == "id-taken@example.org"
    assert refused.json()["detail"] == (
        "taken@example.org already has an account (Asha Rao, Researcher). Open it to change their role or access instead."
    )
    via_list = _call(
        "POST", "/api/access-roster", {"email": "taken@example.org", "password": PASSWORD}, _session("id-admin@example.org")
    )
    assert via_list.status_code == 409 and via_list.headers["x-existing-account-id"] == "id-taken@example.org"


def test_adding_a_person_is_all_or_nothing(monkeypatch) -> None:
    from app.api.routes import users as users_routes
    from app.schemas.users import UserCreate

    fake = _owners(monkeypatch)
    admin = _row(fake, "admin@example.org")

    async def broken(*_: Any, **__: Any) -> Any:
        raise RuntimeError("the access list write failed")

    monkeypatch.setattr(fake.accessroster, "create", broken)
    with pytest.raises(RuntimeError):
        asyncio.run(
            users_routes.create_account(
                admin, UserCreate(email="half@example.org", name="Half", passwordMode="GENERATE", role="RESEARCHER")
            )
        )
    assert all(row.email != "half@example.org" for row in fake.user.rows)
    assert all(row.email != "half@example.org" for row in fake.accessroster.rows)


def test_this_repository_keeps_no_per_role_list() -> None:
    from app.api.routes import users as users_routes

    assert users_routes.ROLE_LISTS == {}
