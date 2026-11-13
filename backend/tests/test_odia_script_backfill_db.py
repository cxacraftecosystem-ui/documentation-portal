from __future__ import annotations

import asyncio
import importlib.util
import os
import socket
import subprocess
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import pytest

BACKEND = Path(__file__).resolve().parents[1]
SCRIPT = BACKEND / "scripts" / "odia_script_backfill.py"

_DSN = os.environ.get("DATABASE_URL", "")
_PARTS = urlsplit(_DSN) if _DSN else None
_LOCAL = bool(_PARTS and _PARTS.hostname in {"localhost", "127.0.0.1", "::1"})


def _listening() -> bool:
    if not _LOCAL or _PARTS is None:
        return False
    try:
        with socket.create_connection((_PARTS.hostname, _PARTS.port or 5432), timeout=1.0):
            return True
    except OSError:
        return False


needs_db = pytest.mark.skipif(
    not _listening(),
    reason="needs an exported DATABASE_URL naming a running Postgres on this machine (never backend/.env)",
)

DEVANAGARI = "अ, तो madam, डॉ केबे"
ODIA = "ଅ, ତୋ madam, ଡ କେବେ"
EDITED = "तो कामु"
EDITED_ODIA = "ତୋ କାମୁ"
HINDI = "यह हिंदी है"


def _load() -> Any:
    spec = importlib.util.spec_from_file_location("odia_script_backfill", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_the_script_imports_no_service_or_transcription_module() -> None:
    probe = (
        "import importlib.util, sys\n"
        f"spec = importlib.util.spec_from_file_location('b', {str(SCRIPT)!r})\n"
        "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
        "heavy = sorted(n for n in sys.modules if n.startswith(('app.services', 'app.transcription', 'app.api')))\n"
        "print(','.join(heavy))\n"
    )
    out = subprocess.run([sys.executable, "-c", probe], cwd=BACKEND, capture_output=True, text=True, timeout=120, check=False)
    assert out.returncode == 0, out.stderr
    assert out.stdout.strip() == ""
    source = SCRIPT.read_text(encoding="utf-8")
    assert "app.services" not in source and "app.transcription" not in source


def _words(text: str) -> list[dict[str, Any]]:
    words: list[dict[str, Any]] = []
    offset = 0
    for token in text.split(" "):
        words.append({"text": token, "startMs": 0, "endMs": 0, "offset": offset})
        offset += len(token) + 1
    return words


async def _seed() -> dict[str, Any]:
    from app.core.db import db

    tag = uuid4().hex[:10]
    user = await db.user.create(data={"email": f"odia-{tag}@example.org", "name": "Odia", "role": "RESEARCHER"})
    made: dict[str, Any] = {"user": user, "media": []}

    async def media(name: str, metadata: Any) -> Any:
        data: dict[str, Any] = {
            "originalFilename": f"{name}.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 1024,
            "bucket": "ci",
            "objectKey": f"media/{user.id}/{tag}/{name}.m4a",
            "uploadedBy": {"connect": {"id": user.id}},
            "transcriptText": DEVANAGARI if name != "hindi" else HINDI,
        }
        if metadata is not None:
            from prisma import Json

            data["extraMetadata"] = Json(metadata)
        row = await db.mediafile.create(data=data)
        made["media"].append(row)
        return row

    async def version(row: Any, number: int, detected: str | None, text: str, *, edited: str | None = None, superseded: bool = False) -> Any:
        from datetime import UTC, datetime

        from prisma import Json

        v = await db.transcriptversion.create(
            data={
                "mediaFile": {"connect": {"id": row.id}},
                "version": number,
                "detectedLanguage": detected,
                "languages": ["hi", "en"],
                "turnCount": 1,
                "supersededAt": datetime.now(UTC) if superseded else None,
            }
        )
        await db.transcriptturn.create(
            data={
                "version": {"connect": {"id": v.id}},
                "mediaFileId": row.id,
                "position": 0,
                "language": "hi",
                "machineText": text,
                "editedText": edited,
                "words": Json(_words(text)),
                "lowConfidence": Json([[text.index("m"), text.index("m") + 5]]),
            }
        )
        return v

    detected = await media("detected", None)
    made["detected"] = await version(detected, 1, "or", DEVANAGARI, edited=EDITED)
    hinted = await media("hinted", {"languageHint": "or"})
    made["old"] = await version(hinted, 1, None, DEVANAGARI, superseded=True)
    made["hinted"] = await version(hinted, 2, "hi", DEVANAGARI)
    hindi = await media("hindi", {"languageHint": "hi"})
    made["hindi"] = await version(hindi, 1, "hi", HINDI)
    return made


async def _cleanup(made: dict[str, Any]) -> None:
    from app.core.db import db

    await db.mediafile.delete_many(where={"id": {"in": [m.id for m in made["media"]]}})
    await db.user.delete(where={"id": made["user"].id})


async def _turn(version_id: str) -> Any:
    from app.core.db import db

    return await db.transcriptturn.find_first(where={"versionId": version_id})


@needs_db
def test_dry_run_counts_then_apply_converts_only_odia() -> None:
    from app.core.db import db

    script = _load()

    async def scenario() -> None:
        await db.connect()
        made = await _seed()
        try:
            counts = await script.run(False)
            assert counts["versions"] >= 2 and counts["turns"] >= 2 and counts["edited"] >= 1 and counts["media"] >= 2
            assert counts["skipped"] >= 1
            for key in ("detected", "hinted", "old"):
                assert (await _turn(made[key].id)).machineText == DEVANAGARI, "a dry run changes nothing"
            assert (await db.mediafile.find_unique(where={"id": made["media"][0].id})).transcriptText == DEVANAGARI

            await script.run(True)
            turn = await _turn(made["detected"].id)
            assert turn.machineText == ODIA and turn.editedText == EDITED_ODIA and turn.language == "or"
            assert turn.revision == 2
            assert [turn.machineText[w["offset"] : w["offset"] + len(w["text"])] for w in turn.words] == [w["text"] for w in turn.words]
            assert all(not any(0x0900 <= ord(c) <= 0x097F for c in w["text"]) for w in turn.words)
            start = ODIA.index("m")
            assert turn.lowConfidence == [[start, start + 5]]
            version = await db.transcriptversion.find_unique(where={"id": made["detected"].id})
            assert version.languages == ["or", "en"] and version.revision == 2
            assert (await _turn(made["hinted"].id)).machineText == ODIA
            assert (await _turn(made["old"].id)).machineText == DEVANAGARI, "superseded versions stay"
            assert (await _turn(made["hindi"].id)).machineText == HINDI, "Hindi stays Devanagari"
            media = {m.id: await db.mediafile.find_unique(where={"id": m.id}) for m in made["media"]}
            assert media[made["media"][0].id].transcriptText == ODIA
            assert media[made["media"][1].id].transcriptText == ODIA
            assert media[made["media"][2].id].transcriptText == HINDI

            again = await script.run(True)
            mine = {made["detected"].id, made["hinted"].id}
            assert (await _turn(made["detected"].id)).revision == 2, "a second run finds nothing to do"
            assert all((await _turn(v)).machineText == ODIA for v in mine)
            assert again["skipped"] >= 1
        finally:
            await _cleanup(made)
            await db.disconnect()

    asyncio.run(scenario())
