"""The ``shared/`` files are one copy across the three streams, and the generated outputs follow them.

``shared/manifest.json`` holds a sha256 per shared file (BUILD_PLAN 0.1). The WEB and ANDROID
streams copy ``shared/`` verbatim from feature/q-backend and run the same check, so a copy that was
edited in place fails here and there. ``scripts/generate_shared.py --check`` also holds the backend's
generated vocabulary, any client output that exists, and every Prisma enum that shares a name with
``shared/enums.json``.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_shared_files_match_the_manifest() -> None:
    manifest = json.loads((ROOT / "shared" / "manifest.json").read_text(encoding="utf-8"))["sha256"]
    for name, digest in manifest.items():
        actual = hashlib.sha256((ROOT / "shared" / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        assert actual == digest, f"shared/{name} differs from shared/manifest.json: copy it from feature/q-backend, never edit it"


def test_generated_outputs_and_prisma_enums_are_current() -> None:
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "generate_shared.py"), "--check"],
        capture_output=True, text=True, encoding="utf-8", check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_backend_vocabulary_is_the_shared_one() -> None:
    from app import shared_vocab

    enums = json.loads((ROOT / "shared" / "enums.json").read_text(encoding="utf-8"))["enums"]
    for name, values in enums.items():
        assert getattr(shared_vocab, name) == tuple(values)
    languages = json.loads((ROOT / "shared" / "languages.json").read_text(encoding="utf-8"))
    assert shared_vocab.LANGUAGE_LABELS["bn"] == "Bengali"
    assert set(shared_vocab.LANGUAGE_LABELS) == {lang["code"] for lang in languages["languages"]}


def test_every_string_key_is_unique_and_placeholders_are_named() -> None:
    import re

    strings = json.loads((ROOT / "shared" / "strings" / "questionnaire.json").read_text(encoding="utf-8"))["strings"]
    snake = [re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", key).lower() for key in strings]
    assert len(snake) == len(set(snake)), "two keys collapse to one Android resource name"
    for key, text in strings.items():
        assert "%" not in re.sub(r"\{[A-Za-z][A-Za-z0-9]*\}", "", text) or key == "uploadingPercent", key
        assert "{" not in re.sub(r"\{[A-Za-z][A-Za-z0-9]*\}", "", text), f"{key}: malformed placeholder"
