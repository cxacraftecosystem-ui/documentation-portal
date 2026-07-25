from typing import Literal

from app.schemas.common import APIModel


class PreferencesUpdateRequest(APIModel):
    """A user's own appearance + accessibility preferences, sent whole on every save.

    Every field defaults, so a client that only knows about some of them still round-trips: the
    omitted ones fall back to "off" / "follow the system" rather than being rejected."""

    theme: Literal["system", "light", "dark"] = "system"
    reducedMotion: bool = False
    largerText: bool = False
    highContrast: bool = False
