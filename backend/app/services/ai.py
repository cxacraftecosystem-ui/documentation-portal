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

from app.core.config import Settings
from app.services import (
    app_settings,
    managed_secrets,
    user_ai_keys,
)
from app.services.ai_providers import AiProvider, AiTask
from app.services.measurement_provenance import (
    MeasurementProvenance,
    vision_model_provenance,
)
from app.services.transcription_store import PrismaTranscriptionStore
from app.services.user_ai_keys import AiCredential
from app.transcription import audio as transcription_audio
from app.transcription import service as transcription_service
from app.transcription.chain import ChainHooks, ChainState, run_chain
from app.transcription.contract import DISABLED_BY_DEFAULT, ApiKey, AudioInput, SttOptions
from app.transcription.format import diarized_markdown, speaker_count, speaker_turns
from app.transcription.providers import ADAPTERS
from app.transcription.providers import deepgram as deepgram_adapter
from app.transcription.providers import elevenlabs as elevenlabs_adapter
from app.transcription.store import StoreHooks

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
    return deepgram_adapter.keyterms_within_budget(craft_keyterms())


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
    return speaker_turns(fragments)


def _speaker_count(turns: list[tuple[Any, str]]) -> int:
    return speaker_count(turns)


def _diarized_markdown(turns: list[tuple[Any, str]]) -> str | None:
    """Turns as ``**Speaker 1:** ...`` paragraphs, or None when only one voice was heard."""
    return diarized_markdown(turns)


def _post_openai_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    """Whisper, one shot, through the shared adapter."""
    result = ADAPTERS["whisper"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("whisper"),
    )
    return _transcription_result(result.text, None)


def _post_muse_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    """Meta Muse, one normalised part. The chain cuts longer recordings before calling it."""
    result = ADAPTERS["muse"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("muse"),
    )
    return _legacy_result(result, language=False)


# A 400/422 means the provider refused the REQUEST, not the audio in it — a renamed option, a model
# id retired since this was written. Rather than let that drop a whole provider out of the chain
# silently, each one retries ONCE with the option set that predates this work: the transcript comes
# back without diarization or boosting instead of not at all, and the log says so. The cost is a
# second upload of the same bytes, paid only on a refusal and never twice.
# --- The shared transcription service ------------------------------------------------------------
#
# Every provider call below goes through ``app/transcription`` (byte-identical in both portals; see
# shared/transcription.sha256). The functions that follow keep their old names and result shapes so
# every caller and test written before the package keeps working; their bodies are now thin wrappers.
# What this portal SAYS about a failure stays its own copy, in ``_TRANSCRIPTION_MESSAGES``.

_TRANSCRIPTION_MESSAGES = transcription_service.Messages(
    key_rejected=lambda name: f"{name}'s key isn't working; an admin can update it in Settings",
    failed=lambda name, status: f"{name} couldn't transcribe this recording",
    unreachable=lambda name: f"{name} couldn't be reached",
    too_large=lambda name: f"{name}: file larger than the provider limit",
    busy=lambda name: f"{name} is busy",
    rate_limited=lambda name: "Transcription is busy; it will retry automatically.",
    over_budget=lambda name: f"{name}'s transcription budget is used up",
    all_failed=lambda sentences: (
        f"Transcription failed: {'; '.join(sentences)}." if sentences else "Transcription failed."
    ),
    unavailable=(
        "Transcription isn't available right now. An administrator can turn it on in Settings."
    ),
    budget_deferred="The transcription budget is used up; it will continue in the next window.",
)


def _stt_options(
    settings: Settings | None,
    *,
    language_bias: list[str] | None = None,
    session_id: str | None = None,
    keep_payload: bool = False,
) -> SttOptions:
    """The options every provider is asked with: craft keyterms, diarization, models, language hint."""
    models = {"elevenlabs": _elevenlabs_model(settings)}
    deepgram_model = getattr(settings, "deepgram_stt_model", None)
    if deepgram_model:
        models["deepgram"] = deepgram_model
    whisper_model = getattr(settings, "openai_transcription_model", None)
    if whisper_model:
        models["whisper"] = whisper_model
    muse_model = getattr(settings, "muse_stt_model", None)
    if muse_model:
        models["muse"] = muse_model
    return SttOptions(
        language_bias=list(language_bias or []),
        keyterms=list(craft_keyterms()),
        models=models,
        session_id=session_id,
        keep_payload=keep_payload,
    )


def transcription_options(
    settings: Settings | None, *, language_bias: list[str] | None = None
) -> SttOptions:
    """The options a queued recording is transcribed with; the dedup cache keys on them."""
    return _stt_options(settings, language_bias=language_bias)


def _audio(
    content: bytes | None, filename: str, mime_type: str, source_path: str | None = None
) -> AudioInput:
    duration = None
    if source_path is not None and transcription_audio.ffmpeg_available():
        try:
            duration = transcription_audio.probe_duration_ms(source_path)
        except Exception:  # noqa: BLE001 - the probe only sharpens the budget estimate
            duration = None
    return AudioInput(
        None if source_path is not None else content,
        source_path,
        filename,
        mime_type or "application/octet-stream",
        duration,
    )


def _provider_key(provider: str) -> ApiKey:
    name = _PROVIDER_KEYS[provider]
    return ApiKey(name, _key(name))


def _legacy_result(result: Any, *, language: bool) -> dict[str, Any]:
    """An adapter's ``SttResult`` as the dictionary the ``_post_*`` functions always returned."""
    out = _transcription_result(result.text, None)
    out["model"] = result.model
    out["speakers"] = result.speakers
    out["segments"] = [segment.as_json() for segment in result.segments]
    if language and result.language:
        out["languageCode"] = result.language
    return out


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


def _elevenlabs_model(settings: Settings | None) -> str:
    configured = (getattr(settings, "elevenlabs_stt_model", None) or "").strip()
    return (
        configured if configured and configured != _ELEVENLABS_LEGACY_MODEL else _ELEVENLABS_MODEL
    )


def _elevenlabs_fields(settings: Settings | None, *, conservative: bool) -> list[tuple[str, str]]:
    """The multipart fields for one Scribe request (see app/transcription/providers/elevenlabs.py)."""
    return ADAPTERS["elevenlabs"].fields(_stt_options(settings), conservative=conservative)


def _elevenlabs_text(payload: dict[str, Any]) -> tuple[str, int]:
    """``(transcript, speakers)`` from a Scribe response, speaker-labelled when several voices spoke."""
    return elevenlabs_adapter.parse_text(payload)


def _post_elevenlabs_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    """ElevenLabs Scribe, through the shared adapter. Raises ``requests.HTTPError`` subclasses."""
    result = ADAPTERS["elevenlabs"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("elevenlabs"),
    )
    return _legacy_result(result, language=True)


def _deepgram_params(settings: Settings | None, *, conservative: bool) -> dict[str, Any]:
    """Query parameters for one Deepgram request (see app/transcription/providers/deepgram.py)."""
    return ADAPTERS["deepgram"].params(_stt_options(settings), conservative=conservative)


def _deepgram_text(payload: dict[str, Any]) -> tuple[str, int]:
    """``(transcript, speakers)`` from a Deepgram response, speaker-labelled where voices differ."""
    return deepgram_adapter.parse_text(payload)


def _post_deepgram_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    """Deepgram Nova-3, through the shared adapter. Raises ``requests.HTTPError`` subclasses."""
    result = ADAPTERS["deepgram"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("deepgram"),
    )
    return _legacy_result(result, language=False)


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
    "muse": "META_MODEL_API_KEY",
}

# How each engine is named to a human. Here rather than in the web client because the Settings hub
# and the Android screen must not be able to drift into calling the same engine two different things.
_PROVIDER_NAMES = {
    "elevenlabs": "ElevenLabs",
    "deepgram": "Deepgram",
    "whisper": "Whisper (OpenAI)",
    "muse": "Meta Muse",
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
    *,
    disabled: set[str] | frozenset[str] | list[str] | None = None,
) -> list[str]:
    """Configured STT providers in priority order, skipping every one whose key is unset or that an
    admin switched off. ``disabled=None`` means the shipped default (Muse off)."""
    ranked = order if order is not None else list(app_settings.DEFAULT_STT_PROVIDER_ORDER)
    off = set(DISABLED_BY_DEFAULT) if disabled is None else set(disabled)
    return [p for p in ranked if p in _PROVIDER_KEYS and p not in off and _key(_PROVIDER_KEYS[p])]


_PROVIDER_CALLS = {
    "elevenlabs": (_post_elevenlabs_transcription, ELEVENLABS_MAX_BYTES),
    "deepgram": (_post_deepgram_transcription, DEEPGRAM_MAX_BYTES),
    "whisper": (_transcribe_whisper_sync, None),  # cut into parts by the chain, no hard cap
    "muse": (_post_muse_transcription, None),  # always normalised and cut into parts by the chain
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
    return {
        "available": True,
        "status": "RATE_LIMITED",
        "text": None,
        "formattedTranscript": None,
        "retryAfter": retry_after,
        "provider": provider,
        "message": "Transcription is busy; it will retry automatically.",
    }


def _transcribe_sync(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    chain: list[str],
    *,
    source_path: str | None = None,
    state: ChainState | None = None,
    hooks: ChainHooks | None = None,
    opts: SttOptions | None = None,
) -> dict[str, Any]:
    """Walk *chain* through the shared service (app/transcription/chain.py) until one provider
    produces a transcript. Per-provider cooldowns and budgets come in on *state*; usage, cooldowns
    and finished parts go out through *hooks* as they happen."""
    outcome = run_chain(
        _audio(content, filename, mime_type, source_path),
        transcription_service.build_slots(chain, _key),
        opts or _stt_options(settings),
        state,
        hooks,
        cutter_factory=(
            transcription_audio.PartCutter if transcription_audio.ffmpeg_available() else None
        ),
    )
    return transcription_service.outcome_dict(outcome, _TRANSCRIPTION_MESSAGES)


# ``transcribe_audio(file: UploadFile, ...)`` USED TO LIVE HERE AND IS GONE ON PURPOSE — A30-10.
#
# It was four lines, and the first of them was ``content = await file.read()``. ``read()`` with no
# argument reads to EOF, so the 25 MB ceiling that ``POST /media/transcribe`` believed it was
# enforcing was applied one line AFTER the whole body had been materialised in one contiguous
# ``bytes`` in the process heap. On this deployment — a single-worker uvicorn on 1 GiB, MEASURED,
# docs/SCALABILITY.md §5.1, with no supervisor to respawn a worker that overcommits because
# ``--workers >1`` is what caused the outage ``app/worker.py`` describes — a body larger than the
# free heap does not degrade one request, it takes every in-flight request on the box down with it.
# The cheapest possible attempt was one signed-in account, one request, one large file.
#
# THE ROUTE NOW READS ITS OWN BYTES through ``app/services/uploads.read_upload_bounded`` and calls
# ``transcribe_audio_bytes`` below (app/api/routes/media.py). This shim is DELETED rather than left
# unused because an unbounded ``file.read()`` sitting in a services module with a friendly name is
# an invitation: the next route that needs a transcript reaches for the one that takes an
# ``UploadFile``, and the bound is gone again with nothing to notice it. ``*_bytes`` is now the only
# door, and a caller holding an ``UploadFile`` has to decide a ceiling to get through it.
#
# The same argument applies verbatim to ``analyze_measurement_image``, deleted below for the same
# reason; see the note above ``analyze_measurement_image_bytes``.


def _transcribe_on_personal_key(
    content: bytes, filename: str, mime_type: str, credential: AiCredential
) -> dict[str, Any] | None:
    """One transcription attempt on a designer's OWN key. ``None`` means "not this provider".

    RETURNS None RATHER THAN RAISING for a provider that cannot transcribe, because the caller's
    contract is "try this, then fall into the chain" and an exception there would be indistinguishable
    from a provider that was reached and failed. Anthropic never arrives here at all — ``resolve``
    will not hand back a Claude credential for a transcription task, because no Claude model accepts
    audio — so this is belt and braces rather than the enforcement point.
    """
    if credential.provider is AiProvider.OPENAI:
        response = requests.post(
            "https://api.openai.com/v1/audio/transcriptions",
            headers={"Authorization": f"Bearer {credential.api_key}"},
            data={"model": credential.model, "response_format": "json"},
            files={"file": (filename, content, mime_type or "application/octet-stream")},
            timeout=180,
        )
        response.raise_for_status()
        payload = response.json()
        return _transcription_result(str(payload.get("text") or "").strip(), payload)

    if credential.provider is AiProvider.GEMINI:
        # Gemini takes audio as an inline part on the ordinary generate endpoint — there is no
        # separate transcription route. The prompt is deliberately bare: this is a transcription and
        # not a summary, and any instruction beyond "write down what is said" is an invitation to
        # tidy the speech, which is precisely what a field transcript must not do.
        response = requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{credential.model}:generateContent",
            headers={"x-goog-api-key": credential.api_key},
            json={
                "contents": [
                    {
                        "parts": [
                            {
                                "text": "Transcribe this recording word for word, in the language "
                                "spoken. Do not translate, summarise, tidy or omit anything. "
                                "Return only the transcript."
                            },
                            {
                                "inlineData": {
                                    "mimeType": mime_type or "audio/mpeg",
                                    "data": base64.b64encode(content).decode("ascii"),
                                }
                            },
                        ]
                    }
                ]
            },
            timeout=180,
        )
        response.raise_for_status()
        payload = response.json()
        candidates = payload.get("candidates") or []
        parts = ((candidates[0].get("content") or {}).get("parts") or []) if candidates else []
        text = "".join(str(part.get("text", "")) for part in parts).strip()
        # An empty answer is NOT a transcript of a silent recording — it is a refusal or a safety
        # block, and returning it would file an empty transcript against real audio. None sends the
        # job into the server's chain, which is the honest outcome.
        return _transcription_result(text, payload) if text else None

    return None



async def transcribe_audio_bytes(
    content: bytes,
    filename: str,
    mime_type: str,
    settings: Settings,
    *,
    user_id: str | None = None,
    media_id: str | None = None,
    job_id: str | None = None,
    language_bias: list[str] | None = None,
    skip_providers: set[str] | None = None,
) -> dict[str, Any]:
    # Prime the managed-secret cache on the event loop BEFORE any thread hop, so both the provider
    # chain below and the header reads inside the thread see keys saved in the UI.
    await managed_secrets.refresh_if_stale()

    # ── A DESIGNER'S OWN KEY, AND WHY IT DOES NOT JOIN THE FAILOVER CHAIN ──────────────────────
    #
    # The chain below is this repository's transcription quality ladder: ElevenLabs first because it
    # auto-detects the regional languages, Deepgram second, Whisper last, each one tried when the one
    # before it fails. It is ordered by how well each provider hears an Odia courtyard, and the order
    # is administrator-configurable for exactly that reason.
    #
    # A personal key is a BILLING choice, not a quality one, so it is offered as a first attempt and
    # the chain remains intact behind it. If the designer's own provider fails, the job falls into
    # the ordinary ladder and the recording still gets transcribed — a recording is the one artefact
    # in this app that cannot be re-taken, and no billing preference is worth losing one over.
    #
    # `user_id` is None for the queue worker unless the job carries its requester, which is what
    # keeps a background drain off an arbitrary designer's card.
    if user_id:
        personal = await user_ai_keys.resolve(user_id, AiTask.TRANSCRIBE)
        if personal is not None and personal.is_user_supplied:
            try:
                answer = await asyncio.to_thread(
                    _transcribe_on_personal_key, content, filename, mime_type, personal
                )
                if answer is not None:
                    return answer
            except Exception as exc:  # noqa: BLE001 - fall into the chain, never fail the recording
                logger.warning(
                    "A designer's own transcription key failed (%s); falling back to the "
                    "server's provider chain",
                    redact_secrets(str(exc)),
                )
    # Resolve the chain here, per job, and hand it to the thread: the ranking lives in the database
    # and awaiting it is impossible once inside `to_thread`. Reading it now is also what makes a
    # reorder apply to the very next job in both the API and the queue process, with no restart.
    chain = transcription_provider_chain(
        settings,
        await app_settings.load_stt_provider_order(),
        disabled=app_settings.stt_providers_disabled(await app_settings.load_app_settings()),
    )
    if not chain:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "text": None,
            "formattedTranscript": None,
            "message": (
                "Transcription isn't available right now. An administrator can turn it on in "
                "Settings."
            ),
        }
    # Cooldowns, budgets and any parts this job already paid for, read before the thread hop;
    # usage, cooldowns and parts are written back from the thread as they happen.
    store = PrismaTranscriptionStore()
    state = await store.load_state(job_id=job_id, providers=chain)
    state.skip |= set(skip_providers or ())
    hooks = StoreHooks(store, asyncio.get_running_loop(), media_id=media_id, job_id=job_id)
    opts = _stt_options(settings, language_bias=language_bias, session_id=media_id)
    try:
        return await asyncio.to_thread(
            _transcribe_sync,
            content,
            filename,
            mime_type,
            settings,
            chain,
            state=state,
            hooks=hooks,
            opts=opts,
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
                "message": "Transcription is busy; it will retry automatically.",
            }
        logger.error("Transcription failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "text": None,
            "formattedTranscript": None,
            "message": "Transcription failed. Try again later.",
        }
    except requests.RequestException as exc:
        logger.error("Transcription failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "text": None,
            "formattedTranscript": None,
            "message": "Transcription failed. Try again later.",
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
            "message": "Refinement isn't available right now.",
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
                "Refinement failed; the original transcript is unchanged. Try again later."
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

#: Which service reads the grid photograph. Named rather than inlined so a reader grepping for
#: everything that sends this repository's photographs to a third party finds this one too.
MEASUREMENT_PROVIDER = "gemini"


def _measurement_provenance(
    analysis: dict[str, Any] | None, settings: Settings
) -> MeasurementProvenance:
    """The provenance of whatever this endpoint just did, INCLUDING when it failed.

    THE PROVIDER AND MODEL ARE REPORTED ON THE FAILURE PATHS TOO. A researcher whose grid read failed
    is told which service refused (the status, never the provider's own words — see
    ``redact_secrets``), and an operator reading a log beside that failure needs to know which model
    id was configured at the time. The self-reported confidence is None there because there is no
    reading to be confident about, and ``UNRECORDED`` is not used for the model id on those paths:
    the id is a fact this process holds, and a configured setting is recorded whether or not the call
    it was used for succeeded.

    See ``services/measurement_provenance`` for why a number with no origin was the defect: both
    clients put this response straight into a form field, and ``records.merge_field_provenance`` then
    stamped it with the name of whoever pressed Save — so the record asserted that a named human had
    measured a model's estimate.
    """
    return vision_model_provenance(
        analysis,
        provider=MEASUREMENT_PROVIDER,
        model_id=settings.gemini_measurement_model,
    )


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
            # The provider's body stays in the log. The raised error carries only the status, and
            # the caller's message repeats none of it.
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
            # WHAT PRODUCED THE NUMBER, TRAVELLING WITH THE NUMBER. Until this, the response carried
            # ``valueInches`` and nothing about where it came from — while
            # ``settings.gemini_measurement_model`` was in hand two lines above the return — so both
            # clients auto-filled a form field from a model's estimate and the save stamped it with
            # the name of whoever pressed Save. ADDITIVE ONLY: ``analysis`` is the provider's own
            # parsed JSON, untouched, because an installed handset reads
            # ``response.analysis?.valueInches`` and cannot be updated. See
            # ``services/measurement_provenance`` for the whole argument and the client half.
            **_measurement_provenance(parsed, settings).payload(),
        }

    raise last_error or RuntimeError("All configured Gemini keys failed")


# ``analyze_measurement_image(file: UploadFile, ...)`` USED TO LIVE HERE AND IS GONE ON PURPOSE.
# The second half of the A30-10 pair argued in full above ``transcribe_audio_bytes``: same
# ``content = await file.read()``, same unbounded contiguous copy into a 1 GiB heap, same
# invitation to the next caller if it were merely left unused. ``POST /media/analyze-measurement``
# now reads through ``uploads.read_upload_bounded`` at an 8 MB ceiling and calls the ``*_bytes``
# function below.


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
            **_measurement_provenance(None, settings).payload(),
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
                "Measuring failed; measure the object and enter the value manually."
            ),
            # ``requiresAcceptance`` is on the FAILURES too, deliberately: it is a statement about
            # what kind of thing this endpoint produces, not about one reading, and a client has to
            # be able to branch on it before it knows whether a number arrived.
            **_measurement_provenance(None, settings).payload(),
        }
