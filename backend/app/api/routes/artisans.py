from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    require_record_creator,
    assert_can_delete,
    can_manage_crafts,
    get_current_user,
)
from app.schemas.records import ArtisanCreate, ArtisanUpdate, drop_masked_identity_numbers
from app.services.access import guard_record_edit
from app.services.artisan_identity import mask_aadhaar, normalize_aadhaar
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import artisan_workshop_clause, resolve_workshop_ids
from app.services.records import (
    Relation,
    public_encode,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    attach_location,
    clean_data,
    contains,
    count_and_page,
    hydrate_relations,
    include_of,
    merge_field_provenance,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    link_workshop_artisan,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/artisans", tags=["artisans"])

# What an artisan carries on the wire. Reads load these in one parallel wave (see
# services/records.py for why that is worth the indirection); writes still pass the derived
# ``INCLUDE`` to Prisma, so the two can never describe different artisans.
RELATIONS = (
    Relation("craft", "craft", "craftId"),
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("workshop", "workshop", "workshopId"),
)
INCLUDE = include_of(RELATIONS)

# Unique columns that identify a real-world person, and the human phrasing for each. A collision on
# one of these is the deduplication working as designed, so it has to read as "you already have this
# artisan" rather than as a database error.
_IDENTITY_CONSTRAINTS = {
    "aadhaarNumber": "Aadhaar number",
    "pehchanCardNumber": "Artisan Pehchan Card number",
}

# ARTISAN'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null`` through for
# on this model, and the reason a researcher can retract a phone number at all.
#
# A FIELD THAT CANNOT BE CLEARED IS A 200 THAT DOES NOTHING. Until this tuple existed, emptying the
# phone box on the artisan form showed the field empty, reported success, and left the stored number
# in the database: ``clean_data`` dropped the ``None`` before anything could act on it. The case with
# no workaround at all is retracting personal information a subject has asked to have removed —
# there is no "" to send instead when the column is a nullable ``String?`` and the client means NULL.
#
# WHY THIS IS A PER-MODEL LIST AND NOT MORE ENTRIES IN ``records.CLEARABLE_KEYS``: that set is global
# and ``clean_data`` does not know which table a payload is bound for. ``email`` is nullable here and
# NOT NULL on other tables, so a global entry would trade one silent no-op for a constraint violation
# elsewhere. See the ``clearable`` section of ``clean_data``'s docstring for the whole argument,
# including why this may only be passed from a route that dumps with ``exclude_unset=True`` —
# ``update_artisan`` below does, and must keep doing.
#
# WHAT IS DELIBERATELY ABSENT, so a later reader does not "complete" the list:
#   * ``name`` and ``place`` — NOT NULL on Artisan.
#   * ``pehchanCardAvailable``, ``status``, ``recordedAt``, ``recordedTimezone`` — NOT NULL.
#   * ``craftId``/``workshopId``/``locationId`` and both identity numbers — already global.
#   * ``craftName`` — not a column at all; ``resolve_craft_id`` turns it into ``craftId``.
#   * ``extraMetadata`` — nullable, but naming it here would change nothing: ``merge_field_provenance``
#     OWNS that column on this route and reassigns it a few lines below the clean, so the null never
#     survives to Prisma either way. Left out rather than listed-and-inert.
#
# ``dos``/``donts`` ARE here even though ``ArtisanCreate`` demands them, and that asymmetry is the
# existing one, not a new one: the columns are nullable precisely because rows recorded before the
# fields existed hold NULL, and ``ArtisanUpdate`` deliberately carries no ``min_length`` on either —
# so an editor can already empty the box today by sending ``""``. NULL is the honest spelling of the
# same edit rather than a state the model did not already have.
#
# WHAT A RETRACTION DOES NOT ERASE, because nobody should read this list as a right-to-erasure
# mechanism. ``services/access.record_revision`` copies the OLD value of every changed column that is
# not in ``REVISION_SKIP_FIELDS`` into an immutable ``RecordRevision.changes`` blob — so the number
# the subject asked to have removed is copied INTO the ledger by the request that removes it, and the
# revision panel reads it back. Narrowing that is an audit-surface decision with its own blast radius
# and is an OWNER CALL, not a follow-up patch. ``merge_field_provenance`` also skips a cleared field
# entirely (``is_empty_value(None)`` is True), so ``extraMetadata.fieldProvenance.phone`` keeps its
# old ``{by, byName, at}`` stamp on a column that is now NULL: not the number, but still a row in the
# "Field contributions" panel naming who entered it and when.
_CLEARABLE_COLUMNS = (
    "localName",
    "gender",
    "phone",
    "email",
    "address",
    "notes",
    "dateOfBirth",
    # CLEARABLE for the same reason ``dateOfBirth`` is: a date typed into the wrong box has to be
    # retractable from the form that typed it, and an omitted key on a PATCH means "leave it alone".
    # Clearing it does not blank the artisan's experience — it hands the answer back to the stated
    # ``experienceYears`` number, then to the legacy metadata, which is what the precedence in
    # ``record_fields``' "Experience (years)" row is for.
    "craftStartDate",
    "experienceYears",
    # THE MONTHS BESIDE THE YEARS, AND CLEARABLE FOR THE SAME REASON THE YEARS ARE. The two boxes are
    # answered and un-answered together on the form, so a pair where one can be blanked and the other
    # cannot would leave an artisan reading "and 6 months" under an empty years box, with no way back
    # from the screen that typed it. NULL and 0 are different answers here — see
    # ``ArtisanUpdate.experienceMonths`` — and this entry is what makes the NULL half reachable:
    # without it ``clean_data`` drops the explicit null and the PATCH answers 200 having changed
    # nothing at all.
    "experienceMonths",
    "dos",
    "donts",
)


def _violated_identity_field(error: Exception) -> str | None:
    """Which identity column a Prisma unique-constraint error was raised on, if any."""
    text = str(error)
    if "unique" not in text.lower():
        return None
    for field in _IDENTITY_CONSTRAINTS:
        if field in text:
            return field
    return None


async def _identity_conflict(
    field: str, value: str, exclude_id: str | None = None, existing: Any = None
) -> HTTPException:
    """A 409 naming the artisan that already holds this number, so the researcher can go to them.

    The existing artisan's name and place are safe to return — the caller already possesses the
    identity number, and without them the message is a dead end ("duplicate" with nowhere to go).
    The number itself is echoed back MASKED.

    Pass ``existing`` when the caller has already loaded the clashing artisan: the pre-flight guard
    below has it in hand, and re-reading it would cost another cross-region round trip to learn
    something we already know.
    """
    if existing is None:
        existing = await db.artisan.find_first(where={field: value}, include={"craft": True})
    detail: dict[str, Any] = {
        "code": "artisan_identity_conflict",
        "field": field,
        "message": f"Another artisan is already recorded with this {_IDENTITY_CONSTRAINTS[field]}.",
    }
    if existing is not None and existing.id != exclude_id:
        detail["existingArtisan"] = {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
        }
        detail["message"] = (
            f"{existing.name} ({existing.place}) is already recorded with this "
            f"{_IDENTITY_CONSTRAINTS[field]}. Open that artisan instead of creating a duplicate."
        )
    if field == "aadhaarNumber":
        detail["maskedValue"] = mask_aadhaar(value)
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)


def _may_read_full_aadhaar(user: Any, artisan: Any) -> bool:
    """May this caller see an artisan's UNMASKED Aadhaar number?

    Aadhaar is regulated personal data, so plain "is signed in" is not the right bar — without this,
    a crowdsource volunteer could read any artisan's full number straight off ``GET /artisans/{id}``.
    The line is drawn at the people who actually need it to do the work: the researcher who recorded
    the artisan, and professor-and-above (which includes admins) who supervise and correct the data.
    Everyone else sees the same masked form the exports show.
    """
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return True
    return bool(get_value(user, "id")) and get_value(artisan, "createdById") == get_value(user, "id")


def _mask_artisan_identity(payload: Any, user: Any, artisan: Any) -> Any:
    """Replace ``aadhaarNumber`` with its masked form for callers not entitled to the raw value.

    Applied to the ENCODED payload, after ``public_encode``, so it covers the response no matter how
    the row was loaded. The masked string is deliberately the same ``XXXX XXXX 9012`` the data
    browser and the workbook show, so one artisan reads identically wherever they appear.
    """
    if not isinstance(payload, dict) or "aadhaarNumber" not in payload:
        return payload
    if _may_read_full_aadhaar(user, artisan):
        return payload
    payload["aadhaarNumber"] = mask_aadhaar(payload.get("aadhaarNumber"))
    return payload


async def _guard_identity_conflicts(data: dict[str, Any], exclude_id: str | None = None) -> None:
    """Reject a duplicate Aadhaar/Pehchan number BEFORE writing, with a message worth reading.

    The unique indexes are the real guarantee — this is the pre-check that turns a would-be 500 into
    an actionable 409. The write is still wrapped in its own handler, because two researchers can
    submit the same artisan in the same instant and only the index can settle that race.

    Both numbers are checked in ONE query, and that query already loads what the 409 needs to say.
    Checking them one at a time cost three sequential cross-region round trips on the way to every
    saved artisan — two probes and a third to look up the artisan being reported — for a question a
    single ``OR`` answers.
    """
    checks = {field: data[field] for field in _IDENTITY_CONSTRAINTS if data.get(field)}
    if not checks:
        return
    clashes = await db.artisan.find_many(
        where={"OR": [{field: value} for field, value in checks.items()]},
        include={"craft": True},
    )
    for field, value in checks.items():
        for clash in clashes:
            if getattr(clash, field, None) == value and clash.id != exclude_id:
                raise await _identity_conflict(field, value, exclude_id, existing=clash)


async def resolve_craft_id(data: dict[str, Any], current_user: Any) -> dict[str, Any]:
    craft_name = data.pop("craftName", None)
    if data.get("craftId") or not craft_name:
        return data
    existing = await db.craft.find_unique(where={"name": craft_name})
    if existing:
        data["craftId"] = existing.id
        return data
    # A free-text craft name that matches nothing would otherwise mint a Craft through the artisan
    # form — the same write POST /crafts guards, reached sideways. Same predicate, so the two can
    # never disagree: Professor and above.
    if not can_manage_crafts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                f"Craft '{craft_name}' does not exist yet. Select an existing craft, or ask a "
                "professor or an admin to add it."
            ),
        )
    created = await db.craft.create(data={"name": craft_name, "createdById": current_user.id})
    data["craftId"] = created.id
    return data


@router.get("")
async def list_artisans(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craft: str | None = None,
    craftId: str | None = None,
    workshopId: str | None = None,
    place: str | None = None,
    statusFilter: str | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    # The workshop SCOPE, plural, from the shared filter vocabulary — repeatable or comma-joined ids
    # plus the reserved value "none". Distinct from the singular ``workshopId`` above, which stays
    # because every existing form-picker link uses it; when both are sent both narrow.
    #
    # It is broader than the singular filter on purpose: it also counts an artisan who SAT IN an
    # interview taken at the workshop, through the shared ``artisan_workshop_clause``, so the
    # consolidated-questionnaire index and the completion matrix cannot disagree about who was there.
    workshopIds: list[str] | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so the
    # free-text search OR and the workshop OR can never overwrite one another — nor the row-visibility
    # filter, which joins the same AND.
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"place": contains(search)},
            {"notes": contains(search)},
            {"craft": {"is": {"name": contains(search)}}},
        ]
    if craft:
        where["craft"] = {"is": {"name": contains(craft)}}
    if craftId:
        where["craftId"] = craftId
    if workshopId:
        # Either reading counts: the artisan's own workshopId column, or the WorkshopArtisan join
        # (relation named ``workshops``) that carried the link before the column existed.
        and_filters.append({"OR": [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]})
    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        and_filters.append(artisan_workshop_clause(*resolved_workshops))
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    total, items = await count_and_page(
        db.artisan,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    # A list page hands back many artisans at once, so mask each row the caller is not entitled to
    # read raw — otherwise one request yields every id AND every full Aadhaar on the page.
    # The encoder masks per node against this viewer, so a list of a hundred artisans is one
    # decision per artisan rather than one blanket decision for the page: the two the caller
    # recorded come back raw, the rest masked.
    return page_payload(public_encode(items, current_user), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_artisan(
    payload: ArtisanCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    # Workshop entries: enforce assignment, then flag + pin a late submission for admin approval.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    # Aadhaar / Pehchan are the dedup keys: point the researcher at the existing artisan instead of
    # letting the unique index surface as a 500.
    await _guard_identity_conflicts(data)
    try:
        created = await db.artisan.create(data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by _violated_identity_field
        field = _violated_identity_field(exc)
        if field is None:
            raise
        # Lost the race against a concurrent submission of the same person.
        raise await _identity_conflict(field, data[field]) from exc
    # The explicit column ADDS to the WorkshopArtisan join every existing query still reads through.
    await link_workshop_artisan(created.workshopId, created.id)
    # The creator is by definition entitled, but pass the viewer anyway rather than relying on
    # that: a future change to who may create an artisan would otherwise silently leak.
    return public_encode(created, current_user)


@router.get("/{artisan_id}")
async def get_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    await hydrate_relations([artisan], RELATIONS)
    # Masked unless this caller is entitled to the raw number (creator, or professor+) — decided
    # inside the encoder now, so the same rule covers this route and every route that merely embeds
    # an Artisan relation without thinking about it.
    return public_encode(artisan, current_user)


@router.patch("/{artisan_id}")
async def update_artisan(
    artisan_id: str,
    payload: ArtisanUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    # The precondition is a QUESTION, not a column — taken out of the body on the line after the
    # clean, because everything between here and the write reads ``data``: ``guard_record_edit``
    # diffs it into a ``RecordRevision``, ``merge_field_provenance`` stamps a contributor against
    # every key it holds, and Prisma is finally handed it as columns.
    expected_updated_at = take_expected_updated_at(data)
    # ABOVE ``guard_record_edit``, and that ordering is the whole of the safety in a backend with no
    # transactions: ``guard_record_edit`` ends in ``record_revision``, which COMMITS a ledger row
    # asserting a change, and there is nothing here to roll it back with.
    assert_expected_updated_at(artisan, expected_updated_at)
    # A caller shown a masked number who saves without touching it means "leave it alone" — for the
    # Pehchan card as much as for the Aadhaar, since both are masked on the way out to them.
    data = drop_masked_identity_numbers(data)
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != artisan.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(artisan, current_user, data, "artisan")
    await apply_status_policy_update(current_user, artisan, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=artisan)
    pin_pending_if_late(data, current_user, check=check, record=artisan)
    merge_field_provenance(data, current_user, previous=artisan)
    resubmit_status(artisan, current_user, data)
    # "Card available = Yes" on an edit is only valid if a number exists — either arriving in this
    # PATCH or already stored. The create-time validator cannot make this call, because a PATCH
    # carrying just the flag is legitimate when the record already holds a number.
    #
    # There are two ways a PATCH can break that pair, and the schema validator can see neither: it is
    # handed only the keys the client actually sent, so it never learns what is on the row.
    #   * the answer turns to Yes while no number exists here or on the record, and
    #   * the number is CLEARED while the answer stays Yes — the mirror image, and the one that
    #     otherwise slips through as {"pehchanCardNumber": null} on its own.
    # A PATCH touching neither key is deliberately left alone: rows that predate this feature carry
    # the column's DEFAULT of Yes with no number, and an unrelated edit to one must not be refused.
    answered_yes = data.get("pehchanCardAvailable") is True
    clears_number = "pehchanCardNumber" in data and not data["pehchanCardNumber"]
    still_yes = data.get("pehchanCardAvailable", artisan.pehchanCardAvailable) is True
    if answered_yes or (clears_number and still_yes):
        number = data.get("pehchanCardNumber", artisan.pehchanCardNumber)
        if not number:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "Enter the Artisan Pehchan Card number, or set the card to 'No' if the artisan "
                    "does not hold one."
                ),
            )
    await _guard_identity_conflicts(data, exclude_id=artisan_id)
    try:
        updated = await db.artisan.update(where={"id": artisan_id}, data=data, include=INCLUDE)
    except Exception as exc:  # noqa: BLE001 - narrowed immediately by _violated_identity_field
        field = _violated_identity_field(exc)
        if field is None:
            raise
        raise await _identity_conflict(field, data[field], exclude_id=artisan_id) from exc
    await link_workshop_artisan(updated.workshopId, updated.id)
    return public_encode(updated, current_user)


@router.get("/lookup/aadhaar")
async def lookup_artisan_by_aadhaar(
    number: str, _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Is this Aadhaar already on an artisan? The form's pre-flight duplicate check.

    Lets the researcher find out while they are still typing, instead of after filling a whole form
    and hitting a 409 on save. Returns ``{found, artisan?}`` and never 404s — "not found" is the
    expected, successful answer. Only the identifying fields of the existing artisan come back, and
    only to a signed-in user who already holds the number they searched with.
    """
    normalized = normalize_aadhaar(number)
    if not normalized:
        return {"found": False, "artisan": None}
    existing = await db.artisan.find_first(
        where={"aadhaarNumber": normalized}, include={"craft": True, "workshop": True}
    )
    if existing is None:
        return {"found": False, "artisan": None}
    return {
        "found": True,
        "artisan": {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
            "workshop": getattr(getattr(existing, "workshop", None), "title", None),
        },
    }


@router.get("/{artisan_id}/questionnaire")
async def get_artisan_questionnaire(artisan_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Everything recorded against this artisan in the questionnaire, gathered per artisan.

    Because one interview is shared across an exact set of artisans, a recording/note/answer made for
    a group (or a larger superset) belongs to EACH member individually — so it surfaces here for every
    artisan in the set, letting each be validated on their own, as part of a subset, or for the whole
    set. Returns: ``answered`` (non-empty answers across every interview the artisan belongs to) and
    ``interviews`` (each interview the artisan is in, with its recordings/media, notes, and the other
    artisans it was recorded with). Deletion of any media stays uploader-or-admin; the interview row is
    admin-only — enforced on the media/questionnaire routes, not here.

    ``answered`` IS GROUPED BY INSTRUMENT FIRST and each row carries ``questionnaireId`` /
    ``questionnaireTitle``. Since the 2026-09-13 instruments migration one artisan can sit for two
    questionnaires whose sections share every code, so neither the order nor the two fields is
    cosmetic — see the block comment above the sort below for what a code-only ordering does to that
    list.
    """
    # The artisan check, the answers and the interviews are three independent reads, so they run
    # together — the 404 is still decided first, it just no longer holds the other two behind a
    # cross-region round trip of its own. Every interview the artisan belongs to (alone, in a subset,
    # or in a larger set) comes back with its recordings and co-artisans, so the same content is
    # validatable for this artisan individually.
    artisan, responses, interview_rows, instruments = await gather_reads(
        db.artisan.find_unique(where={"id": artisan_id}),
        db.questionnaireresponse.find_many(
            where={
                "interview": {"is": {"artisans": {"some": {"artisanId": artisan_id}}}},
                "answerText": {"not": None},
            },
            include={"question": True, "interview": True, "answeredBy": True},
            order={"createdAt": "asc"},
        ),
        db.questionnaireinterview.find_many(
            where={"artisans": {"some": {"artisanId": artisan_id}}},
            include={"artisans": {"include": {"artisan": True}}, "media": True},
            order={"createdAt": "desc"},
        ),
        # EVERY INSTRUMENT, IN ONE ROW-SET, RATHER THAN A NESTED include ON EACH ANSWER. There are a
        # handful of questionnaires and there can be hundreds of answers; joining the instrument
        # onto every response row would repeat the same title and description hundreds of times over
        # the wire from the database for two strings used once per row. This is the shape
        # ``services/questionnaire_consolidation`` already uses for the same lookup, so the two
        # screens that render an artisan's answers cannot disagree about what an instrument is
        # called.
        db.questionnaire.find_many(),
    )
    if not artisan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    instrument_by_id = {row.id: row for row in instruments}
    answered: list[dict[str, Any]] = []
    for response in responses:
        if not (response.answerText and response.answerText.strip()):
            continue
        question = response.question
        interview = response.interview
        answered_by = response.answeredBy
        # WHICH INSTRUMENT THIS ANSWER BELONGS TO, taken from the QUESTION rather than the
        # interview. Both rows carry it and the two cannot disagree - a question may never move
        # between instruments (``update_question`` and ``reorder_questions`` both refuse it with a
        # 422, schema.prisma:1299) and an interview's ``questionnaireId`` is resolved once at create
        # and has no update path at all - but the question is the row whose ``sectionCode`` and
        # ``sortOrder`` are being sorted on below, so the instrument that disambiguates them must
        # come from the same row or the sort key is assembled from two sources. The interview is the
        # fallback only for the impossible case of an unhydrated question, where every other field
        # on this dict is already None.
        questionnaire_id = (
            getattr(question, "questionnaireId", None)
            or getattr(interview, "questionnaireId", None)
        )
        instrument = instrument_by_id.get(questionnaire_id)
        answered.append(
            {
                "responseId": response.id,
                "questionId": response.questionId,
                "prompt": question.prompt if question else None,
                "sectionCode": question.sectionCode if question else None,
                "sectionTitle": question.sectionTitle if question else None,
                "sortOrder": question.sortOrder if question else 0,
                # NEW WIRE FIELDS, 2026-09-13. Without them a client rendering this list has no way
                # to tell the 2nd workshop's section A from the 3rd's: the code, the title and very
                # often the prompt are identical between instruments, so two answers to two
                # different questions print as one question answered twice.
                "questionnaireId": questionnaire_id,
                "questionnaireTitle": getattr(instrument, "title", None),
                "answerText": response.answerText,
                "notes": response.notes,
                "interviewId": response.interviewId,
                "interviewTitle": interview.title if interview else None,
                "interviewDate": interview.interviewDate if interview else None,
                "answeredByName": answered_by.name if answered_by else None,
            }
        )
    # ==============================================================================================
    # BY INSTRUMENT FIRST. SORTING ON (sectionCode, sortOrder) ALONE NOW INTERLEAVES TWO WORKSHOPS.
    # ==============================================================================================
    #
    # ``20260913100000_questionnaire_instruments`` made one artisan legitimately interviewable once
    # per instrument. The 2nd Craft Toolkit Workshop's corpus is coded RESP, A..W and the 3rd's is
    # coded A..V - EVERY ONE of the 22 new codes already existed, which is the collision the
    # migration's header spends its first paragraphs on. So an artisan who sat for both has two
    # section "A"s, two section "B"s, and within each a ``sortOrder`` that restarts at 1.
    #
    # A sort on ``(sectionCode, sortOrder)`` does not merely order those badly, it SHUFFLES THEM
    # TOGETHER: 3rd-workshop A1 lands next to 2nd-workshop A1, under one heading, with nothing on
    # the row to say they came from different instruments. A researcher reading that list sees one
    # questionnaire answered inconsistently. Section V is the worst case in the corpus -
    # "International Exposure and Overseas Travel" in one instrument and "NETWORK / ECOSYSTEM
    # MAPPING" in the other - so the two sets of answers do not even describe the same subject.
    #
    # The instrument's own ``sortOrder`` leads, so the 2nd workshop's account reads before the 3rd's,
    # which is the order ``questionnaire_consolidation`` already prints the same artisan's document
    # in. Its ID BREAKS THE TIE, and that tie is real rather than defensive:
    # ``Questionnaire.sortOrder`` is deliberately NOT unique (schema.prisma:1175) because two
    # instruments sharing a picker position is a cosmetic tie, so without the id the interleaving
    # comes straight back for any two instruments an admin happened to give the same number - and it
    # comes back NON-DETERMINISTICALLY, in a different order on every load, from whatever order the
    # database returned the rows in.
    answered.sort(
        key=lambda item: (
            getattr(instrument_by_id.get(item.get("questionnaireId")), "sortOrder", 0) or 0,
            item.get("questionnaireId") or "",
            item.get("sectionCode") or "",
            item.get("sortOrder") or 0,
        )
    )

    interviews: list[dict[str, Any]] = []
    for interview in interview_rows:
        co_artisans = [
            link.artisan.name
            for link in (interview.artisans or [])
            if link.artisan and link.artisanId != artisan_id
        ]
        interviews.append(
            {
                "interviewId": interview.id,
                "title": interview.title,
                "notes": interview.notes,
                "interviewDate": interview.interviewDate,
                "place": interview.place,
                "language": interview.language,
                "status": interview.status,
                "artisanCount": len(interview.artisans or []),
                "coArtisans": co_artisans,
                "media": public_encode(interview.media or []),
            }
        )
    return public_encode(
        {
            "artisanId": artisan_id,
            "answered": answered,
            "total": len(answered),
            "interviews": interviews,
        }
    )


@router.delete("/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.artisan, artisan_id)
    await db.artisan.delete(where={"id": artisan_id})
