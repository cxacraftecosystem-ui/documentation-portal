"""Workshop linkage, assignment, and the late-submission approval gate.

Every record type now carries an optional ``workshopId`` — the workshop it was documented at. Two
rules ride on that link.

**Assignment.** A WorkshopAssignment row says what ONE user may do in ONE workshop. It carries an
``accessLevel`` (VIEW < CONTRIBUTE < EDIT) and a ``status`` (PENDING / GRANTED / DENIED / REVOKED),
and the SAME row serves both directions of the conversation: an admin granting access, and a user
asking for it. Only GRANTED confers anything — a PENDING request is not access — and DENIED/REVOKED
rows are kept rather than deleted, so a refusal stays on the record instead of being silently
re-requested away.

Whether a workshop is CURATED at all is a different question from whether it has rows, and getting
that wrong locks people out of workshops they have always been able to use. The rule is:

    a workshop is restricted only once an admin has deliberately put somebody on its roster —
    that is, it has a GRANTED row with an ``assignedById``.

Every other shape of row leaves the workshop open to everyone, exactly as it was before assignments
existed:

* no rows at all — every legacy workshop;
* only PENDING requests — so *asking* for access can never lock out the people who already had it;
* only DENIED rows — a refusal is not a lockdown either;
* rows that began as self-requests and were approved: :func:`decide` records ``decidedById`` but
  deliberately does NOT set ``assignedById``, because approving one person's request must not
  silently 403 the rest of a team that was already working in that workshop;
* an admin roster that has since been fully revoked. There is no "curated but empty" state to tell
  apart from "never curated", and emptying the roster is how ``PUT /assignments`` has always
  reopened a workshop — see :func:`set_workshop_assignments`.

On an OPEN workshop everyone implicitly holds CONTRIBUTE, and an explicit grant can only RAISE that,
never lower it: somebody granted VIEW on a workshop the whole team can already write to is not
demoted for having asked. On a RESTRICTED workshop only a GRANTED row confers access, at its level.

Assignments carry no date guard of their own: an admin may assign somebody long AFTER a workshop
ended, and that is precisely how post-workshop access is granted.

**Late submission.** A workshop is over once the whole of its ``endDate`` day has passed. A submission
made outside ``[startDate, endDate]`` is still accepted, but it is

  1. stamped into the record's ``extraMetadata.workshopSubmission`` as ``needsAdminApproval``, and
  2. FORCED to ``PENDING`` regardless of the submitter's own status rights, so a professor or above
     cannot self-approve their own late work.

Only an ADMIN or MASTER_ADMIN can approve a record carrying that flag (enforced in the review routes),
and approving it clears the flag. Clients can ask what a submission would mean before making it via
``GET /workshops/{id}/submission-check``, which returns :meth:`WorkshopSubmissionCheck.payload`.

Nothing here fires when a record carries no ``workshopId``: an omitted workshop behaves exactly as it
always has.
"""
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import get_value, is_admin


def _as_utc(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _iso(value: Any) -> str | None:
    dt = _as_utc(value)
    return dt.isoformat() if dt else None


# ------------------------------------------------------------------ access levels and statuses

# Strictly increasing capability WITHIN one workshop, so a check is a rank comparison rather than a
# set membership test and a new level can be slotted in without hunting down every call site.
WORKSHOP_LEVELS: tuple[str, ...] = ("VIEW", "CONTRIBUTE", "EDIT")
WORKSHOP_LEVEL_ORDER: dict[str, int] = {"VIEW": 1, "CONTRIBUTE": 2, "EDIT": 3}

# Shown in the grant/request UIs so nobody has to guess what a level actually confers.
WORKSHOP_LEVEL_DESCRIPTIONS: dict[str, str] = {
    "VIEW": "Read this workshop's data. Cannot create or change records in it.",
    "CONTRIBUTE": "Everything in View, plus create records in this workshop (the normal level).",
    "EDIT": "Everything in Contribute, plus change other researchers' records in this workshop "
    "(every change is tracked in the edit history).",
}

WORKSHOP_STATUSES: tuple[str, ...] = ("PENDING", "GRANTED", "DENIED", "REVOKED")
# Statuses an admin may hand down when deciding a row. PENDING is not one: only the requester
# creates a PENDING row, so an admin cannot park somebody back in the queue.
WORKSHOP_DECISIONS: tuple[str, ...] = ("GRANTED", "DENIED", "REVOKED")

# The level every authenticated user implicitly holds on an UNCURATED workshop. It is CONTRIBUTE and
# not EDIT: an open workshop means "anyone may add to it", never "anyone may rewrite anyone's work".
OPEN_WORKSHOP_LEVEL = "CONTRIBUTE"

# The level an admin hands out when they do not name one — the level the pre-levels PUT endpoint
# effectively granted, which is what keeps the Android app's replace-the-set call behaving as before.
DEFAULT_GRANT_LEVEL = "CONTRIBUTE"


def enum_str(value: Any) -> str | None:
    """Prisma may hand back an enum wrapper or a plain string depending on the column; normalize."""
    if value is None:
        return None
    return str(getattr(value, "value", value))


def level_rank(level: Any) -> int:
    """Rank of an access level; None/unknown ranks 0, below every real level."""
    return WORKSHOP_LEVEL_ORDER.get(enum_str(level) or "", 0)


def level_at_least(level: Any, minimum: str) -> bool:
    """True when ``level`` confers at least ``minimum``. A missing level never satisfies anything."""
    return level_rank(level) >= WORKSHOP_LEVEL_ORDER.get(minimum, 99)


def valid_level(level: Any) -> bool:
    return enum_str(level) in WORKSHOP_LEVEL_ORDER


@dataclass(frozen=True)
class WorkshopAccess:
    """What one user may do in one workshop, resolved from that workshop's assignment rows.

    ``level`` is the EFFECTIVE level (None = no access at all), which is not the same thing as
    ``rowLevel``: on an open workshop the implicit CONTRIBUTE wins over a lower granted level, and on
    a restricted one a non-GRANTED row confers nothing at all. ``rowStatus``/``rowLevel`` are kept
    alongside so a UI can explain WHY — "your request is still pending" reads very differently from
    "you were never assigned".
    """

    level: str | None = OPEN_WORKSHOP_LEVEL
    restricted: bool = False
    rowStatus: str | None = None
    rowLevel: str | None = None
    unrestricted: bool = False

    def at_least(self, minimum: str) -> bool:
        return level_at_least(self.level, minimum)


def workshop_is_curated(rows: Any) -> bool:
    """True when an admin deliberately put somebody on this workshop's roster.

    A GRANTED row with an ``assignedById`` is the ONLY thing that closes a workshop — see the module
    docstring for why a pending, denied, or self-requested-and-approved row must not. Takes the rows
    rather than an id so a caller that already loaded them does not query twice.
    """
    return any(enum_str(r.status) == "GRANTED" and get_value(r, "assignedById") for r in rows)


async def resolve_workshop_access(user: Any, workshop_id: str) -> WorkshopAccess:
    """Resolve ``user``'s effective level in ``workshop_id`` from the assignment rows.

    Admins are unrestricted and always resolve to EDIT: they are the approval authority, so gating
    them behind their own grant would make a workshop un-administrable.
    """
    rows = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    uid = get_value(user, "id")
    mine = next((r for r in rows if r.userId == uid), None)
    row_status = enum_str(mine.status) if mine is not None else None
    row_level = enum_str(mine.accessLevel) if mine is not None else None
    restricted = workshop_is_curated(rows)
    if is_admin(user):
        return WorkshopAccess("EDIT", restricted, row_status, row_level, unrestricted=True)
    granted = row_level if row_status == "GRANTED" else None
    if restricted:
        level = granted
    else:
        # Open workshop: the implicit level applies, and an explicit grant can only raise it.
        level = granted if level_rank(granted) > level_rank(OPEN_WORKSHOP_LEVEL) else OPEN_WORKSHOP_LEVEL
    return WorkshopAccess(level, restricted, row_status, row_level)


async def workshop_assignee_ids(workshop_id: str) -> set[str]:
    """The users who currently HOLD access to this workshop (GRANTED rows only).

    Status-aware on purpose: a pending, denied, or revoked row must never read as membership.
    """
    rows = await db.workshopassignment.find_many(where={"workshopId": workshop_id, "status": "GRANTED"})
    return {r.userId for r in rows}


async def workshop_edit_privilege(user: Any, workshop_id: str | None) -> bool:
    """True when ``user`` may change OTHER people's records in this workshop (EDIT level or admin).

    This is the workshop-scoped counterpart of the owner/admin/EDIT-grant test in
    ``services.access.guard_record_edit``, which knows nothing about workshops. Call it alongside
    that guard on any route that edits a workshop-linked record — see ``update_workshop`` — so an
    EDIT assignee is treated as a co-owner of the workshop's data rather than as an outside
    contributor who may only fill in blanks. Always pair it with ``record_revision`` so the elevation
    never costs the edit audit.
    """
    if not workshop_id:
        return False
    access = await resolve_workshop_access(user, workshop_id)
    return access.at_least("EDIT")


async def assert_workshop_access(user: Any, workshop_id: str | None, minimum: str = "CONTRIBUTE") -> WorkshopAccess:
    """Require at least ``minimum`` in ``workshop_id``, raising 403 with a reason that names the state.

    A no-op for a missing ``workshop_id`` — a record with no workshop link is unconstrained, exactly
    as before workshops carried access levels.
    """
    if not workshop_id:
        return WorkshopAccess()
    access = await resolve_workshop_access(user, workshop_id)
    if not access.at_least(minimum):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=access_denied_detail(access, minimum))
    return access


def access_denied_detail(access: WorkshopAccess, minimum: str) -> str:
    """The 403 text for a refused workshop action, phrased so the user knows their next move."""
    if access.level is None:
        if access.rowStatus == "PENDING":
            return (
                "Your access request for this workshop is still waiting on an admin decision. "
                "You cannot add to it until the request is approved."
            )
        if access.rowStatus == "DENIED":
            return "Your access request for this workshop was denied. Ask an admin if you need it reconsidered."
        if access.rowStatus == "REVOKED":
            return "Your access to this workshop was revoked. Request access again if you still need it."
        # Unchanged wording for the original case — clients and the Android app surface it verbatim.
        return "You are not assigned to this workshop. Ask an admin to assign you to it."
    return (
        f"Your access to this workshop is {str(access.level).lower()}-only, which does not allow this. "
        f"Ask an admin to raise it to {minimum.lower()}."
    )


def merge_extra(data: dict[str, Any], fragment: dict[str, Any] | None) -> dict[str, Any]:
    """Merge a metadata fragment into data['extraMetadata'] as a plain dict (call before provenance
    merge, which later Json-wraps it)."""
    if not fragment:
        return data
    existing = data.get("extraMetadata")
    base = dict(existing) if isinstance(existing, dict) else {}
    base.update(fragment)
    data["extraMetadata"] = base
    return data


@dataclass(frozen=True)
class WorkshopSubmissionCheck:
    """What submitting a record into one workshop means for one user, right now.

    The default instance describes "no workshop named" — nothing enforced, nothing stamped, which is
    the backwards-compatible path for every record submitted without a ``workshopId``.
    ``unrestricted`` marks an admin submitter: they see the true window/assignment facts but are
    neither blocked nor flagged, because they ARE the approval authority.
    """

    workshopId: str | None = None
    title: str | None = None
    startDate: str | None = None
    endDate: str | None = None
    isOver: bool = False
    outOfWindow: bool = False
    needsAdminApproval: bool = False
    assigned: bool = True
    canSubmit: bool = True
    submittedAt: str = ""
    unrestricted: bool = False
    # --- access-level facts. accessLevel is the EFFECTIVE level (None = no access); requestStatus is
    # the caller's own row status, which is what lets a UI say "still pending" rather than the flat
    # "not assigned". restricted says whether the workshop is curated at all.
    accessLevel: str | None = OPEN_WORKSHOP_LEVEL
    requestStatus: str | None = None
    restricted: bool = False
    canEdit: bool = False

    @property
    def metadata(self) -> dict[str, Any]:
        """The ``extraMetadata`` fragment to stamp on the record; empty when there is nothing to say."""
        if not self.workshopId or self.unrestricted:
            return {}
        return {
            "workshopSubmission": {
                "workshopId": self.workshopId,
                "outOfWindow": self.outOfWindow,
                "needsAdminApproval": self.needsAdminApproval,
                "submittedAt": self.submittedAt,
                "endDate": self.endDate,
                # windowStart/windowEnd are the original key names; kept so anything reading records
                # stamped before endDate joined the shape keeps working.
                "windowStart": self.startDate,
                "windowEnd": self.endDate,
            }
        }

    def payload(self) -> dict[str, Any]:
        """The client-facing pre-flight answer, so a UI can confirm a late submission before sending."""
        return {
            "workshopId": self.workshopId,
            "title": self.title,
            "endDate": self.endDate,
            "isOver": self.isOver,
            "outOfWindow": self.outOfWindow,
            "needsAdminApproval": self.needsAdminApproval,
            "assigned": self.assigned,
            "canSubmit": self.canSubmit,
            # Added, not swapped in: the four keys above are what every existing client reads.
            "accessLevel": self.accessLevel,
            "requestStatus": self.requestStatus,
            "restricted": self.restricted,
            "canEdit": self.canEdit,
        }


async def describe_workshop_submission(
    user: Any, workshop_id: str | None, *, when: datetime | None = None, need: str = "CONTRIBUTE"
) -> WorkshopSubmissionCheck:
    """Describe — WITHOUT raising on a permission problem — what submitting into ``workshop_id`` means.

    Raises 404 only when the workshop does not exist. Use this for the pre-flight endpoint; routes
    that actually write a record use :func:`enforce_workshop_submission`, which is this plus the 403.

    ``need`` is the level the intended action requires (CONTRIBUTE to create, EDIT to rewrite someone
    else's work) and only affects ``canSubmit``; ``assigned`` stays "has any access at all", so a
    VIEW-level user reads as assigned-but-unable-to-submit rather than as a stranger.
    """
    now = when or datetime.now(UTC)
    if not workshop_id:
        return WorkshopSubmissionCheck(submittedAt=now.isoformat())
    workshop = await db.workshop.find_unique(where={"id": workshop_id})
    if workshop is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop not found")
    start = _as_utc(workshop.startDate or workshop.date)
    end = _as_utc(workshop.endDate or workshop.date)
    # Inclusive window: the whole of the end day counts as in-window.
    window_end = end + timedelta(days=1) if end else None
    is_over = bool(window_end and now >= window_end)
    out_of_window = bool(start and window_end and (now < start or now >= window_end))
    admin = is_admin(user)
    access = await resolve_workshop_access(user, workshop_id)
    return WorkshopSubmissionCheck(
        workshopId=workshop_id,
        title=workshop.title,
        startDate=_iso(workshop.startDate or workshop.date),
        endDate=_iso(workshop.endDate or workshop.date),
        isOver=is_over,
        outOfWindow=out_of_window,
        needsAdminApproval=out_of_window and not admin,
        assigned=access.level is not None,
        canSubmit=access.at_least(need),
        submittedAt=now.isoformat(),
        unrestricted=admin,
        accessLevel=access.level,
        requestStatus=access.rowStatus,
        restricted=access.restricted,
        canEdit=access.at_least("EDIT"),
    )


async def enforce_workshop_submission(
    user: Any, workshop_id: str | None, *, when: datetime | None = None, need: str = "CONTRIBUTE"
) -> WorkshopSubmissionCheck:
    """Authorize ``user`` creating/moving a record into ``workshop_id``.

    Raises 403 when the caller's effective level in the workshop is below ``need`` — they hold no
    GRANTED row on a curated workshop, or they hold one at VIEW and are trying to write. Otherwise
    returns the check the route acts on: :func:`stamp_workshop_submission` writes its metadata, and
    :func:`pin_pending_if_late` applies the approval gate. Admins and an empty ``workshop_id`` pass
    through untouched.

    The signature and return type are load-bearing: six record routes call this positionally and read
    the returned check, so ``need`` is keyword-only with the create-time default.
    """
    check = await describe_workshop_submission(user, workshop_id, when=when, need=need)
    if not check.canSubmit:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=access_denied_detail(
                WorkshopAccess(check.accessLevel, check.restricted, check.requestStatus), need
            ),
        )
    return check


def stored_submission_stamp(record: Any) -> dict[str, Any] | None:
    """The ``workshopSubmission`` stamp already stored on a record, if any."""
    extra = get_value(record, "extraMetadata")
    stamp = extra.get("workshopSubmission") if isinstance(extra, dict) else None
    return stamp if isinstance(stamp, dict) else None


def record_needs_admin_approval(record: Any) -> bool:
    """True when this record was submitted late and no admin has approved it yet."""
    stamp = stored_submission_stamp(record)
    return bool(stamp and stamp.get("needsAdminApproval"))


def stamp_workshop_submission(
    data: dict[str, Any], check: WorkshopSubmissionCheck | None = None, record: Any = None
) -> dict[str, Any]:
    """Put the workshop-submission stamp into ``data['extraMetadata']`` as a plain dict.

    Call it AFTER ``guard_record_edit`` — the stamp is the API's own bookkeeping, and a contributor
    must never be 403'd for "changing" a populated ``extraMetadata`` they never touched — and BEFORE
    ``merge_field_provenance``, which Json-wraps the result.

    ``check`` writes a fresh stamp for a create or a workshop re-link. ``record`` carries an existing
    stamp forward on update: provenance rebuilds ``extraMetadata`` from the incoming payload, so an
    un-carried stamp would vanish on the next edit — and the stamp is exactly what keeps a late record
    pinned to PENDING until an admin approves it. A no-op when there is nothing to stamp or carry.

    The stamp is SERVER-OWNED. A ``workshopSubmission`` key arriving in the caller's ``extraMetadata``
    is never trusted: it is replaced by the authoritative value (fresh check, else the one already
    stored on the record) and dropped when there is no authoritative value. Without that, a record's
    own creator could clear their ``needsAdminApproval`` flag by PATCHing ``extraMetadata`` and then
    self-approve — or have a non-admin reviewer approve — their late submission.

    An unapproved late flag also SURVIVES a re-link. Re-pointing a record at a workshop that happens
    to be in-window produces a fresh check saying "not late", which would otherwise launder the flag
    away and leave the record freely approvable by a non-admin. Being moved does not make late work
    on-time; only an admin's approval clears the flag.
    """
    fragment = check.metadata if check is not None else {}
    if fragment:
        if record is not None and record_needs_admin_approval(record):
            fragment = {
                "workshopSubmission": {
                    **fragment["workshopSubmission"],
                    "needsAdminApproval": True,
                    # Keep why it was flagged: the new workshop is in-window, the old one was not.
                    "relinkedFrom": (stored_submission_stamp(record) or {}).get("workshopId"),
                }
            }
        return merge_extra(data, fragment)
    carried = stored_submission_stamp(record) if record is not None else None
    if carried is not None:
        return merge_extra(data, {"workshopSubmission": carried})
    existing = data.get("extraMetadata")
    if isinstance(existing, dict) and "workshopSubmission" in existing:
        data["extraMetadata"] = {k: v for k, v in existing.items() if k != "workshopSubmission"}
    return data


def pin_pending_if_late(
    data: dict[str, Any],
    user: Any,
    check: WorkshopSubmissionCheck | None = None,
    record: Any = None,
) -> dict[str, Any]:
    """Force ``status`` to PENDING when this submission is — or already was — flagged as late.

    Call it AFTER ``apply_status_policy_create`` / ``apply_status_policy_update`` so it OVERRIDES the
    submitter's own status rights: a professor or above who documents a workshop after it ended cannot
    approve their own record, and cannot later flip it to APPROVED by editing it either, because the
    stamp is carried forward on every update. Only an admin's review clears the flag.

    A no-op for admins (the approval authority) and for records with no status column (Craft).
    """
    if is_admin(user):
        return data
    late = bool(check is not None and check.needsAdminApproval)
    if not late and record is not None:
        late = record_needs_admin_approval(record)
    if not late:
        return data
    # Only touch status on models that actually have the column: an existing record exposes it as an
    # attribute, and a create payload always carries the client's requested status.
    if record is not None:
        if get_value(record, "status") is None:
            return data
    elif "status" not in data:
        return data
    data["status"] = "PENDING"
    return data


async def link_workshop_artisan(workshop_id: str | None, artisan_id: str) -> None:
    """Mirror an artisan's explicit ``workshopId`` into the WorkshopArtisan join table.

    The column is an ADDITIONAL link, not a replacement: every query that reads a workshop's artisans
    through the join must keep seeing an artisan created/updated with a workshopId. Idempotent, and
    never removes a link, so an admin-curated roster is only ever added to.
    """
    if not workshop_id or not artisan_id:
        return
    existing = await db.workshopartisan.find_unique(
        where={"workshopId_artisanId": {"workshopId": workshop_id, "artisanId": artisan_id}}
    )
    if existing is not None:
        return
    try:
        await db.workshopartisan.create(data={"workshopId": workshop_id, "artisanId": artisan_id})
    except UniqueViolationError:
        # A concurrent request won the race; the link exists either way.
        pass


async def link_workshop_craft(workshop_id: str | None, craft_id: str) -> None:
    """Mirror a craft's explicit ``workshopId`` into the WorkshopCraft join table (see
    :func:`link_workshop_artisan` — same contract, same idempotence)."""
    if not workshop_id or not craft_id:
        return
    existing = await db.workshopcraft.find_unique(
        where={"workshopId_craftId": {"workshopId": workshop_id, "craftId": craft_id}}
    )
    if existing is not None:
        return
    try:
        await db.workshopcraft.create(data={"workshopId": workshop_id, "craftId": craft_id})
    except UniqueViolationError:
        pass
