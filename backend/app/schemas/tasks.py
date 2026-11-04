from datetime import datetime

from pydantic import Field

from app.schemas.common import APIModel

MAX_TITLE_LENGTH = 300

MAX_ASSIGNEES = 100


class TaskScope(APIModel):
    workshopId: str | None = None
    recordTypes: list[str] | None = None
    artisanIds: list[str] | None = None
    sectionIds: list[str] | None = None
    targetCount: int | None = Field(default=None, ge=1, le=100_000)


class TaskCreate(TaskScope):
    title: str = Field(min_length=1, max_length=MAX_TITLE_LENGTH)
    description: str | None = None
    dueAt: datetime | None = None
    assigneeId: str
    recordType: str | None = None
    recordId: str | None = None


class TaskBatchCreate(TaskScope):
    assigneeIds: list[str] = Field(min_length=1, max_length=MAX_ASSIGNEES)
    title: str | None = Field(default=None, min_length=1, max_length=MAX_TITLE_LENGTH)
    description: str | None = None
    dueAt: datetime | None = None


class TaskUpdate(TaskScope):
    title: str | None = Field(default=None, min_length=1, max_length=MAX_TITLE_LENGTH)
    description: str | None = None
    status: str | None = None
    dueAt: datetime | None = None
    assigneeId: str | None = None
    recordType: str | None = None
    recordId: str | None = None
    progressCount: int | None = Field(default=None, ge=0, le=100_000)
