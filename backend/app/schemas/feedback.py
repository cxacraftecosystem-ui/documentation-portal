from pydantic import Field

from app.schemas.common import APIModel


class FeedbackUpsertRequest(APIModel):
    """A user's own app feedback: a 1–5 quantitative rating and/or a qualitative free-text comment.
    Both fields are optional individually, but at least one is expected in practice."""

    rating: int | None = Field(default=None, ge=1, le=5)
    comment: str | None = Field(default=None, max_length=5000)
