"""Runtime-editable third-party API keys: encrypted at rest, rotatable from the Settings hub.

WHY THIS EXISTS
---------------
Every provider key (OpenAI, ElevenLabs, Deepgram, Gemini, MapTiler, Google OAuth) used to live only
in the process environment. Rotating one — an expired trial key, a quota-exhausted Gemini key, a new
STT provider — meant an SSH session onto the EC2 box, an edit to the systemd unit/.env, and a restart
of BOTH the API and the queue worker. That is slow, needs shell access nobody else has, and drops
in-flight uploads. This module stores an optional *database override* per key so the master admin can
read, rotate and test keys from the UI, and the change takes effect on the next call — no restart.

RESOLUTION ORDER (``get_secret`` / ``peek_secret``)
    1. the decrypted ``ManagedSecret`` row, when one exists and can be decrypted;
    2. otherwise the environment/settings value.
An EMPTY ManagedSecret table therefore behaves *exactly* like the previous environment-only setup,
which is what makes this safe to deploy without touching any existing configuration.

ENCRYPTION KEY — READ THIS BEFORE ROTATING JWT_SECRET
-----------------------------------------------------
Values are Fernet (AES-128-CBC + HMAC) ciphertext. The Fernet key comes from the optional
``SECRETS_ENCRYPTION_KEY`` env var. When that is absent we DERIVE one deterministically from
``JWT_SECRET`` via HKDF-SHA256 with a fixed info string, so the feature works with zero new
configuration on every existing deployment.

  *** Consequence: if SECRETS_ENCRYPTION_KEY is not set and JWT_SECRET is rotated, every stored ***
  *** secret becomes PERMANENTLY UNDECRYPTABLE. They are not lost data (the environment values  ***
  *** still apply and the master admin can re-enter each key), but they cannot be recovered.    ***
  *** Set SECRETS_ENCRYPTION_KEY explicitly before you ever rotate JWT_SECRET.                  ***

We handle that case gracefully rather than loudly: ``decrypt`` returns None, the row is flagged
``lastStatus = FAILED`` with an explanatory ``lastError`` so the UI shows it needs re-entering, and
the resolver falls back to the environment value. A key rotation must never take transcription down.

THREADING / CACHING
-------------------
Transcription runs in worker threads (``asyncio.to_thread``) where awaiting Prisma is impossible, and
it would be wasteful to hit the database for a key on every chunk of every upload anyway. So the
overrides are cached in-process for ``_CACHE_TTL_SECONDS`` and read through two entry points:

  * ``get_secret`` (async)  — refreshes the cache if stale, then resolves. Use from request handlers.
  * ``peek_secret`` (sync)  — cache + environment only, never touches the database. Safe inside a
    thread; the async caller that spawned the thread is responsible for priming the cache first
    (see ``app.services.ai``, which calls ``refresh_if_stale`` before every ``to_thread`` hop).

Writes call ``invalidate()``, so a key saved in the UI is live on the very next call rather than up
to a TTL later.

SECURITY
--------
Plaintext leaves this module in exactly two places: the value handed to a provider HTTP call, and the
master-admin-only reveal endpoint. It is never logged, never put in an error message (probe errors
carry a status code, never a response body, which could echo the key back), and never serialised into
a list response — those carry only a 4-character ``hint``.
"""

from __future__ import annotations

import asyncio
import base64
import logging
import time
from collections.abc import Callable
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import lru_cache
from typing import Any

import requests
from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from app.core.config import get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

# Domain-separation string for the derived Fernet key. NEVER change it: every stored ciphertext is
# bound to it, so a new info string is indistinguishable from a JWT_SECRET rotation.
_HKDF_INFO = b"field-repository/managed-secrets/v1"

# How long a resolved override stays trusted in memory. Short enough that a rotation done on another
# process (the queue worker, a second uvicorn box) is picked up within half a minute; long enough that
# a multi-chunk transcription does not run a query per chunk. Same-process writes call invalidate(),
# so the TTL only ever matters for cross-process propagation.
_CACHE_TTL_SECONDS = 30.0

# Reachability probes must be snappy: they run inline in a master admin's "Test" click.
_PROBE_TIMEOUT_SECONDS = 5

_STATUS_UNKNOWN = "UNKNOWN"
_STATUS_OK = "OK"
_STATUS_FAILED = "FAILED"

SOURCE_DATABASE = "database"
SOURCE_ENVIRONMENT = "environment"
SOURCE_UNSET = "unset"

_UNDECRYPTABLE_ERROR = (
    "Stored value could not be decrypted (SECRETS_ENCRYPTION_KEY changed, or JWT_SECRET was rotated "
    "while no SECRETS_ENCRYPTION_KEY was set). Re-enter the key to fix it; the environment value is "
    "being used meanwhile."
)


# --- Encryption ------------------------------------------------------------------------------------


@lru_cache(maxsize=1)
def _fernet() -> Fernet:
    """The Fernet used for every read and write, built once per process.

    ``SECRETS_ENCRYPTION_KEY`` may be either a real Fernet key (``Fernet.generate_key()``) or any
    high-entropy passphrase — a passphrase is stretched through the same HKDF as the JWT_SECRET
    fallback. Accepting both matters because an operator who pastes a random hex string would
    otherwise get a hard crash, and "silently ignore the operator's key and use the derived one"
    would be far worse: it decrypts nothing they wrote with the intended key.
    """
    settings = get_settings()
    configured = (settings.secrets_encryption_key or "").strip()
    if configured:
        with suppress(Exception):
            return Fernet(configured.encode("utf-8"))  # already a well-formed Fernet key
        logger.info(
            "SECRETS_ENCRYPTION_KEY is not a Fernet key; deriving one from it via HKDF instead"
        )
        return Fernet(_derive_fernet_key(configured))
    # No dedicated key configured: derive from JWT_SECRET so the feature needs no new configuration.
    # See the module docstring for the rotation caveat this creates.
    return Fernet(_derive_fernet_key(settings.jwt_secret))


def _derive_fernet_key(source: str) -> bytes:
    """HKDF-SHA256(source) -> the 32 urlsafe-base64 bytes Fernet expects. Deterministic by design:
    the same input must always yield the same key or previously stored ciphertext stops decrypting.
    (No salt for the same reason — there is nowhere stable to persist one that is not itself a
    secret store.)"""
    raw = HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=_HKDF_INFO).derive(
        source.encode("utf-8")
    )
    return base64.urlsafe_b64encode(raw)


def encrypt(value: str) -> str:
    return _fernet().encrypt(value.encode("utf-8")).decode("ascii")


def decrypt(token: str) -> str | None:
    """Plaintext, or None when the ciphertext cannot be opened with the current key.

    Returns None instead of raising so a changed encryption key degrades to "the environment value
    applies and the UI says re-enter this one" rather than a 500 on every transcription.
    """
    try:
        return _fernet().decrypt(token.encode("utf-8")).decode("utf-8")
    except (InvalidToken, ValueError, TypeError):
        return None


# --- The registry of manageable keys -----------------------------------------------------------------


@dataclass(frozen=True)
class ManagedKey:
    """One key the Settings hub may manage.

    ``settings_attr`` is the field on :class:`app.core.config.Settings` holding the environment
    fallback — the registry is the ONLY place the env-var name and the settings field are tied
    together, so nothing else has to know that e.g. NEXT_PUBLIC_MAPTILER_API_KEY lands on
    ``maptiler_api_key``. ``probe`` is a cheap reachability check, run only when explicitly asked.
    """

    key: str
    label: str
    description: str
    settings_attr: str
    probe: Callable[[str], tuple[bool, str | None]]


def _probe_http(
    url: str,
    *,
    headers: dict[str, str] | None = None,
    params: dict[str, str] | None = None,
    secret: str = "",
) -> tuple[bool, str | None]:
    """GET *url* and turn the outcome into (ok, short human error).

    Deliberately reports only the status code, never the response body: provider error payloads
    sometimes echo the offending key back, and this string is persisted and shown in the UI.
    """
    try:
        response = requests.get(
            url, headers=headers or {}, params=params or {}, timeout=_PROBE_TIMEOUT_SECONDS
        )
    except requests.Timeout:
        return False, f"No response within {_PROBE_TIMEOUT_SECONDS}s"
    except requests.RequestException as exc:
        return False, _redact(f"Network error: {type(exc).__name__}", secret)
    if response.ok:
        return True, None
    if response.status_code in {401, 403}:
        return False, f"Key rejected by the provider (HTTP {response.status_code})"
    if response.status_code == 429:
        return False, "Key reached its rate limit (HTTP 429) — it is valid but throttled"
    return False, f"Provider returned HTTP {response.status_code}"


def _redact(message: str, secret: str) -> str:
    """Belt-and-braces: never let a key slip into a stored/displayed error string."""
    if secret and len(secret) >= 8 and secret in message:
        return message.replace(secret, "***")
    return message


def _probe_openai(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://api.openai.com/v1/models",
        headers={"Authorization": f"Bearer {value}"},
        secret=value,
    )


def _probe_elevenlabs(value: str) -> tuple[bool, str | None]:
    return _probe_http("https://api.elevenlabs.io/v1/user", headers={"xi-api-key": value}, secret=value)


def _probe_deepgram(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://api.deepgram.com/v1/projects",
        headers={"Authorization": f"Token {value}"},
        secret=value,
    )


def _probe_gemini(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://generativelanguage.googleapis.com/v1beta/models",
        params={"key": value, "pageSize": "1"},
        secret=value,
    )


def _probe_gemini_pool(value: str) -> tuple[bool, str | None]:
    """Probe the FIRST key of the rotation pool. Testing every key would burn free-tier quota on a
    button click; the first one failing is already the signal the admin needs."""
    first = next((part.strip() for part in value.replace("\n", ",").split(",") if part.strip()), "")
    if not first:
        return False, "No keys in the list"
    return _probe_gemini(first)


def _probe_maptiler(value: str) -> tuple[bool, str | None]:
    # The style document is the same asset the web map loads, so a 200 here proves the exact thing
    # the frontend needs (key valid AND allowed for this origin-less server-side request).
    return _probe_http(
        "https://api.maptiler.com/maps/streets-v2/style.json", params={"key": value}, secret=value
    )


def _probe_google_client_id(value: str) -> tuple[bool, str | None]:
    """A client ID is public and has no authenticated endpoint to poke, so validate its SHAPE.

    Google issues ``<digits>-<hash>.apps.googleusercontent.com``; a truncated paste (the common
    mistake) is caught by this and would otherwise only surface as "Google sign-in stopped working".
    """
    candidate = value.strip()
    if candidate.endswith(".apps.googleusercontent.com") and len(candidate) > 30:
        return True, None
    return False, "Does not look like a Google OAuth client ID (…apps.googleusercontent.com)"


MANAGED_KEYS: dict[str, ManagedKey] = {
    spec.key: spec
    for spec in (
        ManagedKey(
            key="OPENAI_API_KEY",
            label="OpenAI",
            description=(
                "Transcript refinement and translation (chat model), and Whisper transcription when "
                "neither ElevenLabs nor Deepgram is configured."
            ),
            settings_attr="openai_api_key",
            probe=_probe_openai,
        ),
        ManagedKey(
            key="ELEVENLABS_API_KEY",
            label="ElevenLabs Scribe",
            description=(
                "First-choice speech-to-text. Auto-detects Hindi and regional languages and accepts "
                "large files, so field recordings need no local chunking."
            ),
            settings_attr="elevenlabs_api_key",
            probe=_probe_elevenlabs,
        ),
        ManagedKey(
            key="DEEPGRAM_API_KEY",
            label="Deepgram",
            description="Second-choice speech-to-text (Nova-3, multilingual), used when ElevenLabs fails.",
            settings_attr="deepgram_api_key",
            probe=_probe_deepgram,
        ),
        ManagedKey(
            key="GEMINI_API_KEY",
            label="Gemini",
            description="Vision model that measures craft objects photographed on the 1-inch grid sheet.",
            settings_attr="gemini_api_key",
            probe=_probe_gemini,
        ),
        ManagedKey(
            key="GEMINI_API_KEYS",
            label="Gemini key pool",
            description=(
                "Optional comma-separated extra Gemini keys. Measurement requests rotate across the "
                "pool so free-tier quota on any single key is not the ceiling."
            ),
            settings_attr="gemini_api_keys_raw",
            probe=_probe_gemini_pool,
        ),
        ManagedKey(
            key="NEXT_PUBLIC_MAPTILER_API_KEY",
            label="MapTiler",
            description="Basemap tiles for the location pickers and map views in the web app.",
            settings_attr="maptiler_api_key",
            probe=_probe_maptiler,
        ),
        ManagedKey(
            key="GOOGLE_CLIENT_ID",
            label="Google OAuth client ID",
            description=(
                "Web client ID accepted for Google sign-in. Public by nature, but rotating it here "
                "beats redeploying. (Format-checked only — a client ID has no test endpoint.)"
            ),
            settings_attr="google_client_id",
            probe=_probe_google_client_id,
        ),
    )
}


def is_managed(key: str) -> bool:
    return key in MANAGED_KEYS


# --- The in-process cache ---------------------------------------------------------------------------

# Replaced wholesale (never mutated in place) so a reader in another thread always sees a complete,
# self-consistent snapshot without needing a lock.
_overrides: dict[str, str] = {}
_loaded_at: float = 0.0


def invalidate() -> None:
    """Drop the cached overrides so the next resolution re-reads the database. Called after every
    write, which is what makes a key saved in the UI take effect immediately."""
    global _loaded_at
    _loaded_at = 0.0


def _cache_is_fresh() -> bool:
    return _loaded_at > 0.0 and (time.monotonic() - _loaded_at) < _CACHE_TTL_SECONDS


async def refresh_if_stale(force: bool = False) -> None:
    """Reload the override cache when it has expired. Never raises.

    A database hiccup must not take transcription down: on failure we keep whatever snapshot we have
    (or fall through to environment values) exactly as if no override existed.
    """
    if not force and _cache_is_fresh():
        return
    global _overrides, _loaded_at
    try:
        rows = await db.managedsecret.find_many()
    except Exception as exc:  # noqa: BLE001 - secrets must never break the request/worker path
        logger.warning("Could not load managed secrets (%s); using environment values", exc)
        return

    fresh: dict[str, str] = {}
    undecryptable: list[Any] = []
    for row in rows:
        if row.key not in MANAGED_KEYS:
            continue  # a row for a key we no longer manage: ignore rather than resurrect it
        plaintext = decrypt(row.valueEnc)
        if plaintext is None:
            undecryptable.append(row)
            continue
        fresh[row.key] = plaintext
    _overrides = fresh
    _loaded_at = time.monotonic()

    # Flag (once) anything we could not open, so the UI can tell the admin to re-enter it instead of
    # silently serving the stale environment value forever.
    for row in undecryptable:
        logger.error("Managed secret %s could not be decrypted; falling back to the environment", row.key)
        if row.lastStatus == _STATUS_FAILED and row.lastError == _UNDECRYPTABLE_ERROR:
            continue
        with suppress(Exception):
            await db.managedsecret.update(
                where={"key": row.key},
                data={
                    "lastStatus": _STATUS_FAILED,
                    "lastError": _UNDECRYPTABLE_ERROR,
                    "lastCheckedAt": datetime.now(timezone.utc),
                },
            )


# --- Resolution -------------------------------------------------------------------------------------


def env_value(key: str) -> str | None:
    """The environment/settings fallback for *key*, or None when it is blank/absent."""
    spec = MANAGED_KEYS.get(key)
    if spec is None:
        return None
    value = getattr(get_settings(), spec.settings_attr, None)
    value = str(value).strip() if value is not None else ""
    return value or None


def peek_secret(key: str) -> str | None:
    """Resolve *key* WITHOUT touching the database — cached override first, environment second.

    Safe to call from a worker thread (transcription, measurement). The caller must have primed the
    cache with ``refresh_if_stale`` on the event loop first; if it has not, this simply returns the
    environment value, i.e. the pre-existing behaviour.
    """
    override = _overrides.get(key)
    if override:
        return override
    return env_value(key)


async def get_secret(key: str) -> str | None:
    """Resolve *key*: the stored decrypted override if there is one, else the environment value."""
    await refresh_if_stale()
    return peek_secret(key)


def gemini_key_pool() -> list[str]:
    """Every Gemini key to rotate across, in order and de-duplicated.

    Mirrors ``Settings.gemini_api_keys`` (single GEMINI_API_KEY first, then the GEMINI_API_KEYS list)
    but resolves each half through the override layer, so a key saved in the UI wins over the env one
    while an empty ManagedSecret table reproduces the old list exactly. Synchronous on purpose: the
    measurement call runs in a thread.
    """
    parts: list[str] = []
    single = peek_secret("GEMINI_API_KEY")
    if single:
        parts.append(single.strip())
    pool = peek_secret("GEMINI_API_KEYS") or ""
    for chunk in pool.replace("\n", ",").split(","):
        candidate = chunk.strip()
        if candidate:
            parts.append(candidate)
    ordered: list[str] = []
    seen: set[str] = set()
    for key in parts:
        if key not in seen:
            seen.add(key)
            ordered.append(key)
    return ordered


# --- Description (what the API returns) ---------------------------------------------------------------


def _hint(value: str | None) -> str | None:
    """Last four characters, so an admin can tell two keys apart without revealing either. Values too
    short to hide are reduced to an ellipsis rather than half-printed."""
    if not value:
        return None
    return value[-4:] if len(value) > 8 else "…"


def _describe(spec: ManagedKey, row: Any | None) -> dict[str, Any]:
    """The safe, value-free projection of one managed key. NEVER add the plaintext to this dict."""
    environment = env_value(spec.key)
    stored = row is not None
    if stored:
        source = SOURCE_DATABASE
    elif environment:
        source = SOURCE_ENVIRONMENT
    else:
        source = SOURCE_UNSET
    updated_by = getattr(row, "updatedBy", None) if stored else None
    return {
        "key": spec.key,
        "label": spec.label,
        "description": spec.description,
        "configured": stored or bool(environment),
        "source": source,
        "hint": (row.hint if stored else _hint(environment)),
        "lastStatus": (row.lastStatus if stored else _STATUS_UNKNOWN),
        "lastCheckedAt": (row.lastCheckedAt if stored else None),
        "lastError": (row.lastError if stored else None),
        "updatedBy": (getattr(updated_by, "name", None) or getattr(updated_by, "email", None)),
        "updatedAt": (row.updatedAt if stored else None),
    }


async def _rows_by_key() -> dict[str, Any]:
    rows = await db.managedsecret.find_many(include={"updatedBy": True})
    return {row.key: row for row in rows}


async def list_managed_secrets() -> list[dict[str, Any]]:
    """Every registry key with its current state. Registry order (not database order) so the Settings
    hub always lists the providers in the same, meaningful sequence. No probes are run here.

    The refresh first is what makes the "needs re-entering" flag actually visible. Only
    :func:`refresh_if_stale` attempts a decrypt, and it is the only thing that writes
    ``lastStatus = FAILED`` on a row it cannot open. Reading the rows straight from the database — as
    this did — meant that after a JWT_SECRET rotation the Settings hub, the ONE screen meant to
    surface the problem, showed every undecryptable key as ``source: database`` / ``UNKNOWN`` / no
    error: indistinguishable from a healthy override, while the environment value was quietly being
    used instead. Cheap: one query, cached for the TTL, and it never raises.
    """
    await refresh_if_stale()
    rows = await _rows_by_key()
    return [_describe(spec, rows.get(key)) for key, spec in MANAGED_KEYS.items()]


async def describe_secret(key: str) -> dict[str, Any]:
    row = await db.managedsecret.find_unique(where={"key": key}, include={"updatedBy": True})
    return _describe(MANAGED_KEYS[key], row)


# --- Writes -----------------------------------------------------------------------------------------


async def set_secret(key: str, value: str, updated_by_id: str | None) -> dict[str, Any]:
    """Store (or rotate) the override for *key*. Returns the value-free description of the new row.

    ``lastStatus`` resets to UNKNOWN because a previous OK/FAILED verdict describes the OLD key and
    would be actively misleading next to a freshly pasted one.
    """
    plaintext = value.strip()
    spec = MANAGED_KEYS[key]
    payload = {
        "valueEnc": encrypt(plaintext),
        "hint": _hint(plaintext),
        "description": spec.description,
        "lastStatus": _STATUS_UNKNOWN,
        "lastCheckedAt": None,
        "lastError": None,
        "updatedById": updated_by_id,
    }
    await db.managedsecret.upsert(
        where={"key": key},
        data={"create": {"key": key, **payload}, "update": payload},
    )
    invalidate()
    # WARNING level so the line actually survives: the API process configures no root handler, so
    # INFO records from app loggers are dropped under uvicorn (see routes/secrets.py reveal). A
    # credential rotation is rare and security-relevant — it belongs in the journal.
    logger.warning("AUDIT managed-secret set: key=%s by=%s", key, updated_by_id)
    return await describe_secret(key)


async def delete_secret(key: str) -> dict[str, Any]:
    """Remove the override so the environment value applies again (the row is the only override —
    deleting it can never un-configure a key that the environment still supplies)."""
    await db.managedsecret.delete_many(where={"key": key})
    invalidate()
    logger.warning("AUDIT managed-secret cleared: key=%s", key)
    return await describe_secret(key)


# --- Probing ------------------------------------------------------------------------------------------


async def test_secret(key: str) -> dict[str, Any]:
    """Run this key's probe now and persist the verdict on the row (when there is one).

    An environment-sourced key has no row to write to, so its verdict is returned but not stored —
    the alternative, creating a row, would copy the environment value into the database as an
    override, which is emphatically not what "test" should do.
    """
    spec = MANAGED_KEYS[key]
    value = await get_secret(key)
    checked_at = datetime.now(timezone.utc)
    if not value:
        ok, error = False, "Not configured"
    else:
        ok, error = await asyncio.to_thread(_safe_probe, spec, value)

    described = await describe_secret(key)
    described["lastStatus"] = _STATUS_OK if ok else _STATUS_FAILED
    described["lastCheckedAt"] = checked_at
    described["lastError"] = None if ok else error
    if described["source"] == SOURCE_DATABASE:
        with suppress(Exception):
            await db.managedsecret.update(
                where={"key": key},
                data={
                    "lastStatus": described["lastStatus"],
                    "lastCheckedAt": checked_at,
                    "lastError": described["lastError"],
                },
            )
    logger.info("Managed secret %s probed: %s", key, described["lastStatus"])
    return described


def _safe_probe(spec: ManagedKey, value: str) -> tuple[bool, str | None]:
    """A probe must never raise — it is a UI button, and an unexpected exception would 500 it."""
    try:
        ok, error = spec.probe(value)
    except Exception as exc:  # noqa: BLE001 - any provider client quirk becomes a readable verdict
        return False, _redact(f"Probe failed: {type(exc).__name__}", value)
    return ok, _redact(error, value) if error else None
