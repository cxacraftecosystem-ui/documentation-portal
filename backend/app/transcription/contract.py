"""Types every adapter, the chain and both portals agree on.

Adapters RAISE the typed errors below and never return a status string for a failure. The errors
subclass ``requests`` exceptions on purpose: code written before this package existed catches
``requests.HTTPError`` and ``requests.RequestException``, and it keeps working unchanged.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

import requests

ProviderId = Literal["elevenlabs", "deepgram", "whisper", "muse"]

#: Every provider the package knows, in the order that applied before ranking existed. Muse is last
#: so adding it never moves an existing deployment's first choice.
PROVIDER_IDS: tuple[str, ...] = ("elevenlabs", "deepgram", "whisper", "muse")

#: Providers that ship switched off. Muse needs the owner (and the Ministry) to accept that its
#: processing location is not documented before any audio goes to it.
DISABLED_BY_DEFAULT: tuple[str, ...] = ("muse",)


# --- Errors -----------------------------------------------------------------------------------


class SttError(Exception):
    """Base for every adapter failure. ``provider`` names who failed; never a key or a URL."""

    provider: str = ""


class SttHttpError(SttError, requests.HTTPError):
    """A provider answered with an HTTP failure. Keeps ``.response`` like ``requests`` does."""

    def __init__(self, provider: str, status: int | None, response: Any = None) -> None:
        requests.HTTPError.__init__(self, f"{provider} answered HTTP {status}", response=response)
        self.provider = provider
        self.status = status


class KeyRejected(SttHttpError):
    """401/403: the key is wrong or revoked. Every retry will be refused the same way."""


class Throttled(SttHttpError):
    """429/503 (and Muse's 500 "processing budget exceeded" after its one retry): come back later."""

    def __init__(
        self,
        provider: str,
        status: int | None,
        response: Any = None,
        retry_after: float | None = None,
    ) -> None:
        super().__init__(provider, status, response)
        self.retry_after = retry_after


class OptionRejected(SttHttpError):
    """400/422 on the conservative retry too: the provider refused the request, not the audio."""


class TooLarge(SttHttpError):
    """413, or a pre-flight size or length check: this provider cannot take this file whole."""


class ProviderFailed(SttHttpError):
    """5xx or any other answer: the provider broke on this request."""


class Unreachable(SttError, requests.ConnectionError):
    """No answer at all: DNS, TLS, timeout, connection reset."""

    def __init__(self, provider: str, detail: str = "") -> None:
        requests.ConnectionError.__init__(self, f"{provider} could not be reached")
        self.provider = provider
        self.detail = detail


# --- Inputs -----------------------------------------------------------------------------------


@dataclass(frozen=True)
class ApiKey:
    """One usable key. ``ref`` names it (the env name, or ``personal:<provider>``) and is what
    cooldowns and usage are recorded against; ``value`` never leaves the adapter."""

    ref: str
    value: str

    def __repr__(self) -> str:  # keys must never reach a log line through a repr
        return f"ApiKey(ref={self.ref!r})"


@dataclass(frozen=True)
class AudioInput:
    """The recording, as bytes (short dictation) or as a temp file (anything read from storage).

    Where one is given the other is None. ``duration_ms`` is the probe's answer when there was one.
    """

    content: bytes | None
    source_path: str | None
    filename: str
    mime_type: str
    duration_ms: int | None = None

    @property
    def size_bytes(self) -> int:
        if self.source_path is not None:
            try:
                return os.path.getsize(self.source_path)
            except OSError:
                return 0
        return len(self.content or b"")

    def read_bytes(self) -> bytes:
        if self.source_path is not None:
            with open(self.source_path, "rb") as handle:
                return handle.read()
        return self.content or b""


@dataclass(frozen=True)
class Capabilities:
    diarize: bool = False
    word_timestamps: bool = False
    segment_timestamps: bool = False
    language_bias: bool = False
    keyterm_limit: int = 0


@dataclass
class SttOptions:
    """What a caller asks for. ``language_bias`` is a hint, never a lock."""

    language_bias: list[str] = field(default_factory=list)
    keyterms: list[str] = field(default_factory=list)
    diarize: bool = True
    want_segments: bool = True
    #: Model override per provider id, e.g. {"deepgram": "nova-3"}. Absent = adapter default.
    models: dict[str, str] = field(default_factory=dict)
    #: Keep the provider's full response on the result (subtitle cue building). Never persisted.
    keep_payload: bool = False
    #: Traceable id for providers that take one (Muse ``sessionId``): ``<mediaId>-<chunkIndex>``.
    session_id: str | None = None


# --- Outputs ----------------------------------------------------------------------------------


@dataclass
class Segment:
    start_ms: int
    end_ms: int
    speaker: str | None
    text: str

    def as_json(self) -> dict[str, Any]:
        return {
            "startMs": self.start_ms,
            "endMs": self.end_ms,
            "speaker": self.speaker,
            "text": self.text,
        }


@dataclass
class SttResult:
    status: Literal["COMPLETED", "EMPTY"]
    text: str
    provider: str
    model: str
    segments: list[Segment] = field(default_factory=list)
    language: str | None = None
    audio_ms: int | None = None
    speakers: int = 0
    #: True when the provider refused the full option set and the conservative retry answered.
    degraded: bool = False
    #: How many parts the audio was sent in (1 = whole file).
    chunks: int = 1
    #: A note a reader needs, e.g. that speakers are numbered per part.
    note: str | None = None
    raw_ref: str | None = None
    #: The provider's own response, only when ``SttOptions.keep_payload``. Never persisted.
    payload: Any = None


class SttProvider(Protocol):
    id: str
    display_name: str
    key_env: str
    max_bytes: int | None
    max_duration_ms: int | None
    supports: Capabilities
    price_per_hour_usd: float

    def transcribe(self, audio: AudioInput, opts: SttOptions, key: ApiKey) -> SttResult: ...
