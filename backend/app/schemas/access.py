from pydantic import Field

from app.schemas.blank_text import BlankTextModel

TIERS = {"DOWNLOAD", "COMMENT", "EDIT"}


class ScopeItemIn(BlankTextModel):
    recordType: str = Field(min_length=1, max_length=60)
    recordId: str = Field(min_length=1)


class DataAccessRequestIn(BlankTextModel):
    ownerId: str = Field(min_length=1)
    tier: str = "DOWNLOAD"
    allData: bool = True
    scopeItems: list[ScopeItemIn] = Field(default_factory=list)
    requestNote: str | None = Field(default=None, max_length=2000)


class DataAccessGrantIn(BlankTextModel):
    granteeId: str = Field(min_length=1)
    tier: str = "DOWNLOAD"
    allData: bool = True
    scopeItems: list[ScopeItemIn] = Field(default_factory=list)
    decisionNote: str | None = Field(default=None, max_length=2000)


class DataAccessDecisionIn(BlankTextModel):
    status: str
    tier: str | None = None
    allData: bool | None = None
    scopeItems: list[ScopeItemIn] | None = None
    decisionNote: str | None = Field(default=None, max_length=2000)


class DataAccessUpdateIn(BlankTextModel):
    tier: str | None = None
    allData: bool | None = None
    scopeItems: list[ScopeItemIn] | None = None
    status: str | None = None


class CommentIn(BlankTextModel):
    recordType: str = Field(min_length=1, max_length=60)
    recordId: str = Field(min_length=1)
    body: str = Field(min_length=1, max_length=5000)


class CommentUpdate(BlankTextModel):
    body: str = Field(min_length=1, max_length=5000)


WORKSHOP_REQUEST_MAX = 200


class WorkshopAssignmentIn(BlankTextModel):
    userIds: list[str] = Field(default_factory=list)
    accessLevel: str | None = None


class WorkshopGrantIn(BlankTextModel):
    userId: str = Field(min_length=1)
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAssignmentUpdateIn(BlankTextModel):
    accessLevel: str | None = None
    status: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAccessRequestIn(BlankTextModel):
    workshopIds: list[str] = Field(default_factory=list, max_length=WORKSHOP_REQUEST_MAX)
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAccessDecisionIn(BlankTextModel):
    status: str
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)
