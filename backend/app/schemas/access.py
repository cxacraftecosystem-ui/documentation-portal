from pydantic import BaseModel, Field

TIERS = {"DOWNLOAD", "COMMENT", "EDIT"}


class ScopeItemIn(BaseModel):
    recordType: str = Field(min_length=1, max_length=60)
    recordId: str = Field(min_length=1)


class DataAccessRequestIn(BaseModel):
    ownerId: str = Field(min_length=1)
    tier: str = "DOWNLOAD"
    allData: bool = True
    scopeItems: list[ScopeItemIn] = []
    requestNote: str | None = Field(default=None, max_length=2000)


class DataAccessGrantIn(BaseModel):
    granteeId: str = Field(min_length=1)
    tier: str = "DOWNLOAD"
    allData: bool = True
    scopeItems: list[ScopeItemIn] = []
    decisionNote: str | None = Field(default=None, max_length=2000)


class DataAccessDecisionIn(BaseModel):
    status: str
    tier: str | None = None
    allData: bool | None = None
    scopeItems: list[ScopeItemIn] | None = None
    decisionNote: str | None = Field(default=None, max_length=2000)


class DataAccessUpdateIn(BaseModel):
    tier: str | None = None
    allData: bool | None = None
    scopeItems: list[ScopeItemIn] | None = None
    status: str | None = None


class CommentIn(BaseModel):
    recordType: str = Field(min_length=1, max_length=60)
    recordId: str = Field(min_length=1)
    body: str = Field(min_length=1, max_length=5000)


class CommentUpdate(BaseModel):
    body: str = Field(min_length=1, max_length=5000)


WORKSHOP_REQUEST_MAX = 200


class WorkshopAssignmentIn(BaseModel):
    userIds: list[str] = Field(default_factory=list)
    accessLevel: str | None = None


class WorkshopGrantIn(BaseModel):
    userId: str = Field(min_length=1)
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAssignmentUpdateIn(BaseModel):
    accessLevel: str | None = None
    status: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAccessRequestIn(BaseModel):
    workshopIds: list[str] = Field(default_factory=list, max_length=WORKSHOP_REQUEST_MAX)
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAccessDecisionIn(BaseModel):
    status: str
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)
