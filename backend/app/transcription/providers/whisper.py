from __future__ import annotations

from typing import Any

from app.transcription.contract import ApiKey, AudioInput, Capabilities, SttOptions, SttResult
from app.transcription.providers import _http

ID = "whisper"
URL = "https://api.openai.com/v1/audio/transcriptions"
MODEL = "whisper-1"
MAX_BYTES = 24 * 1024 * 1024


class Whisper:
    id = ID
    display_name = "Whisper (OpenAI)"
    key_env = "OPENAI_API_KEY"
    max_bytes: int | None = MAX_BYTES
    max_duration_ms: int | None = None
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
