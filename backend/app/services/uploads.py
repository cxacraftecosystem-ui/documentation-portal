from typing import Any

from fastapi import HTTPException, status

CHUNK_BYTES = 1024 * 1024

CONTENT_LENGTH_SLACK_BYTES = 64 * 1024


def _limit_label(max_bytes: int) -> str:
    megabytes = max_bytes / (1024 * 1024)
    return f"{megabytes:.0f} MB" if megabytes >= 1 else f"{megabytes:.2f} MB"


def _too_large(max_bytes: int, purpose: str, remedy: str | None = None) -> HTTPException:
    detail = f"That {purpose} is over the {_limit_label(max_bytes)} limit; send a smaller file."
    return HTTPException(
        status_code=status.HTTP_413_CONTENT_TOO_LARGE,
        detail=f"{detail} {remedy}" if remedy else detail,
    )


async def read_upload_bounded(
    file: Any,
    max_bytes: int,
    *,
    request: Any = None,
    purpose: str = "upload",
    remedy: str | None = None,
) -> bytes:
    if request is not None:
        declared = _declared_length(request)
        if declared is not None and declared > max_bytes + CONTENT_LENGTH_SLACK_BYTES:
            raise _too_large(max_bytes, purpose, remedy)

    buffer = bytearray()
    while True:
        chunk = await file.read(CHUNK_BYTES)
        if not chunk:
            break
        buffer += chunk
        if len(buffer) > max_bytes:
            raise _too_large(max_bytes, purpose, remedy)
    return bytes(buffer)


async def read_body_bounded(request: Any, max_bytes: int) -> bytes | None:
    declared = _declared_length(request)
    if declared is not None and declared > max_bytes:
        return None
    buffer = bytearray()
    async for chunk in request.stream():
        buffer += chunk
        if len(buffer) > max_bytes:
            return None
    return bytes(buffer)


def _declared_length(request: Any) -> int | None:
    try:
        raw = request.headers.get("content-length")
    except Exception:  # noqa: BLE001
        return None
    if raw is None:
        return None
    try:
        declared = int(str(raw).strip())
    except (TypeError, ValueError):
        return None
    return declared if declared >= 0 else None
