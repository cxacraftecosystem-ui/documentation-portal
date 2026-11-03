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
from app.services.ai_providers import AiProvider, AiTask, openai_message_text, openai_sampling
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


def _key(name: str) -> str:
    return managed_secrets.peek_secret(name) or ""


_URL_SECRET = re.compile(
    r"((?:key|api[-_]?key|access[-_]?token|token|secret|sig|signature)=)[^&\s\"'>]+",
    re.IGNORECASE,
)


def redact_secrets(text: str) -> str:
    return _URL_SECRET.sub(r"\1REDACTED", text)


_GEMINI_ROTATE_STATUSES = {400, 401, 403, 429, 500, 503}

_gemini_key_lock = threading.Lock()
_gemini_key_counter = 0


def _next_gemini_start(num_keys: int) -> int:
    global _gemini_key_counter
    if num_keys <= 0:
        return 0
    with _gemini_key_lock:
        start = _gemini_key_counter % num_keys
        _gemini_key_counter = (_gemini_key_counter + 1) % num_keys
    return start


WHISPER_MAX_BYTES = 24 * 1024 * 1024
TRANSCRIPTION_CHUNK_MS = 10 * 60 * 1000
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


_CRAFT_VOCABULARY_PATH = Path(__file__).resolve().parents[1] / "data" / "craft_vocabulary.txt"

_KEYTERM_MAX_CHARS = 50
_KEYTERM_MAX_WORDS = 5
_ELEVENLABS_KEYTERM_LIMIT = 100
_DEEPGRAM_KEYTERM_LIMIT = 50
_DEEPGRAM_KEYTERM_TOKEN_BUDGET = 400


@lru_cache(maxsize=1)
def craft_keyterms() -> tuple[str, ...]:
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
            rejected += 1
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
    return deepgram_adapter.keyterms_within_budget(craft_keyterms())



def _speaker_turns(fragments: list[tuple[Any, str]]) -> list[tuple[Any, str]]:
    return speaker_turns(fragments)


def _speaker_count(turns: list[tuple[Any, str]]) -> int:
    return speaker_count(turns)


def _diarized_markdown(turns: list[tuple[Any, str]]) -> str | None:
    return diarized_markdown(turns)


def _post_openai_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
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
    result = ADAPTERS["muse"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("muse"),
    )
    return _legacy_result(result, language=False)


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
    keyterms: list[str] | None = None,
) -> SttOptions:
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
        keyterms=list(keyterms) if keyterms else list(craft_keyterms()),
        models=models,
        session_id=session_id,
        keep_payload=keep_payload,
    )


def transcription_options(
    settings: Settings | None, *, language_bias: list[str] | None = None
) -> SttOptions:
    return _stt_options(settings, language_bias=language_bias)


def _audio(
    content: bytes | None, filename: str, mime_type: str, source_path: str | None = None
) -> AudioInput:
    duration = None
    if source_path is not None and transcription_audio.ffmpeg_available():
        try:
            duration = transcription_audio.probe_duration_ms(source_path)
        except Exception:  # noqa: BLE001
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
    out = _transcription_result(result.text, None)
    out["model"] = result.model
    out["speakers"] = result.speakers
    out["segments"] = [segment.as_json() for segment in result.segments]
    if language and result.language:
        out["languageCode"] = result.language
    return out


_OPTION_REJECTED_STATUSES = {400, 422}

_ELEVENLABS_MODEL = "scribe_v2"
_ELEVENLABS_LEGACY_MODEL = "scribe_v1"


def _elevenlabs_model(settings: Settings | None) -> str:
    configured = (getattr(settings, "elevenlabs_stt_model", None) or "").strip()
    return (
        configured if configured and configured != _ELEVENLABS_LEGACY_MODEL else _ELEVENLABS_MODEL
    )


def _elevenlabs_fields(settings: Settings | None, *, conservative: bool) -> list[tuple[str, str]]:
    return ADAPTERS["elevenlabs"].fields(_stt_options(settings), conservative=conservative)


def _elevenlabs_text(payload: dict[str, Any]) -> tuple[str, int]:
    return elevenlabs_adapter.parse_text(payload)


def _post_elevenlabs_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    result = ADAPTERS["elevenlabs"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("elevenlabs"),
    )
    return _legacy_result(result, language=True)


def _deepgram_params(settings: Settings | None, *, conservative: bool) -> dict[str, Any]:
    return ADAPTERS["deepgram"].params(_stt_options(settings), conservative=conservative)


def _deepgram_text(payload: dict[str, Any]) -> tuple[str, int]:
    return deepgram_adapter.parse_text(payload)


def _post_deepgram_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    result = ADAPTERS["deepgram"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("deepgram"),
    )
    return _legacy_result(result, language=False)


def _split_audio_into_chunks(content: bytes) -> list[tuple[bytes, str, str]] | None:
    try:
        import io

        from pydub import AudioSegment
    except Exception:  # noqa: BLE001
        logger.warning("pydub/ffmpeg unavailable; long audio cannot be chunked for transcription")
        return None
    try:
        audio = AudioSegment.from_file(io.BytesIO(content))
    except Exception as exc:  # noqa: BLE001
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
    if len(content) <= WHISPER_MAX_BYTES:
        return _post_openai_transcription(content, filename, mime_type, settings)

    chunks = _split_audio_into_chunks(content)
    if not chunks:
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

_PROVIDER_NAMES = {
    "elevenlabs": "ElevenLabs",
    "deepgram": "Deepgram",
    "whisper": "Whisper (OpenAI)",
    "muse": "Meta Muse",
}


def transcription_provider_catalog() -> list[tuple[str, str, str]]:
    return [(p, _PROVIDER_NAMES[p], key) for p, key in _PROVIDER_KEYS.items()]


def transcription_provider_configured() -> dict[str, bool]:
    return {provider: bool(_key(name)) for provider, name in _PROVIDER_KEYS.items()}


def transcription_provider_chain(
    settings: Settings | None = None,
    order: list[str] | None = None,
    *,
    disabled: set[str] | frozenset[str] | list[str] | None = None,
) -> list[str]:
    ranked = order if order is not None else list(app_settings.DEFAULT_STT_PROVIDER_ORDER)
    off = set(DISABLED_BY_DEFAULT) if disabled is None else set(disabled)
    return [p for p in ranked if p in _PROVIDER_KEYS and p not in off and _key(_PROVIDER_KEYS[p])]


_PROVIDER_CALLS = {
    "elevenlabs": (_post_elevenlabs_transcription, ELEVENLABS_MAX_BYTES),
    "deepgram": (_post_deepgram_transcription, DEEPGRAM_MAX_BYTES),
    "whisper": (_transcribe_whisper_sync, None),
    "muse": (_post_muse_transcription, None),
}


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



def _transcribe_on_personal_key(
    content: bytes, filename: str, mime_type: str, credential: AiCredential
) -> dict[str, Any] | None:
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
        return _transcription_result(text, payload) if text else None

    return None



async def transcribe_audio_bytes(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings,
    *,
    user_id: str | None = None,
    media_id: str | None = None,
    job_id: str | None = None,
    language_bias: list[str] | None = None,
    skip_providers: set[str] | None = None,
    source_path: str | None = None,
    keyterms: list[str] | None = None,
) -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()

    if user_id and content is not None:
        personal = await user_ai_keys.resolve(user_id, AiTask.TRANSCRIBE)
        if personal is not None and personal.is_user_supplied:
            try:
                answer = await asyncio.to_thread(
                    _transcribe_on_personal_key, content, filename, mime_type, personal
                )
                if answer is not None:
                    return answer
            except Exception as exc:  # noqa: BLE001
                logger.warning(
                    "A designer's own transcription key failed (%s); falling back to the "
                    "server's provider chain",
                    redact_secrets(str(exc)),
                )
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
    store = PrismaTranscriptionStore()
    state = await store.load_state(job_id=job_id, providers=chain)
    state.skip |= set(skip_providers or ())
    hooks = StoreHooks(store, asyncio.get_running_loop(), media_id=media_id, job_id=job_id)
    opts = _stt_options(
        settings, language_bias=language_bias, session_id=media_id, keyterms=keyterms
    )
    try:
        return await asyncio.to_thread(
            _transcribe_sync,
            content,
            filename,
            mime_type,
            settings,
            chain,
            source_path=source_path,
            state=state,
            hooks=hooks,
            opts=opts,
        )
    except requests.HTTPError as exc:
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
            **openai_sampling(settings.openai_chat_model, 0.2),
        },
        timeout=120,
    )
    response.raise_for_status()
    return openai_message_text(response.json())


def _refine_prompts(text: str, translate_to_english: bool) -> tuple[str, str]:
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
    return system, user


async def refine_transcript_text(
    text: str | None,
    translate_to_english: bool,
    settings: Settings,
) -> dict[str, Any]:
    from app.services import transcript_insights

    if await user_ai_keys.resolve(None, AiTask.TRANSLATE) is None:
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
    system, user = _refine_prompts(text, translate_to_english)
    try:
        answer = await transcript_insights._chat(system, user, AiTask.TRANSLATE, None)
    except transcript_insights.InsightsUnavailable:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "refined": None,
            "message": "Refinement isn't available right now.",
        }
    except transcript_insights.InsightsFailed as exc:
        declined = str(exc) == transcript_insights.MODEL_DECLINED
        return {
            "available": True,
            "status": "FAILED",
            "refined": None,
            "message": (
                "The AI model declined this transcript. The original transcript is unchanged."
                if declined
                else "Refinement failed; the original transcript is unchanged. Try again later."
            ),
        }
    refined = str(answer.get("text") or "").strip() or None
    return {
        "available": True,
        "status": "COMPLETED" if refined else "EMPTY",
        "refined": refined,
        "model": answer.get("model"),
        "translated": translate_to_english,
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

MEASUREMENT_PROVIDER = "gemini"


def _measurement_provenance(
    analysis: dict[str, Any] | None, settings: Settings, model_id: str | None = None
) -> MeasurementProvenance:
    return vision_model_provenance(
        analysis,
        provider=MEASUREMENT_PROVIDER,
        model_id=model_id or settings.gemini_measurement_model,
    )


def _measurement_prompt(dimension: str | None) -> str:
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


GEMINI_VISION_FALLBACK_MODEL = "gemini-3.7-flash"


def measurement_models(settings: Settings) -> tuple[str, ...]:
    configured = settings.gemini_measurement_model
    if configured == GEMINI_VISION_FALLBACK_MODEL:
        return (configured,)
    return (configured, GEMINI_VISION_FALLBACK_MODEL)


def _post_gemini_measurement(content: bytes, mime_type: str, settings: Settings, dimension: str | None = None) -> dict[str, Any]:
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

    for model_id in measurement_models(settings):
        for attempt, key in enumerate(ordered_keys):
            try:
                response = requests.post(
                    f"https://generativelanguage.googleapis.com/v1beta/models/{model_id}:generateContent",
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

            if response.status_code == 404:
                last_error = requests.HTTPError("Gemini model unavailable (HTTP 404)", response=response)
                break

            if response.status_code in _GEMINI_ROTATE_STATUSES:
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
                **_measurement_provenance(parsed, settings, model_id).payload(),
            }

    raise last_error or RuntimeError("All configured Gemini keys failed")



async def analyze_measurement_image_bytes(
    content: bytes,
    filename: str,
    mime_type: str,
    settings: Settings,
    dimension: str | None = None,
) -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()
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
            **_measurement_provenance(None, settings).payload(),
        }
