"""The working directory for transcription's temporary files: sized, swept, and under the app.

WHY (D37, 31 Oct 2026). Every recording the queue transcribes is streamed to a temporary file, cut
into parts by ffmpeg and sent part by part. Those files used to go to the system temp directory.
On Field Repository that is ``/tmp`` with a per-user quota; a run of failed downloads left about
200 files (798 MB) behind, the quota filled, and transcription stopped until someone cleaned it.

NOW. Temporary files go to ONE directory under the app (``TRANSCRIPTION_WORK_DIR``, default
``backend/var/work``), which:

* is created on first use, private to the process user;
* has a size budget (``TRANSCRIPTION_WORK_DIR_MAX_MB``, default 2048): a new file that would start
  while the directory already holds that much first sweeps stale files and then, if it is still
  full, refuses with :class:`WorkDirFull` (the queue retries later) instead of filling the disk;
* is swept of stale files (older than ``TRANSCRIPTION_WORK_STALE_MINUTES``, default 180, longer than
  any job runs) when the queue worker starts and before each new file, so a process killed in
  the middle of a job (deploy, OOM, reboot) cannot leave files behind for ever.

Only names this module hands out are ever swept (the :data:`PREFIXES`), so pointing the variable at
a shared directory by mistake deletes nothing else.

Pure standard library: part of the shared transcription package (``shared/transcription.sha256``),
used by both portals' ``services/s3.download_to_temp`` and by ``audio.PartCutter`` / ``chain``.
"""

from __future__ import annotations

import logging
import os
import shutil
import tempfile
import time
from pathlib import Path
from typing import IO, Any

logger = logging.getLogger(__name__)

ENV_DIR = "TRANSCRIPTION_WORK_DIR"
ENV_MAX_MB = "TRANSCRIPTION_WORK_DIR_MAX_MB"
ENV_STALE_MINUTES = "TRANSCRIPTION_WORK_STALE_MINUTES"

DEFAULT_MAX_MB = 2048
DEFAULT_STALE_MINUTES = 180

#: Every name this module creates starts with one of these; nothing else is ever deleted.
PREFIXES = ("s3obj-", "transcription_source_", "transcription_parts_", "work-")

#: backend/ (this file is backend/app/transcription/workdir.py).
_BACKEND = Path(__file__).resolve().parents[2]


class WorkDirFull(OSError):
    """The working directory is at its size budget even after a sweep: try again later."""

    def __init__(self, used: int, limit: int) -> None:
        super().__init__(f"Transcription working directory is full ({used} of {limit} bytes).")
        self.used = used
        self.limit = limit


def _int_env(name: str, default: int) -> int:
    try:
        value = int(str(os.environ.get(name, "")).strip() or default)
    except ValueError:
        return default
    return value if value > 0 else default


def path() -> Path:
    """The directory, created (mode 0700) when missing.

    A read-only app tree (a container whose code belongs to root) cannot hold it; the default then
    falls back to ``<system temp>/transcription-work``, still sized and swept. A configured
    directory that cannot be created is an error: someone chose it.
    """
    configured = str(os.environ.get(ENV_DIR, "")).strip()
    if configured:
        where = Path(configured).expanduser()
        where.mkdir(parents=True, exist_ok=True, mode=0o700)
        return where
    where = _BACKEND / "var" / "work"
    try:
        where.mkdir(parents=True, exist_ok=True, mode=0o700)
        if os.access(where, os.W_OK):
            return where
    except OSError:
        pass
    fallback = Path(tempfile.gettempdir()) / "transcription-work"
    fallback.mkdir(parents=True, exist_ok=True, mode=0o700)
    return fallback


def max_bytes() -> int:
    return _int_env(ENV_MAX_MB, DEFAULT_MAX_MB) * 1024 * 1024


def stale_seconds() -> int:
    return _int_env(ENV_STALE_MINUTES, DEFAULT_STALE_MINUTES) * 60


def _ours(name: str) -> bool:
    return name.startswith(PREFIXES)


def _size(entry: Path) -> int:
    try:
        if entry.is_dir():
            return sum(p.stat().st_size for p in entry.rglob("*") if p.is_file())
        return entry.stat().st_size
    except OSError:
        return 0


def used_bytes(where: Path | None = None) -> int:
    where = where or path()
    try:
        return sum(_size(entry) for entry in where.iterdir() if _ours(entry.name))
    except OSError:
        return 0


def _remove(entry: Path) -> bool:
    try:
        if entry.is_dir():
            shutil.rmtree(entry)
        else:
            entry.unlink()
        return True
    except FileNotFoundError:
        return False
    except OSError as exc:
        logger.warning("transcription workdir: could not remove %s (%s)", entry.name, type(exc).__name__)
        return False


def sweep(*, older_than_s: int | None = None, now: float | None = None) -> int:
    """Remove our files and folders last modified more than ``older_than_s`` ago (default: the stale
    age). Returns how many entries went. Never raises."""
    try:
        where = path()
        entries = list(where.iterdir())
    except OSError as exc:
        logger.warning("transcription workdir: sweep skipped (%s)", type(exc).__name__)
        return 0
    limit = stale_seconds() if older_than_s is None else older_than_s
    moment = time.time() if now is None else now
    removed = 0
    for entry in entries:
        if not _ours(entry.name):
            continue
        try:
            age = moment - entry.stat().st_mtime
        except OSError:
            continue
        if age >= limit and _remove(entry):
            removed += 1
    if removed:
        logger.info("transcription workdir: removed %d stale entries", removed)
    return removed


def on_worker_start() -> int:
    """What the queue worker calls once when it starts: create the directory, sweep stale files."""
    return sweep()


def _room() -> Path:
    """The directory, after making sure a new file may start in it."""
    where = path()
    limit = max_bytes()
    if used_bytes(where) < limit:
        return where
    sweep()
    used = used_bytes(where)
    if used >= limit:
        raise WorkDirFull(used, limit)
    return where


def named_file(*, prefix: str = "work-", suffix: str = "") -> IO[Any]:
    """``NamedTemporaryFile(delete=False)`` in the working directory. The caller removes it."""
    if not _ours(prefix):
        raise ValueError(f"prefix must start with one of {PREFIXES}")
    return tempfile.NamedTemporaryFile(delete=False, prefix=prefix, suffix=suffix, dir=_room())


def make_dir(*, prefix: str = "work-") -> str:
    """``mkdtemp`` in the working directory. The caller removes it."""
    if not _ours(prefix):
        raise ValueError(f"prefix must start with one of {PREFIXES}")
    return tempfile.mkdtemp(prefix=prefix, dir=_room())


async def owned(fn: Any, *args: Any, **kwargs: Any) -> Any:
    """``await asyncio.to_thread(fn, ...)`` for a ``fn`` that returns a temp file's path, safe to cancel.

    A plain ``to_thread`` leaks when the awaiting task is cancelled (a job timeout, a shutdown): the
    thread cannot be stopped, finishes, and returns a path nobody receives. Here the thread's result
    is kept, and when the caller has gone by the time it arrives, the file is deleted.
    """
    import asyncio
    import functools

    loop = asyncio.get_running_loop()
    future = loop.run_in_executor(None, functools.partial(fn, *args, **kwargs))
    try:
        return await asyncio.shield(future)
    except asyncio.CancelledError:
        future.add_done_callback(_discard_orphan)
        raise


def _discard_orphan(future: Any) -> None:
    """The file a cancelled caller left behind, deleted when its thread finishes."""
    if future.cancelled() or future.exception() is not None:
        return
    result = future.result()
    if isinstance(result, (str, os.PathLike)):
        try:
            os.unlink(result)
        except OSError:
            pass
