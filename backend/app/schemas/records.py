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
    # ── THE TWO FACTS THE DESIGN WORKSHOP ASKS EVERY ARTISAN FOR ────────────────────────────
    #
    # Both were read only from `extraMetadata` spellings the record form stopped writing years ago,
    # so the artisan record sheet printed two permanently empty cells and nothing in this product
    # could record either fact. `record_fields.py` carried a note naming exactly this fix — a
    # column read first with the legacy keys behind it, and a DATE rather than an age.
    #
    # A DATE, NOT AN AGE: the record sheet prints an age derived from this. See the column comment
    # in schema.prisma — an age written down is wrong within a year and nothing notices.
    dateOfBirth: datetime | None = None
    # THE JOINING DATE THE EXPERIENCE IS DERIVED FROM. Same argument `dateOfBirth` above makes: a
    # stated number of years is right on the day it is typed and silently wrong from then on.
    # `derive_experience_years` in services/records turns this into the number four export surfaces
    # print, on every read.
    #
    # NO BOUND, deliberately, unlike `experienceYears` below. A date is not a count, so there is no
    # ceiling to mirror; a date that derives to something outside 0..90 (a typo'd century, a date in
    # the future) is dropped by the derivation rather than refused here, which leaves the stated
    # number and the legacy metadata behind it still readable. Refusing the whole PATCH would lose an
    # edit to the phone number that happened to travel beside a mistyped year.
    craftStartDate: datetime | None = None
    # 0..90, the same bound the sibling repository's design-workshop registry uses, so an artisan
    # exported from one product and imported into the other cannot carry a number the other side
    # refuses.
    experienceYears: int | None = Field(default=None, ge=0, le=90)
    # ── THE REMAINING MONTHS OF THAT SAME EXPERIENCE — 0..11, A REMAINDER AND NEVER A TOTAL ────
    #
    # The requirement is two dropdowns on one line, "5 years" beside "6 months", and the read-back has
    # to hand the form back exactly what was chosen — which is why this is a second column rather than
    # an ``experienceTotalMonths`` the two boxes are divided out of. ``Artisan.experienceMonths`` in
    # schema.prisma carries that argument in full.
    #
    # 11 AND NOT 12: twelve months is not a bigger month, it is a year the box above already holds.
    #
    # DECLARED HERE AND NOT LEFT TO THE ``CHECK`` CONSTRAINT. The column carries
    # ``CHECK ("experienceMonths" BETWEEN 0 AND 11)``, and a CHECK violation surfaces as a driver
    # error raised from inside the write: a bare 500 naming no field, on a save the researcher cannot
    # correct. ``ge``/``le`` here is a 422 whose ``loc`` names the box.
    #
    # ABSENT, NULL AND 0 STAY THREE DIFFERENT ANSWERS. On create, ``clean_data`` drops a key whose
    # value is ``None`` — so an unanswered months box writes no column and the row keeps NULL, which
    # is the honest answer for an artisan who said "about thirty years" and nothing about months.
    # ``0`` is not ``None``, survives that drop and stores 0. On PATCH the same three answers are kept
    # apart by ``exclude_unset`` plus ``_CLEARABLE_COLUMNS``; see ``ArtisanUpdate``.
    #
    # THE VALUE STOPS AT THE API. It reaches no export surface — no workbook column, no CSV header,
    # no `details.txt` line — and that is an owner decision recorded here so a reader wiring a client
    # is not surprised by its absence: a months column in a ministry-facing header has its own blast
    # radius, and the sibling repository made the same choice.
    experienceMonths: int | None = Field(default=None, ge=0, le=11)
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
    dateOfBirth: datetime | None = None
    # See `ArtisanCreate` for both of these, and `_CLEARABLE_COLUMNS` in api/routes/artisans for why
    # an explicit null on either one clears the stored value rather than being ignored: a joining
    # date entered by mistake has to be retractable from the form that entered it.
    craftStartDate: datetime | None = None
    experienceYears: int | None = Field(default=None, ge=0, le=90)
    # See ``ArtisanCreate.experienceMonths`` for the bound and for why it is a second column. On this
    # body the three answers are kept apart by two mechanisms working together, and both are needed:
    # ``exclude_unset=True`` in the route means an ABSENT key never reaches ``clean_data``, so the
    # stored value stands; ``"experienceMonths"`` in ``_CLEARABLE_COLUMNS`` means an explicit NULL
    # survives ``clean_data``'s None-drop and blanks the column; and ``0`` is neither, so it stores 0.
    # Without the clearable entry an explicit null would be silently discarded and the save would
    # report 200 while changing nothing — "a field that cannot be cleared is a 200 that does nothing".
    experienceMonths: int | None = Field(default=None, ge=0, le=11)
    craftId: str | None = None
    craftName: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    # ── THE VERSION THIS EDIT WAS COMPOSED AGAINST ──────────────────────────────────────────────
    #
    # OPTIONAL, AND OMITTED IS TODAY'S BEHAVIOUR EXACTLY. Every client shipped to date sends no
    # precondition and is unrefusable by it; only a caller that opts in by SENDING the field can ever
    # meet the 409. That is what makes this safe to deploy ahead of any client change.
    #
    # WHAT IT CLOSES, WHICH IS NOT SPECULATIVE IN THIS PRODUCT. `frontend/lib/offline.ts` types a
    # queued write's method as `"POST" | "PATCH"`, and `components/forms/ArtisanForm.tsx` (with every
    # other record form) calls `saveOrQueue` with `method: initial ? "PATCH" : "POST"` and a WHOLE
    # create-shaped body. So a correction composed in a courtyard and drained hours later overwrites,
    # field for field, whatever anybody else changed in between — silently. With this sent, the
    # server answers 409 `record_changed` instead, carrying both timestamps.
    #
    # A QUESTION AND NOT A COLUMN: ``records.take_expected_updated_at`` pops it out of the body on the
    # line after the clean, before ``guard_record_edit`` diffs the body into a ``RecordRevision`` and
    # before ``merge_field_provenance`` stamps a contributor against every key it holds.
    #
    # See ``records.assert_expected_updated_at`` for the comparison, the one-second tolerance and
    # which of the two possible mistakes that tolerance deliberately makes.
    expectedUpdatedAt: datetime | None = None

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
    # ── NO ``expectedUpdatedAt`` HERE, AND ITS ABSENCE IS A DECISION RATHER THAN AN OMISSION ─────
    #
    # The other five update schemas declare it. This one must not until ``routes/crafts.update_craft``
    # calls ``take_expected_updated_at``, because the two halves only make sense together: the field
    # would be accepted by the schema, survive ``clean_data`` unread, and be handed to Prisma as a
    # column ``Craft`` has never had — a bare 500 on an edit that is currently a clean 422
    # (``APIModel`` is ``extra="forbid"``, so a client sending it today is told so by name). A guard
    # that is present and does nothing is bad; a guard that is present and breaks the save is worse.
    #
    # The route half is one import and two lines, and it belongs to whoever owns that file; see the
    # handoff on this change. ``test_record_update_precondition`` pins BOTH halves of this state:
    # that the five wired schemas accept the key, and that this one still refuses it.
    name: str | None = Field(default=None, min_length=1, max_length=180)
    localName: str | None = None
    category: str | None = None
    description: str | None = None
    place: str | None = None
    workshopId: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    extraMetadata: dict[str, Any] | None = None


#: The kinds of workshop the database has. A frozenset rather than a list because it is only ever
#: asked "is this one of them", and spelled once so the two validators below and the GET filter in
#: api/routes/workshops cannot drift apart.
#:
#: DECLARED HERE AND CHECKED, rather than left to Prisma: `workshopType` reaches a Postgres enum
#: column, so an unknown value is not merely stored wrong — Prisma refuses it and the route answers
#: a bare 500, which reads to a client as "the server is broken" rather than "that is not a kind of
#: workshop".
WORKSHOP_TYPES = frozenset({"DESIGN_PROTOTYPE", "OTHER"})


class WorkshopCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    #: Which kind of workshop this is. Defaults to OTHER, which is what every existing row implicitly
    #: was. See WORKSHOP_TYPES above for why a value that is not one of them is a 422 here rather than
    #: a 500 from Prisma.
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
    # ── THE CREATE-IDEMPOTENCY KEY. THIS IS THE CANONICAL COPY OF THE ARGUMENT ───────────────────
    #
    # A queued create is POSTed, this server writes the row, and the answer is lost on the way back.
    # The client learned nothing, so the entry is still in its queue and the next pass sends it
    # again — and both outboxes' guards (`createdId` on the web, `PendingEntry.createdId` on Android)
    # are records of a REPLY and cannot guard an answer that never arrived. With a key, the second
    # landing is answered from the row the first one wrote. See `Workshop.clientKey` in schema.prisma
    # and `records.client_key_replay`.
    #
    # OPTIONAL, AND ABSENT IS TODAY'S BEHAVIOUR EXACTLY: no key, no read, create the row, answer 201.
    #
    # ON THE CREATE SCHEMAS ONLY, AND THAT IS LOAD-BEARING. ``APIModel`` is ``extra="forbid"``, so a
    # correction that carried a key would be refused as ``extra_forbidden`` — a 422 an outbox reads
    # as a disagreement between builds and re-attempts, for ever, on a prepaid connection. It cannot
    # happen while the field exists on no Update schema and the clients merge the key onto the create
    # path only.
    #
    # ``max_length=200`` because a key is a v4 UUID (36 characters) and an unbounded string on a
    # unique index is an index-size question nobody should have to ask later.
    clientKey: str | None = Field(default=None, max_length=200)

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)

    @model_validator(mode="after")
    def _known_workshop_type(self) -> "WorkshopCreate":
        """Reject a kind the database does not have.

        `workshopType` reaches a Postgres enum column, so an unknown value is not merely stored
        wrong — Prisma refuses it and the route answers a bare 500, which reads to a client as "the
        server is broken" rather than "that is not a kind of workshop".
        """
        if self.workshopType not in WORKSHOP_TYPES:
            raise ValueError(f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}")
        return self


class WorkshopUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    #: Omit to leave the stored kind alone. See WorkshopCreate.
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
    # The version this edit was composed against. Optional; omitted is today's behaviour exactly. See
    # ``ArtisanUpdate.expectedUpdatedAt`` and ``records.assert_expected_updated_at``.
    expectedUpdatedAt: datetime | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    @model_validator(mode="after")
    def _known_workshop_type(self) -> "WorkshopUpdate":
        """Omitted keeps the stored kind; a value must be one the database has."""
        if self.workshopType is not None and self.workshopType not in WORKSHOP_TYPES:
            raise ValueError(f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}")
        return self


# ─────────────────────────────────────────────────────────────────────────────────────────────────
# HOW EACH DOCUMENTED DIMENSION WAS MEASURED — the one key on these four bodies that is not a column
# ─────────────────────────────────────────────────────────────────────────────────────────────────
#
# ``measurementMethods`` is a per-dimension hint about HOW ``lengthInches`` / ``breadthInches`` /
# ``heightInches`` came to be known: typed off a tape, computed from marks a person placed on a
# photograph, or estimated by a vision model. It names no column on either documentation table;
# ``records.merge_field_provenance`` pops it and merges it into the ``{by, byName, at}`` stamp beside
# each dimension, so the row ends up saying *a vision model estimated this, and R. Menon accepted it
# into the record at that moment* instead of asserting that R. Menon measured it.
# ``services/measurement_provenance`` holds the entire argument and the shape.
#
# WHY THIS DECLARATION EXISTS AT ALL, AND WHY IT COULD NOT COME FIRST. ``APIModel`` is
# ``ConfigDict(extra="forbid")``, so until these four lines exist a client sending the marker has its
# ENTIRE save rejected with a 422 naming a key the researcher has never heard of — and neither client
# queue retries a 4xx, so the work is thrown away rather than retried. This declaration is what makes
# the key sendable, which is also exactly why it must not land BEFORE
# ``access.REVISION_SKIP_FIELDS`` gains the same key: a sendable marker with no skip entry makes
# ``guard_record_edit`` append a RecordRevision nobody made on every save that carries one, into an
# append-only audit table that landing the skip entry afterwards cannot un-write.
#
# THE KEY IS OMITTED WHEN THERE IS NOTHING TO SAY, AND NEVER SENT AS ``null``. Absent is legal and
# means UNRECORDED; it never means TYPED. An explicit ``null`` is what breaks against a server
# deployed before this field existed — ``extra="forbid"`` makes it a 422 on the whole save — and the
# web deploys to Vercel while this API deploys to EC2, so a newer web build meeting an older API is
# the ordinary case rather than the unlucky one.
#
# WHY ``dict[str, dict[str, Any]]`` AND NOT A PYDANTIC MODEL PER MARKER. The outer shape is pinned by
# the annotation — an object keyed by dimension name whose values are objects — because those two
# failures deserve pydantic's own precise 422 pointing at the offending key, and it costs nothing. A
# marker MODEL is refused for a different reason than convenience: an ``extra="forbid"`` sub-model
# would 422 a client echoing a marker that a NEWER server added a key to, mid-deploy, which is a
# version skew that breaks saves for a reason no researcher can act on. So the marker's key set stays
# open and its VALUES are closed by ``marker_body_problems`` below — each one either lands in the
# stamp or is refused by name.


def validate_measurement_methods(model):
    """Refuse a marker body that describes something this request is not saying. 422, by name.

    Attached to all four record schemas below — not just the Update pair, because a client that
    implemented its half against ``ProductUpdate`` alone would find every product CREATE refused.

    ``present_fields`` is computed off the SAME model instance, so "is there a value for this
    dimension in this request" is answered by the request itself. It reads non-null rather than
    ``model_fields_set`` on purpose: a dimension sent as an explicit ``null`` is being CLEARED (see
    ``routes/products._CLEARABLE_COLUMNS``, which exists to let that through), and a method
    describing a cleared measurement describes nothing. That covers create (where every unset
    optional is None) and update (where ``exclude_unset`` has not run yet) with one rule.

    Deliberately NOT checked here: whether the value actually CHANGED. A schema cannot see the stored
    row, and the anti-laundering rule that declines to re-stamp an unchanged dimension already lives
    in ``merge_field_provenance``'s changed-fields loop, where the stored row is in scope.

    WHY A REFUSAL RATHER THAN THE SILENT DEGRADE THE SAVE PATH USES. ``provenance_of_marker`` turns
    anything unreadable into UNRECORDED and must keep doing so — a record edit must not fail over a
    provenance hint. But at the boundary the alternative to a refusal is not a safe default, it is a
    silent lie of omission: a researcher presses Accept on a vision-model reading, the client sends a
    typo in the method name, and the row is stored indistinguishable from one saved by a client that
    never implemented any of this, with nobody told. See ``marker_body_problems`` for the full
    two-layer argument and for why no NEW refusal may be added to it once a client has shipped.
    """
    markers = getattr(model, MARKER_BODY_KEY, None)
    if markers is None:
        # Sending nothing is legal and means UNRECORDED — it must never mean TYPED. See
        # ``measurement_provenance.method_stamps``, which writes the explicit UNRECORDED.
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
    craftName: str = Field(min_length=1, max_length=180)
    place: str = Field(min_length=1, max_length=180)
    artisanName: str = Field(min_length=1, max_length=180)
    productName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    productType: str = "OTHER"
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    # ── NON-NEGATIVE, AND THE BOUND IS BOTH HALVES OR IT IS NEITHER ──────────────────────────────
    #
    # Every measurement and price on this model and on Tool took a negative from the box and stored
    # it. A negative length is not a measurement, and the sibling repository's workshop registry
    # declares the fields these are carried into as non-negative — so this product accepted a
    # quantity that product would refuse on a row it filled in FROM here.
    #
    # THIS IS A BEHAVIOUR CHANGE ON PATCH AND NOT ONLY ON CREATE, and it is deliberate. The web forms
    # post the WHOLE payload back on an edit (components/forms/ProductForm.tsx, ToolForm.tsx), so a
    # row that already holds a negative will start 422-ing on any edit at all until the number is
    # corrected — including an edit to a field beside it. The audit query that finds those rows is in
    # the PR that added this bound, and it must be run before deploy:
    #
    #   SELECT 'ProductDocumentation' AS tbl, id FROM "ProductDocumentation"
    #    WHERE "lengthInches" < 0 OR "breadthInches" < 0 OR "heightInches" < 0
    #       OR "costOfMaking" < 0 OR "sellingPrice" < 0
    #   UNION ALL
    #   SELECT 'ToolDocumentation', id FROM "ToolDocumentation"
    #    WHERE "height" < 0 OR "width" < 0 OR "lengthInches" < 0 OR "breadthInches" < 0
    #       OR "heightInches" < 0 OR "thickness" < 0 OR "weight" < 0 OR "radius" < 0
    #       OR "replacementCost" < 0;
    #
    # `min={0}` on the matching web inputs refuses the value in the box, by name, before a request is
    # made; this refuses it for every client that is not one of ours.
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    # HOW each of the three dimensions above was measured. Not a column: popped by
    # ``records.merge_field_provenance`` and merged into that dimension's provenance stamp. Omitting
    # it is legal and means UNRECORDED; it never means TYPED, and it is never sent as null.
    # Validated by ``validate_measurement_methods`` above — see it for what is refused and why a
    # refusal rather than a silent drop.
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
    # The create-idempotency key. Optional; absent is today's behaviour exactly. See
    # ``WorkshopCreate.clientKey``.
    clientKey: str | None = Field(default=None, max_length=200)

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)
    # The marker validator, attached on each of the four bodies that carries the key. Four separate
    # lines and not one shared base, because a base class would also hand the key to every OTHER
    # record schema in this file — an artisan has no dimensions to state a method for.
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)


class ProductUpdate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=180)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)
    productName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    productType: str | None = None
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    # ge=0 on the UPDATE too — see ``ProductCreate`` for the whole argument, including the audit
    # query that must be run before this ships. Bounding create alone would leave the number
    # correctable through a PATCH and therefore not bounded at all.
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    # HOW each of the three dimensions above was measured. Not a column: popped by
    # ``records.merge_field_provenance`` and merged into that dimension's provenance stamp. Omitting
    # it is legal and means UNRECORDED; it never means TYPED, and it is never sent as null.
    # Validated by ``validate_measurement_methods`` above — see it for what is refused and why a
    # refusal rather than a silent drop.
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
    # The version this edit was composed against. Optional; omitted is today's behaviour exactly. See
    # ``ArtisanUpdate.expectedUpdatedAt`` and ``records.assert_expected_updated_at``.
    expectedUpdatedAt: datetime | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)
    # The marker validator, attached on each of the four bodies that carries the key. Four separate
    # lines and not one shared base, because a base class would also hand the key to every OTHER
    # record schema in this file — an artisan has no dimensions to state a method for.
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)


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
    # The create-idempotency key. Optional; absent is today's behaviour exactly. See
    # ``WorkshopCreate.clientKey``. This is the one of the four whose replay has to think about
    # CHILDREN — see ``routes/processes.create_process``.
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
    # The version this edit was composed against. Optional; omitted is today's behaviour exactly. See
    # ``ArtisanUpdate.expectedUpdatedAt`` and ``records.assert_expected_updated_at``.
    expectedUpdatedAt: datetime | None = None


#: THE BOUND ON A TOOL'S ``craftName``, WHICH IS NOT ONE CRAFT'S NAME.
#:
#: Every other 180 in this file bounds a box a person types into. This one bounds a string the SERVER
#: writes: once ``craftIds`` is present and non-empty, ``routes/tools._resolve_tool_links`` replaces
#: whatever the body sent with every linked craft's name joined ``", "``. At 180 the two disagreed,
#: and the disagreement was not theoretical — ``Craft.name`` is itself capped at 180 (``CraftCreate``
#: above), so TWO long craft names already overflow it and about seven ordinary ones do
#: ("Ajrakh Hand Block Printing" is 26 characters; seven of those joined is 194). What that produced
#: was a 422 naming ``craftName`` — a box the researcher had not typed in and, once the value was
#: stored, could not correct, because the route re-derived the same over-long string on the next save
#: and refused it again. The column is TEXT and enforced nothing, so only these two numbers were ever
#: in play.
#:
#: THE NUMBER HAS TO BE ONE NUMBER, and that is the whole point of naming it. ``_resolve_tool_links``
#: imports this constant and refuses a join it cannot fit BEFORE anything is written, so what the
#: route can store and what these schemas will accept back are the same bound by construction rather
#: than by two people remembering to change two literals. Ten crafts at the full 180-character
#: ``Craft.name`` cap is 1818 characters (10 x 180 + 9 x 2); at the names this register actually
#: holds it is about seventy crafts. A tool linked to seventy crafts is a question for a person, not
#: a string to truncate silently, which is why the route answers it with a refusal that says so.
TOOL_CRAFT_NAME_MAX = 2000


class ToolCreate(APIModel):
    # Server-derived whenever ``craftIds`` is sent, so its bound is the JOIN's — see the constant.
    craftName: str = Field(min_length=1, max_length=TOOL_CRAFT_NAME_MAX)
    place: str = Field(min_length=1, max_length=180)
    artisanName: str = Field(min_length=1, max_length=180)
    toolkitName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    yearsInUse: int | None = Field(default=None, ge=0)
    # ge=0 across the measurements and the price — see ``ProductCreate.lengthInches`` for the whole
    # argument and for the pre-deploy audit query, which covers both tables.
    height: Decimal | None = Field(default=None, ge=0)
    width: Decimal | None = Field(default=None, ge=0)
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    # THE THIRD OF THE TRIPLE, AND THE BOX A MACHINE READING LANDS IN. The grid-measurement panel
    # returns an INCHES reading and must fill THIS one; until this column existed the only box it
    # could reach was the bare ``height``, which is how every grid-measured tool height in this
    # repository came to be stored with no recoverable unit. See migration 20260913120100.
    #
    # ``height`` ABOVE IS ITS CENTIMETRE PARTNER SINCE 2026-09-15, not an unrelated column any more:
    # the clients label the pair "Height (cm)" / "Height (inches)" and fill either from the other at
    # 2.54 cm to the inch, and ``width``/``breadthInches`` pair the same way. ``lengthInches`` stands
    # alone. THE SERVER CONVERTS NOTHING — it stores what it is sent, both columns are
    # ``Decimal(10, 2)``, and a body carrying only one of a pair is a body that means only one of a
    # pair. That is what keeps rows saved BEFORE the pairing readable: they can hold two numbers that
    # are not the same measurement, nothing in the database can say what unit the older one was typed
    # in, and no migration invented one.
    heightInches: Decimal | None = Field(default=None, ge=0)
    # HOW each of the three dimensions above was measured. Not a column: popped by
    # ``records.merge_field_provenance`` and merged into that dimension's provenance stamp. Omitting
    # it is legal and means UNRECORDED; it never means TYPED, and it is never sent as null.
    # Validated by ``validate_measurement_methods`` above — see it for what is refused and why a
    # refusal rather than a silent drop.
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
    # ── THE CRAFTS AND ARTISANS THIS TOOL IS LINKED TO, PLURAL ──────────────────────────────────
    #
    # NOT COLUMNS. Both are popped by ``routes/tools`` and written as ``ToolCraft`` / ``ToolArtisan``
    # rows; ``craftId``/``artisanId`` above stay exactly where they are and are DERIVED from element
    # 0 when these are present and non-empty, which is what keeps every existing filter, index,
    # report and carry-forward that reads the singular column reading the same value as before.
    #
    # ``artisanIds`` IS SPELLED EXACTLY AS ``ToolArtisanAssign.artisanIds`` SPELLS IT, below, because
    # it is the same list of the same ids meaning the same thing — the tool-to-artisans join. Two
    # names for one relation is how a client comes to send one of them and wonder why the other
    # screen disagrees.
    #
    # NULL IS REFUSED, and the validator below is the only thing that can refuse it. ABSENT means
    # "leave the links alone"; ``[]`` means "no links". ``clean_data`` drops a ``None`` for anything
    # outside ``CLEARABLE_KEYS``, and these are not columns so ``_CLEARABLE_COLUMNS`` cannot carry
    # them — an explicit null would therefore be silently indistinguishable from an absent key,
    # which is the one distinction the whole contract rests on.
    craftIds: list[str] | None = None
    artisanIds: list[str] | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    # The create-idempotency key. Optional; absent is today's behaviour exactly. See
    # ``WorkshopCreate.clientKey``.
    clientKey: str | None = Field(default=None, max_length=200)

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)
    # The marker validator, attached on each of the four bodies that carries the key. Four separate
    # lines and not one shared base, because a base class would also hand the key to every OTHER
    # record schema in this file — an artisan has no dimensions to state a method for.
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)

    @field_validator("craftIds", "artisanIds", mode="before")
    @classmethod
    def _no_explicit_null_link_list(cls, value: Any) -> Any:
        # Pydantic does NOT call a field validator for an ABSENT field with a default, so reaching
        # this with ``None`` means the caller sent a literal null. The two spellings mean different
        # things to the route and it cannot tell them apart once pydantic has parsed them, so the
        # refusal has to happen here, by name, with the fix in the message.
        if value is None:
            raise ValueError("send [] to clear the links, or omit the key to leave them alone")
        return value


class ToolUpdate(APIModel):
    # Bounded on the UPDATE by the same constant as on the create, and for a reason that only bites
    # here: a stored ``craftName`` the SERVER derived is seeded back into the box by both forms and
    # re-sent on the next save, so a cap this schema could not accept would make the record refuse
    # every later edit on a value nobody typed. See ``TOOL_CRAFT_NAME_MAX``.
    craftName: str | None = Field(default=None, min_length=1, max_length=TOOL_CRAFT_NAME_MAX)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)
    toolkitName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    yearsInUse: int | None = Field(default=None, ge=0)
    # ge=0 on the UPDATE too — see ``ProductCreate.lengthInches``. Bounding create alone would leave
    # the number correctable through a PATCH and therefore not bounded at all.
    height: Decimal | None = Field(default=None, ge=0)
    width: Decimal | None = Field(default=None, ge=0)
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    # See ``ToolCreate.heightInches``: this is the inch box a machine reading lands in, and ``height``
    # above is its centimetre partner — two units of one measurement on every client since 2026-09-15,
    # and still two independent columns to this server, which converts neither.
    heightInches: Decimal | None = Field(default=None, ge=0)
    # HOW each of the three dimensions above was measured. Not a column: popped by
    # ``records.merge_field_provenance`` and merged into that dimension's provenance stamp. Omitting
    # it is legal and means UNRECORDED; it never means TYPED, and it is never sent as null.
    # Validated by ``validate_measurement_methods`` above — see it for what is refused and why a
    # refusal rather than a silent drop.
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
    # ── THE CRAFTS AND ARTISANS THIS TOOL IS LINKED TO, PLURAL ──────────────────────────────────
    #
    # NOT COLUMNS. Both are popped by ``routes/tools`` and written as ``ToolCraft`` / ``ToolArtisan``
    # rows; ``craftId``/``artisanId`` above stay exactly where they are and are DERIVED from element
    # 0 when these are present and non-empty, which is what keeps every existing filter, index,
    # report and carry-forward that reads the singular column reading the same value as before.
    #
    # ``artisanIds`` IS SPELLED EXACTLY AS ``ToolArtisanAssign.artisanIds`` SPELLS IT, below, because
    # it is the same list of the same ids meaning the same thing — the tool-to-artisans join. Two
    # names for one relation is how a client comes to send one of them and wonder why the other
    # screen disagrees.
    #
    # NULL IS REFUSED, and the validator below is the only thing that can refuse it. ABSENT means
    # "leave the links alone"; ``[]`` means "no links". ``clean_data`` drops a ``None`` for anything
    # outside ``CLEARABLE_KEYS``, and these are not columns so ``_CLEARABLE_COLUMNS`` cannot carry
    # them — an explicit null would therefore be silently indistinguishable from an absent key,
    # which is the one distinction the whole contract rests on.
    craftIds: list[str] | None = None
    artisanIds: list[str] | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    # The version this edit was composed against. Optional; omitted is today's behaviour exactly. See
    # ``ArtisanUpdate.expectedUpdatedAt`` and ``records.assert_expected_updated_at``.
    expectedUpdatedAt: datetime | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)
    # The marker validator, attached on each of the four bodies that carries the key. Four separate
    # lines and not one shared base, because a base class would also hand the key to every OTHER
    # record schema in this file — an artisan has no dimensions to state a method for.
    _measurement_methods = model_validator(mode="after")(validate_measurement_methods)

    @field_validator("craftIds", "artisanIds", mode="before")
    @classmethod
    def _no_explicit_null_link_list(cls, value: Any) -> Any:
        # Pydantic does NOT call a field validator for an ABSENT field with a default, so reaching
        # this with ``None`` means the caller sent a literal null. The two spellings mean different
        # things to the route and it cannot tell them apart once pydantic has parsed them, so the
        # refusal has to happen here, by name, with the fix in the message.
        if value is None:
            raise ValueError("send [] to clear the links, or omit the key to leave them alone")
        return value


class ToolArtisanAssign(APIModel):
    """Assign one documented tool to several artisans (same or different crafts)."""

    artisanIds: list[str] = Field(default_factory=list)
