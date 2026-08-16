"""Request bodies for the access roster — the admin side of the sign-in gate.

``EmailStr`` on both bodies is doing real work here: an entry whose "email" is not an email is a row
that can never match a sign-in, sitting in the admin's list looking exactly like one that can, and
the person it was meant to admit is refused with no visible reason. ``max_length`` is set to 254
(RFC 5321's forward-path limit) to agree with ``access_roster.MAX_EMAIL_LENGTH``; a longer address
would be silently dropped by ``normalise_email`` on the way to the gate, so accepting it here would
create exactly the invisible-mismatch row this paragraph is about.
"""

from pydantic import EmailStr, Field

from app.schemas.common import APIModel
from app.services.access_roster import MAX_FULL_NAME_LENGTH, MAX_NOTES_LENGTH


class AccessRosterCreate(APIModel):
    """Admit (or pre-admit) an address. No account is required and none is created."""

    email: EmailStr = Field(max_length=254)
    # ADMIN-WRITTEN ONLY. The sign-in path never sets these; see access_roster.record_access_request
    # for why nothing a stranger supplies is stored.
    fullName: str | None = Field(default=None, max_length=MAX_FULL_NAME_LENGTH)
    notes: str | None = Field(default=None, max_length=MAX_NOTES_LENGTH)
    # "All the users by default join as the lowest rung unless promoted there itself." Absent means
    # the bottom of the ladder; an admin choosing otherwise is the "promoted there itself" case, and
    # is bounded by their own tier in ``users.assert_role``.
    grantedRole: str | None = None
    # Defaults to admitting, because "add somebody to the allow list" is what this endpoint is for.
    # Passing ACTIVE=false creates a row that is on the list and refused — useful only for
    # pre-recording somebody who is not to be let in yet.
    isActive: bool = True


class AccessRosterUpdate(APIModel):
    """Approve, reject, suspend, restore, or correct a roster row.

    Every field is optional and ABSENCE MEANS "LEAVE IT ALONE" — the route reads
    ``model_dump(exclude_unset=True)``. A body that set every unmentioned column to its default
    would let an admin fixing a typo in a note silently re-approve somebody they had rejected.
    """

    email: EmailStr | None = Field(default=None, max_length=254)
    status: str | None = None
    grantedRole: str | None = None
    fullName: str | None = Field(default=None, max_length=MAX_FULL_NAME_LENGTH)
    notes: str | None = Field(default=None, max_length=MAX_NOTES_LENGTH)
