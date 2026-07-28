"""Workshop CRUD plus the whole workshop-access lifecycle.

Access to a workshop is a two-sided conversation held in ONE WorkshopAssignment row per (workshop,
user): an admin grants and revokes; a user requests and is approved or denied. The enforcement that
reads those rows lives in ``app.services.workshop_access`` — including the rule that decides whether
a workshop is curated at all, which is subtler than "does it have rows" and is documented there.

Endpoints come in two families:

* ``/workshops/{id}/assignments*`` — admin-side roster management for ONE workshop.
* ``/workshops/access-requests*`` — the user-side request flow plus the admin's cross-workshop
  approval queue, so an approver works from one list instead of opening every workshop in turn.

Nothing here DELETEs an assignment. A refusal or a withdrawal of access is history worth keeping, so
rows move to DENIED/REVOKED and stay put; see :func:`revoke_workshop_assignment`.
"""
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    get_value,
    require_admin,
    require_workshop_manager,
)
from app.schemas.access import (
    WORKSHOP_REQUEST_MAX,
    WorkshopAccessDecisionIn,
    WorkshopAccessRequestIn,
    WorkshopAssignmentIn,
    WorkshopAssignmentUpdateIn,
    WorkshopGrantIn,
)
from app.schemas.records import WorkshopCreate, WorkshopUpdate
from app.services.access import guard_record_edit, record_revision
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    Relation,
    public_encode,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
    contains,
    count_and_page,
    hydrate_relations,
    merge_field_provenance,
    require_record,
    resubmit_status,
    viewable_where,
)
from app.services.workshop_access import (
    DEFAULT_GRANT_LEVEL,
    WORKSHOP_DECISIONS,
    WORKSHOP_LEVEL_DESCRIPTIONS,
    WORKSHOP_LEVELS,
    access_denied_detail,
    describe_workshop_submission,
    enum_str,
    resolve_workshop_access,
    valid_level,
    workshop_is_curated,
)

router = APIRouter(prefix="/workshops", tags=["workshops"])

# What a workshop carries on the wire, loaded in one parallel wave (see services/records.py for why
# that is worth the indirection). Writes hydrate the row they just saved rather than passing an
# ``include`` to Prisma, so this is the single description of a workshop's relations.
RELATIONS = (
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("artisans", "workshopartisan", "workshopId", many=True, include={"artisan": True}),
    Relation("crafts", "workshopcraft", "workshopId", many=True, include={"craft": True}),
)

# Every party to an assignment row, so a UI can render "granted by X / requested by Y / decided by Z"
# without a second round of lookups.
ASSIGNMENT_INCLUDE: dict[str, Any] = {
    "user": True,
    "assignedBy": True,
    "requestedBy": True,
    "decidedBy": True,
}
# The cross-workshop views also need to name the workshop each row belongs to.
REQUEST_INCLUDE: dict[str, Any] = {**ASSIGNMENT_INCLUDE, "workshop": True}


def _level_or_422(value: Any, fallback: str | None) -> str | None:
    """Validate an incoming accessLevel against the ladder, falling back when the caller omits it."""
    if value is None:
        return fallback
    level = enum_str(value)
    if not valid_level(level):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"accessLevel must be one of {', '.join(WORKSHOP_LEVELS)}",
        )
    return level


def _status_or_422(value: Any, allowed: tuple[str, ...]) -> str:
    state = enum_str(value)
    if state not in allowed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"status must be one of {', '.join(allowed)}",
        )
    return str(state)


async def _assignment_or_404(workshop_id: str, user_id: str) -> Any:
    row = await db.workshopassignment.find_unique(
        where={"workshopId_userId": {"workshopId": workshop_id, "userId": user_id}}
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No assignment for that user on this workshop")
    return row


async def _assignment_list(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.workshopassignment.find_many(
        where={"workshopId": workshop_id}, include=ASSIGNMENT_INCLUDE, order={"createdAt": "asc"}
    )
    return public_encode(rows)


async def _hydrate_assignment(row_id: str) -> dict[str, Any]:
    row = await db.workshopassignment.find_unique(where={"id": row_id}, include=REQUEST_INCLUDE)
    return public_encode(row)


async def replace_workshop_artisans(workshop_id: str, artisan_ids: list[str]) -> None:
    """Rewrite the roster in two statements rather than one per artisan.

    A workshop with forty artisans cost forty-one sequential inserts, and on this deployment every
    one of them is a cross-region round trip — the save took longer than the form took to fill in.
    Duplicates are dropped here because the link table is unique on (workshopId, artisanId) and a
    bulk insert cannot skip a clash the way forty separate ones each could.
    """
    await db.workshopartisan.delete_many(where={"workshopId": workshop_id})
    unique_ids = list(dict.fromkeys(aid for aid in artisan_ids if aid))
    if unique_ids:
        await db.workshopartisan.create_many(
            data=[{"workshopId": workshop_id, "artisanId": aid} for aid in unique_ids]
        )


async def replace_workshop_crafts(workshop_id: str, craft_ids: list[str]) -> None:
    """The craft roster, rewritten in two statements rather than one per craft — see above."""
    await db.workshopcraft.delete_many(where={"workshopId": workshop_id})
    unique_ids = list(dict.fromkeys(cid for cid in craft_ids if cid))
    if unique_ids:
        await db.workshopcraft.create_many(
            data=[{"workshopId": workshop_id, "craftId": cid} for cid in unique_ids]
        )


def normalize_workshop_dates(data: dict[str, Any]) -> dict[str, Any]:
    if not data.get("date") and data.get("startDate"):
        data["date"] = data["startDate"]
    if not data.get("startDate") and data.get("date"):
        data["startDate"] = data["date"]
    if not data.get("endDate") and data.get("startDate"):
        data["endDate"] = data["startDate"]
    return data


@router.get("")
async def list_workshops(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    place: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    statusFilter: str | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # Visibility is AND-composed so the search OR (assigned below) can never overwrite it.
    vis = await viewable_where(current_user)
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [{"title": contains(search)}, {"place": contains(search)}, {"description": contains(search)}]
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = statusFilter
    if createdBy:
        where["createdById"] = createdBy
    # dateFrom/dateTo filter on startDate — the workshop's own timeline, matching the ordering below.
    add_date_range(where, "startDate", dateFrom, dateTo)
    # ORDERING IS DELIBERATE — by the date the workshop HAPPENED, most recent first, NOT by
    # createdAt. Do not flip it to ``createdAt desc`` for consistency with the other record lists.
    # This is the same ``startDate ?: date ?: createdAt`` occurrence key both clients re-sort by
    # after fetching (``sortWorkshopsByOccurrence`` in the web WorkshopSelect, ``workshopsByRecency``
    # on Android), and the first row is what every record form pre-selects. Ordering by createdAt
    # here makes that client-side sort a lie whenever the two disagree: a workshop HELD last week but
    # ENTERED months ago falls past ``pageSize`` (capped at 100), never reaches the client to be
    # re-sorted, and "the most recent workshop" silently defaults to the wrong one. It has to be
    # fixed HERE, at the source, not in the clients.
    # Prisma cannot express COALESCE in ``order``, so this is the multi-key equivalent, and it is
    # exact rather than approximate here: ``date`` is a required column and ``startDate`` is
    # backfilled from it (migration 20260616093000) and kept in lock-step by
    # ``normalize_workshop_dates`` on every create/update — so ``startDate`` decides every row and
    # the remaining keys only break ties, which also makes the order total for pagination.
    total, items = await count_and_page(
        db.workshop,
        where=where,
        skip=skip,
        take=page_size,
        order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}],
        relations=RELATIONS,
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop(
    payload: WorkshopCreate,
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    artisan_ids = data.pop("artisanIds", [])
    craft_ids = data.pop("craftIds", [])
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    created = await db.workshop.create(data=data)
    if artisan_ids:
        await replace_workshop_artisans(created.id, artisan_ids)
    if craft_ids:
        await replace_workshop_crafts(created.id, craft_ids)
    # The row we just wrote IS the response; only its links changed after the insert, and those are
    # loaded here. Reading the whole workshop back to learn what we already know cost another
    # cross-region round trip, plus one more per relation behind it.
    await hydrate_relations([created], RELATIONS)
    return public_encode(created)


# --------------------------------------------------------------------------- access requests
# These MUST stay declared above ``/{workshop_id}``: FastAPI matches in declaration order, and a
# literal path registered after the parameterised one would be swallowed as a workshop id.


@router.get("/access-levels")
async def list_access_levels(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    """The level ladder with human definitions, so a grant/request UI can say what it is handing out."""
    return [{"level": level, "description": WORKSHOP_LEVEL_DESCRIPTIONS[level]} for level in WORKSHOP_LEVELS]


@router.get("/requestable")
async def list_requestable_workshops(
    current_user: Any = Depends(get_current_user),
    limit: int = Query(WORKSHOP_REQUEST_MAX, ge=1, le=WORKSHOP_REQUEST_MAX),
) -> list[dict[str, Any]]:
    """Every workshop the caller could ASK about, with what they already hold on each.

    This exists because ``GET /workshops`` was the wrong list to build a request picker from, in a way
    that silently disabled the whole feature for the people it is for. That endpoint AND-composed the
    old row-visibility predicate, which below PROFESSOR narrowed the result to records the caller had
    CREATED (plus ones shared with them). A researcher or volunteer who had just joined had created
    nothing, so the picker rendered "No workshops to ask about" and there was no way to ask for access
    to anything — to exactly the workshops they could not see, which is the only reason to ask in the
    first place.

    Reading is open now (``records.viewable_where``), so ``GET /workshops`` would no longer strand
    anybody. This route stays, for the second reason it was built: it carries the caller's own standing
    on each workshop, which the plain list does not, and it is capped and hand-projected. Keeping it is
    also what stops the picker regressing if a read rule is ever introduced.

    The projection is deliberately narrow. Only the identifying fields — title, place, and the dates —
    cross the wire. No description, no notes, no artisans, no crafts, no records, no creator. If you
    add a field to this projection, ask first whether somebody with zero access to the workshop may
    read it.

    Each row also carries the caller's OWN standing, so the picker can be honest rather than offering
    to re-request something already held:

    * ``accessStatus`` — the status of the caller's assignment row: GRANTED / PENDING / DENIED /
      REVOKED, or null when they have never asked and were never assigned.
    * ``accessLevel`` — the level on that same row (null when there is no row). Read it together with
      the status: on a DENIED row it is the level that was refused, not one that is held.
    * ``restricted`` — whether the workshop is curated at all (see ``services.workshop_access``). On
      an open workshop everybody already holds CONTRIBUTE, so a request is only worth filing to ask
      for EDIT; saying so stops new users queueing up for access they already have.

    Capped at ``WORKSHOP_REQUEST_MAX`` — the same limit ``POST /access-requests`` puts on one call —
    so everything offered here can be selected in one submission. Ordered by when the workshop
    HAPPENED, most recent first, matching ``list_workshops`` so the truncation drops the oldest.
    """
    uid = get_value(current_user, "id")
    workshops = await db.workshop.find_many(
        take=limit, order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}]
    )
    ids = [w.id for w in workshops]
    # One query for every candidate's assignment rows rather than one per workshop: the caller's own
    # row and the curation test both come out of the same set. Deliberately NOT narrowed to
    # ``status=GRANTED, assignedById != null`` in SQL, tempting as that is — that predicate IS the
    # "is this workshop curated" rule, and ``workshop_is_curated`` owns it. Restating it here would
    # be a second copy to keep in step, and the module docstring on that function spells out what
    # getting it wrong costs: people locked out of workshops that were always open to them.
    rows = await db.workshopassignment.find_many(where={"workshopId": {"in": ids}}) if ids else []
    by_workshop: dict[str, list[Any]] = {}
    for row in rows:
        by_workshop.setdefault(row.workshopId, []).append(row)
    items: list[dict[str, Any]] = []
    for workshop in workshops:
        workshop_rows = by_workshop.get(workshop.id, [])
        mine = next((r for r in workshop_rows if r.userId == uid), None)
        items.append(
            {
                "id": workshop.id,
                "title": workshop.title,
                "place": workshop.place,
                "date": workshop.date,
                "startDate": workshop.startDate,
                "endDate": workshop.endDate,
                "accessStatus": enum_str(mine.status) if mine is not None else None,
                "accessLevel": enum_str(mine.accessLevel) if mine is not None else None,
                "restricted": workshop_is_curated(workshop_rows),
            }
        )
    return public_encode(items)


@router.post("/access-requests", status_code=status.HTTP_201_CREATED)
async def request_workshop_access(
    payload: WorkshopAccessRequestIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Ask for access to one or more workshops in a single call.

    Multi-select because that is how the need arrives: a researcher joining a project wants the same
    access to a whole season of workshops, and filing them one at a time produces a queue nobody
    works through.

    Idempotent per workshop, because the "request access" button will be pressed twice:

    * ``ALREADY_GRANTED`` — access is already held, so the row is left completely alone. Re-requesting
      must never knock a working grant back to PENDING while an admin deliberates.
    * ``ALREADY_PENDING`` — a request is already in the queue; not duplicated, and the original
      timestamp and note are preserved so queue order reflects when they first asked.
    * ``RE_REQUESTED`` — a DENIED or REVOKED row may be asked for again, and that is a NEW request:
      the previous decision is cleared so a stale "denied by X" cannot be mistaken for this one's
      outcome. ``assignedById`` is cleared with it — the row's history as an admin grant ended when it
      was revoked, and leaving it set would let one approval silently re-close a workshop that had
      reopened (see ``workshop_access`` on what makes a workshop curated).
    * ``CREATED`` — a fresh PENDING row.

    A PENDING row confers nothing. It cannot lock other people out of an open workshop either.
    """
    uid = get_value(current_user, "id")
    level = _level_or_422(payload.accessLevel, DEFAULT_GRANT_LEVEL)
    # Preserve the caller's order but drop blanks/duplicates, so asking twice in one body is not two rows.
    wanted: list[str] = list(dict.fromkeys(wid for wid in payload.workshopIds if wid))
    if not wanted:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Select at least one workshop")
    found = {w.id for w in await db.workshop.find_many(where={"id": {"in": wanted}})}
    missing = [wid for wid in wanted if wid not in found]
    if missing:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Workshop(s) not found: {', '.join(missing)}"
        )
    # The whole multi-select is decided from ONE read of the caller's existing rows, then written as
    # at most one insert and one update. Asking for a season of twenty workshops previously cost two
    # cross-region round trips per workshop before the final read — the multi-select was slower than
    # filing them one at a time would have been.
    existing_rows = await db.workshopassignment.find_many(
        where={"workshopId": {"in": wanted}, "userId": uid}
    )
    by_workshop = {row.workshopId: row for row in existing_rows}

    outcomes: list[dict[str, str]] = []
    to_create: list[str] = []
    to_rerequest: list[str] = []
    for workshop_id in wanted:
        existing = by_workshop.get(workshop_id)
        if existing is None:
            to_create.append(workshop_id)
            outcomes.append({"workshopId": workshop_id, "outcome": "CREATED"})
            continue
        state = enum_str(existing.status)
        if state in {"GRANTED", "PENDING"}:
            outcomes.append(
                {"workshopId": workshop_id, "outcome": "ALREADY_GRANTED" if state == "GRANTED" else "ALREADY_PENDING"}
            )
            continue
        to_rerequest.append(existing.id)
        outcomes.append({"workshopId": workshop_id, "outcome": "RE_REQUESTED"})

    if to_create:
        await db.workshopassignment.create_many(
            data=[
                {
                    "workshopId": workshop_id,
                    "userId": uid,
                    "accessLevel": level,
                    "status": "PENDING",
                    "requestedById": uid,
                    "requestNote": payload.note,
                }
                for workshop_id in to_create
            ]
        )
    if to_rerequest:
        await db.workshopassignment.update_many(
            where={"id": {"in": to_rerequest}},
            data={
                "accessLevel": level,
                "status": "PENDING",
                "requestedById": uid,
                "requestNote": payload.note,
                "assignedById": None,
                "decidedById": None,
                "decidedAt": None,
                "decisionNote": None,
            },
        )
    # Re-read by (workshop, user) rather than by id: a bulk insert hands back no ids, and this is the
    # same set of rows the loop would have collected.
    rows = await db.workshopassignment.find_many(
        where={"workshopId": {"in": wanted}, "userId": uid}, include=REQUEST_INCLUDE
    )
    return {"outcomes": outcomes, "requests": public_encode(rows)}


@router.get("/access-requests/mine")
async def my_workshop_access(
    current_user: Any = Depends(get_current_user), statusFilter: str | None = None
) -> list[dict[str, Any]]:
    """Every workshop-access row belonging to the caller, across all workshops.

    Not just the pending ones: a user needs to see what they hold, what they are waiting on, and what
    was refused, in one place — that is the difference between "ask again" and "stop asking".
    """
    where: dict[str, Any] = {"userId": get_value(current_user, "id")}
    if statusFilter:
        where["status"] = _status_or_422(statusFilter, ("PENDING", "GRANTED", "DENIED", "REVOKED"))
    rows = await db.workshopassignment.find_many(
        where=where, include=REQUEST_INCLUDE, order={"updatedAt": "desc"}
    )
    return public_encode(rows)


@router.get("/access-requests")
async def list_workshop_access_requests(
    _: Any = Depends(require_admin), statusFilter: str = "PENDING"
) -> list[dict[str, Any]]:
    """The approval queue across ALL workshops (PENDING by default), oldest first.

    One place to work from. Opening each workshop's assignment screen in turn is how requests sit
    unanswered for a week, so the queue is cross-workshop and each row names its workshop.
    ``statusFilter=ALL`` widens it to the full history for auditing.
    """
    where: dict[str, Any] = {}
    if statusFilter and statusFilter.upper() != "ALL":
        where["status"] = _status_or_422(statusFilter, ("PENDING", "GRANTED", "DENIED", "REVOKED"))
    rows = await db.workshopassignment.find_many(
        where=where, include=REQUEST_INCLUDE, order={"createdAt": "asc"}
    )
    return public_encode(rows)


@router.post("/access-requests/{request_id}/decide")
async def decide_workshop_access_request(
    request_id: str, payload: WorkshopAccessDecisionIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Admin approves (GRANTED) or denies (DENIED) a pending request, optionally setting the level.

    Only a PENDING row can be decided here — changing an already-settled row is roster management, so
    it goes through ``PATCH /workshops/{id}/assignments/{userId}`` where the change is unambiguous.

    Approving deliberately does NOT set ``assignedById``: answering a request is not the same act as
    an admin choosing a roster, and treating it as one would turn the first approval on an open
    workshop into a silent lockout of everybody else. See ``services/workshop_access``.
    """
    decision = _status_or_422(payload.status, ("GRANTED", "DENIED"))
    row = await db.workshopassignment.find_unique(where={"id": request_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Access request not found")
    if enum_str(row.status) != "PENDING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only a PENDING request can be decided. Use the workshop's assignment endpoints to "
            "change an already-decided row.",
        )
    await db.workshopassignment.update(
        where={"id": row.id},
        data={
            "status": decision,
            "accessLevel": _level_or_422(payload.accessLevel, enum_str(row.accessLevel)),
            "decidedById": get_value(current_user, "id"),
            "decidedAt": datetime.now(UTC),
            "decisionNote": payload.note,
        },
    )
    return await _hydrate_assignment(row.id)


@router.get("/{workshop_id}")
async def get_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    await hydrate_relations([workshop], RELATIONS)
    return public_encode(workshop)


@router.patch("/{workshop_id}")
async def update_workshop(
    workshop_id: str,
    payload: WorkshopUpdate,
    # Professor and above, the same floor as creating one. A workshop is the container the whole
    # submission window and assignment ladder hang off — moving its dates changes who is late — so
    # it is not a record anyone may contribute a field to. The workshop-access check below is a
    # SECOND, orthogonal gate (scoping, not rank): a professor still needs CONTRIBUTE on a curated
    # workshop, exactly as before.
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    artisan_ids = data.pop("artisanIds", None)
    craft_ids = data.pop("craftIds", None)
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    # The workshop's own row is a record IN that workshop, so the workshop's access levels govern
    # editing it. Resolved once and used for both decisions below.
    access = await resolve_workshop_access(current_user, workshop_id)
    # VIEW (or no access at all on a curated workshop) cannot edit. The creator is exempt: an admin
    # curating a roster that leaves the author off must not lock the author out of their own entry.
    if get_value(workshop, "createdById") != get_value(current_user, "id") and not access.at_least("CONTRIBUTE"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=access_denied_detail(access, "CONTRIBUTE")
        )
    # An EDIT-level assignee is a co-owner of this workshop's data and may change fields somebody
    # else populated, exactly as the creator or an admin can. guard_record_edit knows nothing about
    # workshops, so the elevation is applied here — and the revision is still written on that path,
    # so the shortcut never costs the edit audit.
    privileged = access.at_least("EDIT")
    if privileged:
        await record_revision(workshop, current_user, data, "workshop")
    else:
        privileged = await guard_record_edit(workshop, current_user, data, "workshop")
    await apply_status_policy_update(current_user, workshop, data)
    merge_field_provenance(data, current_user, previous=workshop)
    resubmit_status(workshop, current_user, data)
    updated = await db.workshop.update(where={"id": workshop_id}, data=data)
    if artisan_ids is not None:
        link_count = await db.workshopartisan.count(where={"workshopId": workshop_id})
        if not privileged:
            assert_can_contribute_relation(workshop, current_user, link_count > 0, "artisanIds")
        await replace_workshop_artisans(workshop_id, artisan_ids)
    if craft_ids is not None:
        craft_link_count = await db.workshopcraft.count(where={"workshopId": workshop_id})
        if not privileged:
            assert_can_contribute_relation(workshop, current_user, craft_link_count > 0, "craftIds")
        await replace_workshop_crafts(workshop_id, craft_ids)
    # ``update`` already handed back the saved row, so the relations are grafted onto it instead of
    # reading the whole workshop a second time from another region.
    await hydrate_relations([updated], RELATIONS)
    return public_encode(updated)


@router.get("/{workshop_id}/submission-check")
async def workshop_submission_check(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """What submitting a record into this workshop would mean for the caller, BEFORE they submit.

    Lets a client confirm ("this workshop ended on <endDate> — your entry will need admin approval")
    instead of discovering it after the fact. ``canSubmit`` false means the workshop has assignments
    and the caller is not one of them, so a create would 403; ``needsAdminApproval`` true means the
    submission would be accepted but forced to PENDING until an admin approves it. Never 403s itself —
    it only reports.
    """
    check = await describe_workshop_submission(current_user, workshop_id)
    return check.payload()


@router.get("/{workshop_id}/assignments")
async def list_workshop_assignments(workshop_id: str, _: Any = Depends(require_admin)) -> list[dict[str, Any]]:
    """Every assignment row on this workshop — granted, pending, denied and revoked alike.

    Still a flat list, because that is the shape both clients already consume. But it is no longer
    "the assigned researchers": a row now carries a ``status``, and only ``GRANTED`` means access.
    A caller building the assigned set MUST filter on ``status === "GRANTED"``; taking every
    ``userId`` would treat a pending request or a revoked researcher as a member.

    Admin-only, because it exposes request notes and decisions. The non-privileged question ("may I
    submit here?") is answered by ``/workshops/{id}/submission-check``, which is open to everyone.
    """
    await require_record(db.workshop, workshop_id)
    return await _assignment_list(workshop_id)


@router.put("/{workshop_id}/assignments")
async def set_workshop_assignments(
    workshop_id: str, payload: WorkshopAssignmentIn, current_user: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    """Admin sets the exact set of researchers assigned to this workshop (replaces the previous set).

    Kept whole-set for backwards compatibility — the Android app and the web assignment dialog both
    send ``{"userIds": [...]}`` and expect the roster to become exactly that. What changed underneath:

    * everyone in the set gets ``status = GRANTED`` and an ``assignedById``, which is what marks this
      workshop as deliberately curated;
    * a level is honoured if the caller sends one, otherwise an existing row keeps the level it
      already had (so re-saving the dialog cannot silently demote an EDIT assignee to CONTRIBUTE) and
      a new row gets CONTRIBUTE — the level this endpoint has always effectively granted;
    * everyone dropped from the set is REVOKED rather than deleted, so "X had access until Y removed
      them on Z" survives. Sending an EMPTY set therefore revokes everybody, which — with no GRANTED
      admin row left — reopens the workshop to all, exactly as clearing the list always did.

    Deliberately carries NO date guard: an admin or master admin may grant someone access to a
    workshop at any time, including long after it ended — that is how post-workshop access is given.
    The newly assigned user can then submit, with any out-of-window entry flagged for admin approval
    (see ``/workshops/{id}/submission-check`` and ``services/workshop_access``).
    """
    await require_record(db.workshop, workshop_id)
    requested_level = _level_or_422(payload.accessLevel, None)
    wanted = {uid for uid in payload.userIds if uid}
    existing = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    by_user = {r.userId: r for r in existing}
    now = datetime.now(UTC)
    admin_id = get_value(current_user, "id")

    # The roster is rewritten in a fixed handful of statements, not one per researcher. Every row
    # being revoked takes the same values, and so does every row being created; the rows being
    # re-granted differ only in the level they end up at, so they are grouped by that level. A
    # thirty-person roster used to cost thirty-one sequential cross-region round trips to save.
    revoke_ids = [
        row.id
        for uid, row in by_user.items()
        if uid not in wanted and enum_str(row.status) != "REVOKED"
    ]
    create_rows = [uid for uid in wanted if uid not in by_user]
    grant_ids_by_level: dict[str, list[str]] = {}
    for uid in wanted:
        row = by_user.get(uid)
        if row is None:
            continue
        level = requested_level or enum_str(row.accessLevel) or DEFAULT_GRANT_LEVEL
        grant_ids_by_level.setdefault(level, []).append(row.id)

    if revoke_ids:
        await db.workshopassignment.update_many(
            where={"id": {"in": revoke_ids}},
            data={
                "status": "REVOKED",
                "decidedById": admin_id,
                "decidedAt": now,
                "decisionNote": "Removed from the workshop roster.",
            },
        )
    if create_rows:
        await db.workshopassignment.create_many(
            data=[
                {
                    "workshopId": workshop_id,
                    "userId": uid,
                    "assignedById": admin_id,
                    "accessLevel": requested_level or DEFAULT_GRANT_LEVEL,
                    "status": "GRANTED",
                }
                for uid in create_rows
            ]
        )
    for level, ids in grant_ids_by_level.items():
        await db.workshopassignment.update_many(
            where={"id": {"in": ids}},
            data={
                "assignedById": admin_id,
                "accessLevel": level,
                "status": "GRANTED",
                "decidedById": admin_id,
                "decidedAt": now,
            },
        )
    return await _assignment_list(workshop_id)


@router.post("/{workshop_id}/assignments", status_code=status.HTTP_201_CREATED)
async def grant_workshop_assignment(
    workshop_id: str, payload: WorkshopGrantIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Admin grants ONE user access at a level, without disturbing the rest of the roster.

    An upsert on the (workshop, user) pair — the row is unique — so re-granting somebody who was
    REVOKED or DENIED flips them straight back to GRANTED at the requested level rather than failing
    on the unique constraint or stacking a second row. The previous decision note is replaced by this
    grant's, because the row now describes the current state; the audit of who did what and when
    lives in the timestamps and the ``assignedBy``/``decidedBy`` links.
    """
    await require_record(db.workshop, workshop_id)
    user = await db.user.find_unique(where={"id": payload.userId})
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    admin_id = get_value(current_user, "id")
    now = datetime.now(UTC)
    existing = await db.workshopassignment.find_unique(
        where={"workshopId_userId": {"workshopId": workshop_id, "userId": payload.userId}}
    )
    level = _level_or_422(
        payload.accessLevel, enum_str(existing.accessLevel) if existing is not None else DEFAULT_GRANT_LEVEL
    )
    data: dict[str, Any] = {
        "accessLevel": level,
        "status": "GRANTED",
        "assignedById": admin_id,
        "decidedById": admin_id,
        "decidedAt": now,
        "decisionNote": payload.note,
    }
    if existing is None:
        created = await db.workshopassignment.create(
            data={**data, "workshopId": workshop_id, "userId": payload.userId}
        )
        return await _hydrate_assignment(created.id)
    await db.workshopassignment.update(where={"id": existing.id}, data=data)
    return await _hydrate_assignment(existing.id)


@router.patch("/{workshop_id}/assignments/{user_id}")
async def update_workshop_assignment(
    workshop_id: str, user_id: str, payload: WorkshopAssignmentUpdateIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Admin changes one assignment: raise/lower its level, and/or set GRANTED, DENIED or REVOKED.

    This is the per-workshop twin of ``/access-requests/{id}/decide`` and, unlike it, works on a row
    in ANY state — an admin looking at one workshop's roster should not have to care whether the row
    started as a grant or as a request.

    Like ``decide``, granting here records ``decidedById`` but does not touch ``assignedById``: if the
    row arrived as somebody's request, approving it from this screen is still an answer to that
    request, not the act of curating a roster. Use PUT or POST to actually put someone on the roster.
    """
    await require_record(db.workshop, workshop_id)
    row = await _assignment_or_404(workshop_id, user_id)
    data: dict[str, Any] = {}
    level = _level_or_422(payload.accessLevel, None)
    if level is not None:
        data["accessLevel"] = level
    if payload.status is not None:
        data["status"] = _status_or_422(payload.status, WORKSHOP_DECISIONS)
    if not data:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Send accessLevel, status, or both"
        )
    # Any admin touch is a decision worth stamping — including a bare level change, which is exactly
    # the edit somebody will later need to explain.
    data["decidedById"] = get_value(current_user, "id")
    data["decidedAt"] = datetime.now(UTC)
    if payload.note is not None:
        data["decisionNote"] = payload.note
    await db.workshopassignment.update(where={"id": row.id}, data=data)
    return await _hydrate_assignment(row.id)


@router.delete("/{workshop_id}/assignments/{user_id}")
async def revoke_workshop_assignment(
    workshop_id: str, user_id: str, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Revoke one user's access. The row is set to REVOKED, NOT deleted, and is returned.

    Deleting would lose the only evidence that access was ever held or withdrawn, and the row is the
    audit trail: who granted it, who asked for it, who took it away and when. It also matters
    operationally — a deleted row is indistinguishable from "never asked", so a user could
    re-request and quietly land back where they were with no sign a decision had been reversed.

    Returns the revoked row rather than a bare 204 so the caller can render the new state (and the
    decision stamp) without a follow-up fetch.
    """
    await require_record(db.workshop, workshop_id)
    row = await _assignment_or_404(workshop_id, user_id)
    await db.workshopassignment.update(
        where={"id": row.id},
        data={
            "status": "REVOKED",
            "decidedById": get_value(current_user, "id"),
            "decidedAt": datetime.now(UTC),
            "decisionNote": "Access revoked.",
        },
    )
    return await _hydrate_assignment(row.id)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.workshop, workshop_id)
    await db.workshop.delete(where={"id": workshop_id})
