from pydantic import EmailStr, Field, field_validator

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


class UserCreate(APIModel):
    email: EmailStr
    name: str = Field(min_length=1, max_length=160)
    password: str = Field(min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
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


class UserUpdate(APIModel):
    email: EmailStr | None = None
    name: str | None = Field(default=None, min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
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
