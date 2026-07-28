from collections.abc import Sequence
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any, NamedTuple

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder
from prisma import Json

from app.core.db import db
from app.services.artisan_identity import mask_aadhaar
from app.services.concurrency import gather_reads
from app.services.text_format import title_case_fields

# Keys that must never leave the API, no matter how deeply nested inside an embedded relation
# (e.g. a media file's ``uploadedBy`` user, or a record's ``createdBy``).
_SENSITIVE_KEYS = {"passwordHash"}

# Regulated identity numbers. Unlike a password hash these are MASKED rather than dropped: a
# researcher still has to be able to confirm they are looking at the right person, and
# "XXXX XXXX 9012" is enough for that while not being a usable identifier.
#
# Both columns exist on Artisan and nowhere else, so an encoded dict carrying either key IS an
# artisan — which means it also carries its own ``createdById``, and the entitlement decision can be
# made per node without the walk knowing anything about the shape it is walking.
_IDENTITY_KEYS = ("aadhaarNumber", "pehchanCardNumber")

# THE FIELDS THAT HAND OVER BYTES. A ``MediaFile.url`` is not a description of a file, it IS the file:
# it is a fetchable object URL, stored on the row and set from ``s3.public_url_for_key``. Anyone
# holding it can save the photograph or the recording, with no further call into this API.
#
# WHY THIS EXISTS AT ALL. It did not need to while reading the repository was owner-scoped: whoever
# could see a media ROW could already download that uploader's data by definition, so the two were the
# same entitlement and nobody had to say so. Opening reads to every signed-in account (see the banner
# above ``viewable_where``) split them apart, and the repository's rule is that everybody may LOOK at
# every record while taking data out stays earned. Left alone, ``GET /media`` and ``GET /search`` would
# have become an index of direct download links to every file in the repository — which is precisely
# the half of the rule that is not open.
#
# So the ROW still travels for everybody: the filename, the type, the caption, the transcript, which
# record it belongs to, who uploaded it. Only the URL is withheld, and only from callers who may not
# take that uploader's data.
#
# ``objectKey`` IS IN THIS LIST, and it has to be. ``s3.public_url_for_key`` is deterministic — the URL
# is the CDN host plus the key, and the host is public knowledge — so handing over the key hands over
# the file just as surely as handing over the URL, one string concatenation later. Withholding one and
# not the other would be a lock on the door beside an open window. Nothing reads ``objectKey`` off a
# LISTED row (the upload flow gets its key from the presign response, which is the caller's own object
# by construction), so removing it from a read response costs no client anything.
_MEDIA_URL_KEYS = ("url", "publicUrl", "objectKey")

# How a node is recognised as a media file without the walk knowing what shape it is walking:
# ``objectKey`` exists on MediaFile and on no other model, and a media node also carries its own
# ``uploadedById``, so the entitlement decision can be made per node. Exactly the trick
# ``_IDENTITY_KEYS`` uses with ``createdById``.
_MEDIA_MARKER = "objectKey"

#: Passed as ``media_urls`` to mean "every URL may travel" — a professor, an admin, or a caller
#: holding the global dataset-download permission.
ALL_MEDIA_URLS = None


class _Unset:
    """A sentinel distinct from ``None``, because ``None`` is a MEANINGFUL value for ``media_urls``.

    ``media_urls=None`` means "allow every URL". So "the caller did not say" cannot also be ``None``,
    or the safe default and the most permissive setting would be spelled identically — and a route that
    simply forgot to think about it would get the widest answer.
    """

    def __repr__(self) -> str:  # pragma: no cover - debugging aid only
        return "<unset>"


_UNSET = _Unset()


def mask_identity_number(value: Any) -> Any:
    """The masked form of an artisan identity number.

    ``mask_aadhaar`` is reused verbatim for the Pehchan card: its rule is "keep the last four
    characters, X out everything before them, and mask anything shorter than four entirely", which is
    right for both numbers — and reusing it means one artisan's identity reads identically on every
    surface instead of gaining a second spelling.
    """
    return mask_aadhaar(value)


def _redact_sensitive(
    value: Any,
    viewer_id: str | None,
    unmasked: bool,
    media_urls: set[str] | None = None,
) -> Any:
    """Recursively scrub an already-encoded payload of everything that must not leave the API.

    Mutates in place and returns the same object. Three jobs:

    * password hashes are dropped outright, however deeply nested;
    * identity numbers are masked unless ``unmasked`` (professor and above) or the node's own
      ``createdById`` is the viewer — entitlement follows the ARTISAN, not the payload;
    * media URLs are dropped unless ``media_urls`` is ``None`` (all allowed) or contains the node's own
      ``uploadedById`` — entitlement follows the FILE'S UPLOADER, for the same reason.

    Both per-node tests read an owner column off the node being walked, which is what lets one pass
    over an arbitrary shape make a per-record decision without knowing what shape it is.
    """
    if isinstance(value, dict):
        for key in _SENSITIVE_KEYS:
            value.pop(key, None)
        if not unmasked and not (viewer_id and value.get("createdById") == viewer_id):
            for key in _IDENTITY_KEYS:
                if key in value:
                    value[key] = mask_identity_number(value[key])
        # The marker is READ before the keys are dropped, which matters because ``objectKey`` is both
        # the marker and one of the keys.
        if media_urls is not None and _MEDIA_MARKER in value:
            # Dropped rather than blanked. A key present and null reads as "this file has no URL",
            # which is a real state (an upload that never completed) and must stay distinguishable
            # from "you may not have it". An absent key is the honest third answer, and a client that
            # renders a play button only when a URL is present degrades correctly on its own.
            if value.get("uploadedById") not in media_urls:
                for key in _MEDIA_URL_KEYS:
                    value.pop(key, None)
        for nested in value.values():
            _redact_sensitive(nested, viewer_id, unmasked, media_urls)
    elif isinstance(value, list):
        for item in value:
            _redact_sensitive(item, viewer_id, unmasked, media_urls)
    return value


def public_encode(obj: Any, viewer: Any = None, *, media_urls: Any = _UNSET) -> Any:
    """``jsonable_encoder`` plus a recursive scrub of everything that must not leave the API.

    Three jobs, all performed on the ENCODED structure so none depends on how the row was loaded:

    * password hashes are removed outright, however deeply an embedded User relation is nested
      (``createdBy``/``uploadedBy``/``answeredBy``/``reviewedBy``);
    * ``aadhaarNumber`` and ``pehchanCardNumber`` are masked unless ``viewer`` is entitled to the raw
      value — professor and above, or the researcher who recorded that particular artisan;
    * ``url`` is removed from media nodes unless the caller may take that uploader's files — see
      :data:`_MEDIA_URL_KEYS` for why a URL is a download rather than a description.

    ``viewer`` DEFAULTS TO MASKED, and that default is the point. The mask used to be applied
    per-route inside artisans.py, so it held on the three artisan routes and nowhere else: every
    other response that embedded an Artisan — the questionnaire's interviews, products, tools,
    workshops, media, search — shipped full 12-digit Aadhaar numbers to anyone signed in, at a
    hundred artisans a page. Masking here, defaulting to the safe answer, is what makes the schema's
    "masked on every exported or shared surface" contract hold for includes nobody has written yet:
    a new route leaks nothing until someone deliberately passes a caller who may see more.

    ``media_urls`` follows the same defaulting discipline, and its default is deliberately the
    CHEAPEST SAFE answer rather than the most generous correct one:

    * omitted — derived from ``viewer`` with no database access: every URL for professor-and-above and
      for a holder of the global dataset-download permission, otherwise only the viewer's OWN uploads.
      A grantee therefore does not see URLs on a route that has not thought about it.
    * ``None`` (:data:`ALL_MEDIA_URLS`) — every URL. For an already-gated download surface.
    * a ``set`` of uploader ids — exactly those uploaders' URLs. Routes where a GRANTEE legitimately
      needs the file pass ``await media_url_owners(viewer)``, which adds the granted uploaders at the
      cost of one query.

    Pass the current user from any route whose caller legitimately needs the real number — the
    artisan edit form is the reason that path exists.
    """
    from app.core.deps import can_download_dataset, get_value, has_rank

    encoded = jsonable_encoder(obj)
    if viewer is None:
        # No viewer named: mask everything and withhold every URL. `set()` rather than None, because
        # None means "all allowed" and this is the path a route reaches by NOT thinking about it.
        allowed: set[str] | None = set() if media_urls is _UNSET else media_urls
        return _redact_sensitive(encoded, viewer_id=None, unmasked=False, media_urls=allowed)

    viewer_id = get_value(viewer, "id")
    if media_urls is _UNSET:
        if has_rank(viewer, "PROFESSOR") or can_download_dataset(viewer):
            allowed = ALL_MEDIA_URLS
        else:
            allowed = {viewer_id} if viewer_id else set()
    else:
        allowed = media_urls
    return _redact_sensitive(
        encoded,
        viewer_id=viewer_id,
        unmasked=has_rank(viewer, "PROFESSOR"),
        media_urls=allowed,
    )


async def media_url_owners(viewer: Any) -> set[str] | None:
    """Whose media URLs ``viewer`` may be handed: ``None`` for all, else a set of uploader ids.

    ``None`` for professor-and-above and for a holder of the global dataset-download permission, since
    both may already download the whole repository. Otherwise the viewer's own uploads plus every
    uploader who has GRANTED them a data-access grant — the same tiered grants
    ``services/access.owner_download_scope`` enforces on the export paths, so a grantee who may
    download a researcher's data can also play their recordings, and nobody else can.

    ONE query, and only for the ranks that need it. Pass the result to ``public_encode(media_urls=…)``
    from the routes where a grantee genuinely needs the file — the media list, search, and the
    consolidated questionnaire's audio. Everywhere else the cheap default is correct.

    The grant is read COARSELY, ignoring ``allData``/``scopeItems``: a subset grant names record ids,
    and a media file is not one of the record types a subset can name, so narrowing by it would
    withhold the audio for the very interview that was shared. Erring wide here matches
    ``visibility``'s own precedent for grant-gated reads and is still grant-gated.
    """
    from app.core.db import db
    from app.core.deps import can_download_dataset, get_value, has_rank

    if has_rank(viewer, "PROFESSOR") or can_download_dataset(viewer):
        return ALL_MEDIA_URLS
    viewer_id = get_value(viewer, "id")
    if not viewer_id:
        return set()
    grants = await db.dataaccessgrant.find_many(
        where={"granteeId": viewer_id, "status": "GRANTED"}
    )
    return {viewer_id, *(grant.ownerId for grant in grants)}


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
#   district     Location.district (written through ``attach_location``). Promoted from an
#                extraMetadata key to a real column by 20260727120000_location_stated_address, and
#                this entry is what that promotion was waiting for -- ``attach_location`` funnels
#                the location dict through ``clean_data`` too, so the column normalised from its
#                first write with no new plumbing. Idempotent for the same reason ``state`` is:
#                every canonical district name is already a fixed point of title_case, which
#                tests/test_address_districts.py asserts for all 795 of them
#   village      Location.village (written through ``attach_location``). Free text with no list
#                behind it, so this IS the only normalisation it gets -- "bagru" and "BAGRU" would
#                otherwise be two villages in every group-by
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


# Where the Android client puts the stated address, and the columns those keys became.
#
# The phone shipped this inside `location.extraMetadata` because at the time there were no columns
# to put it in — its own comment says so. Migration 20260727120000_location_stated_address then
# promoted all four to real columns and `require_location` began demanding `district`, which no
# Android build sends: not the one about to ship, and — the part that matters — not the one already
# installed on every phone in the field. Deploying the strict rule alone would 422 every create from
# every device until an APK reached it, and a device that is offline in a workshop cannot be reached.
#
# So the server accepts both shapes and normalises on the way in. This is not a temporary shim to be
# removed once the fleet updates: records created by today's phones will carry the metadata form for
# as long as they exist, and an edit of one of those rows re-sends what it was given.
_STATED_ADDRESS_FROM_META: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("district", ("district",)),
    ("village", ("village",)),
    ("subjectLatitude", ("subjectLatitude", "artisanLatitude")),
    ("subjectLongitude", ("subjectLongitude", "artisanLongitude")),
)


def lift_stated_address(location_data: dict[str, Any]) -> dict[str, Any]:
    """Copy a stated address out of ``extraMetadata`` into the columns that now hold it.

    A value already present as a column always wins — a client that sends both means the column.
    The metadata keys are left in place rather than popped: they are what older builds read back,
    and removing them would blank the field on a phone that has not updated yet.
    """
    meta = location_data.get("extraMetadata")
    if not isinstance(meta, dict):
        return location_data
    for column, keys in _STATED_ADDRESS_FROM_META:
        if location_data.get(column) not in (None, ""):
            continue
        for key in keys:
            value = meta.get(key)
            if value not in (None, ""):
                location_data[column] = value
                break
    return location_data


async def attach_location(data: dict[str, Any]) -> dict[str, Any]:
    location = data.pop("location", None)
    if location:
        location_data = location.model_dump() if hasattr(location, "model_dump") else dict(location)
        location_data = lift_stated_address(location_data)
        # `extraMetadata` is a Prisma Json column and prisma-client-py rejects a raw dict, so any
        # request carrying one was a 500 rather than a save — which is every Android create, since
        # that is where the phone keeps the stated address.
        created = await db.location.create(data=jsonify_metadata(clean_data(location_data)))
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


# =================================================================================================
# WHO MAY SEE WHAT — two different questions, two different filters
#
# READING the repository and TAKING data out of it are not the same act, and until now one filter
# answered both. That filter said "below Professor you see only your own rows plus rows whose owner
# granted you access", and because every list route used it, it governed READING too. The result was
# a repository that hid itself from the people filling it: a researcher's dashboard counted only
# their own uploads, /search returned only their own uploads, the map drew only their own uploads,
# and an account that had uploaded nothing yet opened an app that appeared to contain nothing at all.
# For a shared documentation corpus that is precisely backwards — the whole point of pooling the
# fieldwork is that everyone can see the pool.
#
# So the two questions are now asked separately:
#
#   viewable_where            — MAY THIS ACCOUNT LOOK AT THIS ROW?  Yes, for every signed-in
#                               account. Reading is open across the repository.
#   owned_or_granted_where    — MAY THIS ACCOUNT TAKE THIS ROW AWAY? Only their own rows, plus rows
#                               whose owner granted them access. Unchanged, and still what every
#                               download / export / bulk-manifest path uses.
#
# Nothing about DOWNLOADING, COMMENTING or EDITING moved. Those are decided by
# ``services/access.py`` (the tiered grants), ``core/deps.py`` (the rank ladder) and the two
# ``owned_or_granted_where`` surfaces below, exactly as before. And nothing about PII moved either:
# ``public_encode`` masks Aadhaar and Pehchan numbers for anybody who is not the artisan's own
# recorder or a Professor+, and it masks by DEFAULT — so widening who may read a row does not widen
# who may read the regulated numbers on it.
#
# The old name ``visibility_where`` is deliberately GONE rather than redefined. It had seventeen call
# sites and the two policies now differ, so a call site that kept compiling against the old name
# would have silently picked whichever policy the rename happened to land on. Removing it forced
# every one of them to be visited and classified.
# =================================================================================================


async def viewable_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    """Row filter for READING the repository: everything, for every signed-in account.

    Authentication is the gate — ``Depends(get_current_user)`` on the route — and past it there is no
    per-row narrowing, so this returns an empty ``where``. ``owner_field`` is accepted and ignored so
    that the read sites and the download sites are spelled the same way and can be told apart by
    NAME rather than by argument.

    WHY IT IS STILL A FUNCTION, AND STILL AWAITED. It is the one hook where the read policy lives. A
    future rule — "records pending review are hidden from volunteers", say — belongs here, applied to
    every list route at once, rather than being re-derived in twenty of them. Keeping the call sites
    is what makes that a one-line change instead of an audit. It stays ``async`` because every caller
    already awaits it, several inside ``gather_reads`` alongside real reads.

    It must still be AND-composed the way it always was (nest it under ``where["AND"]``): it is empty
    today, and a route that stopped composing it correctly would break the day it stops being empty.
    """
    return {}


async def owned_or_granted_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    """Row filter for TAKING DATA OUT — downloads, exports, and bulk manifests.

    Professor and above (and admins) may take every row — an empty filter. Below professor the answer
    is the rows they own, plus rows owned by anyone who has GRANTED them a data-access grant (any
    tier, subset grants included — coarse, but always grant-gated). ``owner_field`` names the row's
    owner column (``createdById`` for records, ``uploadedById`` for media). It must be AND-composed
    with any other ``OR`` the query builds (nest it under ``where["AND"]``) so a search ``OR`` never
    overwrites it.

    This is the ORIGINAL ``visibility_where`` body, unchanged, because the download policy is
    unchanged. Only its reach shrank: it now rides the export and /data queries and nothing else.

    THE GRANT TEST IS PART OF THE PAGE QUERY, not a query of its own. Reading the grant table first
    and folding the owner ids into an ``IN`` list cost a full round trip BEFORE the page could even
    be asked for. It also got worse with success: the ``IN`` list is every owner who has ever granted
    the caller anything, shipped as query parameters on every request. Expressed as a relation
    filter, Postgres answers the same question inside the query it was already running, against the
    ``granteeId`` index that exists for exactly this.
    """
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return {}
    uid = get_value(user, "id")
    # ``createdById`` -> ``createdBy``, ``uploadedById`` -> ``uploadedBy``: the owner COLUMN and the
    # owner RELATION are named that way on every model this filter is applied to.
    owner_relation = owner_field[:-2] if owner_field.endswith("Id") else owner_field
    return {
        "OR": [
            {owner_field: uid},
            {
                owner_relation: {
                    "is": {"dataAccessAsOwner": {"some": {"granteeId": uid, "status": "GRANTED"}}}
                }
            },
        ]
    }


async def own_rows_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    """Row filter for "MINE" — strictly the rows this account owns, whatever its rank.

    Distinct from both filters above and needed by neither of their call sites: it is what a
    "you have contributed N records" figure means. That figure used to fall out of the old
    ``visibility_where`` by accident, because below Professor the read filter WAS an ownership filter
    — which is also why the same figure was wrong for a professor, who saw the repository total
    labelled as their own work. Now that reading is open, "mine" has to be asked for explicitly, and
    this is where it is asked.
    """
    from app.core.deps import get_value

    return {owner_field: get_value(user, "id")}


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


# --- Loading a page's relations without paying for them one at a time ----------------------------
#
# THE PROBLEM THIS SOLVES, measured rather than guessed. Prisma's query engine already batches a
# relation by ROW — ``include={"craft": True}`` on twenty artisans issues one
# ``SELECT … FROM "Craft" WHERE id IN ($1,…)``, not twenty selects — so there is no classic N+1 here.
# What it does NOT do is issue those relation queries CONCURRENTLY: each ``include`` costs its own
# round trip, one after the next, and the page waits for all of them in series.
#
# That is free on a database next door and ruinous on this deployment, where PostgreSQL lives in a
# different AWS region from the web box: ``/health/ready`` measures a single ``SELECT 1`` at 683ms.
# Counted through a proxy in front of a local database, ``GET /tools?pageSize=20`` issued SEVEN
# sequential round trips (count, the page, then Craft, Location, MediaFile, User, ToolArtisan) — and
# in production it answered in 4.8 seconds for twenty rows out of seventy-four. The number of ROWS
# never entered into it; the number of RELATIONS did.
#
# So relations are loaded here instead: one batched query per relation, all issued together, and the
# results grafted back onto the rows. A page then costs a fixed THREE waits — the page and its count
# together, then every relation at once — no matter how many relations are declared or how many rows
# come back. The same shape holds at a hundred times the data, and it needs no cache, no broker and
# no second process to do it, so the 1 GiB box gets the same win as a large one.
#
# The rows come back as ordinary Prisma model instances with their relation attributes populated,
# exactly as ``include`` would have left them, so ``public_encode`` and every caller downstream are
# untouched — including the per-artisan Aadhaar decision, which still sees one node per artisan.


class Relation(NamedTuple):
    """One relation to load alongside a page of rows.

    ``field`` is the attribute set on each row (and therefore the key in the JSON response).
    ``model`` names the Prisma delegate on ``db`` — ``"craft"``, ``"mediafile"``, ``"user"``.

    ``key`` means different things by direction, because the foreign key lives on a different side:

    * to-one (``many=False``): the column ON THE PARENT holding the child's id (``"craftId"``);
    * to-many (``many=True``): the column ON THE CHILD holding the parent's id (``"toolId"``).

    ``include`` is passed straight through for a nested level (a tool's ``artisanLinks`` reaching its
    ``artisan``). Nesting still costs one round trip per level, but those levels run INSIDE the
    parallel wave rather than after everything else in it.
    """

    field: str
    model: str
    key: str
    many: bool = False
    include: dict[str, Any] | None = None


def include_of(relations: Sequence[Relation]) -> dict[str, Any]:
    """The equivalent Prisma ``include`` argument for the same relations.

    Write paths still hand ``include=`` to ``create``/``update``, where one extra round trip is
    noise next to the write itself and a single statement is the safer thing. Deriving that argument
    from the same tuple the read paths use is what stops the two descriptions of "what a tool looks
    like on the wire" from drifting apart — a relation added for the list would otherwise quietly go
    missing from the response to the PATCH that created it.
    """
    return {
        rel.field: ({"include": rel.include} if rel.include else True) for rel in relations
    }


async def hydrate_relations(rows: Sequence[Any], relations: Sequence[Relation]) -> None:
    """Populate ``relations`` on ``rows`` in one parallel wave of batched queries. Mutates the rows.

    Every row ends up with every declared attribute set — ``None`` for an unmatched to-one, ``[]``
    for an empty to-many — which is what ``include`` produces, so an absent relation stays
    distinguishable from a relation that is genuinely empty.
    """
    if not rows or not relations:
        return

    planned: list[tuple[Relation, Any]] = []
    for rel in relations:
        if rel.many:
            ids = {row.id for row in rows}
        else:
            ids = {getattr(row, rel.key, None) for row in rows} - {None}
        if not ids:
            # Nothing to look up. Prisma skips the query in this case too; setting the attributes
            # here keeps the row shape identical to the include it replaces.
            for row in rows:
                setattr(row, rel.field, [] if rel.many else None)
            continue
        args: dict[str, Any] = {"where": {(rel.key if rel.many else "id"): {"in": sorted(ids)}}}
        if rel.include:
            args["include"] = rel.include
        planned.append((rel, getattr(db, rel.model).find_many(**args)))

    if not planned:
        return
    fetched = await gather_reads(*(coro for _, coro in planned))

    for (rel, _), children in zip(planned, fetched):
        if rel.many:
            grouped: dict[Any, list[Any]] = {}
            for child in children:
                grouped.setdefault(getattr(child, rel.key, None), []).append(child)
            for row in rows:
                setattr(row, rel.field, grouped.get(row.id, []))
        else:
            by_id = {child.id: child for child in children}
            for row in rows:
                setattr(row, rel.field, by_id.get(getattr(row, rel.key, None)))


async def count_and_page(
    delegate: Any,
    *,
    where: dict[str, Any],
    skip: int,
    take: int,
    order: Any,
    relations: Sequence[Relation] = (),
) -> tuple[int, list[Any]]:
    """The ``(total, items)`` a paged list route needs, in two waits instead of two-plus-N.

    The count and the page do not depend on each other, so they go together; the relations depend
    only on which rows came back, so they go together after. Callers unpack the pair exactly as they
    would have read it in sequence.
    """
    total, items = await gather_reads(
        delegate.count(where=where),
        delegate.find_many(where=where, skip=skip, take=take, order=order),
    )
    await hydrate_relations(items, relations)
    return total, items


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
