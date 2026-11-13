from datetime import datetime
from decimal import Decimal
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.schemas.blank_text import BlankTextModel
from app.services.address import validate_district, validate_pincode, validate_state

T = TypeVar("T")


class APIModel(BlankTextModel):
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
    state: str | None = None
    district: str | None = None
    village: str | None = None
    pincode: str | None = None
    subjectLatitude: float | None = Field(default=None, ge=-90, le=90)
    subjectLongitude: float | None = Field(default=None, ge=-180, le=180)
    capturedAt: datetime | None = None
    extraMetadata: dict[str, Any] | None = None

    _clean_state = field_validator("state")(lambda cls, v: validate_state(v))
    _clean_pincode = field_validator("pincode")(lambda cls, v: validate_pincode(v))

    @model_validator(mode="after")
    def _clean_district(self) -> "LocationInput":
        self.district = validate_district(self.state, self.district)
        return self

    @model_validator(mode="after")
    def _pin_is_a_pair(self) -> "LocationInput":
        if (self.subjectLatitude is None) != (self.subjectLongitude is None):
            raise ValueError(
                "A map pin needs both subjectLatitude and subjectLongitude. Send the pair, or omit "
                "both — the pin is optional."
            )
        return self


LOCATION_REQUIRED_MESSAGE = (
    "A location is required. Capture a GPS fix, drop a pin on the map, or enter the coordinates "
    "by hand — any of the three satisfies this."
)

STATED_ADDRESS_REQUIRED_MESSAGE = (
    "The state and the district of the place this record is about are required. They describe the "
    "subject, not the device: the coordinates say where the device was when the record was made, "
    "which is often a desk in another state. Choose the state and district from the lists."
)

LOCATION_NOT_CLEARABLE_MESSAGE = (
    "This record's location cannot be removed. Send a replacement location, or leave the field out "
    "of the request to keep the stored one."
)


def require_location(model: Any) -> Any:
    location = getattr(model, "location", None)
    if location is None:
        raise ValueError(LOCATION_REQUIRED_MESSAGE)
    if not getattr(location, "state", None) or not _stated_district(location):
        raise ValueError(STATED_ADDRESS_REQUIRED_MESSAGE)
    return model


def _stated_district(location: Any) -> Any:
    district = getattr(location, "district", None)
    if district:
        return district
    meta = getattr(location, "extraMetadata", None)
    return meta.get("district") if isinstance(meta, dict) else None


def forbid_clearing_location(model: Any) -> Any:
    if "location" in model.model_fields_set and model.location is None:
        raise ValueError(LOCATION_NOT_CLEARABLE_MESSAGE)
    return model


class ReviewAction(APIModel):
    notes: str | None = None
    reason: str | None = None


class ExtraMetadataMixin(APIModel):
    extraMetadata: dict[str, Any] | None = None


DecimalInput = Decimal | int | float | str
