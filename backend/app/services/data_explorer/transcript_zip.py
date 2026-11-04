from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any

from app.services.data_explorer.zipstream import StreamingZip


def render_bytes(doc: Any, fmt: str, track: str, timestamps: bool) -> bytes:
    from app.transcription.transcript import render

    if fmt in {"srt", "vtt"} and track == "both":
        track = "original"
    if fmt == "md":
        return render.copy_all(doc, track, timestamps=timestamps).encode("utf-8")
    if fmt == "docx":
        return render.docx(doc, track, timestamps=timestamps)
    if fmt == "srt":
        return render.srt(doc, track).encode("utf-8")
    return render.vtt(doc, track).encode("utf-8")


def _timed(doc: Any) -> bool:
    return any(int(getattr(t, "end_ms", 0) or 0) > 0 for t in getattr(doc, "turns", []) or [])


def _has_english(doc: Any) -> bool:
    if (getattr(doc, "english_text", None) or "").strip():
        return True
    return any((getattr(t, "english", None) or "").strip() for t in getattr(doc, "turns", []) or [])


def _wanted(doc: Any, fmt: str, track: str, needs_timing: bool) -> bool:
    if needs_timing and not _timed(doc):
        return False
    return not (track == "english" and not _has_english(doc))


def one_transcript_files(media: Any, context: dict[str, Any], doc: Any) -> list[tuple[str, bytes]]:
    from app.services import transcript_export, transcript_pipeline

    out: list[tuple[str, bytes]] = []
    for what, _ext, fmt, track, stamps, needs in transcript_export.BUNDLE_FILES:
        if not _wanted(doc, fmt, track, needs):
            continue
        data = render_bytes(doc, fmt, track, stamps)
        if not data.strip():
            continue
        name = transcript_pipeline.download_name(media, context, fmt, track, what=what)
        out.append((name, data))
    return out


def bundle_name(media: Any, context: dict[str, Any]) -> str:
    from app.services import transcript_pipeline

    return transcript_pipeline.download_name(media, context, "zip", "both", what="Transcript-All-Formats")


async def one_transcript_zip(files: list[tuple[str, bytes]]) -> AsyncIterator[bytes]:
    archive = StreamingZip()
    for name, data in files:
        out = archive.add_bytes(name, data)
        if out:
            yield out
    yield archive.close()


async def folder_zip(
    files: list[dict[str, Any]], load_doc: Any, *, load_text: Any = None, readable: Any = None
) -> AsyncIterator[bytes]:
    archive = StreamingZip()
    cached: tuple[str, Any] | None = None
    written = 0
    for index, entry in enumerate(files):
        files[index] = None  # type: ignore[call-overload]
        if entry is None:
            continue
        path = str(entry.get("path") or "")
        content = entry.get("content")
        spec = entry.get("transcriptFile")
        media_id = str(entry.get("mediaId") or "")
        data: bytes | None = None
        if spec and media_id:
            if readable is not None and media_id not in readable:
                continue
            if cached is None or cached[0] != media_id:
                cached = (media_id, await load_doc(media_id))
            doc = cached[1]
            if doc is None:
                continue
            if not _wanted(doc, spec["format"], spec["track"], spec["format"] in {"srt", "vtt"}):
                continue
            data = render_bytes(doc, spec["format"], spec["track"], bool(spec.get("timestamps")))
        elif isinstance(content, str) and media_id:
            data = content.encode("utf-8")
        elif entry.get("_fill") and media_id and load_text is not None:
            data = str(await load_text(media_id) or "").encode("utf-8")
        if not data or not data.strip():
            continue
        out = archive.add_bytes(path, data)
        written += 1
        if out:
            yield out
    if not written:
        out = archive.add_bytes("No transcripts.txt", b"This folder has no transcripts yet.\n")
        if out:
            yield out
    yield archive.close()
