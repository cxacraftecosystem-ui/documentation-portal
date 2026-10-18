"""The four adapters, keyed by provider id."""

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

#: Human names, here so the web settings hub and the Android screen cannot call one engine two things.
DISPLAY_NAMES = {provider: adapter.display_name for provider, adapter in ADAPTERS.items()}

#: The environment / managed-secret name of each provider's key.
KEY_NAMES = {provider: adapter.key_env for provider, adapter in ADAPTERS.items()}

#: Providers that can answer with timings, for subtitles. Whisper (json) returns text only.
TIMED_PROVIDERS = ("elevenlabs", "deepgram", "muse")
