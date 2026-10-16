"""Request bodies for the access roster — the admin side of the sign-in gate.

``EmailStr`` on both bodies is doing real work here: an entry whose "email" is not an email is a row
that can never match a sign-in, sitting in the admin's list looking exactly like one that can, and
the person it was meant to admit is refused with no visible reason. ``max_length`` is set to 254
(RFC 5321's forward-path limit) to agree with ``access_roster.MAX_EMAIL_LENGTH``; a longer address
would be silently dropped by ``normalise_email`` on the way to the gate, so accepting it here would
create exactly the invisible-mismatch row this paragraph is about.
"""

from pydantic import EmailStr, Field, field_validator

from app.core.security import BCRYPT_MAX_PASSWORD_BYTES
from app.schemas.common import APIModel
from app.schemas.users import _storable_password
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
    # E-mail the address that it may now sign in. Honoured only when the row is admitted (ACTIVE)
    # and mail is configured; the web offers the choice only then.
    sendInvite: bool = False
    # WITH A PASSWORD (2026-10-16): when set, the account is created now with this password (the
    # same path as ``POST /users``, which also admits the address) instead of waiting for a first
    # Google, Microsoft or Yahoo sign-in. The person may still use either. ``mustChangePassword``
    # (default true) sends them to choose their own at the first sign-in.
    password: str | None = Field(default=None, min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
    mustChangePassword: bool = True

    @field_validator("password")
    @classmethod
    def password_fits_bcrypt(cls, value: str | None) -> str | None:
        return _storable_password(value)


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
    # With ``status: "ACTIVE"`` (the approval): e-mail the address that it may now sign in. Ignored
    # on every other change, and when mail is not configured. Not a column: nothing is stored.
    sendInvite: bool = False
