from datetime import datetime
from typing import Any

from pydantic import Field

from app.schemas.common import APIModel, LocationInput


class QuestionnaireSectionCreate(APIModel):
    code: str = Field(min_length=1, max_length=24)
    title: str = Field(min_length=1, max_length=220)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool = True


class QuestionnaireSectionUpdate(APIModel):
    code: str | None = Field(default=None, min_length=1, max_length=24)
    title: str | None = Field(default=None, min_length=1, max_length=220)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None


class QuestionnaireSectionReorder(APIModel):
    sectionIds: list[str] = Field(min_length=1)


class QuestionnaireQuestionCreate(APIModel):
    sectionId: str = Field(min_length=1)
    prompt: str = Field(min_length=1)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool = True


class QuestionnaireQuestionUpdate(APIModel):
    sectionId: str | None = Field(default=None, min_length=1)
    prompt: str | None = Field(default=None, min_length=1)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None


class QuestionnaireQuestionReorder(APIModel):
    sectionId: str = Field(min_length=1)
    questionIds: list[str] = Field(min_length=1)


class QuestionnaireResponseInput(APIModel):
    questionId: str = Field(min_length=1)
    answerText: str | None = None
    notes: str | None = None


class QuestionnaireInterviewCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    interviewDate: datetime | None = None
    place: str | None = None
    language: str | None = None
    notes: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    artisanIds: list[str] = Field(default_factory=list)
    responses: list[QuestionnaireResponseInput] = Field(default_factory=list)
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None


class CompletionCellUpdate(APIModel):
    """Admin-set status for one (artisan, section) cell on the completion matrix. ``status=None``
    clears the manual override (falling back to data-derived completion)."""

    artisanId: str = Field(min_length=1)
    sectionId: str = Field(min_length=1)
    status: str | None = None


class QuestionnaireInterviewUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    interviewDate: datetime | None = None
    place: str | None = None
    language: str | None = None
    notes: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    artisanIds: list[str] | None = None
    responses: list[QuestionnaireResponseInput] | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
