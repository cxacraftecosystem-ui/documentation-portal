"""D37: the transcription working directory, and a download that never leaks its temp file.

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (``shared/transcription.sha256``).
Both portals' ``services/s3.download_to_temp`` write into ``app/transcription/workdir`` and must
leave nothing behind on any failure: a refused size, a failed transfer, a refusal mid-stream, a
close that fails, an interrupt, a full directory, and a caller cancelled while the thread runs.
No database, no network: the storage client is a fake.
"""

from __future__ import annotations

import asyncio
import os
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import s3
from app.transcription import audio, workdir


@pytest.fixture
def work(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    where = tmp_path / "work"
    monkeypatch.setenv(workdir.ENV_DIR, str(where))
    monkeypatch.delenv(workdir.ENV_MAX_MB, raising=False)
    monkeypatch.delenv(workdir.ENV_STALE_MINUTES, raising=False)
    monkeypatch.setattr(s3, "get_settings", lambda: SimpleNamespace(aws_s3_bucket="bucket"))
    return where


def _files(where: Path) -> list[str]:
    return sorted(p.name for p in where.iterdir()) if where.exists() else []


class _Client:
    """``download_fileobj`` writing ``chunks`` then doing ``then`` (None, or an exception to raise)."""

    def __init__(self, chunks: list[bytes], then: BaseException | None = None, gate: threading.Event | None = None) -> None:
        self.chunks = chunks
        self.then = then
        self.gate = gate

    def download_fileobj(self, bucket: str, key: str, target: Any) -> None:
        for chunk in self.chunks:
            target.write(chunk)
        if self.gate is not None:
            self.gate.wait(5)
        if self.then is not None:
            raise self.then


def _use(monkeypatch: pytest.MonkeyPatch, client: _Client, head_size: int | None = None) -> None:
    monkeypatch.setattr(s3, "_client", lambda: client)
    monkeypatch.setattr(
        s3, "head_object", lambda key: None if head_size is None else SimpleNamespace(size_bytes=head_size)
    )


# --- the directory ----------------------------------------------------------------------------


def test_the_directory_is_created_where_configured(work: Path) -> None:
    assert not work.exists()
    assert workdir.path() == work and work.is_dir()


def test_the_default_lives_under_the_app_or_falls_back(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(workdir.ENV_DIR, raising=False)
    where = workdir.path()
    backend = Path(workdir.__file__).resolve().parents[2]
    assert where == backend / "var" / "work" or where.name == "transcription-work"


def test_a_sweep_removes_only_our_stale_entries(work: Path) -> None:
    where = workdir.path()
    old = time.time() - 4 * 3600
    for name in ("s3obj-old", "transcription_source_old", "someone-elses.txt"):
        (where / name).write_bytes(b"x")
        os.utime(where / name, (old, old))
    (where / "transcription_parts_old").mkdir()
    (where / "transcription_parts_old" / "part.wav").write_bytes(b"x")
    os.utime(where / "transcription_parts_old", (old, old))
    (where / "s3obj-fresh").write_bytes(b"x")
    assert workdir.sweep() == 3
    assert _files(where) == ["s3obj-fresh", "someone-elses.txt"]


def test_the_worker_start_sweeps(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(workdir.ENV_STALE_MINUTES, "1")
    where = workdir.path()
    (where / "s3obj-left-by-a-killed-run").write_bytes(b"x")
    os.utime(where / "s3obj-left-by-a-killed-run", (time.time() - 120, time.time() - 120))
    assert workdir.on_worker_start() == 1
    assert _files(where) == []


def test_a_full_directory_sweeps_then_refuses(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(workdir.ENV_MAX_MB, "1")
    where = workdir.path()
    (where / "s3obj-busy").write_bytes(b"x" * (1024 * 1024))
    with pytest.raises(workdir.WorkDirFull):
        workdir.named_file(prefix="s3obj-")
    assert _files(where) == ["s3obj-busy"], "a file still in use is not swept"
    old = time.time() - 4 * 3600
    os.utime(where / "s3obj-busy", (old, old))
    handle = workdir.named_file(prefix="s3obj-")
    handle.close()
    assert _files(where) == [Path(handle.name).name], "the stale file made room"


def test_only_our_prefixes_are_handed_out(work: Path) -> None:
    with pytest.raises(ValueError):
        workdir.named_file(prefix="other-")
    with pytest.raises(ValueError):
        workdir.make_dir(prefix="other-")


def test_parts_are_cut_in_the_working_directory(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    cutter = audio.PartCutter()
    monkeypatch.setattr(audio, "normalise_to_wav", lambda source, wav, runner: False)
    assert cutter.prepare("missing.m4a") is None
    assert cutter._dir is not None and Path(cutter._dir).parent == work
    cutter.close()
    assert _files(work) == []


# --- download_to_temp: every exit -----------------------------------------------------------


def test_a_download_lands_in_the_working_directory(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _use(monkeypatch, _Client([b"abc", b"def"]))
    path = s3.download_to_temp("k/one.m4a", suffix=".m4a")
    try:
        assert Path(path).parent == work and path.endswith(".m4a")
        assert Path(path).read_bytes() == b"abcdef"
    finally:
        s3.discard_temp(path)
    assert _files(work) == []


def test_a_size_refused_by_head_creates_nothing(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _use(monkeypatch, _Client([b"never"]), head_size=10_000)
    with pytest.raises(s3.ObjectTooLarge):
        s3.download_to_temp("k", max_bytes=100)
    assert _files(work) == []


def test_a_failed_transfer_removes_its_file(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _use(monkeypatch, _Client([b"half"], then=ConnectionError("reset")))
    with pytest.raises(ConnectionError):
        s3.download_to_temp("k")
    assert _files(work) == []


def test_a_refusal_mid_stream_removes_its_file(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _use(monkeypatch, _Client([b"x" * 60, b"x" * 60]))
    with pytest.raises(s3.ObjectTooLarge):
        s3.download_to_temp("k", max_bytes=100)
    assert _files(work) == []


def test_an_interrupt_removes_its_file(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _use(monkeypatch, _Client([b"half"], then=KeyboardInterrupt()))
    with pytest.raises(KeyboardInterrupt):
        s3.download_to_temp("k")
    assert _files(work) == []


def test_a_close_that_fails_removes_its_file(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    real = workdir.named_file

    class _Failing:
        def __init__(self, handle: Any) -> None:
            self._handle = handle
            self.name = handle.name

        def close(self) -> None:
            self._handle.close()
            raise OSError("No space left on device")

        def __getattr__(self, name: str) -> Any:
            return getattr(self._handle, name)

    monkeypatch.setattr(workdir, "named_file", lambda **kw: _Failing(real(**kw)))
    _use(monkeypatch, _Client([b"all of it"]))
    with pytest.raises(OSError, match="No space"):
        s3.download_to_temp("k")
    assert _files(work) == []


def test_a_full_working_directory_refuses_before_a_byte_moves(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(workdir.ENV_MAX_MB, "1")
    (workdir.path() / "s3obj-busy").write_bytes(b"x" * (1024 * 1024))
    client = _Client([b"never"])
    _use(monkeypatch, client)
    with pytest.raises(workdir.WorkDirFull):
        s3.download_to_temp("k")
    assert _files(work) == ["s3obj-busy"]


def test_a_cancelled_caller_leaves_nothing_behind(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    gate = threading.Event()
    _use(monkeypatch, _Client([b"the whole recording"], gate=gate))

    async def scenario() -> None:
        task = asyncio.create_task(s3.download_to_temp_async("k"))
        while not _files(work):
            await asyncio.sleep(0.01)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        gate.set()  # the thread finishes the download nobody is waiting for any more
        for _ in range(200):
            if not _files(work):
                return
            await asyncio.sleep(0.01)

    asyncio.run(scenario())
    assert _files(work) == []


def test_an_uncancelled_async_download_is_the_callers(work: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _use(monkeypatch, _Client([b"abc"]))
    path = asyncio.run(s3.download_to_temp_async("k"))
    assert Path(path).read_bytes() == b"abc"
    s3.discard_temp(path)
    assert _files(work) == []
