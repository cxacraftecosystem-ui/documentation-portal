"""OpenAI Whisper: one shot up to 24 MB. Longer recordings are cut into parts by the chain.

``response_format=json`` returns text only: no speakers, no timings. That is why Whisper is the
last rung and why the subtitle path leaves it out.
"""

from __future__ import annotations

from typing import Any

from app.transcription.contract import ApiKey, AudioInput, Capabilities, SttOptions, SttResult
from app.transcription.providers import _http

ID = "whisper"
URL = "https://api.openai.com/v1/audio/transcriptions"
MODEL = "whisper-1"
#: Whisper refuses files at or over 25 MB. Stay comfortably under it.
MAX_BYTES = 24 * 1024 * 1024


class Whisper:
    id = ID
    display_name = "Whisper (OpenAI)"
    key_env = "OPENAI_API_KEY"
    max_bytes: int | None = MAX_BYTES
    max_duration_ms: int | None = None
    #: Over ``max_bytes`` the chain normalises and cuts the audio into parts rather than skipping.
    chunkable = True
    supports = Capabilities()
    price_per_hour_usd = 0.36

    def model(self, opts: SttOptions) -> str:
        return opts.models.get(ID) or MODEL

    def transcribe(self, audio: AudioInput, opts: SttOptions, key: ApiKey) -> SttResult:
        with _http.upload_body(audio) as body:
            response = _http.post(
                ID,
                URL,
                headers={"Authorization": f"Bearer {key.value}"},
                data={"model": self.model(opts), "response_format": "json"},
                files={
                    "file": (audio.filename, body, audio.mime_type or "application/octet-stream")
                },
                timeout=180,
            )
        _http.raise_for(ID, response)
        payload: dict[str, Any] = response.json() or {}
        text = str(payload.get("text") or "").strip()
        return SttResult(
            status="COMPLETED" if text else "EMPTY",
            text=text,
            provider=ID,
            model=self.model(opts),
            payload=payload if opts.keep_payload else None,
        )
