"""Authentication primitives: password hashing and JWT issue/verify.

Hardening properties this module is responsible for (documented in docs/SECURITY.md):

* **The signing algorithm is pinned on decode.** ``jose`` is given exactly one algorithm — the
  configured HMAC one — so a token whose header claims ``alg: none`` (unsigned) or ``alg: RS256``
  (signature verified against our shared secret used as a "public key") is rejected outright
  instead of being trusted. Leaving ``algorithms`` unset, or passing the token's own header value,
  is the classic algorithm-confusion hole. ``Settings._normalise_jwt_algorithm`` guarantees the
  configured value is one of HS256/384/512, so the environment cannot widen this either.
* **Expiry is mandatory, not optional.** ``require_exp`` makes a token without an ``exp`` claim
  invalid rather than eternal, and ``verify_exp`` enforces it. Same for ``sub``, which every caller
  (``deps.get_current_user``) relies on to identify the account.
* **The secret is validated at startup, not at first login.** ``verify_jwt_configuration`` refuses
  to boot on the ``.env.example`` placeholder or a secret too short for the algorithm, because a
  guessable HMAC secret lets anyone forge a master-admin token, and a hole like that must fail
  visibly on deploy rather than silently in production.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from jose import JWTError, jwt
from passlib.context import CryptContext

from app.core.config import get_settings

logger = logging.getLogger(__name__)

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# HS256 signs with a 256-bit key; a secret shorter than 32 characters has less entropy than the
# algorithm assumes and is brute-forceable offline from a single captured token.
MIN_JWT_SECRET_LENGTH = 32

# Values that ship in .env.example / tutorials and therefore are public knowledge. Compared
# case-insensitively; any secret merely *containing* "change" and "secret" is caught by the
# substring rule in _jwt_secret_weakness.
_PLACEHOLDER_JWT_SECRETS = frozenset(
    {
        "change-this-to-a-long-random-secret",
        "change-me",
        "changeme",
        "secret",
        "supersecret",
        "your-secret-key",
        "test",
    }
)


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(password: str, password_hash: str | None) -> bool:
    if not password_hash:
        return False
    return pwd_context.verify(password, password_hash)


def _jwt_secret_weakness(secret: str) -> str | None:
    """Describe why ``secret`` is unsafe for signing tokens, or None when it is acceptable."""
    candidate = secret.strip()
    lowered = candidate.lower()
    if not candidate:
        return "JWT_SECRET is empty"
    if lowered in _PLACEHOLDER_JWT_SECRETS or ("change" in lowered and "secret" in lowered):
        return "JWT_SECRET is still the example placeholder, which is public knowledge"
    if len(candidate) < MIN_JWT_SECRET_LENGTH:
        return (
            f"JWT_SECRET is {len(candidate)} characters; at least {MIN_JWT_SECRET_LENGTH} are "
            "required for the HMAC signing key"
        )
    return None


def verify_jwt_configuration() -> None:
    """Fail fast (and loudly) when the token-signing secret is guessable.

    Called from ``create_app`` so ``uvicorn app.main:app`` refuses to start rather than serving an
    API whose tokens anyone can forge. Set ``ALLOW_WEAK_JWT_SECRET=true`` to downgrade the refusal
    to a CRITICAL log line — intended for local development only; see docs/SECURITY.md.
    """
    settings = get_settings()
    weakness = _jwt_secret_weakness(settings.jwt_secret)
    if not weakness:
        return
    message = (
        f"Insecure JWT signing configuration: {weakness}. Anyone who guesses it can mint a token "
        "for any account, including the master admin. Generate one with "
        "`python -c \"import secrets; print(secrets.token_urlsafe(48))\"` and set JWT_SECRET."
    )
    if settings.allow_weak_jwt_secret:
        logger.critical("%s (ALLOW_WEAK_JWT_SECRET is set — continuing anyway)", message)
        return
    raise RuntimeError(f"{message} (set ALLOW_WEAK_JWT_SECRET=true to override in development)")


def create_access_token(subject: str, extra_claims: dict[str, Any] | None = None) -> str:
    settings = get_settings()
    now = datetime.now(UTC)
    payload: dict[str, Any] = {
        "sub": subject,
        "iat": int(now.timestamp()),
        "exp": now + timedelta(minutes=settings.jwt_expires_minutes),
    }
    if extra_claims:
        payload.update(extra_claims)
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_access_token(token: str) -> dict[str, Any]:
    """Verify a bearer token and return its claims, raising ValueError on anything suspect.

    ``algorithms`` is the single configured HMAC algorithm (never the token's own ``alg`` header),
    and the options below make ``exp``/``sub`` mandatory rather than optional — a token missing
    either is rejected instead of being treated as a non-expiring or subject-less credential.
    """
    settings = get_settings()
    try:
        return jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            options={
                "verify_signature": True,
                "verify_exp": True,
                "require_exp": True,
                "require_sub": True,
            },
        )
    except JWTError as exc:
        raise ValueError("Invalid or expired token") from exc
