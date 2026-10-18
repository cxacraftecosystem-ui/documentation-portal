"""Request and response models for /media/{id}/insights. Every model is ``extra="forbid"``.

``editedAt`` / ``editedById`` are deliberately absent from the PATCH body: the edit stamp comes
from the server clock and the server-resolved caller, so a body that tries to carry one is a 422.
"""

from typing import Literal

from pydantic import Field, field_validator, model_validator

from app.schemas.common import APIModel
from app.services.transcript_insights import (
    QUOTE_FIELD_MAX_CHARS,
    QUOTES_MAX_EDIT,
    SUMMARY_LINE_MAX_CHARS,
    SUMMARY_MAX_LINES,
    TRANSLATION_MAX_CHARS,
)


class InsightQuote(APIModel):
    original: str = Field(min_length=1, max_length=QUOTE_FIELD_MAX_CHARS)
    english: str = Field(default="", max_length=QUOTE_FIELD_MAX_CHARS)
    speaker: str | None = Field(default=None, max_length=QUOTE_FIELD_MAX_CHARS)

    @field_validator("original")
    @classmethod
    def _original_not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("A quote needs its original words.")
        return value.strip()

    @field_validator("english")
    @classmethod
    def _strip_english(cls, value: str) -> str:
        return value.strip()

    @field_validator("speaker")
    @classmethod
    def _blank_speaker_is_none(cls, value: str | None) -> str | None:
        return value.strip() or None if value is not None else None


class TranscriptInsightPatch(APIModel):
    version: int = Field(ge=1)
    summaryLines: list[str] | None = None
    quotes: list[InsightQuote] | None = Field(default=None, max_length=QUOTES_MAX_EDIT)
    translationText: str | None = Field(default=None, max_length=TRANSLATION_MAX_CHARS)

    @field_validator("summaryLines")
    @classmethod
    def _summary_lines(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
        lines = [line.strip() for line in value if isinstance(line, str) and line.strip()]
        if not 1 <= len(lines) <= SUMMARY_MAX_LINES:
            raise ValueError(f"A summary has 1 to {SUMMARY_MAX_LINES} lines.")
        if any(len(line) > SUMMARY_LINE_MAX_CHARS for line in lines):
            raise ValueError(f"Keep each line to {SUMMARY_LINE_MAX_CHARS} characters.")
        return lines

    @model_validator(mode="after")
    def _something_to_save(self) -> TranscriptInsightPatch:
        if self.summaryLines is None and self.quotes is None and self.translationText is None:
            raise ValueError("Nothing to save.")
        return self


class TranscriptInsightRegenerate(APIModel):
    discardEdits: bool = False


TranscriptInsightState = Literal[
    "READY", "QUEUED", "PROCESSING", "FAILED", "NO_TRANSCRIPT", "NOT_GENERATED"
]
