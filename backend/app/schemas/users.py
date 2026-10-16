from typing import Literal

from pydantic import EmailStr, Field, field_validator, model_validator

from app.core.security import BCRYPT_MAX_PASSWORD_BYTES, password_storage_problem
from app.schemas.common import APIModel


def _storable_password(value: str | None) -> str | None:
    """Refuse a password bcrypt cannot store whole — a 422 here instead of a 500 from the hash.

    ``max_length`` on the fields counts CHARACTERS and is only the ceiling the byte rule implies:
    every character is at least one byte, so nothing longer than 72 characters can pass. The byte
    rule itself is ``security.password_storage_problem``, the same function ``hash_password`` checks,
    so the door and the hash cannot disagree about what fits. It was 256 characters until
    2026-10-09, when bcrypt 5 started refusing what passlib on bcrypt 4 used to cut off at 72 bytes
    without saying so. (Sign-in has no ceiling at all, deliberately: see ``LoginRequest``.)
    """
    if value is None:
        return value
    problem = password_storage_problem(value)
    if problem:
        raise ValueError(problem)
    return value


#: How a new account will sign in (``POST /users``, 2026-10-16):
#: SET — the administrator types a password (``password``); GENERATE — the server makes a strong
#: temporary one and answers it ONCE; NONE — no password, the person signs in with Google, Microsoft
#: or Yahoo (optionally e-mailed a link to set one too, ``sendPasswordLink``).
PasswordMode = Literal["SET", "GENERATE", "NONE"]


class UserCreate(APIModel):
    """Add a person in one step: the account, its admission to the access list, at one role.

    ``passwordMode`` defaults to SET when ``password`` is sent, which is what every client before it
    sent; with neither, the request does not say how the person will sign in and is refused (422).
    """

    email: EmailStr
    name: str = Field(min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
    passwordMode: PasswordMode | None = None
    #: A password one person typed for another is a shared secret: the account is sent to the
    #: change-password screen at its first sign-in unless the administrator says otherwise. Forced
    #: true for GENERATE; meaningless for NONE.
    mustChangePassword: bool = True
    #: NONE only: e-mail the person a link to set a password too (after the account is committed).
    sendPasswordLink: bool = False
    role: str = "RESEARCHER"
    canManageQuestionnaire: bool = False
    canManageCrafts: bool = False
    canManageWorkshops: bool = False
    canReview: bool = False
    canViewProvenance: bool = False
    canDownloadDataset: bool = False

    @field_validator("password")
    @classmethod
    def password_fits_bcrypt(cls, value: str | None) -> str | None:
        return _storable_password(value)

    @model_validator(mode="after")
    def one_way_to_sign_in(self) -> "UserCreate":
        if self.passwordMode is None:
            if self.password is None:
                raise ValueError("Choose how they will sign in: a password, a temporary password, or Google, Microsoft or Yahoo")
            self.passwordMode = "SET"
        if self.passwordMode == "SET" and not self.password:
            raise ValueError("Type the password they will sign in with")
        if self.passwordMode != "SET" and self.password is not None:
            raise ValueError("A password is sent only when you set one yourself")
        if self.passwordMode == "GENERATE":
            self.mustChangePassword = True
        if self.passwordMode != "NONE" and self.sendPasswordLink:
            raise ValueError("A password link is offered only for an account without a password")
        return self


class UserUpdate(APIModel):
    email: EmailStr | None = None
    name: str | None = Field(default=None, min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
    #: Sent beside ``password`` it decides whether the new password is temporary (left out, it is).
    #: Sent alone, ``true`` asks the account to choose a new password at its next sign-in and
    #: ``false`` withdraws that request.
    mustChangePassword: bool | None = None
    role: str | None = None
    canManageQuestionnaire: bool | None = None
    canManageCrafts: bool | None = None
    canManageWorkshops: bool | None = None
    canReview: bool | None = None
    canViewProvenance: bool | None = None
    canDownloadDataset: bool | None = None

    @field_validator("password")
    @classmethod
    def password_fits_bcrypt(cls, value: str | None) -> str | None:
        return _storable_password(value)
