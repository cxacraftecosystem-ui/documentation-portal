from __future__ import annotations

import asyncio
import json
import logging
import shutil
from collections import Counter
from collections.abc import AsyncIterator
from typing import Any

from app.transcription.audio import local_input

logger = logging.getLogger(__name__)

CONCURRENCY = 1
WAIT_SECONDS = 20.0
CHUNK_BYTES = 64 * 1024
MAX_SOURCE_BYTES = 1024 * 1024 * 1024
PROBE_SECONDS = 30.0
FIRST_CHUNK_SECONDS = 90.0

_slot = asyncio.Semaphore(CONCURRENCY)
counts: Counter[str] = Counter()


class ConversionUnavailable(Exception):
    pass


def available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


async def audio_codec(source_path: str) -> str | None:
    process = await asyncio.create_subprocess_exec(
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "a:0",
        "-show_entries",
        "stream=codec_name",
        "-of",
        "json",
        *local_input(source_path),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )
    try:
        out, _ = await asyncio.wait_for(process.communicate(), timeout=PROBE_SECONDS)
    except TimeoutError:
        process.kill()
        await process.wait()
        return None
    try:
        streams = json.loads(out.decode("utf-8") or "{}").get("streams") or []
    except ValueError:
        return None
    return str(streams[0].get("codec_name")) if streams and streams[0].get("codec_name") else None


def ffmpeg_args(source_path: str, codec: str | None) -> list[str]:
    audio = ["-c:a", "copy"] if codec == "aac" else ["-c:a", "aac", "-b:a", "128k"]
    return [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        *local_input(source_path),
        "-vn",
        "-map",
        "0:a:0",
        *audio,
        "-movflags",
        "frag_keyframe+empty_moov+default_base_moof",
        "-f",
        "mp4",
        "pipe:1",
    ]


def snapshot() -> dict[str, int]:
    return dict(counts)


def record(outcome: str, media_id: str, reason: str | None = None) -> None:
    counts[outcome] += 1
    if outcome in ("failed", "busy"):
        logger.warning(
            "audio to mp4 %s for %s (%s); totals %s",
            outcome,
            media_id,
            reason or "no reason",
            dict(counts),
        )


async def acquire() -> bool:
    try:
        await asyncio.wait_for(_slot.acquire(), timeout=WAIT_SECONDS)
    except TimeoutError:
        return False
    return True


def release() -> None:
    _slot.release()


async def first_chunk(source_path: str, media_id: str) -> tuple[Any, bytes, str]:
    codec = await audio_codec(source_path)
    if codec is None:
        raise ConversionUnavailable("no audio stream")
    process = await asyncio.create_subprocess_exec(
        *ffmpeg_args(source_path, codec),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )
    if process.stdout is None:
        raise ConversionUnavailable("ffmpeg has no output pipe")
    try:
        head = await asyncio.wait_for(process.stdout.read(CHUNK_BYTES), timeout=FIRST_CHUNK_SECONDS)
    except TimeoutError:
        process.kill()
        await process.wait()
        raise ConversionUnavailable("ffmpeg wrote nothing in time") from None
    if not head:
        code = await process.wait()
        raise ConversionUnavailable(f"ffmpeg wrote nothing (exit {code})")
    return process, head, "copied" if codec == "aac" else "encoded"


class ConversionBroke(Exception):
    pass


async def rest(
    process: Any, head: bytes, media_id: str, mode: str, cleanup: Any
) -> AsyncIterator[bytes]:
    finished = False
    try:
        yield head
        while process.stdout is not None:
            chunk = await process.stdout.read(CHUNK_BYTES)
            if not chunk:
                break
            yield chunk
        code = await process.wait()
        finished = True
        if code != 0:
            record("failed", media_id, f"ffmpeg exited {code} mid-stream")
            raise ConversionBroke(f"ffmpeg exited {code}")
        record(mode, media_id)
    finally:
        if not finished and process.returncode is None:
            process.kill()
            await process.wait()
            record("cancelled", media_id)
        release()
        cleanup()
