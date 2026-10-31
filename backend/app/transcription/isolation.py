"""ElevenLabs Voice Isolator: one recording in, the same recording with the background removed out.

IDENTICAL IN BOTH PORTALS (``shared/transcription.sha256``). Pure transport: no database, no S3.
``services/audio_isolation.py`` decides whether to send, re-reads consent, and stores the result.

Scribe v2 transcribes; it does not clean audio. Noise removal is this separate endpoint, billed
separately by ElevenLabs, and it is switched OFF unless an administrator turns it on in Settings.
The key is the portal's own ``ELEVENLABS_API_KEY``; a key restricted to speech-to-text answers 401
``missing_permissions`` here, which is reported as a key that needs the Voice Isolator permission.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import requests

logger = logging.getLogger(__name__)

URL = "https://api.elevenlabs.io/v1/audio-isolation"
TIMEOUT_SECONDS = 900

THROTTLED = "throttled"
BILLING = "billing"
UNREACHABLE = "unreachable"
FAILED = "failed"
PERMISSION = "permission"
KEY = "key"
REJECTED = "rejected"

MESSAGES = {
    THROTTLED: "ElevenLabs is busy just now; the clean-up will be tried again.",
    BILLING: "The ElevenLabs credit has run out. The clean-up resumes by itself once it is topped up.",
    UNREACHABLE: "ElevenLabs could not be reached; the clean-up will be tried again.",
    FAILED: "ElevenLabs had a problem cleaning this recording; it will be tried again.",
    PERMISSION: "This ElevenLabs key isn't allowed to use Voice Isolator. Give it that permission in ElevenLabs.",
    KEY: "ElevenLabs didn't accept the key. An administrator can check it in Settings.",
    REJECTED: "ElevenLabs couldn't clean this recording.",
}
#: Seconds to wait before asking again; a kind not here does not clear by itself.
HOLD_SECONDS = {THROTTLED: 300, UNREACHABLE: 300, BILLING: 1800}


class IsolationError(RuntimeError):
    def __init__(self, kind: str, *, status: int | None = None) -> None:
        super().__init__(MESSAGES.get(kind, MESSAGES[FAILED]))
        self.kind = kind
        self.status = status
        self.hold_seconds = HOLD_SECONDS.get(kind)
        self.retry = kind in HOLD_SECONDS or kind == FAILED


@dataclass
class Isolated:
    content: bytes
    mime_type: str


def _status_of_body(response: Any) -> str | None:
    try:
        detail = (response.json() or {}).get("detail")
    except ValueError:
        return None
    return detail.get("status") if isinstance(detail, dict) else None


def isolate(
    path: str,
    filename: str,
    mime_type: str,
    *,
    key: str,
    send: Callable[..., Any] = requests.post,
    timeout: float = TIMEOUT_SECONDS,
) -> Isolated:
    """POST the file at ``path``; the cleaned audio, or :class:`IsolationError`. Never logs the key."""
    try:
        with open(path, "rb") as handle:
            response = send(
                URL,
                headers={"xi-api-key": key},
                files={"audio": (filename or "recording", handle, mime_type or "application/octet-stream")},
                timeout=timeout,
            )
    except requests.RequestException as exc:
        logger.warning("voice isolator unreachable (%s)", type(exc).__name__)
        raise IsolationError(UNREACHABLE) from None
    status = getattr(response, "status_code", None)
    if status == 200 and response.content:
        mime = str(response.headers.get("content-type") or "audio/mpeg").split(";")[0].strip() or "audio/mpeg"
        return Isolated(response.content, mime)
    logger.warning("voice isolator answered HTTP %s", status)
    if status in (401, 403):
        raise IsolationError(PERMISSION if _status_of_body(response) == "missing_permissions" else KEY, status=status)
    if status == 402:
        raise IsolationError(BILLING, status=status)
    if status in (429, 503):
        raise IsolationError(THROTTLED, status=status)
    if status in (400, 413, 415, 422):
        raise IsolationError(REJECTED, status=status)
    raise IsolationError(FAILED, status=status)


#: What the cleaned copy is stored as, by the type the isolator answered with.
EXTENSIONS = {"audio/mpeg": "mp3", "audio/mp3": "mp3", "audio/wav": "wav", "audio/x-wav": "wav", "audio/mp4": "m4a"}


def cleaned_leaf(original_key: str, mime_type: str) -> str:
    """The cleaned copy's file name: the original's own name, with the extension of what came back."""
    leaf = (original_key or "recording").rsplit("/", 1)[-1]
    stem = leaf.rsplit(".", 1)[0] if "." in leaf else leaf
    return f"{stem or 'recording'}.{EXTENSIONS.get(mime_type, 'mp3')}"
