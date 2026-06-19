from pydantic import EmailStr, Field

from app.schemas.common import APIModel


class UserCreate(APIModel):
    email: EmailStr
    name: str = Field(min_length=1, max_length=160)
    password: str = Field(min_length=8, max_length=256)
    role: str = "RESEARCHER"
    canManageQuestionnaire: bool = False
    canManageCrafts: bool = False
    canManageWorkshops: bool = False
    canReview: bool = False
    canViewProvenance: bool = False


class UserUpdate(APIModel):
    email: EmailStr | None = None
    name: str | None = Field(default=None, min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=256)
    role: str | None = None
    canManageQuestionnaire: bool | None = None
    canManageCrafts: bool | None = None
    canManageWorkshops: bool | None = None
    canReview: bool | None = None
    canViewProvenance: bool | None = None
