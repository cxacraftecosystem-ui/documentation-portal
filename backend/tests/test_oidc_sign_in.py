"""Microsoft and Yahoo ID tokens: what is accepted, and every way one is refused. No database.

Every token here is genuinely signed by ``oidc_fake_idp.FakeIdP`` and verified by the production
code against a published JWKS, so a refusal below is the verifier refusing, not a stub answering.
The admission and account-linking half — what a PROVED identity may do — is
``test_oidc_sign_in_admission.py``.
"""

from __future__ import annotations

import asyncio
import logging
import time

import pytest
from oidc_fake_idp import (
    MICROSOFT_CLIENT_ID,
    RAW_NONCE,
    REDIRECT_URI,
    VERIFIER,
    WORK_TENANT,
    YAHOO_CLIENT_ID,
    FakeIdP,
    login_body,
)
from pydantic import ValidationError

from app.schemas.auth import LoginRequest
from app.services import oidc_sign_in
from app.services.oidc_sign_in import MICROSOFT, YAHOO, EmailNotVerified, SignInRefused


@pytest.fixture
def idp(monkeypatch):
    return FakeIdP().install(monkeypatch)


def _verify(name: str, token: str, *, raw_nonce: str = RAW_NONCE, now: float | None = None):
    p = oidc_sign_in.provider(name)
    assert p is not None
    return asyncio.run(oidc_sign_in.verify_id_token(p, token, raw_nonce=raw_nonce, now=now))


def _refusal(name: str, token: str, **kwargs) -> str:
    with pytest.raises(SignInRefused) as caught:
        _verify(name, token, **kwargs)
    return caught.value.reason


# ── what is accepted ─────────────────────────────────────────────────────────────────────────────


def test_a_genuine_microsoft_token_is_accepted(idp):
    claims = _verify(MICROSOFT, idp.sign(idp.microsoft_claims("meera@outlook.com")))
    assert claims["email"] == "meera@outlook.com"


def test_a_genuine_yahoo_token_signed_es256_is_accepted(idp):
    claims = _verify(YAHOO, idp.sign(idp.yahoo_claims("ravi@yahoo.com"), alg="ES256"))
    assert claims["aud"] == YAHOO_CLIENT_ID


def test_keys_are_cached_and_refetched_only_for_an_unknown_key(idp):
    token = idp.sign(idp.microsoft_claims("meera@outlook.com"))
    _verify(MICROSOFT, token)
    _verify(MICROSOFT, token)
    assert idp.jwks_fetches == 1
    # A rotated key: one more fetch is allowed, then the token is refused as unknown.
    assert _refusal(MICROSOFT, idp.sign(idp.microsoft_claims("m@outlook.com"), kid="rotated")) == "unknown-key"
    assert idp.jwks_fetches == 1  # within the refetch floor, the cached set answers


# ── signature and algorithm ──────────────────────────────────────────────────────────────────────


def test_a_token_signed_by_an_unpublished_key_is_refused(idp):
    token = idp.sign(idp.microsoft_claims("meera@outlook.com"), key=idp.stranger_key)
    assert _refusal(MICROSOFT, token) == "bad-signature"


@pytest.mark.parametrize("alg", ["none", "HS256", "RS512"])
def test_an_algorithm_outside_rs256_and_es256_is_refused_before_any_key_is_read(idp, alg):
    assert _refusal(MICROSOFT, idp.sign(idp.microsoft_claims("m@outlook.com"), alg=alg, kid="rsa-1")) == "algorithm-refused"
    assert idp.jwks_fetches == 0


def test_a_tampered_payload_is_refused(idp):
    header, _payload, signature = idp.sign(idp.microsoft_claims("meera@outlook.com")).split(".")
    forged = idp.sign(idp.microsoft_claims("someone-else@outlook.com")).split(".")[1]
    assert _refusal(MICROSOFT, f"{header}.{forged}.{signature}") == "bad-signature"


@pytest.mark.parametrize("token", ["", "a.b", "not.a.jwt", "x" * 50])
def test_a_malformed_token_is_refused(idp, token):
    assert _refusal(MICROSOFT, token) == "malformed-token"


# ── issuer, audience, time, nonce ───────────────────────────────────────────────────────────────


def test_the_wrong_audience_is_refused(idp):
    token = idp.sign(idp.microsoft_claims("m@outlook.com", aud="another-apps-client-id"))
    assert _refusal(MICROSOFT, token) == "wrong-audience"


def test_several_audiences_need_azp_to_name_this_app(idp):
    claims = idp.yahoo_claims("r@yahoo.com", aud=[YAHOO_CLIENT_ID, "other"])
    assert _refusal(YAHOO, idp.sign(claims)) == "wrong-audience"
    claims["azp"] = YAHOO_CLIENT_ID
    assert _verify(YAHOO, idp.sign(claims))["azp"] == YAHOO_CLIENT_ID


def test_a_yahoo_token_from_another_issuer_is_refused(idp):
    assert _refusal(YAHOO, idp.sign(idp.yahoo_claims("r@yahoo.com", iss="https://login.yahoo.com"))) == "wrong-issuer"


def test_a_microsoft_issuer_must_name_the_tokens_own_tenant(idp):
    claims = idp.microsoft_claims("m@contoso.com", tenant=WORK_TENANT, xms_edov=True)
    claims["iss"] = f"https://login.microsoftonline.com/{oidc_sign_in.MICROSOFT_CONSUMER_TENANT}/v2.0"
    assert _refusal(MICROSOFT, idp.sign(claims)) == "wrong-issuer"


def test_the_configured_tenant_is_enforced(monkeypatch):
    work = FakeIdP().install(monkeypatch, microsoft_tenant="consumers")
    assert _refusal(MICROSOFT, work.sign(work.microsoft_claims("m@contoso.com", tenant=WORK_TENANT))) == "wrong-tenant"
    orgs = FakeIdP().install(monkeypatch, microsoft_tenant="organizations")
    assert _refusal(MICROSOFT, orgs.sign(orgs.microsoft_claims("m@outlook.com"))) == "wrong-tenant"
    single = FakeIdP().install(monkeypatch, microsoft_tenant=WORK_TENANT.upper())
    assert _refusal(MICROSOFT, single.sign(single.microsoft_claims("m@outlook.com"))) == "wrong-tenant"
    assert _verify(MICROSOFT, single.sign(single.microsoft_claims("m@contoso.com", tenant=WORK_TENANT)))


def test_a_tenant_named_by_domain_turns_microsoft_off_rather_than_guessing(monkeypatch):
    FakeIdP().install(monkeypatch, microsoft_tenant="contoso.onmicrosoft.com")
    assert oidc_sign_in.provider(MICROSOFT) is None


def test_an_expired_token_is_refused_and_a_minute_of_drift_is_not(idp):
    claims = idp.microsoft_claims("m@outlook.com")
    token = idp.sign(claims)
    assert _verify(MICROSOFT, token, now=claims["exp"] + 30)
    assert _refusal(MICROSOFT, token, now=claims["exp"] + 61) == "expired"


def test_a_token_from_the_future_is_refused(idp):
    later = int(time.time()) + 600
    assert _refusal(MICROSOFT, idp.sign(idp.microsoft_claims("m@outlook.com", nbf=later))) == "not-yet-valid"
    assert _refusal(YAHOO, idp.sign(idp.yahoo_claims("r@yahoo.com", iat=later))) == "issued-in-the-future"


def test_a_token_without_exp_is_refused(idp):
    claims = idp.yahoo_claims("r@yahoo.com")
    del claims["exp"]
    assert _refusal(YAHOO, idp.sign(claims)) == "expired"


def test_the_nonce_must_be_the_digest_of_the_raw_value_the_caller_holds(idp):
    token = idp.sign(idp.microsoft_claims("m@outlook.com"))
    assert _refusal(MICROSOFT, token, raw_nonce="somebody-elses-raw-nonce-value") == "nonce-mismatch"
    # Sending the digest itself (what an eavesdropper on the authorization request saw) is refused.
    assert _refusal(MICROSOFT, token, raw_nonce=oidc_sign_in.nonce_digest(RAW_NONCE)) == "nonce-mismatch"
    claims = idp.microsoft_claims("m@outlook.com")
    del claims["nonce"]
    assert _refusal(MICROSOFT, idp.sign(claims)) == "nonce-mismatch"


# ── the email, and when it counts as verified ────────────────────────────────────────────────────


def _email(name: str, claims: dict) -> str:
    p = oidc_sign_in.provider(name)
    assert p is not None
    return oidc_sign_in.verified_email(p, claims)


def test_a_personal_microsoft_address_is_verified_and_only_lower_cased(idp):
    assert _email(MICROSOFT, idp.microsoft_claims("Meera.Iyer+Field@Gmail.com")) == "meera.iyer+field@gmail.com"


def test_a_work_account_address_needs_xms_edov(idp):
    with pytest.raises(EmailNotVerified):
        _email(MICROSOFT, idp.microsoft_claims("ceo@contoso.com", tenant=WORK_TENANT))
    with pytest.raises(EmailNotVerified):
        _email(MICROSOFT, idp.microsoft_claims("ceo@contoso.com", tenant=WORK_TENANT, xms_edov=False))
    assert _email(MICROSOFT, idp.microsoft_claims("ceo@contoso.com", tenant=WORK_TENANT, xms_edov=True)) == "ceo@contoso.com"


@pytest.mark.parametrize("flag", [False, None, "false", 0])
def test_a_yahoo_address_needs_email_verified(idp, flag):
    with pytest.raises(EmailNotVerified):
        _email(YAHOO, idp.yahoo_claims("r@yahoo.com", email_verified=flag))


def test_no_email_at_all_is_unverified(idp):
    claims = idp.yahoo_claims("r@yahoo.com")
    del claims["email"]
    with pytest.raises(EmailNotVerified):
        _email(YAHOO, claims)


# ── the redemption ───────────────────────────────────────────────────────────────────────────────


def test_prove_redeems_with_the_secret_and_the_verifier_then_verifies(idp):
    idp.issue(idp.sign(idp.yahoo_claims("Ravi@Yahoo.com")))
    proved = asyncio.run(
        oidc_sign_in.prove(YAHOO, code="c0de", code_verifier=VERIFIER, redirect_uri=REDIRECT_URI, raw_nonce=RAW_NONCE)
    )
    assert (proved.provider, proved.email, proved.name) == (YAHOO, "ravi@yahoo.com", "Ravi Kumar")
    url, form = idp.redemptions[-1]
    assert url == oidc_sign_in.YAHOO_TOKEN_ENDPOINT
    assert form["code_verifier"] == VERIFIER and form["client_secret"] == "yahoo-secret-value"
    assert form["redirect_uri"] == REDIRECT_URI and form["grant_type"] == "authorization_code"


def test_microsoft_redemption_goes_to_the_configured_tenant(idp):
    idp.issue(idp.sign(idp.microsoft_claims("m@outlook.com")))
    asyncio.run(oidc_sign_in.prove(MICROSOFT, code="c", code_verifier=VERIFIER, redirect_uri=REDIRECT_URI, raw_nonce=RAW_NONCE))
    url, form = idp.redemptions[-1]
    assert url == "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    assert form["client_id"] == MICROSOFT_CLIENT_ID


def test_a_refused_redemption_is_a_refusal_and_logs_no_credential(idp, caplog):
    idp.next_answer = (400, {"error": "invalid_grant", "error_description": "code c0de-secret was used"})
    with caplog.at_level(logging.INFO), pytest.raises(SignInRefused) as caught:
        asyncio.run(oidc_sign_in.prove(YAHOO, code="c0de-secret", code_verifier=VERIFIER, redirect_uri=REDIRECT_URI, raw_nonce=RAW_NONCE))
    assert caught.value.reason == "redemption-refused"
    logged = caplog.text
    assert "invalid_grant" in logged
    for secret in ("c0de-secret", VERIFIER, RAW_NONCE, "yahoo-secret-value", "was used"):
        assert secret not in logged


def test_an_unconfigured_provider_cannot_prove_anything(monkeypatch):
    FakeIdP().install(monkeypatch, yahoo_client_secret="")
    assert oidc_sign_in.provider(YAHOO) is None
    with pytest.raises(SignInRefused):
        asyncio.run(oidc_sign_in.prove(YAHOO, code="c", code_verifier=VERIFIER, redirect_uri=REDIRECT_URI, raw_nonce=RAW_NONCE))


@pytest.mark.parametrize(
    "uri,ok",
    [
        ("https://portal.example.org/login/callback", True),
        ("http://localhost:3000/login/callback", True),
        ("http://portal.example.org/login/callback", False),
        ("javascript:alert(1)", False),
        ("https://x.org/a b", False),
    ],
)
def test_redirect_uris(uri, ok):
    assert oidc_sign_in.acceptable_redirect_uri(uri) is ok


# ── the request body ─────────────────────────────────────────────────────────────────────────────


def test_the_login_body_takes_exactly_one_credential():
    assert LoginRequest(**login_body("MICROSOFT")).has_oidc_login
    with pytest.raises(ValidationError):
        LoginRequest(**login_body("MICROSOFT"), googleIdToken="t")
    with pytest.raises(ValidationError):
        LoginRequest(**{**login_body("YAHOO"), "oidcNonce": None})
    with pytest.raises(ValidationError):
        LoginRequest(**login_body("GITHUB"))
    with pytest.raises(ValidationError):
        LoginRequest(**{**login_body("YAHOO"), "oidcCodeVerifier": "short"})
