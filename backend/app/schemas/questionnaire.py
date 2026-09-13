from datetime import datetime
from typing import Any

from pydantic import Field, model_validator

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


class QuestionnaireUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    description: str | None = Field(default=None, max_length=2000)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None
    # `isDefault` is DELIBERATELY ABSENT. Choosing the default instrument is an admin act with a
    # route of its own (PUT /questionnaires/{id}/default, require_admin), while renaming an
    # instrument is a questionnaire manager's (require_questionnaire_manager). Accepting the flag
    # here would hand the narrower power to the wider tier, silently, through a field. APIModel is
    # extra="forbid", so a client that sends it gets a 422 rather than being quietly ignored.


class QuestionnaireDefaultUpdate(APIModel):
    """PUT /questionnaires/{id}/default. A body rather than a bare PUT so "make this the default"
    and "clear the default" are the same route with different content."""

    isDefault: bool = True


class WorkshopQuestionnaireUpdate(APIModel):
    """PUT /workshops/{id}/questionnaire. `null` detaches, which resolves the workshop back to the
    default instrument rather than leaving it without one."""

    questionnaireId: str | None = None
    # Set true to bind anyway when open tasks hold sections of the OUTGOING instrument. Default
    # false, so the 409 is the answer an admin gets unless they say otherwise — see workshops.py.
    reassignTasks: bool = False


class QuestionnaireSectionCreate(APIModel):
    # WHICH INSTRUMENT THE SECTION IS BEING ADDED TO. OPTIONAL, and it has to be: the backend
    # deploys DAYS before the web and Android builders that learn to send it (rollout steps 5 and
    # 10), and a required field here would 422 every un-updated builder's "add a section" in the
    # meantime. Absent means the default instrument, resolved exactly the way an interview create
    # with no questionnaireId resolves — services/questionnaire_instruments.resolve_questionnaire_id.
    # The same argument the interview path makes ("an old client must keep working") applies here,
    # and the first draft of this change applied it to one and not the other.
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


# THE HELP TEXT CEILING, AND WHY IT IS NOT A NUMBER PICKED HERE ALONE.
#
# The workbook import clips an imported help text to this same constant and REPORTS the clip
# (app/api/routes/questionnaire.py, MAX_HELP_CHARS). The two have to agree: a help text imported at
# 4000 characters through a route with no cap would 422 on every later PATCH from the builder, with
# a pydantic message about a limit the admin never met and cannot see. One constant, imported by the
# import path, rather than two literals that drift.
MAX_HELP_CHARS = 2000


class QuestionnaireQuestionCreate(APIModel):
    sectionId: str = Field(min_length=1)
    prompt: str = Field(min_length=1)
    # DECLARED HERE AND WRITTEN BY THE HANDLER — both halves, or the field is a lie. APIModel is
    # extra="forbid", so without the declaration a builder sending helpText gets a 422; with the
    # declaration and no handler change it gets a 201 that stores nothing, which is worse. See
    # `create_question`, which passes both into the create dict explicitly rather than through
    # model_dump, because that route builds its data field by field.
    helpText: str | None = Field(default=None, max_length=MAX_HELP_CHARS)
    isRequired: bool = False
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool = True


class QuestionnaireQuestionUpdate(APIModel):
    sectionId: str | None = Field(default=None, min_length=1)
    prompt: str | None = Field(default=None, min_length=1)
    helpText: str | None = Field(default=None, max_length=MAX_HELP_CHARS)
    isRequired: bool | None = None
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None
    # `retiredAt` AND `supersededById` ARE DELIBERATELY ABSENT FROM BOTH. They belong to the
    # retirement machinery and are written only by the workbook import, never by an editor: a person
    # who could set `retiredAt` by hand could mark a question "retired because it has answers" when
    # it has none, and the re-upload path reads that flag to decide whether reactivating the question
    # is allowed. extra="forbid" means a client that sends either is refused rather than ignored.


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
    # The workshop this interview was conducted at. Optional: an omitted workshopId behaves exactly as
    # before, while a supplied one is subject to the workshop's assignment + submission-window rules.
    workshopId: str | None = None
    # WHICH INSTRUMENT. Optional, and it must stay optional: Android builds that shipped before
    # 2026-09-13 send no such field and must keep working. Absent means "the workshop's instrument,
    # or the default" — see services/questionnaire_instruments.resolve_questionnaire_id.
    questionnaireId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    artisanIds: list[str] = Field(default_factory=list)
    responses: list[QuestionnaireResponseInput] = Field(default_factory=list)
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create, like every other record type that carries a location.
    _location_required = model_validator(mode="after")(require_location)


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
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    artisanIds: list[str] | None = None
    responses: list[QuestionnaireResponseInput] | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # NO `questionnaireId` HERE, AND THAT IS THE ENFORCEMENT. A sitting never changes instrument:
    # it is half of @@unique([questionnaireId, artisanSetKey]), so moving one would silently make a
    # duplicate legal, and every answer already attached belongs to the questions of the instrument
    # it was taken on. `APIModel` is extra="forbid" (app/schemas/common.py), so a client that sends
    # the field gets a 422 from pydantic before this handler runs — no route code required, and no
    # route code to forget. `QuestionnaireSectionUpdate` above is silent for the same reason.
    #
    # Omit to keep, send to replace, never null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)
