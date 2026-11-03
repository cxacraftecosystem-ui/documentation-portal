from datetime import datetime
from decimal import Decimal
from typing import Any

from pydantic import Field, field_validator, model_validator

from app.schemas.common import (
    APIModel,
    LocationInput,
    forbid_clearing_location,
    require_location,
)
from app.services.artisan_identity import (
    is_masked_aadhaar,
    require_aadhaar,
    validate_aadhaar,
    validate_pehchan,
)
from app.services.measurement_provenance import (
    DIMENSION_FIELDS,
    MARKER_BODY_KEY,
    marker_body_problems,
)

IDENTITY_NUMBER_FIELDS = ("aadhaarNumber", "pehchanCardNumber")


def drop_masked_identity_numbers(data: dict[str, Any]) -> dict[str, Any]:
    for field in IDENTITY_NUMBER_FIELDS:
        if is_masked_aadhaar(data.get(field)):
            data.pop(field, None)
    return data


class ArtisanCreate(APIModel):
    name: str = Field(min_length=1, max_length=180)
    localName: str | None = None
    gender: str | None = None
    phone: str | None = None
    phoneCountryCode: str | None = Field(default=None, max_length=8)
    email: str | None = None
    place: str = Field(min_length=1, max_length=180)
    address: str | None = None
    notes: str | None = None
    aadhaarNumber: str | None = None
    aadhaarNotShared: bool | None = None
    pehchanCardAvailable: bool | None = None
    pehchanCardNumber: str | None = None
    dos: str = Field(min_length=1)
    donts: str = Field(min_length=1)
    craftId: str | None = None
    craftName: str | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    dateOfBirth: datetime | None = None
    craftStartDate: datetime | None = None
    experienceYears: int | None = Field(default=None, ge=0, le=90)
    experienceMonths: int | None = Field(default=None, ge=0, le=11)
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    formContract: int | None = Field(default=None, ge=1, le=2)
    duplicateAcknowledged: list[str] | None = Field(default=None, max_length=20)

    _location_required = model_validator(mode="after")(require_location)

    @model_validator(mode="after")
    def require_aadhaar_unless_not_shared(self) -> "ArtisanCreate":
        has_number = bool((self.aadhaarNumber or "").strip())
        if self.aadhaarNotShared and not has_number:
            self.aadhaarNumber = None
            return self
        self.aadhaarNumber = require_aadhaar(self.aadhaarNumber or "")
        self.aadhaarNotShared = False
        return self
    _clean_pehchan = field_validator("pehchanCardNumber")(lambda cls, v: validate_pehchan(v))

    @model_validator(mode="after")
    def require_craft(self) -> "ArtisanCreate":
        if not self.craftId and not self.craftName:
            raise ValueError("Artisan must be assigned to a craft")
        return self

    @model_validator(mode="after")
    def reconcile_pehchan(self) -> "ArtisanCreate":
        if self.pehchanCardAvailable is None:
            if self.formContract is None or self.formContract < 2:
                self.pehchanCardAvailable = bool(self.pehchanCardNumber)
        elif self.pehchanCardAvailable:
            if not self.pehchanCardNumber:
                raise ValueError(
                    "Enter the Artisan Pehchan Card number, or set the card to 'No' if the "
                    "artisan does not hold one."
                )
        else:
            self.pehchanCardNumber = None
        return self


class ArtisanUpdate(APIModel):
    name: str | None = Field(default=None, min_length=1, max_length=180)
    localName: str | None = None
    gender: str | None = None
    phone: str | None = None
    phoneCountryCode: str | None = Field(default=None, max_length=8)
    email: str | None = None
    place: str | None = Field(default=None, min_length=1, max_length=180)
    address: str | None = None
    notes: str | None = None
    aadhaarNumber: str | None = None
    aadhaarNotShared: bool | None = None
    pehchanCardAvailable: bool | None = None
    pehchanCardNumber: str | None = None
    dos: str | None = None
    donts: str | None = None
    dateOfBirth: datetime | None = None
    craftStartDate: datetime | None = None
    experienceYears: int | None = Field(default=None, ge=0, le=90)
    experienceMonths: int | None = Field(default=None, ge=0, le=11)
    craftId: str | None = None
    craftName: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    expectedUpdatedAt: datetime | None = None
    formContract: int | None = Field(default=None, ge=1, le=2)

    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    _clean_aadhaar = field_validator("aadhaarNumber")(
        lambda cls, v: v if is_masked_aadhaar(v) else validate_aadhaar(v)
    )
    _clean_pehchan = field_validator("pehchanCardNumber")(
        lambda cls, v: v if is_masked_aadhaar(v) else validate_pehchan(v)
    )

    @model_validator(mode="after")
    def reconcile_pehchan(self) -> "ArtisanUpdate":
        if self.pehchanCardAvailable is False:
            self.pehchanCardNumber = None
        return self


CRAFT_GI_STATUSES = ("GI", "NON_GI")


class CraftCreate(APIModel):
    name: str = Field(min_length=1, max_length=180)
    subCategoryId: str = Field(min_length=1)
    localName: str | None = None
    stateOfOrigin: list[str] = Field(default_factory=list)
    stateOfPractice: list[str] = Field(default_factory=list)
    giStatus: str
    category: str | None = None
    description: str | None = None
    place: str | None = None
    workshopId: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    extraMetadata: dict[str, Any] | None = None

    @field_validator("giStatus")
    @classmethod
    def _known_gi_status(cls, value: str) -> str:
        if value not in CRAFT_GI_STATUSES:
            raise ValueError("giStatus must be GI or NON_GI.")
        return value


class CraftUpdate(APIModel):
    name: str | None = Field(default=None, min_length=1, max_length=180)
    subCategoryId: str | None = None
    stateOfOrigin: list[str] | None = None
    stateOfPractice: list[str] | None = None
    giStatus: str | None = None
    localName: str | None = None
    category: str | None = None
    description: str | None = None
    place: str | None = None
    workshopId: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    extraMetadata: dict[str, Any] | None = None

    @field_validator("giStatus")
    @classmethod
    def _known_gi_status(cls, value: str | None) -> str | None:
        if value is not None and value not in CRAFT_GI_STATUSES:
            raise ValueError("giStatus must be GI or NON_GI.")
        return value


class CraftMerge(APIModel):
    intoId: str = Field(min_length=1)


class CraftAdopt(APIModel):
    subCategoryId: str = Field(min_length=1)


WORKSHOP_TYPES = frozenset({"DESIGN_PROTOTYPE", "OTHER"})


class WorkshopCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    workshopType: str = "OTHER"
    date: datetime | None = None
    startDate: datetime | None = None
    endDate: datetime | None = None
    place: str = Field(min_length=1, max_length=180)
    description: str | None = None
    notes: str | None = None
    artisanIds: list[str] = Field(default_factory=list)
    craftIds: list[str] = Field(default_factory=list)
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    clientKey: str | None = Field(default=None, max_length=200)

    _location_required = model_validator(mode="after")(require_location)

    @model_validator(mode="after")
    def _known_workshop_type(self) -> "WorkshopCreate":
        if self.workshopType not in WORKSHOP_TYPES:
            raise ValueError(f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}")
        return self

    @model_validator(mode="after")
    def _has_a_date(self) -> "WorkshopCreate":
        if self.date is None and self.startDate is None:
            raise ValueError("date or startDate is required")
        return self


class WorkshopUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    workshopType: str | None = None
    date: datetime | None = None
    startDate: datetime | None = None
    endDate: datetime | None = None
    place: str | None = Field(default=None, min_length=1, max_length=180)
    description: str | None = None
    notes: str | None = None
    artisanIds: list[str] | None = None
    craftIds: list[str] | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    expectedUpdatedAt: datetime | None = None

    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    @model_validator(mode="after")
    def _known_workshop_type(self) -> "WorkshopUpdate":
        if self.workshopType is not None and self.workshopType not in WORKSHOP_TYPES:
            raise ValueError(f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}")
        return self



def validate_measurement_methods(model):
    markers = getattr(model, MARKER_BODY_KEY, None)
    if markers is None:
        return model
    problems = marker_body_problems(
        markers,
        present_fields={
            field for field in DIMENSION_FIELDS if getattr(model, field, None) is not None
        },
    )
    if problems:
        raise ValueError(" ".join(problems))
    return model


class ProductCreate(APIModel):
    craftName: str | None = Field(default=None, max_length=180)
    place: str | None = Field(default=None, max_length=180)
    artisanName: str | None = Field(default=None, max_length=180)
    productName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    productType: str = "OTHER"
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    measurementMethods: dict[str, dict[str, Any]] | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    costOfMaking: Decimal | None = Field(default=None, ge=0)
    sellingPrice: Decimal | None = Field(default=None, ge=0)
    marketDemand: str = "UNKNOWN"
    rawMaterialsUsed: str | None = None
    mainToolsUsed: str | None = None
    productFunctionUse: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    clientKey: str | None = Field(default=None, max_length=200)

    _location_required = model_validator(mode="after")(require_location)
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)


    @model_validator(mode="after")
    def require_names_unless_linked(self) -> "ProductCreate":
        missing = []
        if not self.artisanId:
            if not (self.artisanName or "").strip():
                missing.append("artisan name")
            if not (self.place or "").strip():
                missing.append("place")
        if not self.craftId and not self.artisanId and not (self.craftName or "").strip():
            missing.append("craft name")
        if missing:
            raise ValueError(f"Enter the {' and the '.join(missing)}, or pick the artisan.")
        return self


class ProductUpdate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=180)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)

    @model_validator(mode="before")
    @classmethod
    def blank_name_means_unchanged(cls, values: Any) -> Any:
        if isinstance(values, dict):
            for key in ("craftName", "place", "artisanName"):
                value = values.get(key)
                if isinstance(value, str) and not value.strip():
                    values = {k: v for k, v in values.items() if k != key}
        return values
    productName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    productType: str | None = None
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    measurementMethods: dict[str, dict[str, Any]] | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    costOfMaking: Decimal | None = Field(default=None, ge=0)
    sellingPrice: Decimal | None = Field(default=None, ge=0)
    marketDemand: str | None = None
    rawMaterialsUsed: str | None = None
    mainToolsUsed: str | None = None
    productFunctionUse: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    expectedUpdatedAt: datetime | None = None

    _location_kept = model_validator(mode="after")(forbid_clearing_location)
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)


class ProcessStepInput(APIModel):
    id: str | None = None
    name: str = Field(default="", max_length=220)
    stepType: str = "SEQUENTIAL"
    sortOrder: int = Field(default=0, ge=0)
    notes: str | None = None


class ProcessCreate(APIModel):
    name: str = Field(min_length=1, max_length=220)
    productId: str = Field(min_length=1)
    preProcessAvailable: bool = False
    notes: str | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    steps: list[ProcessStepInput] = Field(default_factory=list)
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    extraMetadata: dict[str, Any] | None = None
    clientKey: str | None = Field(default=None, max_length=200)


class ProcessUpdate(APIModel):
    name: str | None = Field(default=None, min_length=1, max_length=220)
    productId: str | None = None
    preProcessAvailable: bool | None = None
    notes: str | None = None
    workshopId: str | None = None
    status: str | None = None
    steps: list[ProcessStepInput] | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    extraMetadata: dict[str, Any] | None = None
    expectedUpdatedAt: datetime | None = None


TOOL_CRAFT_NAME_MAX = 2000


class ToolCreate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=TOOL_CRAFT_NAME_MAX)
    place: str = Field(min_length=1, max_length=180)
    artisanName: str = Field(min_length=1, max_length=180)
    toolkitName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    materialFamily: str | None = Field(default=None, max_length=40)
    yearsInUse: int | None = Field(default=None, ge=0)
    height: Decimal | None = Field(default=None, ge=0)
    width: Decimal | None = Field(default=None, ge=0)
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    measurementMethods: dict[str, dict[str, Any]] | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    thickness: Decimal | None = Field(default=None, ge=0)
    weight: Decimal | None = Field(default=None, ge=0)
    radius: Decimal | None = Field(default=None, ge=0)
    maker: str = "UNKNOWN"
    traditionType: str = "UNKNOWN"
    replacementCost: Decimal | None = Field(default=None, ge=0)
    suggestionsForToolImprovement: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    craftIds: list[str] | None = None
    artisanIds: list[str] | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    clientKey: str | None = Field(default=None, max_length=200)

    _location_required = model_validator(mode="after")(require_location)
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)

    @field_validator("craftIds", "artisanIds", mode="before")
    @classmethod
    def _no_explicit_null_link_list(cls, value: Any) -> Any:
        if value is None:
            raise ValueError("send [] to clear the links, or omit the key to leave them alone")
        return value


class ToolUpdate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=TOOL_CRAFT_NAME_MAX)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)
    toolkitName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    materialFamily: str | None = Field(default=None, max_length=40)
    yearsInUse: int | None = Field(default=None, ge=0)
    height: Decimal | None = Field(default=None, ge=0)
    width: Decimal | None = Field(default=None, ge=0)
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    measurementMethods: dict[str, dict[str, Any]] | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    thickness: Decimal | None = Field(default=None, ge=0)
    weight: Decimal | None = Field(default=None, ge=0)
    radius: Decimal | None = Field(default=None, ge=0)
    maker: str | None = None
    traditionType: str | None = None
    replacementCost: Decimal | None = Field(default=None, ge=0)
    suggestionsForToolImprovement: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    craftIds: list[str] | None = None
    artisanIds: list[str] | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    expectedUpdatedAt: datetime | None = None

    _location_kept = model_validator(mode="after")(forbid_clearing_location)
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)

    @field_validator("craftIds", "artisanIds", mode="before")
    @classmethod
    def _no_explicit_null_link_list(cls, value: Any) -> Any:
        if value is None:
            raise ValueError("send [] to clear the links, or omit the key to leave them alone")
        return value


class ToolArtisanAssign(APIModel):
    artisanIds: list[str] = Field(default_factory=list)
