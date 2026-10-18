"""Deepgram pre-recorded STT on Nova-3: multilingual, diarized, craft-vocabulary biased.

``language=multi`` is why Nova-3 fits: it transcribes code-switched Hindi and English without being
told when a sentence changes language. The one rung that truly streams: the audio is the raw
request body, so a temp file is handed to urllib3 and never sits in the heap.
"""

from __future__ import annotations

from typing import Any

from app.transcription.contract import (
    ApiKey,
    AudioInput,
    Capabilities,
    Segment,
    SttOptions,
    SttResult,
)
from app.transcription.format import diarized_markdown, speaker_count, speaker_turns
from app.transcription.providers import _http

ID = "deepgram"
URL = "https://api.deepgram.com/v1/listen"
MODEL = "nova-3"
KEYTERM_LIMIT = 50
# Budget in Deepgram tokens, kept under its 500 ceiling: a romanised craft word splits into more
# tokens than it looks like it should.
KEYTERM_TOKEN_BUDGET = 400


def keyterms_within_budget(terms: list[str] | tuple[str, ...]) -> list[str]:
    """Craft terms inside both Deepgram's term guidance and its 500-token request ceiling."""
    chosen: list[str] = []
    spent = 0
    for term in list(terms)[:KEYTERM_LIMIT]:
        cost = max(1, -(-len(term) // 3))  # ~3 characters a token, deliberately pessimistic
        if spent + cost > KEYTERM_TOKEN_BUDGET:
            break
        chosen.append(term)
        spent += cost
    return chosen


class Deepgram:
    id = ID
    display_name = "Deepgram"
    key_env = "DEEPGRAM_API_KEY"
    max_bytes: int | None = 2 * 1024 * 1024 * 1024
    max_duration_ms: int | None = None
    supports = Capabilities(
        diarize=True,
        word_timestamps=True,
        segment_timestamps=True,
        language_bias=False,
        keyterm_limit=KEYTERM_LIMIT,
    )
    price_per_hour_usd = 0.26

    def model(self, opts: SttOptions) -> str:
        return opts.models.get(ID) or MODEL

    def params(self, opts: SttOptions, *, conservative: bool) -> dict[str, Any]:
        """Query parameters. The boolean ``diarize`` and ``diarize_model`` never appear together:
        Deepgram rejects a request that carries both."""
        params: dict[str, Any] = {
            "model": self.model(opts),
            "language": "multi",
            "smart_format": "true",
        }
        if conservative:
            params["diarize"] = "true"
            return params
        params["paragraphs"] = "true"
        params["diarize_model"] = "latest"
        keyterms = keyterms_within_budget(opts.keyterms)
        if keyterms:
            params["keyterm"] = keyterms
        return params

    def transcribe(self, audio: AudioInput, opts: SttOptions, key: ApiKey) -> SttResult:
        def send(conservative: bool) -> Any:
            with _http.upload_body(audio) as body:
                return _http.post(
                    ID,
                    URL,
                    params=self.params(opts, conservative=conservative),
                    headers={
                        "Authorization": f"Token {key.value}",
                        "Content-Type": audio.mime_type or "application/octet-stream",
                    },
                    data=body,
                    timeout=600,
                )

        response = send(conservative=False)
        degraded = response.status_code in _http.OPTION_REJECTED_STATUSES
        if degraded:
            _http.log_option_retry(ID, response, "with the v1 diarizer and no term boosting")
            response = send(conservative=True)
        _http.raise_for(ID, response)
        payload = response.json() or {}
        text, speakers = parse_text(payload)
        duration = ((payload.get("metadata") or {}).get("duration")) or None
        return SttResult(
            status="COMPLETED" if text else "EMPTY",
            text=text,
            provider=ID,
            model=self.model(opts),
            segments=parse_segments(payload) if opts.want_segments else [],
            # Called with language=multi on purpose, so "multi" is the honest answer, not a detection.
            language="multi",
            audio_ms=int(float(duration) * 1000) if duration else None,
            speakers=speakers,
            degraded=degraded,
            payload=payload if opts.keep_payload else None,
        )


def _alternative(payload: dict[str, Any]) -> dict[str, Any]:
    channels = (payload.get("results") or {}).get("channels") or []
    alternatives = (channels[0].get("alternatives") if channels else None) or []
    return alternatives[0] if alternatives else {}


def parse_text(payload: dict[str, Any]) -> tuple[str, int]:
    """``(transcript, speakers)``: paragraphs first, then words, then the flat transcript."""
    alternative = _alternative(payload)
    fragments: list[tuple[Any, str]] = []
    for paragraph in (alternative.get("paragraphs") or {}).get("paragraphs") or []:
        sentences = paragraph.get("sentences") or []
        text = " ".join(str(sentence.get("text") or "").strip() for sentence in sentences).strip()
        if text:
            fragments.append((paragraph.get("speaker"), text))
    if not fragments:
        fragments = [
            (word.get("speaker"), str(word.get("punctuated_word") or word.get("word") or ""))
            for word in (alternative.get("words") or [])
        ]
    turns = speaker_turns(fragments)
    plain = str(alternative.get("transcript") or "").strip()
    return (diarized_markdown(turns) or plain), speaker_count(turns)


def parse_segments(payload: dict[str, Any]) -> list[Segment]:
    alternative = _alternative(payload)
    segments: list[Segment] = []
    for paragraph in (alternative.get("paragraphs") or {}).get("paragraphs") or []:
        sentences = paragraph.get("sentences") or []
        text = " ".join(str(sentence.get("text") or "").strip() for sentence in sentences).strip()
        if not text:
            continue
        start = paragraph.get("start", sentences[0].get("start") if sentences else 0) or 0
        end = paragraph.get("end", sentences[-1].get("end") if sentences else start) or start
        speaker = paragraph.get("speaker")
        segments.append(
            Segment(
                int(float(start) * 1000),
                int(float(end) * 1000),
                None if speaker is None else str(speaker),
                text,
            )
        )
    if segments:
        return segments
    for word in alternative.get("words") or []:
        text = str(word.get("punctuated_word") or word.get("word") or "").strip()
        if not text:
            continue
        speaker = word.get("speaker")
        speaker = None if speaker is None else str(speaker)
        start = int(float(word.get("start") or 0) * 1000)
        end = int(float(word.get("end") or word.get("start") or 0) * 1000)
        if segments and segments[-1].speaker == speaker:
            segments[-1].text = f"{segments[-1].text} {text}"
            segments[-1].end_ms = max(segments[-1].end_ms, end)
        else:
            segments.append(Segment(start, end, speaker, text))
    return segments
