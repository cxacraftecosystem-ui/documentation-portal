from __future__ import annotations

from app.transcription.providers.deepgram import Deepgram
from app.transcription.providers.elevenlabs import ElevenLabs
from app.transcription.providers.muse import Muse
from app.transcription.providers.whisper import Whisper

ADAPTERS = {
    "elevenlabs": ElevenLabs(),
    "deepgram": Deepgram(),
    "whisper": Whisper(),
    "muse": Muse(),
}

DISPLAY_NAMES = {provider: adapter.display_name for provider, adapter in ADAPTERS.items()}

KEY_NAMES = {provider: adapter.key_env for provider, adapter in ADAPTERS.items()}

TIMED_PROVIDERS = ("elevenlabs", "deepgram", "muse")
