from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

import requests

ProviderId = Literal["elevenlabs", "deepgram", "whisper", "muse"]

PROVIDER_IDS: tuple[str, ...] = ("elevenlabs", "deepgram", "whisper", "muse")

DISABLED_BY_DEFAULT: tuple[str, ...] = ("muse",)



class SttError(Exception):
    provider: str = ""


class SttHttpError(SttError, requests.HTTPError):
    def __init__(self, provider: str, status: int | None, response: Any = None) -> None:
        requests.HTTPError.__init__(self, f"{provider} answered HTTP {status}", response=response)
        self.provider = provider
        self.status = status


class KeyRejected(SttHttpError):
    pass


class Throttled(SttHttpError):
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
    pass


class TooLarge(SttHttpError):
    pass


class ProviderFailed(SttHttpError):
    pass


class Unreachable(SttError, requests.ConnectionError):
    def __init__(self, provider: str, detail: str = "") -> None:
        requests.ConnectionError.__init__(self, f"{provider} could not be reached")
        self.provider = provider
        self.detail = detail



@dataclass(frozen=True)
class ApiKey:
    ref: str
    value: str

    def __repr__(self) -> str:
        return f"ApiKey(ref={self.ref!r})"


@dataclass(frozen=True)
class AudioInput:
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
    language_bias: list[str] = field(default_factory=list)
    keyterms: list[str] = field(default_factory=list)
    diarize: bool = True
    want_segments: bool = True
    models: dict[str, str] = field(default_factory=dict)
    keep_payload: bool = False
    session_id: str | None = None



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
    degraded: bool = False
    chunks: int = 1
    note: str | None = None
    raw_ref: str | None = None
    payload: Any = None
    words: list[dict[str, Any]] = field(default_factory=list)


class SttProvider(Protocol):
    id: str
    display_name: str
    key_env: str
    max_bytes: int | None
    max_duration_ms: int | None
    supports: Capabilities
    price_per_hour_usd: float

    def transcribe(self, audio: AudioInput, opts: SttOptions, key: ApiKey) -> SttResult: ...
