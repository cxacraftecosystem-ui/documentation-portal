from pydantic import Field

from app.schemas.common import APIModel


class FeedbackUpsertRequest(APIModel):
    """A user's own app feedback, in detail: an overall 1–5 rating plus per-aspect 1–5 sub-ratings
    (quantitative) and several targeted free-text prompts (qualitative). Every field is optional
    individually, but at least one is expected in practice."""

    # Quantitative (each 1–5).
    rating: int | None = Field(default=None, ge=1, le=5)
    easeOfUse: int | None = Field(default=None, ge=1, le=5)
    reliability: int | None = Field(default=None, ge=1, le=5)
    performance: int | None = Field(default=None, ge=1, le=5)
    design: int | None = Field(default=None, ge=1, le=5)
    features: int | None = Field(default=None, ge=1, le=5)
    recommend: int | None = Field(default=None, ge=1, le=5)
    # Qualitative free text.
    comment: str | None = Field(default=None, max_length=5000)
    likeMost: str | None = Field(default=None, max_length=5000)
    improve: str | None = Field(default=None, max_length=5000)
    bugs: str | None = Field(default=None, max_length=5000)
    featureRequests: str | None = Field(default=None, max_length=5000)
    role: str | None = Field(default=None, max_length=200)
