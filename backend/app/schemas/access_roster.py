from pydantic import EmailStr, Field, field_validator

from app.core.security import BCRYPT_MAX_PASSWORD_BYTES
from app.schemas.common import APIModel
from app.schemas.users import _storable_password
from app.services.access_roster import MAX_FULL_NAME_LENGTH, MAX_NOTES_LENGTH


class AccessRosterCreate(APIModel):
    email: EmailStr = Field(max_length=254)
    fullName: str | None = Field(default=None, max_length=MAX_FULL_NAME_LENGTH)
    notes: str | None = Field(default=None, max_length=MAX_NOTES_LENGTH)
    grantedRole: str | None = None
    isActive: bool = True
    sendInvite: bool = False
    password: str | None = Field(default=None, min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)
    mustChangePassword: bool = True

    @field_validator("password")
    @classmethod
    def password_fits_bcrypt(cls, value: str | None) -> str | None:
        return _storable_password(value)


class AccessRosterUpdate(APIModel):
    email: EmailStr | None = Field(default=None, max_length=254)
    status: str | None = None
    grantedRole: str | None = None
    fullName: str | None = Field(default=None, max_length=MAX_FULL_NAME_LENGTH)
    notes: str | None = Field(default=None, max_length=MAX_NOTES_LENGTH)
    sendInvite: bool = False
