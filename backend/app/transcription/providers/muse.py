"""Meta Muse Voice Transcribe 1.0: the fourth provider, OFF by default.

The design's verdict (section 3): Meta's public speech-to-text API is Muse Voice Transcribe, not
Muse Spark (Spark is the multimodal LLM; Meta points audio work away from it).

* ``POST https://api.meta.ai/v1/asr/transcribe?sessionId=<id>``, ``Authorization: Bearer <key>``.
* Multipart: ``request`` (JSON: model, audioEncoding, mode, languageBias, keywords) and ``audio``.
* Mono 16-bit PCM WAV at 16 kHz, at most 10 minutes and 32 MB per request. The chain normalises
  and cuts every recording into parts of 9.5 minutes or less before this adapter sees it.
* Answers ``transcript``, ``audioDurationMs`` and ``turns`` (turn-level timings, no word timings).
* 400 bad format or too long, 413 too large, 429 rate or concurrency limit, 500 processing budget
  exceeded (retried once here).

Its processing location is not documented, so it ships disabled until the owner and the Ministry
accept that, as they did for ElevenLabs and Deepgram. Key: ``META_MODEL_API_KEY``.
"""

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
#: What the chain cuts to: under both ceilings with room for the one-second overlap.
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
    #: Only normalised 16 kHz mono PCM16 WAV is accepted; the chain converts everything first.
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
            # Never upload a format Muse refuses; the chain converts first, so this is a bug guard.
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
            # Meta documents a 500 as "processing budget exceeded" and says to retry once.
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
