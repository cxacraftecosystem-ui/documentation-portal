"""Authentication primitives: password hashing and JWT issue/verify.

Hardening properties this module is responsible for (documented in docs/SECURITY.md):

* **The signing algorithm is pinned on decode.** PyJWT is given exactly one algorithm — the
  configured HMAC one — so a token whose header claims ``alg: none`` (unsigned) or ``alg: RS256``
  (signature verified against our shared secret used as a "public key") is rejected outright
  instead of being trusted. Leaving ``algorithms`` unset, or passing the token's own header value,
  is the classic algorithm-confusion hole. ``Settings._normalise_jwt_algorithm`` guarantees the
  configured value is one of HS256/384/512, so the environment cannot widen this either.
* **Expiry is mandatory, not optional.** ``options["require"]`` makes a token without an ``exp``
  claim invalid rather than eternal, and ``verify_exp`` enforces it. Same for ``sub``, which every
  caller (``deps.get_current_user``) relies on to identify the account.
* **The secret is validated at startup, not at first login.** ``verify_jwt_configuration`` refuses
  to boot on the ``.env.example`` placeholder or a secret too short for the algorithm, because a
  guessable HMAC secret lets anyone forge a master-admin token, and a hole like that must fail
  visibly on deploy rather than silently in production.
* **Passwords are bcrypt, at the cost passlib used (12 rounds), and no stored hash moved.**

THE LIBRARIES CHANGED ON 2026-10-09 AND NOTHING THEY PRODUCE DID. python-jose was replaced by PyJWT
(its last release is still affected by GHSA-3qf3-8w2g-rqmx and has no fix), and passlib by bcrypt
called directly (passlib has had no release since 2020 and cannot run on bcrypt 5 at all). An HS256
token is one wire format, and a ``$2b$12$`` hash is one hash format, so every session token already
issued and every password already stored keep working. tests/test_auth_library_swap.py holds tokens
and hashes minted by the OLD pair (python-jose 3.5.0, passlib 1.7.4 on bcrypt 4.0.1) and verifies
them with this module, which is the proof rather than an argument.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

import bcrypt
import jwt

from app.core.config import get_settings

logger = logging.getLogger(__name__)

#: bcrypt reads at most this many BYTES of a password. bcrypt 4.x (under passlib) cut anything longer
#: down to it without a word, so two passwords sharing their first 72 bytes were one password;
#: bcrypt 5 refuses the longer input instead. The two halves of this module answer that differently,
#: on purpose:
#:
#: * :func:`hash_password` REFUSES — a new password must be one bcrypt can store whole, and
#:   ``app/schemas/users.py`` turns that into a 422 before the hash is ever attempted.
#: * :func:`verify_password` TRUNCATES, exactly as passlib did when it wrote the stored hash. A user
#:   who set a longer password before 2026-10-09 has a hash of its first 72 bytes, and refusing their
#:   sign-in now would lock them out of an account whose password has not changed.
BCRYPT_MAX_PASSWORD_BYTES = 72

#: passlib's own default cost for bcrypt, and bcrypt's ``gensalt()`` default. Named so a reader does
#: not have to know both libraries to see that new hashes cost what old ones did.
BCRYPT_ROUNDS = 12

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


def password_storage_problem(password: str) -> str | None:
    """Why ``password`` cannot be stored as a bcrypt hash, or None when it can.

    The schemas call this so a password bcrypt would refuse is a 422 with a sentence, not a 500 from
    :func:`hash_password`. Bytes, not characters: a Devanagari character is three bytes in UTF-8, so
    a 30-character Hindi passphrase is already past the limit that a 72-character count would allow.
    A NUL is refused because passlib refused it too ("bcrypt does not allow NULL bytes"), so no
    stored hash can have been made from one, and accepting it now would create the only ones.
    """
    encoded = password.encode("utf-8")
    if b"\x00" in encoded:
        return "Password must not contain a NUL character"
    if len(encoded) > BCRYPT_MAX_PASSWORD_BYTES:
        return (
            f"Password must be at most {BCRYPT_MAX_PASSWORD_BYTES} bytes in UTF-8, the most bcrypt "
            f"can use (this one is {len(encoded)} bytes; a letter outside English takes 2 to 4)"
        )
    return None


def hash_password(password: str) -> str:
    """A ``$2b$12$`` bcrypt hash of ``password``; ValueError when bcrypt cannot store it whole."""
    problem = password_storage_problem(password)
    if problem:
        raise ValueError(problem)
    hashed = bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=BCRYPT_ROUNDS))
    return hashed.decode("ascii")


def verify_password(password: str, password_hash: str | None) -> bool:
    """Whether ``password`` is the one ``password_hash`` was made from.

    Truncated to 72 bytes first, for the reason on :data:`BCRYPT_MAX_PASSWORD_BYTES`: it is what
    passlib did to the same input when it wrote the hashes this compares against, and it makes an
    over-long sign-in body (the login schema has no ceiling, deliberately) an ordinary wrong password
    rather than the 500 that passlib's own size cap used to raise. A password with a NUL in it cannot
    match any stored hash (see :func:`password_storage_problem`), so it is simply False.

    A MALFORMED STORED HASH still raises ValueError, as passlib's UnknownHashError did: that is a
    broken row an operator has to hear about, not a wrong password.
    """
    if not password_hash:
        return False
    secret = password.encode("utf-8")
    if b"\x00" in secret:
        return False
    return bcrypt.checkpw(secret[:BCRYPT_MAX_PASSWORD_BYTES], password_hash.encode("utf-8"))


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


def create_access_token(
    subject: str,
    extra_claims: dict[str, Any] | None = None,
    *,
    expires_minutes: int | None = None,
) -> str:
    """Mint a signed bearer token for *subject*.

    ``expires_minutes`` overrides the configured session lifetime, and is keyword-only so a caller
    cannot lengthen a token's life by accident when it meant to pass claims. The one caller that
    uses it is ``POST /api/datasets/token``, whose credential is deliberately longer-lived than a
    browser session because it is deliberately narrower (see ``deps.DATASET_READ_SCOPE``).
    """
    settings = get_settings()
    now = datetime.now(UTC)
    minutes = settings.jwt_expires_minutes if expires_minutes is None else expires_minutes
    payload: dict[str, Any] = {
        "sub": subject,
        "iat": int(now.timestamp()),
        "exp": now + timedelta(minutes=minutes),
    }
    if extra_claims:
        # ``extra_claims`` may not overwrite the three claims that decide WHO the token is for and
        # HOW LONG it lasts. Both callers today pass a dict this module built, so nothing is
        # exploitable — but a blind ``update`` means the day someone forwards a claim influenced by
        # request data, that caller can mint a token for another subject or one that never expires,
        # and ``decode_access_token``'s mandatory-``sub``/``exp`` hardening would wave it through
        # because both claims are present. Refused loudly rather than silently dropped: a caller
        # passing ``sub`` has misunderstood the signature, and quietly ignoring it would leave them
        # believing they had changed the subject.
        #
        # ``scope`` is deliberately NOT reserved — POST /api/datasets/token sets it, and it can only
        # ever NARROW what the token may reach (see deps._user_from_bearer).
        reserved = {"sub", "iat", "exp"} & extra_claims.keys()
        if reserved:
            raise ValueError(
                f"extra_claims may not override reserved claims: {', '.join(sorted(reserved))}"
            )
        payload.update(extra_claims)
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_access_token(token: str) -> dict[str, Any]:
    """Verify a bearer token and return its claims, raising ValueError on anything suspect.

    ``algorithms`` is the single configured HMAC algorithm (never the token's own ``alg`` header),
    and the options below make ``exp``/``sub`` mandatory rather than optional — a token missing
    either is rejected instead of being treated as a non-expiring or subject-less credential.

    PyJWT's spelling of python-jose's ``require_exp``/``require_sub`` is the ``require`` list; the
    two ``verify_*`` flags are its defaults, restated so that nobody reading this has to know them.
    ``jwt.PyJWTError`` is the base of everything PyJWT raises for a bad token, from a broken
    signature to a missing claim.
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
                "require": ["exp", "sub"],
            },
        )
    except jwt.PyJWTError as exc:
        raise ValueError("Invalid or expired token") from exc
