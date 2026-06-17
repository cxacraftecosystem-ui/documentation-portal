import asyncio
import base64
import json
import logging
import threading
from typing import Any

import requests
from fastapi import UploadFile

from app.core.config import Settings

logger = logging.getLogger(__name__)

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


def _post_openai_transcription(content: bytes, filename: str, mime_type: str, settings: Settings) -> dict[str, Any]:
    response = requests.post(
        "https://api.openai.com/v1/audio/transcriptions",
        headers={"Authorization": f"Bearer {settings.openai_api_key}"},
        data={"model": settings.openai_transcription_model, "response_format": "json"},
        files={"file": (filename, content, mime_type or "application/octet-stream")},
        timeout=180,
    )
    response.raise_for_status()
    payload = response.json()
    text = str(payload.get("text") or "").strip()
    return {
        "available": True,
        "status": "COMPLETED" if text else "EMPTY",
        "text": text,
        "formattedTranscript": f"Transcript\n\n{text}" if text else "",
        "raw": payload,
    }


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


def _transcribe_sync(content: bytes, filename: str, mime_type: str, settings: Settings) -> dict[str, Any]:
    """Transcribe in one shot when small; otherwise chunk, transcribe sequentially, and stitch."""
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
    return {
        "available": True,
        "status": "COMPLETED" if text else "EMPTY",
        "text": text,
        "formattedTranscript": f"Transcript\n\n{text}" if text else "",
        "chunks": len(chunks),
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
    if not settings.openai_api_key:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "text": None,
            "formattedTranscript": None,
            "message": "Transcription unavailable for now because OPENAI_API_KEY is not configured.",
        }
    try:
        return await asyncio.to_thread(
            _transcribe_sync,
            content,
            filename,
            mime_type,
            settings,
        )
    except requests.RequestException as exc:
        return {
            "available": True,
            "status": "FAILED",
            "text": None,
            "formattedTranscript": None,
            "message": str(exc),
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


def _post_gemini_measurement(content: bytes, mime_type: str, settings: Settings) -> dict[str, Any]:
    keys = settings.gemini_api_keys
    if not keys:
        raise RuntimeError("No Gemini API key configured")

    prompt = (
        "The image shows a craft object placed on a 1 inch square grid sheet. "
        "Estimate the object's length and breadth in inches. Return JSON only with "
        "lengthInches, breadthInches, confidence from 0 to 1, and notes. If the grid "
        "or object is unclear, return null values and explain in notes."
    )
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
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent",
                params={"key": key},
                json=body,
                timeout=90,
            )
        except requests.RequestException as exc:
            last_error = exc
            logger.info("Gemini key #%s network error, rotating: %s", (start + attempt) % len(keys), exc)
            continue

        if response.status_code in _GEMINI_ROTATE_STATUSES:
            last_error = requests.HTTPError(
                f"Gemini key rejected with HTTP {response.status_code}: {response.text[:200]}"
            )
            logger.info("Gemini key #%s returned HTTP %s, rotating", (start + attempt) % len(keys), response.status_code)
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


async def analyze_measurement_image(file: UploadFile, settings: Settings) -> dict[str, Any]:
    content = await file.read()
    return await analyze_measurement_image_bytes(
        content,
        file.filename or "measurement.jpg",
        file.content_type or "image/jpeg",
        settings,
    )


async def analyze_measurement_image_bytes(
    content: bytes,
    filename: str,
    mime_type: str,
    settings: Settings,
) -> dict[str, Any]:
    if not settings.gemini_api_keys:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "analysis": None,
            "message": "Gemini measurement analysis unavailable; fill in length and breadth manually.",
        }
    try:
        return await asyncio.to_thread(
            _post_gemini_measurement,
            content,
            mime_type,
            settings,
        )
    except requests.RequestException as exc:
        return {
            "available": True,
            "status": "FAILED",
            "analysis": None,
            "message": str(exc),
        }
