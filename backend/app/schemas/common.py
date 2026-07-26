from datetime import datetime
from decimal import Decimal
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.services.address import validate_pincode, validate_state

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
    # The postal half of the address. Optional, because a coordinate captured in a field with no
    # postal address at hand is still a location worth keeping — but when they ARE given they are
    # closed-list / fixed-format, so one village cannot end up filed under four spellings of its
    # state. The validators normalise ("gujarat" -> "Gujarat", "380 001" -> "380001") and reject
    # anything they cannot resolve; see services/address.py for the reasoning and the canonical list.
    state: str | None = None
    pincode: str | None = None
    capturedAt: datetime | None = None
    extraMetadata: dict[str, Any] | None = None

    _clean_state = field_validator("state")(lambda cls, v: validate_state(v))
    _clean_pincode = field_validator("pincode")(lambda cls, v: validate_pincode(v))


class ReviewAction(APIModel):
    notes: str | None = None


class ExtraMetadataMixin(APIModel):
    extraMetadata: dict[str, Any] | None = None


DecimalInput = Decimal | int | float | str
