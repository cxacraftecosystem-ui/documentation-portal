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

# The two regulated identity numbers on Artisan, and the only two columns a caller can be shown a
# MASK of. Both are masked identically on the way out (``records.mask_identity_number``), so both
# have to be un-masked identically on the way back in — see ``drop_masked_identity_numbers``.
IDENTITY_NUMBER_FIELDS = ("aadhaarNumber", "pehchanCardNumber")


def drop_masked_identity_numbers(data: dict[str, Any]) -> dict[str, Any]:
    """Remove any identity number that came back as the MASK the caller was shown.

    ``XXXX XXXX 9012`` posted back unchanged means "I was not shown the real value and did not
    change it" — never "set the column to that literal string". Dropping the key is what makes it
    safe to mask an EDIT surface at all.

    This is the write-side half of the masking, and it has to cover both numbers or masking one of
    them destroys it: ``normalize_pehchan("XXXX XXXX 1234")`` is ``"XXXXXXXX1234"``, twelve
    alphanumerics inside the 4-32 window with no checksum to fail, so the mask of a Pehchan card
    validated cleanly and REPLACED the real number — 200 OK, revision recorded, regulated identifier
    gone. (An Aadhaar mask fails validation instead, which is why only that one was noticed.)

    Mutates and returns *data*. Called by every route that writes an artisan from a client payload:
    PATCH /artisans/{id} and the review queue's edit action.
    """
    for field in IDENTITY_NUMBER_FIELDS:
        if is_masked_aadhaar(data.get(field)):
            data.pop(field, None)
    return data


class ArtisanCreate(APIModel):
    name: str = Field(min_length=1, max_length=180)
    localName: str | None = None
    gender: str | None = None
    phone: str | None = None
    email: str | None = None
    place: str = Field(min_length=1, max_length=180)
    address: str | None = None
    notes: str | None = None
    # Identity. aadhaarNumber is the dedup key (unique in the DB) and is REQUIRED to create an
    # artisan: an artisan entered without one can never be deduplicated against, which is the whole
    # job of the column. Typed as a bare `str` so it is required in the OpenAPI schema too, while
    # `require_aadhaar` handles the blank-string case with a message worth reading — and normalises
    # the typed spacing away and rejects a mistyped number outright, because a bad number is worse
    # here than no number at all. The COLUMN stays nullable; see services/artisan_identity.py for
    # why the existing artisans with no Aadhaar cannot and must not be forced to have one.
    aadhaarNumber: str
    # Deliberately Optional rather than `bool = True`. "Yes by default" is the FORM's default, and
    # both current clients send the answer explicitly. Defaulting it to True server-side would make
    # an OMITTED flag mean "has a card", and the validator below would then demand a number every
    # older client (Android <= 1.1.14, any script) has no way to send — breaking artisan creation for
    # them the moment this ships. None means "not answered" and is resolved below.
    pehchanCardAvailable: bool | None = None
    pehchanCardNumber: str | None = None
    # Required on create: newline-separated Do's (positive prompt) and Don'ts (negative prompt).
    dos: str = Field(min_length=1)
    donts: str = Field(min_length=1)
    craftId: str | None = None
    craftName: str | None = None
    # The workshop this artisan was documented at. Optional everywhere: an omitted workshopId behaves
    # exactly as before, while a supplied one both sets the explicit column and adds the
    # WorkshopArtisan join row, and subjects the submission to the workshop's assignment/window rules.
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)

    _clean_aadhaar = field_validator("aadhaarNumber")(lambda cls, v: require_aadhaar(v))
    _clean_pehchan = field_validator("pehchanCardNumber")(lambda cls, v: validate_pehchan(v))

    @model_validator(mode="after")
    def require_craft(self) -> "ArtisanCreate":
        if not self.craftId and not self.craftName:
            raise ValueError("Artisan must be assigned to a craft")
        return self

    @model_validator(mode="after")
    def reconcile_pehchan(self) -> "ArtisanCreate":
        """Keep the "card available?" answer and the card number consistent, three ways.

        - **Yes** must come with a number. Answering yes and leaving the number blank is the one
          combination that puts the row in a state nothing downstream can interpret.
        - **No** clears any number that came with it. The form disables the number box when the
          answer is No, so a number arriving alongside No is leftover UI state rather than an
          instruction, and storing it would orphan a card number on a record that says it has none.
        - **Unanswered** (an older client that predates these fields) resolves from what it did send:
          a number implies Yes, no number means No. That keeps every row satisfying
          "Yes implies a number" without rejecting a request the client could not have made correctly.
        """
        if self.pehchanCardAvailable is None:
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
    email: str | None = None
    place: str | None = Field(default=None, min_length=1, max_length=180)
    address: str | None = None
    notes: str | None = None
    aadhaarNumber: str | None = None
    pehchanCardAvailable: bool | None = None
    pehchanCardNumber: str | None = None
    dos: str | None = None
    donts: str | None = None
    craftId: str | None = None
    craftName: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    # Deliberately still optional and still clearable, even though creating an artisan now demands an
    # Aadhaar. Two edits would otherwise become impossible: correcting the phone number of an artisan
    # recorded before the field existed (their number is NULL and the researcher cannot invent one),
    # and retracting a number typed against the wrong artisan — which has to be removable, or the
    # person who really holds it can never be created past the unique index.
    #
    # A masked number is passed through untouched rather than validated: it means "I was not shown
    # the real value and did not change it", and the route drops the key before the write
    # (``drop_masked_identity_numbers``). Validating it would 422 a caller who edited some unrelated
    # field.
    #
    # BOTH numbers take the same route. The Pehchan card used to be validated here like a real
    # entry, and its mask PASSES that validation — so the mask was stored over the card number
    # whenever an editor who could not read it saved the form.
    _clean_aadhaar = field_validator("aadhaarNumber")(
        lambda cls, v: v if is_masked_aadhaar(v) else validate_aadhaar(v)
    )
    _clean_pehchan = field_validator("pehchanCardNumber")(
        lambda cls, v: v if is_masked_aadhaar(v) else validate_pehchan(v)
    )

    @model_validator(mode="after")
    def reconcile_pehchan(self) -> "ArtisanUpdate":
        """Answering "No" on an edit clears the stored card number in the same request.

        Unlike the create path this cannot demand a number when the answer is Yes: a PATCH carrying
        only ``pehchanCardAvailable=true`` is legitimate when the record already holds one. The route
        does that check against the stored row, where the existing number is actually visible.
        """
        if self.pehchanCardAvailable is False:
            self.pehchanCardNumber = None
        return self


class CraftCreate(APIModel):
    name: str = Field(min_length=1, max_length=180)
    localName: str | None = None
    category: str | None = None
    description: str | None = None
    place: str | None = None
    # See ArtisanCreate.workshopId — the same optional link, mirrored into the WorkshopCraft join.
    workshopId: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    extraMetadata: dict[str, Any] | None = None


class CraftUpdate(APIModel):
    name: str | None = Field(default=None, min_length=1, max_length=180)
    localName: str | None = None
    category: str | None = None
    description: str | None = None
    place: str | None = None
    workshopId: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    extraMetadata: dict[str, Any] | None = None


class WorkshopCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
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

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)


class WorkshopUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
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

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)


class ProductCreate(APIModel):
    craftName: str = Field(min_length=1, max_length=180)
    place: str = Field(min_length=1, max_length=180)
    artisanName: str = Field(min_length=1, max_length=180)
    productName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    productType: str = "OTHER"
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    lengthInches: Decimal | None = None
    breadthInches: Decimal | None = None
    heightInches: Decimal | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    costOfMaking: Decimal | None = None
    sellingPrice: Decimal | None = None
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

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)


class ProductUpdate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=180)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)
    productName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    productType: str | None = None
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    lengthInches: Decimal | None = None
    breadthInches: Decimal | None = None
    heightInches: Decimal | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    costOfMaking: Decimal | None = None
    sellingPrice: Decimal | None = None
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

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)


class ProcessStepInput(APIModel):
    id: str | None = None
    name: str = Field(min_length=1, max_length=220)
    stepType: str = "SEQUENTIAL"
    sortOrder: int = Field(default=0, ge=0)
    notes: str | None = None


class ProcessCreate(APIModel):
    name: str = Field(min_length=1, max_length=220)
    productId: str = Field(min_length=1)
    preProcessAvailable: bool = False
    notes: str | None = None
    # The workshop this process was documented at. Omitted, the process still inherits its parent
    # product's workshop exactly as before; supplied, it names its own and is gated on that workshop.
    workshopId: str | None = None
    status: str = "PENDING"
    steps: list[ProcessStepInput] = Field(default_factory=list)
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    extraMetadata: dict[str, Any] | None = None


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


class ToolCreate(APIModel):
    craftName: str = Field(min_length=1, max_length=180)
    place: str = Field(min_length=1, max_length=180)
    artisanName: str = Field(min_length=1, max_length=180)
    toolkitName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    yearsInUse: int | None = Field(default=None, ge=0)
    height: Decimal | None = None
    width: Decimal | None = None
    lengthInches: Decimal | None = None
    breadthInches: Decimal | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    thickness: Decimal | None = None
    weight: Decimal | None = None
    radius: Decimal | None = None
    maker: str = "UNKNOWN"
    traditionType: str = "UNKNOWN"
    replacementCost: Decimal | None = None
    suggestionsForToolImprovement: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)


class ToolUpdate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=180)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)
    toolkitName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    yearsInUse: int | None = Field(default=None, ge=0)
    height: Decimal | None = None
    width: Decimal | None = None
    lengthInches: Decimal | None = None
    breadthInches: Decimal | None = None
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    thickness: Decimal | None = None
    weight: Decimal | None = None
    radius: Decimal | None = None
    maker: str | None = None
    traditionType: str | None = None
    replacementCost: Decimal | None = None
    suggestionsForToolImprovement: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)


class ToolArtisanAssign(APIModel):
    """Assign one documented tool to several artisans (same or different crafts)."""

    artisanIds: list[str] = Field(default_factory=list)
