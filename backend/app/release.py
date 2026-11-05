from __future__ import annotations

import hashlib
import sys
from functools import cache
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
PATTERNS = ("app/**/*.py", "prisma/schema.prisma", "prisma/migrations/*/migration.sql")


def _files(backend: Path) -> list[Path]:
    found = {
        path
        for pattern in PATTERNS
        for path in backend.glob(pattern)
        if path.is_file() and "__pycache__" not in path.relative_to(backend).parts
    }
    return sorted(found, key=lambda path: path.relative_to(backend).as_posix())


def fingerprint(backend: Path = BACKEND) -> str:
    total = hashlib.sha256()
    for path in _files(backend):
        rel = path.relative_to(backend).as_posix()
        body = hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        total.update(f"{body}  {rel}\n".encode())
    return total.hexdigest()


@cache
def live_release() -> str:
    try:
        return fingerprint()
    except OSError:
        return "unknown"


if __name__ == "__main__":
    print(fingerprint(Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else BACKEND))
