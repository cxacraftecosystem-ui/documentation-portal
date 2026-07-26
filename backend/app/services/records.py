from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder
from prisma import Json

from app.core.db import db
from app.services.text_format import title_case_fields

# Keys that must never leave the API, no matter how deeply nested inside an embedded relation
# (e.g. a media file's ``uploadedBy`` user, or a record's ``createdBy``).
_SENSITIVE_KEYS = {"passwordHash"}


def _strip_sensitive(value: Any) -> Any:
    """Recursively remove sensitive keys (password hashes) from an already-encoded payload."""
    if isinstance(value, dict):
        for key in _SENSITIVE_KEYS:
            value.pop(key, None)
        for nested in value.values():
            _strip_sensitive(nested)
    elif isinstance(value, list):
        for item in value:
            _strip_sensitive(item)
    return value


def public_encode(obj: Any) -> Any:
    """``jsonable_encoder`` plus a recursive scrub of sensitive fields.

    Use this for any response that embeds a User relation (``createdBy``/``uploadedBy``/
    ``answeredBy``/``reviewedBy``) so a researcher can never read another account's password hash
    out of the JSON.
    """
    return _strip_sensitive(jsonable_encoder(obj))


def to_json(value: Any) -> Any:
    """Wrap dict/list values destined for a Prisma Json column. prisma-client-py rejects raw dicts."""
    if isinstance(value, (dict, list)):
        return Json(value)
    return value


def jsonify_metadata(data: dict[str, Any], *fields: str) -> dict[str, Any]:
    """Wrap the given JSON-column fields in ``Json`` if they are plain dict/list values."""
    keys = fields or ("extraMetadata", "measurementAnalysis", "result")
    for key in keys:
        if key in data and isinstance(data[key], (dict, list)):
            data[key] = Json(data[key])
    return data

# Nullable relation columns a client may deliberately CLEAR.
#
# Update payloads are dumped with ``exclude_unset=True``, so a key is present only when the caller
# actually sent it: ``{"workshopId": None}`` means "unlink this record", which is different from
# omitting the key ("leave it alone"). Stripping those Nones the way we strip every other one made
# unlinking a silent no-op — the save returned 200, the form showed "Unlinked", and the old link
# survived in the database. These keys therefore survive the clean with their explicit ``None``.
#
# Only relation FKs belong here. Scalar fields keep the old behaviour, because blanking those is
# governed by the field-clearing guard in ``deps.assert_can_contribute_fields`` instead.
CLEARABLE_KEYS = frozenset(
    {
        "workshopId",
        "craftId",
        "artisanId",
        "productId",
        "toolId",
        "processId",
        "locationId",
        "questionnaireInterviewId",
        # Identity numbers a researcher can legitimately retract: an Aadhaar entered against the
        # wrong artisan has to be removable, and answering "no card" must clear the card number in
        # the same request rather than orphaning it on the record.
        "aadhaarNumber",
        "pehchanCardNumber",
    }
)


# Name-like columns that are title-cased on WRITE (see services/text_format.py for the rule and for
# WHY normalising here rather than in a client is the only fix that holds for web + Android + scripts).
#
# The list is by COLUMN NAME because ``clean_data`` is the one chokepoint every create and update
# funnels through, and it does not know which model the payload is bound for. Every column below is
# name-like in every model that has it:
#
#   name         Artisan.name, Craft.name, Process.name, User.name
#   craftName    Artisan create/update input (resolved to Craft.name), Product.craftName,
#                Tool.craftName  -- casing this BEFORE artisans.resolve_craft_id does its exact-match
#                ``find_unique(where={"name": ...})`` is what stops "bandhani" and "Bandhani" from
#                becoming two crafts
#   artisanName  Product.artisanName, Tool.artisanName
#   productName  ProductDocumentation.productName
#   toolkitName  ToolDocumentation.toolkitName
#   englishName  ToolDocumentation.englishName
#   title        Workshop.title, QuestionnaireSection.title, QuestionnaireInterview.title
#   place        Artisan.place, Craft.place, Workshop.place, Product.place, Tool.place,
#                QuestionnaireInterview.place
#   placeName    Location.placeName (written through ``attach_location``)
#   state        Location.state (written through ``attach_location``). Harmless and idempotent: all
#                36 canonical names in services/address.py are already fixed points of this rule, and
#                the value has been resolved to one of them by LocationInput before it gets here
#   village/district
#                no columns today (artisans keep these in extraMetadata) -- listed so they normalise
#                from day one if they are ever promoted to real columns
#
# DELIBERATELY ABSENT, because casing them would damage meaning rather than tidy it: notes,
# description, remarks, address, dos, donts, transcriptText/transcriptSummary, caption, prompt, email,
# phone, localName (Indic script, and title_case leaves it alone anyway), and every identifier
# (aadhaarNumber, pehchanCardNumber, originalFilename, objectKey, ids).
TITLE_CASE_FIELDS = frozenset(
    {
        "name",
        "craftName",
        "artisanName",
        "productName",
        "toolkitName",
        "englishName",
        "title",
        "place",
        "placeName",
        "village",
        "district",
        "state",
    }
)


def clean_data(data: dict[str, Any], *, title_case: bool = True) -> dict[str, Any]:
    """Drop keys whose value is ``None``, keeping the deliberate nulls in :data:`CLEARABLE_KEYS`, and
    title-case the name-like fields in :data:`TITLE_CASE_FIELDS`.

    Casing happens HERE, at the very top of every write path, so the normalised value is what every
    later step sees: the craft lookup that matches on an exact name, the ``RecordRevision`` diff, the
    field-provenance comparison and the uniqueness checks all agree with what is finally stored.

    Pass ``title_case=False`` from a route whose payload happens to reuse one of those column names
    for prose rather than a name — a generated task title, say — where sentence casing is correct.
    """
    cleaned = {
        key: value for key, value in data.items() if value is not None or key in CLEARABLE_KEYS
    }
    return title_case_fields(cleaned, TITLE_CASE_FIELDS) if title_case else cleaned


def decimal_to_string(data: dict[str, Any]) -> dict[str, Any]:
    converted: dict[str, Any] = {}
    for key, value in data.items():
        if isinstance(value, Decimal):
            converted[key] = str(value)
        elif isinstance(value, dict):
            converted[key] = decimal_to_string(value)
        else:
            converted[key] = value
    return converted


async def attach_location(data: dict[str, Any]) -> dict[str, Any]:
    location = data.pop("location", None)
    if location:
        location_data = location.model_dump() if hasattr(location, "model_dump") else dict(location)
        created = await db.location.create(data=clean_data(location_data))
        data["locationId"] = created.id
    return data


# Characters Postgres will not accept inside a text value. NUL is the one that matters: a `text`
# column cannot hold 0x00 at all, so the driver raises and — because this is a query PARAMETER, not
# a query the caller composed — the failure surfaces as a 500 rather than as a validation error.
# The rest are the C0 controls that carry no meaning in a search box and only exist in pasted junk.
_UNSEARCHABLE = {c: None for c in range(32) if c not in (9, 10, 13)}


def contains(value: str) -> dict[str, Any]:
    """A case-insensitive `contains` filter, with the bytes Postgres cannot store stripped out.

    Every text search in the app funnels through here (57 call sites), which is why the sanitising
    lives here rather than in each route: a single NUL byte pasted into any search box — /search,
    artisans, crafts, tools, products, media, processes, questionnaires, users — returned a 500 from
    every one of them. Stripping is the right response rather than rejecting: a researcher who
    pasted a name out of a PDF and picked up a stray control character wants their search to run,
    not a validation error about a byte they cannot see.

    Tab, newline and carriage return are deliberately kept — Postgres stores them happily and they
    can legitimately appear in a pasted multi-line name.
    """
    return {"contains": value.translate(_UNSEARCHABLE), "mode": "insensitive"}


async def visibility_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    """Row-visibility filter for record list queries.

    Professor and above (and admins) see every record — an empty filter. Below professor a user sees
    only records they own, plus records owned by anyone who has GRANTED them a data-access grant (any
    tier, subset grants included — coarse, but always grant-gated). ``owner_field`` names the record's
    owner column (``createdById`` for records, ``uploadedById`` for media). Async because it reads the
    grant table; every caller must ``await`` it, and it must be AND-composed with any other ``OR`` a
    list route builds (nest it under ``where["AND"]``) so a search ``OR`` never overwrites it.
    """
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return {}
    uid = get_value(user, "id")
    grants = await db.dataaccessgrant.find_many(where={"granteeId": uid, "status": "GRANTED"})
    granted_owner_ids = [g.ownerId for g in grants]
    return {"OR": [{owner_field: uid}, {owner_field: {"in": granted_owner_ids}}]}


def apply_status_policy_create(user: Any, data: dict[str, Any]) -> dict[str, Any]:
    """Force the initial status by rank. Professor and above default to APPROVED (keeping any explicit
    status they passed); everyone below is FORCED to PENDING no matter what the client sent, so a
    researcher / field contributor / volunteer can never self-approve on create. Mutates ``data``.

    One thing outranks this: a submission made after its workshop ended. Routes that accept a
    ``workshopId`` call ``workshop_access.pin_pending_if_late`` immediately AFTER this, which pins such
    a record to PENDING even for a professor+ — only an admin may approve a late entry."""
    from app.core.deps import has_rank

    if has_rank(user, "PROFESSOR"):
        data.setdefault("status", "APPROVED")
    else:
        data["status"] = "PENDING"
    return data


async def apply_status_policy_update(user: Any, record: Any, data: dict[str, Any]) -> dict[str, Any]:
    """Authorize a status change on update, else silently drop it — old clients always echo the current
    status, so an unauthorized change must never 403. A status change sticks only when the editor is
    Professor+ AND is either the record's creator or ranks high enough to review the creator's work
    (``can_review_record``). Everything else — including a no-op that merely re-sends the current
    status — pops ``status`` so the stored value is untouched and ``resubmit_status`` can still flip a
    creator's edit back to PENDING. Call right after ``guard_record_edit`` and before ``resubmit_status``
    (on the workshop-aware routes, ``workshop_access.stamp_workshop_submission`` and
    ``pin_pending_if_late`` sit in between — the pin must run after this so a record flagged as a late
    workshop submission stays PENDING regardless of what this policy would have allowed).
    Mutates and returns ``data``; a no-op for records with no status column (``status`` never present)."""
    from app.core.deps import can_review_record, enum_or_raw, get_value, has_rank

    if "status" not in data:
        return data
    new_status = str(enum_or_raw(data["status"]))
    current = str(enum_or_raw(get_value(record, "status")))
    if new_status != current and has_rank(user, "PROFESSOR"):
        creator_id = get_value(record, "createdById")
        if creator_id is not None and creator_id == get_value(user, "id"):
            return data
        creator = await db.user.find_unique(where={"id": creator_id}) if creator_id else None
        if can_review_record(user, get_value(creator, "role") if creator else None):
            return data
    data.pop("status", None)
    return data


def add_date_range(where: dict[str, Any], field: str, date_from: datetime | None, date_to: datetime | None) -> None:
    range_filter: dict[str, Any] = {}
    if date_from:
        range_filter["gte"] = date_from
    if date_to:
        range_filter["lte"] = date_to
    if range_filter:
        where[field] = range_filter


async def require_record(delegate: Any, record_id: str) -> Any:
    record = await delegate.find_unique(where={"id": record_id})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


# Fields that are infrastructural / system-managed and should not be attributed to a contributor.
PROVENANCE_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "createdById",
    "createdAt",
    "updatedAt",
    "reviewedById",
    "reviewNotes",
    "reviewedAt",
    "recordedAt",
    "recordedTimezone",
    "measurementAnalysis",
    "measurementAnalysisStatus",
    "measurementImageId",
}


def merge_field_provenance(new_data: dict[str, Any], user: Any, previous: Any | None = None) -> None:
    """Record which user populated/changed each field, stored under extraMetadata.fieldProvenance.

    On create (``previous`` is ``None``) every non-empty field is attributed to ``user``. On update
    only fields whose value actually changes are re-attributed; unchanged fields keep the original
    contributor carried over from the previous record. This mutates ``new_data`` in place.
    """
    from app.core.deps import get_value, is_empty_value, values_match

    incoming_extra = new_data.get("extraMetadata")
    base_extra: dict[str, Any] = dict(incoming_extra) if isinstance(incoming_extra, dict) else {}

    provenance: dict[str, Any] = {}
    if previous is not None:
        previous_extra = get_value(previous, "extraMetadata")
        if isinstance(previous_extra, dict) and isinstance(previous_extra.get("fieldProvenance"), dict):
            provenance = dict(previous_extra["fieldProvenance"])

    stamp = {
        "by": get_value(user, "id"),
        "byName": get_value(user, "name"),
        "at": datetime.now(UTC).isoformat(),
    }

    for field, value in new_data.items():
        if field in PROVENANCE_SKIP_FIELDS or is_empty_value(value):
            continue
        previous_value = get_value(previous, field) if previous is not None else None
        if previous is None or is_empty_value(previous_value) or not values_match(previous_value, value):
            provenance[field] = stamp

    if provenance:
        base_extra["fieldProvenance"] = provenance
    if base_extra:
        # Prisma Json columns must receive a Json wrapper, not a raw dict.
        new_data["extraMetadata"] = Json(base_extra)
    else:
        new_data.pop("extraMetadata", None)


def resubmit_status(record: Any, user: Any, data: dict[str, Any]) -> dict[str, Any]:
    """When the CREATOR edits a record a reviewer sent back (NEEDS_REVISION), the edit IS the
    resubmission: flip it back to PENDING so it re-enters the review queue. An explicit status in
    the payload always wins, and other editors (admins tidying up, contributors filling gaps)
    never flip the status. Call after guard_record_edit, before the prisma update. Mutates and
    returns ``data``; a no-op for records without a status column."""
    from app.core.deps import get_value

    if "status" in data:
        return data
    current = get_value(record, "status")
    if str(getattr(current, "value", current)) != "NEEDS_REVISION":
        return data
    creator_id = get_value(record, "createdById")
    if creator_id is None or creator_id != get_value(user, "id"):
        return data
    data["status"] = "PENDING"
    return data


def review_update(status_value: str, notes: str | None, reviewer_id: str) -> dict[str, Any]:
    return {
        "status": status_value,
        "reviewNotes": notes,
        "reviewedById": reviewer_id,
        "reviewedAt": datetime.now(UTC),
    }


def relation_filter(field: str, value: str | None) -> dict[str, Any]:
    return {field: value} if value else {}


def media_relation_data(record_type: str | None, record_id: str | None) -> dict[str, Any]:
    if not record_type or not record_id:
        return {}
    normalized = record_type.lower()
    field_map = {
        "artisan": "artisanId",
        "craft": "craftId",
        "workshop": "workshopId",
        "product": "productId",
        "tool": "toolId",
        "questionnaire": "questionnaireInterviewId",
        "questionnaireinterview": "questionnaireInterviewId",
    }
    field = field_map.get(normalized)
    return {field: record_id} if field else {}
