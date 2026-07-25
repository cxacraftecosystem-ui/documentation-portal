"""Request models for the task-assignment system.

An assignment is described by a SCOPE — the five dimensions below — plus the people it is handed
to. One ``AssignedTask`` row is always exactly one assignee; assigning to N people writes N rows
sharing a ``batchId``, so "record tools for these 5 researchers" stays one manageable unit while
each person still owns (and reports progress on) their own row.

The scope dimensions are deliberately orthogonal, because an admin has to be able to say all of:
  - "record artisans AND products"             -> recordTypes = [artisan, product]
  - "record 10 tools"                          -> recordTypes = [tool], targetCount = 10
  - "record products for artisans A, B, C"     -> recordTypes = [product], artisanIds = [A, B, C]
  - "answer sections C, D, E for everyone"     -> sectionIds = [C, D, E], artisanIds = []
  - "answer section F for artisans A and B"    -> sectionIds = [F], artisanIds = [A, B]

Values are validated on the route (against the known record-type set and against real workshop /
artisan / section rows) rather than here, so the caller gets a specific "these ids do not exist"
message instead of a generic pydantic error.
"""
from datetime import datetime

from pydantic import Field

from app.schemas.common import APIModel

MAX_TITLE_LENGTH = 300

# A single assignment action may not fan out beyond this many people. Not a business rule so much
# as a blast-radius guard: N assignees means N rows written in one request.
MAX_ASSIGNEES = 100


class TaskScope(APIModel):
    """The shared scope block. ``None`` means "not supplied" (leave alone on an update); an empty
    list is a deliberate "not narrowed" / "clear this dimension"."""

    # The workshop the work belongs to. Nullable because a standing task ("record 10 tools") can
    # legitimately exist outside any one workshop, but the assignment screen normally sets it.
    workshopId: str | None = None
    # artisan | product | process | tool | questionnaire | media — validated on the route.
    recordTypes: list[str] | None = None
    # Empty/omitted = every artisan in scope for the assignee, not "no artisans".
    artisanIds: list[str] | None = None
    # QuestionnaireSection ids. Non-empty makes this (also) a questionnaire task.
    sectionIds: list[str] | None = None
    # How many records to produce. Null = "as many as apply".
    targetCount: int | None = Field(default=None, ge=1, le=100_000)


class TaskCreate(TaskScope):
    """Single-assignee create (the original endpoint). Kept so existing callers keep working; the
    scope fields are additive and validated exactly like the batch endpoint's."""

    title: str = Field(min_length=1, max_length=MAX_TITLE_LENGTH)
    description: str | None = None
    dueAt: datetime | None = None
    assigneeId: str
    # Legacy single-record link ("finish THIS product"), orthogonal to the scope block above.
    recordType: str | None = None
    recordId: str | None = None


class TaskBatchCreate(TaskScope):
    """Assign one scope to many people in a single action.

    ``title`` is optional: when it is omitted the route derives a readable one from the scope
    ("Record products and tools for 3 artisans", "Questionnaire sections C, D"), because a task list
    full of "Untitled task" is useless to the person who has to work through it.
    """

    assigneeIds: list[str] = Field(min_length=1, max_length=MAX_ASSIGNEES)
    title: str | None = Field(default=None, min_length=1, max_length=MAX_TITLE_LENGTH)
    description: str | None = None
    dueAt: datetime | None = None


class TaskUpdate(TaskScope):
    """Patch payload. Who may send which field is decided on the route: the assignee may only move
    ``status`` (never to CANCELLED) and report ``progressCount``; the creator/admin may change
    everything, scope included."""

    title: str | None = Field(default=None, min_length=1, max_length=MAX_TITLE_LENGTH)
    description: str | None = None
    # OPEN | IN_PROGRESS | DONE | CANCELLED — validated on the route (CANCELLED is creator/admin only).
    status: str | None = None
    dueAt: datetime | None = None
    assigneeId: str | None = None
    recordType: str | None = None
    recordId: str | None = None
    # Assignee-reported progress toward targetCount. Clamped to 0..targetCount on the route.
    progressCount: int | None = Field(default=None, ge=0, le=100_000)
