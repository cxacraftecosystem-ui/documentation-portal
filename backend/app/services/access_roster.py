"""THE SIGN-IN GATE: who this institution lets in, and what a person who is not let in is told.

This application shipped for months with no sign-in refusal of any kind. Anyone holding a Google
account whose address Google would vouch for got a `User` row and a bearer token, automatically, at
``DEFAULT_SIGNUP_ROLE``. This module is the end of that, and every consequence in here is new.

FOUR THINGS THIS MODULE OWNS, AND THE ARGUMENT FOR EACH.

1. **``roster_admits`` — the one question the gate asks.** It tests ``status = ACTIVE`` in the WHERE
   clause, not the existence of a row, and that distinction is the whole of the security model. A
   PENDING row is created BY THE REFUSED CALLER; if "a row exists" admitted anybody, then the first
   refusal would mint the credential for the second attempt. Anyone refactoring this into
   ``find_unique(where={"email": ...}) is not None`` has written an authentication bypass that the
   attacker triggers on their own address, in one HTTP request, with no privileges at all.

2. **``record_access_request`` — the bounded, unauthenticated write.** See its docstring. In short:
   the caller must have PROVED they own the address before a row is written, one address is one row
   forever, the only attacker-controlled value stored is the address itself, and the table has a
   ceiling.

3. **The refusal sentences.** The product owner ruled explicitly that "awaiting approval" and "wrong
   email or password" must be DIFFERENT answers. That widens account enumeration on purpose — a
   stranger can learn that an address is known to this institution — and the trade was made
   deliberately, because the alternative is a person who is waiting on an administrator spending a
   week resetting a password that was never wrong. What the refusals must NOT do is leak anything
   past that single fact: no name, no role, no whether a password was ever set, no hint about any
   other account. Read ``refusal_detail`` before changing a word of them.

4. **MASTER_ADMIN IS NEVER GATED.** ``is_master_admin_email`` is consulted before the roster on
   every path. The roster is a table only an administrator can edit; an administrator locked out by
   it is an outage with no remedy inside the product, because there is nobody left who can add the
   row that lets anybody — including them — back in. The exemption is keyed on the CONFIGURED
   address rather than on ``User.role`` so that it works even when the account does not exist yet,
   which is precisely the situation a break-glass has to survive.

EVERY EMAIL THAT REACHES THIS MODULE IS LOWER-CASED BY :func:`normalise_email`, and by nothing else.
``AccessRoster.email`` is unique and is joined to ``User.email``; a roster row typed
``A.Sharma@Example.org`` that never matches the account signing in as ``a.sharma@example.org`` is a
person locked out of the application by a capital letter, with an administrator looking straight at
the row that was supposed to let them in.
"""

import logging
from datetime import UTC, datetime
from typing import Any

from app.core.config import get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

#: The only status that admits. Named so that reading the gate does not require remembering which of
#: the four enum values is the permissive one.
ADMITTING_STATUS = "ACTIVE"

#: Statuses an administrator may set from the roster screen. PENDING is absent on purpose: "put this
#: request back in the queue" is not an action anybody asked for, and it would let one admin quietly
#: undo another's rejection in a way that looks, on the queue, exactly like a fresh request from the
#: applicant. Restoring a rejected person is an approval — ACTIVE — which is an act with a name and
#: a ``decidedById`` against it.
DECIDABLE_STATUSES = frozenset({"ACTIVE", "REJECTED", "SUSPENDED"})

#: An email longer than this is not an email. RFC 5321 caps a forward path at 254 characters, and
#: the column is unbounded TEXT, so this is the thing standing between the pending queue and a row
#: whose primary content is a megabyte of an attacker's choosing. Over-length addresses are dropped
#: rather than truncated: a truncated address is a DIFFERENT address, and storing it would put a
#: row in the admin's queue for a person who does not exist.
MAX_EMAIL_LENGTH = 254

#: Caps on the two free-text columns an ADMINISTRATOR writes. Not a security boundary — an admin can
#: already write anything they like all over this application — but the roster is rendered in a
#: table on two clients, and a note the length of a dissertation makes the queue unreadable for
#: every other admin.
MAX_FULL_NAME_LENGTH = 200
MAX_NOTES_LENGTH = 2000


# ---------------------------------------------------------------------------------------------
# The refusals
# ---------------------------------------------------------------------------------------------
#
# THESE TWO SENTENCES ARE THE FEATURE. Everything else in this module exists to decide which one a
# person reads.
#
# The wrong-credential answer is UNCHANGED from what this application has always said, and it is
# still a 401. Do not "improve" it into "no account with that address exists" — the enumeration the
# product owner accepted is specifically about the ROSTER's opinion of an address, not about a free
# oracle for whether a password is right.
INVALID_CREDENTIALS_DETAIL = "Invalid email or password"

# THE PENDING SENTENCE. Three jobs, in this order: say the request exists (so the person stops
# retyping their password), say who is going to act on it (so they stop emailing the wrong desk),
# and say that nothing further is required of them (so they stop re-submitting, which is the
# behaviour that fills the admin's queue with noise).
#
# "SIGN-IN DETAILS", NOT "PASSWORD". The identical sentence is served to the Google branch, where
# there is no password at all — a refusal that told a Google user their password was fine would be
# saying something false about a thing they never typed, and the first support call it produces is
# somebody asking which password the app means.
ACCESS_PENDING_DETAIL = (
    "Your access request is awaiting approval by an administrator. "
    "Your sign-in details are correct; there is nothing more to do until an administrator approves "
    "this address."
)

# THE REJECTED SENTENCE. It does not say "an administrator rejected you", and that is a considered
# choice rather than squeamishness: the person cannot appeal to this screen, and the only thing that
# changes their situation is talking to a human. Saying "not approved" states the fact, and naming
# the administrator names the one action that leads anywhere. It is deliberately DISTINCT from the
# pending sentence, because telling a rejected person they are "awaiting approval" is a lie that
# guarantees they wait forever.
ACCESS_REJECTED_DETAIL = (
    "This address is not approved for access to the repository. "
    "Contact your administrator if you believe this is a mistake."
)

# THE SUSPENDED SENTENCE. Distinct again, because this person HAD access and it was ended — telling
# them they are "awaiting approval" would have them waiting for something that already happened, and
# telling them "not approved" hides that they are looking at a revocation they may be able to get
# reversed today.
ACCESS_SUSPENDED_DETAIL = (
    "Your access to the repository has been suspended. Contact your administrator."
)

#: Status -> the sentence that status is refused with. A status missing from here would fall back to
#: the rejected wording, which is the safe direction: it never invites somebody to wait.
_REFUSAL_DETAILS: dict[str, str] = {
    "PENDING": ACCESS_PENDING_DETAIL,
    "REJECTED": ACCESS_REJECTED_DETAIL,
    "SUSPENDED": ACCESS_SUSPENDED_DETAIL,
}


def refusal_detail(status: Any) -> str:
    """The sentence a person in *status* reads. Never returns the wrong-credential wording."""
    return _REFUSAL_DETAILS.get(status_value(status), ACCESS_REJECTED_DETAIL)


# ---------------------------------------------------------------------------------------------
# Normalisation
# ---------------------------------------------------------------------------------------------


def normalise_email(email: Any) -> str:
    """The one canonical form of an email address in this system. See the module docstring.

    Returns ``""`` for anything absent, blank or over-length; every caller treats the empty string
    as "no usable address", which refuses rather than admits.
    """
    address = str(email or "").strip().lower()
    if not address or len(address) > MAX_EMAIL_LENGTH:
        return ""
    return address


def status_value(status: Any) -> str:
    """A prisma enum member, or a plain string, as a plain string."""
    return str(getattr(status, "value", status) or "")


def role_value(role: Any) -> str:
    return str(getattr(role, "value", role) or "")


def is_master_admin_email(email: Any) -> bool:
    """Is this THE break-glass address? See point 4 of the module docstring.

    Keyed on the configured ``MASTER_ADMIN_EMAIL`` and not on ``User.role``, so it holds before the
    account exists, after somebody has deleted the row, and while the roster says otherwise.
    """
    address = normalise_email(email)
    if not address:
        return False
    return address == normalise_email(get_settings().master_admin_email)


def gate_enforced() -> bool:
    """Is the gate switched on? See ``Settings.access_roster_enforced`` for why the switch exists."""
    return bool(get_settings().access_roster_enforced)


# ---------------------------------------------------------------------------------------------
# The gate
# ---------------------------------------------------------------------------------------------


async def roster_row(email: Any) -> Any | None:
    """The roster row for an address, or ``None``. Does not decide anything; ``roster_admits`` does."""
    address = normalise_email(email)
    if not address:
        return None
    return await db.accessroster.find_unique(where={"email": address})


async def roster_admits(email: Any) -> bool:
    """Is there an ACTIVE roster row for this address?

    THE ``status`` TEST IS IN THE QUERY, NOT IN PYTHON, and it is not an optimisation. See point 1
    of the module docstring: a row exists for people who have been refused, so "found a row" and
    "the institution admits them" are one clause apart and conflating them is an authentication
    bypass an unauthenticated caller can arrange for themselves.
    """
    address = normalise_email(email)
    if not address:
        return False
    row = await db.accessroster.find_first(
        where={"email": address, "status": ADMITTING_STATUS}
    )
    return row is not None


async def mark_first_seen(email: Any) -> None:
    """Stamp ``firstSeenAt`` the first time an admitted address actually gets in.

    Written ONCE — the WHERE clause carries ``firstSeenAt: None`` — so the stamp records the first
    sign-in rather than the most recent one, and two simultaneous logins cannot race each other into
    overwriting it. Silent when there is no row (the master admin's exemption means they can sign in
    without one); an ``update_many`` that matches nothing is cheaper than deciding beforehand whether
    to ask, on a path a person takes once a week.
    """
    address = normalise_email(email)
    if not address:
        return
    await db.accessroster.update_many(
        where={"email": address, "firstSeenAt": None},
        data={"firstSeenAt": datetime.now(UTC)},
    )


async def record_access_request(email: Any) -> None:
    """Record that somebody who PROVED they own *email* was refused. The unauthenticated write.

    THE CALLER MUST HAVE PROVED OWNERSHIP OF THE ADDRESS BEFORE CALLING THIS. In ``auth.py`` that
    means one of exactly two things: a Google ID token this server verified, carrying
    ``email_verified``; or an existing local account whose password hash the supplied password
    matched. A bare "unknown address + any password" attempt writes NOTHING, because it proves
    nothing — that path is the one an anonymous script can drive at line rate, and letting it seed
    the administrators' queue would turn the notification this feature exists to provide into spam
    that hides the real requests. THAT ORDERING IS THE PRIMARY BOUND ON THIS WRITE. Moving this call
    earlier in the sign-in flow, to "capture more requests", removes it.

    THE OTHER BOUNDS, EACH OF WHICH IS LOAD-BEARING:

    * **One address is one row, forever.** A repeat attempt is an UPDATE of ``requestCount`` and
      ``lastRequestedAt``, never an INSERT. Somebody hammering the form produces one row with a
      rising counter, which is also exactly what an administrator needs to see.
    * **Nothing attacker-controlled is stored beyond the address.** No display name, no picture, no
      "reason for request" field — not because those would be useless to an admin, but because the
      pending queue is the only screen in this product where a stranger can cause content to appear,
      and the first thing that goes wrong with such a screen is that it renders somebody's chosen
      text to an administrator. ``fullName`` and ``notes`` are ADMIN-ONLY columns. Do not add a
      field here "just for context".
    * **The address itself is length-capped** by ``normalise_email`` before it reaches the database.
    * **The table has a ceiling** (``ACCESS_ROSTER_MAX_PENDING``). At the cap, a request for an
      address not already on the roster is DROPPED — the person is still refused, with the identical
      sentence, so nothing about the cap is observable to them; only the log and the admin's count
      say so. Dropping is the right failure: the alternative is an unbounded table written by
      anonymous callers, and an admin cannot work a queue of fifty thousand entries anyway.

    A REJECTED PERSON DOES NOT SILENTLY RE-QUEUE. Their re-attempt bumps the counter and the
    timestamp and LEAVES THE STATUS AT REJECTED. Three options were on the table — re-open, stay
    rejected, or rate-limit the re-ask — and re-opening is the one that makes the queue unworkable:
    a rejection an applicant can undo by clicking "sign in with Google" again is not a rejection, it
    is a timer, and the administrator gets the same request back every day forever with no way to
    make it stop. Staying rejected keeps the decision a decision; the rising ``requestCount`` is how
    an admin notices somebody who keeps trying and goes to talk to them. The same argument applies
    to SUSPENDED, which must obviously not be undone by the suspended person retrying. ACTIVE rows
    never reach here at all — they were admitted.
    """
    address = normalise_email(email)
    if not address:
        return

    now = datetime.now(UTC)
    existing = await db.accessroster.find_unique(where={"email": address})
    if existing is not None:
        # The status is deliberately NOT touched. See the last paragraph of the docstring.
        await db.accessroster.update(
            where={"email": address},
            data={
                "requestCount": (existing.requestCount or 0) + 1,
                "lastRequestedAt": now,
            },
        )
        return

    cap = max(0, int(get_settings().access_roster_max_pending))
    pending = await pending_count()
    if pending >= cap:
        logger.warning(
            "Access request from a new address dropped: the pending roster is at its ceiling "
            "(%s of %s). An administrator must clear the queue before new requests are recorded.",
            pending,
            cap,
        )
        return

    await db.accessroster.create(
        data={
            "email": address,
            "status": "PENDING",
            # grantedRole is left at the column default — the lowest rung. "All the users by default
            # join as the lowest rung unless promoted there itself": the promotion happens on the
            # roster screen, at approval time, and never here.
            "requestCount": 1,
            "firstRequestedAt": now,
            "lastRequestedAt": now,
        }
    )


async def pending_count() -> int:
    """How many addresses are waiting on an administrator. THE NOTIFICATION.

    There is no email and no push in this codebase — neither has ever existed in it — so the
    "notify the admins" half of the requirement is served by a number on surfaces administrators
    already open. This is that number, and it is a COUNT rather than a list so that the polling it
    invites stays cheap: ``AccessRoster_status_idx`` answers it from the index alone.
    """
    return await db.accessroster.count(where={"status": "PENDING"})


# ---------------------------------------------------------------------------------------------
# Admission bookkeeping
# ---------------------------------------------------------------------------------------------


async def ensure_admitted(
    email: Any,
    *,
    granted_role: str | None = None,
    full_name: str | None = None,
    notes: str | None = None,
    decided_by_id: str | None = None,
    joined_at: datetime | None = None,
) -> Any:
    """Put an address on the roster as ACTIVE, creating the row or promoting an existing one.

    THE ONE PLACE ADMISSION IS WRITTEN, called from the roster routes, from ``POST /users`` and from
    the admin seed script. That matters more than it looks: an administrator who creates an account
    through the users screen and does NOT get a roster row has created an account that cannot sign
    in, and they would find out about it from the new user, by telephone, a day later. Every path
    that mints a ``User`` goes through here.

    ``joinedAt`` IS STAMPED ONCE AND NEVER MOVED. It is the date of joining the platform; a person
    suspended in June and restored in September joined in March, and re-stamping on restore would
    quietly rewrite that. ``or existing.joinedAt`` — not the other way round — is what enforces it.
    """
    address = normalise_email(email)
    if not address:
        raise ValueError("An access roster entry needs an email address")

    now = datetime.now(UTC)
    role = role_value(granted_role) or None
    existing = await db.accessroster.find_unique(where={"email": address})
    if existing is not None:
        data: dict[str, Any] = {
            "status": ADMITTING_STATUS,
            "decidedAt": now,
            "decidedById": decided_by_id,
            "joinedAt": existing.joinedAt or joined_at or now,
        }
        if role:
            data["grantedRole"] = role
        if full_name is not None:
            data["fullName"] = clean_text(full_name, MAX_FULL_NAME_LENGTH)
        if notes is not None:
            data["notes"] = clean_text(notes, MAX_NOTES_LENGTH)
        return await db.accessroster.update(where={"email": address}, data=data)

    return await db.accessroster.create(
        data={
            "email": address,
            "status": ADMITTING_STATUS,
            "grantedRole": role or get_settings().default_signup_role,
            "fullName": clean_text(full_name, MAX_FULL_NAME_LENGTH),
            "notes": clean_text(notes, MAX_NOTES_LENGTH),
            "joinedAt": joined_at or now,
            "decidedAt": now,
            "decidedById": decided_by_id,
            # Zero, because nobody asked: this address was admitted by an administrator's decision
            # rather than by being turned away first. The distinction is visible on the roster.
            "requestCount": 0,
            "firstRequestedAt": now,
            "lastRequestedAt": now,
        }
    )


def clean_text(value: Any, limit: int) -> str | None:
    """Trim, cap, and turn an all-whitespace value into NULL rather than into ``" "``.

    A roster row whose ``fullName`` is a single space is not blank to any ``if row.fullName`` test in
    this codebase, so it renders as an empty cell that an admin cannot tell from a missing one.
    """
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return text[:limit]


# ---------------------------------------------------------------------------------------------
# Serialisation
# ---------------------------------------------------------------------------------------------


def roster_payload(row: Any, *, account: Any = None) -> dict[str, Any]:
    """One roster row as the two admin clients read it.

    ``account`` is the matching ``User`` when one exists, and it is passed in rather than looked up
    here because the list endpoint resolves them in ONE query for the whole page. The account's live
    ``role`` is surfaced alongside ``grantedRole`` on purpose: those two disagree the moment somebody
    is promoted through the users screen, and an admin reading only the roster would otherwise be
    looking at a stale tier and believe it current. See ``AccessRoster.grantedRole`` in the schema.
    """
    return {
        "id": row.id,
        "email": row.email,
        "status": status_value(row.status),
        "grantedRole": role_value(row.grantedRole),
        "fullName": row.fullName,
        "notes": row.notes,
        "joinedAt": _iso(row.joinedAt),
        "firstSeenAt": _iso(row.firstSeenAt),
        "requestCount": row.requestCount,
        "firstRequestedAt": _iso(row.firstRequestedAt),
        "lastRequestedAt": _iso(row.lastRequestedAt),
        "decidedAt": _iso(row.decidedAt),
        "decidedById": row.decidedById,
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
        # Null when the address has been admitted but nobody has ever signed in with it — which is
        # exactly the state an admin needs to be able to see.
        "userId": getattr(account, "id", None),
        "accountRole": role_value(getattr(account, "role", None)) or None,
        "accountName": getattr(account, "name", None),
    }


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None
