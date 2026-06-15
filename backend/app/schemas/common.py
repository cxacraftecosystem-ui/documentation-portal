from datetime import datetime
from decimal import Decimal
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class APIModel(BaseModel):
    model_config = ConfigDict(extra="forbid", from_attributes=True)


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    page: int
    pageSize: int
    pages: int


class LocationInput(APIModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    altitude: float | None = None
    accuracy: float | None = Field(default=None, ge=0)
    address: str | None = None
    placeName: str | None = None
    capturedAt: datetime | None = None
    extraMetadata: dict[str, Any] | None = None


class ReviewAction(APIModel):
    notes: str | None = None


class ExtraMetadataMixin(APIModel):
    extraMetadata: dict[str, Any] | None = None


DecimalInput = Decimal | int | float | str
