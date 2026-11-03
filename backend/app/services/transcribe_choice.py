from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services import app_settings

logger = logging.getLogger(__name__)

TRANSCRIPTION = "TRANSCRIPTION"
KINDS = frozenset({"AUDIO", "VIDEO"})
UPLOAD = "UPLOAD"
AUTO_AUDIO = "AUTO_AUDIO"
AUTO_VIDEO = "AUTO_VIDEO"
BUTTON = "BUTTON"
BACKFILL = "BACKFILL"
SOURCES = frozenset({UPLOAD, AUTO_AUDIO, AUTO_VIDEO, BUTTON, BACKFILL})


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


def kind(media: Any) -> str:
    media_type = str(_value(media, "mediaType") or "").upper()
    if media_type.endswith("VIDEO"):
        return "VIDEO"
    if media_type.endswith("AUDIO"):
        return "AUDIO"
    return media_type


def transcribable(media: Any) -> bool:
    return kind(media) in KINDS


def asked_for(requested: list[str] | None) -> bool:
    return any(str(item).strip().upper() == TRANSCRIPTION for item in requested or [])


def policy_of(row: Any) -> dict[str, bool]:
    return {
        "autoTranscribeVideo": bool(getattr(row, "autoTranscribeVideo", False)),
        "autoTranscribeAudio": bool(getattr(row, "autoTranscribeAudio", False)),
    }


async def policy() -> dict[str, bool]:
    return policy_of(await app_settings.load_app_settings())


def decide(media_kind: str, asked: bool, current: dict[str, bool]) -> tuple[bool, str | None]:
    if media_kind == "VIDEO" and current.get("autoTranscribeVideo"):
        return True, AUTO_VIDEO
    if media_kind == "AUDIO" and current.get("autoTranscribeAudio"):
        return True, AUTO_AUDIO
    if media_kind in KINDS and asked:
        return True, UPLOAD
    return False, None


async def record(media_id: str, on: bool, source: str | None) -> None:
    try:
        await db.mediafile.update(
            where={"id": media_id},
            data={
                "transcribeRequested": on,
                "transcribeRequestSource": source,
                "transcribeRequestedAt": datetime.now(UTC) if on else None,
            },
        )
    except Exception:
        logger.exception("could not store the transcription choice of media %s", media_id)


async def resolve_upload(media: Any, requested: list[str] | None) -> list[str]:
    others = [
        str(item) for item in requested or [] if str(item).strip().upper() != TRANSCRIPTION
    ]
    if not transcribable(media):
        return others
    on, source = decide(kind(media), asked_for(requested), await policy())
    await record(str(_value(media, "id")), on, source)
    return [*others, TRANSCRIPTION] if on else others


async def mark_requested(media: Any, source: str) -> None:
    if _value(media, "transcribeRequested") is True:
        return
    await record(str(_value(media, "id")), True, source)


def wanted(media: Any) -> bool:
    return transcribable(media) and _value(media, "transcribeRequested") is True


async def save_switches(
    *, video: bool | None, audio: bool | None, updated_by_id: str | None
) -> dict[str, bool]:
    data: dict[str, Any] = {}
    if video is not None:
        data["autoTranscribeVideo"] = bool(video)
    if audio is not None:
        data["autoTranscribeAudio"] = bool(audio)
    if not data:
        return await policy()
    await app_settings.get_or_create_app_settings(updated_by_id)
    row = await db.appsetting.update(
        where={"id": app_settings.SINGLETON_ID},
        data={**data, "updatedById": updated_by_id},
    )
    return policy_of(row)


async def resolve_retry(media: Any, requested: list[str] | None) -> list[str]:
    if _value(media, "transcribeRequested") is None:
        return await resolve_upload(media, requested)
    others = [
        str(item) for item in requested or [] if str(item).strip().upper() != TRANSCRIPTION
    ]
    return [*others, TRANSCRIPTION] if wanted(media) else others
