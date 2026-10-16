"""The security review of the account-and-password port (2026-10-16), one section per finding.

D1  the change-password screen's "current password" spends the per-account guessing budget;
D2  a password change ends EVERY older session, including a token too old to carry a fingerprint,
    while the new session token carries on;
B1  "Forgot your password?" is limited per client on an address a client cannot type, and per door;
    administrators' links and self-service links have separate budgets;
B2  "a password was added" / "your password was changed" notices, and the two-step code before a
    first password;
B3  your own email address is not changed through the users screen;
B4  a master admin cannot reset, sign out or turn off two-step for ANOTHER master admin, nor demote,
    rename or delete one (whatever MASTER_ADMIN_EMAIL says);
N1  an unknown address meets the same per-account budget as a real one: identical 401/429 sequences
    at the sign-in and the dataset-token door;
N4  an administrator who changes an address and a password in one request tells BOTH addresses.

Driven over HTTP through the real routers against ``access_roster_fakes``, with the harness of
``test_account_passwords`` (its autouse ``environment`` fixture is imported, so it applies here too).
"""

from typing import Any

import pytest
from test_account_passwords import (  # noqa: F401 - ``environment`` is an autouse fixture
    NEW_PASSWORD,
    PASSWORD,
    _account,
    _admitted,
    _call,
    _fake,
    _google_proof,
    _params,
    _row,
    _session,
    environment,
)

from app.core.config import get_settings
from app.core.deps import clear_user_cache
from app.scale import rate_limit
from app.scale.rate_limit import (
    FORGOT_PASSWORD_GLOBAL_REQUESTS,
    FORGOT_PASSWORD_REQUESTS,
    client_address,
)
from app.services import credential_links, mailer, two_factor

T0 = 1_800_000_000.0


def _mail_on(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "mail_from_address", "no-reply@example.org")


def _kinds(fake: Any) -> list[str]:
    return [row.kind for row in fake.emailmessage.rows]


# --- D1: the per-account guessing budget on "current password" ----------------------------------


def test_wrong_current_passwords_spend_the_account_budget_and_a_right_one_is_free(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    user = _row(fake, "r@example.org")
    token = _session(user.id, user=user)
    body = {"currentPassword": "not-the-password", "newPassword": NEW_PASSWORD}
    for _ in range(9):
        assert _call("POST", "/api/auth/change-password", body, token).status_code == 400
    # The tenth guess is still a guess; a right password spends nothing, so it goes through.
    changed = _call("POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD}, token)
    assert changed.status_code == 200, changed.text
    fresh = changed.headers["x-session-token"]
    assert _call("POST", "/api/auth/change-password", {"currentPassword": "wrong-again-1", "newPassword": PASSWORD}, fresh).status_code == 400
    refused = _call("POST", "/api/auth/change-password", {"currentPassword": "wrong-again-2", "newPassword": PASSWORD}, fresh)
    assert refused.status_code == 429


def test_the_sign_in_and_the_change_screen_share_one_budget(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    user = _row(fake, "r@example.org")
    for _ in range(10):
        assert _call("POST", "/api/auth/login", {"email": "r@example.org", "password": "guess-guess-guess"}).status_code == 401
    refused = _call(
        "POST", "/api/auth/change-password", {"currentPassword": "guess-again-x", "newPassword": NEW_PASSWORD}, _session(user.id, user=user)
    )
    assert refused.status_code == 429
    assert _call("POST", "/api/auth/login", {"email": "r@example.org", "password": PASSWORD}).status_code == 429


# --- D2: every older session ends, the new one carries on ---------------------------------------


def test_a_password_change_ends_a_token_too_old_to_carry_a_fingerprint(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    user = _row(fake, "r@example.org")
    legacy = _session(user.id, minutes_ago=1)  # minted before the `cred` claim existed: none on it
    assert _call("GET", "/api/auth/me", token=legacy).status_code == 200
    changed = _call(
        "POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD}, _session(user.id, user=user)
    )
    assert changed.status_code == 200
    stamp = _row(fake, "r@example.org").sessionsValidFrom
    assert stamp is not None and stamp.microsecond == 0
    clear_user_cache()
    assert _call("GET", "/api/auth/me", token=legacy).status_code == 401
    assert _call("GET", "/api/auth/me", token=changed.headers["x-session-token"]).status_code == 200


def test_a_first_password_ends_every_older_session_too(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("g@example.org", passwordHash=None, authProvider="GOOGLE")], _admitted("g@example.org"))
    older = _session("id-g@example.org", minutes_ago=2)
    proof = _google_proof(monkeypatch, "g@example.org")
    set_first = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **proof}, _session("id-g@example.org"))
    assert set_first.status_code == 200, set_first.text
    assert _row(fake, "g@example.org").sessionsValidFrom is not None
    clear_user_cache()
    assert _call("GET", "/api/auth/me", token=older).status_code == 401
    assert _call("GET", "/api/auth/me", token=set_first.headers["x-session-token"]).status_code == 200


# --- B1: the per-client key, the door's cap, two link budgets -----------------------------------


def _scope(forwarded: str | None, peer: str = "10.0.0.9") -> dict[str, Any]:
    headers = [(b"x-forwarded-for", forwarded.encode())] if forwarded is not None else []
    return {"headers": headers, "client": (peer, 1234)}


def test_the_client_key_is_never_the_entry_a_client_types(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "trusted_proxy_hops", 1)
    assert client_address(_scope(None)) == "ip:10.0.0.9"
    assert client_address(_scope("6.6.6.6, 203.0.113.7")) == "ip:203.0.113.7"
    assert client_address(_scope("203.0.113.7")) == "ip:203.0.113.7"
    monkeypatch.setattr(get_settings(), "trusted_proxy_hops", 2)
    # CloudFront appends the viewer, nginx appends CloudFront: the viewer is second from the right.
    assert client_address(_scope("6.6.6.6, 198.51.100.4, 130.176.1.1")) == "ip:198.51.100.4"
    assert client_address(_scope("198.51.100.4")) == "ip:198.51.100.4"


def test_a_typed_forwarded_address_does_not_buy_a_fresh_allowance(monkeypatch) -> None:
    _fake(monkeypatch, [], [])
    # Production's shape (TRUSTED_PROXY_HOPS=2): the typed entry, then the viewer CloudFront saw, then
    # the CloudFront edge nginx saw.
    for n in range(FORGOT_PASSWORD_REQUESTS):
        headers = {"X-Forwarded-For": f"10.1.1.{n}, 203.0.113.7, 130.176.1.1"}
        assert _call("POST", "/api/auth/forgot-password", {"email": "x@example.org"}, headers=headers).status_code == 200
    refused = _call("POST", "/api/auth/forgot-password", {"email": "x@example.org"}, headers={"X-Forwarded-For": "10.1.1.99, 203.0.113.7, 130.176.1.1"})
    assert refused.status_code == 429


def test_the_whole_door_is_capped_whatever_the_addresses(monkeypatch) -> None:
    _fake(monkeypatch, [], [])
    for n in range(FORGOT_PASSWORD_GLOBAL_REQUESTS):
        headers = {"X-Forwarded-For": f"198.51.100.{n}"}
        assert _call("POST", "/api/auth/forgot-password", {"email": "x@example.org"}, headers=headers).status_code == 200
    refused = _call("POST", "/api/auth/forgot-password", {"email": "x@example.org"}, headers={"X-Forwarded-For": "198.51.100.250"})
    assert refused.status_code == 429 and int(refused.headers["retry-after"]) > 0
    rate_limit.reset_forgot_password_budget()


def test_self_service_links_do_not_spend_the_administrators_budget(monkeypatch) -> None:
    _mail_on(monkeypatch)
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    for n in range(3):
        _call("POST", "/api/auth/forgot-password", {"email": "r@example.org"}, headers={"X-Forwarded-For": f"203.0.113.{n}"})
    assert sum(1 for row in fake.passwordresettoken.rows if row.issuedById is None) == 3
    admin = _session("id-admin@example.org")
    for _ in range(credential_links.ISSUE_BUDGET):
        assert _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, admin).status_code == 201
    assert _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, admin).status_code == 429


def test_administrators_links_do_not_spend_the_self_service_budget(monkeypatch) -> None:
    _mail_on(monkeypatch)
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    admin = _session("id-admin@example.org")
    for _ in range(credential_links.ISSUE_BUDGET):
        assert _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, admin).status_code == 201
    _call("POST", "/api/auth/forgot-password", {"email": "r@example.org"})
    assert sum(1 for row in fake.passwordresettoken.rows if row.issuedById is None) == 1


# --- B2: the notices, and the second factor before a first password -----------------------------


def test_every_password_write_tells_the_owner_and_carries_no_secret(monkeypatch) -> None:
    _mail_on(monkeypatch)
    fake = _fake(
        monkeypatch,
        [
            _account("admin@example.org", "ADMIN"),
            _account("r@example.org"),
            _account("t@example.org"),
            _account("g@example.org", passwordHash=None, authProvider="GOOGLE"),
        ],
        _admitted("admin@example.org", "r@example.org", "t@example.org", "g@example.org"),
    )
    r = _row(fake, "r@example.org")
    assert _call("POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD}, _session(r.id, user=r)).status_code == 200
    proof = _google_proof(monkeypatch, "g@example.org")
    assert _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **proof}, _session("id-g@example.org")).status_code == 200
    assert _call("PATCH", "/api/users/id-t@example.org", {"password": "temporary-pass-1"}, _session("id-admin@example.org")).status_code == 200

    sent = {(row.toAddress, row.kind) for row in fake.emailmessage.rows}
    assert ("r@example.org", mailer.PASSWORD_CHANGED) in sent
    assert ("g@example.org", mailer.PASSWORD_ADDED) in sent
    assert ("t@example.org", mailer.PASSWORD_CHANGED) in sent
    for row in fake.emailmessage.rows:
        assert row.sealed is None
        assert set(_params(row)) == {"recipientName", "changedAt"}
        rendered = mailer.render(row.kind, _params(row))
        assert "temporary-pass-1" not in rendered.text and NEW_PASSWORD not in rendered.text
        assert "sign you out everywhere" in rendered.text


def test_a_link_redemption_tells_the_owner(monkeypatch) -> None:
    _mail_on(monkeypatch)
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("r@example.org")], _admitted("admin@example.org", "r@example.org"))
    from test_account_passwords import _token_of

    link = _call("POST", "/api/auth/password-links", {"userId": "id-r@example.org"}, _session("id-admin@example.org")).json()["link"]
    assert _call("POST", "/api/auth/set-password", {"token": _token_of(link), "password": NEW_PASSWORD}).status_code == 200
    assert mailer.PASSWORD_CHANGED in _kinds(fake)


def test_no_notice_is_queued_while_mail_is_off(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    r = _row(fake, "r@example.org")
    assert _call("POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD}, _session(r.id, user=r)).status_code == 200
    assert fake.emailmessage.rows == []


def test_a_first_password_asks_for_the_second_factor_when_it_is_on(monkeypatch) -> None:
    clock = {"now": T0}
    monkeypatch.setattr(two_factor, "_clock", lambda: clock["now"])
    fake = _fake(monkeypatch, [_account("g@example.org", passwordHash=None, authProvider="GOOGLE")], _admitted("g@example.org"))
    token = _session("id-g@example.org")
    secret = _call("POST", "/api/auth/two-factor/setup", token=token).json()["secret"]
    assert _call("POST", "/api/auth/two-factor/enable", {"code": two_factor.current_totp(secret)}, token).status_code == 200
    clock["now"] += two_factor.PERIOD_SECONDS
    clear_user_cache()
    proof = _google_proof(monkeypatch, "g@example.org")

    missing = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **proof}, _session("id-g@example.org"))
    assert missing.status_code == 400 and missing.headers["x-two-factor-required"] == "1"
    wrong = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, "twoFactorCode": "000000", **proof}, _session("id-g@example.org"))
    assert wrong.status_code == 400 and wrong.headers["x-two-factor-required"] == "1"
    assert wrong.json()["detail"] == two_factor.WRONG_CODE_DETAIL
    assert _row(fake, "g@example.org").passwordHash is None

    right = _call(
        "POST",
        "/api/auth/change-password",
        {"newPassword": NEW_PASSWORD, "twoFactorCode": two_factor.current_totp(secret), **proof},
        _session("id-g@example.org"),
    )
    assert right.status_code == 200, right.text
    assert _row(fake, "g@example.org").passwordHash is not None


# --- B3: your own address ------------------------------------------------------------------------


def test_your_own_email_address_is_changed_by_another_administrator(monkeypatch) -> None:
    fake = _fake(monkeypatch, [_account("admin@example.org", "ADMIN"), _account("other@example.org", "ADMIN")], _admitted("admin@example.org", "other@example.org"))
    own = _session("id-admin@example.org")
    refused = _call("PATCH", "/api/users/id-admin@example.org", {"email": "mine@example.org"}, own)
    assert refused.status_code == 403
    assert _row(fake, "admin@example.org").email == "admin@example.org"
    renamed = _call("PATCH", "/api/users/id-admin@example.org", {"name": "A New Name", "email": "ADMIN@example.org"}, own)
    assert renamed.status_code == 200 and renamed.json()["name"] == "A New Name"


# --- B4: a master admin over another master admin -------------------------------------------------


def test_a_master_admin_cannot_reset_another_master_admin(monkeypatch) -> None:
    fake = _fake(
        monkeypatch,
        [_account("master@example.org", "MASTER_ADMIN"), _account("deputy@example.org", "MASTER_ADMIN", twoFactorEnabled=True), _account("admin@example.org", "ADMIN")],
        _admitted("master@example.org", "deputy@example.org", "admin@example.org"),
    )
    master = _session("id-master@example.org")
    assert _call("POST", "/api/auth/password-links", {"userId": "id-deputy@example.org"}, master).status_code == 403
    assert _call("PATCH", "/api/users/id-deputy@example.org", {"password": "temporary-pass-2"}, master).status_code == 403
    assert _call("PATCH", "/api/users/id-deputy@example.org", {"mustChangePassword": True}, master).status_code == 403
    assert _call("POST", "/api/users/id-deputy@example.org/sign-out-everywhere", token=master).status_code == 403
    assert _call("DELETE", "/api/users/id-deputy@example.org/two-factor", token=master).status_code == 403
    assert _row(fake, "deputy@example.org").sessionsValidFrom is None
    # Over an administrator, every one of them still works.
    assert _call("POST", "/api/auth/password-links", {"userId": "id-admin@example.org"}, master).status_code == 201
    assert _call("POST", "/api/users/id-admin@example.org/sign-out-everywhere", token=master).status_code == 200
    # Nor demoted, renamed or deleted: the protection follows the privilege, not MASTER_ADMIN_EMAIL.
    assert _call("PATCH", "/api/users/id-deputy@example.org", {"role": "ADMIN"}, master).status_code == 403
    assert _call("PATCH", "/api/users/id-deputy@example.org", {"name": "Renamed"}, master).status_code == 403
    assert _call("DELETE", "/api/users/id-deputy@example.org", token=master).status_code == 403
    deputy = _row(fake, "deputy@example.org")
    assert str(getattr(deputy.role, "value", deputy.role)) == "MASTER_ADMIN" and deputy.name == "Somebody"
    assert _call("PATCH", "/api/users/id-admin@example.org", {"role": "RESEARCHER"}, master).status_code == 200


# --- N1: no enumeration through the per-account 429 ------------------------------------------------


def _sequence(path: str, email: str, attempts: int = 11) -> list[tuple[int, Any]]:
    return [
        (r.status_code, r.json())
        for r in (_call("POST", path, {"email": email, "password": f"wrong-guess-{n}"}) for n in range(attempts))
    ]


@pytest.mark.parametrize("path", ["/api/auth/login", "/api/datasets/token"])
def test_an_unknown_address_answers_exactly_as_a_real_one_does(monkeypatch, path) -> None:
    _fake(monkeypatch, [_account("real@example.org", "ADMIN")], _admitted("real@example.org"))
    known = _sequence(path, "real@example.org")
    unknown = _sequence(path, "nobody@example.org")
    assert known == unknown
    assert [status for status, _ in known] == [401] * 10 + [429]
    # The typed address is normalised before it is a key: a new spelling is not a fresh allowance.
    assert _call("POST", path, {"email": "  NoBody@Example.org ", "password": "another-guess"}).status_code == 429


def test_an_unknown_address_spends_a_bcrypt_check_like_a_real_one(monkeypatch) -> None:
    from app.core import security

    checks: list[bytes] = []
    real = security.bcrypt.checkpw
    monkeypatch.setattr(security.bcrypt, "checkpw", lambda pw, hashed: checks.append(hashed) or real(pw, hashed))
    _fake(monkeypatch, [_account("real@example.org")], _admitted("real@example.org"))
    assert _call("POST", "/api/auth/login", {"email": "nobody@example.org", "password": "a-wrong-guess"}).status_code == 401
    assert checks == [security._TIMING_DECOY_HASH.encode()]


# --- N4: an address and a password changed together ---------------------------------------------


def test_a_password_set_with_a_new_address_tells_the_old_address_too(monkeypatch) -> None:
    _mail_on(monkeypatch)
    fake = _fake(
        monkeypatch,
        [_account("admin@example.org", "ADMIN"), _account("t@example.org")],
        _admitted("admin@example.org", "t@example.org"),
    )
    changed = _call(
        "PATCH", "/api/users/id-t@example.org", {"email": "t.new@example.org", "password": "temporary-pass-3"}, _session("id-admin@example.org")
    )
    assert changed.status_code == 200, changed.text
    notices = sorted(row.toAddress for row in fake.emailmessage.rows if row.kind == mailer.PASSWORD_CHANGED)
    assert notices == ["t.new@example.org", "t@example.org"]
    # A password alone still sends one notice, to the one address.
    fake.emailmessage.rows.clear()
    assert _call("PATCH", "/api/users/id-t@example.org", {"password": "temporary-pass-4"}, _session("id-admin@example.org")).status_code == 200
    assert [row.toAddress for row in fake.emailmessage.rows] == ["t.new@example.org"]
