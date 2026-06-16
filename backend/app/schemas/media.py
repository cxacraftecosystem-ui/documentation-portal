from datetime import datetime
from typing import Any

from pydantic import Field

from app.schemas.common import APIModel, LocationInput


class PresignRequest(APIModel):
    filename: str = Field(min_length=1, max_length=255)
    mimeType: str = Field(min_length=1, max_length=180)
    mediaType: str
    sizeBytes: int = Field(gt=0)
    linkedRecordType: str | None = None
    linkedRecordId: str | None = None


class PresignResponse(APIModel):
    uploadUrl: str
    method: str = "PUT"
    objectKey: str
    bucket: str
    headers: dict[str, str]
    publicUrl: str | None = None


class MediaCompleteRequest(APIModel):
    originalFilename: str = Field(min_length=1, max_length=255)
    mediaType: str
    mimeType: str = Field(min_length=1, max_length=180)
    sizeBytes: int = Field(gt=0)
    objectKey: str = Field(min_length=1)
    bucket: str | None = None
    url: str | None = None
    caption: str | None = None
    checksum: str | None = None
    transcriptText: str | None = None
    transcriptSummary: str | None = None
    transcriptStatus: str | None = None
    transcriptError: str | None = None
    linkedRecordType: str | None = None
    linkedRecordId: str | None = None
    processingRequests: list[str] | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
