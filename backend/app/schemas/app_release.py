from pydantic import Field

from app.schemas.common import APIModel


class AppReleasePublishRequest(APIModel):
    """Recorded after the master admin's app has uploaded its own APK to object storage."""

    versionCode: int = Field(ge=1)
    versionName: str = Field(min_length=1, max_length=64)
    objectKey: str = Field(min_length=1)
    url: str | None = None
    notes: str | None = Field(default=None, max_length=2000)
