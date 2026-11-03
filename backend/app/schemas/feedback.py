from pydantic import Field

from app.schemas.common import APIModel


class FeedbackUpsertRequest(APIModel):
    rating: int | None = Field(default=None, ge=1, le=5)
    easeOfUse: int | None = Field(default=None, ge=1, le=5)
    reliability: int | None = Field(default=None, ge=1, le=5)
    performance: int | None = Field(default=None, ge=1, le=5)
    design: int | None = Field(default=None, ge=1, le=5)
    features: int | None = Field(default=None, ge=1, le=5)
    recommend: int | None = Field(default=None, ge=1, le=5)
    comment: str | None = Field(default=None, max_length=5000)
    likeMost: str | None = Field(default=None, max_length=5000)
    improve: str | None = Field(default=None, max_length=5000)
    bugs: str | None = Field(default=None, max_length=5000)
    featureRequests: str | None = Field(default=None, max_length=5000)
    role: str | None = Field(default=None, max_length=200)
