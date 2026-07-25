"""Request bodies for the two sharing systems.

*Data access* (``TIERS``, ``DataAccess*``) is researcher-to-researcher: one person's records opened
up to another. *Workshop access* (``Workshop*``) is admin-to-researcher: who may work inside one
workshop, and at what level. They are separate ladders on purpose — holding EDIT in a workshop says
nothing about another researcher's private data, and vice versa — but they share the same
request/decide vocabulary so the UIs read alike.
"""
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


# --------------------------------------------------------------------- workshop access
# Levels/statuses are validated against app.services.workshop_access, which owns the ladder and the
# rank comparisons; keeping the strings out of the schema stops the two definitions drifting apart.

# The most workshops ONE request call may name, and — deliberately the same number — the cap on the
# picker that feeds it (``GET /workshops/requestable``). One constant for both, because the two
# limits are the same promise seen from either end: everything the picker offers must be selectable
# in one go. Letting the list grow past the request cap would put options on screen that 422 the
# moment somebody ticks them all.
WORKSHOP_REQUEST_MAX = 200


class WorkshopAssignmentIn(BaseModel):
    """PUT the full set of researchers assigned to a workshop (replaces the existing set).

    ``userIds`` alone is the pre-levels shape the Android app still sends, so ``accessLevel`` is
    optional and defaults to CONTRIBUTE — the level that call has always effectively granted.
    """

    userIds: list[str] = Field(default_factory=list)
    accessLevel: str | None = None


class WorkshopGrantIn(BaseModel):
    """An admin grants ONE user access to a workshop at a level (upsert; re-grants a revoked row)."""

    userId: str = Field(min_length=1)
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAssignmentUpdateIn(BaseModel):
    """An admin changes one assignment: its level, its status, or both."""

    accessLevel: str | None = None
    status: str | None = None  # GRANTED | DENIED | REVOKED
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAccessRequestIn(BaseModel):
    """A user asks for access to one or more workshops in a single call.

    Multi-select by design: a researcher joining a project needs the same access to a whole season of
    workshops, and making them file them one at a time produces a queue nobody works through.
    """

    workshopIds: list[str] = Field(default_factory=list, max_length=WORKSHOP_REQUEST_MAX)
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class WorkshopAccessDecisionIn(BaseModel):
    """An admin approves or denies a pending workshop access request."""

    status: str  # GRANTED | DENIED
    accessLevel: str | None = None
    note: str | None = Field(default=None, max_length=2000)
