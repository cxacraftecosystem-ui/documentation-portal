from datetime import datetime

from pydantic import Field

from app.schemas.common import APIModel


class ManagedSecretDto(APIModel):
    key: str
    label: str
    description: str | None = None
    configured: bool
    source: str
    hint: str | None = None
    lastStatus: str = "UNKNOWN"
    lastCheckedAt: datetime | None = None
    lastError: str | None = None
    updatedBy: str | None = None
    updatedAt: datetime | None = None


class ManagedSecretSetIn(APIModel):
    value: str = Field(min_length=1, max_length=8000)


class ManagedSecretRevealDto(APIModel):
    key: str
    value: str | None = None
    source: str
