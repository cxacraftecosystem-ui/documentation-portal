"""The four auth limitations closed on 2026-10-17, one section each.

1. ``TWO_FACTOR_ENCRYPTION_KEY``: two-step secrets sealed with a key of their own, named by key id in
   the ciphertext, previous keys still opening theirs, the legacy JWT-derived key as a loud fallback,
   re-encryption on use, and an idempotent, dry-run-by-default backfill script.
2. A provider-only account's FIRST PASSWORD needs that provider's sign-in run again (under five
   minutes old, for this account's address, naming a subject) — plus the two-step code when it is on.
3. That code check spends a budget of its own: five per fifteen minutes, never the sign-in's.
4. ``TRUSTED_PROXY_HOPS`` defaults to 2, and the deploy pins it there beside the CloudFront lock.

Driven through the real routers against ``access_roster_fakes``, with ``test_account_passwords``'s
harness (its autouse ``environment`` fixture is imported, so it applies here too).
"""

import asyncio
import logging
from pathlib import Path
from typing import Any

import pytest
from access_roster_fakes import FakeDb, install
from oidc_fake_idp import FakeIdP, login_body
from pydantic import ValidationError
from test_account_passwords import (  # noqa: F401 - ``environment`` is an autouse fixture
    NEW_PASSWORD,
    _account,
    _admitted,
    _call,
    _fake,
    _google_proof,
    _row,
    _session,
    environment,
)

from app.api.routes import auth as auth_routes
from app.core.config import Settings, get_settings
from app.core.deps import clear_user_cache
from app.core.two_factor_keys import KeyFormatError, generate_key, parse_key, parse_keyring
from app.scale.rate_limit import client_address
from app.services import two_factor
from app.services.two_factor import FIRST_PASSWORD_CODE_ATTEMPTS
from scripts.reencrypt_two_factor_secrets import main as backfill_main
from scripts.reencrypt_two_factor_secrets import reencrypt_all

REPO = Path(__file__).resolve().parents[2]
KEY_A = generate_key("kA")
KEY_B = generate_key("kB")
T0 = 1_800_000_000.0


def _keys(monkeypatch: pytest.MonkeyPatch, current: str | None, previous: str | None = None) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "two_factor_encryption_key", current)
    monkeypatch.setattr(settings, "two_factor_encryption_previous_keys", previous)


# ── 1. TWO_FACTOR_ENCRYPTION_KEY ────────────────────────────────────────────────────────────────


def test_a_key_is_an_id_and_thirty_two_bytes() -> None:
    key_id, raw = parse_key(KEY_A)
    assert key_id == "kA" and len(raw) == 32
    assert generate_key().startswith("k20")
    for bad in ("no-colon", "kA:", "kA:c2hvcnQ", "bad id!:" + KEY_A.split(":")[1], ":" + KEY_A.split(":")[1]):
        with pytest.raises(KeyFormatError):
            parse_key(bad)
    # One id, two different keys: some secrets would open at random.
    with pytest.raises(KeyFormatError):
        parse_keyring(KEY_A, "kA:" + KEY_B.split(":")[1])
    current, ring = parse_keyring(KEY_B, f"{KEY_A}, ")
    assert current is not None and current[0] == "kB" and set(ring) == {"kA", "kB"}


def test_the_api_refuses_to_start_on_a_malformed_key_and_never_prints_it() -> None:
    with pytest.raises(ValidationError) as caught:
        Settings(TWO_FACTOR_ENCRYPTION_KEY="kA:not-a-real-key-at-all")
    assert "TWO_FACTOR_ENCRYPTION_KEY" in str(caught.value)
    assert "not-a-real-key-at-all" not in str(caught.value)
    assert "input_value" not in str(caught.value)
    # Nor when ANOTHER setting is the one refused: no error echoes the environment it was read from.
    with pytest.raises(ValidationError) as other:
        Settings(TWO_FACTOR_ENCRYPTION_KEY=KEY_A, JWT_ALGORITHM="none")
    assert KEY_A.split(":", 1)[1] not in str(other.value)


def test_with_a_key_secrets_are_sealed_under_its_id(monkeypatch) -> None:
    _keys(monkeypatch, KEY_A)
    sealed = two_factor.encrypt_secret("JBSWY3DPEHPK3PXP")
    assert sealed.startswith("v2.kA.") and len(sealed.split(".")) == 5
    assert two_factor.decrypt_secret(sealed) == "JBSWY3DPEHPK3PXP"
    assert not two_factor.needs_reencryption(sealed)
    # The key id is authenticated: relabelling the ciphertext to another configured key fails.
    _keys(monkeypatch, KEY_B, KEY_A)
    parts = sealed.split(".")
    assert two_factor.decrypt_secret(".".join(["v2", "kB", *parts[2:]])) is None
    assert two_factor.decrypt_secret(sealed) == "JBSWY3DPEHPK3PXP"  # a previous key still opens
    assert two_factor.needs_reencryption(sealed)
    # Retired entirely: unreadable, as None rather than an error.
    _keys(monkeypatch, KEY_B)
    assert two_factor.decrypt_secret(sealed) is None


def test_without_a_key_the_legacy_key_is_used_and_said_out_loud(monkeypatch, caplog) -> None:
    _keys(monkeypatch, None)
    two_factor.warn_legacy_key.cache_clear()
    with caplog.at_level(logging.WARNING, logger="app.services.two_factor"):
        legacy = two_factor.encrypt_secret("JBSWY3DPEHPK3PXP")
        two_factor.encrypt_secret("JBSWY3DPEHPK3PXP")
    assert legacy.startswith("v1.") and len(legacy.split(".")) == 4
    warnings = [r for r in caplog.records if "TWO_FACTOR_ENCRYPTION_KEY is not set" in r.getMessage()]
    assert len(warnings) == 1 and warnings[0].levelno == logging.WARNING
    assert not two_factor.needs_reencryption(legacy)  # nothing better to move it to
    # The day a key is set, the legacy secret still opens, and is due a move.
    _keys(monkeypatch, KEY_A)
    assert two_factor.decrypt_secret(legacy) == "JBSWY3DPEHPK3PXP"
    assert two_factor.needs_reencryption(legacy)


def _enrolled(monkeypatch: pytest.MonkeyPatch, email: str = "t@example.org", **extra: Any) -> tuple[FakeDb, str]:
    """An account with two-step on, its secret sealed under whatever key is configured NOW."""
    secret = two_factor.generate_totp_secret()
    fake = install(
        monkeypatch,
        FakeDb(
            users=[
                _account(
                    email,
                    twoFactorEnabled=True,
                    twoFactorSecret=two_factor.encrypt_secret(secret),
                    twoFactorRecoveryCodes=[],
                    **extra,
                )
            ],
            roster=_admitted(email),
        ),
    )
    return fake, secret


def test_a_secret_is_re_encrypted_the_first_time_it_is_used(monkeypatch) -> None:
    monkeypatch.setattr(two_factor, "_clock", lambda: T0)
    _keys(monkeypatch, None)
    fake, secret = _enrolled(monkeypatch)
    before = _row(fake, "t@example.org").twoFactorSecret
    assert before.startswith("v1.")

    _keys(monkeypatch, KEY_A)
    user = _row(fake, "t@example.org")
    assert asyncio.run(two_factor.consume_totp(user, two_factor.current_totp(secret, T0)))
    after = _row(fake, "t@example.org").twoFactorSecret
    assert after.startswith("v2.kA.") and two_factor.decrypt_secret(after) == secret

    # A wrong code moves nothing; and a write is conditional on the ciphertext read.
    _keys(monkeypatch, KEY_B, KEY_A)
    assert not asyncio.run(two_factor.consume_totp(_row(fake, "t@example.org"), "000000"))
    assert _row(fake, "t@example.org").twoFactorSecret == after
    assert not asyncio.run(two_factor.reencrypt_if_stale("id-t@example.org", "twoFactorSecret", "v2.kA.stale.x.y"))


def test_the_backfill_counts_by_default_writes_with_apply_and_is_idempotent(monkeypatch) -> None:
    _keys(monkeypatch, None)
    legacy = two_factor.encrypt_secret("JBSWY3DPEHPK3PXP")
    _keys(monkeypatch, KEY_A)
    current = two_factor.encrypt_secret("KRSXG5CTMVRXEZLU")
    fake = install(
        monkeypatch,
        FakeDb(
            users=[
                _account("a@example.org", twoFactorSecret=legacy),
                _account("b@example.org", twoFactorSecret=current),
                _account("c@example.org", twoFactorPendingSecret=legacy),
                _account("d@example.org", twoFactorSecret="v2.kGone.aa.bb.cc"),
                _account("e@example.org"),
            ]
        ),
    )
    dry = asyncio.run(reencrypt_all(fake, apply=False))
    assert (dry.current, dry.would_move, dry.moved) == (1, 2, 0)
    assert dry.unreadable == ["id-d@example.org (twoFactorSecret)"]
    assert _row(fake, "a@example.org").twoFactorSecret == legacy  # a dry run writes nothing

    applied = asyncio.run(reencrypt_all(fake, apply=True))
    assert applied.moved == 2
    assert _row(fake, "a@example.org").twoFactorSecret.startswith("v2.kA.")
    assert two_factor.decrypt_secret(_row(fake, "c@example.org").twoFactorPendingSecret) == "JBSWY3DPEHPK3PXP"
    assert any("moved: 2" in line for line in applied.lines(True))

    again = asyncio.run(reencrypt_all(fake, apply=True))
    assert (again.current, again.would_move, again.moved) == (3, 0, 0)


def test_the_backfill_can_mint_a_key_without_any_configuration(capsys) -> None:
    assert backfill_main(["--generate-key", "--key-id", "k2026"]) == 0
    printed = capsys.readouterr().out.strip()
    assert parse_key(printed)[0] == "k2026"


def test_the_key_is_documented_and_wired_into_the_deploy() -> None:
    example = (REPO / ".env.example").read_text(encoding="utf-8")
    assert "TWO_FACTOR_ENCRYPTION_KEY=" in example and "TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS=" in example
    workflow = (REPO / ".github/workflows/deploy-backend.yml").read_text(encoding="utf-8")
    assert "secrets.TWO_FACTOR_ENCRYPTION_KEY" in workflow
    assert "secrets.TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS" in workflow
    assert "TWO_FACTOR_ENCRYPTION_KEY" in (REPO / "docs/ENVIRONMENT.md").read_text(encoding="utf-8")


# ── 2. The first password needs the provider sign-in run again ──────────────────────────────────


def _google_only(monkeypatch: pytest.MonkeyPatch, email: str = "g@example.org", **extra: Any) -> FakeDb:
    return _fake(monkeypatch, [_account(email, passwordHash=None, authProvider="GOOGLE", **extra)], _admitted(email))


@pytest.mark.parametrize(
    ("label", "proof_kwargs"),
    [
        ("six minutes old", {"seconds_ago": 360}),
        ("from the future", {"seconds_ago": -600}),
        ("another address", {"email": "someone-else@example.org"}),
        ("address not verified", {"verified": False}),
        ("no subject", {"sub": None}),
    ],
)
def test_a_provider_sign_in_that_is_stale_or_not_this_account_is_refused(monkeypatch, label, proof_kwargs) -> None:
    fake = _google_only(monkeypatch)
    email = proof_kwargs.pop("email", "g@example.org")
    proof = _google_proof(monkeypatch, email, **proof_kwargs)
    answer = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **proof}, _session("id-g@example.org"))
    assert answer.status_code == 400, label
    assert answer.headers["x-confirm-sign-in"] == "GOOGLE"
    assert "g@example.org" in answer.json()["detail"]
    assert _row(fake, "g@example.org").passwordHash is None


def test_a_google_token_the_server_cannot_verify_is_a_400_never_a_401(monkeypatch) -> None:
    from fastapi import HTTPException

    _google_only(monkeypatch)

    def refuse(_token: str) -> dict[str, Any]:
        raise HTTPException(status_code=401, detail="Invalid Google ID token")

    monkeypatch.setattr(auth_routes, "verify_google_token", refuse)
    answer = _call(
        "POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, "googleIdToken": "forged"}, _session("id-g@example.org")
    )
    assert answer.status_code == 400 and answer.headers["x-confirm-sign-in"] == "GOOGLE"


def test_the_refusal_names_the_account_s_own_provider(monkeypatch) -> None:
    _fake(monkeypatch, [_account("m@example.org", passwordHash=None, authProvider="MICROSOFT")], _admitted("m@example.org"))
    answer = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD}, _session("id-m@example.org"))
    assert answer.status_code == 400 and answer.headers["x-confirm-sign-in"] == "MICROSOFT"
    assert "sign in with Microsoft again" in answer.json()["detail"]


@pytest.mark.parametrize("provider", ["MICROSOFT", "YAHOO"])
def test_a_microsoft_or_yahoo_sign_in_run_again_sets_the_first_password(monkeypatch, provider) -> None:
    email = "meera@outlook.com"
    fake = _fake(monkeypatch, [_account(email, passwordHash=None, authProvider=provider)], _admitted(email))
    idp = FakeIdP().install(monkeypatch)
    claims = idp.microsoft_claims(email) if provider == "MICROSOFT" else idp.yahoo_claims(email)
    idp.issue(idp.sign(claims) if provider == "MICROSOFT" else idp.sign(claims, alg="ES256"))
    answer = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **login_body(provider)}, _session(f"id-{email}"))
    assert answer.status_code == 200, answer.text
    assert _row(fake, email).passwordHash is not None


def test_a_stale_microsoft_token_is_refused(monkeypatch) -> None:
    import time

    email = "meera@outlook.com"
    fake = _fake(monkeypatch, [_account(email, passwordHash=None, authProvider="MICROSOFT")], _admitted(email))
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.microsoft_claims(email, iat=int(time.time()) - 900)))
    answer = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **login_body("MICROSOFT")}, _session(f"id-{email}"))
    assert answer.status_code == 400 and answer.headers["x-confirm-sign-in"] == "MICROSOFT"
    assert _row(fake, email).passwordHash is None


def test_a_password_change_is_untouched_by_the_provider_rule(monkeypatch) -> None:
    from test_account_passwords import PASSWORD

    fake = _fake(monkeypatch, [_account("r@example.org")], _admitted("r@example.org"))
    user = _row(fake, "r@example.org")
    answer = _call(
        "POST", "/api/auth/change-password", {"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD}, _session(user.id, user=user)
    )
    assert answer.status_code == 200


# ── 3. The first-password code check has a budget of its own ───────────────────────────────────


def test_the_first_password_code_check_has_its_own_five_per_quarter_hour_budget(monkeypatch) -> None:
    monkeypatch.setattr(two_factor, "_clock", lambda: T0)
    secret = two_factor.generate_totp_secret()
    fake = _google_only(
        monkeypatch,
        twoFactorEnabled=True,
        twoFactorSecret=two_factor.encrypt_secret(secret),
        twoFactorRecoveryCodes=[],
    )
    proof = _google_proof(monkeypatch, "g@example.org")
    body = {"newPassword": NEW_PASSWORD, **proof}
    for _ in range(FIRST_PASSWORD_CODE_ATTEMPTS):
        wrong = _call("POST", "/api/auth/change-password", {**body, "twoFactorCode": "000000"}, _session("id-g@example.org"))
        assert wrong.status_code == 400 and wrong.headers["x-two-factor-required"] == "1"
    # The sixth is refused before it is checked — even the right code.
    sixth = _call(
        "POST", "/api/auth/change-password", {**body, "twoFactorCode": two_factor.current_totp(secret, T0)}, _session("id-g@example.org")
    )
    assert sixth.status_code == 429
    row = _row(fake, "g@example.org")
    assert row.passwordHash is None
    # Not one of those guesses touched the sign-in's budget: the owner can still sign in.
    assert not getattr(row, "twoFactorFailedAttempts", 0)
    assert not two_factor.is_locked(row)


def test_a_right_code_costs_nothing_and_a_locked_second_factor_stays_locked(monkeypatch) -> None:
    from datetime import UTC, datetime, timedelta

    clock = {"now": T0}
    monkeypatch.setattr(two_factor, "_clock", lambda: clock["now"])
    secret = two_factor.generate_totp_secret()
    fake = _google_only(
        monkeypatch,
        twoFactorEnabled=True,
        twoFactorSecret=two_factor.encrypt_secret(secret),
        twoFactorRecoveryCodes=[],
        twoFactorLockedUntil=datetime.now(UTC) + timedelta(minutes=10),
    )
    proof = _google_proof(monkeypatch, "g@example.org")
    body = {"newPassword": NEW_PASSWORD, **proof, "twoFactorCode": two_factor.current_totp(secret, T0)}
    assert _call("POST", "/api/auth/change-password", body, _session("id-g@example.org")).status_code == 429

    _row(fake, "g@example.org").twoFactorLockedUntil = None
    clear_user_cache()
    for _ in range(FIRST_PASSWORD_CODE_ATTEMPTS - 1):
        _call("POST", "/api/auth/change-password", {**body, "twoFactorCode": "000000"}, _session("id-g@example.org"))
    right = _call("POST", "/api/auth/change-password", body, _session("id-g@example.org"))
    assert right.status_code == 200, right.text
    assert _row(fake, "g@example.org").passwordHash is not None


def _microsoft_with_two_step(monkeypatch: pytest.MonkeyPatch, email: str = "meera@outlook.com") -> tuple[FakeDb, str, FakeIdP]:
    monkeypatch.setattr(two_factor, "_clock", lambda: T0)
    secret = two_factor.generate_totp_secret()
    fake = _fake(
        monkeypatch,
        [
            _account(
                email,
                passwordHash=None,
                authProvider="MICROSOFT",
                twoFactorEnabled=True,
                twoFactorSecret=two_factor.encrypt_secret(secret),
                twoFactorRecoveryCodes=[],
            )
        ],
        _admitted(email),
    )
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.microsoft_claims(email)))
    return fake, secret, idp


def test_a_mistyped_code_never_spends_a_microsoft_sign_in(monkeypatch) -> None:
    email = "meera@outlook.com"
    fake, secret, idp = _microsoft_with_two_step(monkeypatch, email)
    body = {"newPassword": NEW_PASSWORD, **login_body("MICROSOFT")}
    session = _session(f"id-{email}")

    # No code at all: refused BEFORE the Microsoft code is redeemed, so it is still good.
    missing = _call("POST", "/api/auth/change-password", body, session)
    assert missing.status_code == 400 and missing.headers["x-two-factor-required"] == "1"
    assert idp.redemptions == []

    # A wrong code: redeemed once, and the refusal hands back what stands in for that sign-in.
    wrong = _call("POST", "/api/auth/change-password", {**body, "twoFactorCode": "000000"}, session)
    assert wrong.status_code == 400 and wrong.headers["x-two-factor-required"] == "1"
    carried = wrong.headers["x-sign-in-proof"]
    assert len(idp.redemptions) == 1

    # Another wrong code with it: refused again, with the next one (each is single use).
    again = _call(
        "POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, "providerProof": carried, "twoFactorCode": "111111"}, session
    )
    assert again.status_code == 400 and again.headers["x-two-factor-required"] == "1"
    following = again.headers["x-sign-in-proof"]
    reused = _call(
        "POST",
        "/api/auth/change-password",
        {"newPassword": NEW_PASSWORD, "providerProof": carried, "twoFactorCode": two_factor.current_totp(secret, T0)},
        session,
    )
    assert reused.status_code == 400 and reused.headers["x-confirm-sign-in"] == "MICROSOFT"

    # The right code with the latest one: set, and Microsoft was asked exactly once.
    right = _call(
        "POST",
        "/api/auth/change-password",
        {"newPassword": NEW_PASSWORD, "providerProof": following, "twoFactorCode": two_factor.current_totp(secret, T0)},
        session,
    )
    assert right.status_code == 200, right.text
    assert len(idp.redemptions) == 1
    assert _row(fake, email).passwordHash is not None


def test_what_stands_in_for_a_sign_in_is_no_session_and_no_other_account_s(monkeypatch) -> None:
    import time

    email = "meera@outlook.com"
    _fake(
        monkeypatch,
        [_account(email, passwordHash=None, authProvider="MICROSOFT"), _account("other@example.org", passwordHash=None)],
        _admitted(email, "other@example.org"),
    )
    carried = auth_routes._mint_sign_in_proof(f"id-{email}", "MICROSOFT", time.time() + 120)
    assert _call("GET", "/api/auth/me", None, carried).status_code == 401
    other = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, "providerProof": carried}, _session("id-other@example.org"))
    assert other.status_code == 400 and "x-confirm-sign-in" in other.headers
    stale = auth_routes._mint_sign_in_proof(f"id-{email}", "MICROSOFT", time.time() - 1)
    expired = _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, "providerProof": stale}, _session(f"id-{email}"))
    assert expired.status_code == 400 and expired.headers["x-confirm-sign-in"] == "MICROSOFT"


def test_the_first_password_code_budget_is_kept_on_the_account(monkeypatch) -> None:
    fake = _google_only(
        monkeypatch,
        twoFactorEnabled=True,
        twoFactorSecret=two_factor.encrypt_secret(two_factor.generate_totp_secret()),
        twoFactorRecoveryCodes=[],
    )
    proof = _google_proof(monkeypatch, "g@example.org")
    for _ in range(2):
        _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **proof, "twoFactorCode": "000000"}, _session("id-g@example.org"))
    # In the row, not in this process: a restart hands out nothing new.
    assert _row(fake, "g@example.org").firstPasswordCodeAttempts == 2
    # A refused sign-in gives its check back: nothing was checked.
    _google_proof(monkeypatch, "g@example.org", seconds_ago=900)
    _call("POST", "/api/auth/change-password", {"newPassword": NEW_PASSWORD, **proof, "twoFactorCode": "000000"}, _session("id-g@example.org"))
    assert _row(fake, "g@example.org").firstPasswordCodeAttempts == 2


# ── 4. TRUSTED_PROXY_HOPS ───────────────────────────────────────────────────────────────────────


def test_trusted_proxy_hops_defaults_to_cloudfront_then_nginx() -> None:
    assert Settings.model_fields["trusted_proxy_hops"].default == 2


def test_the_default_keys_on_the_viewer_cloudfront_saw(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "trusted_proxy_hops", 2)
    scope = {"headers": [(b"x-forwarded-for", b"6.6.6.6, 198.51.100.4, 130.176.1.1")], "client": ("10.0.0.9", 1)}
    assert client_address(scope) == "ip:198.51.100.4"


def test_the_deploy_pins_two_hops_only_after_checking_the_cloudfront_lock() -> None:
    workflow = (REPO / ".github/workflows/deploy-backend.yml").read_text(encoding="utf-8")
    pin = workflow.split("- name: Pin the two-step encryption key and the proxy hops", 1)[1].split("- name:", 1)[0]
    # Two only when the box does not answer the runner directly on either port; one otherwise.
    assert "hops=1" in pin and 'locked "http://$EC2_HOST/health" && locked "https://$EC2_HOST/health"' in pin
    assert "printf 'TRUSTED_PROXY_HOPS=%s\\n' \"$HOPS\"" in pin


def test_the_deploy_never_loses_a_two_step_key() -> None:
    workflow = (REPO / ".github/workflows/deploy-backend.yml").read_text(encoding="utf-8")
    # The box's lines are put aside before BACKEND_ENV replaces the file, and carried forward.
    assert "two-factor-before" in workflow and "earlier keys carried" in workflow
    # A malformed secret fails on the runner, before the box is touched.
    assert workflow.index("- name: Check the two-step encryption key secrets") < workflow.index("- name: Write .env on the server")
    # And after the switch, a secret no configured key opens fails the deploy (the release script).
    release = (REPO / "backend/deploy/blue_green.sh").read_text(encoding="utf-8")
    assert "backend/deploy/blue_green.sh" in workflow
    assert '"$py" -m scripts.reencrypt_two_factor_secrets); rc=$?' in release and '[ "$rc" = 3 ]' in release
