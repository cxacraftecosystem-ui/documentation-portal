from __future__ import annotations

import contextlib
import os
import re
import shutil
import subprocess
from collections.abc import Callable
from dataclasses import dataclass

from app.transcription import workdir

Runner = Callable[[list[str]], "subprocess.CompletedProcess[str]"]

SAMPLE_RATE = 16_000
DEFAULT_PART_MS = 570_000
SEARCH_WINDOW_MS = 45_000
OVERLAP_MS = 1_000
SILENCE_NOISE = "-35dB"
SILENCE_MIN_S = 0.4


def _run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, timeout=1800, check=False)


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def probe_duration_ms(path: str, runner: Runner = _run) -> int | None:
    done = runner(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1",
            path,
        ]
    )
    if done.returncode != 0:
        return None
    try:
        return int(float(done.stdout.strip()) * 1000)
    except ValueError:
        return None


def normalise_to_wav(source: str, target: str, runner: Runner = _run) -> bool:
    done = runner(
        [
            "ffmpeg",
            "-nostdin",
            "-y",
            "-v",
            "error",
            "-i",
            source,
            "-vn",
            "-ac",
            "1",
            "-ar",
            str(SAMPLE_RATE),
            "-c:a",
            "pcm_s16le",
            target,
        ]
    )
    return done.returncode == 0 and os.path.exists(target)


VIDEO_AUDIO_SUFFIX = ".m4a"
VIDEO_AUDIO_MIME = "audio/mp4"


def is_video(media_type: str | None, mime_type: str | None) -> bool:
    return str(media_type or "").upper() == "VIDEO" or str(mime_type or "").lower().startswith(
        "video/"
    )


def extract_audio_track(source: str, target: str, runner: Runner = _run) -> bool:
    done = runner(
        [
            "ffmpeg",
            "-nostdin",
            "-y",
            "-v",
            "error",
            "-i",
            source,
            "-map",
            "0:a:0",
            "-vn",
            "-sn",
            "-dn",
            "-ac",
            "1",
            "-ar",
            str(SAMPLE_RATE),
            "-c:a",
            "aac",
            "-b:a",
            "48k",
            target,
        ]
    )
    return done.returncode == 0 and os.path.exists(target) and os.path.getsize(target) > 0


def extract_to_workdir(source: str, runner: Runner | None = None) -> str | None:
    runner = runner or _run
    with workdir.named_file(prefix="transcription_source_", suffix=VIDEO_AUDIO_SUFFIX) as handle:
        target = handle.name
    with contextlib.suppress(OSError, subprocess.SubprocessError):
        if extract_audio_track(source, target, runner):
            return target
    with contextlib.suppress(OSError):
        os.remove(target)
    return None


_SILENCE_START = re.compile(r"silence_start:\s*(-?[\d.]+)")
_SILENCE_END = re.compile(r"silence_end:\s*([\d.]+)")


def detect_silences(path: str, runner: Runner = _run) -> list[tuple[int, int]]:
    done = runner(
        [
            "ffmpeg",
            "-nostdin",
            "-v",
            "info",
            "-i",
            path,
            "-af",
            f"silencedetect=noise={SILENCE_NOISE}:d={SILENCE_MIN_S}",
            "-f",
            "null",
            "-",
        ]
    )
    silences: list[tuple[int, int]] = []
    start: float | None = None
    for line in (done.stderr or "").splitlines():
        if (match := _SILENCE_START.search(line)) is not None:
            start = max(0.0, float(match.group(1)))
        elif (match := _SILENCE_END.search(line)) is not None and start is not None:
            silences.append((int(start * 1000), int(float(match.group(1)) * 1000)))
            start = None
    return silences


@dataclass(frozen=True)
class PartPlan:
    index: int
    count: int
    start_ms: int
    end_ms: int
    own_start_ms: int

    @property
    def file_name(self) -> str:
        return f"chunk_{self.index + 1:03d}_of_{self.count:03d}.wav"


def plan_parts(
    duration_ms: int,
    silences: list[tuple[int, int]],
    part_ms: int = DEFAULT_PART_MS,
    window_ms: int = SEARCH_WINDOW_MS,
    overlap_ms: int = OVERLAP_MS,
) -> list[PartPlan]:
    if duration_ms <= part_ms:
        return [PartPlan(0, 1, 0, max(duration_ms, 0), 0)]
    cuts: list[int] = []
    position = 0
    while duration_ms - position > part_ms:
        target = position + part_ms - overlap_ms
        floor = max(position + 1, target - window_ms)
        choices = [(s + e) // 2 for s, e in silences if floor <= (s + e) // 2 <= target]
        cut = max(choices) if choices else target
        cuts.append(cut)
        position = cut
    bounds = [0, *cuts, duration_ms]
    count = len(bounds) - 1
    return [
        PartPlan(
            index=i,
            count=count,
            start_ms=max(0, bounds[i] - (overlap_ms if i else 0)),
            end_ms=bounds[i + 1],
            own_start_ms=bounds[i],
        )
        for i in range(count)
    ]


def cut_part(wav_path: str, part: PartPlan, directory: str, runner: Runner = _run) -> str | None:
    target = os.path.join(directory, part.file_name)
    done = runner(
        [
            "ffmpeg",
            "-nostdin",
            "-y",
            "-v",
            "error",
            "-ss",
            f"{part.start_ms / 1000:.3f}",
            "-t",
            f"{(part.end_ms - part.start_ms) / 1000:.3f}",
            "-i",
            wav_path,
            "-c",
            "copy",
            target,
        ]
    )
    return target if done.returncode == 0 and os.path.exists(target) else None


class PartCutter:
    def __init__(self, runner: Runner = _run, part_ms: int = DEFAULT_PART_MS) -> None:
        self.runner = runner
        self.part_ms = part_ms
        self._dir: str | None = None
        self._wav: str | None = None
        self.duration_ms: int | None = None

    def prepare(self, source_path: str) -> list[PartPlan] | None:
        if self._wav is None:
            self._dir = workdir.make_dir(prefix="transcription_parts_")
            wav = os.path.join(self._dir, "normalised_16k_mono.wav")
            if not normalise_to_wav(source_path, wav, self.runner):
                return None
            self._wav = wav
            self.duration_ms = probe_duration_ms(wav, self.runner)
        if not self.duration_ms:
            return None
        return plan_parts(self.duration_ms, detect_silences(self._wav, self.runner), self.part_ms)

    def cut(self, part: PartPlan) -> str | None:
        if self._wav is None or self._dir is None:
            return None
        return cut_part(self._wav, part, self._dir, self.runner)

    def discard(self, path: str | None) -> None:
        if path and self._wav and path != self._wav:
            try:
                os.remove(path)
            except OSError:
                pass

    def close(self) -> None:
        if self._dir:
            shutil.rmtree(self._dir, ignore_errors=True)
        self._dir = self._wav = None
