from __future__ import annotations

import json
from typing import Any

from app.transcription.contract import (
    ApiKey,
    AudioInput,
    Capabilities,
    Segment,
    SttOptions,
    SttResult,
    TooLarge,
)
from app.transcription.format import segments_text
from app.transcription.providers import _http

ID = "muse"
URL = "https://api.meta.ai/v1/asr/transcribe"
MODEL = "muse-voice-transcribe-1.0"
MAX_DURATION_MS = 10 * 60 * 1000
MAX_BYTES = 32 * 1024 * 1024
PART_MS = 570_000
KEYWORD_LIMIT = 100
WAV_MIME = "audio/wav"


class Muse:
    id = ID
    display_name = "Meta Muse"
    key_env = "META_MODEL_API_KEY"
    max_bytes: int | None = MAX_BYTES
    max_duration_ms: int | None = MAX_DURATION_MS
    chunkable = True
    needs_wav = True
    supports = Capabilities(
        diarize=True, segment_timestamps=True, language_bias=True, keyterm_limit=KEYWORD_LIMIT
    )
    price_per_hour_usd = 0.18

    def model(self, opts: SttOptions) -> str:
        return opts.models.get(ID) or MODEL

    def request_body(self, opts: SttOptions) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": self.model(opts),
            "audioEncoding": "WAV",
            "mode": "DIARIZATION" if opts.diarize else "TRANSCRIPTION",
        }
        if opts.language_bias:
            body["languageBias"] = list(opts.language_bias)
        if opts.keyterms:
            body["keywords"] = list(opts.keyterms[:KEYWORD_LIMIT])
        return body

    def transcribe(self, audio: AudioInput, opts: SttOptions, key: ApiKey) -> SttResult:
        if audio.mime_type != WAV_MIME:
            raise TooLarge(ID, None)
        if audio.size_bytes > MAX_BYTES or (audio.duration_ms or 0) > MAX_DURATION_MS:
            raise TooLarge(ID, None)

        def send() -> Any:
            with _http.upload_body(audio) as body:
                return _http.post(
                    ID,
                    URL,
                    params={"sessionId": opts.session_id} if opts.session_id else None,
                    headers={"Authorization": f"Bearer {key.value}"},
                    files={
                        "request": (None, json.dumps(self.request_body(opts)), "application/json"),
                        "audio": (audio.filename, body, WAV_MIME),
                    },
                    timeout=600,
                )

        response = send()
        if response.status_code == 500:
            response = send()
        _http.raise_for(ID, response)
        payload: dict[str, Any] = response.json() or {}
        segments = parse_segments(payload)
        text, speakers = segments_text(
            segments, "" if speakers_differ(segments) else str(payload.get("transcript") or "")
        )
        duration = payload.get("audioDurationMs")
        return SttResult(
            status="COMPLETED" if text else "EMPTY",
            text=text,
            provider=ID,
            model=self.model(opts),
            segments=segments if opts.want_segments else [],
            audio_ms=int(duration) if duration else audio.duration_ms,
            speakers=speakers,
            payload=payload if opts.keep_payload else None,
        )


def speakers_differ(segments: list[Segment]) -> bool:
    return len({segment.speaker for segment in segments if segment.speaker is not None}) > 1


def parse_segments(payload: dict[str, Any]) -> list[Segment]:
    segments: list[Segment] = []
    for turn in payload.get("turns") or []:
        text = str(turn.get("transcript") or "").strip()
        if not text:
            continue
        speaker = turn.get("speaker")
        segments.append(
            Segment(
                int(turn.get("startMs") or 0),
                int(turn.get("endMs") or turn.get("startMs") or 0),
                None if speaker is None else str(speaker),
                text,
            )
        )
    return segments
