"""Two-step verification: TOTP (RFC 6238), recovery codes, and the sign-in challenge.

Ported from cxa-cms (``lib/auth/totp.ts``, ``lib/auth/totp-qr.ts``) and shared, name for name, with
the designer portal. The arithmetic is the standard library; the security is in HOW it is used:

* **The shared secret is encrypted at rest** (AES-256-GCM) with ``TWO_FACTOR_ENCRYPTION_KEY``, a
  key of its own with a key id written into every ciphertext (``v2.<key id>....``) and previous keys
  still accepted (``app/core/two_factor_keys.py``). A plaintext TOTP secret in the database means
  one database read is a complete account takeover, the one thing a second factor exists to stop.
  Unset, the legacy key derived from ``JWT_SECRET`` (scrypt and a fixed label, as cxa-cms derives
  it) is used with a WARNING: rotating ``JWT_SECRET`` would then turn every enrolled secret into
  ciphertext nobody can open. Legacy ``v1`` secrets are always still read, and a secret on any key
  but the current one is re-encrypted the first time it is used (:func:`reencrypt_if_stale`) or by
  ``scripts/reencrypt_two_factor_secrets.py``.
* **A code is spent when it is accepted** (RFC 6238 §5.2), which is where this improves on cxa-cms.
  :func:`match_totp_step` returns the time step that matched, and :func:`consume_totp` records it on
  the account with a CONDITIONAL update: a step at or below ``twoFactorLastStep`` is refused, and two
  concurrent submissions of one code cannot both win because only one ``update_many`` matches.
* **±1 step (±30 s)**: zero tolerance rejects a correct code from a phone whose clock is two seconds
  out; a wider window turns a 30-second code into a several-minute one.
* **Constant-time comparison**, and every candidate in the window is computed even after a match, so
  the timing says nothing about which way the clock is skewed.
* **Recovery codes are passwords that bypass the second factor**, so they are stored as bcrypt hashes
  at the password cost, shown once, and consumed with a compare-and-swap on the whole list.

THE CHALLENGE. After a first factor succeeds on an account with this switched on, sign-in answers
``{"two_factor_required": true, "challenge_token": ...}`` instead of a session. The token is a JWT
with ``purpose = "2fa"`` and a ``scope`` claim no route admits (``deps._user_from_bearer`` refuses
any scope it was not told about), so it can never stand in for a session. It lives five minutes, and
its ``jti`` names a ``TwoFactorChallenge`` row that counts attempts: five codes, then it is dead.
"""

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
import segno
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import invalidate_cached_user
from app.core.security import hash_password, verify_password
from app.core.two_factor_keys import parse_keyring
from app.core.user_fields import SECRET_USER_FIELDS as _SECRET_USER_FIELDS
from app.services import email_outbox, mailer

logger = logging.getLogger(__name__)

DIGITS = 6
PERIOD_SECONDS = 30
WINDOW_STEPS = 1

#: What an authenticator app lists the account under.
TOTP_ISSUER = "Field Repository"

RECOVERY_CODE_COUNT = 10
#: Lower-case letters and digits with the look-alikes removed (0/o, 1/l/i), so a code copied off
#: paper by someone who is already locked out reads one way only.
RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

CHALLENGE_PURPOSE = "2fa"
#: The ``scope`` the challenge token carries. No dependency admits it, which is what keeps a
#: challenge token from ever being accepted as a session.
CHALLENGE_SCOPE = "two-factor-challenge"
CHALLENGE_TTL_MINUTES = 5
MAX_CHALLENGE_ATTEMPTS = 5

#: The one sentence every failed code reads, whatever the reason: wrong digits, a replayed code, a
#: used recovery code, an expired or exhausted challenge. The reasons are logged, not told.
WRONG_CODE_DETAIL = "That code didn't work. Check your authenticator app and try again."
CHALLENGE_DEAD_DETAIL = "This sign-in has timed out. Sign in again."
TOO_MANY_ATTEMPTS_DETAIL = "Too many attempts. Try again later."

#: THE PER-ACCOUNT FAILURE BUDGET. The five-code cap on one challenge is not a cap on the account:
#: signing in again mints a fresh challenge. So every wrong second-factor code — at sign-in, turning
#: it off, making new recovery codes, minting a dataset token — is also counted against the ACCOUNT,
#: and ten inside a rolling fifteen minutes lock the second factor: 15 minutes, doubling on each
#: lockout, capped at ONE hour — somebody who only holds the password must not be able to keep the
#: owner out for a day at a time. The lockout count decays to zero once a day has passed since the
#: last lock ended. A correct code resets the budget; an administrator's reset clears everything.
FAILURE_BUDGET = 10
#: Recovery codes have their OWN budget, smaller, and its lock is independent of the six-digit one.
RECOVERY_FAILURE_BUDGET = 5
FAILURE_WINDOW = timedelta(minutes=15)
FIRST_LOCKOUT = timedelta(minutes=15)
MAX_LOCKOUT = timedelta(hours=1)
LOCKOUT_DECAY = timedelta(hours=24)

_ENCRYPTION_LABEL = b"field-repository-totp-v1"


# ── TOTP ────────────────────────────────────────────────────────────────────────────────────────


def _clock() -> float:
    """Seconds since the epoch. One seam, so a test can move time without touching ``time.time``."""
    return time.time()


def generate_totp_secret() -> str:
    """20 random bytes (160 bits, RFC 4226's recommendation) as 32 unpadded base32 characters."""
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
    """The code for ``at`` (default now). For tests and nothing else."""
    moment = _clock() if at is None else at
    return _hotp(_base32_decode(secret), int(moment // PERIOD_SECONDS))


def match_totp_step(secret: str, code: str, at: float | None = None) -> int | None:
    """The time step ``code`` is valid for within ±1 step of ``at``, or None.

    The step rather than a boolean, because the step is what makes a code single-use: see
    :func:`consume_totp`. The last match wins if a secret ever produced one code for two steps,
    which is the safe direction — recording the higher step refuses more later, never less.
    """
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
    """The ``otpauth://`` URI an authenticator app scans."""
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


#: ISO/IEC 18004 asks for four modules of quiet zone. The square may sit on a dark theme, where a
#: thinner one is eaten by the surround, so it carries the full four on its own white background.
QR_QUIET_ZONE_MODULES = 4


def qr_matrix(text: str) -> list[list[bool]]:
    """The module matrix INCLUDING the quiet zone; True is dark. Error correction M: a screen
    photographed at an angle with a glare stripe is the ordinary case, and H would push a
    ~150-character URI to a version too dense to focus on."""
    code = segno.make(text, error="m", micro=False, boost_error=False)
    border = QR_QUIET_ZONE_MODULES
    rows = [[bool(cell) for cell in row] for row in code.matrix]
    width = len(rows[0]) + 2 * border
    framed = [[False] * width for _ in range(border)]
    framed += [[False] * border + row + [False] * border for row in rows]
    framed += [[False] * width for _ in range(border)]
    return framed


def qr_path(matrix: list[list[bool]]) -> str:
    """One SVG path: each horizontal run of dark modules is one ``M x y h n v 1 h -n z`` rectangle.

    Runs are merged so the path stays a few kilobytes and leaves no anti-aliasing seam between
    neighbouring modules. ONLY ``M``, ``h``, ``v`` and ``z``, which is what lets the Android client
    draw this exact string with Compose's ``PathParser`` instead of shipping an SVG renderer.
    """
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
    """A self-contained SVG: a white ``size``×``size`` square and one black path on it.

    The shape is fixed and both clients rely on it: one ``viewBox="0 0 N N"`` and one ``<path d=…>``.
    The web shows it as an image; Android reads the two attributes and draws the path itself.
    """
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
    """Six digits, spaces allowed (authenticators show ``123 456``)."""
    return code_kind(code) == "totp"


# ── Encryption at rest ──────────────────────────────────────────────────────────────────────────


@lru_cache(maxsize=4)
def _encryption_key(jwt_secret: str) -> bytes:
    # scrypt rather than a fast hash: an attacker holding the database could otherwise brute-force a
    # weak JWT_SECRET at hashing speed. The parameters are Node's scryptSync defaults, as cxa-cms.
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
    """``(current (key id, key) or None, every configured key by id)`` — ``TWO_FACTOR_ENCRYPTION_KEY``
    and ``TWO_FACTOR_ENCRYPTION_PREVIOUS_KEYS``. See ``app/core/two_factor_keys.py``."""
    settings = get_settings()
    return _keyring(
        getattr(settings, "two_factor_encryption_key", None),
        getattr(settings, "two_factor_encryption_previous_keys", None),
    )


@lru_cache(maxsize=1)
def warn_legacy_key() -> None:
    """Said once per process: the legacy key is in use, and what that costs."""
    logger.warning(
        "TWO_FACTOR_ENCRYPTION_KEY is not set: two-step secrets are encrypted with a key derived from "
        "JWT_SECRET, so rotating JWT_SECRET would lock every enrolled person out of their "
        "authenticator. Set TWO_FACTOR_ENCRYPTION_KEY (docs/ENVIRONMENT.md)."
    )


def _aad(key_id: str) -> bytes:
    # The key id is authenticated with the ciphertext, so relabelling a secret to another key fails.
    return b"field-repository-totp-v2:" + key_id.encode("ascii")


def encrypt_secret(plain: str) -> str:
    """``v2.<key id>.<iv>.<tag>.<ciphertext>`` with the current ``TWO_FACTOR_ENCRYPTION_KEY``; without
    one, the legacy ``v1.<iv>.<tag>.<ciphertext>`` (cxa-cms's format) with the JWT-derived key."""
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
    """None rather than an exception: an unreadable secret means this person cannot use their
    authenticator, and the way out is a recovery code or an administrator, not a 500.

    ``v2`` opens with the key its id names (current or previous); ``v1`` with the legacy key derived
    from ``JWT_SECRET``, which is always tried, so setting a key never strands an older secret."""
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
    """Is this ciphertext sealed with anything but the current key? False when no key is set (there
    is nothing better to move it to) and for an empty column."""
    if not stored:
        return False
    current, _ring = _configured_keys()
    if current is None:
        return False
    parts = stored.split(".")
    return not (len(parts) == 5 and parts[0] == "v2" and parts[1] == current[0])


async def reencrypt_if_stale(user_id: str, column: str, stored: str | None, plain: str | None = None) -> bool:
    """Move one secret to the current key, IF it is still the ciphertext this request read: one
    conditional write, so a concurrent switch-off or re-enrolment is never overwritten. True when
    this call wrote. Never raises — re-encryption is housekeeping, and the code was already right."""
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
    except Exception:  # see the docstring
        logger.exception("two-factor: could not re-encrypt %s for user %s", column, user_id)
        return False
    if moved:
        invalidate_cached_user(user_id)
        logger.info("two-factor: %s re-encrypted with the current key for user %s", column, user_id)
    return bool(moved)


# ── Recovery codes ──────────────────────────────────────────────────────────────────────────────


def generate_recovery_codes(count: int = RECOVERY_CODE_COUNT) -> list[str]:
    def one() -> str:
        raw = "".join(secrets.choice(RECOVERY_ALPHABET) for _ in range(8))
        return f"{raw[:4]}-{raw[4:]}"

    return [one() for _ in range(count)]


def canonical_recovery_code(value: str) -> str:
    """Lower case, punctuation and spaces stripped, re-hyphenated when it is eight characters.

    THE ONLY COPY. It is applied when a code is hashed and when one is checked, and the two must
    agree character for character or every recovery code silently fails, at exactly the moment
    somebody has lost their phone. Anything not eight characters long is returned stripped but
    unhyphenated, so a malformed entry compares as itself.
    """
    cleaned = re.sub(r"[^a-z0-9]", "", (value or "").lower())
    return f"{cleaned[:4]}-{cleaned[4:]}" if len(cleaned) == 8 else cleaned


def looks_like_recovery_code(code: str) -> bool:
    return code_kind(code) == "recovery"


def code_kind(text: str) -> str | None:
    """Which kind of code this is, by SHAPE: six digits is an authenticator code, eight letters and
    digits (hyphen and spaces optional) a recovery code, anything else neither. The clients run the
    same rule (``codeKind`` on the web, ``TwoFactorCode.kindOf`` on Android)."""
    compact = re.sub(r"[\s-]", "", text or "")
    if len(compact) == DIGITS and compact.isdigit():
        return "totp"
    if len(compact) == 8 and compact.isalnum() and compact.isascii():
        return "recovery"
    return None


async def hash_recovery_codes(codes: list[str]) -> list[str]:
    """bcrypt at the password cost, off the event loop: ten hashes take a couple of seconds."""
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


# ── Using a code against an account ─────────────────────────────────────────────────────────────


def _user_field(user: Any, name: str) -> Any:
    return user.get(name) if isinstance(user, dict) else getattr(user, name, None)


async def consume_totp(user: Any, code: str, at: float | None = None) -> bool:
    """Accept ``code`` for the active secret ONCE. True only if this call recorded its step."""
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
        logger.info("two-factor: replayed code refused for user %s", _user_field(user, "id"))
    # Every write to a User row drops the cached identity (deps.py's contract), this one included.
    invalidate_cached_user(_user_field(user, "id"))
    if won:
        # RE-ENCRYPT ON USE: a secret still under the legacy or a previous key moves to the current
        # one the first time it proves itself, so a key rotation finishes on its own as people sign in.
        await reencrypt_if_stale(_user_field(user, "id"), "twoFactorSecret", _user_field(user, "twoFactorSecret"), secret)
    return bool(won)


async def consume_recovery_code(user: Any, code: str) -> bool:
    """Spend one recovery code. Compare-and-swap on the whole list, so two codes used at once cannot
    lose each other's removal, and one code used twice at once is accepted once."""
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
    """A six-digit code or a recovery code, told apart by shape."""
    kind = code_kind(code)
    if kind == "totp":
        return await consume_totp(user, code)
    if kind == "recovery":
        return await consume_recovery_code(user, code)
    return False


# ── The per-account failure budgets ─────────────────────────────────────────────────────────────
#
# TWO INDEPENDENT BUDGETS, ONE MECHANISM. Six-digit codes spend `CODE_BUDGET` (ten per window);
# recovery codes spend `RECOVERY_BUDGET` (five per window). A lock on one never blocks the other, so
# somebody who holds only the password and burns the six-digit budget cannot shut the owner out: the
# owner's recovery codes still work. Each budget is four columns, named in its `AttemptBudget`.
#
# EVERY STEP IS ONE CONDITIONAL WRITE, because every read here is stale by the time it is acted on: a
# burst of concurrent wrong codes must not each see "not locked" and each get a check. So a code is
# checked only after it has CLAIMED a slot with an atomic, conditional increment; the lock is written
# by exactly one request (a compare-and-swap on the window it ends); and a success clears the counter
# only if nobody else has claimed a slot since it read it.
#
# THE COLUMNS, per budget. `attempts` counts slots claimed in the live window, which started at
# `window_started` (None = no live window). `locked_until` is when the latest lock ends — kept after
# it lapses, as the record of when it ended, which is what the decay reads. `lockouts` is how many
# locks in a row, which sets the next one's length.


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
    """No slot left in this budget: it is locked, or spent and the lock is being set."""


def lockout_duration(previous_lockouts: int) -> timedelta:
    """15 minutes, doubling with each earlier lockout, never more than an hour."""
    return min(FIRST_LOCKOUT * (2 ** min(previous_lockouts, 16)), MAX_LOCKOUT)


def _aware(value: datetime | None) -> datetime | None:
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value


def is_locked(user: Any, budget: AttemptBudget = CODE_BUDGET, now: datetime | None = None) -> bool:
    until = _aware(_user_field(user, budget.locked_until))
    return until is not None and until > (now or datetime.now(UTC))


def fully_locked(user: Any, now: datetime | None = None) -> bool:
    """Every way in is locked: no code of any kind would be checked. What sign-in refuses on."""
    return all(is_locked(user, budget, now) for budget in BUDGETS)


#: What a switch-off or an administrator's reset puts back, for both budgets.
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
    """Which budget a submitted code spends: a recovery code's own, and everything else (six digits,
    or something that is neither shape) the six-digit one."""
    return RECOVERY_BUDGET if allow_recovery and code_kind(code) == "recovery" else CODE_BUDGET


async def _start_window_if_lapsed(row: Any, budget: AttemptBudget, now: datetime) -> None:
    """Begin a fresh window when there is none live (never started, older than fifteen minutes, or
    ended by a lock that has since lapsed). Conditional on the window and the lock being what this
    request read, so only one of a burst restarts it. The lockout count DECAYS to zero here when the
    last lock ended more than a day ago: somebody who merely knows the password cannot ratchet the
    owner's lock up for ever."""
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
    """Claim one of the budget's slots BEFORE any code is checked; the count after claiming.

    One `update_many` whose condition IS the rule — not locked, and fewer than the limit used — with
    an atomic increment. Zero rows means there is no slot: SecondFactorLocked.
    """
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
    """A correct code clears its budget — but only if nobody has claimed a slot since this request
    did. If somebody has, only this request's own slot is handed back, so concurrent wrong codes stay
    counted. A success also ends that budget's lockout run."""
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
    """After a wrong code: when the budget is spent, lock — written by exactly ONE request, by a
    compare-and-swap on the window the lock ends, and the lockout count escalated by an atomic
    increment. The owner is told by e-mail. True when this request wrote the lock."""
    row = await db.user.find_unique(where={"id": user_id})
    if row is None or (_user_field(row, budget.attempts) or 0) < budget.limit:
        return False
    window = _user_field(row, budget.window_started)
    if window is None:
        return False  # another request has already written this window's lock
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
    # The audit trail every other two-step change writes to (enable, disable, reset): WARNING lines
    # that reach journald on the box, where an administrator looking into a lock-out finds them.
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
    """Tell the owner. Only somebody holding the password reaches a second-factor lock, so this is the
    owner's one signal that the password is known. Through the existing outbox (a no-op when mail is
    off); never raises — the lock is already written and must stand whatever the mail does."""
    try:
        await email_outbox.enqueue(
            mailer.SECOND_FACTOR_LOCKED,
            to_address=_user_field(user, "email") or "",
            recipient_id=_user_field(user, "id"),
            params={
                "recipientName": _user_field(user, "name"),
                "lockedUntil": until.isoformat(),
                "codeKind": budget.name,
            },
        )
    except Exception:  # see the docstring
        logger.exception("mail: could not queue a second-factor lock notice for %s", _user_field(user, "id"))


async def clear_failures(user_id: str) -> None:
    await db.user.update(where={"id": user_id}, data=dict(CLEAR_FAILURES))
    invalidate_cached_user(user_id)


async def check_second_factor(user: Any, code: str, *, allow_recovery: bool = True) -> bool:
    """THE one way a second-factor code is checked outside enrolment: claim a slot in the budget the
    code's shape belongs to (or SecondFactorLocked, with nothing checked), check the code — spent if
    right — then settle."""
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


# ── The sign-in challenge ───────────────────────────────────────────────────────────────────────


class ChallengeRefused(Exception):
    """The challenge token is unusable: bad, expired, exhausted or already spent."""


async def issue_challenge(user: Any) -> str:
    """Mint a challenge token for ``user`` and the row that counts attempts against it."""
    settings = get_settings()
    now = datetime.now(UTC)
    expires = now + timedelta(minutes=CHALLENGE_TTL_MINUTES)
    jti = uuid.uuid4().hex
    # Swept here rather than by a job: a challenge is issued at most once per sign-in, so this keeps
    # the table to the challenges of the last few minutes without anything scheduled.
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
    """``(user id, jti)`` from a genuine, unexpired challenge token; ChallengeRefused otherwise.
    A session token is refused here (no ``purpose``), as a challenge token is refused everywhere
    else (its ``scope``)."""
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
    """Spend one of the challenge's five attempts, or raise ChallengeRefused when none are left.

    Claimed BEFORE the code is checked, with a conditional increment, so five concurrent guesses
    cannot all slip in under a count read once."""
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
    """A correct code ends the challenge: it cannot be used for a second session."""
    await db.twofactorchallenge.delete_many(where={"id": jti})


# ── What the account screen reads ───────────────────────────────────────────────────────────────


def status_of(user: Any) -> dict[str, Any]:
    enabled_at = _user_field(user, "twoFactorEnabledAt")
    enabled = bool(_user_field(user, "twoFactorEnabled"))
    return {
        "enabled": enabled,
        "recovery_codes_remaining": len(_user_field(user, "twoFactorRecoveryCodes") or []) if enabled else 0,
        "enabled_at": enabled_at.isoformat() if isinstance(enabled_at, datetime) else enabled_at,
    }


#: Re-exported from its one home, ``app/core/user_fields.py``.
SECRET_USER_FIELDS = _SECRET_USER_FIELDS
