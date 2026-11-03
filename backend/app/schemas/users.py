from typing import Literal

from pydantic import EmailStr, Field, field_validator, model_validator

from app.core.security import BCRYPT_MAX_PASSWORD_BYTES, password_storage_problem
from app.schemas.common import APIModel


def _storable_password(value: str | None) -> str | None:
    if value is None:
        return value
    problem = password_storage_problem(value)
    if problem:
        raise ValueError(problem)
    return value


PasswordMode = Literal["SET", "GENERATE", "NONE"]


class UserCreate(APIModel):
    email: EmailStr
    name: str = Field(min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
    passwordMode: PasswordMode | None = None
    mustChangePassword: bool = True
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
