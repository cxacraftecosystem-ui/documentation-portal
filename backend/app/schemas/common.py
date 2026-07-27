from datetime import datetime
from decimal import Decimal
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.services.address import validate_district, validate_pincode, validate_state

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
    """One request body carrying TWO answers to two different questions. See the ``Location`` model
    in prisma/schema.prisma for the finding that split them.

    PROVENANCE — ``latitude``, ``longitude``, ``altitude``, ``accuracy``, ``capturedAt``,
    ``placeName``, ``address``. Where the DEVICE was when the record was made. A client fills these
    automatically and no rule here asks a human to confirm them.

    STATED ADDRESS — ``state``, ``district``, ``village``, ``pincode``, ``subjectLatitude``,
    ``subjectLongitude``. Where the SUBJECT is, as a statement by the researcher. A geocoder may
    OFFER a value for these; the clients never write one without a person accepting it, and this
    schema never derives one from the coordinates above.

    Keeping both in one payload is what lets ``attach_location`` stay a single insert, and keeping
    them in separately named columns is what stops an export of "artisan coordinates" from being a
    silent mixture of the two.
    """

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    altitude: float | None = None
    accuracy: float | None = Field(default=None, ge=0)
    address: str | None = None
    placeName: str | None = None
    # The stated address. Individually optional at the FIELD level and required at the REQUEST level
    # on create only — see require_location, which is where "mandatory" is defined and where the
    # legacy rows are let through. When they are given they are closed-list / fixed-format, so one
    # village cannot end up filed under four spellings of its state. The validators normalise
    # ("gujarat" -> "Gujarat", "Akola District" -> "Akola", "380 001" -> "380001") and reject
    # anything they cannot resolve; see services/address.py for the reasoning and the lists.
    state: str | None = None
    district: str | None = None
    # Free text, and staying free text: no closed list of Indian villages exists that a field
    # researcher could pick from. Title-cased on write like every other name-like column, through
    # TITLE_CASE_FIELDS in services/records.py.
    village: str | None = None
    pincode: str | None = None
    # The researcher's optional pin on the SUBJECT'S place. Never derived from the fix above.
    subjectLatitude: float | None = Field(default=None, ge=-90, le=90)
    subjectLongitude: float | None = Field(default=None, ge=-180, le=180)
    capturedAt: datetime | None = None
    extraMetadata: dict[str, Any] | None = None

    _clean_state = field_validator("state")(lambda cls, v: validate_state(v))
    _clean_pincode = field_validator("pincode")(lambda cls, v: validate_pincode(v))

    @model_validator(mode="after")
    def _clean_district(self) -> "LocationInput":
        """Resolve the district WITHIN its state, which is the only scope it means anything in.

        A model validator rather than a field one because the rule reads two fields: several
        district names are used by two states at once (Bilaspur, Hamirpur, Aurangabad, Pratapgarh,
        Bijapur), so "is this a real district" is not a question that can be answered without the
        state. Runs after the field validators, so ``self.state`` is already the canonical name and
        an accepted alias ("Gujrat") still scopes its district correctly.
        """
        self.district = validate_district(self.state, self.district)
        return self

    @model_validator(mode="after")
    def _pin_is_a_pair(self) -> "LocationInput":
        """Half a pin is not a place.

        A latitude with no longitude is 111 km of meridian, and stored on its own it would read as a
        precise statement about the subject in exactly the column that exists to be precise. Both or
        neither — and neither is fine, because the pin is optional.
        """
        if (self.subjectLatitude is None) != (self.subjectLongitude is None):
            raise ValueError(
                "A map pin needs both subjectLatitude and subjectLongitude. Send the pair, or omit "
                "both — the pin is optional."
            )
        return self


# Worded for the researcher who will read it out of a red box on a form, not for the developer who
# wrote the client: it says what to do, and it says that the GPS is not the only way to do it. A
# device that cannot answer is the normal case indoors, not an error, and a rule that reads like the
# GPS is compulsory is a rule people work around by making something up.
LOCATION_REQUIRED_MESSAGE = (
    "A location is required. Capture a GPS fix, drop a pin on the map, or enter the coordinates "
    "by hand — any of the three satisfies this."
)

# The other half of "mandatory", and the half that is new. Worded so that a researcher who somehow
# reaches it — an old client, a hand-written request — is told which two boxes to fill and where the
# names come from, rather than being told a field is missing.
STATED_ADDRESS_REQUIRED_MESSAGE = (
    "The state and the district of the place this record is about are required. They are a "
    "STATEMENT about the subject, not a reading from the device: the coordinates say where the "
    "device was when the record was made, which is often a desk in another state. Choose both from "
    "the lists GET /reference/address serves."
)

LOCATION_NOT_CLEARABLE_MESSAGE = (
    "This record's location cannot be removed. Send a replacement location, or leave the field out "
    "of the request to keep the stored one."
)


def require_location(model: Any) -> Any:
    """Refuse a create with no location, and refuse one that does not say where the SUBJECT is.

    TWO RULES, because the two halves of a location are satisfiable under different conditions.

    A COORDINATE, unchanged. It demands a Location — a point the researcher stood at, pointed at on
    a map, or typed — and it does not demand that the GPS worked, because a browser can be denied
    permission permanently and a laptop indoors has no satellite lock to give. That coordinate is
    PROVENANCE: it records where the record was made, which is a true and useful thing to know and
    is not a claim about the subject.

    A STATE AND A DISTRICT, new. This is the fix for the finding written up on the ``Location``
    model: fifteen artisans documented in Rajasthan, Gujarat, Uttarakhand and Andhra Pradesh all
    carry Kharagpur coordinates, because the only place the schema offered for "where the artisan
    is" was the field that means "where the device is". Requiring these two is what makes the
    distinction real rather than advisory — a nullable column nobody has to fill is a column that
    stays as empty as ``district`` has been since it was first exported.

    THEY ARE REQUIREABLE, and the pincode still is not. Both come from a closed list the client
    already holds — ``GET /reference/address`` is a pure constant a form fetches once and caches —
    so a researcher in a village with no signal can still answer them from memory of where they are
    standing. A pincode cannot be answered that way and is not asked for. Nothing here depends on a
    reverse geocode succeeding, which is the test every mandatory field in this codebase has to pass.

    ONLY ON CREATE, and that is exactly how the legacy rows survive. The rule reads the REQUEST, and
    an update never passes through here — see :func:`forbid_clearing_location`. The sixteen artisans
    already stored have no state and no district; every one of them stays editable and saveable,
    because a PATCH that carries a location with neither is still accepted. Nothing is backfilled
    and no stored value changes. The disagreement between a West Bengal coordinate and a Rajasthan
    place name is surfaced to the researcher who was there, in the form, and left for them to
    resolve.
    """
    location = getattr(model, "location", None)
    if location is None:
        raise ValueError(LOCATION_REQUIRED_MESSAGE)
    if not getattr(location, "state", None) or not _stated_district(location):
        raise ValueError(STATED_ADDRESS_REQUIRED_MESSAGE)
    return model


def _stated_district(location: Any) -> Any:
    """The district, from the column or from ``extraMetadata``.

    The Android client keeps the stated address inside ``location.extraMetadata`` — it had nowhere
    else to put it before the columns existed — and NO Android build sends a `district` column, the
    installed fleet included. Reading only the column would therefore 422 every create from every
    phone the moment this rule deployed, turning the primary field-capture client read-only with no
    APK able to fix it. `services.records.lift_stated_address` performs the matching normalisation
    on the way into the database, so a record accepted here is stored in the column either way.
    """
    district = getattr(location, "district", None)
    if district:
        return district
    meta = getattr(location, "extraMetadata", None)
    return meta.get("district") if isinstance(meta, dict) else None


def forbid_clearing_location(model: Any) -> Any:
    """On an update, allow the field to be ABSENT but never to be an explicit ``null``.

    The distinction is what keeps the existing data editable. Fifteen of the sixteen artisans on the
    live database carry a location; the sixteenth predates the requirement, and a researcher who
    opened it to correct a phone number cannot invent a coordinate for a workshop they are not
    standing in. A PATCH that simply does not mention ``location`` — which is what both clients send
    for a record with none — therefore has to keep working, and does.

    What it stops is the other direction: a stored location being emptied. That is the only way the
    create-time rule could be got round, and it is never a thing anybody means to do — neither client
    can express it (``JSON.stringify`` drops ``undefined``, kotlinx's ``explicitNulls = false`` drops
    a null), so this can only ever be reached by a hand-written request.

    NO MIGRATION, and none needed: the rule reads ``model_fields_set`` on the request, never the
    stored row, so it changes what may be WRITTEN from now on and asks nothing of what is already
    there.

    AND IT DOES NOT REQUIRE A STATE OR A DISTRICT, which :func:`require_location` does. That
    asymmetry is not an oversight, it is the whole legacy story. ``attach_location`` writes a BRAND
    NEW Location row on every save, so an edit re-sends whatever the form is holding — and for the
    fifteen artisans recorded before the stated address existed, what the form is holding is a
    coordinate and two empty dropdowns. Demanding the pair here would mean a researcher who opened
    one of those records to correct a phone number could not save it until they had also decided,
    possibly wrongly and from a desk, which district the artisan lives in. Guessed data is worse
    than absent data; the form flags the gap and invites them to close it, and this validator lets
    them save either way.
    """
    if "location" in model.model_fields_set and model.location is None:
        raise ValueError(LOCATION_NOT_CLEARABLE_MESSAGE)
    return model


class ReviewAction(APIModel):
    notes: str | None = None


class ExtraMetadataMixin(APIModel):
    extraMetadata: dict[str, Any] | None = None


DecimalInput = Decimal | int | float | str
