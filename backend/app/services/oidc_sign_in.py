from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import re
import threading
import time
from dataclasses import dataclass
from typing import Any

import requests
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

from app.core.config import get_settings

logger = logging.getLogger(__name__)

MICROSOFT = "MICROSOFT"
YAHOO = "YAHOO"
PROVIDERS = (MICROSOFT, YAHOO)

MICROSOFT_CONSUMER_TENANT = "9188040d-6c67-4c5b-b112-36a304b66dad"
MICROSOFT_LOGIN_HOST = "https://login.microsoftonline.com"
MICROSOFT_TENANT_KEYWORDS = ("common", "organizations", "consumers")

YAHOO_ISSUER = "https://api.login.yahoo.com"
YAHOO_TOKEN_ENDPOINT = "https://api.login.yahoo.com/oauth2/get_token"
YAHOO_JWKS_URI = "https://api.login.yahoo.com/openid/v1/certs"

ALLOWED_ALGORITHMS = ("RS256", "ES256")
CLOCK_LEEWAY_SECONDS = 60
JWKS_TTL_SECONDS = 3600
JWKS_REFETCH_FLOOR_SECONDS = 60
HTTP_TIMEOUT_SECONDS = 10

_GUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class SignInRefused(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class EmailNotVerified(SignInRefused):
    pass


@dataclass(frozen=True)
class Provider:
    name: str
    label: str
    client_id: str
    client_secret: str
    token_endpoint: str
    jwks_uri: str
    tenant: str | None = None
    scope: str = "openid email profile"


def label_of(name: str) -> str:
    return {MICROSOFT: "Microsoft", YAHOO: "Yahoo"}.get(name, name.title())


def microsoft_tenant(raw: str | None) -> str | None:
    value = (raw or "common").strip().lower()
    if value in MICROSOFT_TENANT_KEYWORDS or _GUID.match(value):
        return value
    return None


def provider(name: str) -> Provider | None:
    settings = get_settings()
    if name == MICROSOFT:
        client_id = (settings.microsoft_client_id or "").strip()
        secret = (settings.microsoft_client_secret or "").strip()
        tenant = microsoft_tenant(settings.microsoft_tenant)
        if not client_id or not secret:
            return None
        if tenant is None:
            logger.error(
                "oidc: MICROSOFT_TENANT must be common, organizations, consumers or a tenant ID; "
                "Microsoft sign-in is off until it is"
            )
            return None
        return Provider(
            name=MICROSOFT,
            label="Microsoft",
            client_id=client_id,
            client_secret=secret,
            token_endpoint=f"{MICROSOFT_LOGIN_HOST}/{tenant}/oauth2/v2.0/token",
            jwks_uri=f"{MICROSOFT_LOGIN_HOST}/{tenant}/discovery/v2.0/keys",
            tenant=tenant,
        )
    if name == YAHOO:
        client_id = (settings.yahoo_client_id or "").strip()
        secret = (settings.yahoo_client_secret or "").strip()
        if not client_id or not secret:
            return None
        return Provider(
            name=YAHOO,
            label="Yahoo",
            client_id=client_id,
            client_secret=secret,
            token_endpoint=YAHOO_TOKEN_ENDPOINT,
            jwks_uri=YAHOO_JWKS_URI,
            scope="openid email profile",
        )
    return None


def nonce_digest(raw_nonce: str) -> str:
    digest = hashlib.sha256(raw_nonce.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


def acceptable_redirect_uri(uri: str) -> bool:
    if len(uri) > 2048 or any(ch.isspace() for ch in uri):
        return False
    if uri.startswith("https://"):
        return True
    return uri.startswith(("http://localhost:", "http://localhost/", "http://127.0.0.1:"))



def _post_form(url: str, data: dict[str, str]) -> tuple[int, Any]:
    response = requests.post(
        url, data=data, headers={"Accept": "application/json"}, timeout=HTTP_TIMEOUT_SECONDS
    )
    try:
        body = response.json()
    except ValueError:
        body = None
    return response.status_code, body


def _get_json(url: str) -> Any:
    response = requests.get(url, headers={"Accept": "application/json"}, timeout=HTTP_TIMEOUT_SECONDS)
    response.raise_for_status()
    return response.json()


async def redeem_code(
    p: Provider, *, code: str, code_verifier: str, redirect_uri: str
) -> str:
    data = {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri,
        "client_id": p.client_id,
        "client_secret": p.client_secret,
        "code_verifier": code_verifier,
    }
    if p.name == MICROSOFT:
        data["scope"] = p.scope
    try:
        status_code, body = await asyncio.to_thread(_post_form, p.token_endpoint, data)
    except requests.RequestException as exc:
        logger.warning("oidc: %s code redemption did not complete (%s)", p.name, type(exc).__name__)
        raise SignInRefused("redemption-unreachable") from exc
    if status_code != 200 or not isinstance(body, dict):
        error = body.get("error") if isinstance(body, dict) else None
        logger.info(
            "oidc: %s refused the code redemption (HTTP %s, %s)",
            p.name,
            status_code,
            error if isinstance(error, str) and error.isidentifier() else "no error code",
        )
        raise SignInRefused("redemption-refused")
    id_token = body.get("id_token")
    if not isinstance(id_token, str) or not id_token:
        logger.info("oidc: %s answered the code redemption without an ID token", p.name)
        raise SignInRefused("no-id-token")
    return id_token



@dataclass
class _KeySet:
    fetched_at: float
    keys: list[dict[str, Any]]


_jwks_cache: dict[str, _KeySet] = {}
_jwks_lock = threading.Lock()


def forget_cached_keys() -> None:
    with _jwks_lock:
        _jwks_cache.clear()


async def _keys(uri: str, *, refresh: bool) -> list[dict[str, Any]]:
    now = time.monotonic()
    with _jwks_lock:
        cached = _jwks_cache.get(uri)
    if cached is not None:
        fresh = now - cached.fetched_at < JWKS_TTL_SECONDS
        may_refetch = now - cached.fetched_at >= JWKS_REFETCH_FLOOR_SECONDS
        if fresh and not (refresh and may_refetch):
            return cached.keys
    try:
        document = await asyncio.to_thread(_get_json, uri)
    except (requests.RequestException, ValueError) as exc:
        logger.warning("oidc: the signing keys at %s could not be read (%s)", uri, type(exc).__name__)
        if cached is not None:
            return cached.keys
        raise SignInRefused("jwks-unreachable") from exc
    keys = document.get("keys") if isinstance(document, dict) else None
    if not isinstance(keys, list):
        raise SignInRefused("jwks-malformed")
    keys = [key for key in keys if isinstance(key, dict)]
    with _jwks_lock:
        _jwks_cache[uri] = _KeySet(fetched_at=now, keys=keys)
    return keys


def _b64url_decode(segment: str) -> bytes:
    padded = segment + "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode(padded.encode("ascii"))


def _int_of(segment: str) -> int:
    return int.from_bytes(_b64url_decode(segment), "big")


def _public_key(jwk: dict[str, Any], alg: str) -> Any:
    kty = jwk.get("kty")
    if alg == "RS256" and kty == "RSA":
        return rsa.RSAPublicNumbers(_int_of(jwk["e"]), _int_of(jwk["n"])).public_key()
    if alg == "ES256" and kty == "EC" and jwk.get("crv") == "P-256":
        return ec.EllipticCurvePublicNumbers(
            _int_of(jwk["x"]), _int_of(jwk["y"]), ec.SECP256R1()
        ).public_key()
    raise SignInRefused("key-type-mismatch")


def _verify_signature(key: Any, alg: str, signing_input: bytes, signature: bytes) -> None:
    try:
        if alg == "RS256":
            key.verify(signature, signing_input, padding.PKCS1v15(), hashes.SHA256())
            return
        if len(signature) != 64:
            raise SignInRefused("bad-signature")
        der = encode_dss_signature(
            int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big")
        )
        key.verify(der, signing_input, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as exc:
        raise SignInRefused("bad-signature") from exc


def _split(token: str) -> tuple[dict[str, Any], dict[str, Any], bytes, bytes]:
    parts = token.split(".")
    if len(parts) != 3:
        raise SignInRefused("malformed-token")
    try:
        header = json.loads(_b64url_decode(parts[0]))
        claims = json.loads(_b64url_decode(parts[1]))
        signature = _b64url_decode(parts[2])
    except (ValueError, UnicodeDecodeError) as exc:
        raise SignInRefused("malformed-token") from exc
    if not isinstance(header, dict) or not isinstance(claims, dict):
        raise SignInRefused("malformed-token")
    return header, claims, f"{parts[0]}.{parts[1]}".encode("ascii"), signature


def _expected_issuer(p: Provider, claims: dict[str, Any]) -> str:
    if p.name == YAHOO:
        return YAHOO_ISSUER
    tid = claims.get("tid")
    if not isinstance(tid, str) or not _GUID.match(tid.lower()):
        raise SignInRefused("no-tenant")
    tid = tid.lower()
    if p.tenant == "consumers" and tid != MICROSOFT_CONSUMER_TENANT:
        raise SignInRefused("wrong-tenant")
    if p.tenant == "organizations" and tid == MICROSOFT_CONSUMER_TENANT:
        raise SignInRefused("wrong-tenant")
    if p.tenant not in MICROSOFT_TENANT_KEYWORDS and tid != p.tenant:
        raise SignInRefused("wrong-tenant")
    return f"{MICROSOFT_LOGIN_HOST}/{tid}/v2.0"


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


async def verify_id_token(
    p: Provider, token: str, *, raw_nonce: str, now: float | None = None
) -> dict[str, Any]:
    header, claims, signing_input, signature = _split(token)
    alg = header.get("alg")
    if alg not in ALLOWED_ALGORITHMS:
        raise SignInRefused("algorithm-refused")
    kid = header.get("kid")

    def matching(keys: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return [
            key
            for key in keys
            if (kid is None or key.get("kid") == kid)
            and key.get("use", "sig") == "sig"
            and key.get("alg", alg) == alg
        ]

    candidates = matching(await _keys(p.jwks_uri, refresh=False))
    if not candidates:
        candidates = matching(await _keys(p.jwks_uri, refresh=True))
    if not candidates:
        raise SignInRefused("unknown-key")
    last: SignInRefused | None = None
    for jwk in candidates:
        try:
            _verify_signature(_public_key(jwk, alg), alg, signing_input, signature)
            break
        except (SignInRefused, KeyError, ValueError) as exc:
            last = exc if isinstance(exc, SignInRefused) else SignInRefused("bad-key")
    else:
        raise last or SignInRefused("bad-signature")

    if claims.get("iss") != _expected_issuer(p, claims):
        raise SignInRefused("wrong-issuer")

    audience = claims.get("aud")
    audiences = audience if isinstance(audience, list) else [audience]
    if p.client_id not in audiences:
        raise SignInRefused("wrong-audience")
    if len(audiences) > 1 and claims.get("azp") != p.client_id:
        raise SignInRefused("wrong-audience")

    moment = time.time() if now is None else now
    expires = _number(claims.get("exp"))
    if expires is None or expires <= moment - CLOCK_LEEWAY_SECONDS:
        raise SignInRefused("expired")
    not_before = _number(claims.get("nbf"))
    if not_before is not None and not_before > moment + CLOCK_LEEWAY_SECONDS:
        raise SignInRefused("not-yet-valid")
    issued = _number(claims.get("iat"))
    if issued is not None and issued > moment + CLOCK_LEEWAY_SECONDS:
        raise SignInRefused("issued-in-the-future")

    nonce = claims.get("nonce")
    if not isinstance(nonce, str) or not hmac.compare_digest(
        nonce.encode("utf-8"), nonce_digest(raw_nonce).encode("utf-8")
    ):
        raise SignInRefused("nonce-mismatch")
    return claims


def _truthy(value: Any) -> bool:
    return value is True or (isinstance(value, str) and value.strip().lower() in ("true", "1"))


def verified_email(p: Provider, claims: dict[str, Any]) -> str:
    email = claims.get("email")
    if not isinstance(email, str) or "@" not in email.strip():
        raise EmailNotVerified("no-email")
    if p.name == YAHOO:
        verified = _truthy(claims.get("email_verified"))
    else:
        tid = str(claims.get("tid") or "").lower()
        verified = tid == MICROSOFT_CONSUMER_TENANT or _truthy(claims.get("xms_edov"))
    if not verified:
        raise EmailNotVerified("email-not-verified")
    return email.strip().lower()


def display_name(claims: dict[str, Any]) -> str | None:
    name = claims.get("name")
    if isinstance(name, str) and name.strip():
        return name.strip()[:200]
    given = claims.get("given_name")
    family = claims.get("family_name")
    joined = " ".join(part.strip() for part in (given, family) if isinstance(part, str) and part.strip())
    return joined[:200] or None


@dataclass(frozen=True)
class ProvedIdentity:
    provider: str
    email: str
    name: str | None
    subject: str | None = None
    issued_at: float | None = None


async def prove(
    name: str, *, code: str, code_verifier: str, redirect_uri: str, raw_nonce: str
) -> ProvedIdentity:
    p = provider(name)
    if p is None:
        raise SignInRefused("not-configured")
    token = await redeem_code(p, code=code, code_verifier=code_verifier, redirect_uri=redirect_uri)
    claims = await verify_id_token(p, token, raw_nonce=raw_nonce)
    subject = claims.get("sub")
    return ProvedIdentity(
        provider=p.name,
        email=verified_email(p, claims),
        name=display_name(claims),
        subject=subject if isinstance(subject, str) and subject else None,
        issued_at=_number(claims.get("iat")),
    )
