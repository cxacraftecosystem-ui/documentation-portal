from pydantic import BaseModel, Field

TIERS = {"DOWNLOAD", "COMMENT", "EDIT"}


class ScopeItemIn(BaseModel):
    recordType: str = Field(min_length=1, max_length=60)
    recordId: str = Field(min_length=1)


class DataAccessRequestIn(BaseModel):
    """A grantee asks an owner for access to their data."""
    ownerId: str = Field(min_length=1)
    tier: str = "DOWNLOAD"
    allData: bool = True
    scopeItems: list[ScopeItemIn] = []
    requestNote: str | None = Field(default=None, max_length=2000)


class DataAccessGrantIn(BaseModel):
    """An owner proactively grants a grantee access (no prior request needed)."""
    granteeId: str = Field(min_length=1)
    tier: str = "DOWNLOAD"
    allData: bool = True
    scopeItems: list[ScopeItemIn] = []
    decisionNote: str | None = Field(default=None, max_length=2000)


class DataAccessDecisionIn(BaseModel):
    """An owner approves/denies a pending request, optionally adjusting the tier/scope they give."""
    status: str  # GRANTED | DENIED
    tier: str | None = None
    allData: bool | None = None
    scopeItems: list[ScopeItemIn] | None = None
    decisionNote: str | None = Field(default=None, max_length=2000)


class DataAccessUpdateIn(BaseModel):
    """An owner changes an existing grant's tier/scope/status."""
    tier: str | None = None
    allData: bool | None = None
    scopeItems: list[ScopeItemIn] | None = None
    status: str | None = None  # GRANTED | REVOKED


class CommentIn(BaseModel):
    recordType: str = Field(min_length=1, max_length=60)
    recordId: str = Field(min_length=1)
    body: str = Field(min_length=1, max_length=5000)
