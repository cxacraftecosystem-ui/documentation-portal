import asyncio
import base64
import json
import logging
import re
import threading
from functools import lru_cache
from pathlib import Path
from typing import Any

import requests
from fastapi import UploadFile

from app.core.config import Settings
from app.services import app_settings, managed_secrets

logger = logging.getLogger(__name__)

# Provider keys are resolved through app.services.managed_secrets, NOT read off Settings, so a key
# rotated in the Settings hub takes effect on the next call instead of the next restart. Everything
# else (model ids, chunk sizes) still comes from Settings — those are deploy-time choices.
#
# The lookups below are the SYNCHRONOUS `peek_secret`, because most of them run inside
# `asyncio.to_thread` where awaiting Prisma is impossible. That is safe only because every async
# entry point in this module primes the cache with `refresh_if_stale()` before handing off to a
# thread; without priming, `peek_secret` degrades to the environment value, i.e. the old behaviour.


def _key(name: str) -> str:
    """Effective value of a provider key, or "" when unconfigured.

    Empty rather than None so a header value is always a string: if a key somehow disappears between
    the provider-chain check and the request, the provider answers 401 and the chain falls through to
    the next provider — far better than a TypeError crashing the whole transcription job.
    """
    return managed_secrets.peek_secret(name) or ""


# --- What a provider failure is allowed to say ----------------------------------------------------
#
# ``requests`` builds every one of its exception messages out of the PREPARED request, URL and query
# string included. A provider authenticated by query parameter therefore puts its API key inside
# ``str(exc)`` — and the results below travel a long way: the measurement result is returned verbatim
# as the JSON body of POST /media/analyze-measurement (any signed-in account, down to a crowdsource
# volunteer) and is also written by media_queue into ``MediaFile.extraMetadata.measurementProcessing``,
# which is stored and served with the media row. The Gemini key went both ways until it moved into a
# header (see ``_post_gemini_measurement``).
#
# So no caller of this module ever receives a provider's own words. It gets the two facts it can act
# on — which provider, and how it failed — while the response body stays in the server log. The
# redaction below is the backstop for the next integration written without this in mind, and for a
# provider that echoes a credential back at us.

_URL_SECRET = re.compile(
    r"((?:key|api[-_]?key|access[-_]?token|token|secret|sig|signature)=)[^&\s\"'>]+",
    re.IGNORECASE,
)


def redact_secrets(text: str) -> str:
    """Blank out the value of any credential-looking query parameter in *text*.

    Applied to everything derived from a provider exception before it is logged — and exported for
    ``media_queue``, which writes an arbitrary job exception straight into a column.
    """
    return _URL_SECRET.sub(r"\1REDACTED", text)


def _fault(exc: Exception) -> str:
    """How a provider failed, in the only terms safe to repeat: the status it answered with, or the
    class of transport error."""
    code = getattr(getattr(exc, "response", None), "status_code", None)
    return f"HTTP {code}" if code else f"unreachable ({type(exc).__name__})"


# HTTP statuses that mean "this key won't work right now" (quota, auth, bad key) -> rotate to next.
_GEMINI_ROTATE_STATUSES = {400, 401, 403, 429, 500, 503}

_gemini_key_lock = threading.Lock()
_gemini_key_counter = 0


def _next_gemini_start(num_keys: int) -> int:
    """Round-robin starting offset so load spreads across free-tier keys across calls."""
    global _gemini_key_counter
    if num_keys <= 0:
        return 0
    with _gemini_key_lock:
        start = _gemini_key_counter % num_keys
        _gemini_key_counter = (_gemini_key_counter + 1) % num_keys
    return start


# Whisper rejects files at/over 25 MB. Stay comfortably under it, and split anything larger into
# ~10-minute mono segments that are transcribed sequentially and stitched back together.
WHISPER_MAX_BYTES = 24 * 1024 * 1024
TRANSCRIPTION_CHUNK_MS = 10 * 60 * 1000
# Dedicated STT providers accept far larger uploads than Whisper, so they skip local chunking.
ELEVENLABS_MAX_BYTES = 1000 * 1024 * 1024
DEEPGRAM_MAX_BYTES = 2 * 1024 * 1024 * 1024


def _transcription_result(text: str, payload: Any = None) -> dict[str, Any]:
    return {
        "available": True,
        "status": "COMPLETED" if text else "EMPTY",
        "text": text,
        "formattedTranscript": f"Transcript\n\n{text}" if text else "",
        "raw": payload,
    }


# --- Craft vocabulary ---------------------------------------------------------------------------
#
# What the recordings actually contain decides these settings. They are field interviews with
# artisans, mostly Hindi code-switched with English mid-sentence, recorded next to looms, hammers
# and kilns, and 8 of the 25 interviews on record seat two to five artisans at once. So the
# vocabulary below is boosted rather than left to a general model, which writes "dabu" as "double"
# and "ringal" as "ring all" — and once a craft's name is wrong, the transcript is unsearchable for
# exactly the term a researcher will look for.

_CRAFT_VOCABULARY_PATH = Path(__file__).resolve().parents[1] / "data" / "craft_vocabulary.txt"

# ElevenLabs refuses a keyterm over 50 characters or 5 words and takes at most 100 of them.
# Deepgram caps keyterm prompting at 500 tokens per request and advises 20-50 terms, past which the
# boost dilutes; the file is ordered most-distinctive-first so its truncation drops the terms a
# general model was likeliest to get right unaided.
_KEYTERM_MAX_CHARS = 50
_KEYTERM_MAX_WORDS = 5
_ELEVENLABS_KEYTERM_LIMIT = 100
_DEEPGRAM_KEYTERM_LIMIT = 50
# Budget in Deepgram tokens, kept under the 500 ceiling because a romanised craft word splits into
# more tokens than it looks like it should ("jamboori" is not one token anywhere).
_DEEPGRAM_KEYTERM_TOKEN_BUDGET = 400


@lru_cache(maxsize=1)
def craft_keyterms() -> tuple[str, ...]:
    """The craft terms handed to providers that support term boosting, in file order.

    The list lives in ``app/data/craft_vocabulary.txt`` rather than in this module so a researcher
    can add a technique, a tool or a village without touching Python — the words come from the
    fieldwork, and the people who know them are not the people who deploy the API.

    A missing or unreadable file is not an error: the providers are then asked without a vocabulary,
    which is exactly the behaviour that shipped before boosting existed.
    """
    try:
        raw = _CRAFT_VOCABULARY_PATH.read_text(encoding="utf-8")
    except OSError as exc:
        logger.warning("Craft vocabulary unreadable (%s); transcribing without term boosting", exc)
        return ()
    terms: list[str] = []
    seen: set[str] = set()
    rejected = 0
    for line in raw.splitlines():
        term = line.split("#", 1)[0].strip()
        if not term:
            continue
        if len(term) > _KEYTERM_MAX_CHARS or len(term.split()) > _KEYTERM_MAX_WORDS:
            rejected += 1  # a provider would reject the whole request over one over-long line
            continue
        if term.casefold() in seen:
            continue
        seen.add(term.casefold())
        terms.append(term)
    if rejected:
        logger.warning(
            "%s craft vocabulary entries skipped: a term may be at most %s characters and %s words",
            rejected,
            _KEYTERM_MAX_CHARS,
            _KEYTERM_MAX_WORDS,
        )
    return tuple(terms)


def _deepgram_keyterms() -> list[str]:
    """Craft terms for Deepgram, inside both its term guidance and its 500-token request ceiling."""
    chosen: list[str] = []
    spent = 0
    for term in craft_keyterms()[:_DEEPGRAM_KEYTERM_LIMIT]:
        cost = max(1, -(-len(term) // 3))  # ~3 characters a token, deliberately pessimistic
        if spent + cost > _DEEPGRAM_KEYTERM_TOKEN_BUDGET:
            break
        chosen.append(term)
        spent += cost
    return chosen


# --- Diarization ------------------------------------------------------------------------------
#
# A group sitting transcribed as one voice is a wall of text a researcher has to re-attribute by
# ear, so both dedicated providers are asked to diarize and the speakers are carried into the text
# itself — a speaker label nobody can see is not worth requesting.
#
# The label shape is fixed by the far end: services/transcript_format.py recognises a speaker turn
# as a BOLD span ending in a colon (``**Speaker 1:**``, at most 60 characters) at the start of a
# line, and renders it as a real bold run with its own line in the Excel export. The refinement
# pass then rewrites these into ``**Interviewer:**`` / ``**Interviewee 2:**`` where it can tell who
# is who. Change the shape here and the export silently goes back to one unbroken paragraph.


def _speaker_turns(fragments: list[tuple[Any, str]]) -> list[tuple[Any, str]]:
    """``(speaker, fragment)`` pairs merged into one turn per uninterrupted stretch of a voice."""
    turns: list[tuple[Any, str]] = []
    for speaker, fragment in fragments:
        text = fragment.strip()
        if not text:
            continue
        if turns and turns[-1][0] == speaker:
            turns[-1] = (speaker, f"{turns[-1][1]} {text}")
        else:
            turns.append((speaker, text))
    return turns


def _speaker_count(turns: list[tuple[Any, str]]) -> int:
    return len({speaker for speaker, _ in turns if speaker is not None})


def _diarized_markdown(turns: list[tuple[Any, str]]) -> str | None:
    """Turns as ``**Speaker 1:** …`` paragraphs, or None when only one voice was heard.

    Speakers are numbered in order of first appearance rather than by the provider's own id, which
    is arbitrary and can skip values — "Speaker 3" in a transcript with two voices reads as a
    mistake. A solo interview returns None so it is not decorated with a label that says nothing.
    """
    order: list[Any] = []
    for speaker, _ in turns:
        if speaker is not None and speaker not in order:
            order.append(speaker)
    if len(order) < 2:
        return None
    numbers = {speaker: index + 1 for index, speaker in enumerate(order)}
    return "\n\n".join(
        f"**Speaker {numbers[speaker]}:** {text}" if speaker in numbers else text
        for speaker, text in turns
    )


def _post_openai_transcription(content: bytes, filename: str, mime_type: str, settings: Settings) -> dict[str, Any]:
    response = requests.post(
        "https://api.openai.com/v1/audio/transcriptions",
        headers={"Authorization": f"Bearer {_key('OPENAI_API_KEY')}"},
        data={"model": settings.openai_transcription_model, "response_format": "json"},
        files={"file": (filename, content, mime_type or "application/octet-stream")},
        timeout=180,
    )
    response.raise_for_status()
    payload = response.json()
    text = str(payload.get("text") or "").strip()
    return _transcription_result(text, payload)


# A 400/422 means the provider refused the REQUEST, not the audio in it — a renamed option, a model
# id retired since this was written. Rather than let that drop a whole provider out of the chain
# silently, each one retries ONCE with the option set that predates this work: the transcript comes
# back without diarization or boosting instead of not at all, and the log says so. The cost is a
# second upload of the same bytes, paid only on a refusal and never twice.
_OPTION_REJECTED_STATUSES = {400, 422}

# Scribe v2 is the current batch model, and every one of its advantages is one this audio needs:
# it is the long-form model (interviews run past an hour), it diarizes up to 32 speakers where v1
# fragmented on pauses and tone changes, and keyterm biasing is a v2 feature. config.py still
# defaults ELEVENLABS_STT_MODEL to scribe_v1 — the only model that existed when this integration was
# written — and that file belongs to another change, so the historical default is treated here as
# "never chosen". Any other value set in the environment wins; pinning scribe_v1 deliberately needs
# that default in config.py to move first.
_ELEVENLABS_MODEL = "scribe_v2"
_ELEVENLABS_LEGACY_MODEL = "scribe_v1"


def _elevenlabs_model(settings: Settings) -> str:
    configured = (settings.elevenlabs_stt_model or "").strip()
    return configured if configured and configured != _ELEVENLABS_LEGACY_MODEL else _ELEVENLABS_MODEL


def _elevenlabs_fields(settings: Settings, *, conservative: bool) -> list[tuple[str, str]]:
    """The multipart fields for one Scribe request. A list, not a dict: ``keyterms`` is an array
    parameter and multipart carries an array as the same field name repeated.

    ``language_code`` is deliberately absent. Scribe auto-detects, and naming a language would be a
    worse guess than its own: these interviews code-switch into English mid-sentence, and several
    are in regional languages (Marwari, Garhwali) that have no code to name.

    ``tag_audio_events`` stays off. It is on by default and it would interleave the workshop —
    hammering, a passing motorbike — into the speech as ``(banging)``, which the refinement pass
    then has to carry through translation as if someone had said it.
    """
    fields = [
        ("model_id", _ELEVENLABS_LEGACY_MODEL if conservative else _elevenlabs_model(settings)),
        ("diarize", "true"),
        ("tag_audio_events", "false"),
        # Speakers are reported per word, so word timestamps are what makes diarization readable.
        ("timestamps_granularity", "word"),
    ]
    if conservative:
        return fields
    # num_speakers is left unset on purpose: the sittings on record run from one artisan to five
    # plus an interviewer, and a wrong count is worse than none — it forces voices to merge.
    return fields + [("keyterms", term) for term in craft_keyterms()[:_ELEVENLABS_KEYTERM_LIMIT]]


def _elevenlabs_text(payload: dict[str, Any]) -> tuple[str, int]:
    """``(transcript, speakers)`` from a Scribe response, speaker-labelled when several voices spoke.

    Words carry ``speaker_id``; the spacing entries between them carry the whitespace, and
    ``audio_event`` entries are dropped in case tagging is ever turned back on. Falling back to the
    flat ``text`` field keeps a non-diarized response working exactly as it used to.
    """
    fragments = [
        (word.get("speaker_id"), str(word.get("text") or ""))
        for word in (payload.get("words") or [])
        if word.get("type") != "audio_event"
    ]
    turns = _speaker_turns(fragments)
    plain = str(payload.get("text") or "").strip()
    return (_diarized_markdown(turns) or plain), _speaker_count(turns)


def _post_elevenlabs_transcription(content: bytes, filename: str, mime_type: str, settings: Settings) -> dict[str, Any]:
    """ElevenLabs Scribe v2 speech-to-text: the batch model, diarized, biased towards craft terms.

    Accepts files up to 3 GB and 10 hours, so nothing is chunked locally.
    """

    def send(conservative: bool) -> Any:
        return requests.post(
            "https://api.elevenlabs.io/v1/speech-to-text",
            headers={"xi-api-key": _key("ELEVENLABS_API_KEY")},
            data=_elevenlabs_fields(settings, conservative=conservative),
            files={"file": (filename, content, mime_type or "application/octet-stream")},
            timeout=600,
        )

    response = send(conservative=False)
    degraded = response.status_code in _OPTION_REJECTED_STATUSES
    if degraded:
        logger.warning(
            "ElevenLabs rejected the request options (HTTP %s: %s); retrying without diarization "
            "extras or term boosting",
            response.status_code,
            str(response.text)[:200],
        )
        response = send(conservative=True)
    response.raise_for_status()
    payload = response.json()
    text, speakers = _elevenlabs_text(payload)
    result = _transcription_result(text, None)  # word-level payload is huge; don't persist it
    result["model"] = _ELEVENLABS_LEGACY_MODEL if degraded else _elevenlabs_model(settings)
    result["speakers"] = speakers
    if payload.get("language_code"):
        result["languageCode"] = payload.get("language_code")
    return result


def _deepgram_params(settings: Settings, *, conservative: bool) -> dict[str, Any]:
    """Query parameters for one Deepgram pre-recorded request.

    ``language=multi`` is the whole reason Nova-3 is the right model here: it transcribes
    code-switched audio across ten languages including Hindi, without being told when a sentence
    changes language. Naming ``language=hi`` instead would force every English clause through a
    Hindi decoder, and these interviews switch several times a minute.

    ``smart_format`` brings punctuation, paragraphing and numeral formatting; ``paragraphs`` is
    asked for explicitly because smart formatting only promises its extras "where available" for
    non-English audio, and the paragraph objects are what carry a speaker per block of speech.
    """
    params: dict[str, Any] = {
        "model": settings.deepgram_stt_model,
        "language": "multi",
        "smart_format": "true",
    }
    if conservative:
        # The deprecated boolean, which routes to the v1 diarizer. Deepgram REJECTS a request that
        # sets both this and diarize_model, so the two forms can never appear together.
        params["diarize"] = "true"
        return params
    params["paragraphs"] = "true"
    params["diarize_model"] = "latest"
    keyterms = _deepgram_keyterms()
    if keyterms:
        # Keyterm prompting is Nova-3's replacement for the old weighted `keywords`, which Nova-3
        # ignores. Repeating the parameter is how the array is expressed in a query string.
        params["keyterm"] = keyterms
    return params


def _deepgram_text(payload: dict[str, Any]) -> tuple[str, int]:
    """``(transcript, speakers)`` from a Deepgram response, speaker-labelled where voices differ.

    Paragraphs are preferred over words: they already group a speaker's sentences into blocks, so
    the turns read as speech rather than as a re-assembled word list. Words are the fallback for a
    response that carries diarization without paragraph formatting, and the flat ``transcript``
    field for one that carries neither.
    """
    channels = (payload.get("results") or {}).get("channels") or []
    alternatives = (channels[0].get("alternatives") if channels else None) or []
    alternative = alternatives[0] if alternatives else {}

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

    turns = _speaker_turns(fragments)
    plain = str(alternative.get("transcript") or "").strip()
    return (_diarized_markdown(turns) or plain), _speaker_count(turns)


def _post_deepgram_transcription(content: bytes, filename: str, mime_type: str, settings: Settings) -> dict[str, Any]:
    """Deepgram pre-recorded STT on Nova-3, multilingual, diarized and craft-vocabulary biased."""

    def send(conservative: bool) -> Any:
        return requests.post(
            "https://api.deepgram.com/v1/listen",
            params=_deepgram_params(settings, conservative=conservative),
            headers={
                "Authorization": f"Token {_key('DEEPGRAM_API_KEY')}",
                "Content-Type": mime_type or "application/octet-stream",
            },
            data=content,
            timeout=600,
        )

    response = send(conservative=False)
    degraded = response.status_code in _OPTION_REJECTED_STATUSES
    if degraded:
        logger.warning(
            "Deepgram rejected the request options (HTTP %s: %s); retrying with the v1 diarizer and "
            "no term boosting",
            response.status_code,
            str(response.text)[:200],
        )
        response = send(conservative=True)
    response.raise_for_status()
    payload = response.json()
    text, speakers = _deepgram_text(payload)
    result = _transcription_result(text, None)  # word/paragraph payload is huge; don't persist it
    result["model"] = settings.deepgram_stt_model
    result["speakers"] = speakers
    return result


def _split_audio_into_chunks(content: bytes) -> list[tuple[bytes, str, str]] | None:
    """Split audio into <=10-minute mono MP3 chunks, each safely under the Whisper size limit.

    Returns a list of ``(bytes, filename, mime_type)`` or ``None`` when splitting is not possible
    (pydub/ffmpeg unavailable or the bytes can't be decoded) — the caller then falls back to a
    single-shot upload.
    """
    try:
        import io

        from pydub import AudioSegment
    except Exception:  # noqa: BLE001 - missing optional dependency
        logger.warning("pydub/ffmpeg unavailable; long audio cannot be chunked for transcription")
        return None
    try:
        audio = AudioSegment.from_file(io.BytesIO(content))
    except Exception as exc:  # noqa: BLE001 - undecodable container
        logger.warning("Unable to decode audio for chunked transcription: %s", exc)
        return None

    chunks: list[tuple[bytes, str, str]] = []
    for index, start in enumerate(range(0, max(len(audio), 1), TRANSCRIPTION_CHUNK_MS)):
        segment = audio[start : start + TRANSCRIPTION_CHUNK_MS].set_channels(1)
        buffer = io.BytesIO()
        segment.export(buffer, format="mp3", bitrate="64k")
        chunks.append((buffer.getvalue(), f"chunk-{index + 1:03d}.mp3", "audio/mpeg"))
    return chunks or None


def _transcribe_whisper_sync(content: bytes, filename: str, mime_type: str, settings: Settings) -> dict[str, Any]:
    """Whisper path: one shot when small; otherwise chunk, transcribe sequentially, and stitch."""
    if len(content) <= WHISPER_MAX_BYTES:
        return _post_openai_transcription(content, filename, mime_type, settings)

    chunks = _split_audio_into_chunks(content)
    if not chunks:
        # Can't split locally — attempt the whole file so the failure (if any) surfaces honestly.
        return _post_openai_transcription(content, filename, mime_type, settings)

    pieces: list[str] = []
    for chunk_bytes, chunk_name, chunk_mime in chunks:
        result = _post_openai_transcription(chunk_bytes, chunk_name, chunk_mime, settings)
        piece = str(result.get("text") or "").strip()
        if piece:
            pieces.append(piece)
    text = " ".join(pieces).strip()
    result = _transcription_result(text, None)
    result["chunks"] = len(chunks)
    return result


_PROVIDER_KEYS = {
    "elevenlabs": "ELEVENLABS_API_KEY",
    "deepgram": "DEEPGRAM_API_KEY",
    "whisper": "OPENAI_API_KEY",
}

# How each engine is named to a human. Here rather than in the web client because the Settings hub
# and the Android screen must not be able to drift into calling the same engine two different things.
_PROVIDER_NAMES = {
    "elevenlabs": "ElevenLabs",
    "deepgram": "Deepgram",
    "whisper": "Whisper (OpenAI)",
}


def transcription_provider_catalog() -> list[tuple[str, str, str]]:
    """``(id, display name, key name)`` for every engine the chain knows, in default order."""
    return [(p, _PROVIDER_NAMES[p], key) for p, key in _PROVIDER_KEYS.items()]


def transcription_provider_configured() -> dict[str, bool]:
    """Which engines currently have a usable key, keyed by provider id.

    Resolved through the same ``_key`` the chain itself uses, so the "configured" dot in the ranking
    UI cannot disagree with the provider that actually gets called. The caller must have primed the
    managed-secret cache (``refresh_if_stale``) first, exactly as the transcription path does.
    """
    return {provider: bool(_key(name)) for provider, name in _PROVIDER_KEYS.items()}


def transcription_provider_chain(
    settings: Settings | None = None,
    order: list[str] | None = None,
) -> list[str]:
    """Configured STT providers in priority order, skipping every one whose key is unset.

    ``order`` is the master admin's ranking (see ``app_settings.stt_provider_order``); omitting it
    falls back to the order that applied before ranking existed. A provider is dropped wherever it
    sits the moment its key is missing — ranking expresses a preference, not a requirement, so
    promoting Deepgram on a deployment that has no Deepgram key must not stop transcription.

    ``settings`` is accepted but unused — the keys that decide the chain now come from the managed
    secret layer, so adding a Deepgram key in the UI extends the chain immediately. The parameter is
    kept so existing callers (and the sync transcription path, which passes it along) don't break.
    """
    ranked = order if order is not None else list(app_settings.DEFAULT_STT_PROVIDER_ORDER)
    return [p for p in ranked if p in _PROVIDER_KEYS and _key(_PROVIDER_KEYS[p])]


_PROVIDER_CALLS = {
    "elevenlabs": (_post_elevenlabs_transcription, ELEVENLABS_MAX_BYTES),
    "deepgram": (_post_deepgram_transcription, DEEPGRAM_MAX_BYTES),
    "whisper": (_transcribe_whisper_sync, None),  # chunks internally, no hard cap
}


# How an HTTP failure from a provider is read. The three cases are genuinely different and the
# queue treats them differently, so they are separated here rather than at the call site:
#
#   401/403  the key is wrong or revoked. Every retry of this job will be rejected identically, so
#            it counts as a hard failure — the job must be allowed to terminate — but the message
#            names the key an admin has to fix instead of repeating an HTTP status at them.
#   429/503  the provider said "come back later" in the two ways it can say it. Returned as
#            RATE_LIMITED, which media_queue requeues WITHOUT consuming an attempt and behind a
#            growing cooldown, so a throttled clip is still transcribed eventually.
#   5xx      the provider broke on THIS request. Retrying the same bytes forever would leave the
#            job queued for good, so it is a hard failure and the job's normal attempt budget and
#            backoff apply — the difference from 503 is "you broke" versus "I am busy".
_AUTH_STATUSES = {401, 403}
_DEFER_STATUSES = {429, 503}


def _rate_limited_result(provider: str, response: Any, code: int) -> dict[str, Any]:
    retry_after = None
    if response is not None:
        try:
            header = response.headers.get("Retry-After")
            retry_after = float(header) if header else None
        except (TypeError, ValueError):
            retry_after = None
    reason = "rate-limited" if code == 429 else "temporarily unavailable"
    return {
        "available": True,
        "status": "RATE_LIMITED",
        "text": None,
        "formattedTranscript": None,
        "retryAfter": retry_after,
        "provider": provider,
        "message": f"{provider} transcription {reason} (HTTP {code}); will retry automatically.",
    }


def _transcribe_sync(
    content: bytes,
    filename: str,
    mime_type: str,
    settings: Settings,
    chain: list[str],
) -> dict[str, Any]:
    """Walk *chain* until one provider produces a transcript.

    A provider that hard-fails or is throttled falls through to the next; an EMPTY result is kept as
    a fallback but the next provider still gets a chance (codecs/languages one engine can't decode are
    sometimes fine on another). Resolution when nothing returned text: a definitive EMPTY wins (the
    clip is silent — done); a PURE throttle (no hard failures) returns RATE_LIMITED so the queue backs
    off without burning attempts; a throttle mixed with hard failures returns FAILED so the job's
    normal retry/backoff applies and a permanently-broken clip still terminates after maxAttempts.
    """
    rate_limited: dict[str, Any] | None = None
    empty: dict[str, Any] | None = None
    errors: list[str] = []
    for provider in chain:
        call, max_bytes = _PROVIDER_CALLS[provider]
        if max_bytes is not None and len(content) > max_bytes:
            errors.append(f"{provider}: file larger than the provider limit")
            continue
        try:
            result = call(content, filename, mime_type, settings)
        except requests.HTTPError as exc:
            response = exc.response
            code = response.status_code if response is not None else None
            if code in _DEFER_STATUSES:
                rate_limited = rate_limited or _rate_limited_result(provider, response, code)
                logger.warning("%s transcription throttled (HTTP %s); trying next provider", provider, code)
            elif code in _AUTH_STATUSES:
                key_name = _PROVIDER_KEYS.get(provider, "the provider key")
                errors.append(
                    f"{provider}: API key rejected (HTTP {code}); set a working {key_name} in Settings"
                )
                logger.error(
                    "%s rejected the configured API key (HTTP %s); trying next provider", provider, code
                )
            else:
                errors.append(f"{provider}: {_fault(exc)}")
                logger.warning(
                    "%s transcription failed (%s); trying next provider",
                    provider,
                    redact_secrets(str(exc)),
                )
            continue
        except requests.RequestException as exc:
            errors.append(f"{provider}: {_fault(exc)}")
            logger.warning(
                "%s transcription network error (%s); trying next provider",
                provider,
                redact_secrets(str(exc)),
            )
            continue
        if result.get("status") == "COMPLETED":
            result["provider"] = provider
            return result
        if result.get("status") == "EMPTY" and empty is None:
            result["provider"] = provider
            empty = result
    if empty:
        return empty
    if rate_limited and not errors:
        return rate_limited
    if rate_limited:
        errors.append(str(rate_limited.get("message")))
    return {
        "available": True,
        "status": "FAILED",
        "text": None,
        "formattedTranscript": None,
        "message": "; ".join(errors) or "All transcription providers failed.",
    }


async def transcribe_audio(file: UploadFile, settings: Settings) -> dict[str, Any]:
    content = await file.read()
    return await transcribe_audio_bytes(
        content,
        file.filename or "recording.webm",
        file.content_type or "audio/webm",
        settings,
    )


async def transcribe_audio_bytes(
    content: bytes,
    filename: str,
    mime_type: str,
    settings: Settings,
) -> dict[str, Any]:
    # Prime the managed-secret cache on the event loop BEFORE any thread hop, so both the provider
    # chain below and the header reads inside the thread see keys saved in the UI.
    await managed_secrets.refresh_if_stale()
    # Resolve the chain here, per job, and hand it to the thread: the ranking lives in the database
    # and awaiting it is impossible once inside `to_thread`. Reading it now is also what makes a
    # reorder apply to the very next job in both the API and the queue process, with no restart.
    chain = transcription_provider_chain(settings, await app_settings.load_stt_provider_order())
    if not chain:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "text": None,
            "formattedTranscript": None,
            "message": (
                "Transcription unavailable: configure ELEVENLABS_API_KEY, DEEPGRAM_API_KEY, "
                "or OPENAI_API_KEY."
            ),
        }
    try:
        return await asyncio.to_thread(
            _transcribe_sync,
            content,
            filename,
            mime_type,
            settings,
            chain,
        )
    except requests.HTTPError as exc:
        # A 429 (or a 503 "overloaded") is transient throttling, not a real failure — surface it as
        # RATE_LIMITED so the queue backs off and retries WITHOUT consuming the job's attempts (so the
        # clip is transcribed eventually). Honour a Retry-After header when the provider sends one.
        response = exc.response
        code = response.status_code if response is not None else None
        if code in _DEFER_STATUSES:
            retry_after = None
            if response is not None:
                try:
                    retry_after = float(response.headers.get("Retry-After")) if response.headers.get("Retry-After") else None
                except (TypeError, ValueError):
                    retry_after = None
            return {
                "available": True,
                "status": "RATE_LIMITED",
                "text": None,
                "formattedTranscript": None,
                "retryAfter": retry_after,
                "message": f"Transcription rate-limited (HTTP {code}); will retry automatically.",
            }
        logger.error("Transcription failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "text": None,
            "formattedTranscript": None,
            "message": f"Transcription failed ({_fault(exc)}). The provider's reply is in the server log.",
        }
    except requests.RequestException as exc:
        logger.error("Transcription failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "text": None,
            "formattedTranscript": None,
            "message": f"Transcription failed ({_fault(exc)}). The provider's reply is in the server log.",
        }


# --- Transcript refinement (raw transcript -> clean interviewer/interviewee conversation) ----------

# Hard cap on the transcript we send to the chat model, so a runaway transcript can't blow up the
# token bill or the request. ~48k characters is well within gpt-4o-mini's context window.
_REFINE_MAX_CHARS = 48_000


def _post_openai_chat(messages: list[dict[str, str]], settings: Settings) -> str:
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {_key('OPENAI_API_KEY')}",
            "Content-Type": "application/json",
        },
        json={
            "model": settings.openai_chat_model,
            "messages": messages,
            "temperature": 0.2,
        },
        timeout=120,
    )
    response.raise_for_status()
    payload = response.json()
    return str(payload["choices"][0]["message"]["content"]).strip()


def _refine_sync(text: str, translate_to_english: bool, settings: Settings) -> dict[str, Any]:
    clipped = text.strip()[:_REFINE_MAX_CHARS]
    translate_clause = (
        " Then translate the entire conversation into clear, natural English, preserving meaning."
        if translate_to_english
        else ""
    )
    system = (
        "You are an expert interview transcript editor. You reformat a raw, unpunctuated speech-to-text "
        "transcript into a clean, readable dialogue. An interview may involve one interviewer (or more) "
        "and ONE OR MORE interviewees. You fix obvious transcription errors, punctuation and "
        "capitalisation, and split the text into speaker turns. You NEVER invent, add, or remove "
        "information — only restructure and lightly correct what is present. If the speaker of a passage "
        "is genuinely unclear, label it **Speaker:**."
    )
    user = (
        "Reformat the following raw interview transcript into a conversation using Markdown. Put each "
        "turn on its own line, beginning with a bold speaker label, followed by that turn's text. Use "
        "`**Interviewer:**` for the interviewer. There may be MULTIPLE interviewees — when you can tell "
        "them apart, label them `**Interviewee 1:**`, `**Interviewee 2:**`, etc.; if there is clearly "
        "only one, use `**Interviewee:**`. The raw transcript may ALREADY carry `**Speaker 1:**`-style "
        "labels from automatic speaker separation: keep those turn boundaries and rename each speaker "
        "to its role, rather than re-splitting the text yourself. Separate clearly distinct topics or "
        "sections with a Markdown horizontal rule on its own line (`---`). Keep it faithful to the "
        "source." + translate_clause
        + "\n\nRaw transcript:\n\n" + clipped
    )
    refined = _post_openai_chat(
        [{"role": "system", "content": system}, {"role": "user", "content": user}],
        settings,
    )
    return {
        "available": True,
        "status": "COMPLETED" if refined else "EMPTY",
        "refined": refined,
        "model": settings.openai_chat_model,
        "translated": translate_to_english,
    }


async def refine_transcript_text(
    text: str | None,
    translate_to_english: bool,
    settings: Settings,
) -> dict[str, Any]:
    """Refine a raw transcript into a clean interviewer/interviewee conversation (Markdown), optionally
    translating it to English. Uses the configured chat model (gpt-4o-mini by default)."""
    if not await managed_secrets.get_secret("OPENAI_API_KEY"):
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "refined": None,
            "message": "Refinement unavailable because OPENAI_API_KEY is not configured.",
        }
    if not text or not text.strip():
        return {
            "available": True,
            "status": "EMPTY",
            "refined": None,
            "message": "There is no transcript text to refine yet.",
        }
    try:
        return await asyncio.to_thread(_refine_sync, text, translate_to_english, settings)
    except requests.RequestException as exc:
        logger.error("Transcript refinement failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "refined": None,
            "message": (
                f"Refinement failed ({_fault(exc)}). The raw transcript is unchanged; the "
                "provider's reply is in the server log."
            ),
        }


def _extract_json(text: str) -> dict[str, Any]:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`")
        cleaned = cleaned.removeprefix("json").strip()
    try:
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else {"raw": parsed}
    except json.JSONDecodeError:
        return {"rawText": text}


_DIMENSION_ALIASES = {"length": "length", "breadth": "breadth", "width": "breadth", "height": "height"}


def _measurement_prompt(dimension: str | None) -> str:
    """Prompt for either a single requested dimension or the legacy length+breadth pair."""
    if dimension:
        dim = _DIMENSION_ALIASES.get(dimension.strip().lower(), dimension.strip().lower())
        return (
            f"The image shows a single craft object placed on a 1 inch square grid sheet. "
            f"By counting the grid squares the object spans, estimate the object's {dim} in inches. "
            f"Return JSON only with valueInches (a number, or null if it cannot be determined), "
            f"confidence from 0 to 1, and notes. If the grid or object is unclear, return null for "
            f"valueInches and explain why in notes."
        )
    return (
        "The image shows a craft object placed on a 1 inch square grid sheet. "
        "Estimate the object's length and breadth in inches. Return JSON only with "
        "lengthInches, breadthInches, confidence from 0 to 1, and notes. If the grid "
        "or object is unclear, return null values and explain in notes."
    )


def _post_gemini_measurement(content: bytes, mime_type: str, settings: Settings, dimension: str | None = None) -> dict[str, Any]:
    # Managed override first, env pool second — see managed_secrets.gemini_key_pool, which reproduces
    # Settings.gemini_api_keys exactly when nothing is stored (single key, then the rotation list).
    keys = managed_secrets.gemini_key_pool()
    if not keys:
        raise RuntimeError("No Gemini API key configured")

    prompt = _measurement_prompt(dimension)
    body = {
        "contents": [
            {
                "parts": [
                    {"text": prompt},
                    {
                        "inlineData": {
                            "mimeType": mime_type or "image/jpeg",
                            "data": base64.b64encode(content).decode("ascii"),
                        }
                    },
                ]
            }
        ],
        "generationConfig": {"responseMimeType": "application/json"},
    }

    start = _next_gemini_start(len(keys))
    ordered_keys = keys[start:] + keys[:start]
    last_error: Exception | None = None

    for attempt, key in enumerate(ordered_keys):
        try:
            response = requests.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{settings.gemini_measurement_model}:generateContent",
                # The key goes in the header the API documents for it, NOT in ``?key=`` — a query
                # parameter is part of the prepared URL, which means it is inside the message of
                # every requests exception this call can raise, and those messages were being
                # returned to the caller and stored on the media row. It also lands in any proxy or
                # access log between here and Google. Every other provider in this module already
                # authenticates by header; this was the one that did not.
                headers={"x-goog-api-key": key},
                json=body,
                timeout=90,
            )
        except requests.RequestException as exc:
            last_error = exc
            logger.info(
                "Gemini key #%s network error, rotating: %s",
                (start + attempt) % len(keys),
                redact_secrets(str(exc)),
            )
            continue

        if response.status_code in _GEMINI_ROTATE_STATUSES:
            # The provider's body stays in the log. The raised error carries the response so the
            # caller can name the status and nothing else — see ``_fault``.
            last_error = requests.HTTPError(
                f"Gemini rejected the request (HTTP {response.status_code})", response=response
            )
            logger.info(
                "Gemini key #%s returned HTTP %s, rotating: %s",
                (start + attempt) % len(keys),
                response.status_code,
                redact_secrets(str(response.text)[:200]),
            )
            continue

        try:
            response.raise_for_status()
        except requests.RequestException as exc:
            last_error = exc
            continue

        payload = response.json()
        text = (
            payload.get("candidates", [{}])[0]
            .get("content", {})
            .get("parts", [{}])[0]
            .get("text", "")
        )
        parsed = _extract_json(text)
        return {
            "available": True,
            "status": "COMPLETED",
            "analysis": parsed,
            "keysTried": attempt + 1,
            "raw": payload,
        }

    raise last_error or RuntimeError("All configured Gemini keys failed")


async def analyze_measurement_image(file: UploadFile, settings: Settings, dimension: str | None = None) -> dict[str, Any]:
    content = await file.read()
    return await analyze_measurement_image_bytes(
        content,
        file.filename or "measurement.jpg",
        file.content_type or "image/jpeg",
        settings,
        dimension,
    )


async def analyze_measurement_image_bytes(
    content: bytes,
    filename: str,
    mime_type: str,
    settings: Settings,
    dimension: str | None = None,
) -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()  # prime before the thread hop (see _key)
    if not managed_secrets.gemini_key_pool():
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "analysis": None,
            "message": "Gemini measurement analysis unavailable; fill in the value manually.",
        }
    try:
        return await asyncio.to_thread(
            _post_gemini_measurement,
            content,
            mime_type,
            settings,
            dimension,
        )
    except requests.RequestException as exc:
        logger.error("Gemini measurement analysis failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "analysis": None,
            "message": (
                f"Measurement analysis failed ({_fault(exc)}); measure the object and enter the "
                "value manually. The provider's reply is in the server log."
            ),
        }
