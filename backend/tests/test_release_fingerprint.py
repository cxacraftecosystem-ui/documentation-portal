from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from app import release


def _tree(root: Path) -> Path:
    backend = root / "backend"
    (backend / "app" / "services").mkdir(parents=True)
    (backend / "prisma" / "migrations" / "1_init").mkdir(parents=True)
    (backend / "app" / "main.py").write_bytes(b"app = 1\n")
    (backend / "app" / "services" / "x.py").write_bytes(b"x = 2\n")
    (backend / "prisma" / "schema.prisma").write_bytes(b"model A {}\n")
    (backend / "prisma" / "migrations" / "1_init" / "migration.sql").write_bytes(b"select 1;\n")
    return backend


def test_line_endings_caches_and_runtime_files_do_not_change_it(tmp_path: Path) -> None:
    backend = _tree(tmp_path)
    before = release.fingerprint(backend)
    (backend / "app" / "main.py").write_bytes(b"app = 1\r\n")
    (backend / "app" / "__pycache__").mkdir()
    (backend / "app" / "__pycache__" / "main.cpython-315.pyc").write_bytes(b"\0")
    (backend / "app" / "__pycache__" / "stray.py").write_bytes(b"y = 3\n")
    (backend / ".env").write_bytes(b"SECRET=1\n")
    (backend / "app" / "data.json").write_bytes(b"{}\n")
    assert release.fingerprint(backend) == before


def test_code_schema_and_migrations_change_it(tmp_path: Path) -> None:
    backend = _tree(tmp_path)
    seen = {release.fingerprint(backend)}
    for rel, body in (
        ("app/services/x.py", b"x = 3\n"),
        ("prisma/schema.prisma", b"model B {}\n"),
        ("prisma/migrations/1_init/migration.sql", b"select 2;\n"),
        ("app/services/new.py", b"\n"),
    ):
        (backend / rel).write_bytes(body)
        seen.add(release.fingerprint(backend))
    assert len(seen) == 5


def test_the_command_line_prints_what_health_reports() -> None:
    printed = subprocess.run(
        [sys.executable, str(Path(release.__file__)), str(release.BACKEND)],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    assert printed == release.fingerprint() == release.live_release()
    assert len(printed) == 64
