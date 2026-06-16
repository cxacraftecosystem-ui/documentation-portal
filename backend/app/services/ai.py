import asyncio
import base64
import json
from typing import Any

import requests
from fastapi import UploadFile

from app.core.config import Settings


def _post_openai_transcription(content: bytes, filename: str, mime_type: str, settings: Settings) -> dict[str, Any]:
    response = requests.post(
        "https://api.openai.com/v1/audio/transcriptions",
        headers={"Authorization": f"Bearer {settings.openai_api_key}"},
        data={"model": settings.openai_transcription_model, "response_format": "json"},
        files={"file": (filename, content, mime_type or "application/octet-stream")},
        timeout=90,
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
            _post_openai_transcription,
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
    prompt = (
        "The image shows a craft object placed on a 1 inch square grid sheet. "
        "Estimate the object's length and breadth in inches. Return JSON only with "
        "lengthInches, breadthInches, confidence from 0 to 1, and notes. If the grid "
        "or object is unclear, return null values and explain in notes."
    )
    response = requests.post(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent",
        params={"key": settings.gemini_api_key},
        json={
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
        },
        timeout=90,
    )
    response.raise_for_status()
    payload = response.json()
    text = (
        payload.get("candidates", [{}])[0]
        .get("content", {})
        .get("parts", [{}])[0]
        .get("text", "")
    )
    parsed = _extract_json(text)
    return {"available": True, "status": "COMPLETED", "analysis": parsed, "raw": payload}


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
    if not settings.gemini_api_key:
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
