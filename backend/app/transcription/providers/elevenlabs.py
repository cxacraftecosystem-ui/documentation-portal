"""ElevenLabs Scribe: the batch model, diarized, biased towards craft terms.

Takes files up to 1 GB whole, so nothing is chunked locally. ``language_code`` is never sent:
Scribe auto-detects, and these interviews code-switch into English mid-sentence and include
regional languages (Marwari, Garhwali) that have no code to name. ``tag_audio_events`` stays off so
workshop noise is not written into the speech as ``(banging)``.
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

ID = "elevenlabs"
URL = "https://api.elevenlabs.io/v1/speech-to-text"
MODEL = "scribe_v2"
LEGACY_MODEL = "scribe_v1"
KEYTERM_LIMIT = 100


class ElevenLabs:
    id = ID
    display_name = "ElevenLabs"
    key_env = "ELEVENLABS_API_KEY"
    max_bytes: int | None = 1000 * 1024 * 1024
    max_duration_ms: int | None = None
    supports = Capabilities(
        diarize=True, word_timestamps=True, segment_timestamps=True, keyterm_limit=KEYTERM_LIMIT
    )
    #: Scribe v2 pay-as-you-go (owner, 31 Oct 2026: design section 9).
    price_per_hour_usd = 0.22

    def model(self, opts: SttOptions) -> str:
        return opts.models.get(ID) or MODEL

    def fields(self, opts: SttOptions, *, conservative: bool) -> list[tuple[str, str]]:
        """Multipart fields. A list, not a dict: ``keyterms`` repeats one field name per term."""
        fields = [
            ("model_id", LEGACY_MODEL if conservative else self.model(opts)),
            ("diarize", "true"),
            ("tag_audio_events", "false"),
            # Speakers are reported per word, so word timestamps are what makes diarization readable.
            ("timestamps_granularity", "word"),
        ]
        if conservative:
            return fields
        # num_speakers stays unset: a wrong count forces voices to merge, which is worse than none.
        return fields + [("keyterms", term) for term in opts.keyterms[:KEYTERM_LIMIT]]

    def transcribe(self, audio: AudioInput, opts: SttOptions, key: ApiKey) -> SttResult:
        def send(conservative: bool) -> Any:
            with _http.upload_body(audio) as body:
                return _http.post(
                    ID,
                    URL,
                    headers={"xi-api-key": key.value},
                    data=self.fields(opts, conservative=conservative),
                    files={
                        "file": (
                            audio.filename,
                            body,
                            audio.mime_type or "application/octet-stream",
                        )
                    },
                    timeout=600,
                )

        response = send(conservative=False)
        degraded = response.status_code in _http.OPTION_REJECTED_STATUSES
        if degraded:
            _http.log_option_retry(ID, response, "without diarization extras or term boosting")
            response = send(conservative=True)
        _http.raise_for(ID, response)
        payload = response.json() or {}
        text, speakers = parse_text(payload)
        return SttResult(
            status="COMPLETED" if text else "EMPTY",
            text=text,
            provider=ID,
            model=LEGACY_MODEL if degraded else self.model(opts),
            segments=parse_segments(payload) if opts.want_segments else [],
            language=payload.get("language_code") or None,
            speakers=speakers,
            degraded=degraded,
            payload=payload if opts.keep_payload else None,
            words=parse_words(payload) if opts.want_segments else [],
        )


def _words(payload: dict[str, Any]) -> list[dict[str, Any]]:
    return [word for word in (payload.get("words") or []) if word.get("type") != "audio_event"]


_WORD_FIELDS = ("text", "start", "end", "type", "speaker_id", "logprob")


def parse_words(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Scribe's words (spacing included, audio events left out), trimmed to the fields turns use."""
    return [
        {name: word.get(name) for name in _WORD_FIELDS if word.get(name) is not None}
        for word in _words(payload)
    ]


def parse_text(payload: dict[str, Any]) -> tuple[str, int]:
    """``(transcript, speakers)``: speaker-labelled when several voices spoke, else the flat text."""
    fragments = [(word.get("speaker_id"), str(word.get("text") or "")) for word in _words(payload)]
    turns = speaker_turns(fragments)
    plain = str(payload.get("text") or "").strip()
    return (diarized_markdown(turns) or plain), speaker_count(turns)


def parse_segments(payload: dict[str, Any]) -> list[Segment]:
    """One segment per uninterrupted stretch of a voice, timed from its first and last word."""
    segments: list[Segment] = []
    for word in _words(payload):
        if word.get("type") == "spacing":
            continue
        text = str(word.get("text") or "").strip()
        if not text:
            continue
        speaker = word.get("speaker_id")
        start = int(float(word.get("start") or 0) * 1000)
        end = int(float(word.get("end") or word.get("start") or 0) * 1000)
        if segments and segments[-1].speaker == speaker:
            segments[-1].text = f"{segments[-1].text} {text}"
            segments[-1].end_ms = max(segments[-1].end_ms, end)
        else:
            segments.append(Segment(start, end, speaker, text))
    return segments
