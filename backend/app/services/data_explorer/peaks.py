from __future__ import annotations

import asyncio
import shutil
import subprocess
import threading
from array import array
from typing import Any

BUCKETS = 800
SAMPLE_RATE = 2000
BLOCK = 50
MAX_SECONDS = 4 * 3600
DECODE_SECONDS = 120.0
WAIT_SECONDS = 30.0
READ_BYTES = 256 * 1024
KINDS = frozenset({"AUDIO", "VIDEO"})

_slot = asyncio.Semaphore(1)


class DecodeFailed(Exception):
    pass


class DecodeBusy(Exception):
    pass


def available() -> bool:
    return shutil.which("ffmpeg") is not None


def ffmpeg_args(source: str) -> list[str]:
    return [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-i",
        source,
        "-vn",
        "-t",
        str(MAX_SECONDS),
        "-ac",
        "1",
        "-ar",
        str(SAMPLE_RATE),
        "-f",
        "s16le",
        "pipe:1",
    ]


class _Reducer:
    def __init__(self) -> None:
        self.blocks = array("H")
        self.samples = 0
        self._carry = b""

    def feed(self, chunk: bytes) -> None:
        data = self._carry + chunk
        usable = len(data) - (len(data) % (BLOCK * 2))
        self._carry = data[usable:]
        if usable:
            self._reduce(data[:usable])

    def _reduce(self, data: bytes) -> None:
        samples = array("h")
        samples.frombytes(data)
        self.samples += len(samples)
        for start in range(0, len(samples), BLOCK):
            part = samples[start : start + BLOCK]
            high = max(part)
            low = -min(part)
            self.blocks.append(min(32767, max(low, high)))

    def finish(self) -> None:
        tail = self._carry[: len(self._carry) - (len(self._carry) % 2)]
        self._carry = b""
        if tail:
            self._reduce(tail)


def bucket(blocks: Any, buckets: int = BUCKETS) -> list[float]:
    count = len(blocks)
    if count == 0:
        return [0.0] * buckets
    out: list[int] = []
    for index in range(buckets):
        start = index * count // buckets
        end = max(start + 1, (index + 1) * count // buckets)
        out.append(max(blocks[start:min(end, count)]))
    loudest = max(out) or 1
    return [round(value / loudest, 3) for value in out]


def decode(source: str, *, timeout: float = DECODE_SECONDS) -> tuple[list[float], int | None]:
    try:
        process = subprocess.Popen(
            ffmpeg_args(source),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        raise DecodeFailed("ffmpeg could not start") from exc
    timer = threading.Timer(timeout, process.kill)
    timer.start()
    reducer = _Reducer()
    try:
        if process.stdout is None:
            raise DecodeFailed("ffmpeg has no output pipe")
        while True:
            chunk = process.stdout.read(READ_BYTES)
            if not chunk:
                break
            reducer.feed(chunk)
        code = process.wait()
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
            process.wait()
    reducer.finish()
    if code != 0 or reducer.samples == 0:
        raise DecodeFailed(f"ffmpeg exited {code} with {reducer.samples} samples")
    duration = int(reducer.samples * 1000 / SAMPLE_RATE)
    return bucket(reducer.blocks), duration


async def peaks_of(source: str) -> tuple[list[float], int | None]:
    if not available():
        raise DecodeFailed("ffmpeg is not installed")
    try:
        await asyncio.wait_for(_slot.acquire(), timeout=WAIT_SECONDS)
    except TimeoutError:
        raise DecodeBusy() from None
    try:
        return await asyncio.to_thread(decode, source)
    finally:
        _slot.release()
