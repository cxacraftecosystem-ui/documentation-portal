from __future__ import annotations

import hashlib
import json

from app.transcription.contract import AudioInput, SttOptions

_BLOCK = 1024 * 1024


def sha256_of(audio: AudioInput) -> str:
    digest = hashlib.sha256()
    if audio.source_path is not None:
        with open(audio.source_path, "rb") as handle:
            while block := handle.read(_BLOCK):
                digest.update(block)
    else:
        digest.update(audio.content or b"")
    return digest.hexdigest()


def options_hash(opts: SttOptions) -> str:
    material = {
        "diarize": bool(opts.diarize),
        "languageBias": sorted(opts.language_bias),
        "models": dict(sorted(opts.models.items())),
    }
    return hashlib.sha256(json.dumps(material, sort_keys=True).encode("utf-8")).hexdigest()[:32]
