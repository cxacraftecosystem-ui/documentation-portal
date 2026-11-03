from typing import Literal

from app.schemas.common import APIModel


class PreferencesUpdateRequest(APIModel):
    theme: Literal["system", "light", "dark"] = "system"
    reducedMotion: bool = False
    largerText: bool = False
    highContrast: bool = False


class NotificationPreferencesUpdate(APIModel):
    emailTaskUpdates: bool
