"""Same bytes, same options: one provider call. The cache key and the checksum."""

from __future__ import annotations

import hashlib
import json

from app.transcription.contract import AudioInput, SttOptions

_BLOCK = 1024 * 1024


def sha256_of(audio: AudioInput) -> str:
    """SHA-256 of the recording, read in 1 MB blocks so a large file never sits in the heap."""
    digest = hashlib.sha256()
    if audio.source_path is not None:
        with open(audio.source_path, "rb") as handle:
            while block := handle.read(_BLOCK):
                digest.update(block)
    else:
        digest.update(audio.content or b"")
    return digest.hexdigest()


def options_hash(opts: SttOptions) -> str:
    """What changes a transcript: diarization, the language hint and the model choices. Keyterms
    are left out on purpose; a vocabulary edit should not throw the whole cache away."""
    material = {
        "diarize": bool(opts.diarize),
        "languageBias": sorted(opts.language_bias),
        "models": dict(sorted(opts.models.items())),
    }
    return hashlib.sha256(json.dumps(material, sort_keys=True).encode("utf-8")).hexdigest()[:32]
