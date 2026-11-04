from __future__ import annotations

import io
import time
import zipfile
from collections.abc import AsyncIterator, Iterable


class _Sink(io.RawIOBase):
    def __init__(self) -> None:
        self._parts: list[bytes] = []
        self._size = 0

    def writable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return False

    def write(self, data: bytes | bytearray | memoryview) -> int:
        chunk = bytes(data)
        self._parts.append(chunk)
        self._size += len(chunk)
        return len(chunk)

    def tell(self) -> int:
        return self._size

    def drain(self) -> bytes:
        out = b"".join(self._parts)
        self._parts.clear()
        return out


class StreamingZip:
    def __init__(self) -> None:
        self._sink = _Sink()
        self._zip = zipfile.ZipFile(
            self._sink, mode="w", compression=zipfile.ZIP_DEFLATED, allowZip64=True
        )
        self._names: set[str] = set()

    def unique(self, name: str) -> str:
        parts = name.replace("\\", "/").split("/")
        clean = "/".join(part for part in parts if part not in ("", ".", ".."))
        clean = clean or "file"
        candidate = clean
        stem, dot, ext = clean.rpartition(".")
        if not dot or "/" in ext:
            stem, ext = clean, ""
        n = 2
        while candidate.lower() in self._names:
            candidate = f"{stem} ({n}).{ext}" if ext else f"{clean} ({n})"
            n += 1
        self._names.add(candidate.lower())
        return candidate

    def _info(self, name: str, *, stored: bool) -> zipfile.ZipInfo:
        info = zipfile.ZipInfo(self.unique(name), date_time=time.localtime()[:6])
        info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        return info

    def add_bytes(self, name: str, data: bytes, *, stored: bool = False) -> bytes:
        info = self._info(name, stored=stored)
        with self._zip.open(info, mode="w", force_zip64=len(data) > 0xFFFF0000) as handle:
            handle.write(data)
        return self._sink.drain()

    def add_chunks(
        self, name: str, chunks: Iterable[bytes], *, stored: bool = True
    ) -> Iterable[bytes]:
        with self._zip.open(self._info(name, stored=stored), mode="w", force_zip64=True) as handle:
            for chunk in chunks:
                handle.write(chunk)
                out = self._sink.drain()
                if out:
                    yield out
        tail = self._sink.drain()
        if tail:
            yield tail

    def close(self) -> bytes:
        self._zip.close()
        return self._sink.drain()


async def zip_bytes(entries: AsyncIterator[tuple[str, bytes]]) -> AsyncIterator[bytes]:
    archive = StreamingZip()
    async for name, data in entries:
        out = archive.add_bytes(name, data)
        if out:
            yield out
    yield archive.close()
