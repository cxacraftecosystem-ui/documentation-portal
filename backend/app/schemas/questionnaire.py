from datetime import datetime
from typing import Any

from typing import Literal

from pydantic import Field, field_validator, model_validator

from app.schemas.common import (
    APIModel,
    LocationInput,
    forbid_clearing_location,
    require_location,
)


class QuestionnaireCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    description: str | None = Field(default=None, max_length=2000)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool = True
    kind: str | None = Field(default=None, max_length=40)


class QuestionnaireUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    description: str | None = Field(default=None, max_length=2000)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None
    kind: str | None = Field(default=None, max_length=40)
    isShared: bool | None = None


class QuestionnaireReuse(APIModel):
    title: str | None = Field(default=None, max_length=220)
    description: str | None = Field(default=None, max_length=2000)
    kind: str | None = Field(default=None, max_length=40)


class QuestionnaireDefaultUpdate(APIModel):
    isDefault: bool = True


class WorkshopQuestionnaireUpdate(APIModel):
    questionnaireId: str | None = None
    reassignTasks: bool = False


class QuestionnaireSectionCreate(APIModel):
    questionnaireId: str | None = Field(default=None, min_length=1)
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


MAX_HELP_CHARS = 2000

ARTISAN_FIELD_KEYS: tuple[str, ...] = (
    "name",
    "localName",
    "craft",
    "place",
    "gender",
    "phone",
    "email",
    "dateOfBirth",
    "experienceYears",
    "address",
    "interviewDate",
    "interviewer",
)


def _check_artisan_field(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    if value not in ARTISAN_FIELD_KEYS:
        raise ValueError(f"artisanField must be one of {', '.join(ARTISAN_FIELD_KEYS)}")
    return value


class QuestionnaireQuestionCreate(APIModel):
    sectionId: str = Field(min_length=1)
    prompt: str = Field(min_length=1)
    helpText: str | None = Field(default=None, max_length=MAX_HELP_CHARS)
    isRequired: bool = False
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool = True
    artisanField: str | None = None

    _artisan_field = field_validator("artisanField")(lambda cls, v: _check_artisan_field(v))


class QuestionnaireQuestionUpdate(APIModel):
    sectionId: str | None = Field(default=None, min_length=1)
    prompt: str | None = Field(default=None, min_length=1)
    helpText: str | None = Field(default=None, max_length=MAX_HELP_CHARS)
    isRequired: bool | None = None
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None
    artisanField: str | None = None

    _artisan_field = field_validator("artisanField")(lambda cls, v: _check_artisan_field(v))


class QuestionnaireQuestionReorder(APIModel):
    sectionId: str = Field(min_length=1)
    questionIds: list[str] = Field(min_length=1)


class QuestionnaireResponseInput(APIModel):
    questionId: str = Field(min_length=1)
    answerText: str | None = None
    notes: str | None = None
    answerSource: Literal["TRANSCRIPT"] | None = None


class QuestionnaireInterviewCreate(APIModel):
    title: str | None = Field(default=None, max_length=220)
    interviewDate: datetime | None = None
    place: str | None = None
    language: str | None = None
    notes: str | None = None
    workshopId: str | None = None
    questionnaireId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    artisanIds: list[str] = Field(default_factory=list)
    responses: list[QuestionnaireResponseInput] = Field(default_factory=list)
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    _location_required = model_validator(mode="after")(require_location)


class CompletionCellUpdate(APIModel):
    artisanId: str = Field(min_length=1)
    sectionId: str = Field(min_length=1)
    status: str | None = None


class QuestionnaireInterviewUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    interviewDate: datetime | None = None
    place: str | None = None
    language: str | None = None
    notes: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    artisanIds: list[str] | None = None
    responses: list[QuestionnaireResponseInput] | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    _location_kept = model_validator(mode="after")(forbid_clearing_location)
