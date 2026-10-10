"""A fake Microsoft / Yahoo identity provider for the sign-in tests. Not a test module.

It holds real key pairs (RSA for RS256, P-256 for ES256), publishes their public halves as a JWKS
and mints ID tokens signed with the private halves, so ``app.services.oidc_sign_in`` verifies them
with exactly the code that verifies a real provider's. Only the two HTTP calls are replaced — the
token endpoint and the JWKS fetch — and nothing in the module under test knows it is being tested.
"""

from __future__ import annotations

import base64
import json
import time
from types import SimpleNamespace
from typing import Any

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

from app.services import oidc_sign_in

MICROSOFT_CLIENT_ID = "00000000-aaaa-bbbb-cccc-000000000001"
YAHOO_CLIENT_ID = "dj0yJmk9ZmFrZS15YWhvby1jbGllbnQ"
#: A work-or-school tenant, as opposed to the consumer tenant every personal account uses.
WORK_TENANT = "72f988bf-86f1-41af-91ab-2d7cd011db47"
RAW_NONCE = "a-raw-nonce-only-the-client-holds-0123456789"
VERIFIER = "v" * 64
REDIRECT_URI = "https://portal.example.org/login/callback"


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _int_b64(value: int) -> str:
    return _b64(value.to_bytes((value.bit_length() + 7) // 8, "big"))


def settings(**overrides: Any) -> SimpleNamespace:
    values = {
        "microsoft_client_id": MICROSOFT_CLIENT_ID,
        "microsoft_client_secret": "microsoft-secret-value",
        "microsoft_tenant": "common",
        "yahoo_client_id": YAHOO_CLIENT_ID,
        "yahoo_client_secret": "yahoo-secret-value",
    }
    values.update(overrides)
    return SimpleNamespace(**values)


class FakeIdP:
    def __init__(self) -> None:
        self.rsa_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.ec_key = ec.generate_private_key(ec.SECP256R1())
        #: A key nobody published: a token signed with it must be refused.
        self.stranger_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        rsa_public = self.rsa_key.public_key().public_numbers()
        ec_public = self.ec_key.public_key().public_numbers()
        self.jwks = {
            "keys": [
                {"kty": "RSA", "kid": "rsa-1", "use": "sig", "alg": "RS256",
                 "n": _int_b64(rsa_public.n), "e": _int_b64(rsa_public.e)},
                {"kty": "EC", "kid": "ec-1", "use": "sig", "crv": "P-256",
                 "x": _b64(ec_public.x.to_bytes(32, "big")), "y": _b64(ec_public.y.to_bytes(32, "big"))},
            ]
        }
        self.jwks_fetches = 0
        self.redemptions: list[tuple[str, dict[str, str]]] = []
        #: What the token endpoint answers next: (status, body). Set by ``issue``.
        self.next_answer: tuple[int, Any] = (400, {"error": "invalid_grant"})

    # ── minting ──────────────────────────────────────────────────────────────────────────────

    def sign(self, claims: dict[str, Any], *, alg: str = "RS256", kid: str | None = None,
             key: Any = None) -> str:
        header = {"alg": alg, "typ": "JWT"}
        if kid is not None or alg in ("RS256", "ES256"):
            header["kid"] = kid if kid is not None else ("ec-1" if alg == "ES256" else "rsa-1")
        signing_input = f"{_b64(json.dumps(header).encode())}.{_b64(json.dumps(claims).encode())}"
        if alg == "RS256":
            signer = key or self.rsa_key
            signature = signer.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
        elif alg == "ES256":
            der = (key or self.ec_key).sign(signing_input.encode(), ec.ECDSA(hashes.SHA256()))
            r, s = decode_dss_signature(der)
            signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
        else:
            signature = b""
        return f"{signing_input}.{_b64(signature)}"

    @staticmethod
    def microsoft_claims(email: str, *, tenant: str = oidc_sign_in.MICROSOFT_CONSUMER_TENANT,
                         **extra: Any) -> dict[str, Any]:
        now = int(time.time())
        claims = {
            "iss": f"https://login.microsoftonline.com/{tenant}/v2.0",
            "aud": MICROSOFT_CLIENT_ID,
            "tid": tenant,
            "sub": "pairwise-subject",
            "email": email,
            "name": "Meera Iyer",
            "iat": now,
            "nbf": now,
            "exp": now + 3600,
            "nonce": oidc_sign_in.nonce_digest(RAW_NONCE),
        }
        claims.update(extra)
        return claims

    @staticmethod
    def yahoo_claims(email: str, **extra: Any) -> dict[str, Any]:
        now = int(time.time())
        claims = {
            "iss": "https://api.login.yahoo.com",
            "aud": YAHOO_CLIENT_ID,
            "sub": "yahoo-subject",
            "email": email,
            "email_verified": True,
            "name": "Ravi Kumar",
            "iat": now,
            "exp": now + 3600,
            "nonce": oidc_sign_in.nonce_digest(RAW_NONCE),
        }
        claims.update(extra)
        return claims

    def issue(self, token: str) -> None:
        self.next_answer = (200, {"id_token": token, "token_type": "Bearer", "access_token": "at"})

    # ── the two HTTP calls oidc_sign_in makes ────────────────────────────────────────────────

    def post_form(self, url: str, data: dict[str, str]) -> tuple[int, Any]:
        self.redemptions.append((url, dict(data)))
        return self.next_answer

    def get_json(self, url: str) -> Any:
        self.jwks_fetches += 1
        return self.jwks

    def install(self, monkeypatch: Any, **setting_overrides: Any) -> FakeIdP:
        oidc_sign_in.forget_cached_keys()
        monkeypatch.setattr(oidc_sign_in, "_post_form", self.post_form)
        monkeypatch.setattr(oidc_sign_in, "_get_json", self.get_json)
        monkeypatch.setattr(oidc_sign_in, "get_settings", lambda: settings(**setting_overrides))
        return self


def login_body(provider: str, *, code: str = "the-code") -> dict[str, str]:
    return {
        "oidcProvider": provider,
        "oidcCode": code,
        "oidcCodeVerifier": VERIFIER,
        "oidcRedirectUri": REDIRECT_URI,
        "oidcNonce": RAW_NONCE,
    }

