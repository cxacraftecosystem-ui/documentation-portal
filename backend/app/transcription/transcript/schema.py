"""Request bodies of the transcript API (``shared/transcript-contract.json`` ``types``).

Pydantic only: no FastAPI, so the package stays importable without a portal.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

SpeakerRole = Literal["RESEARCHER", "ARTISAN", "OTHER"]
Track = Literal["original", "english", "both"]


class _Body(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SpeakerMapping(_Body):
    speakerKey: str = Field(min_length=1, max_length=64)
    name: str | None = Field(default=None, max_length=80)
    role: SpeakerRole | None = None
    personId: str | None = Field(default=None, max_length=64)

    @field_validator("name")
    @classmethod
    def _name(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = " ".join(value.split())
        return cleaned or None


class SpeakerMappingBody(_Body):
    baseRevision: int = Field(ge=0)
    mappings: list[SpeakerMapping] = Field(min_length=1, max_length=40)


class TurnEditBody(_Body):
    baseRevision: int = Field(ge=0)
    text: str | None = Field(default=None, min_length=1, max_length=20_000)
    english: str | None = Field(default=None, max_length=20_000)


class CopyBody(_Body):
    track: Track
    turnIds: list[str] | None = Field(default=None, max_length=5_000)
    single: bool = False


class AskBody(_Body):
    question: str = Field(min_length=3, max_length=500)


class QuoteCardBody(_Body):
    turnIds: list[str] = Field(min_length=1, max_length=6)


class BudgetBody(_Body):
    monthlyUsd: float = Field(ge=0, le=100_000)
