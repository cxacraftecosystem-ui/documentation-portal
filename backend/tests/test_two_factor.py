"""Two-step verification: the TOTP arithmetic, single use, the sign-in challenge, and who may reset it.

The arithmetic is held to RFC 6238 Appendix B. Everything else is driven over HTTP through the real
routers against ``access_roster_fakes`` (no database), so the assertions are about the bytes the web
app and the phone receive. Time is moved through ``two_factor._clock``, never ``time.time``.
"""

import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import jwt
import pytest
from access_roster_fakes import FakeDb, install
from fastapi import FastAPI
from oidc_fake_idp import FakeIdP, login_body

from app.api.router import api_router
from app.api.routes import auth as auth_routes
from app.core import security
from app.core.config import get_settings
from app.core.deps import clear_user_cache
from app.core.security import create_access_token, hash_password
from app.services import two_factor

PASSWORD = "correct-horse-battery"
T0 = 1_800_000_000.0  # a fixed instant, on a step boundary

_APP = FastAPI()
_APP.include_router(api_router)


@pytest.fixture(autouse=True)
def environment(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "master_admin_email", "master@example.org")
    monkeypatch.setattr(settings, "access_roster_enforced", True)
    monkeypatch.setattr(settings, "access_roster_max_pending", 2000)
    monkeypatch.setattr(settings, "default_signup_role", "CROWDSOURCE_VOLUNTEER")
    # Cheap hashes: ten recovery codes at cost 12 is seconds per test for nothing the test checks.
    monkeypatch.setattr(security, "BCRYPT_ROUNDS", 4)
    clear_user_cache()


class Clock:
    def __init__(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self.now = T0
        monkeypatch.setattr(two_factor, "_clock", lambda: self.now)

    def tick(self, steps: int = 1) -> None:
        self.now += two_factor.PERIOD_SECONDS * steps


@pytest.fixture
def clock(monkeypatch: pytest.MonkeyPatch) -> Clock:
    return Clock(monkeypatch)


def _call(method: str, path: str, body: dict[str, Any] | None = None, token: str | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        async with httpx.AsyncClient(transport=transport, base_url="http://two-factor.test") as client:
            return await client.request(method, path, json=body, headers=headers)

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


def _session(user_id: str) -> str:
    return create_access_token(subject=user_id)


def _enrol(user_id: str, clock: Clock) -> tuple[str, list[str]]:
    """Set up and switch on two-step verification the way the account screen does."""
    token = _session(user_id)
    setup = _call("POST", "/api/auth/two-factor/setup", token=token)
    assert setup.status_code == 200, setup.text
    secret = setup.json()["secret"]
    enabled = _call("POST", "/api/auth/two-factor/enable", {"code": two_factor.current_totp(secret)}, token)
    assert enabled.status_code == 200, enabled.text
    clock.tick()
    return secret, enabled.json()["recovery_codes"]


def _password_login(email: str) -> httpx.Response:
    return _call("POST", "/api/auth/login", {"email": email, "password": PASSWORD})


# --- RFC 6238 ---------------------------------------------------------------------------------------

RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"  # base32 of b"12345678901234567890"


@pytest.mark.parametrize(
    "at,eight_digits",
    [
        (59, "94287082"),
        (1111111109, "07081804"),
        (1111111111, "14050471"),
        (1234567890, "89005924"),
        (2000000000, "69279037"),
        (20000000000, "65353130"),
    ],
)
def test_rfc_6238_appendix_b_sha1_vectors(at: int, eight_digits: str) -> None:
    # The appendix prints 8 digits; a 6-digit code is the same truncation modulo 10^6.
    assert two_factor.current_totp(RFC_SECRET, at) == eight_digits[-6:]
    assert two_factor.match_totp_step(RFC_SECRET, eight_digits[-6:], at) == at // 30


def test_the_window_is_one_step_either_side() -> None:
    code = two_factor.current_totp(RFC_SECRET, T0)
    assert two_factor.match_totp_step(RFC_SECRET, code, T0 + 30) is not None
    assert two_factor.match_totp_step(RFC_SECRET, code, T0 - 30) is not None
    assert two_factor.match_totp_step(RFC_SECRET, code, T0 + 60) is None
    assert two_factor.match_totp_step(RFC_SECRET, code, T0 - 60) is None
    assert two_factor.match_totp_step(RFC_SECRET, code[:3] + " " + code[3:], T0) is not None
    assert two_factor.match_totp_step(RFC_SECRET, "12345", T0) is None


def test_a_new_secret_is_160_bits_of_unpadded_base32() -> None:
    secret = two_factor.generate_totp_secret()
    assert len(secret) == 32 and set(secret) <= set("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")


def test_the_secret_is_encrypted_and_tamper_evident(monkeypatch: pytest.MonkeyPatch) -> None:
    stored = two_factor.encrypt_secret(RFC_SECRET)
    assert RFC_SECRET not in stored and stored.startswith("v1.")
    assert two_factor.decrypt_secret(stored) == RFC_SECRET
    head, iv, tag, body = stored.split(".")
    # The FIRST character: the last one of unpadded base64 carries unused bits, so changing it can
    # leave the bytes — and the tag check — untouched, which made this test flaky.
    flipped = ("A" if body[0] != "A" else "B") + body[1:]
    assert two_factor.decrypt_secret(f"{head}.{iv}.{tag}.{flipped}") is None
    monkeypatch.setattr(get_settings(), "jwt_secret", "another-secret-entirely-0123456789abcdef")
    assert two_factor.decrypt_secret(stored) is None


def test_recovery_codes_have_one_shape_and_one_canonical_form() -> None:
    codes = two_factor.generate_recovery_codes()
    assert len(codes) == 10 and len(set(codes)) == 10
    for code in codes:
        assert len(code) == 9 and code[4] == "-"
        assert set(code.replace("-", "")) <= set(two_factor.RECOVERY_ALPHABET)
    assert two_factor.canonical_recovery_code(" ABCD efgh ") == "abcd-efgh"
    assert two_factor.canonical_recovery_code("abcd--efgh") == "abcd-efgh"
    assert two_factor.looks_like_recovery_code("ABCD-EFGH")
    assert not two_factor.looks_like_recovery_code("123456")
    assert two_factor.looks_like_totp("123 456")


def test_the_otpauth_uri_and_qr_code() -> None:
    uri = two_factor.totp_uri(RFC_SECRET, "meera@example.org")
    assert uri.startswith("otpauth://totp/Field%20Repository%3Ameera%40example.org?")
    assert f"secret={RFC_SECRET}" in uri and "issuer=Field%20Repository" in uri
    svg = two_factor.qr_svg(uri)
    assert svg.startswith("<svg") and "viewBox" in svg and svg.count("<path") == 1
    matrix = two_factor.qr_matrix(uri)
    # The quiet zone is four light modules on every side, and the path draws only M, h, v and z.
    assert not any(matrix[0]) and not any(row[0] for row in matrix)
    assert set(two_factor.qr_path(matrix)) <= set("Mhvz0123456789 -")


# --- Enrolment ----------------------------------------------------------------------------------------


def test_setup_then_enable_switches_it_on_and_hides_every_secret(monkeypatch, clock) -> None:
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    token = _session(f"id-{email}")

    assert _call("GET", "/api/auth/two-factor", token=token).json() == {
        "enabled": False, "recovery_codes_remaining": 0, "enabled_at": None
    }
    setup = _call("POST", "/api/auth/two-factor/setup", token=token).json()
    assert set(setup) == {"secret", "otpauth_uri", "qr_svg"}
    row = fake.user.rows[0]
    assert row.twoFactorEnabled is False
    assert row.twoFactorPendingSecret and setup["secret"] not in row.twoFactorPendingSecret

    wrong = _call("POST", "/api/auth/two-factor/enable", {"code": "000000"}, token)
    assert wrong.status_code == 400 and wrong.json()["detail"] == two_factor.WRONG_CODE_DETAIL

    enabled = _call("POST", "/api/auth/two-factor/enable", {"code": two_factor.current_totp(setup["secret"])}, token)
    assert enabled.status_code == 200
    codes = enabled.json()["recovery_codes"]
    assert len(codes) == 10
    assert row.twoFactorEnabled and row.twoFactorPendingSecret is None
    assert all(code not in row.twoFactorRecoveryCodes for code in codes)

    status_now = _call("GET", "/api/auth/two-factor", token=token).json()
    assert status_now["enabled"] is True and status_now["recovery_codes_remaining"] == 10

    me = _call("GET", "/api/me", token=token).json()
    for field in two_factor.SECRET_USER_FIELDS:
        assert field not in me
    assert me["twoFactorEnabled"] is True
    assert _call("POST", "/api/auth/two-factor/setup", token=token).status_code == 409


def test_the_enrolment_code_cannot_also_sign_in(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    token = _session(f"id-{email}")
    secret = _call("POST", "/api/auth/two-factor/setup", token=token).json()["secret"]
    code = two_factor.current_totp(secret)
    assert _call("POST", "/api/auth/two-factor/enable", {"code": code}, token).status_code == 200
    challenge = _password_login(email).json()["challenge_token"]
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": code}).status_code == 400


# --- The sign-in challenge ------------------------------------------------------------------------------


def test_password_sign_in_is_challenged_then_verified(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)

    first = _password_login(email)
    assert first.status_code == 200
    assert set(first.json()) == {"two_factor_required", "challenge_token"}
    assert first.json()["two_factor_required"] is True

    verified = _call(
        "POST",
        "/api/auth/two-factor/verify",
        {"challenge_token": first.json()["challenge_token"], "code": two_factor.current_totp(secret)},
    )
    assert verified.status_code == 200, verified.text
    body = verified.json()
    assert body["accessToken"] and body["user"]["email"] == email
    assert _call("GET", "/api/me", token=body["accessToken"]).status_code == 200


def test_a_code_is_single_use(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    code = two_factor.current_totp(secret)

    one = _password_login(email).json()["challenge_token"]
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": one, "code": code}).status_code == 200
    two = _password_login(email).json()["challenge_token"]
    replay = _call("POST", "/api/auth/two-factor/verify", {"challenge_token": two, "code": code})
    assert replay.status_code == 400 and replay.json()["detail"] == two_factor.WRONG_CODE_DETAIL
    clock.tick()
    fresh = two_factor.current_totp(secret)
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": two, "code": fresh}).status_code == 200


def test_a_challenge_is_single_use(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    challenge = _password_login(email).json()["challenge_token"]
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": two_factor.current_totp(secret)}).status_code == 200
    clock.tick()
    again = _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": two_factor.current_totp(secret)})
    assert again.status_code == 401 and again.json()["detail"] == two_factor.CHALLENGE_DEAD_DETAIL


def test_a_recovery_code_signs_in_once(monkeypatch, clock) -> None:
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _, codes = _enrol(f"id-{email}", clock)

    challenge = _password_login(email).json()["challenge_token"]
    typed = codes[3].upper().replace("-", " ")
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": typed}).status_code == 200
    assert len(fake.user.rows[0].twoFactorRecoveryCodes) == 9

    challenge = _password_login(email).json()["challenge_token"]
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": codes[3]}).status_code == 400


def test_five_wrong_codes_kill_the_challenge(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    challenge = _password_login(email).json()["challenge_token"]
    right = two_factor.current_totp(secret)
    wrong = "000000" if right != "000000" else "111111"
    for _ in range(two_factor.MAX_CHALLENGE_ATTEMPTS):
        assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": wrong}).status_code == 400
    dead = _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": right})
    assert dead.status_code == 401 and dead.json()["detail"] == two_factor.CHALLENGE_DEAD_DETAIL


def test_an_expired_challenge_is_refused(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    settings = get_settings()
    stale = jwt.encode(
        {
            "sub": f"id-{email}",
            "purpose": two_factor.CHALLENGE_PURPOSE,
            "scope": two_factor.CHALLENGE_SCOPE,
            "jti": "stale",
            "exp": datetime.now(UTC) - timedelta(seconds=1),
        },
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    response = _call("POST", "/api/auth/two-factor/verify", {"challenge_token": stale, "code": two_factor.current_totp(secret)})
    assert response.status_code == 401


def test_a_session_token_is_not_a_challenge_and_a_challenge_is_not_a_session(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    session = _session(f"id-{email}")
    as_challenge = _call("POST", "/api/auth/two-factor/verify", {"challenge_token": session, "code": two_factor.current_totp(secret)})
    assert as_challenge.status_code == 401
    challenge = _password_login(email).json()["challenge_token"]
    assert _call("GET", "/api/me", token=challenge).status_code == 403


def test_a_wrong_password_is_never_challenged(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _enrol(f"id-{email}", clock)
    response = _call("POST", "/api/auth/login", {"email": email, "password": "not-the-password"})
    assert response.status_code == 401 and "challenge_token" not in response.text


def test_google_sign_in_is_challenged(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _enrol(f"id-{email}", clock)
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda token: {"email": email, "email_verified": True, "name": "Meera", "picture": None},
    )
    for path in ("/api/auth/login", "/api/auth/google"):
        body = _call("POST", path, {"googleIdToken": "a-verified-token"}).json()
        assert body.get("two_factor_required") is True and "accessToken" not in body


@pytest.mark.parametrize("provider", ["MICROSOFT", "YAHOO"])
def test_microsoft_and_yahoo_sign_in_are_challenged(monkeypatch, clock, provider: str) -> None:
    email = "meera@outlook.com"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _enrol(f"id-{email}", clock)
    idp = FakeIdP().install(monkeypatch)
    if provider == "MICROSOFT":
        idp.issue(idp.sign(idp.microsoft_claims(email)))
    else:
        idp.issue(idp.sign(idp.yahoo_claims(email), alg="ES256"))
    body = _call("POST", "/api/auth/login", login_body(provider)).json()
    assert body.get("two_factor_required") is True and "accessToken" not in body


def test_an_account_without_it_signs_in_as_before(monkeypatch) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    body = _password_login(email).json()
    assert body["accessToken"] and "two_factor_required" not in body


# --- Turning it off, new recovery codes -------------------------------------------------------------------


def test_turning_it_off_needs_a_current_code(monkeypatch, clock) -> None:
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    token = _session(f"id-{email}")
    assert _call("POST", "/api/auth/two-factor/disable", {"code": "000000"}, token).status_code == 400
    assert fake.user.rows[0].twoFactorEnabled is True
    off = _call("POST", "/api/auth/two-factor/disable", {"code": two_factor.current_totp(secret)}, token)
    assert off.status_code == 204
    row = fake.user.rows[0]
    assert (row.twoFactorEnabled, row.twoFactorSecret, row.twoFactorRecoveryCodes) == (False, None, [])
    assert "accessToken" in _password_login(email).json()


def test_turning_it_off_with_a_recovery_code(monkeypatch, clock) -> None:
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _, codes = _enrol(f"id-{email}", clock)
    off = _call("POST", "/api/auth/two-factor/disable", {"code": codes[0]}, _session(f"id-{email}"))
    assert off.status_code == 204 and fake.user.rows[0].twoFactorEnabled is False


def test_new_recovery_codes_replace_the_old_ones(monkeypatch, clock) -> None:
    email = "meera@example.org"
    install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, old = _enrol(f"id-{email}", clock)
    token = _session(f"id-{email}")
    assert _call("POST", "/api/auth/two-factor/recovery-codes", {"code": old[0]}, token).status_code == 400
    fresh = _call("POST", "/api/auth/two-factor/recovery-codes", {"code": two_factor.current_totp(secret)}, token)
    assert fresh.status_code == 200
    new = fresh.json()["recovery_codes"]
    assert len(new) == 10 and not set(new) & set(old)
    challenge = _password_login(email).json()["challenge_token"]
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": old[1]}).status_code == 400
    assert _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": new[1]}).status_code == 200


# --- The administrator's reset ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "actor_role,target_role,expected",
    [
        ("ADMIN", "RESEARCHER", 200),
        ("MASTER_ADMIN", "ADMIN", 200),
        ("ADMIN", "ADMIN", 403),
        ("PROFESSOR", "RESEARCHER", 403),
        ("RESEARCHER", "FIELD_CONTRIBUTOR", 403),
    ],
)
def test_admin_reset_follows_the_manage_user_rule(monkeypatch, clock, actor_role, target_role, expected) -> None:
    target = "target@example.org"
    actor = "actor@example.org"
    fake = install(
        monkeypatch,
        FakeDb(users=[_account(target, target_role), _account(actor, actor_role)], roster=_admitted(target, actor)),
    )
    _enrol(f"id-{target}", clock)
    response = _call("DELETE", f"/api/users/id-{target}/two-factor", token=_session(f"id-{actor}"))
    assert response.status_code == expected, response.text
    row = next(r for r in fake.user.rows if r.email == target)
    assert row.twoFactorEnabled is (expected != 200)
    if expected == 200:
        assert response.json()["twoFactorEnabled"] is False
        assert row.twoFactorRecoveryCodes == [] and row.twoFactorSecret is None


def test_admin_reset_refuses_your_own_account_and_an_account_without_it(monkeypatch, clock) -> None:
    admin = "admin@example.org"
    other = "other@example.org"
    install(monkeypatch, FakeDb(users=[_account(admin, "ADMIN"), _account(other)], roster=_admitted(admin, other)))
    _enrol(f"id-{admin}", clock)
    token = _session(f"id-{admin}")
    assert _call("DELETE", f"/api/users/id-{admin}/two-factor", token=token).status_code == 403
    assert _call("DELETE", f"/api/users/id-{other}/two-factor", token=token).status_code == 409


def test_the_users_list_shows_the_badge_and_no_secret(monkeypatch, clock) -> None:
    admin = "admin@example.org"
    other = "other@example.org"
    install(monkeypatch, FakeDb(users=[_account(admin, "ADMIN"), _account(other)], roster=_admitted(admin, other)))
    _enrol(f"id-{other}", clock)
    items = _call("GET", "/api/users", token=_session(f"id-{admin}")).json()["items"]
    row = next(item for item in items if item["email"] == other)
    assert row["twoFactorEnabled"] is True
    for field in two_factor.SECRET_USER_FIELDS:
        assert field not in row


# --- The dataset token is a sign-in too ------------------------------------------------------------------


def _dataset_token(email: str, code: str | None = None) -> httpx.Response:
    body: dict[str, Any] = {"email": email, "password": PASSWORD}
    if code is not None:
        body["code"] = code
    return _call("POST", "/api/datasets/token", body)


def _wrong(secret: str) -> str:
    return "000000" if two_factor.current_totp(secret) != "000000" else "111111"


def test_a_dataset_token_needs_the_second_factor(monkeypatch, clock) -> None:
    email = "admin@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email, "ADMIN")], roster=_admitted(email)))
    secret, codes = _enrol(f"id-{email}", clock)

    missing = _dataset_token(email)
    assert missing.status_code == 401 and "accessToken" not in missing.text
    wrong = _dataset_token(email, _wrong(secret))
    assert wrong.status_code == 401 and wrong.json()["detail"] == missing.json()["detail"]
    assert fake.user.rows[0].twoFactorFailedAttempts == 1

    code = two_factor.current_totp(secret)
    minted = _dataset_token(email, code)
    assert minted.status_code == 200 and minted.json()["accessToken"]
    assert fake.user.rows[0].twoFactorFailedAttempts == 0
    # Spent: the same code cannot mint a second token, and a recovery code works once.
    assert _dataset_token(email, code).status_code == 401
    assert _dataset_token(email, codes[0]).status_code == 200
    assert _dataset_token(email, codes[0]).status_code == 401


def test_an_admin_without_it_mints_on_the_password_alone(monkeypatch) -> None:
    email = "admin@example.org"
    install(monkeypatch, FakeDb(users=[_account(email, "ADMIN")], roster=_admitted(email)))
    assert _dataset_token(email).status_code == 200


# --- The per-account failure budget ---------------------------------------------------------------------


def test_the_lockout_backs_off_and_is_capped_at_an_hour() -> None:
    assert two_factor.lockout_duration(0) == timedelta(minutes=15)
    assert two_factor.lockout_duration(1) == timedelta(minutes=30)
    assert two_factor.lockout_duration(2) == timedelta(hours=1)
    assert two_factor.lockout_duration(3) == timedelta(hours=1)
    assert two_factor.lockout_duration(500) == timedelta(hours=1)


def test_fresh_challenges_do_not_buy_more_guesses(monkeypatch, clock) -> None:
    email = "admin@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email, "ADMIN")], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    # Minted before the lock, to show that a challenge already in hand is no way round it.
    stockpiled = _password_login(email).json()["challenge_token"]

    # Two challenges of five wrong codes each spend the account's budget of ten.
    for _ in range(2):
        challenge = _password_login(email).json()["challenge_token"]
        for _ in range(two_factor.MAX_CHALLENGE_ATTEMPTS):
            response = _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": _wrong(secret)})
            assert response.status_code == 400
    row = fake.user.rows[0]
    assert row.twoFactorLockedUntil is not None and row.twoFactorLockouts == 1

    # Locked: the RIGHT code on a challenge already in hand is refused plainly…
    locked = _call(
        "POST", "/api/auth/two-factor/verify", {"challenge_token": stockpiled, "code": two_factor.current_totp(secret)}
    )
    assert locked.status_code == 429 and locked.json()["detail"] == "Too many attempts. Try again later."
    # …the dataset token shares the lock…
    assert _dataset_token(email, two_factor.current_totp(secret)).status_code == 429
    # …and sign-in mints no new challenge, answering exactly as a wrong password does.
    refused = _password_login(email)
    wrong_password = _call("POST", "/api/auth/login", {"email": email, "password": "not-the-password"})
    assert refused.status_code == wrong_password.status_code == 401
    assert refused.json() == wrong_password.json()


def test_a_lapsed_lock_lets_the_right_code_in_and_resets_the_budget(monkeypatch, clock) -> None:
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    row = fake.user.rows[0]
    # The state a lock leaves behind: budget spent, window closed, the lock just ended.
    row.twoFactorFailedAttempts = two_factor.FAILURE_BUDGET
    row.twoFactorFailureWindowStartedAt = None
    row.twoFactorLockouts = 2
    row.twoFactorLockedUntil = datetime.now(UTC) - timedelta(seconds=1)
    challenge = _password_login(email).json()["challenge_token"]
    ok = _call("POST", "/api/auth/two-factor/verify", {"challenge_token": challenge, "code": two_factor.current_totp(secret)})
    assert ok.status_code == 200
    assert (row.twoFactorFailedAttempts, row.twoFactorLockouts) == (0, 0)


def test_a_claim_outside_the_window_starts_a_new_one(monkeypatch, clock) -> None:
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _enrol(f"id-{email}", clock)
    row = fake.user.rows[0]
    row.twoFactorFailedAttempts = 9
    row.twoFactorFailureWindowStartedAt = datetime.now(UTC) - timedelta(minutes=16)
    assert asyncio.run(two_factor.claim_attempt_slot(row.id)) == 1
    assert row.twoFactorLockedUntil is None


def test_a_burst_of_concurrent_wrong_codes_gets_only_the_budget(monkeypatch, clock, caplog) -> None:
    """Finding A: every request claims a slot atomically BEFORE its code is checked, so thirty
    simultaneous guesses get ten checks, one lock and one audit line — not thirty of each."""
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    checked: list[str] = []

    async def counting_consume(user: Any, code: str) -> bool:
        checked.append(code)
        await asyncio.sleep(0)  # yield, so the burst genuinely interleaves
        return False

    monkeypatch.setattr(two_factor, "consume_any_code", counting_consume)

    async def burst() -> list[Any]:
        user = fake.user.rows[0]
        return await asyncio.gather(
            *(two_factor.check_second_factor(user, _wrong(secret)) for _ in range(30)), return_exceptions=True
        )

    with caplog.at_level("WARNING"):
        outcomes = asyncio.run(burst())
    assert len(checked) == two_factor.FAILURE_BUDGET
    assert sum(isinstance(o, two_factor.SecondFactorLocked) for o in outcomes) == 30 - two_factor.FAILURE_BUDGET
    row = fake.user.rows[0]
    assert row.twoFactorLockouts == 1 and two_factor.is_locked(row)
    assert sum("AUDIT two-factor locked" in r.getMessage() for r in caplog.records) == 1


def test_a_success_keeps_wrong_codes_that_raced_it(monkeypatch, clock) -> None:
    """Finding B: a correct code clears the budget only if nobody claimed a slot after it; otherwise
    it hands back its own slot and the racing failures stay counted."""
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _enrol(f"id-{email}", clock)
    row = fake.user.rows[0]
    row.twoFactorFailedAttempts = 6
    row.twoFactorFailureWindowStartedAt = datetime.now(UTC)

    async def race() -> None:
        mine = await two_factor.claim_attempt_slot(row.id)  # 7
        await two_factor.claim_attempt_slot(row.id)  # an attacker's wrong code lands: 8
        await two_factor.claim_attempt_slot(row.id)  # and another: 9
        await two_factor.settle_success(row.id, mine)

    asyncio.run(race())
    assert row.twoFactorFailedAttempts == 8  # the two racing failures survive; only my slot went back


def test_lockouts_escalate_once_per_lock(monkeypatch, clock) -> None:
    """Finding B: two locks in a row are lockout 1 (15 min) then lockout 2 (30 min), each written once."""
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    row = fake.user.rows[0]

    def spend() -> None:
        for _ in range(two_factor.FAILURE_BUDGET):
            assert asyncio.run(two_factor.check_second_factor(row, _wrong(secret))) is False

    spend()
    first = row.twoFactorLockedUntil - datetime.now(UTC)
    assert row.twoFactorLockouts == 1 and timedelta(minutes=14) < first <= timedelta(minutes=15)
    row.twoFactorLockedUntil = datetime.now(UTC) - timedelta(seconds=1)  # let it lapse
    spend()
    second = row.twoFactorLockedUntil - datetime.now(UTC)
    assert row.twoFactorLockouts == 2 and timedelta(minutes=29) < second <= timedelta(minutes=30)


@pytest.mark.parametrize("ended_hours_ago,expected", [(25, 0), (23, 3)])
def test_the_lockout_count_decays_a_day_after_the_last_lock(monkeypatch, clock, ended_hours_ago, expected) -> None:
    """Finding C: a password holder cannot ratchet the owner's lock up for ever."""
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    _enrol(f"id-{email}", clock)
    row = fake.user.rows[0]
    row.twoFactorLockouts = 3
    row.twoFactorLockedUntil = datetime.now(UTC) - timedelta(hours=ended_hours_ago)
    row.twoFactorFailureWindowStartedAt = None
    row.twoFactorFailedAttempts = two_factor.FAILURE_BUDGET
    asyncio.run(two_factor.claim_attempt_slot(row.id))
    assert row.twoFactorLockouts == expected and row.twoFactorFailedAttempts == 1


@pytest.mark.parametrize("path", ["/api/auth/two-factor/disable", "/api/auth/two-factor/recovery-codes"])
def test_disable_and_recovery_codes_spend_the_same_budget(monkeypatch, clock, path: str) -> None:
    email = "meera@example.org"
    fake = install(monkeypatch, FakeDb(users=[_account(email)], roster=_admitted(email)))
    secret, _ = _enrol(f"id-{email}", clock)
    token = _session(f"id-{email}")
    for _ in range(two_factor.FAILURE_BUDGET):
        assert _call("POST", path, {"code": _wrong(secret)}, token).status_code == 400
    refused = _call("POST", path, {"code": two_factor.current_totp(secret)}, token)
    assert refused.status_code == 429 and refused.json()["detail"] == two_factor.TOO_MANY_ATTEMPTS_DETAIL
    assert fake.user.rows[0].twoFactorEnabled is True


def test_an_administrators_reset_clears_the_lock(monkeypatch, clock) -> None:
    admin = "admin@example.org"
    other = "other@example.org"
    fake = install(
        monkeypatch, FakeDb(users=[_account(admin, "ADMIN"), _account(other)], roster=_admitted(admin, other))
    )
    _enrol(f"id-{other}", clock)
    row = next(r for r in fake.user.rows if r.email == other)
    row.twoFactorLockedUntil = datetime.now(UTC) + timedelta(hours=1)
    row.twoFactorLockouts = 3
    row.twoFactorFailedAttempts = 4
    assert _call("DELETE", f"/api/users/id-{other}/two-factor", token=_session(f"id-{admin}")).status_code == 200
    assert (row.twoFactorLockedUntil, row.twoFactorLockouts, row.twoFactorFailedAttempts) == (None, 0, 0)


# --- No embedded User leaks a secret ----------------------------------------------------------------------


def test_public_encode_strips_every_secret_user_field_at_any_depth() -> None:
    from app.core.user_fields import SECRET_USER_FIELDS
    from app.services.records import public_encode

    populated = {
        "id": "u1",
        "email": "meera@example.org",
        "name": "Meera",
        "role": "RESEARCHER",
        "twoFactorEnabled": True,
        "passwordHash": "$2b$12$hash",
        "twoFactorSecret": "v1.a.b.c",
        "twoFactorPendingSecret": "v1.d.e.f",
        "twoFactorRecoveryCodes": ["$2b$12$code"],
        "twoFactorLastStep": 59_000_000,
        "twoFactorFailedAttempts": 3,
        "twoFactorFailureWindowStartedAt": datetime.now(UTC),
        "twoFactorLockedUntil": datetime.now(UTC),
        "twoFactorLockouts": 1,
    }
    assert set(SECRET_USER_FIELDS) <= set(populated), "the fixture must populate every secret field"
    record = {
        "id": "m1",
        "uploadedBy": dict(populated),
        "workshop": {"createdBy": dict(populated), "assignments": [{"user": dict(populated)}]},
    }
    encoded = public_encode(record)

    def keys(value: Any) -> set[str]:
        if isinstance(value, dict):
            found = set(value)
            for nested in value.values():
                found |= keys(nested)
            return found
        if isinstance(value, list):
            found = set()
            for nested in value:
                found |= keys(nested)
            return found
        return set()

    assert not keys(encoded) & set(SECRET_USER_FIELDS)
    assert encoded["uploadedBy"]["twoFactorEnabled"] is True and encoded["uploadedBy"]["email"]
