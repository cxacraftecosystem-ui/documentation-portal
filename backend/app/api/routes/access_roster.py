"""The access roster: the admin screen that decides who may sign in, and the pending queue.

WHAT THIS IS FOR. Until the gate shipped, this application had no answer to "who is allowed in" —
the answer was "anybody with a Google account". These endpoints are that answer, and the PENDING
half of them is also the notification: there is no email and no push anywhere in this codebase, so
"the admins and master admins should get a notification to approve or reject the user" is served by
a count admins can put on any surface they already open (``GET /access-roster/pending-count``) plus
the queue itself (``GET /access-roster?status=PENDING``).

**DELETE IS A SUSPENSION.** It sets ``status = SUSPENDED``; it never removes the row. The roster is
the RECORD that an address was recognised, and that record outlives the access: an audit two years
from now asks who was admitted and when, and a deleted row answers "nobody". It also matters
operationally — a deleted row goes straight back into the pending queue the next time that person
signs in, which is precisely the loop the REJECTED and SUSPENDED statuses exist to break.

**ROUTE ORDER IS LOAD-BEARING.** ``/pending-count`` is declared before ``/{roster_id}``. FastAPI
matches in declaration order, so the other way round the literal path would be swallowed by the
parameterised one and the count endpoint would spend forever looking for a roster entry whose id is
the string "pending-count".

**WHO MAY USE THESE.** ``require_admin`` — ADMIN and MASTER_ADMIN, matching the requirement's "the
admins and master admins". Professors and below cannot see the queue: it contains addresses of
people who tried to get in, which is not information the tiers below administration have any reason
to hold.
"""

import asyncio
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import invalidate_cached_user, require_admin
from app.schemas.access_roster import AccessRosterCreate, AccessRosterUpdate
from app.services.access_roster import (
    ADMITTING_STATUS,
    DECIDABLE_STATUSES,
    clean_text,
    ensure_admitted,
    is_master_admin_email,
    normalise_email,
    pending_count,
    roster_payload,
    status_value,
)
from app.services.access_roster import MAX_FULL_NAME_LENGTH, MAX_NOTES_LENGTH
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains

# assert_role is imported from the users router rather than re-implemented, and that is deliberate.
# "Which tiers may this administrator hand out" is one rule — an admin may grant at or below their
# own tier, and only the master admin may mint MASTER_ADMIN — and it is enforced on POST /users
# already. A second copy here would agree today and drift the first time somebody changes one of
# them, and the way that drift surfaces is an admin promoting somebody above themselves through
# whichever screen was forgotten.
from app.api.routes.users import assert_role

router = APIRouter(prefix="/access-roster", tags=["access-roster"])

_NOT_FOUND = "Record not found"

# The master admin's row cannot be taken off the list. The gate exempts that address anyway
# (``auth.is_gate_exempt``), so a non-ACTIVE row would not actually lock them out — but it would
# display, on the admin screen, as though the institution's break-glass account were suspended, and
# the next administrator to read it would either believe it or "fix" it. Refusing the write keeps
# the screen honest about a fact the code has already decided.
_MASTER_ADMIN_PROTECTED = (
    "The master admin address cannot be removed from the access roster. It is the break-glass "
    "account and the sign-in gate never applies to it."
)


async def _accounts_for(rows: list[Any]) -> dict[str, Any]:
    """The ``User`` rows matching a page of roster entries, in ONE query, keyed by lower-cased email.

    A per-row lookup here would be N round trips to a cross-region database for a screen an admin
    opens to scan fifty entries at once. The join is on email because that is what the roster is
    keyed by — see the ``AccessRoster`` model for why it is not keyed by user id.
    """
    emails = [row.email for row in rows if row.email]
    if not emails:
        return {}
    accounts = await db.user.find_many(where={"email": {"in": emails}})
    return {str(account.email).lower(): account for account in accounts}


# ---------------------------------------------------------------------------------------------
# The notification
# ---------------------------------------------------------------------------------------------


@router.get("/pending-count")
async def pending_requests_count(_: Any = Depends(require_admin)) -> dict[str, int]:
    """How many people are waiting on an administrator. THE NOTIFICATION, in its entirety.

    Deliberately a bare count and not a list: this is meant to be polled by every admin surface —
    a badge on the dashboard, a dot on the Android home screen — and it is answered from
    ``AccessRoster_status_idx`` without reading a row. A "give me the queue as well" convenience
    payload here would make the cheap thing expensive and the badge would quietly become the most
    costly query on the dashboard.
    """
    return {"pending": await pending_count()}


# ---------------------------------------------------------------------------------------------
# The roster
# ---------------------------------------------------------------------------------------------


@router.get("")
async def list_roster(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    status_filter: str | None = Query(default=None, alias="status"),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """The roster, every status included by default.

    REFUSED ROWS ARE SHOWN UNLESS FILTERED OUT, and that default is the point of the screen: an
    administrator looking for somebody who says they cannot sign in needs to SEE the row that is
    refusing them. A list that hid it would leave them re-adding an address the unique index then
    rejects with a 409, and nothing in the UI would ever explain why.

    Ordered by ``lastRequestedAt`` descending so the queue reads as a queue — the person who asked
    most recently is at the top — and so the composite index carries the common
    ``?status=PENDING`` view.
    """
    where: dict[str, Any] = {}
    if status_filter:
        wanted = status_value(status_filter).upper()
        if wanted not in DECIDABLE_STATUSES | {"PENDING"}:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Unknown access status filter",
            )
        where["status"] = wanted
    if search:
        where["OR"] = [{"email": contains(search)}, {"fullName": contains(search)}]

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await asyncio.gather(
        db.accessroster.count(where=where),
        db.accessroster.find_many(
            where=where, skip=skip, take=clean_size, order={"lastRequestedAt": "desc"}
        ),
    )
    accounts = await _accounts_for(rows)
    items = [roster_payload(row, account=accounts.get(row.email)) for row in rows]
    return page_payload(items, total, clean_page, clean_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def add_to_roster(
    payload: AccessRosterCreate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Put an address on the allow list. No account is required and none is created.

    THIS IS HOW AN ADMIN ADMITS SOMEBODY WHO HAS NEVER OPENED THE APPLICATION — the reason the
    roster is keyed by email rather than by user id. The row waits; the account provisions itself,
    at ``grantedRole``, the first time that address signs in with Google.

    A duplicate is a 409 NAMING THE EXISTING ROW rather than a silent overwrite. The usual way to
    arrive here is an admin re-adding somebody they suspended in March, and overwriting would erase
    the note recording why — the one thing the row exists to preserve. The answer says where the row
    is and that restoring it is a PATCH.
    """
    email = normalise_email(payload.email)
    if not email:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="An access roster entry needs an email address",
        )
    assert_role(payload.grantedRole, current_user)

    existing = await db.accessroster.find_unique(where={"email": email})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{email} is already on the access roster with status "
                f"{status_value(existing.status)}. Update entry {existing.id} instead of adding it "
                f"again."
            ),
        )

    if payload.isActive:
        row = await ensure_admitted(
            email,
            granted_role=payload.grantedRole,
            full_name=payload.fullName,
            notes=payload.notes,
            decided_by_id=current_user.id,
        )
    else:
        now = datetime.now(UTC)
        row = await db.accessroster.create(
            data={
                "email": email,
                "status": "PENDING",
                "grantedRole": payload.grantedRole or "CROWDSOURCE_VOLUNTEER",
                "fullName": clean_text(payload.fullName, MAX_FULL_NAME_LENGTH),
                "notes": clean_text(payload.notes, MAX_NOTES_LENGTH),
                # Zero: nobody asked to be here, an administrator entered them.
                "requestCount": 0,
                "firstRequestedAt": now,
                "lastRequestedAt": now,
            }
        )
    return roster_payload(row)


@router.patch("/{roster_id}")
async def update_roster_entry(
    roster_id: str,
    payload: AccessRosterUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Approve, reject, suspend, restore, or correct one entry. THE ADMIN'S DECISION.

    ``status: "ACTIVE"`` is the approval, and it goes through ``ensure_admitted`` so that the
    ``joinedAt`` rule lives in exactly one place: the date of joining is stamped the FIRST time an
    address is admitted and never moved afterwards, so somebody suspended in June and restored in
    September still shows the March date they actually joined.

    ``status: "PENDING"`` is refused. See ``access_roster.DECIDABLE_STATUSES``: putting a request
    back in the queue is not an action anybody asked for, and on the queue it would be
    indistinguishable from a fresh request by the applicant — one admin could quietly undo another's
    rejection and nothing on the screen would say so.

    CHANGING THE STATUS DROPS THE ACCOUNT'S CACHED IDENTITY. Suspending somebody has to end their
    session, and ``get_current_user`` serves a user row out of an in-process cache for a few seconds
    — see the long note in ``deps.py``. Without the invalidation, a suspended person keeps working
    for the length of the TTL after the admin has clicked the button and watched the row change.
    """
    row = await db.accessroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_NOT_FOUND)

    values = payload.model_dump(exclude_unset=True)
    new_status = status_value(values.get("status")).upper() if values.get("status") else None
    if new_status and new_status not in DECIDABLE_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "An access roster entry can be set to ACTIVE, REJECTED or SUSPENDED. "
                "PENDING is written only by a refused sign-in."
            ),
        )
    if new_status and new_status != ADMITTING_STATUS and is_master_admin_email(row.email):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=_MASTER_ADMIN_PROTECTED)
    if "grantedRole" in values:
        assert_role(values.get("grantedRole"), current_user)

    # Approval is the one branch with bookkeeping attached, so it goes through the shared helper and
    # the plain-column edits ride along with it.
    if new_status == ADMITTING_STATUS:
        updated = await ensure_admitted(
            row.email,
            granted_role=values.get("grantedRole"),
            full_name=values.get("fullName") if "fullName" in values else None,
            notes=values.get("notes") if "notes" in values else None,
            decided_by_id=current_user.id,
        )
        await _invalidate_account(row.email)
        return roster_payload(updated)

    data: dict[str, Any] = {}
    if "email" in values and values["email"]:
        email = normalise_email(values["email"])
        if email and email != row.email:
            clash = await db.accessroster.find_unique(where={"email": email})
            if clash is not None:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"{email} is already on the access roster as entry {clash.id}.",
                )
            data["email"] = email
    if "fullName" in values:
        data["fullName"] = clean_text(values["fullName"], MAX_FULL_NAME_LENGTH)
    if "notes" in values:
        data["notes"] = clean_text(values["notes"], MAX_NOTES_LENGTH)
    if "grantedRole" in values and values["grantedRole"]:
        data["grantedRole"] = values["grantedRole"]
    if new_status:
        data["status"] = new_status
        data["decidedAt"] = datetime.now(UTC)
        data["decidedById"] = current_user.id
    if not data:
        return roster_payload(row)

    updated = await db.accessroster.update(where={"id": roster_id}, data=data)
    if new_status:
        await _invalidate_account(row.email)
    return roster_payload(updated)


@router.delete("/{roster_id}")
async def suspend_roster_entry(
    roster_id: str, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """SUSPEND, never delete. See the module docstring.

    Idempotent: suspending an already-suspended entry keeps the ORIGINAL ``decidedAt``, because that
    date is the answer to "when did this person lose access", and a second click on the button would
    otherwise move it to today and destroy it.

    Answers 200 with the suspended row rather than 204 with nothing, because unlike a real delete
    there is still something to show — and the client needs the new status to re-render the row it
    just acted on.
    """
    row = await db.accessroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_NOT_FOUND)
    if is_master_admin_email(row.email):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=_MASTER_ADMIN_PROTECTED)
    if status_value(row.status) == "SUSPENDED":
        return roster_payload(row)

    updated = await db.accessroster.update(
        where={"id": roster_id},
        data={
            "status": "SUSPENDED",
            "decidedAt": row.decidedAt or datetime.now(UTC),
            "decidedById": current_user.id,
        },
    )
    await _invalidate_account(row.email)
    return roster_payload(updated)


async def _invalidate_account(email: str) -> None:
    """Drop the cached identity for an address whose access just changed. See ``deps.py``.

    Silent when no account exists — pre-admitting or rejecting an address with no ``User`` row is
    the normal case, and there is nothing cached to drop.
    """
    account = await db.user.find_unique(where={"email": email})
    if account is not None:
        invalidate_cached_user(account.id)
