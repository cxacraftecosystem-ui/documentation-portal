from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
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

_HKDF_INFO = b"field-repository/managed-secrets/v1"

_FINGERPRINT_HKDF_INFO = b"field-repository/secret-verdict-fingerprint/v1"

_CACHE_TTL_SECONDS = 30.0

_PROBE_TIMEOUT_SECONDS = 5

_STATUS_UNKNOWN = "UNKNOWN"
_STATUS_OK = "OK"
_STATUS_FAILED = "FAILED"

SOURCE_DATABASE = "database"
SOURCE_ENVIRONMENT = "environment"
SOURCE_UNSET = "unset"

_UNDECRYPTABLE_ERROR = (
    "This saved key can't be read. Enter it again; the default is used meanwhile."
)



@lru_cache(maxsize=1)
def _fernet() -> Fernet:
    settings = get_settings()
    configured = (settings.secrets_encryption_key or "").strip()
    if configured:
        with suppress(Exception):
            return Fernet(configured.encode("utf-8"))
        logger.info(
            "SECRETS_ENCRYPTION_KEY is not a Fernet key; deriving one from it via HKDF instead"
        )
        return Fernet(_derive_fernet_key(configured))
    return Fernet(_derive_fernet_key(settings.jwt_secret))


def _derive_fernet_key(source: str) -> bytes:
    raw = HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=_HKDF_INFO).derive(
        source.encode("utf-8")
    )
    return base64.urlsafe_b64encode(raw)


def encrypt(value: str) -> str:
    return _fernet().encrypt(value.encode("utf-8")).decode("ascii")


def decrypt(token: str) -> str | None:
    try:
        return _fernet().decrypt(token.encode("utf-8")).decode("utf-8")
    except (InvalidToken, ValueError, TypeError):
        return None


@lru_cache(maxsize=1)
def _fingerprint_pepper() -> bytes:
    settings = get_settings()
    source = (settings.secrets_encryption_key or "").strip() or settings.jwt_secret
    return HKDF(
        algorithm=hashes.SHA256(), length=32, salt=None, info=_FINGERPRINT_HKDF_INFO
    ).derive(source.encode("utf-8"))


def fingerprint(value: str) -> str:
    return hmac.new(_fingerprint_pepper(), value.encode("utf-8"), hashlib.sha256).hexdigest()



@dataclass(frozen=True)
class ManagedKey:
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
    try:
        response = requests.get(
            url, headers=headers or {}, params=params or {}, timeout=_PROBE_TIMEOUT_SECONDS
        )
    except requests.Timeout:
        return False, "The provider didn't answer in time. Try again later."
    except requests.RequestException:
        return False, "The provider couldn't be reached. Try again later."
    if response.ok:
        return True, None
    if response.status_code in {401, 403}:
        return False, "The provider rejected this key."
    if response.status_code == 429:
        return False, "This key works but has reached its usage limit. Try again later."
    return False, "The provider couldn't check this key. Try again later."


def _redact(message: str, secret: str) -> str:
    if secret and len(secret) >= 8 and secret in message:
        return message.replace(secret, "***")
    return message


def _probe_openai(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://api.openai.com/v1/models",
        headers={"Authorization": f"Bearer {value}"},
        secret=value,
    )


def _probe_anthropic(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://api.anthropic.com/v1/models",
        headers={"x-api-key": value, "anthropic-version": "2023-06-01"},
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


def _probe_meta(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://api.meta.ai/v1/models",
        headers={"Authorization": f"Bearer {value}"},
        secret=value,
    )


def _probe_gemini(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://generativelanguage.googleapis.com/v1beta/models",
        params={"key": value, "pageSize": "1"},
        secret=value,
    )


def _probe_gemini_pool(value: str) -> tuple[bool, str | None]:
    first = next((part.strip() for part in value.replace("\n", ",").split(",") if part.strip()), "")
    if not first:
        return False, "No keys in the list"
    return _probe_gemini(first)


def _probe_maptiler(value: str) -> tuple[bool, str | None]:
    return _probe_http(
        "https://api.maptiler.com/maps/streets-v2/style.json", params={"key": value}, secret=value
    )


def _probe_google_client_id(value: str) -> tuple[bool, str | None]:
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
                "neither ElevenLabs nor Deepgram has a key."
            ),
            settings_attr="openai_api_key",
            probe=_probe_openai,
        ),
        ManagedKey(
            key="ANTHROPIC_API_KEY",
            label="Anthropic Claude",
            description=(
                "Proofreading, expanding, summarising, translating and photo captions when a "
                "designer has not supplied their own key. Claude cannot transcribe audio — no "
                "Claude model accepts a sound file — so transcription is unaffected by this key."
            ),
            settings_attr="anthropic_api_key",
            probe=_probe_anthropic,
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
            key="META_MODEL_API_KEY",
            label="Meta Muse",
            description=(
                "Meta Muse Voice Transcribe, the fourth speech-to-text provider. Switched off "
                "until the owner accepts where Meta processes the audio."
            ),
            settings_attr="meta_model_api_key",
            probe=_probe_meta,
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
                "Used for Google sign-in. Only its format is checked."
            ),
            settings_attr="google_client_id",
            probe=_probe_google_client_id,
        ),
    )
}


def is_managed(key: str) -> bool:
    return key in MANAGED_KEYS


_overrides: dict[str, str] = {}
_loaded_at: float = 0.0


def invalidate() -> None:
    global _loaded_at
    _loaded_at = 0.0


def _cache_is_fresh() -> bool:
    return _loaded_at > 0.0 and (time.monotonic() - _loaded_at) < _CACHE_TTL_SECONDS


async def refresh_if_stale(force: bool = False) -> None:
    if not force and _cache_is_fresh():
        return
    global _overrides, _loaded_at
    try:
        rows = await db.managedsecret.find_many()
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not load managed secrets (%s); using environment values", exc)
        return

    fresh: dict[str, str] = {}
    undecryptable: list[Any] = []
    for row in rows:
        if row.key not in MANAGED_KEYS:
            continue
        plaintext = decrypt(row.valueEnc)
        if plaintext is None:
            undecryptable.append(row)
            continue
        fresh[row.key] = plaintext
    _overrides = fresh
    _loaded_at = time.monotonic()

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



def env_value(key: str) -> str | None:
    spec = MANAGED_KEYS.get(key)
    if spec is None:
        return None
    value = getattr(get_settings(), spec.settings_attr, None)
    value = str(value).strip() if value is not None else ""
    return value or None


def peek_secret(key: str) -> str | None:
    override = _overrides.get(key)
    if override:
        return override
    return env_value(key)


async def get_secret(key: str) -> str | None:
    await refresh_if_stale()
    return peek_secret(key)


def gemini_key_pool() -> list[str]:
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



def _verdict_of(row: Any) -> dict[str, Any]:
    return {"status": row.status, "checkedAt": row.checkedAt, "error": row.error}


def _still_describes_current_key(row: Any) -> bool:
    current = env_value(row.key)
    if not current:
        return False
    return hmac.compare_digest(row.fingerprint, fingerprint(current))


async def environment_verdicts() -> dict[str, dict[str, Any]]:
    try:
        rows = await db.secrettestresult.find_many()
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not load environment key verdicts (%s)", exc)
        return {}
    return {row.key: _verdict_of(row) for row in rows if _still_describes_current_key(row)}


async def environment_verdict(key: str) -> dict[str, Any] | None:
    try:
        row = await db.secrettestresult.find_unique(where={"key": key})
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not load the environment key verdict for %s (%s)", key, exc)
        return None
    if row is None or not _still_describes_current_key(row):
        return None
    return _verdict_of(row)


async def record_environment_verdict(
    key: str, value: str, *, ok: bool, checked_at: datetime, error: str | None
) -> None:
    payload = {
        "status": _STATUS_OK if ok else _STATUS_FAILED,
        "checkedAt": checked_at,
        "error": None if ok else error,
        "fingerprint": fingerprint(value),
    }
    try:
        await db.secrettestresult.upsert(
            where={"key": key},
            data={"create": {"key": key, **payload}, "update": payload},
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not persist the environment key verdict for %s (%s)", key, exc)



def _hint(value: str | None) -> str | None:
    if not value:
        return None
    return value[-4:] if len(value) > 8 else "…"


def _describe(spec: ManagedKey, row: Any | None, verdict: dict[str, Any] | None = None) -> dict[str, Any]:
    environment = env_value(spec.key)
    stored = row is not None
    if stored:
        source = SOURCE_DATABASE
    elif environment:
        source = SOURCE_ENVIRONMENT
    else:
        source = SOURCE_UNSET
    updated_by = getattr(row, "updatedBy", None) if stored else None
    if stored:
        last_status, last_checked_at, last_error = row.lastStatus, row.lastCheckedAt, row.lastError
    elif verdict is not None:
        last_status, last_checked_at, last_error = (
            verdict["status"],
            verdict["checkedAt"],
            verdict["error"],
        )
    else:
        last_status, last_checked_at, last_error = _STATUS_UNKNOWN, None, None
    return {
        "key": spec.key,
        "label": spec.label,
        "description": spec.description,
        "configured": stored or bool(environment),
        "source": source,
        "hint": (row.hint if stored else _hint(environment)),
        "lastStatus": last_status,
        "lastCheckedAt": last_checked_at,
        "lastError": last_error,
        "updatedBy": (getattr(updated_by, "name", None) or getattr(updated_by, "email", None)),
        "updatedAt": (row.updatedAt if stored else None),
    }


async def _rows_by_key() -> dict[str, Any]:
    rows = await db.managedsecret.find_many(include={"updatedBy": True})
    return {row.key: row for row in rows}


async def list_managed_secrets() -> list[dict[str, Any]]:
    await refresh_if_stale()
    rows = await _rows_by_key()
    verdicts = await environment_verdicts()
    return [_describe(spec, rows.get(key), verdicts.get(key)) for key, spec in MANAGED_KEYS.items()]


async def describe_secret(key: str) -> dict[str, Any]:
    row = await db.managedsecret.find_unique(where={"key": key}, include={"updatedBy": True})
    verdict = None if row is not None else await environment_verdict(key)
    return _describe(MANAGED_KEYS[key], row, verdict)



async def set_secret(key: str, value: str, updated_by_id: str | None) -> dict[str, Any]:
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
    logger.warning("AUDIT managed-secret set: key=%s by=%s", key, updated_by_id)
    return await describe_secret(key)


async def delete_secret(key: str) -> dict[str, Any]:
    await db.managedsecret.delete_many(where={"key": key})
    invalidate()
    logger.warning("AUDIT managed-secret cleared: key=%s", key)
    return await describe_secret(key)



async def test_secret(key: str) -> dict[str, Any]:
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
    elif value:
        await record_environment_verdict(key, value, ok=ok, checked_at=checked_at, error=error)
    logger.info("Managed secret %s probed: %s", key, described["lastStatus"])
    return described


def _safe_probe(spec: ManagedKey, value: str) -> tuple[bool, str | None]:
    try:
        ok, error = spec.probe(value)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Managed secret %s probe raised %s", spec.key, type(exc).__name__)
        return False, "This key couldn't be checked. Try again later."
    return ok, _redact(error, value) if error else None
