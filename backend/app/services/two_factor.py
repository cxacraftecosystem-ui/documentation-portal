from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import logging
import re
import secrets
import struct
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from typing import Any
from urllib.parse import quote, urlencode

import jwt
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import invalidate_cached_user
from app.core.security import hash_password, verify_password
from app.core.two_factor_keys import parse_keyring
from app.core.user_fields import SECRET_USER_FIELDS as _SECRET_USER_FIELDS
from app.services import mailer

logger = logging.getLogger(__name__)

DIGITS = 6
PERIOD_SECONDS = 30
WINDOW_STEPS = 1

TOTP_ISSUER = "Field Repository"

RECOVERY_CODE_COUNT = 10
RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

CHALLENGE_PURPOSE = "2fa"
CHALLENGE_SCOPE = "two-factor-challenge"
CHALLENGE_TTL_MINUTES = 5
MAX_CHALLENGE_ATTEMPTS = 5

WRONG_CODE_DETAIL = "That code didn't work. Check your authenticator app and try again."
CHALLENGE_DEAD_DETAIL = "This sign-in has timed out. Sign in again."
TOO_MANY_ATTEMPTS_DETAIL = "Too many attempts. Try again later."

FAILURE_BUDGET = 10
RECOVERY_FAILURE_BUDGET = 5
FAILURE_WINDOW = timedelta(minutes=15)
FIRST_LOCKOUT = timedelta(minutes=15)
MAX_LOCKOUT = timedelta(hours=1)
LOCKOUT_DECAY = timedelta(hours=24)

_ENCRYPTION_LABEL = b"field-repository-totp-v1"



def _clock() -> float:
    return time.time()


def generate_totp_secret() -> str:
    return base64.b32encode(secrets.token_bytes(20)).decode("ascii").rstrip("=")


def _base32_decode(value: str) -> bytes:
    cleaned = re.sub(r"\s", "", value).upper().rstrip("=")
    return base64.b32decode(cleaned + "=" * (-len(cleaned) % 8))


def _hotp(key: bytes, counter: int) -> str:
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return str(binary % 10**DIGITS).zfill(DIGITS)


def current_totp(secret: str, at: float | None = None) -> str:
    moment = _clock() if at is None else at
    return _hotp(_base32_decode(secret), int(moment // PERIOD_SECONDS))


def match_totp_step(secret: str, code: str, at: float | None = None) -> int | None:
    normalised = re.sub(r"\s", "", code or "")
    if len(normalised) != DIGITS or not normalised.isdigit():
        return None
    try:
        key = _base32_decode(secret)
    except (ValueError, TypeError):
        return None
    moment = _clock() if at is None else at
    counter = int(moment // PERIOD_SECONDS)
    matched: int | None = None
    for step in range(counter - WINDOW_STEPS, counter + WINDOW_STEPS + 1):
        if hmac.compare_digest(_hotp(key, step), normalised):
            matched = step
    return matched


def totp_uri(secret: str, account_name: str, issuer: str = TOTP_ISSUER) -> str:
    label = quote(f"{issuer}:{account_name}", safe="")
    params = urlencode(
        {
            "secret": secret,
            "issuer": issuer,
            "algorithm": "SHA1",
            "digits": DIGITS,
            "period": PERIOD_SECONDS,
        },
        quote_via=quote,
    )
    return f"otpauth://totp/{label}?{params}"


QR_QUIET_ZONE_MODULES = 4


def qr_matrix(text: str) -> list[list[bool]]:
    import segno

    code = segno.make(text, error="m", micro=False, boost_error=False)
    border = QR_QUIET_ZONE_MODULES
    rows = [[bool(cell) for cell in row] for row in code.matrix]
    width = len(rows[0]) + 2 * border
    framed = [[False] * width for _ in range(border)]
    framed += [[False] * border + row + [False] * border for row in rows]
    framed += [[False] * width for _ in range(border)]
    return framed


def qr_path(matrix: list[list[bool]]) -> str:
    commands: list[str] = []
    for y, row in enumerate(matrix):
        x = 0
        while x < len(row):
            if not row[x]:
                x += 1
                continue
            start = x
            while x < len(row) and row[x]:
                x += 1
            run = x - start
            commands.append(f"M{start} {y}h{run}v1h-{run}z")
    return "".join(commands)


def qr_svg(text: str) -> str:
    matrix = qr_matrix(text)
    size = len(matrix)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" '
        f'shape-rendering="crispEdges">'
        f'<rect width="{size}" height="{size}" fill="#ffffff"/>'
        f'<path fill="#000000" d="{qr_path(matrix)}"/>'
        "</svg>"
    )


def looks_like_totp(code: str) -> bool:
    return code_kind(code) == "totp"



@lru_cache(maxsize=4)
def _encryption_key(jwt_secret: str) -> bytes:
    return hashlib.scrypt(
        jwt_secret.encode("utf-8"), salt=_ENCRYPTION_LABEL, n=16384, r=8, p=1, dklen=32
    )


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _unb64(data: str) -> bytes:
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


@lru_cache(maxsize=8)
def _keyring(current: str | None, previous: str | None) -> tuple[tuple[str, bytes] | None, dict[str, bytes]]:
    return parse_keyring(current, previous)


def _configured_keys() -> tuple[tuple[str, bytes] | None, dict[str, bytes]]:
    settings = get_settings()
    return _keyring(
        getattr(settings, "two_factor_encryption_key", None),
        getattr(settings, "two_factor_encryption_previous_keys", None),
    )


@lru_cache(maxsize=1)
def warn_legacy_key() -> None:
    logger.warning(
        "TWO_FACTOR_ENCRYPTION_KEY is not set: two-step secrets are encrypted with a key derived from "
        "JWT_SECRET, so rotating JWT_SECRET would lock every enrolled person out of their "
        "authenticator. Set TWO_FACTOR_ENCRYPTION_KEY (docs/ENVIRONMENT.md)."
    )


def _aad(key_id: str) -> bytes:
    return b"field-repository-totp-v2:" + key_id.encode("ascii")


def encrypt_secret(plain: str) -> str:
    iv = secrets.token_bytes(12)
    current, _ring = _configured_keys()
    if current is None:
        warn_legacy_key()
        sealed = AESGCM(_encryption_key(get_settings().jwt_secret)).encrypt(iv, plain.encode("utf-8"), None)
        ciphertext, tag = sealed[:-16], sealed[-16:]
        return ".".join(["v1", _b64(iv), _b64(tag), _b64(ciphertext)])
    key_id, key = current
    sealed = AESGCM(key).encrypt(iv, plain.encode("utf-8"), _aad(key_id))
    ciphertext, tag = sealed[:-16], sealed[-16:]
    return ".".join(["v2", key_id, _b64(iv), _b64(tag), _b64(ciphertext)])


def decrypt_secret(stored: str | None) -> str | None:
    if not stored:
        return None
    parts = stored.split(".")
    try:
        if len(parts) == 4 and parts[0] == "v1":
            iv, tag, ciphertext = (_unb64(part) for part in parts[1:])
            plain = AESGCM(_encryption_key(get_settings().jwt_secret)).decrypt(iv, ciphertext + tag, None)
            return plain.decode("utf-8")
        if len(parts) == 5 and parts[0] == "v2":
            key = _configured_keys()[1].get(parts[1])
            if key is None:
                logger.warning("two-factor: a secret names key id %r, which is not configured", parts[1])
                return None
            iv, tag, ciphertext = (_unb64(part) for part in parts[2:])
            return AESGCM(key).decrypt(iv, ciphertext + tag, _aad(parts[1])).decode("utf-8")
    except (InvalidTag, ValueError):
        return None
    return None


def needs_reencryption(stored: str | None) -> bool:
    if not stored:
        return False
    current, _ring = _configured_keys()
    if current is None:
        return False
    parts = stored.split(".")
    return not (len(parts) == 5 and parts[0] == "v2" and parts[1] == current[0])


async def reencrypt_if_stale(user_id: str, column: str, stored: str | None, plain: str | None = None) -> bool:
    if not needs_reencryption(stored):
        return False
    secret = plain if plain is not None else decrypt_secret(stored)
    if not secret:
        return False
    try:
        moved = await db.user.update_many(
            where={"id": user_id, column: stored},
            data={column: encrypt_secret(secret)},
        )
    except Exception:
        logger.exception("two-factor: could not re-encrypt %s for user %s", column, user_id)
        return False
    if moved:
        invalidate_cached_user(user_id)
        logger.info("two-factor: %s re-encrypted with the current key for user %s", column, user_id)
    return bool(moved)



def generate_recovery_codes(count: int = RECOVERY_CODE_COUNT) -> list[str]:
    def one() -> str:
        raw = "".join(secrets.choice(RECOVERY_ALPHABET) for _ in range(8))
        return f"{raw[:4]}-{raw[4:]}"

    return [one() for _ in range(count)]


def canonical_recovery_code(value: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]", "", (value or "").lower())
    return f"{cleaned[:4]}-{cleaned[4:]}" if len(cleaned) == 8 else cleaned


def looks_like_recovery_code(code: str) -> bool:
    return code_kind(code) == "recovery"


def code_kind(text: str) -> str | None:
    compact = re.sub(r"[\s-]", "", text or "")
    if len(compact) == DIGITS and compact.isdigit():
        return "totp"
    if len(compact) == 8 and compact.isalnum() and compact.isascii():
        return "recovery"
    return None


async def hash_recovery_codes(codes: list[str]) -> list[str]:
    return await asyncio.to_thread(lambda: [hash_password(canonical_recovery_code(c)) for c in codes])


def _matching_hash(code: str, hashes: list[str]) -> str | None:
    canonical = canonical_recovery_code(code)
    found: str | None = None
    for stored in hashes:
        try:
            if verify_password(canonical, stored) and found is None:
                found = stored
        except ValueError:
            continue
    return found



def _user_field(user: Any, name: str) -> Any:
    return user.get(name) if isinstance(user, dict) else getattr(user, name, None)


def _user_id(user: Any) -> str:
    raw = user.get("id") if isinstance(user, dict) else getattr(user, "id", None)
    return str(raw) if raw is not None else "-"


async def consume_totp(user: Any, code: str, at: float | None = None) -> bool:
    secret = decrypt_secret(_user_field(user, "twoFactorSecret"))
    if not secret:
        return False
    step = match_totp_step(secret, code, at)
    if step is None:
        return False
    won = await db.user.update_many(
        where={
            "id": _user_field(user, "id"),
            "twoFactorEnabled": True,
            "OR": [{"twoFactorLastStep": None}, {"twoFactorLastStep": {"lt": step}}],
        },
        data={"twoFactorLastStep": step},
    )
    if not won:
        logger.info("two-factor: replayed code refused for user %s", _user_id(user))
    invalidate_cached_user(_user_field(user, "id"))
    if won:
        await reencrypt_if_stale(_user_field(user, "id"), "twoFactorSecret", _user_field(user, "twoFactorSecret"), secret)
    return bool(won)


async def consume_recovery_code(user: Any, code: str) -> bool:
    user_id = _user_field(user, "id")
    hashes = list(_user_field(user, "twoFactorRecoveryCodes") or [])
    for _ in range(3):
        if not hashes:
            return False
        matched = await asyncio.to_thread(_matching_hash, code, hashes)
        if matched is None:
            return False
        remaining = [h for h in hashes if h != matched]
        won = await db.user.update_many(
            where={
                "id": user_id,
                "twoFactorEnabled": True,
                "twoFactorRecoveryCodes": {"equals": hashes},
            },
            data={"twoFactorRecoveryCodes": remaining},
        )
        if won:
            invalidate_cached_user(user_id)
            logger.warning(
                "AUDIT two-factor recovery code used: user=%s remaining=%d", user_id, len(remaining)
            )
            return True
        fresh = await db.user.find_unique(where={"id": user_id})
        hashes = list(_user_field(fresh, "twoFactorRecoveryCodes") or []) if fresh else []
    return False


async def consume_any_code(user: Any, code: str) -> bool:
    kind = code_kind(code)
    if kind == "totp":
        return await consume_totp(user, code)
    if kind == "recovery":
        return await consume_recovery_code(user, code)
    return False



@dataclass(frozen=True)
class AttemptBudget:
    name: str
    limit: int
    attempts: str
    window_started: str
    locked_until: str
    lockouts: str


CODE_BUDGET = AttemptBudget(
    name="code",
    limit=FAILURE_BUDGET,
    attempts="twoFactorFailedAttempts",
    window_started="twoFactorFailureWindowStartedAt",
    locked_until="twoFactorLockedUntil",
    lockouts="twoFactorLockouts",
)
RECOVERY_BUDGET = AttemptBudget(
    name="recovery",
    limit=RECOVERY_FAILURE_BUDGET,
    attempts="twoFactorRecoveryFailedAttempts",
    window_started="twoFactorRecoveryWindowStartedAt",
    locked_until="twoFactorRecoveryLockedUntil",
    lockouts="twoFactorRecoveryLockouts",
)
BUDGETS = (CODE_BUDGET, RECOVERY_BUDGET)


class SecondFactorLocked(Exception):
    pass


def lockout_duration(previous_lockouts: int) -> timedelta:
    return min(FIRST_LOCKOUT * (2 ** min(previous_lockouts, 16)), MAX_LOCKOUT)


def _aware(value: datetime | None) -> datetime | None:
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value


def is_locked(user: Any, budget: AttemptBudget = CODE_BUDGET, now: datetime | None = None) -> bool:
    until = _aware(_user_field(user, budget.locked_until))
    return until is not None and until > (now or datetime.now(UTC))


def fully_locked(user: Any, now: datetime | None = None) -> bool:
    return all(is_locked(user, budget, now) for budget in BUDGETS)


CLEAR_FAILURES: dict[str, Any] = {
    column: value
    for budget in BUDGETS
    for column, value in (
        (budget.attempts, 0),
        (budget.window_started, None),
        (budget.locked_until, None),
        (budget.lockouts, 0),
    )
}


def budget_for(code: str, *, allow_recovery: bool = True) -> AttemptBudget:
    return RECOVERY_BUDGET if allow_recovery and code_kind(code) == "recovery" else CODE_BUDGET


async def _start_window_if_lapsed(row: Any, budget: AttemptBudget, now: datetime) -> None:
    started = _aware(_user_field(row, budget.window_started))
    if started is not None and now - started <= FAILURE_WINDOW:
        return
    last_lock_end = _aware(_user_field(row, budget.locked_until))
    data: dict[str, Any] = {budget.attempts: 0, budget.window_started: now}
    if last_lock_end is not None and now - last_lock_end > LOCKOUT_DECAY:
        data[budget.lockouts] = 0
    await db.user.update_many(
        where={
            "id": _user_field(row, "id"),
            budget.window_started: _user_field(row, budget.window_started),
            budget.locked_until: _user_field(row, budget.locked_until),
        },
        data=data,
    )


async def claim_attempt_slot(user_id: str, budget: AttemptBudget = CODE_BUDGET) -> int:
    now = datetime.now(UTC)
    row = await db.user.find_unique(where={"id": user_id})
    if row is None or is_locked(row, budget, now):
        raise SecondFactorLocked()
    await _start_window_if_lapsed(row, budget, now)
    claimed = await db.user.update_many(
        where={
            "id": user_id,
            budget.attempts: {"lt": budget.limit},
            "OR": [{budget.locked_until: None}, {budget.locked_until: {"lt": now}}],
        },
        data={budget.attempts: {"increment": 1}},
    )
    if not claimed:
        raise SecondFactorLocked()
    invalidate_cached_user(user_id)
    after = await db.user.find_unique(where={"id": user_id})
    return int(_user_field(after, budget.attempts) or 0)


async def settle_success(user_id: str, claimed_count: int, budget: AttemptBudget = CODE_BUDGET) -> None:
    cleared = await db.user.update_many(
        where={"id": user_id, budget.attempts: claimed_count},
        data={budget.attempts: 0, budget.window_started: None, budget.lockouts: 0},
    )
    if not cleared:
        await db.user.update_many(
            where={"id": user_id, budget.attempts: {"gt": 0}},
            data={budget.attempts: {"decrement": 1}},
        )
    invalidate_cached_user(user_id)


async def lock_if_spent(user_id: str, budget: AttemptBudget = CODE_BUDGET) -> bool:
    row = await db.user.find_unique(where={"id": user_id})
    if row is None or (_user_field(row, budget.attempts) or 0) < budget.limit:
        return False
    window = _user_field(row, budget.window_started)
    if window is None:
        return False
    now = datetime.now(UTC)
    lockouts = int(_user_field(row, budget.lockouts) or 0)
    until = now + lockout_duration(lockouts)
    won = await db.user.update_many(
        where={
            "id": user_id,
            budget.attempts: {"gte": budget.limit},
            budget.window_started: window,
        },
        data={
            budget.locked_until: until,
            budget.window_started: None,
            budget.lockouts: {"increment": 1},
        },
    )
    if not won:
        return False
    invalidate_cached_user(user_id)
    logger.warning(
        "AUDIT two-factor locked after %d wrong %s codes: user=%s (%s) lockout=%d until=%s",
        budget.limit,
        budget.name,
        user_id,
        _user_field(row, "email"),
        lockouts + 1,
        until.isoformat(),
    )
    await notify_second_factor_locked(row, until, budget)
    return True


async def notify_second_factor_locked(user: Any, until: datetime, budget: AttemptBudget = CODE_BUDGET) -> None:
    from app.services.notifications import notify

    try:
        params = {
            "recipientName": _user_field(user, "name"),
            "lockedUntil": until.isoformat(),
            "codeKind": budget.name,
        }
        await notify(
            user,
            mailer.SECOND_FACTOR_LOCKED,
            mailer.subject_for(mailer.SECOND_FACTOR_LOCKED, params),
            None,
            "/settings",
            email_kind=mailer.SECOND_FACTOR_LOCKED,
            email_params=params,
        )
    except Exception:
        logger.exception("mail: could not queue a second-factor lock notice for %s", _user_id(user))


FIRST_PASSWORD_CODE_ATTEMPTS = 5
FIRST_PASSWORD_CODE_WINDOW = timedelta(minutes=15)


async def claim_first_password_check(user_id: str) -> bool:
    now = datetime.now(UTC)
    row = await db.user.find_unique(where={"id": user_id})
    if row is None:
        return False
    read_started = _user_field(row, "firstPasswordCodeWindowStartedAt")
    started = _aware(read_started)
    if started is None or now - started > FIRST_PASSWORD_CODE_WINDOW:
        await db.user.update_many(
            where={"id": user_id, "firstPasswordCodeWindowStartedAt": read_started},
            data={"firstPasswordCodeAttempts": 0, "firstPasswordCodeWindowStartedAt": now},
        )
    claimed = await db.user.update_many(
        where={"id": user_id, "firstPasswordCodeAttempts": {"lt": FIRST_PASSWORD_CODE_ATTEMPTS}},
        data={"firstPasswordCodeAttempts": {"increment": 1}},
    )
    invalidate_cached_user(user_id)
    return bool(claimed)


async def refund_first_password_check(user_id: str) -> None:
    await db.user.update_many(
        where={"id": user_id, "firstPasswordCodeAttempts": {"gt": 0}},
        data={"firstPasswordCodeAttempts": {"decrement": 1}},
    )
    invalidate_cached_user(user_id)


async def clear_failures(user_id: str) -> None:
    await db.user.update(where={"id": user_id}, data=dict(CLEAR_FAILURES))
    invalidate_cached_user(user_id)


async def check_second_factor(user: Any, code: str, *, allow_recovery: bool = True) -> bool:
    user_id = _user_field(user, "id")
    budget = budget_for(code, allow_recovery=allow_recovery)
    claimed = await claim_attempt_slot(user_id, budget)
    if budget is RECOVERY_BUDGET:
        accepted = await consume_recovery_code(user, code)
    else:
        accepted = code_kind(code) == "totp" and await consume_totp(user, code)
    if accepted:
        await settle_success(user_id, claimed, budget)
        return True
    await lock_if_spent(user_id, budget)
    return False



class ChallengeRefused(Exception):
    pass


async def issue_challenge(user: Any) -> str:
    settings = get_settings()
    now = datetime.now(UTC)
    expires = now + timedelta(minutes=CHALLENGE_TTL_MINUTES)
    jti = uuid.uuid4().hex
    await db.twofactorchallenge.delete_many(where={"expiresAt": {"lt": now}})
    await db.twofactorchallenge.create(
        data={"id": jti, "userId": _user_field(user, "id"), "expiresAt": expires}
    )
    payload = {
        "sub": _user_field(user, "id"),
        "purpose": CHALLENGE_PURPOSE,
        "scope": CHALLENGE_SCOPE,
        "jti": jti,
        "iat": int(now.timestamp()),
        "exp": expires,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_challenge(token: str) -> tuple[str, str]:
    settings = get_settings()
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            options={"require": ["exp", "sub", "jti"]},
        )
    except jwt.PyJWTError as exc:
        raise ChallengeRefused("invalid") from exc
    if payload.get("purpose") != CHALLENGE_PURPOSE or payload.get("scope") != CHALLENGE_SCOPE:
        raise ChallengeRefused("purpose")
    return str(payload["sub"]), str(payload["jti"])


async def claim_attempt(jti: str) -> None:
    claimed = await db.twofactorchallenge.update_many(
        where={
            "id": jti,
            "attempts": {"lt": MAX_CHALLENGE_ATTEMPTS},
            "expiresAt": {"gt": datetime.now(UTC)},
        },
        data={"attempts": {"increment": 1}},
    )
    if not claimed:
        raise ChallengeRefused("exhausted")


async def finish_challenge(jti: str) -> None:
    await db.twofactorchallenge.delete_many(where={"id": jti})



PENDING_TTL = timedelta(minutes=15)

CLEAR_PENDING: dict[str, Any] = {"twoFactorPendingSecret": None, "twoFactorPendingAt": None}


def pending_now() -> datetime:
    return datetime.fromtimestamp(_clock(), UTC)


def pending_is_live(user: Any, now: datetime | None = None) -> bool:
    if _user_field(user, "twoFactorEnabled"):
        return False
    if not _user_field(user, "twoFactorPendingSecret"):
        return False
    issued = _aware(_user_field(user, "twoFactorPendingAt"))
    if issued is None:
        return False
    return (now or pending_now()) - issued < PENDING_TTL


def has_stale_pending(user: Any, now: datetime | None = None) -> bool:
    return bool(_user_field(user, "twoFactorPendingSecret")) and not pending_is_live(user, now)


def status_of(user: Any, now: datetime | None = None) -> dict[str, Any]:
    enabled_at = _user_field(user, "twoFactorEnabledAt")
    enabled = bool(_user_field(user, "twoFactorEnabled"))
    return {
        "enabled": enabled,
        "pending": pending_is_live(user, now),
        "recovery_codes_remaining": len(_user_field(user, "twoFactorRecoveryCodes") or []) if enabled else 0,
        "enabled_at": enabled_at.isoformat() if isinstance(enabled_at, datetime) else enabled_at,
    }


SECRET_USER_FIELDS = _SECRET_USER_FIELDS
