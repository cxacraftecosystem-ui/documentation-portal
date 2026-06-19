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


class MediaRelinkRequest(APIModel):
    """Re-attach an orphaned/mis-linked media file to an existing record of the given type."""

    linkedRecordType: str = Field(min_length=1, max_length=60)
    linkedRecordId: str = Field(min_length=1, max_length=60)


class TranscriptRefineRequest(APIModel):
    """Refine a media file's transcript into a clean interviewer/interviewee conversation; when
    ``translate`` is true the conversation is also translated into English."""

    translate: bool = False


class PresignResponse(APIModel):
    uploadUrl: str
    method: str = "PUT"
    objectKey: str
    bucket: str
    headers: dict[str, str]
    publicUrl: str | None = None


class MultipartCreateRequest(APIModel):
    filename: str = Field(min_length=1, max_length=255)
    mimeType: str = Field(min_length=1, max_length=180)
    mediaType: str
    sizeBytes: int = Field(gt=0)
    linkedRecordType: str | None = None
    linkedRecordId: str | None = None


class MultipartCreateResponse(APIModel):
    objectKey: str
    uploadId: str
    bucket: str
    partSize: int
    partCount: int
    publicUrl: str | None = None


class MultipartPresignPartsRequest(APIModel):
    objectKey: str = Field(min_length=1)
    uploadId: str = Field(min_length=1)
    partNumbers: list[int]


class MultipartPresignPartsResponse(APIModel):
    urls: dict[str, str]


class CompletedPartInput(APIModel):
    partNumber: int = Field(ge=1)
    etag: str = Field(min_length=1)


class MultipartCompleteRequest(APIModel):
    objectKey: str = Field(min_length=1)
    uploadId: str = Field(min_length=1)
    parts: list[CompletedPartInput]


class MultipartCompleteResponse(APIModel):
    objectKey: str
    bucket: str
    publicUrl: str | None = None


class MultipartAbortRequest(APIModel):
    objectKey: str = Field(min_length=1)
    uploadId: str = Field(min_length=1)


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
